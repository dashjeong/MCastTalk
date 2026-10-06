package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

// Tiny ONNX/PE stand-ins exercise tree transfer/rollback only. They are not
// runnable TTS models and provide no speech quality or Windows acceptance proof.
func portableTestAddTTSBundles(t *testing.T, f *portableTestFixture) {
	t.Helper()
	for _, id := range []string{"runtime-sherpa-tts", "tts-supertonic3", "tts-kokoro-zh"} {
		profile, ok := f.assets.getArtifact(id)
		if !ok {
			t.Fatalf("TTS fixture profile missing: %s", id)
		}
		folder, name, data := "models", "payload/model.onnx", []byte("synthetic ONNX tree transfer fixture, never executed")
		if profile.Task == "runtime" {
			folder, name, data = "runtimes", "payload/lib/sherpa-onnx-c-api.dll", portableTestPE()
		}
		root := filepath.Join(f.store.dir, folder, id+"-fixture")
		manifest := bundleManifest{}
		for _, entry := range []string{name, "payload/data/token.txt"} {
			payload := data
			if strings.HasSuffix(entry, ".txt") {
				payload = []byte("synthetic fixture tokens")
			}
			if err := atomicFile(filepath.Join(root, filepath.FromSlash(entry)), payload); err != nil {
				t.Fatal(err)
			}
			manifest.Files = append(manifest.Files, bundleFile{entry, portableTestHash(payload), int64(len(payload))})
		}
		raw, _ := json.Marshal(manifest)
		if err := atomicFile(filepath.Join(root, "installed-manifest.json"), raw); err != nil {
			t.Fatal(err)
		}
		if err := f.store.put("assets", id, InstalledAsset{ID: id, Path: root, SHA256: profile.SHA256, Bytes: profile.Bytes, InstalledAt: time.Now().UTC()}); err != nil {
			t.Fatal(err)
		}
		f.ids = append(f.ids, id)
	}
}

func TestPortableBundleTTSDefaultSelectionAndLegacyWarning(t *testing.T) {
	f := portableTestOpen(t, "tts-default")
	path := filepath.Join(portableTestTemp(t), "default-tts.zip")
	if _, err := f.manager.Export(context.Background(), path, PortableExportOptions{Format: "zip"}); err != nil {
		t.Fatal(err)
	}
	source, err := openPortable(path)
	if err != nil {
		t.Fatal(err)
	}
	if !portableTTSComplete(source.manifest) {
		t.Fatal("default export omitted selected-language TTS bundles")
	}
	source.close()
	legacy := filepath.Join(portableTestTemp(t), "legacy-models.zip")
	report, err := f.manager.Export(context.Background(), legacy, PortableExportOptions{Format: "zip", AssetIDs: f.ids[:4]})
	if err != nil || report.OfflineComplete || !strings.Contains(strings.Join(report.Warnings, " "), "음성") {
		t.Fatalf("legacy export: %+v %v", report, err)
	}
	target := portableTestOpen(t, "tts-legacy-target")
	report, err = target.manager.Import(context.Background(), legacy)
	if err != nil || report.OfflineComplete || report.TargetVerified || !strings.Contains(strings.Join(report.Warnings, " "), "미완비") {
		t.Fatalf("schema-1 legacy models did not remain readable with truthful warning: %+v %v", report, err)
	}
}

func TestPortableBundleTTSTamperAndCommitRollback(t *testing.T) {
	for _, failure := range []string{"tree-tamper", "commit"} {
		t.Run(failure, func(t *testing.T) {
			source := portableTestOpen(t, "tts-rollback-source-"+failure)
			path := portableTestArchive(t, source, false)
			target := portableTestOpen(t, "tts-rollback-target-"+failure)
			before := target.store.config()
			if failure == "tree-tamper" {
				entries := portableTestReadZIP(t, path)
				changed := false
				for i := range entries {
					if strings.HasPrefix(entries[i].name, "models/tts-supertonic3/") && strings.HasSuffix(entries[i].name, ".onnx") {
						entries[i].data[0] ^= 1
						changed = true
					}
				}
				if !changed {
					t.Fatal("TTS fixture payload missing")
				}
				portableTestWriteZIP(t, path, entries)
			} else {
				target.manager.beforeCommit = func() error { return errors.New("synthetic final commit failure") }
			}
			if _, err := target.manager.Import(context.Background(), path); err == nil {
				t.Fatal("failed TTS bundle import activated")
			}
			if !reflect.DeepEqual(before, target.store.config()) || !reflect.DeepEqual(target.installed, target.store.assets()) {
				t.Fatal("failed bundle import changed working config or registry")
			}
			for _, installed := range target.installed {
				if _, err := os.Stat(installed.Path); err != nil {
					t.Fatal("failed bundle import removed a working asset", err)
				}
			}
			for _, folder := range []string{"models", "runtimes"} {
				paths, _ := filepath.Glob(filepath.Join(target.store.dir, folder, "portable-*"))
				if len(paths) != 0 {
					t.Fatal("failed bundle import retained new activated paths", paths)
				}
			}
		})
	}
}

func TestBundledTTSLanguages(t *testing.T) {
	if got := bundledTTSAssetIDs([]string{"ko-KR", "en", "ja", "es", "zh-CN", "ko"}); !reflect.DeepEqual(got, []string{"runtime-sherpa-tts", "tts-supertonic3", "tts-kokoro-zh"}) {
		t.Fatal(got)
	}
	if got := bundledTTSAssetIDs([]string{"vi", "fr-FR"}); !reflect.DeepEqual(got, []string{"runtime-sherpa-tts", "tts-supertonic3"}) {
		t.Fatal(got)
	}
	if bundledTTSLanguagesSupported([]string{"en", "unknown"}) || bundledTTSLanguagesSupported(nil) {
		t.Fatal("unknown language claimed bundled support")
	}
}
