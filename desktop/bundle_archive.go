package main

import (
	"archive/tar"
	"archive/zip"
	"compress/bzip2"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

const bundleMaxEntries = 4096
const bundleMaxFile int64 = 1 << 30
const bundleMaxTotal int64 = 2 << 30
const bundleMaxRatio int64 = 500
const bundleMaxManifest = 2 << 20

func artifactBundle(a Artifact) bool {
	// ZIP is the original runtime format; tar bundles additionally support TTS.
	return a.Format == "zip" || a.Format == "tar.bz2" && (a.Task == "runtime" || a.Task == "tts")
}

// These language codes are from the pinned Supertonic 3 model documentation.
// Kokoro's pinned sherpa model supplies Mandarin, which Supertonic 3 omits.
func bundledTTSAssetIDs(langs []string) []string {
	supertonic := map[string]bool{}
	for _, lang := range strings.Fields("en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi") {
		supertonic[lang] = true
	}
	useSupertonic, useKokoro := false, false
	for _, lang := range langs {
		base := strings.Split(strings.ToLower(strings.ReplaceAll(lang, "_", "-")), "-")[0]
		useSupertonic = useSupertonic || supertonic[base]
		useKokoro = useKokoro || base == "zh"
	}
	if !useSupertonic && !useKokoro {
		return nil
	}
	ids := []string{"runtime-sherpa-tts"}
	if useSupertonic {
		ids = append(ids, "tts-supertonic3")
	}
	if useKokoro {
		ids = append(ids, "tts-kokoro-zh")
	}
	return ids
}

func bundledTTSLanguagesSupported(langs []string) bool {
	for _, lang := range langs {
		if len(bundledTTSAssetIDs([]string{lang})) == 0 {
			return false
		}
	}
	return len(langs) > 0
}

type bundleFile struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
	Bytes  int64  `json:"bytes"`
}
type bundleManifest struct {
	Files []bundleFile `json:"files"`
}

// The catalogue pin is derived from the complete, SHA-verified source archive,
// never from an installation's writable manifest. Sorting makes old manifests
// compatible without trusting their formatting or original archive entry order.
func canonicalBundleTreeSHA(m bundleManifest) (string, error) {
	if len(m.Files) == 0 || len(m.Files) > bundleMaxEntries {
		return "", errors.New("bundle manifest file count invalid")
	}
	files := append([]bundleFile(nil), m.Files...)
	guard := bundleGuard{}
	for _, f := range files {
		if err := guard.add(f.Path, false, f.Bytes); err != nil {
			return "", err
		}
		if !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(f.SHA256) {
			return "", errors.New("bundle manifest hash invalid")
		}
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Path < files[j].Path })
	raw, err := json.Marshal(bundleManifest{Files: files})
	if err != nil || len(raw) > bundleMaxManifest {
		return "", errors.New("bundle manifest too large")
	}
	h := sha256.New()
	_, _ = io.WriteString(h, "MCastTalk bundle tree v1\n")
	_, _ = h.Write(raw)
	return hex.EncodeToString(h.Sum(nil)), nil
}

func readBundleManifest(ctx context.Context, root string) (bundleManifest, error) {
	var m bundleManifest
	if err := ctx.Err(); err != nil {
		return m, err
	}
	if err := portableLocalPath(root, true); err != nil {
		return m, err
	}
	path := filepath.Join(root, "installed-manifest.json")
	if err := portableLocalPath(path, false); err != nil {
		return m, err
	}
	f, err := os.Open(path)
	if err != nil {
		return m, err
	}
	b, err := io.ReadAll(io.LimitReader(engineContextReader{ctx, f}, bundleMaxManifest+1))
	ce := f.Close()
	if err != nil {
		return m, err
	}
	if ce != nil {
		return m, ce
	}
	if len(b) > bundleMaxManifest {
		return m, errors.New("bundle manifest too large")
	}
	err = portableJSON(b, &m)
	return m, err
}

func verifyPinnedArtifactBundle(ctx context.Context, root string, art Artifact) error {
	if !artifactBundle(art) || !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(art.TreeSHA256) {
		return errors.New("bundle requires a trusted catalogue tree pin")
	}
	m, err := readBundleManifest(ctx, root)
	if err != nil {
		return err
	}
	sha, err := canonicalBundleTreeSHA(m)
	if err != nil {
		return err
	}
	if sha != art.TreeSHA256 {
		return errors.New("bundle tree differs from the verified source archive pin")
	}
	return verifyBundleManifest(ctx, root, m)
}

// Workers receive only installed paths. Match their complete tree against an
// immutable built-in catalogue pin before native DLLs or model data are read.
func verifyInstalledBundle(ctx context.Context, root string) error {
	m, err := readBundleManifest(ctx, root)
	if err != nil {
		return err
	}
	sha, err := canonicalBundleTreeSHA(m)
	if err != nil {
		return err
	}
	for _, art := range Catalog() {
		if artifactBundle(art) && art.TreeSHA256 != "" && sha == art.TreeSHA256 {
			return verifyBundleManifest(ctx, root, m)
		}
	}
	return errors.New("installed bundle does not match a trusted catalogue tree pin")
}

func compareBundleManifests(existing, verifiedArchiveTree string) error {
	read := func(root string) (bundleManifest, error) {
		var m bundleManifest
		f, err := os.Open(filepath.Join(root, "installed-manifest.json"))
		if err != nil {
			return m, err
		}
		defer f.Close()
		b, err := io.ReadAll(io.LimitReader(f, bundleMaxManifest+1))
		if err != nil || len(b) > bundleMaxManifest {
			return m, errors.New("cannot compare bounded bundle manifest")
		}
		if err := portableJSON(b, &m); err != nil {
			return m, err
		}
		return m, nil
	}
	old, err := read(existing)
	if err != nil {
		return err
	}
	fresh, err := read(verifiedArchiveTree)
	if err != nil {
		return err
	}
	oldSHA, err := canonicalBundleTreeSHA(old)
	if err != nil {
		return err
	}
	freshSHA, err := canonicalBundleTreeSHA(fresh)
	if err != nil {
		return err
	}
	if oldSHA != freshSHA {
		return errors.New("existing bundle differs from the verified source archive")
	}
	return nil
}

type bundlePath struct {
	name      string
	directory bool
	explicit  bool
}
type bundleGuard struct {
	paths   map[string]bundlePath
	entries int
	total   int64
}

func (g *bundleGuard) add(name string, directory bool, size int64) error {
	name = strings.TrimSuffix(name, "/")
	if !portableName(name) || len(strings.Split(name, "/")) > 64 {
		return errors.New("invalid zip entry name or unsafe bundle path")
	}
	if strings.EqualFold(name, "installed-manifest.json") {
		return errors.New("archive may not replace installed-manifest.json")
	}
	if size < 0 || size > bundleMaxFile || directory && size != 0 {
		return errors.New("single file in bundle exceeds 1GiB or has invalid size")
	}
	g.entries++
	if g.entries > bundleMaxEntries {
		return errors.New("too many entries in bundle")
	}
	if size > bundleMaxTotal-g.total {
		return errors.New("total extracted size exceeds 2GiB")
	}
	g.total += size
	if g.paths == nil {
		g.paths = map[string]bundlePath{}
	}
	parts := strings.Split(name, "/")
	for i := 1; i <= len(parts); i++ {
		prefix := strings.Join(parts[:i], "/")
		key := strings.ToLower(prefix)
		isDirectory := i < len(parts) || directory
		old, exists := g.paths[key]
		if exists && (old.name != prefix || !old.directory || !isDirectory || i == len(parts) && old.explicit) {
			return errors.New("duplicate, case collision or file/directory conflict in bundle")
		}
		g.paths[key] = bundlePath{prefix, isDirectory, old.explicit || i == len(parts)}
	}
	return nil
}

func bundleRatio(total, compressed int64) error {
	if compressed <= 0 || total < 0 || compressed < (total+bundleMaxRatio-1)/bundleMaxRatio {
		return errors.New("high compression ratio detected (bundle bomb)")
	}
	return nil
}

func copyBundleFile(ctx context.Context, root, name string, size int64, src io.Reader) (bundleFile, error) {
	path := filepath.Join(root, filepath.FromSlash(name))
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return bundleFile{}, err
	}
	out, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return bundleFile{}, err
	}
	defer out.Close()
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(out, h), io.LimitReader(engineContextReader{ctx, src}, size+1))
	if err != nil {
		return bundleFile{}, err
	}
	if n != size {
		return bundleFile{}, errors.New("bundle entry length mismatch")
	}
	if err = out.Sync(); err != nil {
		return bundleFile{}, err
	}
	if err = out.Close(); err != nil {
		return bundleFile{}, err
	}
	return bundleFile{name, hex.EncodeToString(h.Sum(nil)), n}, nil
}

func extractArtifactBundle(ctx context.Context, archive, root string, art Artifact) error {
	guard := bundleGuard{}
	manifest := bundleManifest{}
	if art.Format == "zip" {
		z, err := zip.OpenReader(archive)
		if err != nil {
			return err
		}
		defer z.Close()
		for _, entry := range z.File {
			if err := ctx.Err(); err != nil {
				return err
			}
			dir := entry.FileInfo().IsDir()
			if entry.Mode()&os.ModeSymlink != 0 || !entry.Mode().IsRegular() && !dir || entry.Method != zip.Store && entry.Method != zip.Deflate || entry.UncompressedSize64 > uint64(bundleMaxFile) {
				return errors.New("symlink, special type, compression method or oversized file in zip")
			}
			if err := guard.add(entry.Name, dir, int64(entry.UncompressedSize64)); err != nil {
				return err
			}
			if entry.UncompressedSize64 > 0 && (entry.CompressedSize64 == 0 || entry.CompressedSize64 < (entry.UncompressedSize64+uint64(bundleMaxRatio)-1)/uint64(bundleMaxRatio)) {
				return errors.New("high compression ratio detected (zip bomb)")
			}
			if dir {
				if err := os.MkdirAll(filepath.Join(root, filepath.FromSlash(strings.TrimSuffix(entry.Name, "/"))), 0700); err != nil {
					return err
				}
				continue
			}
			r, err := entry.Open()
			if err != nil {
				return err
			}
			file, err := copyBundleFile(ctx, root, entry.Name, int64(entry.UncompressedSize64), r)
			ce := r.Close()
			if err != nil {
				return err
			}
			if ce != nil {
				return ce
			}
			manifest.Files = append(manifest.Files, file)
		}
	} else if art.Format == "tar.bz2" {
		f, err := os.Open(archive)
		if err != nil {
			return err
		}
		defer f.Close()
		// Bound decompression including headers, GNU/PAX metadata and padding.
		decoded := &io.LimitedReader{R: engineContextReader{ctx, bzip2.NewReader(engineContextReader{ctx, f})}, N: bundleMaxTotal + (16 << 20) + 1}
		r := tar.NewReader(decoded)
		for {
			if err := ctx.Err(); err != nil {
				return err
			}
			header, err := r.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				return err
			}
			dir := header.Typeflag == tar.TypeDir
			if header.Linkname != "" || !dir && header.Typeflag != tar.TypeReg && header.Typeflag != tar.TypeRegA {
				return errors.New("link or special type in tar bundle")
			}
			for key := range header.PAXRecords {
				if strings.HasPrefix(key, "GNU.sparse") {
					return errors.New("sparse tar entry rejected")
				}
			}
			if err := guard.add(header.Name, dir, header.Size); err != nil {
				return err
			}
			if err := bundleRatio(guard.total, art.Bytes); err != nil {
				return err
			}
			if dir {
				if err := os.MkdirAll(filepath.Join(root, filepath.FromSlash(strings.TrimSuffix(header.Name, "/"))), 0700); err != nil {
					return err
				}
				continue
			}
			file, err := copyBundleFile(ctx, root, header.Name, header.Size, r)
			if err != nil {
				return err
			}
			manifest.Files = append(manifest.Files, file)
		}
		// Finish bzip2 integrity checks and reject appended non-padding payload.
		buf := make([]byte, 32768)
		for {
			n, err := decoded.Read(buf)
			for _, b := range buf[:n] {
				if b != 0 {
					return errors.New("non-padding data after tar terminator")
				}
			}
			if err == io.EOF {
				break
			}
			if err != nil {
				return err
			}
		}
		if decoded.N == 0 {
			return errors.New("decompressed bundle limit exceeded")
		}
	} else {
		return errors.New("unsupported artifact bundle")
	}
	if len(manifest.Files) == 0 {
		return errors.New("bundle has no regular files")
	}
	if err := bundleRatio(guard.total, art.Bytes); err != nil {
		return err
	}
	b, err := json.Marshal(manifest)
	if err != nil || len(b) > bundleMaxManifest {
		return errors.New("bundle manifest too large")
	}
	if err = atomicFile(filepath.Join(root, "installed-manifest.json"), b); err != nil {
		return err
	}
	if art.TreeSHA256 != "" {
		return verifyPinnedArtifactBundle(ctx, root, art)
	}
	return verifyArtifactBundle(ctx, root)
}

// Validate every manifest entry and reject additional, aliased or linked files.
// This proves tree integrity against the saved install manifest, not publisher
// authenticity; an archive-to-tree binding is a separate trust requirement.
func verifyArtifactBundle(ctx context.Context, root string) error {
	m, err := readBundleManifest(ctx, root)
	if err != nil {
		return err
	}
	return verifyBundleManifest(ctx, root, m)
}

func verifyBundleManifest(ctx context.Context, root string, m bundleManifest) error {
	manifestPath := filepath.Join(root, "installed-manifest.json")
	if len(m.Files) == 0 || len(m.Files) > bundleMaxEntries {
		return errors.New("bundle manifest file count invalid")
	}
	guard := bundleGuard{}
	known := map[string]bool{}
	for _, file := range m.Files {
		if err := guard.add(file.Path, false, file.Bytes); err != nil {
			return err
		}
		if !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(file.SHA256) {
			return errors.New("bundle manifest hash invalid")
		}
		path := filepath.Join(root, filepath.FromSlash(file.Path))
		if err := portableLocalPath(path, false); err != nil {
			return err
		}
		if err := verifyModel(ctx, path, file.SHA256, file.Bytes, nil); err != nil {
			return err
		}
		known[filepath.Clean(path)] = true
	}
	return filepath.Walk(root, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := portableLocalPath(path, info.IsDir()); err != nil {
			return err
		}
		if info.IsDir() {
			if path != root {
				rel, _ := filepath.Rel(root, path)
				if !portableName(filepath.ToSlash(rel)) {
					return errors.New("unsafe bundle directory")
				}
			}
			return nil
		}
		if path != manifestPath && !known[filepath.Clean(path)] {
			return fmt.Errorf("unverified extra bundle file: %s", filepath.Base(path))
		}
		return nil
	})
}
