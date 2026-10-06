package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPINSettingsRedactPreserveAndReplace(t *testing.T) {
	a, _ := testApp(t)
	a.cfg.ListenerPIN = "synthetic-listener-pin"
	a.cfg.SpeakerPIN = "synthetic-speaker-pin"
	a.cfg.Online.APIKey = "synthetic-api-secret"
	status := callAdmin(t, a, "GET", "/status", nil)
	for _, secret := range []string{a.cfg.ListenerPIN, a.cfg.SpeakerPIN, a.cfg.Online.APIKey} {
		if bytes.Contains(status.Body.Bytes(), []byte(secret)) {
			t.Fatal("settings disclosed a credential")
		}
	}
	var public struct {
		Config Config          `json:"config"`
		Pins   map[string]bool `json:"pinStatus"`
	}
	if err := json.Unmarshal(status.Body.Bytes(), &public); err != nil {
		t.Fatal(err)
	}
	if !public.Pins["listenerSet"] || !public.Pins["speakerSet"] {
		t.Fatal("missing set flags")
	}
	public.Config.Access = "pin"
	body, _ := json.Marshal(public.Config)
	saved := callAdmin(t, a, "POST", "/config", body)
	if saved.Code != http.StatusOK || a.config().ListenerPIN != "synthetic-listener-pin" || a.config().SpeakerPIN != "synthetic-speaker-pin" || a.config().Online.APIKey != "synthetic-api-secret" {
		t.Fatal("blank redacted inputs cleared saved credentials")
	}
	public.Config.ListenerPIN = "new-listener-pin"
	public.Config.SpeakerPIN = "new-speaker-pin"
	body, _ = json.Marshal(public.Config)
	saved = callAdmin(t, a, "POST", "/config", body)
	if saved.Code != http.StatusOK || a.config().ListenerPIN != public.Config.ListenerPIN || a.config().SpeakerPIN != public.Config.SpeakerPIN {
		t.Fatal("explicit replacement failed")
	}
	if bytes.Contains(saved.Body.Bytes(), []byte("new-listener-pin")) || bytes.Contains(saved.Body.Bytes(), []byte("new-speaker-pin")) {
		t.Fatal("save response disclosed PIN")
	}
}

func TestFirstPINSelectionRequiresValidNewPIN(t *testing.T) {
	a, _ := testApp(t)
	c := redacted(a.config())
	c.Access = "pin"
	for _, pin := range []string{"", "12345"} {
		c.ListenerPIN = pin
		body, _ := json.Marshal(c)
		if w := callAdmin(t, a, "POST", "/config", body); w.Code != http.StatusBadRequest {
			t.Fatal("invalid first PIN accepted", pin, w.Code)
		}
	}
	c.ListenerPIN = "123456"
	body, _ := json.Marshal(c)
	if w := callAdmin(t, a, "POST", "/config", body); w.Code != http.StatusOK {
		t.Fatal("valid first PIN rejected", w.Code)
	}
	loaded := a.store.config()
	if loaded.ListenerPIN != c.ListenerPIN {
		t.Fatal("PIN not durable")
	}
}

func TestPINSaveRejectsUnusableCredentialsAndJoinsWithUnicode(t *testing.T) {
	a, _ := testApp(t)
	for _, field := range []string{"listener", "speaker"} {
		for _, pin := range []string{" 123456", "123456 ", "\t123456", "123456\u3000", "      ", strings.Repeat("x", 33), strings.Repeat("한", 11)} {
			c := a.config()
			c.Access, c.ListenerPIN, c.SpeakerPIN = "pin", "123456", "654321"
			if field == "listener" {
				c.ListenerPIN = pin
			} else {
				c.SpeakerPIN = pin
			}
			before := a.config()
			body, _ := json.Marshal(c)
			if w := callAdmin(t, a, "POST", "/config", body); w.Code != http.StatusBadRequest {
				t.Fatalf("unusable %s PIN accepted: %d", field, w.Code)
			}
			if a.config().ListenerPIN != before.ListenerPIN || a.config().SpeakerPIN != before.SpeakerPIN {
				t.Fatal("rejected PIN changed active credentials")
			}
		}
	}
	c := a.config()
	c.Access, c.ListenerPIN, c.SpeakerPIN = "pin", "한글", "가나"
	body, _ := json.Marshal(c)
	if w := callAdmin(t, a, "POST", "/config", body); w.Code != http.StatusOK {
		t.Fatalf("valid six-byte Unicode PIN rejected: %d", w.Code)
	}
	testSession(t, a, []string{"en"})
	for _, tc := range []struct{ path, pin string }{{"/api/join", "한글"}, {"/api/mic/join", "가나"}} {
		r := httptest.NewRequest("POST", "http://127.0.0.1:8787"+tc.path, strings.NewReader(tc.pin))
		r.RemoteAddr = "127.0.0.1:23456"
		r.Header.Set("Origin", "http://127.0.0.1:8787")
		w := httptest.NewRecorder()
		a.publicHandler().ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("saved Unicode PIN cannot join %s: %d", tc.path, w.Code)
		}
	}
}
