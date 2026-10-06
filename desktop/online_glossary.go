package main

import (
	"strings"
	"unicode/utf8"
)

// selectOnlineGlossary sends only terminology present in this utterance for the
// exact target language. Stored order provides deterministic priority; entries
// that do not fit are skipped so a later smaller relevant term can still fit.
// It preserves the caller's slice and bounds both scanning and provider input.
func selectOnlineGlossary(text, target string, terms []GlossaryTerm) []GlossaryTerm {
	if text == "" || target == "" || !utf8.ValidString(text) {
		return nil
	}
	const maxScan = 10000
	const maxTerms = 100
	const maxTermBytes = 512 // Same bound as POST /glossary's persisted fields.
	const maxRunes = 2000
	limit := len(terms)
	if limit > maxScan {
		limit = maxScan
	}
	var selected []GlossaryTerm
	usedRunes := 0
	for _, term := range terms[:limit] {
		if len(selected) == maxTerms {
			break
		}
		if term.Language != target || len(term.Source) == 0 || len(term.Target) == 0 ||
			len(term.Source) > maxTermBytes || len(term.Target) > maxTermBytes ||
			!utf8.ValidString(term.Source) || !utf8.ValidString(term.Target) ||
			strings.TrimSpace(term.Source) == "" || strings.TrimSpace(term.Target) == "" ||
			!strings.Contains(text, term.Source) {
			continue
		}
		cost := utf8.RuneCountInString(term.Source) + utf8.RuneCountInString(term.Target)
		if cost > maxRunes-usedRunes {
			continue
		}
		selected = append(selected, term)
		usedRunes += cost
	}
	return selected
}
