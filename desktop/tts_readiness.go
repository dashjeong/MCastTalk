package main

import (
	"path/filepath"
	"sort"
	"strings"
)

func bundledSpeechStatus(cfg Config, assets map[string]InstalledAsset) SpeechLanguageStatus {
	status := SpeechLanguageStatus{Provider: "bundled-local", Supported: true, Available: []string{}, Missing: []string{}}
	profiles := map[string]Artifact{}
	for _, p := range Catalog() {
		profiles[p.ID] = p
	}
	seen := map[string]bool{}
	for _, language := range append([]string{cfg.SourceLanguage}, cfg.TargetLanguages...) {
		language = normalizeSpeechLanguage(language)
		if seen[language] {
			continue
		}
		seen[language] = true
		ids := bundledTTSAssetIDs([]string{language})
		ready := len(ids) > 0
		for _, id := range ids {
			p, ok := profiles[id]
			a, installed := assets[id]
			ready = ready && ok && installed && a.ID == id && p.SHA256 == a.SHA256 && p.Bytes == a.Bytes && a.Path != "" && portableLocalPath(a.Path, true) == nil && portableLocalPath(filepath.Join(a.Path, "installed-manifest.json"), false) == nil
		}
		if ready {
			status.Available = append(status.Available, language)
		} else {
			status.Missing = append(status.Missing, language)
		}
		if len(ids) == 0 {
			status.Error = "선택한 언어의 로컬 음성 모델이 없습니다: " + strings.Split(language, "-")[0]
		}
	}
	sort.Strings(status.Available)
	sort.Strings(status.Missing)
	return status
}
