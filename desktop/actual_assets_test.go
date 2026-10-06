package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Explicit multi-GB download validation for the dedicated Windows POC data
// directory. Ordinary regression runs never download public model files.
// File integrity is not an inference/voice/quality acceptance result.
func TestActualPinnedSetupAssets(t *testing.T) {
	if os.Getenv("MCAST_ACTUAL_PINNED_ASSETS") != "1" {
		t.Skip("opt-in real multi-GB public model/runtime download")
	}
	base := os.Getenv("MCAST_ACTUAL_ASSET_DIR")
	if base == "" {
		base = filepath.Join(os.TempDir(), "MCastTalk-actual-environment")
	}
	if !filepath.IsAbs(base) {
		t.Fatal("provide an absolute MCAST_ACTUAL_ASSET_DIR on this OS")
	}
	store, err := openStore(base)
	if err != nil {
		t.Fatal(err)
	}
	defer store.db.Close()
	assets := NewAssetManager(base, func(asset InstalledAsset) error { return store.put("assets", asset.ID, asset) })
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Minute)
	defer cancel()
	done := make(chan struct{})
	defer close(done)
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				b, _ := json.Marshal(assets.Progress())
				fmt.Println("ACTUAL_ASSET_PROGRESS", string(b))
			}
		}
	}()
	ids := []string{"runtime-llama-cpu", "runtime-whisper-cpu", "qwen3-4b-q4", "whisper-turbo"}
	ids = append(ids, bundledTTSAssetIDs(append([]string{store.config().SourceLanguage}, store.config().TargetLanguages...))...)
	for _, id := range ids {
		art, ok := assets.getArtifact(id)
		if !ok {
			t.Fatal("missing production profile", id)
		}
		if err := portableProfile(art); err != nil {
			t.Fatal(err)
		}
		installed := store.assets()[id]
		if !setupInstalled(base, art, installed) {
			fmt.Println("ACTUAL_ASSET_DOWNLOAD", id, art.Bytes)
			if err := assets.Download(ctx, id); err != nil {
				t.Fatal(id, err)
			}
			installed = store.assets()[id]
		}
		if artifactBundle(art) {
			err = verifyPinnedArtifactBundle(ctx, installed.Path, art)
		} else {
			err = verifyModel(ctx, installed.Path, art.SHA256, art.Bytes, nil)
		}
		if err != nil {
			t.Fatal(id, err)
		}
		fmt.Println("ACTUAL_ASSET_INTEGRITY_VERIFIED", id, art.Bytes)
	}
	result, _ := json.MarshalIndent(map[string]any{"at": time.Now().UTC(), "assets": store.assets(), "filesIntegrityVerified": true, "modelsExecuted": false, "windowsExecuted": false, "qualityVerified": false, "liveSLAVerified": false}, "", "  ")
	if err := atomicFile(filepath.Join(base, "actual-integrity-evidence.json"), result); err != nil {
		t.Fatal(err)
	}
}

func TestActualPreparedEnvironmentExport(t *testing.T) {
	if os.Getenv("MCAST_ACTUAL_PREPARED_EXPORT") != "1" {
		t.Skip("opt-in export of downloaded multi-GB Windows environment")
	}
	base := os.Getenv("MCAST_ACTUAL_ASSET_DIR")
	if base == "" {
		base = filepath.Join(os.TempDir(), "MCastTalk-actual-environment")
	}
	if !filepath.IsAbs(base) {
		t.Fatal("provide an absolute MCAST_ACTUAL_ASSET_DIR on this OS")
	}
	store, err := openStore(base)
	if err != nil {
		t.Fatal(err)
	}
	defer store.db.Close()
	manager := NewPortableManager(store, NewAssetManager(base, nil))
	destination := filepath.Join(filepath.Dir(base), "actual-windows-cpu-environment-speech-v2.zip")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	report := PortableReport{RequiresWarmup: true}
	created := false
	if _, err = os.Stat(destination); os.IsNotExist(err) {
		report, err = manager.Export(ctx, destination, PortableExportOptions{Format: "zip"})
		if err != nil {
			t.Fatal(err)
		}
		created = true
	}
	// Verify every real ZIP payload without substituting a Windows diagnostic
	// for this Mac. OS compatibility must still be rejected by Inspect here.
	source, err := openPortable(destination)
	if err != nil {
		t.Fatal(err)
	}
	defer source.close()
	var verifiedBytes int64
	for _, file := range source.manifest.Files {
		input, err := source.entries[file.Path]()
		if err != nil {
			t.Fatal(err)
		}
		err = copyPortable(ctx, io.Discard, input, file)
		closeErr := input.Close()
		if err != nil || closeErr != nil {
			t.Fatal(file.Path, err, closeErr)
		}
		verifiedBytes += file.Bytes
	}
	inspection, inspectionErr := manager.Inspect(ctx, destination)
	if manager.targetOS == "windows" && inspectionErr != nil {
		t.Fatal(inspectionErr)
	}
	if manager.targetOS != "windows" && (inspectionErr == nil || !strings.Contains(inspectionErr.Error(), "Windows x64")) {
		t.Fatal("non-Windows target must not be certified compatible", inspectionErr)
	}
	if len(inspection.Manifest.Assets) != 7 || inspection.Manifest.TargetOS != "windows" || report.TargetVerified || !report.RequiresWarmup {
		t.Fatal("incorrect environment scope", report)
	}
	result, _ := json.MarshalIndent(map[string]any{"at": time.Now().UTC(), "path": destination, "createdThisRun": created, "export": report, "inspection": inspection, "inspectionError": fmt.Sprint(inspectionErr), "payloadBytesVerified": verifiedBytes, "payloadHashesVerified": true, "modelsExecuted": false, "qualityVerified": false, "liveSLAVerified": false}, "", "  ")
	if err := atomicFile(filepath.Join(filepath.Dir(base), "actual-export-evidence.json"), result); err != nil {
		t.Fatal(err)
	}
	fmt.Println("ACTUAL_WINDOWS_ENVIRONMENT_EXPORTED", destination, verifiedBytes, "target execution not verified")
}
