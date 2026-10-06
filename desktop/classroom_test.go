package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// These fixtures verify routing, authorization and durable processing contracts.
// They do not measure model/voice quality or prove 300-person inference capacity.
type classroomTranslationCall struct {
	Text, Source, Target, Context string
	Private                       bool
}

type classroomFixtureEngine struct {
	*fixtureEngine
	traceMu                                      sync.Mutex
	translations                                 []classroomTranslationCall
	publicSTT, privateSTT, publicTTS, privateTTS int
	sttLanguages                                 []string
	privateTTSLanguages                          []string
}

func (e *classroomFixtureEngine) translate(ctx context.Context, text, source, target, recent string, terms []GlossaryTerm, private bool) (string, error) {
	e.traceMu.Lock()
	e.translations = append(e.translations, classroomTranslationCall{text, source, target, recent, private})
	e.traceMu.Unlock()
	return e.fixtureEngine.Translate(ctx, text, source, target, recent, terms)
}
func (e *classroomFixtureEngine) Translate(ctx context.Context, text, source, target, recent string, terms []GlossaryTerm) (string, error) {
	return e.translate(ctx, text, source, target, recent, terms, false)
}
func (e *classroomFixtureEngine) TranslatePrivate(ctx context.Context, text, source, target, recent string, terms []GlossaryTerm) (string, error) {
	return e.translate(ctx, text, source, target, recent, terms, true)
}
func (e *classroomFixtureEngine) Transcribe(ctx context.Context, wav []byte, lang string) (string, error) {
	e.traceMu.Lock()
	e.publicSTT++
	e.sttLanguages = append(e.sttLanguages, lang)
	e.traceMu.Unlock()
	return e.fixtureEngine.Transcribe(ctx, wav, lang)
}
func (e *classroomFixtureEngine) TranscribePrivate(ctx context.Context, wav []byte, lang string) (string, error) {
	e.traceMu.Lock()
	e.privateSTT++
	e.sttLanguages = append(e.sttLanguages, lang)
	e.traceMu.Unlock()
	return e.fixtureEngine.Transcribe(ctx, wav, lang)
}
func (e *classroomFixtureEngine) Synthesize(ctx context.Context, text, lang string) ([]byte, error) {
	e.traceMu.Lock()
	e.publicTTS++
	e.traceMu.Unlock()
	return e.fixtureEngine.Synthesize(ctx, text, lang)
}
func (e *classroomFixtureEngine) SynthesizePrivate(ctx context.Context, text, lang string) ([]byte, error) {
	e.traceMu.Lock()
	e.privateTTS++
	e.privateTTSLanguages = append(e.privateTTSLanguages, lang)
	e.traceMu.Unlock()
	return e.fixtureEngine.Synthesize(ctx, text, lang)
}

type classroomFixture struct {
	a                          *App
	rs                         *RoomService
	engine                     *classroomFixtureEngine
	room                       Room
	teacher, alice, bob, third Member
	tokens                     map[string]string
}

func newClassroomFixture(t *testing.T) *classroomFixture {
	t.Helper()
	a, base := testApp(t)
	e := &classroomFixtureEngine{fixtureEngine: base}
	a.pipeline.engine = e
	rs := a.classroomService()
	r, err := rs.createRoom("권한 회귀시험", "lecture", []string{"ko", "en", "ja", "zh", "es", "fr"}, 64)
	if err != nil {
		t.Fatal(err)
	}
	f := &classroomFixture{a: a, rs: rs, engine: e, room: r, tokens: map[string]string{}}
	join := func(invite, name, lang string) Member {
		m, token, _, err := rs.join(invite, name, lang)
		if err != nil {
			t.Fatal(err)
		}
		f.tokens[m.ID] = token
		return m
	}
	f.teacher = join(r.TeacherInvite, "강사", "ko")
	f.alice = join(r.StudentInvite, "Alice", "en")
	f.bob = join(r.StudentInvite, "Bob", "ja")
	f.third = join(r.StudentInvite, "Third", "zh")
	return f
}

func classroomCall(t *testing.T, a *App, token, method, path string, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(method, "http://127.0.0.1:8787/api/classroom"+path, bytes.NewReader(body))
	r.RemoteAddr = "127.0.0.1:43210"
	r.Header.Set("Authorization", "Bearer "+token)
	if method != http.MethodGet {
		r.Header.Set("Origin", "http://127.0.0.1:8787")
		r.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	a.publicHandler().ServeHTTP(w, r)
	return w
}
func classroomJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func classroomPrivate(t *testing.T, f *classroomFixture) Channel {
	t.Helper()
	c, err := f.rs.createPrivate(f.alice, []string{f.bob.ID})
	if err != nil {
		t.Fatal(err)
	}
	return c
}
func classroomFloor(t *testing.T, f *classroomFixture, m Member, c Channel) Channel {
	t.Helper()
	c, err := f.rs.floor(m, f.room, c, "request", "")
	if err != nil {
		t.Fatal(err)
	}
	if c.Floor.ParticipantID != m.ID {
		t.Fatal("fixture did not obtain floor")
	}
	return c
}

func TestClassroomRemoteAuthenticationRequiresTLS(t *testing.T) {
	f := newClassroomFixture(t)
	for _, path := range []string{"/join", "/rooms", "/" + f.room.ID + "/status"} {
		r := httptest.NewRequest("GET", "http://127.0.0.1:8787/api/classroom"+path, nil)
		r.RemoteAddr = "192.0.2.40:4500"
		r.Header.Set("Authorization", "Bearer "+f.tokens[f.alice.ID])
		w := httptest.NewRecorder()
		f.a.classroomAPI(w, r)
		if w.Code != http.StatusForbidden {
			t.Fatalf("plaintext remote %s: %d", path, w.Code)
		}
		if bytes.Contains(w.Body.Bytes(), []byte(f.alice.Name)) {
			t.Fatal("remote denial disclosed participant")
		}
	}
}

func TestClassroomRequiresExplicitChannelWithoutBroadcastFallback(t *testing.T) {
	f := newClassroomFixture(t)
	base, err := f.rs.channel(f.room.ID)
	if err != nil {
		t.Fatal(err)
	}
	classroomFloor(t, f, f.teacher, base)
	for _, action := range []string{"text", "floor", "context", "audio", "lines", "export"} {
		method := "POST"
		if action == "lines" || action == "export" {
			method = "GET"
		}
		body := classroomJSON(t, map[string]string{"requestId": newID(), "text": "must not enter broadcast", "action": "request", "notes": "no fallback"})
		w := classroomCall(t, f.a, f.tokens[f.teacher.ID], method, "/"+f.room.ID+"/"+action, body)
		if w.Code < 400 || w.Code >= 500 {
			t.Fatalf("missing channel %s: got %d %s", action, w.Code, w.Body.String())
		}
	}
	lines, err := f.a.store.lines(f.room.ID, 0, 100)
	if err != nil || len(lines) != 0 {
		t.Fatalf("missing channel wrote public lines: %v %v", lines, err)
	}
	wrong := newID()
	w := classroomCall(t, f.a, f.tokens[f.teacher.ID], "POST", "/"+f.room.ID+"/text", classroomJSON(t, map[string]string{"channelId": wrong, "requestId": newID(), "text": "no fallback"}))
	if w.Code != 403 {
		t.Fatalf("unknown channel: %d", w.Code)
	}
}

func TestClassroomTeacherAndThirdPartyCannotAccessPrivateChannel(t *testing.T) {
	f := newClassroomFixture(t)
	c := classroomFloor(t, f, f.alice, classroomPrivate(t, f))
	l, err := f.rs.text(f.alice, c, newID(), "PRIVATE_API_SENTINEL")
	if err != nil {
		t.Fatal(err)
	}
	name := l.ID + "-en.wav"
	if err = f.a.store.writeRecording(c.ID, name, wavBytes([]byte{1, 2, 3, 4})); err != nil {
		t.Fatal(err)
	}
	if err = f.a.store.updateLine(l.ID, func(x *Line) { x.Audio["en"] = name }); err != nil {
		t.Fatal(err)
	}
	for _, outsider := range []Member{f.teacher, f.third} {
		for _, tc := range []struct{ method, action string }{
			{"GET", "lines"}, {"GET", "audio/" + name}, {"GET", "export"},
			{"POST", "text"}, {"POST", "audio"}, {"POST", "context"}, {"POST", "floor"},
		} {
			body := classroomJSON(t, map[string]string{"channelId": c.ID, "requestId": newID(), "text": "intrusion", "notes": "intrusion", "action": "grant", "participantId": f.alice.ID})
			w := classroomCall(t, f.a, f.tokens[outsider.ID], tc.method, "/"+f.room.ID+"/"+tc.action+"?channelId="+c.ID, body)
			if w.Code != 403 {
				t.Fatalf("%s %s %s: %d %s", outsider.Role, tc.method, tc.action, w.Code, w.Body.String())
			}
			if bytes.Contains(w.Body.Bytes(), []byte(l.SourceText)) {
				t.Fatal("denial leaked private text")
			}
		}
		w := classroomCall(t, f.a, f.tokens[outsider.ID], "GET", "/"+f.room.ID+"/channels", nil)
		if w.Code != 200 || bytes.Contains(w.Body.Bytes(), []byte(c.ID)) {
			t.Fatalf("outsider channel listing leaked private ID: %s", w.Body.String())
		}
	}
	w := classroomCall(t, f.a, f.tokens[f.alice.ID], "GET", "/"+f.room.ID+"/lines?channelId="+c.ID, nil)
	if w.Code != 200 || !bytes.Contains(w.Body.Bytes(), []byte(l.SourceText)) {
		t.Fatalf("member history unavailable: %d %s", w.Code, w.Body.String())
	}
	other, err := f.rs.createRoom("other room", "conversation", []string{"en"}, 8)
	if err != nil {
		t.Fatal(err)
	}
	w = classroomCall(t, f.a, f.tokens[f.alice.ID], "GET", "/"+other.ID+"/lines?channelId="+c.ID, nil)
	if w.Code != 403 {
		t.Fatalf("room scope mismatch: %d", w.Code)
	}
}

func TestClassroomFloorAndIdempotentPayloadMismatch(t *testing.T) {
	f := newClassroomFixture(t)
	c, err := f.rs.channel(f.room.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.rs.text(f.alice, c, newID(), "without floor"); err == nil {
		t.Fatal("student spoke without floor")
	}
	c, err = f.rs.floor(f.alice, f.room, c, "request", "")
	if err != nil || c.Floor.ParticipantID != "" || len(c.Queue) != 1 {
		t.Fatalf("lecture student bypassed approval: %+v %v", c, err)
	}
	if _, err = f.rs.floor(f.alice, f.room, c, "grant", f.bob.ID); err == nil {
		t.Fatal("student granted floor")
	}
	c, err = f.rs.floor(f.teacher, f.room, c, "grant", f.alice.ID)
	if err != nil {
		t.Fatal(err)
	}
	id := newID()
	l, err := f.rs.text(f.alice, c, id, "original payload")
	if err != nil {
		t.Fatal(err)
	}
	again, err := f.rs.text(f.alice, c, id, "original payload")
	if err != nil || again.ID != l.ID || again.Sequence != l.Sequence {
		t.Fatal("text retry duplicated line")
	}
	if _, err = f.rs.text(f.alice, c, id, "changed payload"); err == nil {
		t.Fatal("text request ID reused for different content")
	}
	wav := wavBytes(bytes.Repeat([]byte{1, 2}, 32))
	audioID := newID()
	j, err := f.rs.audio(f.alice, c, audioID, wav)
	if err != nil {
		t.Fatal(err)
	}
	againJob, err := f.rs.audio(f.alice, c, audioID, wav)
	if err != nil || againJob.ID != j.ID {
		t.Fatal("audio retry duplicated job")
	}
	changed := append([]byte(nil), wav...)
	changed[len(changed)-1] ^= 1
	if _, err = f.rs.audio(f.alice, c, audioID, changed); err == nil {
		t.Fatal("audio request ID reused for different content")
	}
	if j.SourceLanguage != f.alice.Language || j.SpeakerID != f.alice.ID {
		t.Fatal("input identity/language lost")
	}
	c.Floor.ExpiresAt = time.Now().Add(-time.Second)
	if err = f.rs.saveChannel(c); err != nil {
		t.Fatal(err)
	}
	if _, err = f.rs.text(f.alice, c, newID(), "expired floor"); err == nil {
		t.Fatal("expired floor accepted")
	}
}

func TestClassroomRevocationInviteRotationAndRecovery(t *testing.T) {
	f := newClassroomFixture(t)
	c := classroomFloor(t, f, f.alice, classroomPrivate(t, f))
	c.Queue = []string{f.bob.ID}
	if err := f.rs.saveChannel(c); err != nil {
		t.Fatal(err)
	}
	if err := f.rs.recoverChannels(); err != nil {
		t.Fatal(err)
	}
	recovered, err := f.rs.channel(c.ID)
	if err != nil || recovered.Floor.ParticipantID != "" || len(recovered.Queue) != 0 {
		t.Fatalf("stale floor survived recovery: %+v %v", recovered, err)
	}
	w := callAdmin(t, f.a, "POST", "/rooms/"+f.room.ID+"/rotate", []byte("{}"))
	if w.Code != 200 {
		t.Fatalf("rotate: %d %s", w.Code, w.Body.String())
	}
	if _, _, _, err := f.rs.join(f.room.StudentInvite, "old invite", "en"); err == nil {
		t.Fatal("rotated invite accepted")
	}
	if _, err := f.rs.authenticate(f.tokens[f.alice.ID]); err != nil {
		t.Fatal("invite rotation revoked an existing valid participant")
	}
	if err := f.rs.leave(f.alice); err != nil {
		t.Fatal(err)
	}
	if _, err := f.rs.authenticate(f.tokens[f.alice.ID]); err == nil {
		t.Fatal("revoked token accepted")
	}
	w = classroomCall(t, f.a, f.tokens[f.alice.ID], "GET", "/"+f.room.ID+"/lines?channelId="+c.ID, nil)
	if w.Code != 401 {
		t.Fatalf("revoked history access: %d", w.Code)
	}
}

func TestClassroomLanguageFanoutUsesOneInferencePerTarget(t *testing.T) {
	f := newClassroomFixture(t)
	// Thirty simulated subscribers exercise routing independence, not measured capacity.
	f.rs.mu.Lock()
	for i := 0; i < 30; i++ {
		lang := []string{"ko", "ja", "zh", "es", "fr"}[i%5]
		f.rs.subscribers[&roomSubscriber{memberID: newID(), roomID: f.room.ID, channelID: f.room.ID, language: lang, ch: make(chan any, 128), done: make(chan struct{})}] = true
	}
	f.rs.mu.Unlock()
	_, err := f.a.store.newSpeakerLine(f.room.ID, "PUBLIC_CONTEXT_MARKER", newID(), SpeechMetadata{SourceLanguage: "ko", SpeakerID: f.teacher.ID, SpeakerName: f.teacher.Name, Role: "teacher"})
	if err != nil {
		t.Fatal(err)
	}
	private := classroomPrivate(t, f)
	if _, err = f.a.store.newSpeakerLine(private.ID, "PRIVATE_CONTEXT_MUST_NOT_LEAK", newID(), SpeechMetadata{SourceLanguage: "en", SpeakerID: f.alice.ID}); err != nil {
		t.Fatal(err)
	}
	l, err := f.a.store.newSpeakerLine(f.room.ID, "five targets", newID(), SpeechMetadata{SourceLanguage: "en", SpeakerID: f.alice.ID, SpeakerName: f.alice.Name, Role: "student"})
	if err != nil {
		t.Fatal(err)
	}
	j := Job{ID: l.ID, LineID: l.ID, SessionID: l.SessionID, Kind: "translate"}
	if err = f.a.pipeline.processTranslation(j); err != nil {
		t.Fatal(err)
	}
	if err = f.a.pipeline.processTranslation(j); err != nil {
		t.Fatal(err)
	}
	f.engine.traceMu.Lock()
	calls := append([]classroomTranslationCall(nil), f.engine.translations...)
	tts := f.engine.publicTTS
	f.engine.traceMu.Unlock()
	if len(calls) != 5 || tts != 6 {
		t.Fatalf("inference multiplied by listeners/retry: translate=%d TTS=%d", len(calls), tts)
	}
	seen := map[string]int{}
	for _, call := range calls {
		seen[call.Target]++
		if call.Source != "en" || call.Private {
			t.Fatalf("wrong input route: %+v", call)
		}
		if !strings.Contains(call.Context, "PUBLIC_CONTEXT_MARKER") || strings.Contains(call.Context, "PRIVATE_CONTEXT_MUST_NOT_LEAK") {
			t.Fatalf("context crossed channel: %q", call.Context)
		}
	}
	for _, lang := range []string{"ko", "ja", "zh", "es", "fr"} {
		if seen[lang] != 1 {
			t.Fatalf("%s inference count %d", lang, seen[lang])
		}
	}
	var saved Line
	if err = f.a.store.get("lines", l.ID, &saved); err != nil {
		t.Fatal(err)
	}
	if saved.Translations["en"] != l.SourceText || len(saved.Translations) != 6 {
		t.Fatal("same-language source reuse failed")
	}
}

func TestClassroomPrivatePipelineUsesOnlyLocalAdaptersAndContext(t *testing.T) {
	f := newClassroomFixture(t)
	c := classroomFloor(t, f, f.alice, classroomPrivate(t, f))
	if _, err := f.a.store.newSpeakerLine(f.room.ID, "PUBLIC_CONTEXT_DO_NOT_USE", newID(), SpeechMetadata{SourceLanguage: "ko", SpeakerID: f.teacher.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := f.a.store.newSpeakerLine(c.ID, "PRIVATE_RECENT_CONTEXT", newID(), SpeechMetadata{SourceLanguage: "ja", SpeakerID: f.bob.ID}); err != nil {
		t.Fatal(err)
	}
	c.ContextNotes = "PRIVATE_CHANNEL_NOTE"
	if err := f.rs.saveChannel(c); err != nil {
		t.Fatal(err)
	}
	originalWAV := wavBytes(bytes.Repeat([]byte{1, 2}, 32))
	j, err := f.rs.audio(f.alice, c, newID(), originalWAV)
	if err != nil {
		t.Fatal(err)
	}
	if err = f.a.pipeline.processSTT(j); err != nil {
		t.Fatal(err)
	}
	lineID := j.ID[:30] + "00"
	if err = f.a.pipeline.processTranslation(Job{ID: lineID, LineID: lineID, SessionID: c.ID, Kind: "translate"}); err != nil {
		t.Fatal(err)
	}
	var saved Line
	if err = f.a.store.get("lines", lineID, &saved); err != nil {
		t.Fatal(err)
	}
	if saved.Audio["source"] == "" || saved.Audio["en"] != saved.Audio["source"] {
		t.Fatal("same-language audio did not reuse the original source WAV")
	}
	reusedWAV, err := f.a.store.readRecording(c.ID, saved.Audio["en"])
	if err != nil || !bytes.Equal(reusedWAV, originalWAV) {
		t.Fatal("same-language private audio differs from the recorded source WAV")
	}
	if saved.Audio["ja"] == "" || saved.Audio["ja"] == saved.Audio["source"] {
		t.Fatal("translated-language audio was not produced separately")
	}
	if saved.SynthesisLatencyMillis["en"] != 0 {
		t.Fatal("source audio reuse unexpectedly reported synthesis")
	}
	f.engine.traceMu.Lock()
	defer f.engine.traceMu.Unlock()
	if f.engine.publicSTT != 0 || f.engine.publicTTS != 0 || f.engine.privateSTT != 1 || f.engine.privateTTS != 1 {
		t.Fatalf("private routed to public adapter: %+v", f.engine.sttLanguages)
	}
	if len(f.engine.privateTTSLanguages) != 1 || f.engine.privateTTSLanguages[0] != "ja" {
		t.Fatal("private synthesis was not limited to the translated target language")
	}
	if len(f.engine.translations) != 1 || !f.engine.translations[0].Private {
		t.Fatal("private translation adapter not used")
	}
	call := f.engine.translations[0]
	if call.Source != "en" || call.Target != "ja" || !strings.Contains(call.Context, "PRIVATE_RECENT_CONTEXT") || !strings.Contains(call.Context, "PRIVATE_CHANNEL_NOTE") || strings.Contains(call.Context, "PUBLIC_CONTEXT_DO_NOT_USE") {
		t.Fatalf("private context/source mismatch: %+v", call)
	}
	if len(f.engine.sttLanguages) != 1 || f.engine.sttLanguages[0] != "en" {
		t.Fatal("private STT lost participant language")
	}
}

func TestClassroomPrivateMissingAdaptersFailClosed(t *testing.T) {
	f := newClassroomFixture(t)
	c := classroomFloor(t, f, f.alice, classroomPrivate(t, f))
	// The baseline fixture has only public adapters; private work must not use them.
	f.a.pipeline.engine = f.engine.fixtureEngine
	j, err := f.rs.audio(f.alice, c, newID(), wavBytes(bytes.Repeat([]byte{1, 2}, 32)))
	if err != nil {
		t.Fatal(err)
	}
	if err = f.a.pipeline.processSTT(j); err == nil {
		t.Fatal("private STT fell back to a public adapter")
	}
	lines, err := f.a.store.lines(c.ID, 0, 100)
	if err != nil || len(lines) != 0 {
		t.Fatal("failed private STT created a public-provider transcript")
	}
	l, err := f.rs.text(f.alice, c, newID(), "private adapter absent")
	if err != nil {
		t.Fatal(err)
	}
	if err = f.a.pipeline.processTranslation(Job{ID: l.ID, LineID: l.ID, SessionID: c.ID, Kind: "translate"}); err == nil {
		t.Fatal("missing private translation/voice hidden")
	}
	f.engine.fixtureEngine.mu.Lock()
	defer f.engine.fixtureEngine.mu.Unlock()
	if len(f.engine.fixtureEngine.calls) != 0 {
		t.Fatal("private translation called public adapter")
	}
}

func TestClassroomParticipantResponsesRedactInvitesAndAuthHashes(t *testing.T) {
	f := newClassroomFixture(t)
	for _, path := range []string{"/rooms", "/" + f.room.ID + "/status"} {
		w := classroomCall(t, f.a, f.tokens[f.alice.ID], "GET", path, nil)
		if w.Code != 200 {
			t.Fatalf("participant %s: %d", path, w.Code)
		}
		for _, secret := range []string{f.room.TeacherInvite, f.room.StudentInvite, f.teacher.TokenHash, f.alice.TokenHash, f.tokens[f.teacher.ID]} {
			if bytes.Contains(w.Body.Bytes(), []byte(secret)) {
				t.Fatalf("participant response %s disclosed an invite/auth secret", path)
			}
		}
	}
}

// Real TLS and WebSocket transport tests below still use synthetic inference.
// Connection count and elapsed transport time are not model capacity/quality claims.
type classroomWSEvent struct {
	Type        string     `json:"type"`
	Line        Line       `json:"line"`
	Channel     Channel    `json:"channel"`
	Participant MemberView `json:"participant"`
	Room        Room       `json:"room"`
}
type classroomWSResult struct {
	ParticipantID string
	Language      string
	Caption       string
	AudioFile     string
	Frames        int
	Bytes         int
	Error         string
}
type classroomWSGroup struct {
	connections []*websocket.Conn
	readers     sync.WaitGroup
}

func newClassroomWSGroup(t *testing.T) *classroomWSGroup {
	t.Helper()
	g := &classroomWSGroup{}
	t.Cleanup(func() {
		// Close hijacked sockets before waiting for readers, TLS server or database.
		for _, c := range g.connections {
			_ = c.Close()
		}
		g.readers.Wait()
	})
	return g
}
func classroomTLSDial(t *testing.T, srv *httptest.Server, g *classroomWSGroup) *websocket.Conn {
	t.Helper()
	transport, ok := srv.Client().Transport.(*http.Transport)
	if !ok || transport.TLSClientConfig == nil || transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("TLS test server client certificate trust missing")
	}
	dialer := websocket.Dialer{TLSClientConfig: transport.TLSClientConfig.Clone(), HandshakeTimeout: 10 * time.Second}
	conn, resp, err := dialer.Dial("wss"+strings.TrimPrefix(srv.URL, "https")+"/api/classroom/events", http.Header{"Origin": []string{srv.URL}})
	if err != nil {
		if resp != nil {
			_ = resp.Body.Close()
		}
		t.Fatalf("trusted TLS WebSocket handshake failed: %v", err)
	}
	g.connections = append(g.connections, conn)
	return conn
}
func classroomTLSSnapshot(t *testing.T, srv *httptest.Server, g *classroomWSGroup, token string, m Member, channelID string) *websocket.Conn {
	t.Helper()
	c := classroomTLSDial(t, srv, g)
	if err := c.SetWriteDeadline(time.Now().Add(10 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if err := c.WriteJSON(map[string]string{"token": token, "channelId": channelID}); err != nil {
		t.Fatal(err)
	}
	if err := c.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		t.Fatal(err)
	}
	var event classroomWSEvent
	if err := c.ReadJSON(&event); err != nil {
		t.Fatalf("first-frame authenticated snapshot: %v", err)
	}
	if event.Type != "snapshot" || event.Channel.ID != channelID || event.Participant.ID != m.ID || event.Participant.Language != m.Language {
		t.Fatalf("snapshot scope/identity mismatch: %s %s %s", event.Type, event.Channel.ID, event.Participant.ID)
	}
	if event.Room.TeacherInvite != "" || event.Room.StudentInvite != "" {
		t.Fatal("snapshot disclosed room invites")
	}
	return c
}
func classroomTLSRequest(t *testing.T, ctx context.Context, client *http.Client, base, token, method, path string, body []byte) (int, []byte) {
	t.Helper()
	r, err := http.NewRequestWithContext(ctx, method, base+"/api/classroom"+path, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	r.Header.Set("Origin", base)
	r.Header.Set("Content-Type", "application/json")
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(r)
	if err != nil {
		t.Fatalf("TLS API %s: %v", path, err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1024*1024))
	if err != nil {
		t.Fatal(err)
	}
	return resp.StatusCode, data
}
func classroomTLSJoin(t *testing.T, ctx context.Context, client *http.Client, base, invite, name, lang string) (Member, string) {
	t.Helper()
	code, data := classroomTLSRequest(t, ctx, client, base, "", "POST", "/join", classroomJSON(t, map[string]string{"invite": invite, "name": name, "language": lang}))
	if code != 201 {
		t.Fatalf("TLS participant join %s: %d %s", name, code, data)
	}
	var joined struct {
		Token       string     `json:"token"`
		Participant MemberView `json:"participant"`
	}
	if err := json.Unmarshal(data, &joined); err != nil {
		t.Fatal(err)
	}
	if len(joined.Token) != 64 || joined.Participant.Language != lang || !validID(joined.Participant.ID) {
		t.Fatal("invalid authenticated join response")
	}
	m := Member{ID: joined.Participant.ID, RoomID: joined.Participant.RoomID, Name: joined.Participant.Name, Role: joined.Participant.Role, Language: joined.Participant.Language, ExpiresAt: joined.Participant.ExpiresAt}
	return m, joined.Token
}
func classroomReadLine(g *classroomWSGroup, conn *websocket.Conn, m Member, lineID, sessionID string, complete bool, forbidden []string, out chan<- classroomWSResult) {
	g.readers.Add(1)
	go func() {
		defer g.readers.Done()
		result := classroomWSResult{ParticipantID: m.ID, Language: m.Language}
		defer func() { out <- result }()
		if err := conn.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
			result.Error = err.Error()
			return
		}
		for {
			_, raw, err := conn.ReadMessage()
			if err != nil {
				result.Error = err.Error()
				return
			}
			result.Frames++
			result.Bytes += len(raw)
			for _, secret := range forbidden {
				if secret != "" && bytes.Contains(raw, []byte(secret)) {
					result.Error = "cross-channel payload received"
					return
				}
			}
			var event classroomWSEvent
			if err = json.Unmarshal(raw, &event); err != nil {
				result.Error = err.Error()
				return
			}
			if event.Type != "line" || event.Line.ID != lineID {
				continue
			}
			if event.Line.SessionID != sessionID {
				result.Error = "line session scope mismatch"
				return
			}
			for lang := range event.Line.Translations {
				if lang != m.Language {
					result.Error = "received another target language"
					return
				}
			}
			if !complete || (event.Line.Translations[m.Language] != "" && event.Line.Audio[m.Language] != "") {
				result.Caption = event.Line.Translations[m.Language]
				result.AudioFile = event.Line.Audio[m.Language]
				return
			}
		}
	}()
}
func classroomAwaitWS(t *testing.T, ctx context.Context, count int, out <-chan classroomWSResult) []classroomWSResult {
	t.Helper()
	results := make([]classroomWSResult, 0, count)
	for len(results) < count {
		select {
		case r := <-out:
			if r.Error != "" {
				t.Fatalf("WebSocket recipient %s (%s): %s", r.ParticipantID, r.Language, r.Error)
			}
			results = append(results, r)
		case <-ctx.Done():
			t.Fatalf("transport delivery incomplete: %d/%d: %v", len(results), count, ctx.Err())
		}
	}
	return results
}

func TestClassroomTLSWebSocketPrivateIsolation(t *testing.T) {
	f := newClassroomFixture(t)
	c := classroomPrivate(t, f)
	srv := httptest.NewTLSServer(f.a.publicHandler())
	t.Cleanup(srv.Close)
	client := srv.Client()
	client.Timeout = 10 * time.Second
	t.Cleanup(client.CloseIdleConnections)
	g := newClassroomWSGroup(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	aliceConn := classroomTLSSnapshot(t, srv, g, f.tokens[f.alice.ID], f.alice, c.ID)
	bobConn := classroomTLSSnapshot(t, srv, g, f.tokens[f.bob.ID], f.bob, c.ID)
	teacherConn := classroomTLSSnapshot(t, srv, g, f.tokens[f.teacher.ID], f.teacher, f.room.ID)
	thirdConn := classroomTLSSnapshot(t, srv, g, f.tokens[f.third.ID], f.third, f.room.ID)
	// A successful TLS upgrade alone must not authenticate an unauthorized channel.
	for _, outsider := range []Member{f.teacher, f.third} {
		denied := classroomTLSDial(t, srv, g)
		_ = denied.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if err := denied.WriteJSON(map[string]string{"token": f.tokens[outsider.ID], "channelId": c.ID}); err != nil {
			t.Fatal(err)
		}
		_ = denied.SetReadDeadline(time.Now().Add(10 * time.Second))
		var event classroomWSEvent
		if err := denied.ReadJSON(&event); err == nil {
			t.Fatal("outsider received private WebSocket snapshot")
		}
		_ = denied.Close()
	}
	privateRequest, barrierRequest := newID(), newID()
	privateLineID := requestKey(f.alice.ID, c.ID, privateRequest, "text")
	barrierLineID := requestKey(f.teacher.ID, f.room.ID, barrierRequest, "text")
	secret := "PRIVATE_TLS_STREAM_SENTINEL_584931"
	results := make(chan classroomWSResult, 4)
	classroomReadLine(g, aliceConn, f.alice, privateLineID, c.ID, true, nil, results)
	classroomReadLine(g, bobConn, f.bob, privateLineID, c.ID, true, nil, results)
	classroomReadLine(g, teacherConn, f.teacher, barrierLineID, f.room.ID, false, []string{secret, c.ID}, results)
	classroomReadLine(g, thirdConn, f.third, barrierLineID, f.room.ID, false, []string{secret, c.ID}, results)
	code, data := classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[f.alice.ID], "POST", "/"+f.room.ID+"/floor", classroomJSON(t, map[string]string{"channelId": c.ID, "action": "request"}))
	if code != 200 {
		t.Fatalf("private floor: %d %s", code, data)
	}
	code, data = classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[f.alice.ID], "POST", "/"+f.room.ID+"/text", classroomJSON(t, map[string]string{"channelId": c.ID, "requestId": privateRequest, "text": secret}))
	if code != 201 {
		t.Fatalf("private text: %d %s", code, data)
	}
	if err := f.a.pipeline.processTranslation(Job{ID: privateLineID, LineID: privateLineID, SessionID: c.ID, Kind: "translate"}); err != nil {
		t.Fatal(err)
	}
	code, data = classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[f.teacher.ID], "POST", "/"+f.room.ID+"/floor", classroomJSON(t, map[string]string{"channelId": f.room.ID, "action": "request"}))
	if code != 200 {
		t.Fatalf("teacher floor: %d %s", code, data)
	}
	// Store notifications are FIFO: this public barrier follows every private line update.
	code, data = classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[f.teacher.ID], "POST", "/"+f.room.ID+"/text", classroomJSON(t, map[string]string{"channelId": f.room.ID, "requestId": barrierRequest, "text": "PUBLIC_STREAM_BARRIER"}))
	if code != 201 {
		t.Fatalf("public barrier: %d %s", code, data)
	}
	got := classroomAwaitWS(t, ctx, 4, results)
	privateAudioDownloads := 0
	for _, r := range got {
		if r.ParticipantID != f.alice.ID && r.ParticipantID != f.bob.ID {
			continue
		}
		if r.AudioFile == "" {
			t.Fatal("private subscriber did not receive audio reference")
		}
		code, audio := classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[r.ParticipantID], "GET", "/"+f.room.ID+"/audio/"+r.AudioFile+"?channelId="+c.ID, nil)
		if code != 200 || !bytes.Equal(audio, wavBytes(bytes.Repeat([]byte{1, 2}, 1600))) {
			t.Fatalf("private authenticated WAV delivery %s: %d", r.Language, code)
		}
		privateAudioDownloads++
		for _, outsider := range []Member{f.teacher, f.third} {
			code, _ := classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[outsider.ID], "GET", "/"+f.room.ID+"/audio/"+r.AudioFile+"?channelId="+c.ID, nil)
			if code != 403 {
				t.Fatalf("outsider private TLS audio fetch: %d", code)
			}
		}
	}
	logData, _ := json.Marshal(map[string]any{"evidence": "TLS_WEBSOCKET_TRANSPORT_FIXTURE_ONLY", "snapshots": 4, "privateRecipients": 2, "privateWAVDownloads": privateAudioDownloads, "outsiderPrivateSnapshotDenials": 2, "barrierObservers": 2, "deliveredRecipients": len(got), "modelCapacityMeasured": false})
	t.Log(string(logData))
}

func TestClassroomTLSWebSocketLoadTransport30_100_320(t *testing.T) {
	for _, listeners := range []int{30, 100, 320} {
		t.Run(fmt.Sprintf("listeners_%d", listeners), func(t *testing.T) {
			start := time.Now()
			a, base := testApp(t)
			engine := &classroomFixtureEngine{fixtureEngine: base}
			a.pipeline.engine = engine
			rs := a.classroomService()
			languages := []string{"ko", "en", "ja", "zh", "es"}
			room, err := rs.createRoom("TLS transport fixture", "lecture", languages, 512)
			if err != nil {
				t.Fatal(err)
			}
			srv := httptest.NewTLSServer(a.publicHandler())
			t.Cleanup(srv.Close)
			client := srv.Client()
			client.Timeout = 10 * time.Second
			t.Cleanup(client.CloseIdleConnections)
			g := newClassroomWSGroup(t)
			// Sequential HTTPS registration and fsync run under the race detector;
			// bootstrap has its own budget, separate from event delivery below.
			ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
			defer cancel()
			teacher, teacherToken := classroomTLSJoin(t, ctx, client, srv.URL, room.TeacherInvite, "Teacher", "ko")
			members := make([]Member, 0, listeners)
			tokens := make([]string, 0, listeners)
			tokensByID := map[string]string{}
			languageCounts := map[string]int{}
			// Authenticate the class over HTTPS before opening the event streams. This
			// measures snapshot+line fanout; it does not claim concurrent join capacity.
			for i := 0; i < listeners; i++ {
				lang := languages[i%len(languages)]
				member, token := classroomTLSJoin(t, ctx, client, srv.URL, room.StudentInvite, fmt.Sprintf("Student-%03d", i), lang)
				members = append(members, member)
				tokens = append(tokens, token)
				languageCounts[lang]++
				tokensByID[member.ID] = token
			}
			conns := make([]*websocket.Conn, 0, listeners)
			for i, m := range members {
				if err := ctx.Err(); err != nil {
					t.Fatalf("snapshot setup deadline: %v", err)
				}
				conns = append(conns, classroomTLSSnapshot(t, srv, g, tokens[i], m, room.ID))
			}
			rs.mu.Lock()
			active := len(rs.subscribers)
			rs.mu.Unlock()
			if active != listeners {
				t.Fatalf("connected subscribers %d, want %d", active, listeners)
			}
			ctx, deliveryCancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer deliveryCancel()
			requestID := newID()
			lineID := requestKey(teacher.ID, room.ID, requestID, "text")
			results := make(chan classroomWSResult, listeners)
			for i, m := range members {
				classroomReadLine(g, conns[i], m, lineID, room.ID, true, nil, results)
			}
			code, data := classroomTLSRequest(t, ctx, client, srv.URL, teacherToken, "POST", "/"+room.ID+"/floor", classroomJSON(t, map[string]string{"channelId": room.ID, "action": "request"}))
			if code != 200 {
				t.Fatalf("teacher floor: %d %s", code, data)
			}
			deliveryStart := time.Now()
			sourceText := "전송 시험: 숫자는 123이며 단위는 미터입니다."
			code, data = classroomTLSRequest(t, ctx, client, srv.URL, teacherToken, "POST", "/"+room.ID+"/text", classroomJSON(t, map[string]string{"channelId": room.ID, "requestId": requestID, "text": sourceText}))
			if code != 201 {
				t.Fatalf("teacher text: %d %s", code, data)
			}
			if err = a.pipeline.processTranslation(Job{ID: lineID, LineID: lineID, SessionID: room.ID, Kind: "translate"}); err != nil {
				t.Fatal(err)
			}
			got := classroomAwaitWS(t, ctx, listeners, results)
			deliveryMillis := time.Since(deliveryStart).Milliseconds()
			deliveredLanguages := map[string]int{}
			frames, wireBytes := 0, 0
			audioSamples := map[string]bool{}
			for _, r := range got {
				deliveredLanguages[r.Language]++
				frames += r.Frames
				wireBytes += r.Bytes
				expected := r.Language + ":" + sourceText
				if r.Language == "ko" {
					expected = sourceText
				}
				if r.Caption != expected {
					t.Fatalf("fixture caption delivery mismatch for %s", r.Language)
				}
				if !audioSamples[r.Language] {
					code, audio := classroomTLSRequest(t, ctx, client, srv.URL, tokensByID[r.ParticipantID], "GET", "/"+room.ID+"/audio/"+r.AudioFile+"?channelId="+room.ID, nil)
					if code != 200 || !bytes.Equal(audio, wavBytes(bytes.Repeat([]byte{1, 2}, 1600))) {
						t.Fatalf("TLS WAV fixture delivery %s: %d", r.Language, code)
					}
					audioSamples[r.Language] = true
				}
			}
			for _, lang := range languages {
				if deliveredLanguages[lang] != languageCounts[lang] {
					t.Fatalf("%s delivered %d/%d", lang, deliveredLanguages[lang], languageCounts[lang])
				}
			}
			engine.traceMu.Lock()
			calls := append([]classroomTranslationCall(nil), engine.translations...)
			tts := engine.publicTTS
			privateTTS := engine.privateTTS
			engine.traceMu.Unlock()
			counts := map[string]int{}
			for _, call := range calls {
				counts[call.Target]++
				if call.Private || call.Source != "ko" {
					t.Fatal("broadcast inference scope/source mismatch")
				}
			}
			if len(calls) != 4 || tts != 5 || privateTTS != 0 {
				t.Fatalf("listeners multiplied inference: translation=%d publicTTS=%d privateTTS=%d", len(calls), tts, privateTTS)
			}
			for _, lang := range []string{"en", "ja", "zh", "es"} {
				if counts[lang] != 1 {
					t.Fatalf("%s translated %d times", lang, counts[lang])
				}
			}
			var final Line
			if err = a.store.get("lines", lineID, &final); err != nil {
				t.Fatal(err)
			}
			if final.Translations["ko"] != final.SourceText {
				t.Fatal("source-language caption was inferred instead of reused")
			}
			logData, _ := json.Marshal(map[string]any{"evidence": "TLS_WEBSOCKET_TRANSPORT_WITH_FIXTURE_ENGINE", "listeners": listeners, "httpsJoins": listeners + 1, "authenticatedSnapshots": len(conns), "deliveredRecipients": len(got), "languageCounts": deliveredLanguages, "translationCalls": counts, "publicTTSCalls": tts, "sampledLanguagesWithHTTPSWAV": len(audioSamples), "receivedFramesUntilCompletion": frames, "receivedBytesUntilCompletion": wireBytes, "deliveryMillis": deliveryMillis, "totalMillis": time.Since(start).Milliseconds(), "modelCapacityMeasured": false, "qualityMeasured": false})
			t.Log(string(logData))
		})
	}
}
