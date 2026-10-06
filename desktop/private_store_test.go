package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	bolt "go.etcd.io/bbolt"
)

// Storage/ACL fixtures are not actual model, Windows reboot or hardware evidence.
func privateStoreSession(t *testing.T, s *Store) Session {
	t.Helper()
	session := Session{ID: newID(), Kind: "private", Title: "개인 채널", State: "active", SourceLanguage: "en", Targets: []string{"en", "ja"}, StartedAt: time.Now().UTC()}
	if err := s.put("sessions", session.ID, session); err != nil {
		t.Fatal(err)
	}
	return session
}

func TestPrivateStoreEncryptsRecordsAudioAndRestoresLatestRevision(t *testing.T) {
	dir := t.TempDir()
	s, err := openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if s != nil {
			_ = s.db.Close()
		}
	})
	session := privateStoreSession(t, s)
	text := "PRIVATE_STORAGE_TEXT_SENTINEL_72814"
	notes := "PRIVATE_STORAGE_NOTES_SENTINEL_98127"
	l, err := s.newSpeakerLine(session.ID, text, newID(), SpeechMetadata{SourceLanguage: "en", SpeakerID: newID(), SpeakerName: "Alice", Role: "student"})
	if err != nil {
		t.Fatal(err)
	}
	rs := &RoomService{store: s}
	c := Channel{ID: session.ID, RoomID: newID(), Kind: "private", ContextNotes: notes, Floor: Floor{ParticipantID: l.SpeakerID, ExpiresAt: time.Now().Add(time.Minute)}, Queue: []string{newID()}}
	if err = rs.saveChannel(c); err != nil {
		t.Fatal(err)
	}
	pcm := bytes.Repeat([]byte{0x31, 0x72}, 64)
	wav := wavBytes(pcm)
	name := l.ID + "-en.wav"
	if err = s.writeRecording(session.ID, name, wav); err != nil {
		t.Fatal(err)
	}
	if err = s.updateLine(l.ID, func(x *Line) { x.Translations["ja"] = "PRIVATE_LATEST_TRANSLATION"; x.Audio["en"] = name }); err != nil {
		t.Fatal(err)
	}
	if err = s.db.View(func(tx *bolt.Tx) error {
		for _, item := range []struct{ bucket, key, secret string }{{"lines", l.ID, text}, {"channels", c.ID, notes}} {
			raw := tx.Bucket([]byte(item.bucket)).Get([]byte(item.key))
			if !bytes.HasPrefix(raw, protectedMagic) || bytes.Contains(raw, []byte(item.secret)) {
				t.Errorf("%s plaintext/unprotected record", item.bucket)
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	path, err := s.recordingPath(session.ID, name)
	if err != nil {
		t.Fatal(err)
	}
	ciphertext, err := os.ReadFile(path)
	if err != nil || !bytes.HasPrefix(ciphertext, protectedMagic) || bytes.Contains(ciphertext, pcm) {
		t.Fatal("private WAV stored as plaintext")
	}
	if err = s.db.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	var latest Line
	if err = s.get("lines", l.ID, &latest); err != nil {
		t.Fatal(err)
	}
	if latest.SourceText != text || latest.Translations["ja"] != "PRIVATE_LATEST_TRANSLATION" || latest.Revision != 2 {
		t.Fatalf("latest private revision not recovered: %+v", latest)
	}
	got, err := s.readRecording(session.ID, name)
	if err != nil || !bytes.Equal(got, wav) {
		t.Fatal("private audio did not decrypt after restart")
	}
	var recovered Channel
	if err = s.get("channels", c.ID, &recovered); err != nil || recovered.ContextNotes != notes {
		t.Fatal("private channel notes did not survive restart")
	}
	if _, err = s.recover(); err != nil {
		t.Fatal(err)
	}
	rs.store = s
	if err = rs.recoverChannels(); err != nil {
		t.Fatal(err)
	}
	if err = s.get("channels", c.ID, &recovered); err != nil {
		t.Fatal(err)
	}
	if recovered.Floor.ParticipantID != "" || len(recovered.Queue) != 0 || recovered.ContextNotes != notes {
		t.Fatal("recovery lost notes or retained stale floor")
	}
	storedSession, err := s.session(session.ID)
	if err != nil || storedSession.State != "active" || storedSession.GapCount != 1 {
		t.Fatalf("recovery gap/resume mismatch: %+v %v", storedSession, err)
	}
}

func TestPrivateStoreAuthenticationRejectsTamperWrongRecordAndWrongAudio(t *testing.T) {
	s, err := openStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.db.Close() })
	aad := recordAAD("lines", newID())
	plain := []byte("private authenticated content")
	first, err := s.protect(plain, aad)
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.protect(plain, aad)
	if err != nil || bytes.Equal(first, second) {
		t.Fatal("GCM encryption reused a nonce")
	}
	for _, tc := range []struct {
		name      string
		data, aad []byte
	}{
		{"different record", first, recordAAD("lines", newID())},
		{"different bucket", first, recordAAD("channels", string(aad))},
		{"truncated", first[:len(protectedMagic)+1], aad},
	} {
		if _, err := s.unprotect(tc.data, tc.aad); err == nil {
			t.Fatalf("%s ciphertext accepted", tc.name)
		}
	}
	tampered := append([]byte(nil), first...)
	tampered[len(tampered)-1] ^= 1
	if _, err = s.unprotect(tampered, aad); err == nil {
		t.Fatal("modified ciphertext accepted")
	}
	session := privateStoreSession(t, s)
	name := newID() + "-en.wav"
	if err = s.writeRecording(session.ID, name, wavBytes([]byte{1, 2, 3, 4})); err != nil {
		t.Fatal(err)
	}
	path, _ := s.recordingPath(session.ID, name)
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.unprotect(raw, []byte(session.ID+"/"+newID()+"-en.wav")); err == nil {
		t.Fatal("audio copied to another identity decrypted")
	}
	raw[len(raw)-1] ^= 1
	if err = os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = s.readRecording(session.ID, name); err == nil {
		t.Fatal("tampered private audio accepted")
	}
}

func TestPrivateStoreGenericBackupAndExportExcludePrivateContent(t *testing.T) {
	f := newClassroomFixture(t)
	private := classroomPrivate(t, f)
	secret := "PRIVATE_BACKUP_SECRET_MARKER_46719"
	l, err := f.a.store.newSpeakerLine(private.ID, secret, newID(), SpeechMetadata{SourceLanguage: "en", SpeakerID: f.alice.ID})
	if err != nil {
		t.Fatal(err)
	}
	name := l.ID + "-en.wav"
	if err = f.a.store.writeRecording(private.ID, name, wavBytes(bytes.Repeat([]byte{3, 4}, 64))); err != nil {
		t.Fatal(err)
	}
	publicText := "PUBLIC_BACKUP_MARKER_18472"
	if _, err = f.a.store.newSpeakerLine(f.room.ID, publicText, newID(), SpeechMetadata{SourceLanguage: "ko", SpeakerID: f.teacher.ID}); err != nil {
		t.Fatal(err)
	}
	publicName := newID() + "-ko.wav"
	if err = f.a.store.writeRecording(f.room.ID, publicName, wavBytes([]byte{1, 2, 3, 4})); err != nil {
		t.Fatal(err)
	}
	w := httptest.NewRecorder()
	f.a.backup(w, httptest.NewRequest("GET", "http://127.0.0.1:8790/admin/api/backup", nil))
	z, err := zip.NewReader(bytes.NewReader(w.Body.Bytes()), int64(w.Body.Len()))
	if err != nil {
		t.Fatal(err)
	}
	foundPublic, foundPublicAudio := false, false
	for _, entry := range z.File {
		if strings.Contains(entry.Name, private.ID) || filepath.Base(entry.Name) == "private-data.key" {
			t.Fatalf("private artifact in generic backup: %s", entry.Name)
		}
		r, err := entry.Open()
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(r)
		_ = r.Close()
		if err != nil {
			t.Fatal(err)
		}
		if bytes.Contains(data, []byte(secret)) || bytes.Contains(data, []byte(private.ID)) || bytes.Contains(data, []byte(l.ID)) {
			t.Fatalf("private record in %s", entry.Name)
		}
		if bytes.Contains(data, []byte(publicText)) {
			foundPublic = true
		}
		if entry.Name == "recordings/"+f.room.ID+"/"+publicName {
			foundPublicAudio = true
		}
		if entry.Name == "manifest.json" {
			var manifest map[string]any
			if err = json.Unmarshal(data, &manifest); err != nil || manifest["privateChannelsExcluded"] != true || manifest["containsSecrets"] != false {
				t.Fatal("backup privacy manifest missing")
			}
		}
	}
	if !foundPublic || !foundPublicAudio {
		t.Fatal("generic backup also lost public artifacts")
	}
	for _, format := range []string{"json", "txt", "zip"} {
		w = httptest.NewRecorder()
		f.a.exportSession(w, httptest.NewRequest("GET", "http://127.0.0.1:8790/admin/api/export?format="+format, nil), private.ID)
		if w.Code != 403 || bytes.Contains(w.Body.Bytes(), []byte(secret)) {
			t.Fatalf("generic private export %s permitted", format)
		}
	}
	w = httptest.NewRecorder()
	f.a.audioFile(w, httptest.NewRequest("GET", "http://127.0.0.1:8790/admin/api/audio", nil), private.ID, name)
	if w.Code != 403 {
		t.Fatal("generic audio endpoint exposed private audio")
	}
}
