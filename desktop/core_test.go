package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type fixtureEngine struct {
	mu    sync.Mutex
	calls map[string]int
	fail  string
}

func (e *fixtureEngine) Start(context.Context, Config, map[string]InstalledAsset) error { return nil }
func (e *fixtureEngine) Stop()                                                          {}
func (e *fixtureEngine) Ready() EngineStatus {
	return EngineStatus{TranslationReady: true, STTReady: true, TTSReady: true, Backend: "fixture"}
}
func (e *fixtureEngine) Translate(ctx context.Context, text, source, target, hints string, terms []GlossaryTerm) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.calls == nil {
		e.calls = map[string]int{}
	}
	e.calls[target]++
	if target == e.fail {
		return "", fmt.Errorf("fixture: %s unavailable", target)
	}
	return target + ":" + text, nil
}
func (e *fixtureEngine) Transcribe(context.Context, []byte, string) (string, error) {
	return "안녕하세요. 숫자는 123입니다.", nil
}
func (e *fixtureEngine) Synthesize(context.Context, string, string) ([]byte, error) {
	return bytes.Repeat([]byte{1, 2}, 1600), nil
}
func testApp(t *testing.T) (*App, *fixtureEngine) {
	t.Helper()
	s, e := openStore(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	engine := &fixtureEngine{}
	p := newPipeline(s, engine)
	cfg := defaultConfig()
	cfg.SpeakerPIN = "12345678"
	if e := s.saveConfig(cfg); e != nil {
		t.Fatal(e)
	}
	a := &App{store: s, pipeline: p, cfg: cfg, adminToken: newID(), requests: newLimiter()}
	a.assets = NewAssetManager(s.dir, func(asset InstalledAsset) error { return s.put("assets", asset.ID, asset) })
	t.Cleanup(func() { p.close(); s.db.Close() })
	return a, engine
}
func testSession(t *testing.T, a *App, targets []string) Session {
	t.Helper()
	s := Session{ID: newID(), Kind: "broadcast", Title: "시험", SourceLanguage: "ko", Targets: targets, Token: newID(), StartedAt: time.Now().UTC()}
	if e := a.pipeline.begin(s); e != nil {
		t.Fatal(e)
	}
	return *a.pipeline.current()
}
func callAdmin(t *testing.T, a *App, method, path string, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(method, "http://127.0.0.1:8790/admin/api"+path, bytes.NewReader(body))
	r.RemoteAddr = "127.0.0.1:43210"
	r.Header.Set("Authorization", "Bearer "+a.adminToken)
	if method != "GET" {
		r.Header.Set("Origin", "http://127.0.0.1:8790")
	}
	w := httptest.NewRecorder()
	a.adminHandler().ServeHTTP(w, r)
	return w
}
func TestAdminCredentialAndOriginIsolation(t *testing.T) {
	a, _ := testApp(t)
	for _, tc := range []struct {
		name, host, peer, origin, token string
		code                            int
	}{{"valid", "127.0.0.1:8790", "127.0.0.1:5", "http://127.0.0.1:8790", a.adminToken, 200}, {"no_token", "127.0.0.1:8790", "127.0.0.1:5", "http://127.0.0.1:8790", "", 401}, {"foreign_origin", "127.0.0.1:8790", "127.0.0.1:5", "https://evil.invalid", a.adminToken, 403}, {"remote_peer", "127.0.0.1:8790", "192.168.1.3:5", "http://127.0.0.1:8790", a.adminToken, 421}, {"rebind_host", "evil.invalid", "127.0.0.1:5", "http://evil.invalid", a.adminToken, 421}} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest("GET", "http://"+tc.host+"/admin/api/status", nil)
			r.RemoteAddr = tc.peer
			r.Header.Set("Origin", tc.origin)
			r.Header.Set("Authorization", "Bearer "+tc.token)
			w := httptest.NewRecorder()
			a.adminHandler().ServeHTTP(w, r)
			if w.Code != tc.code {
				t.Fatalf("got%d want%d %s", w.Code, tc.code, w.Body.String())
			}
		})
	}
}
func TestWANRequiresTLSAndSeparateAdmin(t *testing.T) {
	a, _ := testApp(t)
	c := a.cfg
	c.PublicBind = "0.0.0.0:8787"
	c.PublicURL = "https://broadcast.example.invalid"
	if validateConfig(c) == nil {
		t.Fatal("WAN without TLS accepted")
	}
	c.TLSCert = "cert.pem"
	c.TLSKey = "key.pem"
	if e := validateConfig(c); e != nil {
		t.Fatal(e)
	}
	r := httptest.NewRequest("GET", "http://127.0.0.1:8787/admin/api/status", nil)
	r.RemoteAddr = "127.0.0.1:1"
	w := httptest.NewRecorder()
	a.publicHandler().ServeHTTP(w, r)
	if w.Code != 404 {
		t.Fatalf("public admin exposed:%d", w.Code)
	}
}
func TestDurablePaginationAndIdempotentLine(t *testing.T) {
	a, _ := testApp(t)
	s := testSession(t, a, []string{"en"})
	for i := 0; i < 1100; i++ {
		id := newID()
		line, e := a.store.newLine(s.ID, fmt.Sprint(i), id)
		if e != nil {
			t.Fatal(e)
		}
		again, e := a.store.newLine(s.ID, "duplicate", id)
		if e != nil || again.SourceText != fmt.Sprint(i) || again.Sequence != line.Sequence {
			t.Fatal("retry duplicated/replaced line")
		}
	}
	var cursor uint64
	count := 0
	for {
		lines, e := a.store.lines(s.ID, cursor, 100)
		if e != nil {
			t.Fatal(e)
		}
		if len(lines) == 0 {
			break
		}
		for _, l := range lines {
			if l.Sequence <= cursor {
				t.Fatal("out-of-order")
			}
			cursor = l.Sequence
			count++
		}
	}
	if count != 1100 {
		t.Fatalf("archive truncated:%d", count)
	}
}
func TestProviderFailureIsolatedAndOriginalDurable(t *testing.T) {
	a, e := testApp(t)
	e.fail = "ja"
	s := testSession(t, a, []string{"en", "ja", "fr"})
	pcm := bytes.Repeat([]byte{0xff, 0x20}, 2048)
	if err := a.pipeline.inputPCM(pcm); err != nil {
		t.Fatal(err)
	}
	path, _ := a.store.recordingPath(s.ID, "source.pcm")
	saved, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(saved, pcm) {
		t.Fatal("original not durable")
	}
	l, err := a.pipeline.text(s.ID, "123입니다")
	if err != nil {
		t.Fatal(err)
	}
	j, err := a.store.claimJob("translate")
	if err != nil || j == nil {
		t.Fatal(err)
	}
	if a.pipeline.processTranslation(*j) == nil {
		t.Fatal("failure was hidden")
	}
	var line Line
	if err := a.store.get("lines", l.ID, &line); err != nil {
		t.Fatal(err)
	}
	if line.Translations["en"] == "" || line.Translations["fr"] == "" || line.Errors["ja"] == "" {
		t.Fatalf("language isolation failed:%+v", line)
	}
	if a.pipeline.current() == nil {
		t.Fatal("broadcast closed")
	}
}
func TestLearningApprovalAndHold(t *testing.T) {
	a, _ := testApp(t)
	lesson := Lesson{ID: newID(), Source: "원문", Language: "en", Proposed: "reviewed result", Status: "pending"}
	if e := a.store.put("lessons", lesson.ID, lesson); e != nil {
		t.Fatal(e)
	}
	if a.pipeline.approved("원문", "en") != "" {
		t.Fatal("unreviewed learning applied")
	}
	lesson.Status = "approved"
	_ = a.store.put("lessons", lesson.ID, lesson)
	if a.pipeline.approved("원문", "en") != "reviewed result" {
		t.Fatal("approved result not used")
	}
	lesson.Status = "held"
	_ = a.store.put("lessons", lesson.ID, lesson)
	if a.pipeline.approved("원문", "en") != "" {
		t.Fatal("held stale cache applied")
	}
}
func TestSlowListenerDisconnectedAndReset(t *testing.T) {
	h := newHub()
	s, e := h.subscribe("source", 1)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = h.subscribe("en", 1); e == nil {
		t.Fatal("capacity not enforced")
	}
	for i := 0; i < 33; i++ {
		h.publish("source", []byte{1, 2})
	}
	select {
	case <-s.done:
	default:
		t.Fatal("slow queue never disconnected")
	}
	if h.dropped.Load() != 1 || h.count() != 0 {
		t.Fatal("queue not bounded")
	}
	next, e := h.subscribe("en", 1)
	if e != nil {
		t.Fatal(e)
	}
	gen := h.gen()
	h.reset()
	select {
	case <-next.done:
	default:
		t.Fatal("old generation open")
	}
	if h.gen() == gen {
		t.Fatal("generation unchanged")
	}
}
func TestSourceReplayRangeAndAuthorization(t *testing.T) {
	a, _ := testApp(t)
	s := testSession(t, a, nil)
	pcm := bytes.Repeat([]byte{0x11, 0x22}, 2048)
	if e := a.pipeline.inputPCM(pcm); e != nil {
		t.Fatal(e)
	}
	for _, token := range []string{"", s.Token} {
		r := httptest.NewRequest("GET", "http://127.0.0.1:8787/api/replay/0/0?channel=source&offset=2&count=64", nil)
		r.RemoteAddr = "127.0.0.1:5"
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		a.publicHandler().ServeHTTP(w, r)
		if token == "" {
			if w.Code != 401 {
				t.Fatal("unauthorized replay")
			}
		} else if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), pcm[2:66]) {
			t.Fatal("PCM replay range changed")
		}
	}
}
func TestSecretRoundTripAndRedaction(t *testing.T) {
	a, _ := testApp(t)
	cfg := a.cfg
	cfg.Online.APIKey = "TEST-ONLY-secret-no-provider"
	if e := a.store.saveConfig(cfg); e != nil {
		t.Fatal(e)
	}
	if a.store.config().Online.APIKey != cfg.Online.APIKey {
		t.Fatal("secret roundtrip")
	}
	a.cfg = cfg
	w := callAdmin(t, a, "GET", "/status", nil)
	if bytes.Contains(w.Body.Bytes(), []byte(cfg.Online.APIKey)) {
		t.Fatal("secret exposed")
	}
	var saved Config
	if e := a.store.get("settings", "config", &saved); e != nil {
		t.Fatal(e)
	}
	if saved.Online.APIKey != "" {
		t.Fatal("secret in config")
	}
}
func TestAtomicReplacementUnicodeAndSpace(t *testing.T) {
	path := filepath.Join(t.TempDir(), "한글 자료", "동일 파일.wav")
	for _, v := range []string{"first", "latest"} {
		if e := atomicFile(path, []byte(v)); e != nil {
			t.Fatal(e)
		}
	}
	b, e := os.ReadFile(path)
	if e != nil || string(b) != "latest" {
		t.Fatal("atomic replacement failed")
	}
}
func TestWAVValidationMalformedChunks(t *testing.T) {
	good := wavBytes([]byte{1, 2, 3, 4})
	if e := validateWAV(good); e != nil {
		t.Fatal(e)
	}
	for _, b := range [][]byte{nil, []byte("RIFF"), append(append([]byte{}, good[:40]...), 0xff, 0xff, 0xff, 0x7f)} {
		if validateWAV(b) == nil {
			t.Fatal("malformed accepted")
		}
	}
}
func TestFileChunkingBoundsAndSurvivesRestart(t *testing.T) {
	a, _ := testApp(t)
	s := testSession(t, a, nil)
	j, e := a.pipeline.queueFile(s.ID, wavBytes(bytes.Repeat([]byte{1, 2}, 16000*43)))
	if e != nil || j.ID == "" {
		t.Fatal(e)
	}
	raw, _ := a.store.all("jobs")
	if len(raw) != 3 {
		t.Fatalf("want3 chunks got%d", len(raw))
	}
	for _, r := range raw {
		var job Job
		_ = json.Unmarshal(r, &job)
		b, e := os.ReadFile(job.Path)
		if e != nil || len(b) > 20*32000+44 {
			t.Fatal("file chunk unbounded")
		}
	}
}
func TestSpeakerCookieScopedExpiredAndTampered(t *testing.T) {
	a, _ := testApp(t)
	token, _ := a.makeSpeakerCookie()
	if !a.validSpeakerCookie(token) || a.validSpeakerCookie(token+"a") {
		t.Fatal("cookie signature")
	}
	a.adminToken = newID()
	if a.validSpeakerCookie(token) {
		t.Fatal("cookie survives restart credential")
	}
}
func TestDurableRecoveryAfterForcedTermination(t *testing.T) {
	if os.Getenv("MCAST_CRASH_CHILD") == "1" {
		dir := os.Getenv("MCAST_CRASH_DIR")
		s, e := openStore(dir)
		if e != nil {
			os.Exit(7)
		}
		session := Session{ID: strings.Repeat("a", 32), State: "active", Kind: "broadcast", SourceLanguage: "ko", Token: newID(), StartedAt: time.Now()}
		_ = s.put("sessions", session.ID, session)
		path, _ := s.recordingPath(session.ID, "pending-input.wav")
		_ = atomicFile(path, wavBytes([]byte{1, 2, 3, 4}))
		j := Job{ID: strings.Repeat("b", 32), SessionID: session.ID, Kind: "stt", Path: path, State: "collecting"}
		_ = s.put("jobs", j.ID, j)
		_, _ = s.newLine(session.ID, "최신 기록", strings.Repeat("c", 32))
		fmt.Fprintln(os.Stdout, "READY")
		time.Sleep(time.Hour)
		os.Exit(9)
	}
	dir := t.TempDir()
	cmd := exec.Command(os.Args[0], "-test.run=^TestDurableRecoveryAfterForcedTermination$")
	cmd.Env = append(os.Environ(), "MCAST_CRASH_CHILD=1", "MCAST_CRASH_DIR="+dir)
	stdout, e := cmd.StdoutPipe()
	if e != nil {
		t.Fatal(e)
	}
	if e = cmd.Start(); e != nil {
		t.Fatal(e)
	}
	ready := make(chan bool, 1)
	go func() {
		b := make([]byte, 6)
		_, e := io.ReadFull(stdout, b)
		ready <- e == nil && string(b) == "READY\n"
	}()
	select {
	case ok := <-ready:
		if !ok {
			cmd.Process.Kill()
			cmd.Wait()
			t.Fatal("child failed to commit")
		}
	case <-time.After(10 * time.Second):
		cmd.Process.Kill()
		cmd.Wait()
		t.Fatal("child timeout")
	}
	if e = cmd.Process.Kill(); e != nil {
		t.Fatal(e)
	}
	_ = cmd.Wait()
	s, e := openStore(dir)
	if e != nil {
		t.Fatal(e)
	}
	defer s.db.Close()
	sessions, e := s.recover()
	if e != nil || len(sessions) != 1 || sessions[0].GapCount != 1 {
		t.Fatal("interrupted state not restored")
	}
	var j Job
	_ = s.get("jobs", strings.Repeat("b", 32), &j)
	if j.State != "queued" {
		t.Fatal("collecting input not requeued")
	}
	lines, e := s.lines(strings.Repeat("a", 32), 0, 100)
	if e != nil || len(lines) != 1 || lines[0].SourceText != "최신 기록" {
		t.Fatal("latest committed transcript lost")
	}
}

func TestBrowserFixture(t *testing.T) {
	if os.Getenv("MCAST_BROWSER_FIXTURE") != "1" {
		t.Skip("only used by the browser acceptance harness")
	}
	a, e := testApp(t)
	e.fail = "ja"
	a.adminToken = os.Getenv("MCAST_ADMIN_TOKEN")
	if a.adminToken == "" {
		t.Fatal("test token missing")
	}
	a.pipeline.startWorkers()
	a.diagnostic = Diagnostic{OS: "windows", Arch: "amd64", RAMGB: 16, AvailableRAMGB: 8, FreeDiskGB: 40, MobileEquivalent: true, Measured: false, Warnings: []string{"시험 fixture: 실제 하드웨어 측정 아님"}, Recommendations: []ModelRecommendation{{ID: "translategemma-4b-q4", Status: "candidate", Backend: "cpu", Reason: "실제 준비/지연 시험 필요"}}}
	adminBind := os.Getenv("MCAST_BROWSER_ADMIN_BIND")
	if adminBind == "" {
		adminBind = "127.0.0.1:18790"
	}
	publicBind := os.Getenv("MCAST_BROWSER_PUBLIC_BIND")
	if publicBind == "" {
		publicBind = "127.0.0.1:18787"
	}
	a.cfg.PublicBind = publicBind
	a.cfg.PublicURL = "http://" + publicBind
	admin := &http.Server{Addr: adminBind, Handler: a.adminHandler()}
	public := &http.Server{Addr: publicBind, Handler: a.publicHandler()}
	adminListener, err := net.Listen("tcp", adminBind)
	if err != nil {
		t.Fatal(err)
	}
	defer adminListener.Close()
	publicListener, err := net.Listen("tcp", publicBind)
	if err != nil {
		t.Fatal(err)
	}
	defer publicListener.Close()
	go admin.Serve(adminListener)
	go public.Serve(publicListener)
	defer admin.Close()
	defer public.Close()
	fmt.Println("BROWSER_FIXTURE_READY")
	select {
	case <-time.After(8 * time.Minute):
	case <-a.pipeline.ctx.Done():
	}
}
