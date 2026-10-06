package main

import (
	"fmt"
	"reflect"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestSelectOnlineGlossaryTenThousandAndUnrelatedCanary(t *testing.T) {
	terms := make([]GlossaryTerm, 10000)
	for i := range terms {
		terms[i] = GlossaryTerm{Source: fmt.Sprintf("irrelevant-%05d", i), Target: fmt.Sprintf("excluded-canary-%05d", i), Language: "en"}
	}
	wanted := GlossaryTerm{Source: "서울역", Target: "Seoul Station", Language: "en"}
	terms[9999] = wanted
	before := append([]GlossaryTerm(nil), terms...)
	got := selectOnlineGlossary("서울역으로 가세요.", "en", terms)
	if !reflect.DeepEqual(got, []GlossaryTerm{wanted}) {
		t.Fatalf("relevant term at stored 10,000th entry not selected: %#v", got)
	}
	if !reflect.DeepEqual(terms, before) {
		t.Fatal("caller's stored glossary was modified")
	}
	if !reflect.DeepEqual(selectOnlineGlossary("서울역으로 가세요.", "en", terms), got) {
		t.Fatal("selection differs for identical input")
	}
	// Direct callers outside the server cannot cause an unbounded scan.
	terms = append(terms, GlossaryTerm{Source: "only-after-bound", Target: "must not scan", Language: "en"})
	if got := selectOnlineGlossary("only-after-bound", "en", terms); len(got) != 0 {
		t.Fatal("selection scanned beyond the persisted 10,000-entry limit")
	}
}

func TestSelectOnlineGlossaryExactLanguageAndCase(t *testing.T) {
	terms := []GlossaryTerm{
		{Source: "AI", Target: "artificial intelligence", Language: "en"},
		{Source: "ai", Target: "lowercase excluded", Language: "en"},
		{Source: "AI", Target: "other-language-canary", Language: "ja"},
		{Source: "missing", Target: "unrelated-canary", Language: "en"},
		{Source: "AI", Target: "regional-only", Language: "en-US"},
	}
	if got := selectOnlineGlossary("AI helps here.", "en", terms); !reflect.DeepEqual(got, terms[:1]) {
		t.Fatalf("language/case/unrelated filtering incorrect: %#v", got)
	}
	if got := selectOnlineGlossary("AI helps here.", "en-US", terms); !reflect.DeepEqual(got, terms[4:5]) {
		t.Fatalf("regional target was silently broadened: %#v", got)
	}
}

func TestSelectOnlineGlossaryNilAndInvalidUTF8(t *testing.T) {
	invalid := string([]byte{0xff})
	for _, tc := range []struct {
		name, text, target string
		terms              []GlossaryTerm
	}{
		{name: "nil", text: "word", target: "en"},
		{name: "empty text", target: "en", terms: []GlossaryTerm{{Source: "word", Target: "translation", Language: "en"}}},
		{name: "empty target", text: "word", terms: []GlossaryTerm{{Source: "word", Target: "translation"}}},
		{name: "invalid utterance", text: "word" + invalid, target: "en", terms: []GlossaryTerm{{Source: "word", Target: "translation", Language: "en"}}},
		{name: "invalid source", text: "word", target: "en", terms: []GlossaryTerm{{Source: invalid, Target: "translation", Language: "en"}}},
		{name: "invalid translation", text: "word", target: "en", terms: []GlossaryTerm{{Source: "word", Target: invalid, Language: "en"}}},
		{name: "empty source", text: "word", target: "en", terms: []GlossaryTerm{{Target: "translation", Language: "en"}}},
		{name: "blank source", text: "word word", target: "en", terms: []GlossaryTerm{{Source: " ", Target: "translation", Language: "en"}}},
		{name: "blank translation", text: "word", target: "en", terms: []GlossaryTerm{{Source: "word", Target: "\t\n", Language: "en"}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := selectOnlineGlossary(tc.text, tc.target, tc.terms); got != nil {
				t.Fatalf("expected nil safe selection: %#v", got)
			}
		})
	}
}

func TestSelectOnlineGlossaryFieldByteLimitAndUnicodeBudget(t *testing.T) {
	maxASCII := strings.Repeat("a", 512)
	maxUnicode := strings.Repeat("한", 170) // 510 UTF-8 bytes, 170 runes.
	terms := []GlossaryTerm{
		{Source: maxASCII + "a", Target: "excluded long source", Language: "en"},
		{Source: "word", Target: strings.Repeat("b", 513), Language: "en"},
		{Source: "word", Target: strings.Repeat("한", 171), Language: "en"},
		{Source: maxASCII, Target: "accepted boundary", Language: "en"},
		{Source: maxUnicode, Target: maxUnicode, Language: "en"},
	}
	want := terms[3:]
	got := selectOnlineGlossary(maxASCII+"a word "+maxUnicode, "en", terms)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("stored field byte limit or multibyte handling failed: %#v", got)
	}
	// Count Unicode runes, rather than treating UTF-8 bytes as the aggregate.
	multibyte := GlossaryTerm{Source: "한", Target: maxUnicode, Language: "en"}
	got = selectOnlineGlossary("한", "en", []GlossaryTerm{multibyte, multibyte, multibyte, multibyte, multibyte, multibyte, multibyte, multibyte, multibyte, multibyte, multibyte, multibyte})
	if len(got) != 11 { // 11*171=1881 runes; the next would exceed 2000.
		t.Fatalf("aggregate was not measured in runes: %d terms", len(got))
	}
}

func TestSelectOnlineGlossaryAggregateBoundarySkipsNonFittingTerm(t *testing.T) {
	large := GlossaryTerm{Source: strings.Repeat("x", 500), Target: strings.Repeat("y", 500), Language: "en"}
	last := GlossaryTerm{Source: "z", Target: "v", Language: "en"}
	text := large.Source + "z"
	got := selectOnlineGlossary(text, "en", []GlossaryTerm{large, large, last})
	if !reflect.DeepEqual(got, []GlossaryTerm{large, large}) {
		t.Fatalf("exact 2000-rune boundary not respected: %d terms", len(got))
	}
	// At 1500 runes a 1000-rune term is skipped, while a later 2-rune term fits.
	medium := GlossaryTerm{Source: "x", Target: strings.Repeat("b", 499), Language: "en"}
	got = selectOnlineGlossary(text, "en", []GlossaryTerm{large, medium, large, last})
	if !reflect.DeepEqual(got, []GlossaryTerm{large, medium, last}) {
		t.Fatalf("non-fitting entry prevented a later relevant entry: %#v", got)
	}
	budget := 0
	for _, term := range got {
		budget += utf8.RuneCountInString(term.Source) + utf8.RuneCountInString(term.Target)
	}
	if budget != 1502 {
		t.Fatalf("unexpected rune budget: %d", budget)
	}
}

func TestSelectOnlineGlossaryHundredTermCapAndCopy(t *testing.T) {
	terms := make([]GlossaryTerm, 101)
	for i := range terms {
		terms[i] = GlossaryTerm{Source: "x", Target: fmt.Sprintf("v%03d", i), Language: "en"}
	}
	before := append([]GlossaryTerm(nil), terms...)
	got := selectOnlineGlossary("x", "en", terms)
	if len(got) != 100 || !reflect.DeepEqual(got, terms[:100]) {
		t.Fatal("100-term cap or deterministic stored order violated")
	}
	got[0].Target = "changed result"
	if !reflect.DeepEqual(terms, before) {
		t.Fatal("returned slice aliases the caller's stored slice")
	}
}
