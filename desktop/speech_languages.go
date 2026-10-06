package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"
)

type SpeechLanguageStatus struct {
	Provider  string   `json:"provider,omitempty"`
	Supported bool     `json:"supported"`
	Available []string `json:"available"`
	Missing   []string `json:"missing"`
	Error     string   `json:"error,omitempty"`
}

func normalizeSpeechLanguage(language string) string {
	return strings.ToLower(strings.ReplaceAll(strings.TrimSpace(language), "_", "-"))
}

func parseSpeechLanguages(raw []byte, required []string) SpeechLanguageStatus {
	status := SpeechLanguageStatus{Supported: true, Available: []string{}, Missing: []string{}}
	available, err := parseSpeechVoiceCultures(raw)
	if err != nil {
		status.Error = err.Error()
		return status
	}
	missing, err := missingSpeechLanguages(available, required)
	if err != nil {
		status.Error = err.Error()
		return status
	}
	status.Available, status.Missing = available, missing
	return status
}

func parseSpeechVoiceCultures(raw []byte) ([]string, error) {
	raw = bytes.TrimSpace(bytes.TrimPrefix(raw, []byte{0xef, 0xbb, 0xbf}))
	if len(raw) == 0 || len(raw) > 1<<20 || !utf8.Valid(raw) {
		return nil, errors.New("Windows 음성 언어 조회의 UTF-8 응답이 유효하지 않습니다")
	}
	type voice struct {
		Culture string `json:"culture"`
		Enabled *bool  `json:"enabled"`
	}
	var voices []voice
	switch raw[0] {
	case '[':
		if err := json.Unmarshal(raw, &voices); err != nil {
			return nil, errors.New("Windows 음성 언어 JSON을 읽지 못했습니다")
		}
	case '{':
		var single voice
		if err := json.Unmarshal(raw, &single); err != nil {
			return nil, errors.New("Windows 음성 언어 JSON을 읽지 못했습니다")
		}
		voices = []voice{single}
	default:
		return nil, errors.New("Windows 음성 언어 조회가 완료되지 않았습니다")
	}
	seen := map[string]bool{}
	for _, voice := range voices {
		if voice.Enabled == nil {
			return nil, errors.New("Windows 음성의 사용 가능 상태를 확인하지 못했습니다")
		}
		if !*voice.Enabled {
			continue
		}
		culture := normalizeSpeechLanguage(voice.Culture)
		if !validSpeechLanguage(culture) {
			return nil, errors.New("Windows 음성의 문화권 정보를 확인하지 못했습니다")
		}
		seen[culture] = true
	}
	available := []string{}
	for culture := range seen {
		available = append(available, culture)
	}
	sort.Strings(available)
	return available, nil
}

var speechLanguagePattern = regexp.MustCompile(`^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$`)

func validSpeechLanguage(language string) bool {
	return speechLanguagePattern.MatchString(language) && language != "und"
}

// Match the current System.Speech synthesizer: exact culture first, then its
// base language. This does not promise synthesis quality or installed packs.
func missingSpeechLanguages(available, required []string) ([]string, error) {
	exact, base := map[string]bool{}, map[string]bool{}
	for _, culture := range available {
		culture = normalizeSpeechLanguage(culture)
		if !validSpeechLanguage(culture) {
			return nil, errors.New("음성 문화권 정보가 유효하지 않습니다")
		}
		exact[culture] = true
		base[strings.SplitN(culture, "-", 2)[0]] = true
	}
	missing := []string{}
	seen := map[string]bool{}
	for _, language := range required {
		language = normalizeSpeechLanguage(language)
		if !validSpeechLanguage(language) {
			return nil, errors.New("요청한 음성 언어 형식이 유효하지 않습니다")
		}
		if !exact[language] && !base[strings.SplitN(language, "-", 2)[0]] && !seen[language] {
			seen[language] = true
			missing = append(missing, language)
		}
	}
	sort.Strings(missing)
	return missing, nil
}
