package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// These local HTTP fixtures exercise the real startup warmup and finalization
// paths. They do not load a model or establish the actual Windows C6 cause.
func TestEngineStartupWarmupFailureNeverPublishesReady(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		http.Error(w, "PRIVATE_UPSTREAM_BODY_CANARY", http.StatusServiceUnavailable)
	}))
	defer server.Close()
	e := NewEngine(t.TempDir())
	e.cfg.TranslationModel = "qwen3-fixture"
	e.llamaURL = server.URL
	e.ready = EngineStatus{STTReady: true, TTSReady: true}
	e.generation = 1
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	err := e.warmLlama(ctx, server.URL, "", "qwen3-fixture", "chat", 1, make(chan error))
	if err == nil || !strings.Contains(err.Error(), "503") || strings.Contains(err.Error(), "PRIVATE_UPSTREAM_BODY_CANARY") {
		t.Fatal("failed warmup was accepted or disclosed the upstream body")
	}
	if e.Ready().TranslationReady {
		t.Fatal("failed warmup published public translation readiness")
	}
	if _, err := e.TranslatePrivate(ctx, "ordinary input", "en", "ko", "", nil); err == nil || calls.Load() != 1 {
		t.Fatal("ordinary translation reached a runtime after failed warmup")
	}
}

func TestEngineStartupPublishesReadyOnlyAfterLocalWarmup(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		calls.Add(1)
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer synthetic-runtime-key" {
			t.Error("warmup did not preserve the local authenticated request protocol")
		}
		once.Do(func() { close(entered) })
		select {
		case <-release:
		case <-r.Context().Done():
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"warm translation"},"finish_reason":"stop"}]}`))
	}))
	defer server.Close()
	e := NewEngine(t.TempDir())
	e.cfg.TranslationModel, e.profile.Prompt = "qwen3-fixture", "chat"
	// Warmup must remain local despite an explicitly configured online mode.
	e.cfg.Online = OnlineConfig{Endpoint: "https://online.invalid", Consent: true}
	e.llamaURL, e.llamaKey, e.generation = server.URL, "synthetic-runtime-key", 3
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	result := make(chan error, 1)
	go func() {
		result <- e.warmLlama(ctx, server.URL, e.llamaKey, e.cfg.TranslationModel, "chat", 3, make(chan error))
	}()
	awaitHealthSignal(t, entered)
	if e.Ready().TranslationReady {
		t.Fatal("readiness was visible while warmup was still pending")
	}
	if _, err := e.TranslatePrivate(ctx, "ordinary input", "en", "ko", "", nil); err == nil || calls.Load() != 1 {
		t.Fatal("ordinary translation bypassed the public readiness gate during warmup")
	}
	close(release)
	select {
	case err := <-result:
		if err != nil {
			t.Fatalf("local warmup failed: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("local warmup did not complete")
	}
	if !e.Ready().TranslationReady {
		t.Fatal("successful warmup did not publish readiness")
	}
	if _, err := e.TranslatePrivate(ctx, "ordinary input", "en", "ko", "", nil); err != nil || calls.Load() != 2 {
		t.Fatal("ordinary translation remained unavailable after successful warmup")
	}
}

func TestEngineStartupRejectsWarmupCompletionAfterExitOrReplacement(t *testing.T) {
	for _, replacement := range []bool{false, true} {
		name := "process-exit"
		if replacement {
			name = "replacement-generation"
		}
		t.Run(name, func(t *testing.T) {
			e := NewEngine(t.TempDir())
			e.generation = 7
			done := make(chan error, 1)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				e.readyMu.Lock()
				if replacement {
					e.generation++
				} else {
					done <- errors.New("PRIVATE_EXIT_DETAIL_CANARY")
					close(done)
				}
				e.readyMu.Unlock()
				_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"warm translation"},"finish_reason":"stop"}]}`))
			}))
			defer server.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			err := e.warmLlama(ctx, server.URL, "", "qwen3-fixture", "chat", 7, done)
			if err == nil || e.Ready().TranslationReady || strings.Contains(err.Error(), "PRIVATE_EXIT_DETAIL_CANARY") {
				t.Fatal("stale/dead runtime warmup published readiness or exposed exit detail")
			}
		})
	}
}

func TestEngineStartupCancelledWarmupNeverPublishesReady(t *testing.T) {
	entered := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		close(entered)
		<-r.Context().Done()
	}))
	defer server.Close()
	e := NewEngine(t.TempDir())
	e.generation = 1
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- e.warmLlama(ctx, server.URL, "", "qwen3-fixture", "chat", 1, make(chan error)) }()
	awaitHealthSignal(t, entered)
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) || e.Ready().TranslationReady {
			t.Fatal("cancelled warmup published readiness or lost cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal("warmup ignored cancellation")
	}
}

func TestEngineStartupAnyErrorStopsAllPartialReadiness(t *testing.T) {
	for _, tc := range []struct {
		name   string
		ready  EngineStatus
		errors []string
	}{
		{"previously-published-all-ready", EngineStatus{TranslationReady: true, STTReady: true, TTSReady: true}, []string{"llama-server: warmup failed503"}},
		{"independent-failures", EngineStatus{STTReady: true, TTSReady: true}, []string{"llama-server: warmup failed503", "whisper-server: warmup failed502"}},
		{"missing-ready-without-collected-error", EngineStatus{TranslationReady: true, TTSReady: true}, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := NewEngine(t.TempDir())
			e.ready = tc.ready
			var stopped atomic.Bool
			e.cancel = func() { stopped.Store(true) }
			cfg := Config{Backend: "cpu", TranslationModel: "qwen3-fixture", STTModel: "whisper-fixture"}
			e.lifeMu.Lock()
			err := e.finishStartup(context.Background(), cfg, tc.errors)
			e.lifeMu.Unlock()
			status := e.Ready()
			if err == nil || !stopped.Load() || status.TranslationReady || status.STTReady || status.TTSReady || status.Error == "" || status.Backend != "cpu" {
				t.Fatal("startup failure was treated as partial success or did not reset all components")
			}
			for _, expected := range tc.errors {
				if !strings.Contains(err.Error(), expected) || !strings.Contains(status.Error, expected) {
					t.Fatal("startup cleanup lost an independently collected failure")
				}
			}
		})
	}
}
