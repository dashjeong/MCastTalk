package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"time"
)

func (a *App) makeSpeakerCookie() (string, error) {
	expiry := strconv.FormatInt(time.Now().Add(4*time.Hour).Unix(), 10)
	payload := expiry + "." + newID()
	mac := hmac.New(sha256.New, []byte(a.adminToken))
	mac.Write([]byte(payload))
	return payload + "." + hex.EncodeToString(mac.Sum(nil)), nil
}
func (a *App) validSpeakerCookie(token string) bool {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || len(parts[1]) != 32 {
		return false
	}
	expiry, e := strconv.ParseInt(parts[0], 10, 64)
	if e != nil || expiry < time.Now().Unix() || expiry > time.Now().Add(4*time.Hour).Unix() {
		return false
	}
	mac := hmac.New(sha256.New, []byte(a.adminToken))
	fmt.Fprintf(mac, "%s.%s", parts[0], parts[1])
	signature, e := hex.DecodeString(parts[2])
	return e == nil && hmac.Equal(signature, mac.Sum(nil))
}
