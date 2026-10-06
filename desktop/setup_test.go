package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	bolt "go.etcd.io/bbolt"
)

// Downloads use tiny localhost fixtures and actual hash/install code. The
// traced engine is an interface fixture, not Windows/model/quality evidence.
type setupTestEngine struct {
	mu             sync.Mutex
	ready          bool
	starts, stops  int
	transcriptions int
	translations   map[string]int
	voices         map[string]int
	wav            []byte
	assets         map[string]InstalledAsset
	startConfig    Config
	inputLanguages map[string]int
	sttError       bool
	voiceEntered   chan struct{}
}

func (e *setupTestEngine) Start(ctx context.Context, cfg Config, assets map[string]InstalledAsset) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.starts++
	e.startConfig = cfg
	e.ready = true
	e.assets = make(map[string]InstalledAsset, len(assets))
	for id, asset := range assets {
		e.assets[id] = asset
	}
	return nil
}
func (e *setupTestEngine) Stop() { e.mu.Lock(); e.stops++; e.ready = false; e.mu.Unlock() }
func (e *setupTestEngine) Ready() EngineStatus {
	e.mu.Lock()
	defer e.mu.Unlock()
	return EngineStatus{TranslationReady: e.ready, STTReady: e.ready, TTSReady: e.ready, Backend: "fixture"}
}
func (e *setupTestEngine) Translate(ctx context.Context, text, source, target, recent string, terms []GlossaryTerm) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	e.mu.Lock()
	e.translations[target]++
	e.mu.Unlock()
	return target + ":" + text, nil
}
func (e *setupTestEngine) Transcribe(ctx context.Context, wav []byte, language string) (string, error) {
	if err := validateWAV(wav); err != nil {
		return "", err
	}
	e.mu.Lock()
	e.transcriptions++
	e.inputLanguages[language]++
	e.wav = append([]byte(nil), wav...)
	fail := e.sttError
	e.mu.Unlock()
	if fail {
		return "", errors.New("fixture transcription failure")
	}
	return "안녕하세요. 수업은 열 시에 시작합니다.", ctx.Err()
}
func (e *setupTestEngine) Synthesize(ctx context.Context, text, language string) ([]byte, error) {
	e.mu.Lock()
	e.voices[language]++
	entered := e.voiceEntered
	if entered != nil {
		e.voiceEntered = nil
	}
	e.mu.Unlock()
	if entered != nil {
		close(entered)
		<-ctx.Done()
		return nil, ctx.Err()
	}
	return bytes.Repeat([]byte{1, 2}, 1600), ctx.Err()
}

func newSetupTestApp(t *testing.T, label string) (*App, *portableTestFixture, *setupTestEngine) {
	t.Helper()
	f := portableTestOpen(t, label)
	if err := f.store.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket([]byte("assets"))
		keys := [][]byte{}
		if err := b.ForEach(func(k, _ []byte) error { keys = append(keys, append([]byte(nil), k...)); return nil }); err != nil {
			return err
		}
		for _, k := range keys {
			if err := b.Delete(k); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	e := &setupTestEngine{translations: map[string]int{}, voices: map[string]int{}, inputLanguages: map[string]int{}}
	p := newPipeline(f.store, e)
	// This engine supplies deterministic speech interfaces; the production
	// controller instead installs and starts the actual bundled TTS models.
	a := &App{store: f.store, pipeline: p, assets: f.assets, cfg: f.store.config(), adminToken: newID(), requests: newLimiter(), setup: &setupController{validate: portableProfile}}
	a.diagnostic = Diagnostic{OS: "windows", Arch: "amd64", Cores: 8, RAMGB: 64, AvailableRAMGB: 48, FreeDiskGB: 64, Measured: true}
	t.Cleanup(func() { p.close(); a.envMu.Lock(); a.envMu.Unlock() })
	return a, f, e
}

func setupTestPlan(t *testing.T, a *App) setupPlan {
	t.Helper()
	w := callAdmin(t, a, http.MethodGet, "/setup/plan", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("plan: %d %s", w.Code, w.Body.String())
	}
	var plan setupPlan
	if err := json.Unmarshal(w.Body.Bytes(), &plan); err != nil {
		t.Fatal(err)
	}
	return plan
}
func setupTestStatus(t *testing.T, a *App) setupOperation {
	t.Helper()
	w := callAdmin(t, a, http.MethodGet, "/setup/status", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("setup status: %d %s", w.Code, w.Body.String())
	}
	var status struct {
		Operation setupOperation `json:"operation"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &status); err != nil {
		t.Fatal(err)
	}
	return status.Operation
}
func setupTestStart(t *testing.T, a *App, plan setupPlan) setupOperation {
	t.Helper()
	w := callAdmin(t, a, http.MethodPost, "/setup/download", classroomJSON(t, map[string]any{"fingerprint": plan.Fingerprint, "consent": true}))
	if w.Code != http.StatusAccepted {
		t.Fatalf("setup start: %d %s", w.Code, w.Body.String())
	}
	var op setupOperation
	if err := json.Unmarshal(w.Body.Bytes(), &op); err != nil || op.ID == "" || op.State != "running" {
		t.Fatalf("bad queued operation: %+v %v", op, err)
	}
	return op
}
func setupTestWait(t *testing.T, a *App, id string) setupOperation {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	for {
		op := setupTestStatus(t, a)
		if op.ID == id && op.State != "running" && a.assetTasks.Load() == 0 && a.envMu.TryRLock() {
			a.envMu.RUnlock()
			return op
		}
		select {
		case <-ctx.Done():
			t.Fatalf("setup operation did not finish: %+v", op)
		case <-ticker.C:
		}
	}
}

func TestSetupReadAndConsentFailuresMakeNoNetworkRequest(t *testing.T) {
	oldDefault, oldClient := http.DefaultTransport, http.DefaultClient.Transport
	var requests atomic.Int64
	blocked := &mockTransport{roundTripFunc: func(*http.Request) (*http.Response, error) {
		requests.Add(1)
		return nil, errors.New("unexpected model network request")
	}}
	http.DefaultTransport, http.DefaultClient.Transport = blocked, blocked
	defer func() { http.DefaultTransport, http.DefaultClient.Transport = oldDefault, oldClient }()
	a, _, e := newSetupTestApp(t, "consent")
	plan := setupTestPlan(t, a)
	if !plan.Compatible || len(plan.Missing) != 4 {
		t.Fatalf("bad controlled Windows plan: %+v", plan)
	}
	setupTestStatus(t, a)
	for _, body := range []map[string]any{
		{},
		{"fingerprint": plan.Fingerprint},
		{"fingerprint": plan.Fingerprint, "consent": false},
		{"consent": true},
		{"fingerprint": strings.Repeat("0", 64), "consent": true},
	} {
		w := callAdmin(t, a, http.MethodPost, "/setup/download", classroomJSON(t, body))
		if w.Code < 400 || w.Code >= 500 {
			t.Fatalf("missing consent/fingerprint accepted: %d %s", w.Code, w.Body.String())
		}
	}
	if requests.Load() != 0 || setupTestStatus(t, a).ID != "" || e.starts != 0 {
		t.Fatal("startup/read/rejected choice triggered network or engine")
	}
}

func TestSetupPlanDependenciesCompatibilityAndUnknownProfile(t *testing.T) {
	a, f, _ := newSetupTestApp(t, "dependencies")
	setBackend := func(backend string) {
		a.cfgMu.Lock()
		a.cfg.Backend = backend
		cfg := a.cfg
		a.cfgMu.Unlock()
		if err := f.store.saveConfig(cfg); err != nil {
			t.Fatal(err)
		}
	}
	assertIDs := func(plan setupPlan, expected []string) {
		actual := map[string]bool{}
		var total int64
		for _, art := range plan.Artifacts {
			actual[art.ID] = true
		}
		for _, art := range plan.Missing {
			total += art.Bytes
		}
		if len(actual) != len(expected) || len(plan.Missing) != len(expected) || total != plan.DownloadBytes || plan.RequiredDiskBytes < plan.DownloadBytes || len(plan.Fingerprint) != 64 {
			t.Fatalf("incomplete dependency/size plan: %+v", plan)
		}
		for _, id := range expected {
			if !actual[id] {
				t.Fatalf("required profile missing: %s", id)
			}
		}
	}
	base := []string{f.ids[0], f.ids[1], "runtime-llama-cpu", "runtime-whisper-cpu"}
	assertIDs(setupTestPlan(t, a), base)
	setBackend("cuda")
	cuda := append(append([]string(nil), base...), "runtime-llama-cuda", "runtime-llama-cudart")
	if _, ok := a.assets.getArtifact("runtime-whisper-cuda"); ok {
		assertIDs(setupTestPlan(t, a), append(append([]string(nil), cuda...), "runtime-whisper-cuda"))
	}
	a.assets.mu.Lock()
	registry := []Artifact{}
	for _, art := range a.assets.registry {
		if art.ID != "runtime-whisper-cuda" {
			registry = append(registry, art)
		}
	}
	a.assets.registry = registry
	a.assets.mu.Unlock()
	assertIDs(setupTestPlan(t, a), cuda)
	setBackend("cpu")
	for _, condition := range []string{"unmeasured", "ram", "disk", "os"} {
		original := a.diagnostic
		a.diagMu.Lock()
		switch condition {
		case "unmeasured":
			a.diagnostic.Measured = false
		case "ram":
			a.diagnostic.AvailableRAMGB = 0
		case "disk":
			a.diagnostic.FreeDiskGB = 0
		case "os":
			a.diagnostic.OS = "darwin"
		}
		a.diagMu.Unlock()
		plan := setupTestPlan(t, a)
		if plan.Compatible || len(plan.Warnings) == 0 {
			t.Fatalf("unsafe condition %s considered compatible", condition)
		}
		w := callAdmin(t, a, http.MethodPost, "/setup/download", classroomJSON(t, map[string]any{"fingerprint": plan.Fingerprint, "consent": true}))
		if w.Code < 400 {
			t.Fatal("unmeasured/incompatible PC started downloading")
		}
		a.diagMu.Lock()
		a.diagnostic = original
		a.diagMu.Unlock()
	}
	a.cfgMu.Lock()
	a.cfg.TranslationModel = "unknown-model"
	a.cfgMu.Unlock()
	w := callAdmin(t, a, http.MethodGet, "/setup/plan", nil)
	if w.Code < 400 {
		t.Fatal("unknown model silently chose a replacement")
	}
	if op := setupTestStatus(t, a); op.ID != "" {
		t.Fatal("bad plan queued setup")
	}
}

type setupTestSource struct {
	mu       sync.Mutex
	requests map[string]int
	blocked  chan struct{}
	stop     chan struct{}
	blockID  string
	mode     string
}

func (s *setupTestSource) count(id string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.requests[id]
}

func setupTestLocalSource(t *testing.T, a *App, f *portableTestFixture, mode string) *setupTestSource {
	t.Helper()
	s := &setupTestSource{requests: map[string]int{}, blocked: make(chan struct{}), stop: make(chan struct{}), mode: mode}
	s.blockID = f.ids[1]
	payloads := map[string][]byte{}
	for _, id := range f.ids[:4] {
		asset := f.installed[id]
		if strings.HasPrefix(id, "runtime-") {
			var b bytes.Buffer
			z := zip.NewWriter(&b)
			err := filepath.Walk(asset.Path, func(path string, info os.FileInfo, err error) error {
				if err != nil || info.IsDir() || filepath.Base(path) == "installed-manifest.json" {
					return err
				}
				rel, err := filepath.Rel(asset.Path, path)
				if err != nil {
					return err
				}
				h := &zip.FileHeader{Name: filepath.ToSlash(rel), Method: zip.Store}
				h.SetMode(0600)
				w, err := z.CreateHeader(h)
				if err != nil {
					return err
				}
				data, err := os.ReadFile(path)
				if err != nil {
					return err
				}
				_, err = w.Write(data)
				return err
			})
			if err != nil {
				t.Fatal(err)
			}
			if err := z.Close(); err != nil {
				t.Fatal(err)
			}
			payloads[id] = b.Bytes()
		} else {
			b, err := os.ReadFile(asset.Path)
			if err != nil {
				t.Fatal(err)
			}
			payloads[id] = b
		}
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := strings.TrimPrefix(r.URL.Path, "/")
		s.mu.Lock()
		s.requests[id]++
		s.mu.Unlock()
		if id == s.blockID && mode == "cancel" {
			select {
			case <-s.blocked:
			default:
				close(s.blocked)
			}
			select {
			case <-r.Context().Done():
			case <-s.stop:
			}
			return
		}
		if id == s.blockID && mode == "fail" {
			http.Error(w, "fixture source unavailable", http.StatusServiceUnavailable)
			return
		}
		data, ok := payloads[id]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Length", fmt.Sprint(len(data)))
		if mode == "corrupt" && id == s.blockID {
			data = append([]byte(nil), data...)
			data[len(data)-1] ^= 1
		}
		_, _ = w.Write(data)
	}))
	t.Cleanup(server.Close)
	t.Cleanup(func() { close(s.stop) })
	a.assets.mu.Lock()
	for i, art := range a.assets.registry {
		if data, ok := payloads[art.ID]; ok {
			art.URL, art.Bytes, art.SHA256 = server.URL+"/"+art.ID, int64(len(data)), portableTestHash(data)
			if artifactBundle(art) {
				art.TreeSHA256 = f.manager.bundleTreePins[art.ID]
			}
			a.assets.registry[i] = art
		}
	}
	a.assets.mu.Unlock()
	// Only local fixture profiles use this hook. Production validates immutable sources.
	a.setup.validate = func(Artifact) error { return nil }
	return s
}

func TestSetupLocalDownloadsInstallAndExerciseSpeechInterfaces(t *testing.T) {
	a, f, e := newSetupTestApp(t, "download")
	a.cfg.Online = OnlineConfig{Endpoint: "https://example.invalid", Consent: true}
	source := setupTestLocalSource(t, a, f, "ok")
	plan := setupTestPlan(t, a)
	op := setupTestStart(t, a, plan)
	op = setupTestWait(t, a, op.ID)
	if op.State != "verified" || !op.FunctionalVerified || op.QualityVerified || op.LiveSLAVerified || op.Completed != 4 || op.Error != "" {
		t.Fatalf("incorrect setup verification result: %+v", op)
	}
	for _, check := range op.Checks {
		if !check.Passed {
			t.Fatalf("functional check failed: %+v", check)
		}
	}
	installed := a.store.assets()
	for _, art := range plan.Artifacts {
		asset, ok := installed[art.ID]
		if !ok || source.count(art.ID) != 1 || asset.SHA256 != art.SHA256 || asset.Bytes != art.Bytes {
			t.Fatalf("download/install missing or repeated: %s", art.ID)
		}
		if art.Task == "runtime" {
			if err := verifyPinnedArtifactBundle(context.Background(), asset.Path, art); err != nil {
				t.Fatal(err)
			}
		} else if err := verifyModel(context.Background(), asset.Path, art.SHA256, art.Bytes, nil); err != nil {
			t.Fatal(err)
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.starts != 1 || e.stops != 0 || e.transcriptions != 5 || len(e.assets) != 4 || e.startConfig.Online != (OnlineConfig{}) || !bytes.Equal(e.wav, wavBytes(bytes.Repeat([]byte{1, 2}, 1600))) {
		t.Fatal("downloaded setup did not start and transcribe its actual generated WAV")
	}
	if !reflect.DeepEqual(e.inputLanguages, map[string]int{"ko": 1, "en": 1, "ja": 1, "zh": 1, "es": 1}) || !reflect.DeepEqual(e.translations, map[string]int{"ko": 4, "en": 1, "ja": 1, "zh": 1, "es": 1}) || !reflect.DeepEqual(e.voices, map[string]int{"ko": 2, "en": 1, "ja": 1, "zh": 1, "es": 1}) {
		t.Fatalf("language interface checks incomplete: translation=%v voice=%v", e.translations, e.voices)
	}
	if next := setupTestPlan(t, a); len(next.Missing) != 0 || next.DownloadBytes != 0 {
		t.Fatal("installed files were not reused by next launch plan")
	}
}

func TestSetupLowRAMAllowsExplicitPreparationWithoutClaimingReadiness(t *testing.T) {
	a, f, e := newSetupTestApp(t, "prepare-low-ram")
	source := setupTestLocalSource(t, a, f, "ok")
	a.diagMu.Lock()
	a.diagnostic.AvailableRAMGB = 1
	a.diagMu.Unlock()
	plan := setupTestPlan(t, a)
	if plan.Compatible || plan.RuntimeCompatible || !plan.PreparationCompatible || len(plan.RuntimeIssues) != 1 {
		t.Fatalf("RAM shortage still prevents file preparation or claims runtime fit: %+v", plan)
	}
	for _, payload := range []map[string]any{
		{"fingerprint": plan.Fingerprint, "consent": true},
		{"fingerprint": plan.Fingerprint, "prepareOnly": true},
	} {
		w := callAdmin(t, a, http.MethodPost, "/setup/download", classroomJSON(t, payload))
		if w.Code < 400 {
			t.Fatal("low-RAM run or unconsented preparation was accepted")
		}
	}
	w := callAdmin(t, a, http.MethodPost, "/setup/download", classroomJSON(t, map[string]any{"fingerprint": plan.Fingerprint, "consent": true, "prepareOnly": true}))
	if w.Code != http.StatusAccepted {
		t.Fatalf("explicit file preparation rejected: %d %s", w.Code, w.Body.String())
	}
	var queued setupOperation
	if err := json.Unmarshal(w.Body.Bytes(), &queued); err != nil {
		t.Fatal(err)
	}
	op := setupTestWait(t, a, queued.ID)
	if op.State != "prepared" || !op.PreparationOnly || op.FunctionalVerified || op.QualityVerified || op.LiveSLAVerified || len(op.Checks) != 0 || op.Completed != 4 || e.starts != 0 {
		t.Fatalf("file preparation started an engine or claimed verified service: %+v", op)
	}
	for _, id := range f.ids[:4] {
		if source.count(id) != 1 || a.store.assets()[id].ID != id {
			t.Fatalf("file preparation skipped installation: %s", id)
		}
	}
	// A restart reads the distinct state; only a new explicit full operation
	// after recovering capacity may run the actual functional validation.
	a.setupStateMu.Lock()
	a.setup = nil
	a.setupStateMu.Unlock()
	if restored := setupTestStatus(t, a); restored.State != "prepared" || restored.FunctionalVerified {
		t.Fatal("prepared state was not retained honestly across controller recovery")
	}
	// This controlled registry remains a localhost fixture after recovery.
	a.setupController().validate = func(Artifact) error { return nil }
	a.diagMu.Lock()
	a.diagnostic.AvailableRAMGB = 48
	a.diagMu.Unlock()
	next := setupTestPlan(t, a)
	if !next.RuntimeCompatible || len(next.Missing) != 0 {
		t.Fatal("prepared files were not reused when runtime capacity recovered")
	}
	op = setupTestWait(t, a, setupTestStart(t, a, next).ID)
	if op.State != "verified" || op.PreparationOnly || !op.FunctionalVerified || e.starts != 1 {
		t.Fatal("explicit subsequent validation failed to publish only actual interface readiness")
	}
}

func TestSetupPreparationNeverBypassesPlatformMeasurementOrDiskChecks(t *testing.T) {
	for _, condition := range []string{"unmeasured", "disk", "os", "arch"} {
		t.Run(condition, func(t *testing.T) {
			a, _, _ := newSetupTestApp(t, "prepare-"+condition)
			switch condition {
			case "unmeasured":
				a.diagnostic.Measured = false
			case "disk":
				a.diagnostic.FreeDiskGB = 0
			case "os":
				a.diagnostic.OS = "darwin"
			case "arch":
				a.diagnostic.Arch = "arm64"
			}
			plan := setupTestPlan(t, a)
			if plan.PreparationCompatible || plan.RuntimeCompatible {
				t.Fatalf("unsafe preparation accepted: %+v", plan)
			}
			w := callAdmin(t, a, http.MethodPost, "/setup/download", classroomJSON(t, map[string]any{"fingerprint": plan.Fingerprint, "consent": true, "prepareOnly": true}))
			if w.Code < 400 || setupTestStatus(t, a).ID != "" {
				t.Fatal("preparation bypassed a platform/space guard")
			}
		})
	}
}

// Opt-in UI harness: tiny real downloads and an explicit engine interface
// fixture. It provides no evidence of Windows/model inference or latency.
func TestSetupBrowserFixture(t *testing.T) {
	if os.Getenv("MCAST_SETUP_BROWSER_FIXTURE") != "1" {
		t.Skip("opt-in setup UI harness; fixture models are not executable")
	}
	a, f, e := newSetupTestApp(t, "setup-browser")
	source := setupTestLocalSource(t, a, f, "ok")
	a.adminToken = "mcast-desktop-setup-fixture-only"
	bind := "127.0.0.1:18890"
	listener, err := net.Listen("tcp", bind)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	admin := a.adminHandler()
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/admin/api/diagnostics" && equalToken(bearer(r), a.adminToken) {
			jsonReply(w, 200, a.diagnostic)
			return
		}
		if r.URL.Path == "/fixture/stats" {
			e.mu.Lock()
			defer e.mu.Unlock()
			source.mu.Lock()
			defer source.mu.Unlock()
			jsonReply(w, 200, map[string]any{"fixtureOnly": true, "modelsExecuted": false, "requests": source.requests, "starts": e.starts, "inputLanguages": e.inputLanguages, "translationTargets": e.translations, "voices": e.voices})
			return
		}
		admin.ServeHTTP(w, r)
	})
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second}
	go server.Serve(listener)
	defer server.Close()
	fmt.Println("SETUP_BROWSER_FIXTURE_READY", "http://"+bind+"/#token="+a.adminToken)
	ctx, cancel := context.WithTimeout(a.pipeline.ctx, 3*time.Minute)
	defer cancel()
	<-ctx.Done()
}

func TestSetupQueueCancellationFailureAndCorruptionPreserveCompletedAssets(t *testing.T) {
	for _, mode := range []string{"cancel", "fail", "corrupt"} {
		t.Run(mode, func(t *testing.T) {
			a, f, e := newSetupTestApp(t, "queue-"+mode)
			source := setupTestLocalSource(t, a, f, mode)
			plan := setupTestPlan(t, a)
			op := setupTestStart(t, a, plan)
			if mode == "cancel" {
				select {
				case <-source.blocked:
				case <-time.After(5 * time.Second):
					t.Fatal("second download did not block")
				}
				for _, path := range []string{"/config", "/models/download", "/environment/inspect"} {
					var body any = map[string]string{"path": "/private/unused", "id": f.ids[0]}
					if path == "/config" {
						body = a.config()
					} else if path == "/environment/inspect" {
						body = map[string]string{"path": "/private/unused"}
					}
					w := callAdmin(t, a, http.MethodPost, path, classroomJSON(t, body))
					if w.Code != http.StatusConflict {
						t.Fatalf("setup did not exclusively gate %s: %d", path, w.Code)
					}
				}
				w := callAdmin(t, a, http.MethodPost, "/setup/cancel", []byte(`{}`))
				if w.Code != http.StatusAccepted {
					t.Fatalf("cancel blocked by setup gate: %d", w.Code)
				}
			}
			op = setupTestWait(t, a, op.ID)
			expectedState := "failed"
			if mode == "cancel" {
				expectedState = "cancelled"
			}
			if op.State != expectedState || op.FunctionalVerified || op.Completed != 1 || op.Error == "" {
				t.Fatalf("queue failure/cancel reported verification: %+v", op)
			}
			installed := a.store.assets()
			if len(installed) != 1 || installed[f.ids[0]].ID == "" {
				t.Fatalf("completed asset lost or unfinished asset installed: %v", installed)
			}
			first := installed[f.ids[0]]
			if err := verifyModel(context.Background(), first.Path, first.SHA256, first.Bytes, []byte("GGUF")); err != nil {
				t.Fatal(err)
			}
			for _, id := range f.ids[2:] {
				if source.count(id) != 0 {
					t.Fatalf("queue continued after failure: %s", id)
				}
			}
			if e.starts != 0 || e.stops == 0 || e.Ready().TranslationReady {
				t.Fatal("failed queue started/left engine ready")
			}
		})
	}
}

func TestSetupSpeechFailureOrCancellationStopsEngineWithoutQualityClaim(t *testing.T) {
	for _, mode := range []string{"stt_failure", "voice_cancel"} {
		t.Run(mode, func(t *testing.T) {
			a, f, e := newSetupTestApp(t, "speech-"+mode)
			setupTestLocalSource(t, a, f, "ok")
			var entered chan struct{}
			if mode == "stt_failure" {
				e.sttError = true
			} else {
				entered = make(chan struct{})
				e.voiceEntered = entered
			}
			op := setupTestStart(t, a, setupTestPlan(t, a))
			if entered != nil {
				select {
				case <-entered:
				case <-time.After(5 * time.Second):
					t.Fatal("source voice check not reached")
				}
				w := callAdmin(t, a, http.MethodPost, "/setup/cancel", []byte(`{}`))
				if w.Code != http.StatusAccepted {
					t.Fatal("speech verification could not be cancelled")
				}
			}
			op = setupTestWait(t, a, op.ID)
			if op.FunctionalVerified || op.QualityVerified || op.LiveSLAVerified || op.Error == "" || (op.State != "failed" && op.State != "cancelled") {
				t.Fatalf("speech failure claimed success: %+v", op)
			}
			if len(a.store.assets()) != 4 || e.starts != 1 || e.stops == 0 || e.Ready().TTSReady {
				t.Fatal("verification failure lost downloads or kept engine running")
			}
		})
	}
}

func TestSetupInterruptedOperationRecoveryDoesNotAutomaticallyRetry(t *testing.T) {
	a, _, e := newSetupTestApp(t, "setup-recovery")
	old := setupOperation{ID: newID(), State: "running", Phase: "파일 구성", ArtifactIDs: []string{"not-started"}, StartedAt: time.Now().Add(-time.Minute)}
	if err := a.store.put("settings", "setup-operation", old); err != nil {
		t.Fatal(err)
	}
	a.setupStateMu.Lock()
	a.setup = nil
	a.setupStateMu.Unlock()
	op := setupTestStatus(t, a)
	if op.ID != old.ID || op.State != "interrupted" || op.Error == "" || op.FunctionalVerified || e.starts != 0 || a.assetTasks.Load() != 0 {
		t.Fatalf("startup retried or verified interrupted setup: %+v", op)
	}
}

func TestSetupCompletionSerializesCancellationAndPersistsItsVerdict(t *testing.T) {
	for _, cancellationFirst := range []bool{true, false} {
		t.Run(fmt.Sprint(cancellationFirst), func(t *testing.T) {
			a, _, engine := newSetupTestApp(t, "finalization")
			c := a.setupController()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			c.operation = setupOperation{ID: newID(), State: "running"}
			c.cancel = cancel
			if cancellationFirst {
				// Successful verification already returned nil; cancellation
				// wins before final acceptance acquires the shared mutex.
				cancel()
			}
			a.finishSetup(ctx, c, nil)
			var persisted setupOperation
			if err := a.store.get("settings", "setup-operation", &persisted); err != nil {
				t.Fatal(err)
			}
			if cancellationFirst {
				if persisted.State != "cancelled" || persisted.FunctionalVerified || engine.stops != 1 {
					t.Fatalf("successful verification overwrote cancellation: %+v", persisted)
				}
			} else {
				w := callAdmin(t, a, http.MethodPost, "/setup/cancel", nil)
				if persisted.State != "verified" || !persisted.FunctionalVerified || engine.stops != 0 || ctx.Err() != nil || !strings.Contains(w.Body.String(), `"state":"verified"`) {
					t.Fatalf("completed operation was cancelled: %+v %s", persisted, w.Body.String())
				}
			}
		})
	}
}

func TestSetupVerdictStorageFailureCannotReportVerified(t *testing.T) {
	a, _, engine := newSetupTestApp(t, "verdict-storage-failure")
	c := a.setupController()
	c.operation = setupOperation{ID: newID(), State: "running"}
	if err := a.store.db.Close(); err != nil {
		t.Fatal(err)
	}
	a.finishSetup(context.Background(), c, nil)
	if c.operation.State != "failed" || c.operation.FunctionalVerified || engine.stops != 1 || !strings.Contains(c.operation.Error, "저장하지 못했습니다") {
		t.Fatalf("unpersisted verdict reported success: %+v", c.operation)
	}
}
