package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestPortablePinsRejectCoherentForgedTreeBeforeActivation(t *testing.T) {
	calls := portableSystemTestNoHTTP(t)
	for _, id := range []string{"runtime-llama-cpu", "runtime-sherpa-tts", "tts-supertonic3", "tts-kokoro-zh"} {
		t.Run(id, func(t *testing.T) {
			source := portableTestOpen(t, "forged-source-"+id)
			path := portableTestArchive(t, source, false)
			entries := portableTestReadZIP(t, path)
			root := "models/" + id
			if strings.HasPrefix(id, "runtime-") {
				root = "runtimes/" + id
			}
			manifestPath := root + "/installed-manifest.json"
			var tree bundleManifest
			manifestIndex := -1
			for i := range entries {
				if entries[i].name == manifestPath {
					manifestIndex = i
					if err := json.Unmarshal(entries[i].data, &tree); err != nil {
						t.Fatal(err)
					}
				}
			}
			if manifestIndex < 0 || len(tree.Files) == 0 {
				t.Fatal("bundle fixture missing")
			}
			payloadPath := root + "/" + tree.Files[0].Path
			changed := map[string][]byte{}
			for i := range entries {
				if entries[i].name == payloadPath {
					entries[i].data[len(entries[i].data)-1] ^= 1
					changed[payloadPath] = entries[i].data
				}
			}
			if len(changed) != 1 {
				t.Fatal("payload fixture missing")
			}
			tree.Files[0].SHA256 = portableTestHash(changed[payloadPath])
			raw, err := json.Marshal(tree)
			if err != nil {
				t.Fatal(err)
			}
			entries[manifestIndex].data = raw
			changed[manifestPath] = raw
			// Rewrite all claimed hashes as an attacker would. The immutable
			// profile's archive/tree pin remains the external trust boundary.
			portableTestManifest(t, entries, func(m map[string]any) {
				for _, v := range m["files"].([]any) {
					f := v.(map[string]any)
					if b, ok := changed[f["path"].(string)]; ok {
						f["sha256"], f["bytes"] = portableTestHash(b), len(b)
					}
				}
			})
			portableTestWriteZIP(t, path, entries)
			parsed, err := openPortable(path)
			if err != nil {
				t.Fatal("fixture should retain coherent portable metadata", err)
			}
			parsed.close()
			target := portableTestOpen(t, "forged-target-"+id)
			before := target.store.config()
			if _, err := target.manager.Inspect(context.Background(), path); err == nil {
				t.Fatal("inspection certified a coherently forged archive tree")
			}
			if _, err := target.manager.Import(context.Background(), path); err == nil {
				t.Fatal("import activated a coherently forged archive tree")
			}
			if !reflect.DeepEqual(before, target.store.config()) || !reflect.DeepEqual(target.installed, target.store.assets()) {
				t.Fatal("failed pin check changed the working environment")
			}
			for _, folder := range []string{"models", "runtimes"} {
				newPaths, _ := filepath.Glob(filepath.Join(target.store.dir, folder, "portable-*"))
				if len(newPaths) != 0 {
					t.Fatal("failed pin check activated a new path", newPaths)
				}
			}
		})
	}
	if *calls != 0 {
		t.Fatal("offline tree validation performed HTTP", *calls)
	}
}

func TestPortableExportRejectsCoherentInstalledTreeRewrite(t *testing.T) {
	f := portableTestOpen(t, "forged-export")
	root := f.installed["tts-supertonic3"].Path
	m, err := readBundleManifest(context.Background(), root)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, filepath.FromSlash(m.Files[0].Path))
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	b[len(b)-1] ^= 1
	if err := atomicFile(path, b); err != nil {
		t.Fatal(err)
	}
	m.Files[0].SHA256 = portableTestHash(b)
	raw, _ := json.Marshal(m)
	if err := atomicFile(filepath.Join(root, "installed-manifest.json"), raw); err != nil {
		t.Fatal(err)
	}
	if err := verifyArtifactBundle(context.Background(), root); err != nil {
		t.Fatal("fixture must be coherent against its rewritten self-manifest", err)
	}
	out := filepath.Join(portableTestTemp(t), "rejected-export.zip")
	if _, err := f.manager.Export(context.Background(), out, PortableExportOptions{Format: "zip", AssetIDs: f.ids}); err == nil {
		t.Fatal("export relabelled a tampered installed tree as official")
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Fatal("failed export published a package", err)
	}
}

func TestPortablePinsInspectRejectsAbsentInstalledFileList(t *testing.T) {
	f := portableTestOpen(t, "missing-tree-list")
	path := portableTestArchive(t, f, false)
	entries := portableTestReadZIP(t, path)
	name := "models/tts-supertonic3/installed-manifest.json"
	for i := range entries {
		if entries[i].name == name {
			entries[i].data = []byte("{}")
		}
	}
	portableTestManifest(t, entries, func(m map[string]any) {
		for _, v := range m["files"].([]any) {
			file := v.(map[string]any)
			if file["path"] == name {
				file["bytes"], file["sha256"] = 2, portableTestHash([]byte("{}"))
			}
		}
	})
	portableTestWriteZIP(t, path, entries)
	if _, err := f.manager.Inspect(context.Background(), path); err == nil {
		t.Fatal("inspection substituted the portable list for an absent installed file list")
	}
}

func TestPortableLegacyTreePinsAndVCDLLMetadataRemainOffline(t *testing.T) {
	vc := portableSystemTestPayload(t)
	calls := portableSystemTestNoHTTP(t)
	source := portableTestOpen(t, "historical-source")
	portableSystemTestCache(t, source, vc)
	path := filepath.Join(portableTestTemp(t), "historical-schema1")
	if _, err := source.manager.Export(context.Background(), path, PortableExportOptions{Format: "folder", AssetIDs: source.ids}); err != nil {
		t.Fatal(err)
	}
	manifestPath := filepath.Join(path, portableManifestName)
	b, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	var m portableManifest
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	for i := range m.Assets {
		if artifactBundle(m.Assets[i].Profile) {
			m.Assets[i].Profile.TreeSHA256 = ""
		}
	}
	if len(m.SystemDependencies) != 1 || len(m.SystemDependencies[0].Profile.RequiredDLLs) != 5 {
		t.Fatal("current dependency fixture missing")
	}
	m.SystemDependencies[0].Profile.RequiredDLLs = m.SystemDependencies[0].Profile.RequiredDLLs[:4]
	b, _ = json.Marshal(m)
	if err := atomicFile(manifestPath, b); err != nil {
		t.Fatal(err)
	}
	target := portableTestOpen(t, "historical-target")
	inspection, err := target.manager.Inspect(context.Background(), path)
	if err != nil || !inspection.Compatible {
		t.Fatal("historical fixed metadata was not accepted with current tree verification", err)
	}
	report, err := target.manager.Import(context.Background(), path)
	if err != nil || !report.OfflineComplete || report.TargetVerified || !report.RequiresWarmup {
		t.Fatalf("historical cache/tree transfer: %+v error=%v", report, err)
	}
	if len(SystemDependencies()[0].RequiredDLLs) != 5 || SystemDependencies()[0].RequiredDLLs[4] != "MSVCP140_1.dll" {
		t.Fatal("legacy metadata weakened the current runtime dependency probe")
	}
	cache, _ := SystemDependencyInstallerPath(target.store.dir, SystemDependencies()[0].ID)
	if err := verifyModel(context.Background(), cache, SystemDependencies()[0].SHA256, SystemDependencies()[0].Bytes, nil); err != nil {
		t.Fatal("historical installer changed during transfer", err)
	}
	if *calls != 0 {
		t.Fatal("historical offline import attempted HTTP", *calls)
	}
}

func TestPortableProfileAllowsOnlyAbsentLegacyTreePin(t *testing.T) {
	for _, a := range Catalog() {
		if !artifactBundle(a) {
			continue
		}
		a.TreeSHA256 = ""
		if err := portableProfile(a); err != nil {
			t.Fatal("legacy absent tree field rejected", a.ID, err)
		}
		a.TreeSHA256 = strings.Repeat("0", 64)
		if err := portableProfile(a); err == nil {
			t.Fatal("explicit substituted tree pin accepted", a.ID)
		}
		a.TreeSHA256 = ""
		a.Bytes++
		if err := portableProfile(a); err == nil {
			t.Fatal("legacy exception accepted a different archive profile", a.ID)
		}
	}
}
