package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// The barrier exercises the exact registration method used by roomEvents,
// without a production timing hook. The peer delivery and revoked-token denial
// also use real trusted TLS/WebSocket connections, with no model-quality claim.
func TestClassroomSubscriberRegistrationRechecksRevocation(t *testing.T) {
	f := newClassroomFixture(t)
	c := classroomPrivate(t, f)
	srv := httptest.NewTLSServer(f.a.publicHandler())
	t.Cleanup(srv.Close)
	client := srv.Client()
	client.Timeout = 5 * time.Second
	t.Cleanup(client.CloseIdleConnections)
	g := newClassroomWSGroup(t)
	bobConn := classroomTLSSnapshot(t, srv, g, f.tokens[f.bob.ID], f.bob, c.ID)

	authenticated := make(chan struct{})
	resumeRegistration := make(chan struct{})
	registration := make(chan error, 1)
	go func() {
		stale, err := f.rs.authenticate(f.tokens[f.alice.ID])
		close(authenticated)
		if err != nil {
			registration <- fmt.Errorf("initial authentication: %w", err)
			return
		}
		<-resumeRegistration
		sub, _, _, _, err := f.rs.registerRoomSubscriber(stale, c.ID)
		if err == nil {
			f.rs.mu.Lock()
			f.rs.dropLocked(sub)
			f.rs.mu.Unlock()
			registration <- fmt.Errorf("revoked authenticated member registered")
			return
		}
		registration <- nil
	}()
	select {
	case <-authenticated:
	case <-time.After(5 * time.Second):
		close(resumeRegistration)
		t.Fatal("authentication barrier not reached")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	code, body := classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[f.alice.ID], "POST", "/"+f.room.ID+"/leave", []byte(`{}`))
	close(resumeRegistration)
	if code != http.StatusOK {
		t.Fatalf("leave before registration: %d %s", code, body)
	}
	select {
	case err := <-registration:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("registration did not finish")
	}
	f.rs.mu.Lock()
	for sub := range f.rs.subscribers {
		if sub.memberID == f.alice.ID {
			f.rs.mu.Unlock()
			t.Fatal("revoked member remains subscribed")
		}
	}
	f.rs.mu.Unlock()

	// The valid designated peer still receives a new private line. The revoked
	// token must not receive even a snapshot, let alone that line.
	code, body = classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[f.bob.ID], "POST", "/"+f.room.ID+"/floor", classroomJSON(t, map[string]string{"channelId": c.ID, "action": "request"}))
	if code != http.StatusOK {
		t.Fatalf("peer floor: %d %s", code, body)
	}
	secret := "PRIVATE_AFTER_REVOKE_SENTINEL"
	requestID := newID()
	lineID := requestKey(f.bob.ID, c.ID, requestID, "text")
	code, body = classroomTLSRequest(t, ctx, client, srv.URL, f.tokens[f.bob.ID], "POST", "/"+f.room.ID+"/text", classroomJSON(t, map[string]string{"channelId": c.ID, "requestId": requestID, "text": secret}))
	if code != http.StatusCreated {
		t.Fatalf("peer private line: %d %s", code, body)
	}
	_ = bobConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		var event classroomWSEvent
		if err := bobConn.ReadJSON(&event); err != nil {
			t.Fatalf("valid peer did not receive private line: %v", err)
		}
		if event.Type == "line" && event.Line.ID == lineID {
			if event.Line.SessionID != c.ID || event.Line.SourceText != secret {
				t.Fatal("valid private line scope mismatch")
			}
			break
		}
	}
	denied := classroomTLSDial(t, srv, g)
	_ = denied.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if err := denied.WriteJSON(map[string]string{"token": f.tokens[f.alice.ID], "channelId": c.ID}); err != nil {
		t.Fatal(err)
	}
	_ = denied.SetReadDeadline(time.Now().Add(5 * time.Second))
	var event classroomWSEvent
	if err := denied.ReadJSON(&event); err == nil {
		t.Fatalf("revoked token received private event: %s", event.Type)
	}
}

func TestClassroomSubscriberRegistrationUsesLatestLanguageAndACL(t *testing.T) {
	for _, mode := range []string{"language", "expired", "acl"} {
		t.Run(mode, func(t *testing.T) {
			f := newClassroomFixture(t)
			c := classroomPrivate(t, f)
			stale, err := f.rs.authenticate(f.tokens[f.alice.ID])
			if err != nil {
				t.Fatal(err)
			}
			switch mode {
			case "language":
				m := stale
				m.Language = "es"
				if err := f.rs.changeLanguage(m); err != nil {
					t.Fatal(err)
				}
			case "expired":
				m := stale
				m.ExpiresAt = time.Now().Add(-time.Second)
				f.rs.mu.Lock()
				err = f.a.store.put("members", m.ID, m)
				f.rs.mu.Unlock()
			case "acl":
				c.MemberIDs = []string{f.bob.ID}
				f.rs.mu.Lock()
				err = f.rs.saveChannel(c)
				f.rs.mu.Unlock()
			}
			if err != nil {
				t.Fatal(err)
			}
			sub, current, _, _, err := f.rs.registerRoomSubscriber(stale, c.ID)
			if mode != "language" {
				if err == nil || sub != nil {
					t.Fatal("obsolete authority registered")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if current.Language != "es" || sub.language != "es" {
				t.Fatal("registration retained stale language")
			}
			f.rs.publishLine(Line{SessionID: c.ID, Translations: map[string]string{"en": "old-language", "es": "current-language"}})
			select {
			case event := <-sub.ch:
				line := event.(map[string]any)["line"].(Line)
				if len(line.Translations) != 1 || line.Translations["es"] != "current-language" {
					t.Fatal("fanout used stale language")
				}
			default:
				t.Fatal("current-language line not delivered")
			}
		})
	}
}

func TestClassroomSubscriberLateHeartbeatDoesNotRestoreRevokedPresence(t *testing.T) {
	f := newClassroomFixture(t)
	c := classroomPrivate(t, f)
	m, err := f.rs.authenticate(f.tokens[f.alice.ID])
	if err != nil {
		t.Fatal(err)
	}
	sub, _, _, _, err := f.rs.registerRoomSubscriber(m, c.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.rs.leave(m); err != nil {
		t.Fatal(err)
	}
	if err := f.rs.refreshRoomSubscriber(sub); err == nil || f.rs.roomSubscriberActive(sub) {
		t.Fatal("late heartbeat revived revoked subscription")
	}
	f.rs.mu.Lock()
	_, present := f.rs.presence[m.ID]
	f.rs.mu.Unlock()
	if present {
		t.Fatal("late heartbeat recreated revoked presence")
	}
	f.rs.publishLine(Line{ID: newID(), SessionID: c.ID, SourceText: "PRIVATE_POST_LEAVE_NOT_DELIVERED"})
	select {
	case <-sub.ch:
		t.Fatal("removed subscription received new private line")
	default:
	}
	select {
	case <-sub.done:
	default:
		t.Fatal("revoked subscription was not closed")
	}
}
