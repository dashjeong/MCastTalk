package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"
)

// Operator-invoked diagnostics use public fixed sample sentences only. This
// imports the pinned original archives, then exercises the production worker.
func runBundledTTSProof(ctx context.Context, data, source string) error {
	if portableLocalPath(source, true) != nil {
		return errors.New("시험용 음성 원본 폴더를 확인하세요")
	}
	if st, err := os.Stat(data); err == nil && st.IsDir() {
		entries, e := os.ReadDir(data)
		if e != nil || len(entries) != 0 {
			return errors.New("음성 진단은 비어 있는 별도 자료 폴더가 필요합니다")
		}
	} else if err != nil && !os.IsNotExist(err) {
		return err
	}
	if err := os.MkdirAll(data, 0700); err != nil {
		return err
	}
	am := NewAssetManager(data, nil)
	init := nativeTTSInit{DataDir: data, Threads: 2}
	files := map[string]string{"runtime-sherpa-tts": "runtime.tar.bz2", "tts-supertonic3": "supertonic.tar.bz2", "tts-kokoro-zh": "kokoro.tar.bz2"}
	started := time.Now()
	for _, id := range bundledTTSAssetIDs([]string{"ko", "en", "ja", "zh", "es"}) {
		if err := am.Import(ctx, id, filepath.Join(source, files[id])); err != nil {
			return err
		}
		var art Artifact
		for _, p := range Catalog() {
			if p.ID == id {
				art = p
				break
			}
		}
		folder := "models"
		if id == "runtime-sherpa-tts" {
			folder = "runtimes"
		}
		root := filepath.Join(data, folder, id+"-"+art.SHA256[:16])
		switch id {
		case "runtime-sherpa-tts":
			init.RuntimeDir = root
		case "tts-supertonic3":
			init.SupertonicDir = root
		case "tts-kokoro-zh":
			init.KokoroDir = root
		}
	}
	result := map[string]any{"version": Version, "scope": "Production bundled native TTS only; public fixed samples; translation, microphone, quality and live SLA not accepted", "archiveImportMillis": time.Since(started).Milliseconds(), "qualityVerified": false, "liveSLAVerified": false}
	started = time.Now()
	worker, err := startTTSWorker(ctx, init)
	if err != nil {
		return err
	}
	defer worker.Close()
	result["modelLoadMillis"] = time.Since(started).Milliseconds()
	checks := []map[string]any{}
	for _, language := range []string{"ko", "en", "ja", "zh", "es"} {
		for attempt := 0; attempt < 2; attempt++ {
			started = time.Now()
			stepCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
			pcm, err := worker.Synthesize(stepCtx, setupSample(language), language)
			cancel()
			if err != nil {
				return err
			}
			wav := wavBytes(pcm)
			hash := sha256.Sum256(wav)
			check := map[string]any{"language": language, "attempt": attempt + 1, "millis": time.Since(started).Milliseconds(), "audioBytes": len(wav), "audioSHA256": hex.EncodeToString(hash[:]), "passed": true}
			if attempt == 1 {
				check["wav"] = wav
			}
			checks = append(checks, check)
		}
	}
	result["checks"], result["stage"] = checks, "PASS_NATIVE_BUNDLED_TTS_5_LANGUAGES"
	return json.NewEncoder(os.Stdout).Encode(result)
}
