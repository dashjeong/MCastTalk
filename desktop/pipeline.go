package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	bolt "go.etcd.io/bbolt"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type SpeechEngine interface {
	Start(context.Context, Config, map[string]InstalledAsset) error
	Stop()
	Ready() EngineStatus
	Translate(context.Context, string, string, string, string, []GlossaryTerm) (string, error)
	Transcribe(context.Context, []byte, string) (string, error)
	Synthesize(context.Context, string, string) ([]byte, error)
}
type audioSubscription struct {
	channel string
	frames  chan []byte
	done    chan struct{}
}
type AudioHub struct {
	mu         sync.Mutex
	subs       map[*audioSubscription]bool
	generation int64
	dropped    atomic.Uint64
	delivered  atomic.Uint64
}

func newHub() *AudioHub {
	return &AudioHub{subs: map[*audioSubscription]bool{}, generation: time.Now().UnixNano()}
}
func (h *AudioHub) reset() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.subs {
		close(s.done)
		delete(h.subs, s)
	}
	h.generation++
}
func (h *AudioHub) subscribe(channel string, limit int) (*audioSubscription, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.subs) >= limit {
		return nil, errors.New("청취자 정원에 도달했습니다")
	}
	s := &audioSubscription{channel: channel, frames: make(chan []byte, 32), done: make(chan struct{})}
	h.subs[s] = true
	return s, nil
}
func (h *AudioHub) unsubscribe(s *audioSubscription) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.subs[s] {
		delete(h.subs, s)
		close(s.done)
	}
}
func (h *AudioHub) publish(channel string, pcm []byte) {
	for len(pcm) > 0 {
		n := min(len(pcm), 4096)
		frame := append([]byte(nil), pcm[:n]...)
		pcm = pcm[n:]
		h.mu.Lock()
		for s := range h.subs {
			if s.channel == channel {
				select {
				case s.frames <- frame:
				default:
					h.dropped.Add(1)
					delete(h.subs, s)
					close(s.done)
				}
			}
		}
		h.mu.Unlock()
	}
}
func (h *AudioHub) count() int { h.mu.Lock(); defer h.mu.Unlock(); return len(h.subs) }
func (h *AudioHub) gen() int64 { h.mu.Lock(); defer h.mu.Unlock(); return h.generation }

type Pipeline struct {
	store        *Store
	engine       SpeechEngine
	hub          *AudioHub
	mu           sync.Mutex
	active       *Session
	sessionDone  chan struct{}
	input        []byte
	silence      int
	spoke        bool
	sourceFile   *os.File
	pending      *Job
	speechMu     sync.Mutex
	speechQueues map[string]chan speechPublication
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
	errorMu      sync.Mutex
	lastError    string
	running      atomic.Int64
}
type speechPublication struct {
	pcm        []byte
	sessionID  string
	generation int64
}

func newPipeline(s *Store, e SpeechEngine) *Pipeline {
	ctx, cancel := context.WithCancel(context.Background())
	return &Pipeline{store: s, engine: e, hub: newHub(), ctx: ctx, cancel: cancel, speechQueues: map[string]chan speechPublication{}}
}
func (p *Pipeline) publishSpeech(lang, sessionID string, pcm []byte) error {
	if p.ctx.Err() != nil {
		return p.ctx.Err()
	}
	p.speechMu.Lock()
	q := p.speechQueues[lang]
	if q == nil {
		q = make(chan speechPublication, 8)
		p.speechQueues[lang] = q
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			for {
				select {
				case <-p.ctx.Done():
					return
				case pub := <-q:
					for len(pub.pcm) > 0 {
						if p.hub.gen() != pub.generation {
							break
						}
						n := min(len(pub.pcm), 3200)
						p.hub.publish(lang, pub.pcm[:n])
						pub.pcm = pub.pcm[n:]
						timer := time.NewTimer(time.Duration(n) * time.Second / 32000)
						select {
						case <-p.ctx.Done():
							timer.Stop()
							return
						case <-timer.C:
						}
					}
				}
			}
		}()
	}
	p.speechMu.Unlock()
	select {
	case q <- speechPublication{pcm: pcm, sessionID: sessionID, generation: p.hub.gen()}:
		return nil
	default:
		return errors.New("음성 송출 대기열이 가득 찼습니다. 저장 음성에서 확인하세요")
	}
}
func (p *Pipeline) report(e error) {
	if e == nil {
		return
	}
	p.errorMu.Lock()
	p.lastError = e.Error()
	p.errorMu.Unlock()
}
func (p *Pipeline) lastIssue() string { p.errorMu.Lock(); defer p.errorMu.Unlock(); return p.lastError }
func (p *Pipeline) startWorkers() {
	for _, kind := range []string{"stt", "translate"} {
		p.wg.Add(1)
		go func(kind string) {
			defer p.wg.Done()
			ticker := time.NewTicker(200 * time.Millisecond)
			defer ticker.Stop()
			for {
				select {
				case <-p.ctx.Done():
					return
				case <-ticker.C:
				}
				ready := p.engine.Ready()
				if (kind == "stt" && !ready.STTReady) || (kind == "translate" && !ready.TranslationReady) {
					// Preserve pending inputs across model installation/startup and worker failure.
					continue
				}
				j, e := p.store.claimJob(kind)
				if e != nil {
					p.report(e)
					continue
				}
				if j == nil {
					continue
				}
				p.running.Add(1)
				if kind == "stt" {
					e = p.processSTT(*j)
				} else {
					e = p.processTranslation(*j)
				}
				if e != nil {
					j.State = "failed"
					j.Error = e.Error()
					p.report(e)
				} else {
					j.State = "done"
					j.Error = ""
				}
				if err := p.store.put("jobs", j.ID, j); err != nil {
					p.report(err)
				}
				p.running.Add(-1)
			}
		}(kind)
	}
}
func (p *Pipeline) close() {
	p.mu.Lock()
	if p.sessionDone != nil {
		close(p.sessionDone)
		p.sessionDone = nil
	}
	p.report(p.flushLocked())
	if p.sourceFile != nil {
		p.report(p.sourceFile.Close())
		p.sourceFile = nil
	}
	p.mu.Unlock()
	p.cancel()
	p.engine.Stop()
	p.hub.reset()
	p.wg.Wait()
}
func (p *Pipeline) current() *Session {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active == nil {
		return nil
	}
	copy := *p.active
	copy.Targets = append([]string(nil), copy.Targets...)
	return &copy
}
func (p *Pipeline) begin(s Session) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active != nil {
		return errors.New("진행 중인 작업을 먼저 종료하세요")
	}
	s.State = "active"
	s.UpdatedAt = time.Now().UTC()
	if e := p.store.put("sessions", s.ID, s); e != nil {
		return e
	}
	p.active = &s
	p.sessionDone = make(chan struct{})
	p.input = nil
	p.silence = 0
	p.spoke = false
	p.hub.reset()
	return nil
}
func (p *Pipeline) stop() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active == nil {
		return nil
	}
	if e := p.flushLocked(); e != nil {
		return e
	}
	s := *p.active
	s.State = "stopped"
	s.UpdatedAt = time.Now().UTC()
	if e := p.store.put("sessions", s.ID, s); e != nil {
		return e
	}
	if p.sourceFile != nil {
		p.report(p.sourceFile.Close())
		p.sourceFile = nil
	}
	p.active = nil
	if p.sessionDone != nil {
		close(p.sessionDone)
		p.sessionDone = nil
	}
	p.hub.reset()
	return nil
}
func (p *Pipeline) text(sessionID, text string) (Line, error) {
	if p.store.isPrivateSession(sessionID) {
		return Line{}, errors.New("개인 대화는 해당 채널 참여 경로로 전송하세요")
	}
	if strings.TrimSpace(text) == "" || len(text) > 8192 {
		return Line{}, errors.New("원문은 1~8,192바이트로 입력하세요")
	}
	if _, e := p.store.session(sessionID); e != nil {
		return Line{}, e
	}
	return p.store.newLine(sessionID, text, newID())
}
func (p *Pipeline) inputPCM(pcm []byte) error {
	return p.inputPCMForSession("", pcm)
}

// Socket input must remain bound to the session selected before microphone
// permission or network waits. The check and durable write share the same lock
// as stop/begin so an old connection cannot write into a replacement session.
func (p *Pipeline) inputPCMForSession(sessionID string, pcm []byte) error {
	if len(pcm) == 0 || len(pcm) > 65536 || len(pcm)%2 != 0 {
		return errors.New("PCM16LE 프레임이 올바르지 않습니다")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active == nil {
		return errors.New("방송 또는 음성노트 작업을 먼저 여세요")
	}
	if sessionID != "" && p.active.ID != sessionID {
		return errors.New("마이크를 시작한 작업이 종료되었습니다. 새 작업에서 다시 시작하세요")
	}
	// Every accepted microphone frame reaches disk and fsync before transmission.
	if p.sourceFile == nil {
		path, e := p.store.recordingPath(p.active.ID, "source.pcm")
		if e != nil {
			return e
		}
		if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
			return e
		}
		p.sourceFile, e = os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
		if e != nil {
			return e
		}
	}
	if _, e := p.sourceFile.Write(pcm); e != nil {
		return e
	}
	if e := p.sourceFile.Sync(); e != nil {
		return e
	}
	var sum float64
	for i := 0; i < len(pcm); i += 2 {
		x := float64(int16(binary.LittleEndian.Uint16(pcm[i:]))) / 32768
		sum += x * x
	}
	voiced := math.Sqrt(sum/float64(len(pcm)/2)) >= 0.012
	p.input = append(p.input, pcm...)
	if voiced {
		p.spoke = true
		p.silence = 0
	} else {
		p.silence += len(pcm)
	}
	if p.spoke {
		if p.pending == nil {
			id := newID()
			path, e := p.store.recordingPath(p.active.ID, id+"-input.wav")
			if e != nil {
				return e
			}
			p.pending = &Job{ID: id, SessionID: p.active.ID, Kind: "stt", Path: path, State: "collecting", CreatedAt: time.Now().UTC()}
		}
		if e := atomicFile(p.pending.Path, wavBytes(p.input)); e != nil {
			return e
		}
		if e := p.store.put("jobs", p.pending.ID, p.pending); e != nil {
			return e
		}
	}
	p.hub.publish("source", pcm)
	if p.spoke && (p.silence >= 16000 || len(p.input) >= 128000) {
		return p.flushLocked()
	}
	if !p.spoke && len(p.input) > 8000 {
		p.input = append([]byte(nil), p.input[len(p.input)-8000:]...)
	}
	return nil
}
func (p *Pipeline) flushInput() error { p.mu.Lock(); defer p.mu.Unlock(); return p.flushLocked() }
func (p *Pipeline) flushInputForSession(sessionID string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active == nil || p.active.ID != sessionID {
		return errors.New("마이크를 시작한 작업이 종료되었습니다")
	}
	return p.flushLocked()
}
func (p *Pipeline) flushLocked() error {
	if !p.spoke || p.active == nil {
		p.input = nil
		p.silence = 0
		p.spoke = false
		return nil
	}
	if p.pending == nil {
		return errors.New("입력 저장 상태를 찾을 수 없습니다")
	}
	p.pending.State = "queued"
	if e := p.store.put("jobs", p.pending.ID, p.pending); e != nil {
		return e
	}
	p.pending = nil
	p.input = nil
	p.silence = 0
	p.spoke = false
	return nil
}
func (p *Pipeline) queueFile(sessionID string, wav []byte) (Job, error) {
	if p.store.isPrivateSession(sessionID) {
		return Job{}, errors.New("개인 음성은 해당 채널 참여 경로로 전송하세요")
	}
	if _, e := p.store.session(sessionID); e != nil {
		return Job{}, e
	}
	if e := validateWAV(wav); e != nil {
		return Job{}, e
	}
	var pcm []byte
	for off := 12; off+8 <= len(wav); {
		size := int(binary.LittleEndian.Uint32(wav[off+4:]))
		if string(wav[off:off+4]) == "data" {
			pcm = wav[off+8 : off+8+size]
			break
		}
		off += 8 + size + (size % 2)
	}
	var first Job
	for len(pcm) > 0 {
		n := min(len(pcm), 20*32000)
		id := newID()
		path, e := p.store.recordingPath(sessionID, id+"-input.wav")
		if e != nil {
			return first, e
		}
		if e = atomicFile(path, wavBytes(pcm[:n])); e != nil {
			return first, e
		}
		j := Job{ID: id, SessionID: sessionID, Kind: "stt", Path: path, State: "queued", CreatedAt: time.Now().UTC()}
		if e = p.store.put("jobs", id, j); e != nil {
			return first, e
		}
		if first.ID == "" {
			first = j
		}
		pcm = pcm[n:]
	}
	return first, nil
}
func validateWAV(b []byte) error {
	if len(b) < 44 || string(b[:4]) != "RIFF" || string(b[8:12]) != "WAVE" {
		return errors.New("PCM16LE, 모노 16kHz WAV를 선택하세요")
	}
	fmtOK, dataOK := false, false
	for off := 12; off+8 <= len(b); {
		size := int(binary.LittleEndian.Uint32(b[off+4:]))
		if size < 0 || size > len(b)-off-8 {
			return errors.New("손상된 WAV 청크")
		}
		tag := string(b[off : off+4])
		payload := b[off+8 : off+8+size]
		if tag == "fmt " {
			fmtOK = size >= 16 && binary.LittleEndian.Uint16(payload) == 1 && binary.LittleEndian.Uint16(payload[2:]) == 1 && binary.LittleEndian.Uint32(payload[4:]) == 16000 && binary.LittleEndian.Uint16(payload[14:]) == 16
		}
		if tag == "data" {
			dataOK = size > 0 && size%2 == 0
		}
		off += 8 + size + (size % 2)
	}
	if !fmtOK || !dataOK {
		return errors.New("PCM16LE, 모노 16kHz WAV가 필요합니다. 파일 화면에서 변환하세요")
	}
	return nil
}
func (p *Pipeline) processSTT(j Job) error {
	s, e := p.store.session(j.SessionID)
	if e != nil {
		return e
	}
	b, e := p.store.readRecording(j.SessionID, filepath.Base(j.Path))
	if e != nil {
		return e
	}
	ctx, cancel := context.WithTimeout(p.ctx, 90*time.Second)
	defer cancel()
	source := j.SourceLanguage
	if source == "" {
		source = s.SourceLanguage
	}
	var text string
	if s.Kind == "private" {
		local, ok := p.engine.(interface {
			TranscribePrivate(context.Context, []byte, string) (string, error)
		})
		if !ok {
			return errors.New("개인 채널용 로컬 음성 인식이 준비되지 않았습니다")
		}
		text, e = local.TranscribePrivate(ctx, b, source)
	} else {
		text, e = p.engine.Transcribe(ctx, b, source)
	}
	if e != nil {
		return e
	}
	if strings.TrimSpace(text) == "" {
		return errors.New("음성에서 전사문을 얻지 못했습니다. 원음은 보관되었습니다")
	}
	// A separate deterministic line ID makes crash retry idempotent without replacing the STT job.
	id := j.ID[:30] + "00"
	meta := j.SpeechMetadata
	meta.SourceLanguage = source
	_, e = p.store.newInputLine(s.ID, text, id, meta, filepath.Base(j.Path), j.CreatedAt)
	return e
}
func (p *Pipeline) approved(source, lang string) string {
	out := ""
	_ = p.store.db.View(func(tx *bolt.Tx) error {
		id := tx.Bucket([]byte("lesson-index")).Get(lessonKey(source, lang))
		if id == nil {
			return nil
		}
		var l Lesson
		if json.Unmarshal(tx.Bucket([]byte("lessons")).Get(id), &l) == nil && l.Status == "approved" && l.Source == source && l.Language == lang {
			out = l.Proposed
		}
		return nil
	})
	return out
}
func (p *Pipeline) glossary() []GlossaryTerm {
	var out []GlossaryTerm
	_ = p.store.get("glossary", "terms", &out)
	return out
}
func (p *Pipeline) processTranslation(j Job) error {
	var l Line
	if e := p.store.get("lines", j.LineID, &l); e != nil {
		return e
	}
	s, e := p.store.session(j.SessionID)
	if e != nil {
		return e
	}
	if l.SessionID != s.ID {
		return errors.New("번역 작업과 발화의 채널이 일치하지 않습니다")
	}
	source := l.SourceLanguage
	if source == "" {
		source = s.SourceLanguage
	}
	recent := p.store.recentContext(s.ID, l.Sequence, 6)
	terms := p.glossary()
	// A task belongs to an utterance and a unique language, never to a listener.
	// Both workers read the original line snapshot; Store serializes fresh updates.
	var targets []string
	seen := map[string]bool{}
	for _, lang := range s.Targets {
		if seen[lang] {
			continue
		}
		seen[lang] = true
		if l.Translations[lang] != "" && l.Audio[lang] != "" {
			continue
		}
		targets = append(targets, lang)
	}
	results := make([]error, len(targets))
	tasks := make(chan int, len(targets))
	for i := range targets {
		tasks <- i
	}
	close(tasks)
	var wg sync.WaitGroup
	for worker := 0; worker < min(2, len(targets)); worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range tasks {
				if err := p.ctx.Err(); err != nil {
					results[i] = err
					continue
				}
				results[i] = p.processTranslationLanguage(s, l, source, recent, targets[i], terms)
			}
		}()
	}
	wg.Wait()
	var failed []int
	for i, err := range results {
		if err != nil {
			failed = append(failed, i)
		}
	}
	sort.Slice(failed, func(i, j int) bool { return targets[failed[i]] < targets[failed[j]] })
	var failures []error
	for _, i := range failed {
		failures = append(failures, fmt.Errorf("%s: %w", targets[i], results[i]))
	}
	if len(failures) != 0 {
		return fmt.Errorf("언어별 처리 실패 (원음과 성공한 자막 유지): %w", errors.Join(failures...))
	}
	return nil
}

func (p *Pipeline) processTranslationLanguage(s Session, l Line, source, recent, lang string, terms []GlossaryTerm) error {
	ctx, cancel := context.WithTimeout(p.ctx, 30*time.Second)
	defer cancel()
	update := func(fn func(*Line)) error {
		err := p.store.updateLine(l.ID, fn)
		if err != nil {
			p.report(err)
		}
		return err
	}
	failure := func(cause error, message string) error {
		if err := update(func(x *Line) { x.Errors[lang] = message }); err != nil {
			return errors.Join(cause, fmt.Errorf("처리 오류 상태 저장: %w", err))
		}
		return cause
	}
	start := time.Now()
	out := l.Translations[lang]
	if out == "" {
		out = p.approved(l.SourceText, lang)
	}
	if out == "" && lang == source {
		out = l.SourceText
	}
	var err error
	if out == "" {
		if s.Kind == "private" {
			local, ok := p.engine.(interface {
				TranslatePrivate(context.Context, string, string, string, string, []GlossaryTerm) (string, error)
			})
			if !ok {
				err = errors.New("개인 채널용 로컬 번역 엔진이 준비되지 않았습니다")
			} else {
				out, err = local.TranslatePrivate(ctx, l.SourceText, source, lang, recent, append([]GlossaryTerm(nil), terms...))
			}
		} else {
			out, err = p.engine.Translate(ctx, l.SourceText, source, lang, recent, append([]GlossaryTerm(nil), terms...))
		}
	}
	if err == nil {
		err = ctx.Err()
	}
	if err == nil && strings.TrimSpace(out) == "" {
		err = errors.New("번역문이 비어 있습니다")
	}
	if err != nil {
		return failure(err, err.Error())
	}
	ms := time.Since(start).Milliseconds()
	if err := update(func(x *Line) {
		x.Translations[lang] = out
		x.TranslationLatencyMillis[lang] = ms
		delete(x.Errors, lang)
	}); err != nil {
		return err
	}
	if lang == source && l.Audio["source"] != "" {
		return update(func(x *Line) {
			x.Audio[lang] = l.Audio["source"]
			x.SynthesisLatencyMillis[lang] = 0
			x.FirstAudioLatencyMillis[lang] = time.Since(l.CapturedAt).Milliseconds()
		})
	}
	ttsStart := time.Now()
	var pcm []byte
	if s.Kind == "private" {
		local, ok := p.engine.(interface {
			SynthesizePrivate(context.Context, string, string) ([]byte, error)
		})
		if !ok {
			err = errors.New("개인 채널용 로컬 음성이 준비되지 않았습니다")
		} else {
			pcm, err = local.SynthesizePrivate(ctx, out, lang)
		}
	} else {
		pcm, err = p.engine.Synthesize(ctx, out, lang)
	}
	if err == nil {
		err = ctx.Err()
	}
	if err == nil && (len(pcm) == 0 || len(pcm)%2 != 0) {
		err = errors.New("합성 음성 형식 오류")
	}
	if err != nil {
		return failure(err, "자막 저장됨 / 음성: "+err.Error())
	}
	ttsMS := time.Since(ttsStart).Milliseconds()
	name := l.ID + "-" + lang + ".wav"
	if err := p.store.writeRecording(s.ID, name, wavBytes(pcm)); err != nil {
		p.report(err)
		return failure(err, "자막 저장됨 / 음성 저장: "+err.Error())
	}
	if err := update(func(x *Line) {
		x.Audio[lang] = name
		x.SynthesisLatencyMillis[lang] = ttsMS
		x.FirstAudioLatencyMillis[lang] = time.Since(l.CapturedAt).Milliseconds()
	}); err != nil {
		return err
	}
	current := p.current()
	if current != nil && current.ID == s.ID {
		if err := p.publishSpeech(lang, s.ID, pcm); err != nil {
			p.report(err)
			return failure(err, err.Error())
		}
	}
	return nil
}
