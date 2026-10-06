package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestEngineOnlinePublicConsentAndPrivateIsolation(t *testing.T) {
	var onlineCalls, localCalls atomic.Int32
	online := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		onlineCalls.Add(1)
		body, _ := io.ReadAll(r.Body)
		if strings.Contains(string(body), "PRIVATE-CHANNEL-CANARY") {
			t.Error("private content reached online provider")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"공개 번역"},"finish_reason":"stop"}]}`)
	}))
	defer online.Close()
	local := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		localCalls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"로컬 번역"},"finish_reason":"stop"}]}`)
	}))
	defer local.Close()
	e := NewEngine(t.TempDir())
	e.onlineClient = online.Client()
	e.llamaURL = local.URL
	e.ready.TranslationReady = true
	e.SetOnlineConfig(OnlineConfig{Consent: true, Endpoint: online.URL + "/chat/completions", Model: "fixture", APIKey: "synthetic-fixture-key"})
	got, err := e.Translate(context.Background(), "Public test", "en", "ko", "", nil)
	if err != nil || got != "공개 번역" || onlineCalls.Load() != 1 {
		t.Fatalf("public route failed: %q %v calls=%d", got, err, onlineCalls.Load())
	}
	got, err = e.TranslatePrivate(context.Background(), "PRIVATE-CHANNEL-CANARY", "en", "ko", "", nil)
	if err != nil || got != "로컬 번역" || onlineCalls.Load() != 1 || localCalls.Load() != 1 {
		t.Fatalf("private isolation failed: %q %v online=%d local=%d", got, err, onlineCalls.Load(), localCalls.Load())
	}
	e.SetOnlineConfig(OnlineConfig{Consent: false, Endpoint: online.URL + "/chat/completions", Model: "fixture", APIKey: "synthetic-fixture-key"})
	if _, err = e.TranslateOnline(context.Background(), "After revoke", "en", "ko", "", nil); err == nil || onlineCalls.Load() != 1 {
		t.Fatal("direct online call bypassed revoked consent")
	}
	got, err = e.Translate(context.Background(), "After revoke", "en", "ko", "", nil)
	if err != nil || got != "로컬 번역" || onlineCalls.Load() != 1 || localCalls.Load() != 2 {
		t.Fatal("subsequent public call did not honor revoked consent")
	}
}

func TestEngineModelStartCannotRestoreRevokedOnlineConsent(t *testing.T) {
	var calls atomic.Int32
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"must not be sent"},"finish_reason":"stop"}]}`)
	}))
	defer provider.Close()
	stale := defaultConfig()
	stale.Online = OnlineConfig{Consent: true, Endpoint: provider.URL + "/chat/completions", Model: "fixture", APIKey: "synthetic-fixture-key"}
	app := &App{cfg: stale}
	e := NewEngine(t.TempDir())
	e.onlineClient = provider.Client()
	e.onlineConfig = func() OnlineConfig { return app.config().Online }
	// A Start handler has already copied stale configuration. The operator
	// revokes before it reaches Engine.Start's snapshot write.
	app.cfgMu.Lock()
	app.cfg.Online.Consent = false
	app.cfgMu.Unlock()
	if err := e.Start(context.Background(), stale, nil); err == nil {
		t.Fatal("missing models must prevent this fixture startup")
	}
	if _, err := e.TranslateOnline(context.Background(), "After operator revoke", "en", "ko", "", nil); err == nil || calls.Load() != 0 {
		t.Fatal("stale model startup restored cloud consent")
	}
}

func TestEngineOnlineLargeStoredGlossaryUsesOnlyRelevantBoundedTerms(t *testing.T) {
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(body), "UNRELATED-GLOSSARY-CANARY") {
			t.Error("unrelated glossary leaked")
		}
		if !strings.Contains(string(body), "relevant-term") || !strings.Contains(string(body), "번역용어") {
			t.Error("relevant last stored term was lost")
		}
		var decoded any
		if json.Unmarshal(body, &decoded) != nil {
			t.Error("invalid provider JSON")
		}
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"공개 번역"},"finish_reason":"stop"}]}`)
	}))
	defer provider.Close()
	e := NewEngine(t.TempDir())
	e.onlineClient = provider.Client()
	e.SetOnlineConfig(OnlineConfig{Consent: true, Endpoint: provider.URL + "/chat/completions", Model: "fixture", APIKey: "synthetic-fixture-key"})
	terms := make([]GlossaryTerm, 10000)
	for i := range terms {
		terms[i] = GlossaryTerm{Language: "ko", Source: "UNRELATED-GLOSSARY-CANARY", Target: "excluded"}
	}
	terms[len(terms)-1] = GlossaryTerm{Language: "ko", Source: "relevant-term", Target: "번역용어"}
	got, err := e.TranslateOnline(context.Background(), "Please translate relevant-term.", "en", "ko", "", terms)
	if err != nil || got != "공개 번역" {
		t.Fatal("valid stored glossary blocked online translation", got, err)
	}
}
