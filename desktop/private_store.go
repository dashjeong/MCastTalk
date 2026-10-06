package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	bolt "go.etcd.io/bbolt"
	"io"
	"os"
	"path/filepath"
)

var protectedMagic = []byte("MCPT1")

func (s *Store) initPrivateKey() error {
	path := filepath.Join(s.dir, "private-data.key")
	sealed, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		key := make([]byte, 32)
		if _, err = rand.Read(key); err != nil {
			return err
		}
		sealed, err = sealSecret(key)
		if err != nil {
			return err
		}
		if err = atomicFile(path, sealed); err != nil {
			return err
		}
	}
	if err != nil {
		return err
	}
	key, err := openSecret(sealed)
	if err != nil {
		return err
	}
	if len(key) != 32 {
		return errors.New("개인 자료 암호화 키가 유효하지 않습니다")
	}
	s.privateKey = key
	return nil
}
func (s *Store) protect(data, aad []byte) ([]byte, error) {
	block, err := aes.NewCipher(s.privateKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	out := append(append([]byte(nil), protectedMagic...), nonce...)
	return gcm.Seal(out, nonce, data, aad), nil
}
func (s *Store) unprotect(data, aad []byte) ([]byte, error) {
	if !bytes.HasPrefix(data, protectedMagic) {
		return data, nil
	}
	block, err := aes.NewCipher(s.privateKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	data = data[len(protectedMagic):]
	if len(data) < gcm.NonceSize() {
		return nil, errors.New("개인 자료가 손상되었습니다")
	}
	return gcm.Open(nil, data[:gcm.NonceSize()], data[gcm.NonceSize():], aad)
}
func recordAAD(bucket, key string) []byte { return []byte(bucket + "\x00" + key) }
func (s *Store) decodeRecord(bucket, key string, data []byte, v any) error {
	raw, err := s.unprotect(data, recordAAD(bucket, key))
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, v)
}
func privateSessionTx(tx *bolt.Tx, id string) bool {
	var session Session
	return json.Unmarshal(tx.Bucket([]byte("sessions")).Get([]byte(id)), &session) == nil && session.Kind == "private"
}
func (s *Store) isPrivateSession(id string) bool {
	private := false
	_ = s.db.View(func(tx *bolt.Tx) error { private = privateSessionTx(tx, id); return nil })
	return private
}
func (s *Store) encodeLine(tx *bolt.Tx, l Line) ([]byte, error) {
	raw, err := json.Marshal(l)
	if err != nil {
		return nil, err
	}
	if privateSessionTx(tx, l.SessionID) {
		return s.protect(raw, recordAAD("lines", l.ID))
	}
	return raw, nil
}
func (s *Store) writeRecording(sessionID, file string, data []byte) error {
	path, err := s.recordingPath(sessionID, file)
	if err != nil {
		return err
	}
	if s.isPrivateSession(sessionID) {
		data, err = s.protect(data, []byte(sessionID+"/"+file))
		if err != nil {
			return err
		}
	}
	return atomicFile(path, data)
}
func (s *Store) readRecording(sessionID, file string) ([]byte, error) {
	path, err := s.recordingPath(sessionID, file)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return s.unprotect(data, []byte(sessionID+"/"+file))
}
