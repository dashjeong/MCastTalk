package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	bolt "go.etcd.io/bbolt"
)

type Store struct {
	db         *bolt.DB
	dir        string
	notifyMu   sync.RWMutex
	onLine     func(Line)
	privateKey []byte
}

var bucketNames = []string{"settings", "assets", "sessions", "lines", "line-index", "jobs", "job-index", "glossary", "scripts", "lessons", "lesson-index", "rooms", "members", "member-index", "member-auth", "channels", "member-channels"}

func newID() string {
	b := make([]byte, 16)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func validID(s string) bool {
	if len(s) != 32 {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}
func openStore(dir string) (*Store, error) {
	if err := os.MkdirAll(filepath.Join(dir, "recordings"), 0700); err != nil {
		return nil, err
	}
	db, err := bolt.Open(filepath.Join(dir, "state.db"), 0600, &bolt.Options{Timeout: time.Second})
	if err != nil {
		return nil, fmt.Errorf("자료 폴더를 열 수 없습니다. 다른 MCastTalk가 실행 중인지 확인하세요: %w", err)
	}
	s := &Store{db: db, dir: dir}
	if err = s.initPrivateKey(); err != nil {
		db.Close()
		return nil, err
	}
	err = db.Update(func(tx *bolt.Tx) error {
		for _, n := range bucketNames {
			if _, e := tx.CreateBucketIfNotExists([]byte(n)); e != nil {
				return e
			}
		}
		return nil
	})
	if err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}
func (s *Store) put(bucket, key string, v any) error {
	data, e := json.Marshal(v)
	if e != nil {
		return e
	}
	return s.db.Update(func(tx *bolt.Tx) error {
		if bucket == "jobs" {
			var j Job
			if e := json.Unmarshal(data, &j); e != nil {
				return e
			}
			return putJobTx(tx, j)
		}
		if bucket == "lessons" {
			var l Lesson
			if e := json.Unmarshal(data, &l); e != nil {
				return e
			}
			idx := tx.Bucket([]byte("lesson-index"))
			ikey := lessonKey(l.Source, l.Language)
			if l.Status == "approved" {
				if e := idx.Put(ikey, []byte(key)); e != nil {
					return e
				}
			} else if string(idx.Get(ikey)) == key {
				if e := idx.Delete(ikey); e != nil {
					return e
				}
			}
		}
		return tx.Bucket([]byte(bucket)).Put([]byte(key), data)
	})
}
func lessonKey(source, lang string) []byte {
	sum := sha256.Sum256([]byte(lang + "\x00" + source))
	return sum[:]
}
func jobIndexKey(tx *bolt.Tx, j Job) []byte {
	priority := "2"
	var s Session
	if json.Unmarshal(tx.Bucket([]byte("sessions")).Get([]byte(j.SessionID)), &s) == nil {
		if s.Kind == "broadcast" || s.Kind == "classroom" {
			priority = "0"
		} else if s.Kind == "note" {
			priority = "1"
		}
	}
	return []byte(fmt.Sprintf("%s:%s:%020d:%s", j.Kind, priority, j.CreatedAt.UnixNano(), j.ID))
}
func putJobTx(tx *bolt.Tx, j Job) error {
	b := tx.Bucket([]byte("jobs"))
	index := tx.Bucket([]byte("job-index"))
	if raw := b.Get([]byte(j.ID)); raw != nil {
		var old Job
		if json.Unmarshal(raw, &old) == nil {
			if e := index.Delete(jobIndexKey(tx, old)); e != nil {
				return e
			}
		}
	}
	raw, e := json.Marshal(j)
	if e != nil {
		return e
	}
	if e = b.Put([]byte(j.ID), raw); e != nil {
		return e
	}
	if j.State == "queued" {
		return index.Put(jobIndexKey(tx, j), []byte(j.ID))
	}
	return nil
}
func (s *Store) get(bucket, key string, v any) error {
	return s.db.View(func(tx *bolt.Tx) error {
		b := tx.Bucket([]byte(bucket)).Get([]byte(key))
		if b == nil {
			return os.ErrNotExist
		}
		return s.decodeRecord(bucket, key, b, v)
	})
}
func (s *Store) all(bucket string) ([]json.RawMessage, error) {
	out := []json.RawMessage{}
	err := s.db.View(func(tx *bolt.Tx) error {
		return tx.Bucket([]byte(bucket)).ForEach(func(k, v []byte) error {
			if v != nil {
				raw, err := s.unprotect(v, recordAAD(bucket, string(k)))
				if err != nil {
					return err
				}
				out = append(out, append(json.RawMessage(nil), raw...))
			}
			return nil
		})
	})
	return out, err
}
func (s *Store) config() Config {
	c := defaultConfig()
	_ = s.get("settings", "config", &c)
	var sealed []byte
	if s.get("settings", "online-secret", &sealed) == nil {
		if raw, e := openSecret(sealed); e == nil {
			c.Online.APIKey = string(raw)
		}
	}
	return c
}
func (s *Store) queueDepth() int {
	n := 0
	_ = s.db.View(func(tx *bolt.Tx) error { n = tx.Bucket([]byte("job-index")).Stats().KeyN; return nil })
	return n
}
func (s *Store) saveConfig(c Config) error {
	key := c.Online.APIKey
	c.Online.APIKey = ""
	data, e := json.Marshal(c)
	if e != nil {
		return e
	}
	var sealed []byte
	if key != "" {
		sealed, e = sealSecret([]byte(key))
		if e != nil {
			return e
		}
	}
	return s.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket([]byte("settings"))
		if err := b.Put([]byte("config"), data); err != nil {
			return err
		}
		if key != "" {
			raw, _ := json.Marshal(sealed)
			return b.Put([]byte("online-secret"), raw)
		}
		return nil
	})
}
func (s *Store) assets() map[string]InstalledAsset {
	out := map[string]InstalledAsset{}
	_ = s.db.View(func(tx *bolt.Tx) error {
		return tx.Bucket([]byte("assets")).ForEach(func(k, v []byte) error {
			var a InstalledAsset
			if json.Unmarshal(v, &a) == nil {
				out[string(k)] = a
			}
			return nil
		})
	})
	return out
}
func (s *Store) session(id string) (Session, error) {
	var v Session
	err := s.get("sessions", id, &v)
	return v, err
}
func (s *Store) newLine(sessionID, text, id string) (Line, error) {
	return s.newSpeakerLine(sessionID, text, id, SpeechMetadata{})
}
func (s *Store) setLineNotifier(fn func(Line)) { s.notifyMu.Lock(); s.onLine = fn; s.notifyMu.Unlock() }
func (s *Store) emitLine(l Line) {
	s.notifyMu.RLock()
	fn := s.onLine
	s.notifyMu.RUnlock()
	if fn != nil {
		fn(l)
	}
}
func (s *Store) newSpeakerLine(sessionID, text, id string, meta SpeechMetadata) (Line, error) {
	return s.newInputLine(sessionID, text, id, meta, "", time.Time{})
}

// Commit source audio and capture time before exposing the queued translation job.
func (s *Store) newInputLine(sessionID, text, id string, meta SpeechMetadata, audio string, captured time.Time) (Line, error) {
	l := Line{SpeechMetadata: meta, ID: id, SessionID: sessionID, Revision: 1, SourceText: text, IsFinal: true, CapturedAt: time.Now().UTC(), Translations: map[string]string{}, TranslationLatencyMillis: map[string]int64{}, FirstAudioLatencyMillis: map[string]int64{}, SynthesisLatencyMillis: map[string]int64{}, Errors: map[string]string{}, Audio: map[string]string{}}
	if audio != "" {
		l.Audio["source"] = audio
	}
	if !captured.IsZero() {
		l.CapturedAt = captured
	}
	err := s.db.Update(func(tx *bolt.Tx) error {
		lines := tx.Bucket([]byte("lines"))
		if old := lines.Get([]byte(id)); old != nil {
			if err := s.decodeRecord("lines", id, old, &l); err != nil {
				return err
			}
			if meta.SpeakerID != "" && (l.SessionID != sessionID || l.SpeakerID != meta.SpeakerID || l.SourceText != text || l.SourceLanguage != meta.SourceLanguage) {
				return errors.New("같은 요청 ID에 다른 내용을 전송할 수 없습니다")
			}
			return nil
		}
		var session Session
		if err := json.Unmarshal(tx.Bucket([]byte("sessions")).Get([]byte(sessionID)), &session); err != nil {
			return err
		}
		if l.SourceLanguage == "" {
			l.SourceLanguage = session.SourceLanguage
		}
		seq, e := lines.NextSequence()
		if e != nil {
			return e
		}
		l.Sequence = seq
		raw, e := s.encodeLine(tx, l)
		if e != nil {
			return e
		}
		if e = lines.Put([]byte(id), raw); e != nil {
			return e
		}
		if e = tx.Bucket([]byte("line-index")).Put(lineIndexKey(sessionID, seq), []byte(id)); e != nil {
			return e
		}
		jb := Job{ID: id, SessionID: sessionID, LineID: id, Kind: "translate", State: "queued", CreatedAt: time.Now().UTC()}
		return putJobTx(tx, jb)
	})
	if err == nil {
		s.emitLine(l)
	}
	return l, err
}
func (s *Store) updateLine(id string, fn func(*Line)) error {
	var updated Line
	err := s.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket([]byte("lines"))
		raw := b.Get([]byte(id))
		if raw == nil {
			return os.ErrNotExist
		}
		var l Line
		if err := s.decodeRecord("lines", id, raw, &l); err != nil {
			return err
		}
		fn(&l)
		l.Revision++
		data, e := s.encodeLine(tx, l)
		if e != nil {
			return e
		}
		if err := b.Put([]byte(id), data); err != nil {
			return err
		}
		updated = l
		return nil
	})
	if err == nil {
		s.emitLine(updated)
	}
	return err
}
func (s *Store) lines(sessionID string, after uint64, limit int) ([]Line, error) {
	if limit < 1 || limit > 1000 {
		limit = 100
	}
	out := []Line{}
	err := s.db.View(func(tx *bolt.Tx) error {
		c := tx.Bucket([]byte("line-index")).Cursor()
		prefix := sessionID + ":"
		for k, v := c.Seek(lineIndexKey(sessionID, after+1)); k != nil && strings.HasPrefix(string(k), prefix) && len(out) < limit; k, v = c.Next() {
			var l Line
			if e := s.decodeRecord("lines", string(v), tx.Bucket([]byte("lines")).Get(v), &l); e != nil {
				return e
			}
			out = append(out, l)
		}
		return nil
	})
	return out, err
}
func lineIndexKey(sessionID string, seq uint64) []byte {
	return []byte(fmt.Sprintf("%s:%020d", sessionID, seq))
}
func (s *Store) rebuildLineIndex(tx *bolt.Tx) error {
	var highest uint64
	return tx.Bucket([]byte("lines")).ForEach(func(k, v []byte) error {
		var l Line
		if e := s.decodeRecord("lines", string(k), v, &l); e != nil {
			return e
		}
		if l.Sequence > highest {
			highest = l.Sequence
			if e := tx.Bucket([]byte("lines")).SetSequence(highest); e != nil {
				return e
			}
		}
		return tx.Bucket([]byte("line-index")).Put(lineIndexKey(l.SessionID, l.Sequence), k)
	})
}
func (s *Store) claimJob(kind string) (*Job, error) {
	var selected *Job
	err := s.db.Update(func(tx *bolt.Tx) error {
		index := tx.Bucket([]byte("job-index"))
		cursor := index.Cursor()
		prefix := kind + ":"
		fair := tx.Bucket([]byte("settings"))
		fairKey := []byte("fair-" + kind)
		count := byte(0)
		if b := fair.Get(fairKey); len(b) > 0 {
			count = b[0]
		}
		k, v := cursor.Seek([]byte(prefix))
		if count >= 5 {
			fk, fv := cursor.Seek([]byte(prefix + "1:"))
			if fk != nil && strings.HasPrefix(string(fk), prefix) {
				k, v = fk, fv
				count = 0
			} else {
				k, v = cursor.Seek([]byte(prefix))
			}
		}
		if k == nil || !strings.HasPrefix(string(k), prefix) {
			return nil
		}
		var j Job
		if e := json.Unmarshal(tx.Bucket([]byte("jobs")).Get(v), &j); e != nil {
			return e
		}
		selected = &j
		count++
		if e := fair.Put(fairKey, []byte{count}); e != nil {
			return e
		}
		selected.State = "running"
		selected.Attempts++
		return putJobTx(tx, *selected)
	})
	return selected, err
}
func (s *Store) recover() ([]Session, error) {
	out := []Session{}
	err := s.db.Update(func(tx *bolt.Tx) error {
		for _, name := range []string{"sessions", "jobs"} {
			b := tx.Bucket([]byte(name))
			updates := map[string][]byte{}
			if e := b.ForEach(func(k, v []byte) error {
				if name == "sessions" {
					var x Session
					if e := json.Unmarshal(v, &x); e != nil {
						return e
					}
					if x.State == "active" {
						x.State = "interrupted"
						x.GapCount++
						x.UpdatedAt = time.Now().UTC()
						raw, _ := json.Marshal(x)
						updates[string(k)] = raw
						out = append(out, x)
					}
				} else {
					var x Job
					if e := json.Unmarshal(v, &x); e != nil {
						return e
					}
					if x.State == "running" || x.State == "collecting" {
						x.State = "queued"
						x.Error = "재시작 후 저장된 입력에서 재개"
						raw, _ := json.Marshal(x)
						updates[string(k)] = raw
					}
				}
				return nil
			}); e != nil {
				return e
			}
			for k, v := range updates {
				if name == "jobs" {
					var j Job
					if e := json.Unmarshal(v, &j); e != nil {
						return e
					}
					if e := putJobTx(tx, j); e != nil {
						return e
					}
					continue
				}
				if e := b.Put([]byte(k), v); e != nil {
					return e
				}
			}
		}
		return nil
	})
	return out, err
}
func atomicFile(path string, data []byte) error {
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		return e
	}
	tmp, e := os.CreateTemp(filepath.Dir(path), ".writing-*")
	if e != nil {
		return e
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, e = tmp.Write(data); e == nil {
		e = tmp.Sync()
	}
	closeErr := tmp.Close()
	if e != nil {
		return e
	}
	if closeErr != nil {
		return closeErr
	}
	return replaceFile(name, path)
}
func (s *Store) recordingPath(sessionID, file string) (string, error) {
	if !validID(sessionID) || strings.ContainsAny(file, "/\\") || file == "" || strings.HasPrefix(file, ".") {
		return "", errors.New("잘못된 녹음 경로")
	}
	return filepath.Join(s.dir, "recordings", sessionID, file), nil
}
func wavBytes(pcm []byte) []byte { header := wavHeader(int64(len(pcm))); return append(header, pcm...) }
func wavHeader(size int64) []byte {
	h := make([]byte, 44)
	copy(h, "RIFF")
	binary.LittleEndian.PutUint32(h[4:], uint32(size+36))
	copy(h[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(h[16:], 16)
	binary.LittleEndian.PutUint16(h[20:], 1)
	binary.LittleEndian.PutUint16(h[22:], 1)
	binary.LittleEndian.PutUint32(h[24:], 16000)
	binary.LittleEndian.PutUint32(h[28:], 32000)
	binary.LittleEndian.PutUint16(h[32:], 2)
	binary.LittleEndian.PutUint16(h[34:], 16)
	copy(h[36:], "data")
	binary.LittleEndian.PutUint32(h[40:], uint32(size))
	return h
}
