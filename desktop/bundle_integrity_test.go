package main

import (
	"archive/tar"
	"archive/zip"
	"compress/bzip2"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// These tests establish archive-to-tree integrity. They do not execute models,
// Windows binaries or establish speech quality, SLA or Gemini approval.
func TestBundlePinRejectsCoherentManifestRewrite(t *testing.T) {
	root := portableTestTemp(t)
	data := []byte("original model payload from a trusted archive")
	name := "model/weights.onnx"
	if err := atomicFile(filepath.Join(root, filepath.FromSlash(name)), data); err != nil {
		t.Fatal(err)
	}
	m := bundleManifest{Files: []bundleFile{{name, portableTestHash(data), int64(len(data))}}}
	pin, err := canonicalBundleTreeSHA(m)
	if err != nil {
		t.Fatal(err)
	}
	profile := Artifact{ID: "fixture-trusted-tree", Task: "tts", Format: "tar.bz2", TreeSHA256: pin}
	write := func(m bundleManifest) {
		raw, err := json.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		if err := atomicFile(filepath.Join(root, "installed-manifest.json"), raw); err != nil {
			t.Fatal(err)
		}
	}
	write(m)
	if err := verifyPinnedArtifactBundle(context.Background(), root, profile); err != nil {
		t.Fatal("trusted complete tree rejected", err)
	}
	data = []byte("attacker replaced both the payload and its writable manifest")
	if err := atomicFile(filepath.Join(root, filepath.FromSlash(name)), data); err != nil {
		t.Fatal(err)
	}
	m.Files[0].Bytes, m.Files[0].SHA256 = int64(len(data)), portableTestHash(data)
	write(m)
	if err := verifyArtifactBundle(context.Background(), root); err != nil {
		t.Fatal("fixture must demonstrate a coherent self-manifest", err)
	}
	if err := verifyPinnedArtifactBundle(context.Background(), root, profile); err == nil {
		t.Fatal("coherently rewritten payload and manifest escaped the trusted archive pin")
	}
	if err := verifyInstalledBundle(context.Background(), root); err == nil {
		t.Fatal("worker accepted a self-declared tree without a built-in source pin")
	}
}

func TestBundlePinCanonicalOrderAndEntryValidation(t *testing.T) {
	m := bundleManifest{Files: []bundleFile{{"z/data", strings.Repeat("a", 64), 3}, {"a/model", strings.Repeat("b", 64), 4}}}
	copyBefore := append([]bundleFile(nil), m.Files...)
	pin, err := canonicalBundleTreeSHA(m)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(copyBefore, m.Files) {
		t.Fatal("canonical hashing mutated the caller's immutable file list")
	}
	m.Files[0], m.Files[1] = m.Files[1], m.Files[0]
	again, err := canonicalBundleTreeSHA(m)
	if err != nil || again != pin {
		t.Fatal("legacy manifest order changed the content pin")
	}
	for _, mutation := range []func(*bundleManifest){
		func(m *bundleManifest) { m.Files[0].Bytes++ },
		func(m *bundleManifest) { m.Files[0].Path += "-different" },
		func(m *bundleManifest) { m.Files[0].SHA256 = strings.Repeat("c", 64) },
		func(m *bundleManifest) { m.Files = m.Files[:1] },
	} {
		changed := bundleManifest{Files: append([]bundleFile(nil), m.Files...)}
		mutation(&changed)
		newPin, err := canonicalBundleTreeSHA(changed)
		if err != nil || newPin == pin {
			t.Fatal("complete path/content/size/list change was not bound by the pin")
		}
	}
	for _, bad := range []bundleManifest{
		{},
		{Files: []bundleFile{{"../escape", strings.Repeat("a", 64), 1}}},
		{Files: []bundleFile{{"installed-manifest.json", strings.Repeat("a", 64), 1}}},
		{Files: []bundleFile{{"a", strings.Repeat("a", 64), -1}}},
		{Files: []bundleFile{{"a", "untrusted", 1}}},
		{Files: []bundleFile{{"a", strings.Repeat("a", 64), 1}, {"A", strings.Repeat("a", 64), 1}}},
	} {
		if _, err := canonicalBundleTreeSHA(bad); err == nil {
			t.Fatal("unsafe or ambiguous canonical tree accepted", bad)
		}
	}
}

func TestCatalogBundlesRequireImmutableTreePins(t *testing.T) {
	count := 0
	for _, a := range Catalog() {
		if !artifactBundle(a) {
			continue
		}
		count++
		if len(a.TreeSHA256) != 64 {
			t.Fatal("runtime/model bundle is missing its archive-derived pin", a.ID)
		}
		if _, err := hex.DecodeString(a.TreeSHA256); err != nil {
			t.Fatal("invalid tree pin", a.ID, err)
		}
	}
	if count != 10 {
		t.Fatal("review the official bundle coverage when catalogue changes", count)
	}
}

func TestBundlePinsAllOfficialLocalArchives(t *testing.T) {
	if os.Getenv("MCAST_OFFICIAL_BUNDLE_PINS") != "1" {
		t.Skip("opt-in archive verification only; public payloads already local, no HTTP or inference")
	}
	archiveDir := officialArchiveTestDir(t)
	for _, art := range Catalog() {
		if !artifactBundle(art) {
			continue
		}
		t.Run(art.ID, func(t *testing.T) {
			path := filepath.Join(archiveDir, art.ID+"."+art.Format)
			if err := verifyModel(context.Background(), path, art.SHA256, art.Bytes, nil); err != nil {
				t.Fatal("official archive identity", err)
			}
			m := bundleManifest{}
			guard := bundleGuard{}
			add := func(name string, size int64, r io.Reader) {
				if err := guard.add(name, false, size); err != nil {
					t.Fatal(err)
				}
				h := sha256.New()
				n, err := io.Copy(h, io.LimitReader(r, size+1))
				if err != nil || n != size {
					t.Fatal("complete archive entry must be read", name, n, err)
				}
				m.Files = append(m.Files, bundleFile{name, hex.EncodeToString(h.Sum(nil)), n})
			}
			if art.Format == "zip" {
				z, err := zip.OpenReader(path)
				if err != nil {
					t.Fatal(err)
				}
				defer z.Close()
				for _, entry := range z.File {
					if entry.FileInfo().IsDir() {
						continue
					}
					r, err := entry.Open()
					if err != nil {
						t.Fatal(err)
					}
					add(entry.Name, int64(entry.UncompressedSize64), r)
					if err := r.Close(); err != nil {
						t.Fatal(err)
					}
				}
			} else {
				f, err := os.Open(path)
				if err != nil {
					t.Fatal(err)
				}
				defer f.Close()
				archive := tar.NewReader(bzip2.NewReader(f))
				for {
					entry, err := archive.Next()
					if err == io.EOF {
						break
					}
					if err != nil {
						t.Fatal(err)
					}
					if entry.Typeflag == tar.TypeDir {
						continue
					}
					if entry.Typeflag != tar.TypeReg && entry.Typeflag != tar.TypeRegA {
						t.Fatal("official bundle has a special entry", entry.Name)
					}
					add(entry.Name, entry.Size, archive)
				}
			}
			sha, err := canonicalBundleTreeSHA(m)
			if err != nil || sha != art.TreeSHA256 {
				t.Fatalf("Go archive-derived pin mismatch: want=%s got=%s error=%v", art.TreeSHA256, sha, err)
			}
			t.Logf("official archive and complete tree verified: files=%d treeSHA256=%s", len(m.Files), sha)
		})
	}
}

func officialArchiveTestDir(t *testing.T) string {
	t.Helper()
	dir := os.Getenv("MCAST_OFFICIAL_ARCHIVE_DIR")
	if dir == "" {
		t.Fatal("official archive verification is enabled; set MCAST_OFFICIAL_ARCHIVE_DIR to a directory containing {artifact.ID}.{zip|tar.bz2}")
	}
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		t.Fatalf("MCAST_OFFICIAL_ARCHIVE_DIR must be an existing directory: %v", err)
	}
	return dir
}
