package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestInputSocketCannotCrossSessionRestart(t *testing.T) {
	a, _ := testApp(t)
	old := testSession(t, a, []string{"en"})
	srv := httptest.NewServer(a.adminHandler())
	defer srv.Close()
	dial := func(id string) (*websocket.Conn, *http.Response, error) {
		return websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/admin/ws/input?token="+a.adminToken+"&sessionId="+id, http.Header{"Origin": []string{srv.URL}})
	}
	c, _, err := dial(old.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err := a.pipeline.stop(); err != nil {
		t.Fatal(err)
	}
	current := testSession(t, a, []string{"en"})
	pcm := bytes.Repeat([]byte{0xff, 0x20}, 2048)
	_ = c.WriteMessage(websocket.BinaryMessage, pcm)
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, body, err := c.ReadMessage()
	if err == nil {
		t.Fatalf("old connection not closed by session stop: %s", body)
	}
	path, _ := a.store.recordingPath(current.ID, "source.pcm")
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("old PCM entered new session: %v", err)
	}
	if _, resp, err := dial(old.ID); err == nil || resp == nil || resp.StatusCode != http.StatusConflict {
		t.Fatal("stale handshake accepted")
	} else {
		resp.Body.Close()
	}
	next, _, err := dial(current.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	if err := next.WriteMessage(websocket.BinaryMessage, pcm); err != nil {
		t.Fatal(err)
	}
	if err := next.WriteMessage(websocket.TextMessage, []byte("probe")); err != nil {
		t.Fatal(err)
	}
	_ = next.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, body, err = next.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	var status struct {
		Ready bool `json:"ready"`
	}
	if json.Unmarshal(body, &status) != nil || !status.Ready {
		t.Fatalf("new connection not ready: %s", body)
	}
	saved, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(saved, pcm) {
		t.Fatal("new connection PCM not durable", err)
	}
}

func TestMicrophoneLeaseHandoverCannotReleaseNewOwner(t *testing.T) {
	a, _ := testApp(t)
	old := testSession(t, a, []string{"en"})
	previous, ended, err := a.claimMicrophone(old.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := a.pipeline.stop(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ended:
	default:
		t.Fatal("stop did not revoke idle microphone")
	}
	current := testSession(t, a, []string{"en"})
	next, _, err := a.claimMicrophone(current.ID)
	if err != nil {
		t.Fatal("new microphone blocked by idle old lease", err)
	}
	a.releaseMicrophone(previous)
	if !a.inputOwned.Load() {
		t.Fatal("old handler released newer owner's lease")
	}
	if _, _, err := a.claimMicrophone(current.ID); err == nil {
		t.Fatal("second current microphone accepted")
	}
	if _, _, err := a.claimMicrophone(old.ID); err == nil {
		t.Fatal("stale handshake reclaimed newer lease")
	}
	a.releaseMicrophone(next)
	if a.inputOwned.Load() {
		t.Fatal("current owner failed to release")
	}
}

func TestOldInputFlushCannotQueueNewSession(t *testing.T) {
	a, _ := testApp(t)
	old := testSession(t, a, []string{"en"})
	if err := a.pipeline.stop(); err != nil {
		t.Fatal(err)
	}
	current := testSession(t, a, []string{"en"})
	if err := a.pipeline.inputPCMForSession(current.ID, bytes.Repeat([]byte{0xff, 0x20}, 2048)); err != nil {
		t.Fatal(err)
	}
	if err := a.pipeline.flushInputForSession(old.ID); err == nil {
		t.Fatal("old socket flush accepted")
	}
	if w := callAdmin(t, a, "POST", "/input/flush", []byte(`{"sessionId":"`+old.ID+`"}`)); w.Code != http.StatusConflict {
		t.Fatal("old UI flush accepted", w.Code)
	}
	if w := callAdmin(t, a, "POST", "/input/flush", []byte(`{}`)); w.Code != http.StatusBadRequest {
		t.Fatal("unbound flush accepted", w.Code)
	}
	a.pipeline.mu.Lock()
	pending := a.pipeline.pending
	a.pipeline.mu.Unlock()
	if pending == nil || pending.SessionID != current.ID || pending.State != "collecting" {
		t.Fatal("new session flushed by old connection")
	}
	if w := callAdmin(t, a, "POST", "/input/flush", []byte(`{"sessionId":"`+current.ID+`"}`)); w.Code != http.StatusOK {
		t.Fatal("valid flush rejected", w.Code)
	}
}
