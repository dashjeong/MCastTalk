package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestEngine_TranslatePrivate_PromptARRAY(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"role":"user"`) {
			t.Errorf("expected user role")
		}
		if !strings.Contains(string(body), `"type":"text"`) {
			t.Errorf("expected text type in ARRAY")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"choices": [{"message": {"content": "Bonjour"}}]}`))
	}))
	defer ts.Close()

	e := NewEngine("/tmp")
	e.llamaURL = ts.URL
	e.cfg.TranslationModel = "translategemma-4b"
	e.ready = EngineStatus{TranslationReady: true, STTReady: true, TTSReady: true, Backend: "cpu"}

	res, err := e.TranslatePrivate(context.Background(), "Hello", "en", "fr", "", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if res != "Bonjour" {
		t.Errorf("expected Bonjour, got %s", res)
	}
}

func TestEngine_TranscribePrivate_WAVValidation(t *testing.T) {
	e := NewEngine("/tmp")
	e.whisperURL = "http://fake"
	e.ready = EngineStatus{STTReady: true}

	_, err := e.TranscribePrivate(context.Background(), []byte("bad_wav"), "en")
	if err == nil {
		t.Fatal("expected malformed WAV to fail before HTTP")
	}
}

func TestEngine_Start_MissingAssets(t *testing.T) {
	e := NewEngine("/tmp")
	cfg := Config{TranslationModel: "model-1", STTModel: "model-2"}
	assets := map[string]InstalledAsset{} // missing
	err := e.Start(context.Background(), cfg, assets)
	if err == nil {
		t.Fatal("expected missing assets to fail")
	}
}

func TestEngineTranscribePrivateCapabilityAndRedactedFailures(t *testing.T) {
	const capability = "/private-stt-capability-canary"
	const privateCanary = "PRIVATE_SPEECH_MUST_NOT_APPEAR_IN_ERRORS"
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != capability+"/inference" {
			http.NotFound(w, r)
			return
		}
		if err := r.ParseMultipartForm(128 << 10); err != nil {
			t.Error(err)
		}
		if r.FormValue("language") != "ko" {
			t.Error("source language must reach protected inference route")
		}
		http.Error(w, privateCanary+capability, http.StatusBadGateway)
	}))
	e := NewEngine(t.TempDir())
	e.whisperURL, e.ready.STTReady = ts.URL+capability, true
	_, err := e.TranscribePrivate(context.Background(), makeSilenceWAV(), "ko")
	if err == nil || !strings.Contains(err.Error(), "502") || strings.Contains(err.Error(), privateCanary) || strings.Contains(err.Error(), capability) {
		t.Fatalf("upstream body or private endpoint leaked: %v", err)
	}
	ts.Close()
	_, err = e.TranscribePrivate(context.Background(), makeSilenceWAV(), "ko")
	if err == nil || strings.Contains(err.Error(), capability) || strings.Contains(err.Error(), ts.URL) {
		t.Fatalf("transport error exposed private endpoint: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = e.TranscribePrivate(ctx, makeSilenceWAV(), "ko")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation was hidden: %v", err)
	}
}
