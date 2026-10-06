package main

import (
	"fmt"
	bolt "go.etcd.io/bbolt"
	"strings"
)

// Indexed reverse traversal keeps the foreground context bounded to this room.
func (s *Store) recentLines(sessionID string, before uint64, limit int) ([]Line, error) {
	if limit < 1 || limit > 100 {
		limit = 50
	}
	if before == 0 {
		before = ^uint64(0)
	}
	out := []Line{}
	err := s.db.View(func(tx *bolt.Tx) error {
		c := tx.Bucket([]byte("line-index")).Cursor()
		key := lineIndexKey(sessionID, before)
		k, v := c.Seek(key)
		if k == nil {
			k, v = c.Last()
		} else {
			k, v = c.Prev()
		}
		for ; k != nil && strings.HasPrefix(string(k), sessionID+":") && len(out) < limit; k, v = c.Prev() {
			var line Line
			if err := s.decodeRecord("lines", string(v), tx.Bucket([]byte("lines")).Get(v), &line); err != nil {
				return err
			}
			out = append(out, line)
		}
		return nil
	})
	for left, right := 0, len(out)-1; left < right; left, right = left+1, right-1 {
		out[left], out[right] = out[right], out[left]
	}
	return out, err
}
func (s *Store) recentContext(sessionID string, before uint64, limit int) string {
	lines, err := s.recentLines(sessionID, before, limit)
	if err != nil {
		return ""
	}
	var notes string
	var channel Channel
	if s.get("channels", sessionID, &channel) == nil && channel.ContextNotes != "" {
		notes = "채널에 명시한 참고 문맥: " + channel.ContextNotes + "\n"
	}
	var b strings.Builder
	for _, line := range lines {
		text := []rune(line.SourceText)
		if len(text) > 256 {
			text = text[:256]
		}
		fmt.Fprintf(&b, "[%s · %s] %s\n", line.SourceLanguage, line.SpeakerName, string(text))
	}
	text := []rune(b.String())
	budget := 1024 - len([]rune(notes))
	if budget < 0 {
		notes = string([]rune(notes)[:1024])
		budget = 0
	}
	if len(text) > budget {
		text = text[len(text)-budget:]
	}
	return notes + string(text)
}
