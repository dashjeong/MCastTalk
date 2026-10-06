package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Portable tests check cache identity and transaction safety only. The actual
// pinned local payload is never executed or signature-validated here; this is
// not evidence of Windows installation, AI quality/SLA, or Gemini approval.
func portableSystemTestPayload(t *testing.T) []byte {
	t.Helper()
	path := os.Getenv("MCAST_TEST_VC_REDIST")
	if path == "" {
		path = filepath.Join(os.TempDir(), "mcasttalk-vc-redist.x64.exe")
	}
	if _, err := os.Stat(path); os.IsNotExist(err) {
		t.Skip("pinned local VC++ payload absent; set MCAST_TEST_VC_REDIST; no download is performed")
	}
	d := SystemDependencies()[0]
	if err := verifyModel(context.Background(), path, d.SHA256, d.Bytes, nil); err != nil {
		t.Fatalf("local official fixture identity: %v", err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func portableSystemTestCache(t *testing.T, f *portableTestFixture, b []byte) string {
	t.Helper()
	path, err := SystemDependencyInstallerPath(f.store.dir, SystemDependencies()[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := atomicFile(path, b); err != nil {
		t.Fatal(err)
	}
	return path
}

func portableSystemTestNoHTTP(t *testing.T) *int {
	t.Helper()
	calls := 0
	oldDefault, oldClient := http.DefaultTransport, http.DefaultClient.Transport
	blocked := &mockTransport{roundTripFunc: func(*http.Request) (*http.Response, error) {
		calls++
		return nil, errors.New("offline portable fixture blocks HTTP")
	}}
	http.DefaultTransport, http.DefaultClient.Transport = blocked, blocked
	t.Cleanup(func() { http.DefaultTransport, http.DefaultClient.Transport = oldDefault, oldClient })
	return &calls
}

func TestPortableSystemDependencyAbsentLegacyAndInvalidCache(t *testing.T) {
	calls := portableSystemTestNoHTTP(t)
	source := portableTestOpen(t, "legacy-no-vc")
	path := filepath.Join(portableTestTemp(t), "legacy.zip")
	report, err := source.manager.Export(context.Background(), path, PortableExportOptions{Format: "zip", AssetIDs: source.ids})
	if err != nil {
		t.Fatal(err)
	}
	if report.OfflineComplete || !strings.Contains(strings.Join(report.Warnings, " "), "VC++") {
		t.Fatalf("missing dependency not reported: %+v", report)
	}
	s, err := openPortable(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(s.manifest.SystemDependencies) != 0 || s.manifest.Schema != 1 {
		t.Fatalf("legacy schema changed: %+v", s.manifest)
	}
	s.close()
	target := portableTestOpen(t, "legacy-target")
	report, err = target.manager.Import(context.Background(), path)
	if err != nil || report.OfflineComplete || report.TargetVerified || !report.RequiresWarmup {
		t.Fatalf("legacy import: %+v %v", report, err)
	}
	cache, _ := SystemDependencyInstallerPath(target.store.dir, SystemDependencies()[0].ID)
	if _, err := os.Stat(cache); !os.IsNotExist(err) {
		t.Fatalf("legacy import created an installer: %v", err)
	}
	portableSystemTestCache(t, source, []byte("corrupt cache"))
	badDest := filepath.Join(portableTestTemp(t), "bad.zip")
	if _, err := source.manager.Export(context.Background(), badDest, PortableExportOptions{Format: "zip", AssetIDs: source.ids}); err == nil {
		t.Fatal("corrupt system cache exported")
	}
	if _, err := os.Stat(badDest); !os.IsNotExist(err) {
		t.Fatalf("failed export published archive: %v", err)
	}
	if *calls != 0 {
		t.Fatalf("portable operation attempted %d HTTP requests", *calls)
	}
}

func TestPortableSystemDependencyMetadataIsFixed(t *testing.T) {
	source := portableTestOpen(t, "vc-metadata")
	s, err := openPortable(portableTestArchive(t, source, false))
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	d := SystemDependencies()[0]
	base := s.manifest
	base.SystemDependencies = []portableSystemDependency{{Profile: d, Path: portableDependencyPath(d)}}
	base.Files = append(base.Files, portableFile{portableDependencyPath(d), "", d.Bytes, d.SHA256})
	validate := func(m portableManifest) error {
		// Metadata validation fixture declares the pinned byte count without
		// allocating a fake 18 MB payload. Import/hash tests use the real file.
		entries := map[string]func() (io.ReadCloser, error){portableManifestName: nil}
		sizes := map[string]int64{}
		for _, file := range m.Files {
			entries[file.Path] = nil
			sizes[file.Path] = file.Bytes
		}
		return validatePortableManifest(m, entries, sizes)
	}
	if err := validate(base); err != nil {
		t.Fatalf("control fixed metadata: %v", err)
	}
	for _, tc := range []struct {
		name   string
		change func(*portableManifest)
	}{
		{"version", func(m *portableManifest) { m.SystemDependencies[0].Profile.Version = "0.0.0.0" }},
		{"url", func(m *portableManifest) { m.SystemDependencies[0].Profile.URL = "https://evil.invalid/vc.exe" }},
		{"sha", func(m *portableManifest) { m.SystemDependencies[0].Profile.SHA256 = strings.Repeat("0", 64) }},
		{"bytes", func(m *portableManifest) { m.SystemDependencies[0].Profile.Bytes++ }},
		{"dll-policy", func(m *portableManifest) { m.SystemDependencies[0].Profile.RequiredDLLs = nil }},
		{"unknown", func(m *portableManifest) { m.SystemDependencies[0].Profile.ID = "other-installer" }},
		{"terms", func(m *portableManifest) { m.SystemDependencies[0].Profile.TermsURL = "https://evil.invalid/terms" }},
		{"path", func(m *portableManifest) { m.SystemDependencies[0].Path = "application/MCastTalk.exe" }},
		{"duplicate", func(m *portableManifest) {
			m.SystemDependencies = append(m.SystemDependencies, m.SystemDependencies[0])
		}},
		{"missing-file", func(m *portableManifest) { m.Files = m.Files[:len(m.Files)-1] }},
		{"unlisted-installer", func(m *portableManifest) { m.SystemDependencies = nil }},
		{"file-sha", func(m *portableManifest) { m.Files[len(m.Files)-1].SHA256 = strings.Repeat("0", 64) }},
		{"file-bytes", func(m *portableManifest) { m.Files[len(m.Files)-1].Bytes-- }},
		{"asset-disguise", func(m *portableManifest) { m.Files[len(m.Files)-1].AssetID = m.SafeConfig.TranslationModel }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			b, _ := json.Marshal(base)
			var changed portableManifest
			if err := json.Unmarshal(b, &changed); err != nil {
				t.Fatal(err)
			}
			tc.change(&changed)
			if err := validate(changed); err == nil {
				t.Fatal("altered dependency metadata was accepted")
			}
		})
	}
}

func TestPortableSystemDependencyI386ExceptionDoesNotAllowApplication(t *testing.T) {
	source := portableTestOpen(t, "i386-app-source")
	entries := portableTestReadZIP(t, portableTestArchive(t, source, false))
	b := portableTestPE()
	binary.LittleEndian.PutUint16(b[0x84:], 0x14c)
	name := "application/MCastTalk.exe"
	entries = append(entries, portableTestEntry{name, b, 0600, zip.Store})
	portableTestManifest(t, entries, func(m map[string]any) {
		m["executableIncluded"] = true
		m["files"] = append(m["files"].([]any), map[string]any{"path": name, "bytes": len(b), "sha256": portableTestHash(b)})
	})
	path := filepath.Join(portableTestTemp(t), "i386-application.zip")
	portableTestWriteZIP(t, path, entries)
	target := portableTestOpen(t, "i386-app-target")
	before := target.store.config()
	if _, err := target.manager.Import(context.Background(), path); err == nil {
		t.Fatal("I386 application inherited the fixed installer exception")
	}
	if !reflect.DeepEqual(before, target.store.config()) || !reflect.DeepEqual(target.installed, target.store.assets()) {
		t.Fatal("rejected I386 application activated environment")
	}
}

func TestPortableSystemDependencyActualCacheOfflineRoundtrip(t *testing.T) {
	b := portableSystemTestPayload(t)
	calls := portableSystemTestNoHTTP(t)
	for _, format := range []string{"zip", "folder"} {
		t.Run(format, func(t *testing.T) {
			source := portableTestOpen(t, "vc-source-"+format)
			portableSystemTestCache(t, source, b)
			path := filepath.Join(portableTestTemp(t), "vc-package-"+format)
			report, err := source.manager.Export(context.Background(), path, PortableExportOptions{Format: format, AssetIDs: source.ids})
			if err != nil || !report.OfflineComplete || report.TargetVerified {
				t.Fatalf("export: %+v %v", report, err)
			}
			target := portableTestOpen(t, "vc-target-"+format)
			inspection, err := target.manager.Inspect(context.Background(), path)
			if err != nil || !inspection.Compatible || len(inspection.Manifest.SystemDependencies) != 1 {
				t.Fatalf("inspect: %+v %v", inspection, err)
			}
			cache, _ := SystemDependencyInstallerPath(target.store.dir, SystemDependencies()[0].ID)
			if _, err := os.Stat(cache); !os.IsNotExist(err) {
				t.Fatal("inspection activated installer cache")
			}
			report, err = target.manager.Import(context.Background(), path)
			if err != nil || !report.OfflineComplete || report.TargetVerified || !report.RequiresWarmup {
				t.Fatalf("import: %+v %v", report, err)
			}
			if err := verifyModel(context.Background(), cache, SystemDependencies()[0].SHA256, SystemDependencies()[0].Bytes, nil); err != nil {
				t.Fatal(err)
			}
			if portablePE(cache) == nil {
				t.Fatal("fixture unexpectedly AMD64; I386-only exception was not exercised")
			}
			if err := portableDependencyPE(cache, SystemDependencies()[0]); err != nil {
				t.Fatal(err)
			}
			if len(report.Installed) != len(source.ids) {
				t.Fatal("installer was registered as an engine asset")
			}
			if !strings.Contains(strings.Join(report.Warnings, " "), "자동 설치하지 않습니다") {
				t.Fatal("cache-only action not explained")
			}
		})
	}
	if *calls != 0 {
		t.Fatalf("offline cache roundtrip attempted %d HTTP requests", *calls)
	}
}

func TestPortableSystemDependencyActualCacheTamperAndRollback(t *testing.T) {
	b := portableSystemTestPayload(t)
	calls := portableSystemTestNoHTTP(t)
	source := portableTestOpen(t, "vc-rollback-source")
	portableSystemTestCache(t, source, b)
	path := filepath.Join(portableTestTemp(t), "vc-folder")
	if _, err := source.manager.Export(context.Background(), path, PortableExportOptions{Format: "folder", AssetIDs: source.ids}); err != nil {
		t.Fatal(err)
	}
	for _, existing := range []string{"missing", "corrupt", "verified"} {
		t.Run("rollback-"+existing, func(t *testing.T) {
			target := portableTestOpen(t, "vc-rollback-"+existing)
			cache, _ := SystemDependencyInstallerPath(target.store.dir, SystemDependencies()[0].ID)
			var old []byte
			if existing == "corrupt" {
				old = []byte("previous cache retained on rollback")
			}
			if existing == "verified" {
				old = b
			}
			if old != nil {
				portableSystemTestCache(t, target, old)
			}
			before := target.store.config()
			target.manager.beforeCommit = func() error { return errors.New("injected portable commit failure") }
			if _, err := target.manager.Import(context.Background(), path); err == nil {
				t.Fatal("commit failure reported success")
			}
			if !reflect.DeepEqual(before, target.store.config()) || !reflect.DeepEqual(target.installed, target.store.assets()) {
				t.Fatal("failed import activated environment")
			}
			got, err := os.ReadFile(cache)
			if old == nil {
				if !os.IsNotExist(err) {
					t.Fatalf("rollback left new installer: %v", err)
				}
			} else if err != nil || !bytes.Equal(old, got) {
				t.Fatal("rollback changed previous installer cache")
			}
		})
	}
	t.Run("body-tamper", func(t *testing.T) {
		d := SystemDependencies()[0]
		file, err := os.OpenFile(filepath.Join(path, filepath.FromSlash(portableDependencyPath(d))), os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal(err)
		}
		_, err = file.WriteAt([]byte{b[128] ^ 0xff}, 128)
		closeErr := file.Close()
		if err != nil || closeErr != nil {
			t.Fatalf("tamper fixture: %v %v", err, closeErr)
		}
		target := portableTestOpen(t, "vc-tamper-target")
		old := []byte("target prior cache")
		cache := portableSystemTestCache(t, target, old)
		if _, err := target.manager.Inspect(context.Background(), path); err == nil {
			t.Fatal("inspection accepted corrupted installer")
		}
		if _, err := target.manager.Import(context.Background(), path); err == nil {
			t.Fatal("import accepted corrupted installer")
		}
		got, _ := os.ReadFile(cache)
		if !bytes.Equal(old, got) || !reflect.DeepEqual(target.installed, target.store.assets()) {
			t.Fatal("tampered installer modified target")
		}
	})
	if *calls != 0 {
		t.Fatalf("offline failure attempted %d HTTP requests", *calls)
	}
}
