package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

// These tests exercise the real HTTP handlers and transfer/gate lifecycle with
// tiny transfer fixtures. They do not execute Windows or measure AI capacity.
func newEnvironmentTestApp(t *testing.T, label string) (*App, *portableTestFixture, *Engine) {
	t.Helper()
	f := portableTestOpen(t, label)
	e := NewEngine(f.store.dir)
	p := newPipeline(f.store, e)
	a := &App{store: f.store, pipeline: p, assets: f.assets, cfg: f.store.config(), adminToken: newID(), requests: newLimiter(), environment: &environmentController{manager: f.manager}}
	t.Cleanup(func() {
		p.close()
		// An asynchronous transfer must finish before fixture DB cleanup.
		a.envMu.Lock()
		a.envMu.Unlock()
	})
	return a, f, e
}

func environmentTestStatus(t *testing.T, a *App) (environmentOperation, bool) {
	t.Helper()
	w := callAdmin(t, a, http.MethodGet, "/environment/status", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("status: %d %s", w.Code, w.Body.String())
	}
	var status struct {
		Operation       environmentOperation `json:"operation"`
		RestartRequired bool                 `json:"restartRequired"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &status); err != nil {
		t.Fatal(err)
	}
	return status.Operation, status.RestartRequired
}

func environmentTestStart(t *testing.T, a *App, kind, path, fingerprint string) environmentOperation {
	t.Helper()
	w := callAdmin(t, a, http.MethodPost, "/environment/"+kind, classroomJSON(t, map[string]any{"path": path, "fingerprint": fingerprint}))
	if w.Code != http.StatusAccepted {
		t.Fatalf("start %s: %d %s", kind, w.Code, w.Body.String())
	}
	var op environmentOperation
	if err := json.Unmarshal(w.Body.Bytes(), &op); err != nil || op.ID == "" || op.State != "running" {
		t.Fatalf("missing running operation identity: %+v %v", op, err)
	}
	return op
}

func environmentTestWait(t *testing.T, a *App, id string) environmentOperation {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	for {
		op, _ := environmentTestStatus(t, a)
		if op.ID == id && op.State != "running" && a.envMu.TryRLock() {
			a.envMu.RUnlock()
			return op
		}
		select {
		case <-ctx.Done():
			t.Fatalf("environment operation did not finish: %+v", op)
		case <-ticker.C:
		}
	}
}

func environmentTestInspect(t *testing.T, a *App, path string) string {
	t.Helper()
	op := environmentTestStart(t, a, "inspect", path, "")
	op = environmentTestWait(t, a, op.ID)
	if op.State != "completed" || op.Error != "" {
		t.Fatalf("inspect failed: %+v", op)
	}
	b, err := json.Marshal(op.Result)
	if err != nil {
		t.Fatal(err)
	}
	var report PortableInspection
	if err := json.Unmarshal(b, &report); err != nil || !report.Compatible || len(report.Fingerprint) != 64 {
		t.Fatalf("inspection did not issue a package fingerprint: %+v %v", report, err)
	}
	return report.Fingerprint
}

func TestEnvironmentHTTPAuthenticationBeforePathDisclosure(t *testing.T) {
	a, _, _ := newEnvironmentTestApp(t, "auth")
	privatePath := "/private/source-only/SECRET-ENVIRONMENT-PATH.zip"
	a.environment.operation = environmentOperation{ID: newID(), State: "completed", Path: privatePath}
	for _, tc := range []struct {
		name, method, peer, origin, token string
		code                              int
	}{
		{"missing_token_status", "GET", "127.0.0.1:7", "", "", 401},
		{"wrong_token", "POST", "127.0.0.1:7", "http://127.0.0.1:8790", "wrong", 401},
		{"foreign_origin", "POST", "127.0.0.1:7", "https://foreign.invalid", a.adminToken, 403},
		{"remote_admin", "GET", "192.0.2.42:7", "", a.adminToken, 421},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := "/admin/api/environment/status"
			if tc.method == http.MethodPost {
				path = "/admin/api/environment/inspect"
			}
			r := httptest.NewRequest(tc.method, "http://127.0.0.1:8790"+path, bytes.NewReader(classroomJSON(t, map[string]string{"path": privatePath})))
			r.RemoteAddr = tc.peer
			r.Header.Set("Authorization", "Bearer "+tc.token)
			if tc.origin != "" {
				r.Header.Set("Origin", tc.origin)
			}
			w := httptest.NewRecorder()
			a.adminHandler().ServeHTTP(w, r)
			if w.Code != tc.code || strings.Contains(w.Body.String(), privatePath) {
				t.Fatalf("admin auth leaked operation path: %d %s", w.Code, w.Body.String())
			}
		})
	}
	r := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8787/admin/api/environment/status", nil)
	r.RemoteAddr = "127.0.0.1:7"
	w := httptest.NewRecorder()
	a.publicHandler().ServeHTTP(w, r)
	if w.Code != http.StatusNotFound || strings.Contains(w.Body.String(), privatePath) {
		t.Fatal("environment admin state appeared on public server")
	}
}

func TestEnvironmentExclusiveGateRejectsMutations(t *testing.T) {
	a, f, _ := newEnvironmentTestApp(t, "gate")
	before := a.config()
	a.envMu.Lock()
	defer a.envMu.Unlock()
	for _, tc := range []struct {
		path string
		body any
	}{
		{"/models/register", map[string]string{"repo": "fixture/test", "filename": "model.gguf"}},
		{"/models/download", map[string]string{"id": f.ids[0]}},
		{"/models/import", map[string]string{"id": f.ids[0], "path": "/private/never-open.gguf"}},
		{"/config", before},
		{"/rooms", map[string]any{"title": "blocked room", "mode": "lecture", "languages": []string{"ko", "en"}, "capacity": 30}},
		{"/engine/start", map[string]string{}},
	} {
		w := callAdmin(t, a, http.MethodPost, tc.path, classroomJSON(t, tc.body))
		if w.Code != http.StatusConflict {
			t.Fatalf("mutation %s bypassed exclusive gate: %d %s", tc.path, w.Code, w.Body.String())
		}
	}
	r := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:8787/api/classroom/join", strings.NewReader(`{"invite":"not-read","name":"test","language":"en"}`))
	r.RemoteAddr = "127.0.0.1:9"
	r.Header.Set("Origin", "http://127.0.0.1:8787")
	w := httptest.NewRecorder()
	a.publicHandler().ServeHTTP(w, r)
	if w.Code != http.StatusConflict {
		t.Fatalf("public classroom mutation bypassed gate: %d", w.Code)
	}
	if !reflect.DeepEqual(before, f.store.config()) || a.assetTasks.Load() != 0 {
		t.Fatal("blocked mutation changed settings or started asset work")
	}
	if op, _ := environmentTestStatus(t, a); op.ID != "" {
		t.Fatal("gate rejection invented an environment operation")
	}
}

func TestEnvironmentHTTPImportRefreshesConfigAndRequiresRestart(t *testing.T) {
	source := portableTestOpen(t, "api-source")
	archive := portableTestArchive(t, source, true)
	a, f, e := newEnvironmentTestApp(t, "api-target")
	fingerprint := environmentTestInspect(t, a, archive)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	releaseImport := func() { once.Do(func() { close(release) }) }
	t.Cleanup(releaseImport)
	f.manager.beforeCommit = func() error {
		close(entered)
		select {
		case <-release:
			return nil
		case <-a.pipeline.ctx.Done():
			return a.pipeline.ctx.Err()
		}
	}
	op := environmentTestStart(t, a, "import", archive, fingerprint)
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("import did not reach activation gate")
	}
	for _, path := range []string{"/environment/inspect", "/config", "/rooms"} {
		w := callAdmin(t, a, http.MethodPost, path, classroomJSON(t, map[string]string{"path": archive}))
		if w.Code != http.StatusConflict {
			t.Fatalf("concurrent mutation %s was accepted: %d %s", path, w.Code, w.Body.String())
		}
	}
	releaseImport()
	op = environmentTestWait(t, a, op.ID)
	if op.State != "completed" || !op.RestartRequired || !a.envRestart.Load() {
		t.Fatalf("import state/restart requirement missing: %+v", op)
	}
	if !reflect.DeepEqual(a.config(), f.store.config()) || a.config().TranslationModel != source.ids[0] || a.config().STTModel != source.ids[1] || a.config().AutoResume {
		t.Fatal("successful import did not refresh the live safe config")
	}
	w := callAdmin(t, a, http.MethodPost, "/engine/start", []byte(`{}`))
	if w.Code < 400 || !strings.Contains(w.Body.String(), "다시 실행") {
		t.Fatalf("engine start bypassed restart gate: %d %s", w.Code, w.Body.String())
	}
	if e.llamaCmd != nil || e.whisperCmd != nil || e.generation != 0 {
		t.Fatal("import or restart-denied start executed a model process")
	}
	r := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8787/api/session", nil)
	r.RemoteAddr = "192.0.2.55:5555"
	w = httptest.NewRecorder()
	a.publicHandler().ServeHTTP(w, r)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("remote public service continued before environment restart: %d", w.Code)
	}
	_, restart := environmentTestStatus(t, a)
	if !restart {
		t.Fatal("status hid restart requirement")
	}
}

func TestEnvironmentHTTPRejectsPackageChangedAfterInspection(t *testing.T) {
	source := portableTestOpen(t, "fingerprint-source")
	folder := filepath.Join(portableTestTemp(t), "same-package-folder")
	if _, err := source.manager.Export(context.Background(), folder, PortableExportOptions{Format: "folder", AssetIDs: source.ids}); err != nil {
		t.Fatal(err)
	}
	a, f, _ := newEnvironmentTestApp(t, "fingerprint-target")
	fingerprint := environmentTestInspect(t, a, folder)
	beforeConfig, beforeAssets := a.config(), f.store.assets()
	manifestPath := filepath.Join(folder, portableManifestName)
	b, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	var manifest portableManifest
	if err := json.Unmarshal(b, &manifest); err != nil {
		t.Fatal(err)
	}
	manifest.CreatedAt = manifest.CreatedAt.Add(time.Second)
	b, err = json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	if err := atomicFile(manifestPath, b); err != nil {
		t.Fatal(err)
	}
	op := environmentTestStart(t, a, "import", folder, fingerprint)
	op = environmentTestWait(t, a, op.ID)
	if op.State != "failed" || op.Error == "" || a.envRestart.Load() {
		t.Fatalf("changed manifest was accepted after inspect: %+v", op)
	}
	if !reflect.DeepEqual(beforeConfig, a.config()) || !reflect.DeepEqual(beforeAssets, f.store.assets()) {
		t.Fatal("changed package activated before fingerprint rejection")
	}
}

func TestEnvironmentHTTPImportRejectsEngineStartupAndProcesses(t *testing.T) {
	source := portableTestOpen(t, "engine-busy-source")
	archive := portableTestArchive(t, source, false)
	for _, state := range []string{"startup", "llama", "whisper"} {
		t.Run(state, func(t *testing.T) {
			a, f, e := newEnvironmentTestApp(t, "engine-busy-target")
			fingerprint := environmentTestInspect(t, a, archive)
			if state == "startup" {
				e.lifeMu.Lock()
				defer e.lifeMu.Unlock()
			} else {
				// Ownership marker only: no executable or OS process is started.
				e.lifeMu.Lock()
				if state == "llama" {
					e.llamaCmd = &exec.Cmd{}
				} else {
					e.whisperCmd = &exec.Cmd{}
				}
				e.lifeMu.Unlock()
				defer func() { e.lifeMu.Lock(); e.llamaCmd = nil; e.whisperCmd = nil; e.lifeMu.Unlock() }()
			}
			w := callAdmin(t, a, http.MethodPost, "/environment/import", classroomJSON(t, map[string]string{"path": archive, "fingerprint": fingerprint}))
			if w.Code < 400 || w.Code >= 500 {
				t.Fatalf("busy engine accepted transfer: %d %s", w.Code, w.Body.String())
			}
			if op, _ := environmentTestStatus(t, a); op.Kind != "inspect" || op.State != "completed" {
				t.Fatal("busy engine import spawned an operation")
			}
			if !reflect.DeepEqual(f.installed, f.store.assets()) {
				t.Fatal("busy engine import changed installed assets")
			}
		})
	}
}

func TestEnvironmentHTTPRejectsOutstandingAssetTasks(t *testing.T) {
	a, _, _ := newEnvironmentTestApp(t, "asset-task")
	a.assetTasks.Store(1)
	defer a.assetTasks.Store(0)
	for _, kind := range []string{"inspect", "export", "import"} {
		w := callAdmin(t, a, http.MethodPost, "/environment/"+kind, classroomJSON(t, map[string]any{"path": "/private/never-read.zip", "fingerprint": strings.Repeat("a", 64), "options": PortableExportOptions{Format: "zip"}}))
		if w.Code < 400 || w.Code >= 500 {
			t.Fatalf("asset task did not block %s: %d", kind, w.Code)
		}
	}
	if op, _ := environmentTestStatus(t, a); op.ID != "" {
		t.Fatal("asset tasks allowed an asynchronous environment operation")
	}
}

func TestEnvironmentPersistedRunningOperationRecoversInterrupted(t *testing.T) {
	a, f, e := newEnvironmentTestApp(t, "recovery")
	old := environmentOperation{ID: newID(), Kind: "import", State: "running", Path: "/private/interrupted-package.zip", StartedAt: time.Now().Add(-time.Minute), Phase: "verifying", CompletedBytes: 42, TotalBytes: 100}
	if err := f.store.put("settings", "environment-operation", old); err != nil {
		t.Fatal(err)
	}
	// A new controller instance reads the durable record as after app restart.
	a.envStateMu.Lock()
	a.environment = nil
	a.envStateMu.Unlock()
	op, restart := environmentTestStatus(t, a)
	if op.ID != old.ID || op.State != "interrupted" || op.Error == "" || restart {
		t.Fatalf("interrupted transfer did not recover honestly: %+v", op)
	}
	var saved environmentOperation
	if err := f.store.get("settings", "environment-operation", &saved); err != nil || saved.State != "interrupted" {
		t.Fatalf("recovery state not durable: %+v %v", saved, err)
	}
	if !reflect.DeepEqual(f.installed, f.store.assets()) || e.llamaCmd != nil || e.whisperCmd != nil {
		t.Fatal("controller recovery activated files or started models")
	}
}

func TestPortableBrowserFixture(t *testing.T) {
	if os.Getenv("MCAST_BROWSER_FIXTURE") != "1" {
		t.Skip("opt-in real browser harness; transfer fixtures are not runnable AI models")
	}
	source := portableTestOpen(t, "browser-source")
	root, err := os.MkdirTemp("", "mcasttalk-portable-browser-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(root) })
	packagePath := filepath.Join(root, "prepared-offline.zip")
	if _, err := source.manager.Export(context.Background(), packagePath, PortableExportOptions{Format: "zip", AssetIDs: source.ids}); err != nil {
		t.Fatal(err)
	}
	a, f, _ := newEnvironmentTestApp(t, "browser-target")
	a.adminToken = os.Getenv("MCAST_ADMIN_TOKEN")
	if a.adminToken == "" {
		a.adminToken = "mcast-desktop-fixture-20261005-only"
	}
	adminBind, publicBind := os.Getenv("MCAST_BROWSER_ADMIN_BIND"), os.Getenv("MCAST_BROWSER_PUBLIC_BIND")
	if adminBind == "" {
		adminBind = "127.0.0.1:18890"
	}
	if publicBind == "" {
		publicBind = "127.0.0.1:18887"
	}
	a.cfg.PublicBind = publicBind
	if err := f.store.saveConfig(a.cfg); err != nil {
		t.Fatal(err)
	}
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
	admin := &http.Server{Handler: a.adminHandler(), ReadHeaderTimeout: 5 * time.Second}
	public := &http.Server{Handler: a.publicHandler(), ReadHeaderTimeout: 5 * time.Second}
	go admin.Serve(adminListener)
	go public.Serve(publicListener)
	defer admin.Close()
	defer public.Close()
	metadata, _ := json.Marshal(map[string]any{"packagePath": packagePath, "exportPath": filepath.Join(root, "exported-offline.zip"), "adminURL": "http://" + adminBind, "publicURL": "http://" + publicBind, "fixtureOnly": true, "modelsExecuted": false})
	fmt.Println("PORTABLE_BROWSER_FIXTURE_READY", string(metadata))
	ctx, cancel := context.WithTimeout(a.pipeline.ctx, 2*time.Minute)
	defer cancel()
	<-ctx.Done()
}
