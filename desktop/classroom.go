package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/gorilla/websocket"
	bolt "go.etcd.io/bbolt"
	"io"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Floor struct {
	ParticipantID string    `json:"participantId"`
	ExpiresAt     time.Time `json:"expiresAt"`
}
type Room struct {
	ID            string       `json:"id"`
	Title         string       `json:"title"`
	Mode          string       `json:"mode"`
	Languages     []string     `json:"languages"`
	Capacity      int          `json:"capacity"`
	State         string       `json:"state"`
	GapCount      int          `json:"gapCount"`
	StartedAt     time.Time    `json:"startedAt"`
	TeacherInvite string       `json:"teacherInvite,omitempty"`
	StudentInvite string       `json:"studentInvite,omitempty"`
	TeacherURL    string       `json:"teacherURL,omitempty"`
	StudentURL    string       `json:"studentURL,omitempty"`
	Members       []MemberView `json:"members"`
}
type Channel struct {
	ID           string    `json:"id"`
	RoomID       string    `json:"roomId"`
	Kind         string    `json:"kind"`
	Title        string    `json:"title"`
	MemberIDs    []string  `json:"memberIds,omitempty"`
	Floor        Floor     `json:"floor"`
	Queue        []string  `json:"queue"`
	ContextNotes string    `json:"contextNotes,omitempty"`
	UpdatedAt    time.Time `json:"updatedAt"`
}
type Member struct {
	ID        string    `json:"id"`
	RoomID    string    `json:"roomId"`
	Name      string    `json:"name"`
	Role      string    `json:"role"`
	Language  string    `json:"language"`
	TokenHash string    `json:"tokenHash"`
	ExpiresAt time.Time `json:"expiresAt"`
	Revoked   bool      `json:"revoked"`
}
type MemberView struct {
	ID        string    `json:"id"`
	RoomID    string    `json:"roomId"`
	Name      string    `json:"name"`
	Role      string    `json:"role"`
	Language  string    `json:"language"`
	ExpiresAt time.Time `json:"expiresAt"`
	Online    bool      `json:"online"`
}

func (m Member) view(online bool) MemberView {
	return MemberView{m.ID, m.RoomID, m.Name, m.Role, m.Language, m.ExpiresAt, online}
}

type roomSubscriber struct {
	memberID, roomID, channelID, language string
	expiresAt                             time.Time
	ch                                    chan any
	done                                  chan struct{}
}
type RoomService struct {
	store       *Store
	pipeline    *Pipeline
	mu          sync.Mutex
	presence    map[string]time.Time
	subscribers map[*roomSubscriber]bool
	events      chan Line
	reset       chan struct{}
	connecting  int
}

func (a *App) classroomService() *RoomService {
	a.roomsMu.Lock()
	defer a.roomsMu.Unlock()
	if a.rooms != nil {
		return a.rooms
	}
	rs := &RoomService{store: a.store, pipeline: a.pipeline, presence: map[string]time.Time{}, subscribers: map[*roomSubscriber]bool{}, events: make(chan Line, 128), reset: make(chan struct{}, 1)}
	a.store.setLineNotifier(func(l Line) {
		select {
		case rs.events <- l:
		default:
			select {
			case rs.reset <- struct{}{}:
			default:
			}
		}
	})
	a.rooms = rs
	go func() {
		for {
			select {
			case <-a.pipeline.ctx.Done():
				return
			case line := <-rs.events:
				rs.publishLine(line)
			case <-rs.reset:
				rs.mu.Lock()
				for sub := range rs.subscribers {
					rs.dropLocked(sub)
				}
				rs.mu.Unlock()
			}
		}
	}()
	return rs
}
func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
func requestKey(memberID, channelID, requestID, kind string) string {
	sum := sha256.Sum256([]byte(memberID + "\x00" + channelID + "\x00" + requestID + "\x00" + kind))
	return hex.EncodeToString(sum[:16])
}
func (rs *RoomService) room(id string) (Room, error) {
	var r Room
	err := rs.store.get("rooms", id, &r)
	return r, err
}
func (rs *RoomService) updateRoom(id string, fn func(*Room)) (Room, error) {
	var room Room
	err := rs.store.db.Update(func(tx *bolt.Tx) error {
		if e := json.Unmarshal(tx.Bucket([]byte("rooms")).Get([]byte(id)), &room); e != nil {
			return e
		}
		fn(&room)
		raw, e := json.Marshal(room)
		if e != nil {
			return e
		}
		return tx.Bucket([]byte("rooms")).Put([]byte(id), raw)
	})
	return room, err
}
func (rs *RoomService) channel(id string) (Channel, error) {
	var c Channel
	err := rs.store.get("channels", id, &c)
	return c, err
}
func (rs *RoomService) saveChannelTx(tx *bolt.Tx, c Channel) error {
	c.UpdatedAt = time.Now().UTC()
	raw, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if c.Kind == "private" {
		raw, err = rs.store.protect(raw, recordAAD("channels", c.ID))
		if err != nil {
			return err
		}
	}
	return tx.Bucket([]byte("channels")).Put([]byte(c.ID), raw)
}
func (rs *RoomService) saveChannel(c Channel) error {
	return rs.store.db.Update(func(tx *bolt.Tx) error { return rs.saveChannelTx(tx, c) })
}
func (rs *RoomService) authenticate(token string) (Member, error) {
	if len(token) != 64 {
		return Member{}, errors.New("참여 인증이 필요합니다")
	}
	rs.mu.Lock()
	defer rs.mu.Unlock()
	var member Member
	err := rs.store.db.View(func(tx *bolt.Tx) error {
		id := tx.Bucket([]byte("member-auth")).Get([]byte(hashToken(token)))
		if id == nil {
			return errors.New("참여 인증이 유효하지 않습니다")
		}
		return json.Unmarshal(tx.Bucket([]byte("members")).Get(id), &member)
	})
	if err != nil {
		return member, err
	}
	if member.Revoked || time.Now().After(member.ExpiresAt) {
		return member, errors.New("참여 인증이 만료 또는 취소되었습니다")
	}
	rs.presence[member.ID] = time.Now()
	return member, nil
}
func (rs *RoomService) member(id string) (Member, error) {
	var m Member
	err := rs.store.get("members", id, &m)
	return m, err
}
func (rs *RoomService) channelAllowed(c Channel, m Member) bool {
	return c.RoomID == m.RoomID && (c.Kind == "broadcast" || slices.Contains(c.MemberIDs, m.ID))
}
func (rs *RoomService) publicRoom(r Room) Room {
	r.TeacherInvite = ""
	r.StudentInvite = ""
	r.TeacherURL = ""
	r.StudentURL = ""
	r.Members = []MemberView{}
	rs.mu.Lock()
	defer rs.mu.Unlock()
	_ = rs.store.db.View(func(tx *bolt.Tx) error {
		cursor := tx.Bucket([]byte("member-index")).Cursor()
		prefix := r.ID + ":"
		for k, v := cursor.Seek([]byte(prefix)); k != nil && strings.HasPrefix(string(k), prefix); k, v = cursor.Next() {
			var m Member
			if json.Unmarshal(tx.Bucket([]byte("members")).Get(v), &m) == nil && !m.Revoked && time.Now().Before(m.ExpiresAt) {
				online := time.Since(rs.presence[m.ID]) < 90*time.Second
				r.Members = append(r.Members, m.view(online))
			}
		}
		return nil
	})
	return r
}
func (rs *RoomService) createRoom(title, mode string, languages []string, capacity int) (Room, error) {
	title = strings.TrimSpace(title)
	if title == "" || len([]rune(title)) > 120 {
		return Room{}, errors.New("대화방 제목은 1~120자로 입력하세요")
	}
	if mode != "lecture" && mode != "conversation" {
		return Room{}, errors.New("강의 또는 대화 모드를 선택하세요")
	}
	if capacity < 1 || capacity > 512 {
		return Room{}, errors.New("정원은 1~512명으로 설정하세요. 설정값은 검증된 처리 용량이 아닙니다")
	}
	if len(languages) < 1 || len(languages) > 20 {
		return Room{}, errors.New("언어는 1~20개로 설정하세요")
	}
	seen := map[string]bool{}
	for _, lang := range languages {
		if !languagePattern.MatchString(lang) || seen[lang] {
			return Room{}, errors.New("언어 형식 또는 중복 오류")
		}
		seen[lang] = true
	}
	r := Room{ID: newID(), Title: title, Mode: mode, Languages: languages, Capacity: capacity, State: "active", StartedAt: time.Now().UTC(), TeacherInvite: newID() + newID(), StudentInvite: newID() + newID(), Members: []MemberView{}}
	c := Channel{ID: r.ID, RoomID: r.ID, Kind: "broadcast", Title: "전체 강의·공지", Queue: []string{}}
	session := Session{ID: r.ID, Kind: "classroom", Title: title, State: "active", SourceLanguage: languages[0], Targets: languages, StartedAt: r.StartedAt, UpdatedAt: r.StartedAt}
	err := rs.store.db.Update(func(tx *bolt.Tx) error {
		if tx.Bucket([]byte("rooms")).Stats().KeyN >= 100 {
			return errors.New("대화방 상한 100개에 도달했습니다")
		}
		for name, value := range map[string]any{"rooms": r, "sessions": session} {
			raw, e := json.Marshal(value)
			if e != nil {
				return e
			}
			if e = tx.Bucket([]byte(name)).Put([]byte(r.ID), raw); e != nil {
				return e
			}
		}
		return rs.saveChannelTx(tx, c)
	})
	return r, err
}
func (rs *RoomService) join(invite, name, language string) (Member, string, Room, error) {
	name = strings.TrimSpace(name)
	if len(invite) != 64 || name == "" || len([]rune(name)) > 40 {
		return Member{}, "", Room{}, errors.New("초대 링크와 1~40자의 이름을 확인하세요")
	}
	rs.mu.Lock()
	defer rs.mu.Unlock()
	var room Room
	role := ""
	err := rs.store.db.View(func(tx *bolt.Tx) error {
		return tx.Bucket([]byte("rooms")).ForEach(func(k, v []byte) error {
			var r Room
			if err := json.Unmarshal(v, &r); err != nil {
				return err
			}
			if equalToken(invite, r.TeacherInvite) {
				room = r
				role = "teacher"
			} else if equalToken(invite, r.StudentInvite) {
				room = r
				role = "student"
			}
			return nil
		})
	})
	if err != nil || role == "" {
		return Member{}, "", room, errors.New("초대 링크가 유효하지 않습니다")
	}
	if room.State != "active" {
		return Member{}, "", room, errors.New("종료된 대화방입니다")
	}
	if !slices.Contains(room.Languages, language) {
		return Member{}, "", room, errors.New("대화방에서 지원하는 언어를 선택하세요")
	}
	token := newID() + newID()
	m := Member{ID: newID(), RoomID: room.ID, Name: name, Role: role, Language: language, TokenHash: hashToken(token), ExpiresAt: time.Now().UTC().Add(24 * time.Hour)}
	err = rs.store.db.Update(func(tx *bolt.Tx) error {
		if tx.Bucket([]byte("members")).Stats().KeyN >= 4096 {
			return errors.New("보관 참여자 상한에 도달했습니다")
		}
		count := 0
		cur := tx.Bucket([]byte("member-index")).Cursor()
		prefix := room.ID + ":"
		for k, v := cur.Seek([]byte(prefix)); k != nil && strings.HasPrefix(string(k), prefix); k, v = cur.Next() {
			var old Member
			if json.Unmarshal(tx.Bucket([]byte("members")).Get(v), &old) == nil && !old.Revoked && time.Now().Before(old.ExpiresAt) && time.Since(rs.presence[old.ID]) < 90*time.Second {
				count++
			}
		}
		if count >= room.Capacity {
			return errors.New("대화방 정원에 도달했습니다")
		}
		raw, e := json.Marshal(m)
		if e != nil {
			return e
		}
		if e = tx.Bucket([]byte("members")).Put([]byte(m.ID), raw); e != nil {
			return e
		}
		if e = tx.Bucket([]byte("member-index")).Put([]byte(room.ID+":"+m.ID), []byte(m.ID)); e != nil {
			return e
		}
		return tx.Bucket([]byte("member-auth")).Put([]byte(m.TokenHash), []byte(m.ID))
	})
	if err == nil {
		rs.presence[m.ID] = time.Now()
	}
	return m, token, room, err
}
func selectedLine(line Line, language string) Line {
	tr := line.Translations[language]
	audio := line.Audio[language]
	problem := line.Errors[language]
	source := line.Audio["source"]
	line.Translations = map[string]string{}
	if tr != "" {
		line.Translations[language] = tr
	}
	line.Audio = map[string]string{}
	if source != "" {
		line.Audio["source"] = source
	}
	if audio != "" {
		line.Audio[language] = audio
	}
	line.Errors = map[string]string{}
	if problem != "" {
		line.Errors[language] = problem
	}
	return line
}
func lineAudioSource(line Line) string { return line.Audio["source"] }
func (rs *RoomService) dropLocked(sub *roomSubscriber) {
	if rs.subscribers[sub] {
		delete(rs.subscribers, sub)
		close(sub.done)
	}
}

// Authentication and subscription can be separated by leave or language change.
// Re-read authority while holding the same mutex as those mutations, so a stale
// authenticated Member cannot register after its subscriptions were revoked.
func (rs *RoomService) registerRoomSubscriber(authenticated Member, channelID string) (*roomSubscriber, Member, Channel, Room, error) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	m, err := rs.member(authenticated.ID)
	if err != nil || m.Revoked || !time.Now().Before(m.ExpiresAt) || m.TokenHash != authenticated.TokenHash || m.RoomID != authenticated.RoomID {
		return nil, Member{}, Channel{}, Room{}, errors.New("참여 인증이 만료 또는 취소되었습니다")
	}
	c, err := rs.channel(channelID)
	if err != nil || !rs.channelAllowed(c, m) {
		return nil, Member{}, Channel{}, Room{}, errors.New("현재 채널의 참여 권한이 없습니다")
	}
	r, err := rs.room(m.RoomID)
	if err != nil {
		return nil, Member{}, Channel{}, Room{}, err
	}
	count := 0
	for other := range rs.subscribers {
		if other.memberID == m.ID {
			count++
		}
	}
	if count >= 2 || len(rs.subscribers) >= 4096 {
		return nil, Member{}, Channel{}, Room{}, errors.New("참여 연결 상한에 도달했습니다")
	}
	sub := &roomSubscriber{memberID: m.ID, roomID: m.RoomID, channelID: c.ID, language: m.Language, expiresAt: m.ExpiresAt, ch: make(chan any, 32), done: make(chan struct{})}
	rs.subscribers[sub] = true
	rs.presence[m.ID] = time.Now()
	return sub, m, c, r, nil
}

func (rs *RoomService) roomSubscriberActive(sub *roomSubscriber) bool {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	if !rs.subscribers[sub] {
		return false
	}
	if !time.Now().Before(sub.expiresAt) {
		rs.dropLocked(sub)
		return false
	}
	return true
}

// A late pong must not recreate presence after leave or keep an obsolete
// language/ACL alive. This check runs on heartbeat, not per-recipient fanout.
func (rs *RoomService) refreshRoomSubscriber(sub *roomSubscriber) error {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	if !rs.subscribers[sub] {
		return errors.New("참여 연결이 종료되었습니다")
	}
	m, err := rs.member(sub.memberID)
	if err == nil && !m.Revoked && time.Now().Before(m.ExpiresAt) && m.RoomID == sub.roomID && m.Language == sub.language {
		var c Channel
		c, err = rs.channel(sub.channelID)
		if err == nil && rs.channelAllowed(c, m) {
			rs.presence[m.ID] = time.Now()
			return nil
		}
	}
	rs.dropLocked(sub)
	return errors.New("현재 참여 인증 또는 채널 권한이 변경되었습니다")
}
func (rs *RoomService) publishLine(line Line) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	for sub := range rs.subscribers {
		if sub.channelID != line.SessionID {
			continue
		}
		select {
		case sub.ch <- map[string]any{"type": "line", "line": selectedLine(line, sub.language)}:
		default:
			rs.dropLocked(sub)
		}
	}
}
func (rs *RoomService) publishChannel(c Channel) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	for sub := range rs.subscribers {
		if sub.channelID != c.ID {
			continue
		}
		select {
		case sub.ch <- map[string]any{"type": "channel", "channel": c}:
		default:
			rs.dropLocked(sub)
		}
	}
}
func (rs *RoomService) publishRoom(r Room) {
	view := rs.publicRoom(r)
	rs.mu.Lock()
	defer rs.mu.Unlock()
	for sub := range rs.subscribers {
		if sub.roomID != r.ID {
			continue
		}
		select {
		case sub.ch <- map[string]any{"type": "room", "room": view}:
		default:
			rs.dropLocked(sub)
		}
	}
}
func (rs *RoomService) channels(m Member) ([]Channel, error) {
	out := []Channel{}
	base, err := rs.channel(m.RoomID)
	if err != nil {
		return nil, err
	}
	out = append(out, base)
	err = rs.store.db.View(func(tx *bolt.Tx) error {
		cur := tx.Bucket([]byte("member-channels")).Cursor()
		prefix := m.ID + ":"
		for k, v := cur.Seek([]byte(prefix)); k != nil && strings.HasPrefix(string(k), prefix); k, v = cur.Next() {
			var c Channel
			if err := rs.store.decodeRecord("channels", string(v), tx.Bucket([]byte("channels")).Get(v), &c); err != nil {
				return err
			}
			if rs.channelAllowed(c, m) {
				out = append(out, c)
			}
		}
		return nil
	})
	return out, err
}
func (rs *RoomService) createPrivate(m Member, others []string) (Channel, error) {
	if len(others) != 1 || others[0] == m.ID {
		return Channel{}, errors.New("개인 대화 상대 한 명을 선택하세요")
	}
	peer, err := rs.member(others[0])
	if err != nil || peer.RoomID != m.RoomID || peer.Revoked || time.Now().After(peer.ExpiresAt) {
		return Channel{}, errors.New("같은 대화방의 유효한 상대를 선택하세요")
	}
	members := []string{m.ID, peer.ID}
	slices.Sort(members)
	c := Channel{ID: newID(), RoomID: m.RoomID, Kind: "private", Title: m.Name + " ↔ " + peer.Name, MemberIDs: members, Queue: []string{}}
	targets := []string{m.Language}
	if peer.Language != m.Language {
		targets = append(targets, peer.Language)
	}
	s := Session{ID: c.ID, Kind: "private", Title: "개인 대화", State: "active", SourceLanguage: m.Language, Targets: targets, StartedAt: time.Now().UTC(), UpdatedAt: time.Now().UTC()}
	err = rs.store.db.Update(func(tx *bolt.Tx) error {
		if tx.Bucket([]byte("channels")).Stats().KeyN >= 4096 {
			return errors.New("개인 채널 보관 상한에 도달했습니다")
		}
		if err := rs.saveChannelTx(tx, c); err != nil {
			return err
		}
		raw, e := json.Marshal(s)
		if e != nil {
			return e
		}
		if e = tx.Bucket([]byte("sessions")).Put([]byte(s.ID), raw); e != nil {
			return e
		}
		for _, id := range members {
			if e = tx.Bucket([]byte("member-channels")).Put([]byte(id+":"+c.ID), []byte(c.ID)); e != nil {
				return e
			}
		}
		return nil
	})
	if err == nil {
		rs.mu.Lock()
		for sub := range rs.subscribers {
			if slices.Contains(members, sub.memberID) {
				select {
				case sub.ch <- map[string]any{"type": "channelsChanged"}:
				default:
					rs.dropLocked(sub)
				}
			}
		}
		rs.mu.Unlock()
	}
	return c, err
}
func (rs *RoomService) floor(m Member, r Room, c Channel, action, target string) (Channel, error) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	var err error
	c, err = rs.channel(c.ID)
	if err != nil {
		return c, err
	}
	now := time.Now().UTC()
	if now.After(c.Floor.ExpiresAt) {
		c.Floor = Floor{}
	}
	switch action {
	case "request":
		if c.Floor.ParticipantID == m.ID {
			c.Floor.ExpiresAt = now.Add(45 * time.Second)
		} else if (c.Kind == "broadcast" && m.Role == "teacher") || (c.Floor.ParticipantID == "" && (c.Kind == "private" || r.Mode == "conversation")) {
			c.Floor = Floor{m.ID, now.Add(45 * time.Second)}
			c.Queue = slices.DeleteFunc(c.Queue, func(id string) bool { return id == m.ID })
		} else if !slices.Contains(c.Queue, m.ID) {
			if len(c.Queue) >= r.Capacity {
				return c, errors.New("발언 대기열이 가득 찼습니다")
			}
			c.Queue = append(c.Queue, m.ID)
		}
	case "release":
		c.Queue = slices.DeleteFunc(c.Queue, func(id string) bool { return id == m.ID })
		if c.Floor.ParticipantID == m.ID {
			c.Floor = Floor{}
		}
	case "grant":
		if m.Role != "teacher" || c.Kind != "broadcast" {
			return c, errors.New("전체 강의의 발언 승인 권한이 없습니다")
		}
		peer, e := rs.member(target)
		if e != nil || peer.RoomID != r.ID || peer.Revoked || time.Now().After(peer.ExpiresAt) {
			return c, errors.New("유효한 대화방 참가자를 선택하세요")
		}
		c.Floor = Floor{target, now.Add(45 * time.Second)}
		c.Queue = slices.DeleteFunc(c.Queue, func(id string) bool { return id == target })
	default:
		return c, errors.New("발언 요청 동작 오류")
	}
	if c.Floor.ParticipantID == "" && (c.Kind == "private" || r.Mode == "conversation") {
		for len(c.Queue) > 0 {
			id := c.Queue[0]
			c.Queue = c.Queue[1:]
			if time.Since(rs.presence[id]) < 90*time.Second {
				c.Floor = Floor{id, now.Add(45 * time.Second)}
				break
			}
		}
	}
	return c, rs.saveChannel(c)
}
func (rs *RoomService) ownsFloor(m Member, c Channel) error {
	if c.Floor.ParticipantID != m.ID || time.Now().After(c.Floor.ExpiresAt) {
		return errors.New("현재 채널의 발언권을 먼저 요청하세요")
	}
	if rs.store.queueDepth() > 256 {
		return errors.New("처리 대기열이 가득 찼습니다. 잠시 후 다시 전송하세요")
	}
	return nil
}
func (rs *RoomService) audio(m Member, c Channel, id string, wav []byte) (Job, error) {
	if !validID(id) || len(wav) > 320044 {
		return Job{}, errors.New("요청 ID와 최대 10초 PCM WAV를 확인하세요")
	}
	if err := validateWAV(wav); err != nil {
		return Job{}, err
	}
	key := requestKey(m.ID, c.ID, id, "audio")
	sum := sha256.Sum256(wav)
	digest := hex.EncodeToString(sum[:])
	rs.mu.Lock()
	defer rs.mu.Unlock()
	var old Job
	if rs.store.get("jobs", key, &old) == nil {
		if old.SessionID != c.ID || old.SpeakerID != m.ID || old.PayloadSHA256 != digest {
			return old, errors.New("같은 요청 ID의 음성 내용이 다릅니다")
		}
		return old, nil
	}
	current, err := rs.channel(c.ID)
	if err != nil {
		return Job{}, err
	}
	if err = rs.ownsFloor(m, current); err != nil {
		return Job{}, err
	}
	name := key + "-input.wav"
	if err = rs.store.writeRecording(c.ID, name, wav); err != nil {
		return Job{}, err
	}
	path, _ := rs.store.recordingPath(c.ID, name)
	j := Job{SpeechMetadata: SpeechMetadata{m.Language, m.ID, m.Name, m.Role}, ID: key, SessionID: c.ID, Kind: "stt", Path: path, State: "queued", CreatedAt: time.Now().UTC(), PayloadSHA256: digest}
	if err = rs.store.put("jobs", j.ID, j); err != nil {
		return j, err
	}
	current.Floor.ExpiresAt = time.Now().UTC().Add(45 * time.Second)
	return j, rs.saveChannel(current)
}
func (rs *RoomService) text(m Member, c Channel, id, text string) (Line, error) {
	text = strings.TrimSpace(text)
	if !validID(id) || text == "" || len(text) > 8192 {
		return Line{}, errors.New("요청 ID와 1~8192바이트 원문을 확인하세요")
	}
	key := requestKey(m.ID, c.ID, id, "text")
	rs.mu.Lock()
	defer rs.mu.Unlock()
	current, err := rs.channel(c.ID)
	if err != nil {
		return Line{}, err
	}
	var old Line
	if rs.store.get("lines", key, &old) == nil {
		if old.SessionID != c.ID || old.SpeakerID != m.ID || old.SourceText != text {
			return old, errors.New("같은 요청 ID의 내용이 다릅니다")
		}
		return old, nil
	}
	if err = rs.ownsFloor(m, current); err != nil {
		return Line{}, err
	}
	l, err := rs.store.newSpeakerLine(c.ID, text, key, SpeechMetadata{m.Language, m.ID, m.Name, m.Role})
	if err != nil {
		return l, err
	}
	current.Floor.ExpiresAt = time.Now().UTC().Add(45 * time.Second)
	return l, rs.saveChannel(current)
}
func (a *App) roomURLs(r Room) Room {
	base := a.config().PublicURL
	if base == "" {
		scheme := "http"
		if a.config().TLSCert != "" {
			scheme = "https"
		}
		base = scheme + "://" + a.config().PublicBind
	}
	r.TeacherURL = strings.TrimRight(base, "/") + "/room/#invite=" + url.QueryEscape(r.TeacherInvite)
	r.StudentURL = strings.TrimRight(base, "/") + "/room/#invite=" + url.QueryEscape(r.StudentInvite)
	return r
}
func (a *App) adminRooms(w http.ResponseWriter, r *http.Request, path string) bool {
	if path != "/rooms" && !strings.HasPrefix(path, "/rooms/") {
		return false
	}
	rs := a.classroomService()
	if path == "/rooms" && r.Method == "GET" {
		rows, err := a.store.all("rooms")
		if err != nil {
			apiError(w, err)
			return true
		}
		rooms := []Room{}
		for _, raw := range rows {
			var room Room
			_ = json.Unmarshal(raw, &room)
			view := rs.publicRoom(room)
			view.TeacherInvite = room.TeacherInvite
			view.StudentInvite = room.StudentInvite
			rooms = append(rooms, a.roomURLs(view))
		}
		jsonReply(w, 200, rooms)
		return true
	}
	if path == "/rooms" && r.Method == "POST" {
		var in struct {
			Title     string   `json:"title"`
			Mode      string   `json:"mode"`
			Languages []string `json:"languages"`
			Capacity  int      `json:"capacity"`
		}
		if err := readJSON(w, r, &in); err != nil {
			apiError(w, err)
			return true
		}
		room, err := rs.createRoom(in.Title, in.Mode, in.Languages, in.Capacity)
		if err != nil {
			apiError(w, err)
		} else {
			jsonReply(w, 201, a.roomURLs(room))
		}
		return true
	}
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) != 3 {
		http.NotFound(w, r)
		return true
	}
	room, err := rs.room(parts[1])
	if err != nil {
		http.NotFound(w, r)
		return true
	}
	switch {
	case parts[2] == "lines" && r.Method == "GET":
		after, _ := strconv.ParseUint(r.URL.Query().Get("after"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		lines, e := a.store.lines(room.ID, after, limit)
		if e != nil {
			apiError(w, e)
		} else {
			jsonReply(w, 200, lines)
		}
	case (parts[2] == "close" || parts[2] == "rotate") && r.Method == "POST":
		room, e := rs.updateRoom(room.ID, func(current *Room) {
			if parts[2] == "close" {
				current.State = "closed"
			} else {
				current.TeacherInvite = newID() + newID()
				current.StudentInvite = newID() + newID()
			}
		})
		if e != nil {
			apiError(w, e)
		} else {
			rs.publishRoom(room)
			jsonReply(w, 200, a.roomURLs(room))
		}
	default:
		http.NotFound(w, r)
	}
	return true
}
func securePrivate(r *http.Request) bool { return r.TLS != nil || loopback(peerIP(r)) }
func (a *App) classroomAPI(w http.ResponseWriter, r *http.Request) {
	if !securePrivate(r) {
		http.Error(w, "Participant authentication requires HTTPS outside localhost", 403)
		return
	}
	rs := a.classroomService()
	path := strings.TrimPrefix(r.URL.Path, "/api/classroom")
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if path == "/join" && r.Method == "POST" {
		if !a.requests.allowBudget("join:"+peerIP(r), 1024) {
			http.Error(w, "Too many joins", 429)
			return
		}
		var in struct {
			Invite   string `json:"invite"`
			Name     string `json:"name"`
			Language string `json:"language"`
		}
		if err := readJSON(w, r, &in); err != nil {
			apiError(w, err)
			return
		}
		m, token, room, err := rs.join(in.Invite, in.Name, in.Language)
		if err != nil {
			apiError(w, err)
			return
		}
		rs.publishRoom(room)
		jsonReply(w, 201, map[string]any{"token": token, "participant": m.view(true), "room": rs.publicRoom(room)})
		return
	}
	if path == "/events" {
		a.roomEvents(w, r)
		return
	}
	m, err := rs.authenticate(bearer(r))
	if err != nil {
		http.Error(w, "Unauthorized", 401)
		return
	}
	if !a.requests.allowBudget("member:"+m.ID, 360) {
		http.Error(w, "Too many requests", 429)
		return
	}
	room, err := rs.room(m.RoomID)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if path == "/rooms" && r.Method == "GET" {
		jsonReply(w, 200, []Room{rs.publicRoom(room)})
		return
	}
	if len(parts) < 2 || parts[0] != m.RoomID {
		http.Error(w, "Forbidden", 403)
		return
	}
	action := parts[1]
	if action == "status" && r.Method == "GET" {
		jsonReply(w, 200, map[string]any{"room": rs.publicRoom(room), "participant": m.view(true), "engine": a.pipeline.engine.Ready(), "queueDepth": a.store.queueDepth()})
		return
	}
	if action == "channels" {
		if r.Method == "GET" {
			channels, e := rs.channels(m)
			if e != nil {
				apiError(w, e)
			} else {
				if !securePrivate(r) {
					channels = slices.DeleteFunc(channels, func(c Channel) bool { return c.Kind == "private" })
				}
				jsonReply(w, 200, channels)
			}
			return
		}
		if r.Method == "POST" {
			if !securePrivate(r) {
				http.Error(w, "Private channels require HTTPS", 403)
				return
			}
			if room.State != "active" {
				http.Error(w, "Room closed", 410)
				return
			}
			var in struct {
				ParticipantIDs []string `json:"participantIds"`
			}
			if e := readJSON(w, r, &in); e != nil {
				apiError(w, e)
				return
			}
			channel, e := rs.createPrivate(m, in.ParticipantIDs)
			if e != nil {
				apiError(w, e)
			} else {
				jsonReply(w, 201, channel)
			}
			return
		}
	}
	if action == "language" && r.Method == "POST" {
		var in struct {
			Language string `json:"language"`
		}
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		if !slices.Contains(room.Languages, in.Language) {
			apiError(w, errors.New("대화방 지원 언어를 선택하세요"))
			return
		}
		m.Language = in.Language
		if e := rs.changeLanguage(m); e != nil {
			apiError(w, e)
			return
		}
		rs.publishRoom(room)
		jsonReply(w, 200, m.view(true))
		return
	}
	if action == "leave" && r.Method == "POST" {
		if e := rs.leave(m); e != nil {
			apiError(w, e)
		} else {
			rs.publishRoom(room)
			jsonReply(w, 200, map[string]bool{"left": true})
		}
		return
	}
	if action == "mode" && r.Method == "POST" {
		if m.Role != "teacher" {
			http.Error(w, "Forbidden", 403)
			return
		}
		var in struct {
			Mode string `json:"mode"`
		}
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		if in.Mode != "lecture" && in.Mode != "conversation" {
			apiError(w, errors.New("모드 오류"))
			return
		}
		room, e := rs.updateRoom(room.ID, func(current *Room) { current.Mode = in.Mode })
		if e != nil {
			apiError(w, e)
		} else {
			rs.publishRoom(room)
			jsonReply(w, 200, rs.publicRoom(room))
		}
		return
	}
	channelID := r.URL.Query().Get("channelId")
	if channelID == "" {
		channelID = r.Header.Get("X-Channel-ID")
	}
	var command struct {
		Action        string `json:"action"`
		ParticipantID string `json:"participantId"`
		Text          string `json:"text"`
		RequestID     string `json:"requestId"`
		ChannelID     string `json:"channelId"`
		Notes         string `json:"notes"`
	}
	if r.Method == "POST" && action != "audio" {
		if e := readJSON(w, r, &command); e != nil {
			apiError(w, e)
			return
		}
		if command.ChannelID != "" {
			channelID = command.ChannelID
		}
	}
	if channelID == "" {
		apiError(w, errors.New("대상 채널을 명시적으로 선택하세요"))
		return
	}
	channel, err := rs.channel(channelID)
	if err != nil || !rs.channelAllowed(channel, m) {
		http.Error(w, "Forbidden", 403)
		return
	}
	if channel.Kind == "private" && !securePrivate(r) {
		http.Error(w, "Private channels require HTTPS", 403)
		return
	}
	if r.Method == "POST" && room.State != "active" {
		http.Error(w, "Room closed", 410)
		return
	}
	switch {
	case action == "floor" && r.Method == "POST":
		c, e := rs.floor(m, room, channel, command.Action, command.ParticipantID)
		if e != nil {
			apiError(w, e)
		} else {
			rs.publishChannel(c)
			jsonReply(w, 200, c)
		}
	case action == "text" && r.Method == "POST":
		line, e := rs.text(m, channel, command.RequestID, command.Text)
		if e != nil {
			apiError(w, e)
		} else {
			jsonReply(w, 201, selectedLine(line, m.Language))
		}
	case action == "audio" && r.Method == "POST":
		r.Body = http.MaxBytesReader(w, r.Body, 320044)
		wav, e := io.ReadAll(r.Body)
		if e != nil {
			apiError(w, e)
			return
		}
		job, e := rs.audio(m, channel, r.Header.Get("X-Request-ID"), wav)
		if e != nil {
			apiError(w, e)
		} else {
			jsonReply(w, 202, job)
		}
	case action == "lines" && r.Method == "GET":
		after, _ := strconv.ParseUint(r.URL.Query().Get("after"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		lines, e := a.store.lines(channel.ID, after, limit)
		if e != nil {
			apiError(w, e)
		} else {
			for i := range lines {
				lines[i] = selectedLine(lines[i], m.Language)
			}
			jsonReply(w, 200, lines)
		}
	case action == "audio" && r.Method == "GET" && len(parts) == 3:
		a.classroomAudio(w, r, m, channel, parts[2])
	case action == "context" && r.Method == "POST":
		if channel.Kind == "broadcast" && m.Role != "teacher" {
			http.Error(w, "Forbidden", 403)
			return
		}
		if len([]rune(command.Notes)) > 512 {
			apiError(w, errors.New("채널 문맥 메모는 512자 이하로 입력하세요"))
			return
		}
		rs.mu.Lock()
		latest, e := rs.channel(channel.ID)
		if e == nil {
			latest.ContextNotes = command.Notes
			e = rs.saveChannel(latest)
		}
		rs.mu.Unlock()
		channel = latest
		if e != nil {
			apiError(w, e)
		} else {
			rs.publishChannel(channel)
			jsonReply(w, 200, channel)
		}
	case action == "export" && r.Method == "GET":
		a.classroomExport(w, r, m, channel)
	default:
		http.NotFound(w, r)
	}
}
func (rs *RoomService) changeLanguage(m Member) error {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	return rs.store.db.Update(func(tx *bolt.Tx) error {
		var current Member
		if err := json.Unmarshal(tx.Bucket([]byte("members")).Get([]byte(m.ID)), &current); err != nil {
			return err
		}
		if current.Revoked || time.Now().After(current.ExpiresAt) {
			return errors.New("참여 인증이 취소되었습니다")
		}
		current.Language = m.Language
		m = current
		raw, err := json.Marshal(m)
		if err != nil {
			return err
		}
		if err = tx.Bucket([]byte("members")).Put([]byte(m.ID), raw); err != nil {
			return err
		}
		cur := tx.Bucket([]byte("member-channels")).Cursor()
		prefix := m.ID + ":"
		for k, v := cur.Seek([]byte(prefix)); k != nil && strings.HasPrefix(string(k), prefix); k, v = cur.Next() {
			var c Channel
			if err = rs.store.decodeRecord("channels", string(v), tx.Bucket([]byte("channels")).Get(v), &c); err != nil {
				return err
			}
			targets := []string{}
			for _, id := range c.MemberIDs {
				var peer Member
				if json.Unmarshal(tx.Bucket([]byte("members")).Get([]byte(id)), &peer) == nil && !slices.Contains(targets, peer.Language) {
					targets = append(targets, peer.Language)
				}
			}
			var session Session
			if err = json.Unmarshal(tx.Bucket([]byte("sessions")).Get(v), &session); err != nil {
				return err
			}
			session.Targets = targets
			raw, err = json.Marshal(session)
			if err != nil {
				return err
			}
			if err = tx.Bucket([]byte("sessions")).Put(v, raw); err != nil {
				return err
			}
		}
		for sub := range rs.subscribers {
			if sub.memberID == m.ID {
				rs.dropLocked(sub)
			}
		}
		return nil
	})
}
func (rs *RoomService) leave(m Member) error {
	channels, err := rs.channels(m)
	if err != nil {
		return err
	}
	rs.mu.Lock()
	changed := []Channel{}
	defer func() {
		rs.mu.Unlock()
		for _, c := range changed {
			rs.publishChannel(c)
		}
	}()
	m, err = rs.member(m.ID)
	if err != nil {
		return err
	}
	m.Revoked = true
	if err = rs.store.put("members", m.ID, m); err != nil {
		return err
	}
	delete(rs.presence, m.ID)
	for sub := range rs.subscribers {
		if sub.memberID == m.ID {
			rs.dropLocked(sub)
		}
	}
	for _, old := range channels {
		c, e := rs.channel(old.ID)
		if e != nil {
			return e
		}
		c.Queue = slices.DeleteFunc(c.Queue, func(id string) bool { return id == m.ID })
		if c.Floor.ParticipantID == m.ID {
			c.Floor = Floor{}
		}
		if err = rs.saveChannel(c); err != nil {
			return err
		}
		changed = append(changed, c)
	}
	return nil
}
func (a *App) classroomAudio(w http.ResponseWriter, r *http.Request, m Member, c Channel, name string) {
	if !strings.HasSuffix(name, ".wav") || len(name) < 38 || strings.ContainsAny(name, "/\\") {
		http.NotFound(w, r)
		return
	}
	lineID := name[:32]
	if strings.HasSuffix(name, "-input.wav") {
		lineID = name[:30] + "00"
	}
	var line Line
	if err := a.store.get("lines", lineID, &line); err != nil || line.SessionID != c.ID || (line.Audio["source"] != name && line.Audio[m.Language] != name) {
		http.NotFound(w, r)
		return
	}
	data, err := a.store.readRecording(c.ID, name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	http.ServeContent(w, r, name, line.CapturedAt, bytes.NewReader(data))
}
func (a *App) classroomExport(w http.ResponseWriter, r *http.Request, m Member, c Channel) {
	format := r.URL.Query().Get("format")
	if format == "" {
		format = "json"
	}
	if format != "json" && format != "txt" {
		apiError(w, errors.New("참가자 내보내기는 JSON 또는 TXT를 선택하세요"))
		return
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="MCastTalk-%s.%s"`, c.ID, format))
	if format == "txt" {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_ = a.walkLines(c.ID, func(line Line) error {
			fmt.Fprintf(w, "[%s] %s (%s): %s\n[%s] %s\n\n", line.CapturedAt.Format(time.RFC3339), line.SpeakerName, line.SourceLanguage, line.SourceText, m.Language, line.Translations[m.Language])
			return nil
		})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	io.WriteString(w, "{\"version\":\""+Version+"\",\"channelKind\":\""+c.Kind+"\",\"lines\":[")
	first := true
	_ = a.walkLines(c.ID, func(line Line) error {
		if !first {
			io.WriteString(w, ",")
		}
		first = false
		raw, err := json.Marshal(selectedLine(line, m.Language))
		if err != nil {
			return err
		}
		_, err = w.Write(raw)
		return err
	})
	io.WriteString(w, "]}")
}
func (a *App) roomEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" || !validOrigin(r, true) || !securePrivate(r) {
		http.Error(w, "Invalid Origin", 403)
		return
	}
	rs := a.classroomService()
	// Bound unauthenticated handshakes globally while allowing a large class behind NAT.
	rs.mu.Lock()
	if rs.connecting >= 1024 || len(rs.subscribers) >= 4096 {
		rs.mu.Unlock()
		http.Error(w, "Connection capacity reached", http.StatusServiceUnavailable)
		return
	}
	rs.connecting++
	rs.mu.Unlock()
	var releaseOnce sync.Once
	releaseHandshake := func() {
		releaseOnce.Do(func() { rs.mu.Lock(); rs.connecting--; rs.mu.Unlock() })
	}
	defer releaseHandshake()
	up := websocket.Upgrader{CheckOrigin: func(q *http.Request) bool { return validOrigin(q, true) }, ReadBufferSize: 1024, WriteBufferSize: 8192}
	conn, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()
	conn.SetReadLimit(4096)
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	var hello struct {
		Token     string `json:"token"`
		ChannelID string `json:"channelId"`
	}
	if err = conn.ReadJSON(&hello); err != nil {
		return
	}
	releaseHandshake()
	m, err := rs.authenticate(hello.Token)
	if err != nil {
		return
	}
	if hello.ChannelID == "" {
		return
	}
	sub, m, c, room, err := rs.registerRoomSubscriber(m, hello.ChannelID)
	if err != nil {
		return
	}
	defer func() { rs.mu.Lock(); rs.dropLocked(sub); rs.mu.Unlock() }()
	lines, err := a.store.recentLines(c.ID, 0, 50)
	if err != nil {
		return
	}
	for i := range lines {
		lines[i] = selectedLine(lines[i], m.Language)
	}
	if !rs.roomSubscriberActive(sub) {
		return
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if err = conn.WriteJSON(map[string]any{"type": "snapshot", "room": rs.publicRoom(room), "participant": m.view(true), "channel": c, "lines": lines}); err != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	conn.SetPongHandler(func(string) error {
		if err := rs.refreshRoomSubscriber(sub); err != nil {
			return err
		}
		return conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	})
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		for {
			if _, _, e := conn.ReadMessage(); e != nil {
				return
			}
		}
	}()
	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-a.pipeline.ctx.Done():
			return
		case <-readDone:
			return
		case <-sub.done:
			return
		case event := <-sub.ch:
			if !rs.roomSubscriberActive(sub) {
				return
			}
			_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if err = conn.WriteJSON(event); err != nil {
				return
			}
		case <-ping.C:
			if rs.refreshRoomSubscriber(sub) != nil {
				return
			}
			_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if err = conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
func (rs *RoomService) recoverChannels() error {
	return rs.store.db.Update(func(tx *bolt.Tx) error {
		var updates []Channel
		err := tx.Bucket([]byte("channels")).ForEach(func(k, v []byte) error {
			var c Channel
			if e := rs.store.decodeRecord("channels", string(k), v, &c); e != nil {
				return e
			}
			c.Floor = Floor{}
			c.Queue = []string{}
			updates = append(updates, c)
			return nil
		})
		if err != nil {
			return err
		}
		for _, c := range updates {
			if err = rs.saveChannelTx(tx, c); err != nil {
				return err
			}
			var session Session
			raw := tx.Bucket([]byte("sessions")).Get([]byte(c.ID))
			if json.Unmarshal(raw, &session) == nil && session.State == "interrupted" {
				session.State = "active"
				value, _ := json.Marshal(session)
				if err = tx.Bucket([]byte("sessions")).Put([]byte(session.ID), value); err != nil {
					return err
				}
				if c.Kind == "broadcast" {
					var room Room
					if json.Unmarshal(tx.Bucket([]byte("rooms")).Get([]byte(c.RoomID)), &room) == nil {
						room.GapCount = session.GapCount
						value, _ = json.Marshal(room)
						if err = tx.Bucket([]byte("rooms")).Put([]byte(room.ID), value); err != nil {
							return err
						}
					}
				}
			}
		}
		return nil
	})
}
