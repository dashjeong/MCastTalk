package main

import (
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

//go:embed catalog.json
var embeddedCatalog []byte

var reservedNames = regexp.MustCompile(`^(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$`)

func isValidZipName(name string) bool {
	return portableName(strings.TrimSuffix(name, "/"))
}

func isValidRepoName(repo string) bool {
	parts := strings.Split(repo, "/")
	if len(parts) != 2 {
		return false
	}
	for _, p := range parts {
		if p == "" || p == "." || p == ".." || strings.Contains(p, "..") {
			return false
		}
		for _, c := range p {
			if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
				return false
			}
		}
	}
	return true
}

type AssetManager struct {
	dataDir        string
	persist        func(InstalledAsset) error
	registry       []Artifact
	customRegistry []Artifact
	mu             sync.Mutex
	progress       map[string]DownloadProgress
	cancelMap      map[string]context.CancelFunc
	muCancel       sync.Mutex
}

func Catalog() []Artifact {
	var c []Artifact
	_ = json.Unmarshal(embeddedCatalog, &c)
	return c
}

func NewAssetManager(dataDir string, persist func(InstalledAsset) error) *AssetManager {
	am := &AssetManager{
		dataDir:   dataDir,
		persist:   persist,
		registry:  Catalog(),
		progress:  make(map[string]DownloadProgress),
		cancelMap: make(map[string]context.CancelFunc),
	}
	am.loadRegistry()
	return am
}

func (a *AssetManager) loadRegistry() {
	b, err := os.ReadFile(filepath.Join(a.dataDir, "registry.json"))
	if err == nil {
		var c []Artifact
		if err := json.Unmarshal(b, &c); err == nil {
			a.customRegistry = c
			a.registry = append(a.registry, c...)
		}
	}
}

func (a *AssetManager) saveRegistry() error {
	b, err := json.MarshalIndent(a.customRegistry, "", "  ")
	if err != nil {
		return err
	}
	return atomicFile(filepath.Join(a.dataDir, "registry.json"), b)
}

func (a *AssetManager) getArtifact(id string) (Artifact, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, art := range a.registry {
		if art.ID == id {
			return art, true
		}
	}
	return Artifact{}, false
}

func (a *AssetManager) Download(ctx context.Context, id string) (finalErr error) {
	art, ok := a.getArtifact(id)
	if !ok {
		return errors.New("artifact not found")
	}

	cancelCtx, cancelFunc := context.WithCancel(ctx)
	a.muCancel.Lock()
	if _, active := a.cancelMap[id]; active {
		a.muCancel.Unlock()
		cancelFunc()
		return errors.New("asset operation already active")
	}
	a.cancelMap[id] = cancelFunc
	a.muCancel.Unlock()
	defer func() { a.muCancel.Lock(); delete(a.cancelMap, id); a.muCancel.Unlock(); cancelFunc() }()
	a.mu.Lock()
	if p, ok := a.progress[id]; ok && (p.State == "downloading" || p.State == "queued") {
		a.mu.Unlock()
		return errors.New("already downloading")
	}
	a.progress[id] = DownloadProgress{ID: id, State: "downloading", Received: 0, Total: art.Bytes}
	a.mu.Unlock()

	defer func() {
		a.mu.Lock()
		if p, ok := a.progress[id]; ok && p.State == "downloading" {
			p.State = "failed"
			if finalErr != nil {
				p.Error = finalErr.Error()
			}
			a.progress[id] = p
		}
		a.mu.Unlock()
	}()

	downloadsDir := filepath.Join(a.dataDir, "downloads")
	_ = os.MkdirAll(downloadsDir, 0755)

	destPath := filepath.Join(downloadsDir, id+".part")

	var received int64 = 0
	if stat, err := os.Stat(destPath); err == nil {
		received = stat.Size()
		if received > art.Bytes {
			os.Remove(destPath)
			received = 0
		}
	}

	req, err := http.NewRequestWithContext(cancelCtx, "GET", art.URL, nil)
	if err != nil {
		finalErr = err
		return err
	}
	if received > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", received))
	}
	req.Header.Set("Accept-Encoding", "identity")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		finalErr = err
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == 200 {
		received = 0
		os.Remove(destPath)
	} else if resp.StatusCode == 416 {
		if received == art.Bytes {
			// Already fully downloaded
		} else {
			finalErr = fmt.Errorf("unexpected 416 range not satisfiable")
			return finalErr
		}
	} else if resp.StatusCode == 206 {
		cr := resp.Header.Get("Content-Range")
		var start, end, total int64
		if n, parseErr := fmt.Sscanf(cr, "bytes %d-%d/%d", &start, &end, &total); parseErr != nil || n != 3 || start != received || end < start || end != art.Bytes-1 || total != art.Bytes || cr != fmt.Sprintf("bytes %d-%d/%d", start, end, total) {
			finalErr = errors.New("Content-Range mismatch")
			return finalErr
		}
	} else {
		finalErr = fmt.Errorf("unexpected status code %d", resp.StatusCode)
		return finalErr
	}

	if received < art.Bytes {
		file, err := os.OpenFile(destPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
		if err != nil {
			finalErr = err
			return err
		}
		defer file.Close()

		limitReader := io.LimitReader(resp.Body, art.Bytes-received+1)
		buf := make([]byte, 32768)
		for {
			n, err := limitReader.Read(buf)
			if n > 0 {
				if _, werr := file.Write(buf[:n]); werr != nil {
					finalErr = werr
					return werr
				}
				received += int64(n)
				if received > art.Bytes {
					finalErr = errors.New("download exceeds expected size")
					return finalErr
				}
				a.mu.Lock()
				if p, ok := a.progress[id]; ok {
					p.Received = received
					a.progress[id] = p
				}
				a.mu.Unlock()
			}
			if err != nil {
				if err == io.EOF {
					break
				}
				finalErr = err
				return err
			}
		}
		if err := file.Sync(); err != nil {
			return err
		}
		if err := file.Close(); err != nil {
			return err
		}
	}

	if received != art.Bytes {
		finalErr = errors.New("size mismatch")
		return finalErr
	}

	hash := sha256.New()
	f, err := os.Open(destPath)
	if err != nil {
		finalErr = err
		return err
	}
	if _, err := io.Copy(hash, f); err != nil {
		f.Close()
		finalErr = err
		return err
	}
	f.Close()

	if hex.EncodeToString(hash.Sum(nil)) != art.SHA256 {
		os.Remove(destPath)
		finalErr = errors.New("hash mismatch")
		return finalErr
	}

	err = a.importAsset(cancelCtx, id, destPath)
	if err != nil {
		finalErr = err
	}
	return err
}

func (a *AssetManager) Import(ctx context.Context, id, path string) error {
	ctx, cancel := context.WithCancel(ctx)
	a.muCancel.Lock()
	if _, active := a.cancelMap[id]; active {
		a.muCancel.Unlock()
		cancel()
		return errors.New("asset operation already active")
	}
	a.cancelMap[id] = cancel
	a.muCancel.Unlock()
	defer func() { a.muCancel.Lock(); delete(a.cancelMap, id); a.muCancel.Unlock(); cancel() }()
	err := a.importAsset(ctx, id, path)
	if err != nil {
		a.mu.Lock()
		a.progress[id] = DownloadProgress{ID: id, State: "failed", Error: err.Error()}
		a.mu.Unlock()
	}
	return err
}
func (a *AssetManager) importAsset(ctx context.Context, id, path string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	art, ok := a.getArtifact(id)
	if !ok {
		return errors.New("artifact not found")
	}

	lstat, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !lstat.Mode().IsRegular() {
		return errors.New("source file is not a regular file")
	}
	if lstat.Size() != art.Bytes {
		return errors.New("source size mismatch")
	}

	stagingDir := filepath.Join(a.dataDir, "staging")
	_ = os.MkdirAll(stagingDir, 0700)

	srcFile, err := os.Open(path)
	if err != nil {
		return err
	}
	defer srcFile.Close()

	dstFile, err := os.CreateTemp(stagingDir, "asset-*.tmp")
	if err != nil {
		return err
	}
	tmpPath := dstFile.Name()
	defer func() {
		dstFile.Close()
		os.Remove(tmpPath)
	}()

	hash := sha256.New()
	multiWriter := io.MultiWriter(dstFile, hash)

	if art.Format == "gguf" || art.Format == "ggml" {
		magic := make([]byte, 4)
		n, err := io.ReadFull(srcFile, magic)
		if err != nil {
			return err
		}
		if art.Format == "gguf" && string(magic) != "GGUF" {
			return errors.New("invalid magic bytes for GGUF")
		}
		if art.Format == "ggml" && string(magic) != "lmgg" && string(magic) != "ggml" {
			return errors.New("invalid magic bytes for ggml/whisper")
		}
		if _, err := multiWriter.Write(magic[:n]); err != nil {
			return err
		}
	}

	copied, err := io.Copy(multiWriter, io.LimitReader(engineContextReader{ctx, srcFile}, art.Bytes+1))
	if err != nil {
		return err
	}

	if art.Format == "gguf" || art.Format == "ggml" {
		copied += 4
	}

	if copied != art.Bytes {
		return errors.New("size mismatch on import")
	}

	if hex.EncodeToString(hash.Sum(nil)) != art.SHA256 {
		return errors.New("hash mismatch on import")
	}

	if err := dstFile.Sync(); err != nil {
		return err
	}
	dstFile.Close()

	finalPath := ""

	createdBundle := false
	if artifactBundle(art) {
		folder := "runtimes"
		if art.Task == "tts" {
			folder = "models"
		}
		extractDir := filepath.Join(a.dataDir, folder, id+"-"+art.SHA256[:16])
		if err := os.MkdirAll(filepath.Dir(extractDir), 0700); err != nil {
			return err
		}
		tmpExtractDir, err := os.MkdirTemp(filepath.Dir(extractDir), "install-*")
		if err != nil {
			return err
		}
		defer os.RemoveAll(tmpExtractDir)
		if err := extractArtifactBundle(ctx, tmpPath, tmpExtractDir, art); err != nil {
			return err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if _, err := os.Lstat(extractDir); err == nil {
			verify := verifyArtifactBundle
			if art.TreeSHA256 != "" {
				verify = func(ctx context.Context, root string) error { return verifyPinnedArtifactBundle(ctx, root, art) }
			}
			if err := verify(ctx, extractDir); err != nil {
				return err
			}
			// The newly extracted tree came from this import's pinned archive.
			// An existing tree may not substitute a rewritten self-manifest.
			if err := compareBundleManifests(extractDir, tmpExtractDir); err != nil {
				return err
			}
		} else if !os.IsNotExist(err) {
			return err
		} else if err := os.Rename(tmpExtractDir, extractDir); err != nil {
			return err
		} else {
			createdBundle = true
		}
		finalPath = extractDir
	} else if art.Format == "gguf" || art.Format == "ggml" {
		modelsDir := filepath.Join(a.dataDir, "models")
		_ = os.MkdirAll(modelsDir, 0755)

		ext := ".gguf"
		if art.Format == "ggml" {
			ext = ".bin"
		}
		finalPath = filepath.Join(modelsDir, id+ext)

		if err := ctx.Err(); err != nil {
			return err
		}
		if err := replaceFile(tmpPath, finalPath); err != nil {
			return err
		}
	} else {
		return errors.New("unsupported format")
	}

	if a.persist != nil {
		if err := a.persist(InstalledAsset{
			ID:          id,
			Path:        finalPath,
			SHA256:      art.SHA256,
			Bytes:       art.Bytes,
			InstalledAt: time.Now().UTC(),
		}); err != nil {
			if createdBundle {
				_ = os.RemoveAll(finalPath)
			}
			return err
		}
	}

	a.mu.Lock()
	if p, ok := a.progress[id]; ok {
		p.State = "done"
		a.progress[id] = p
	}
	a.mu.Unlock()

	return nil
}

func (a *AssetManager) Progress() []DownloadProgress {
	a.mu.Lock()
	defer a.mu.Unlock()
	var res []DownloadProgress
	for _, p := range a.progress {
		res = append(res, p)
	}
	return res
}

func (a *AssetManager) Cancel(id string) {
	a.muCancel.Lock()
	if cancelFunc, ok := a.cancelMap[id]; ok {
		cancelFunc()
	}
	a.muCancel.Unlock()

	a.mu.Lock()
	defer a.mu.Unlock()
	if p, ok := a.progress[id]; ok && p.State == "downloading" {
		p.State = "cancelled"
		a.progress[id] = p
	}
}

func (a *AssetManager) Register(ctx context.Context, repo, revision, filename string, profile Artifact) (Artifact, error) {
	if repo == "" || filename == "" {
		return Artifact{}, errors.New("repo and filename are required")
	}
	if !isValidRepoName(repo) {
		return Artifact{}, errors.New("invalid repo format")
	}
	if revision == "" {
		revision = "main"
	}
	if !isValidZipName(filename) {
		return Artifact{}, errors.New("invalid filename")
	}
	if profile.Format != "gguf" && profile.Format != "ggml" {
		return Artifact{}, errors.New("invalid format in profile")
	}
	if profile.Task != "translation" && profile.Task != "stt" {
		return Artifact{}, errors.New("invalid task in profile")
	}
	if (profile.Task == "translation" && (profile.Format != "gguf" || (profile.Prompt != "chat" && profile.Prompt != "translategemma"))) || (profile.Task == "stt" && profile.Format != "ggml") {
		return Artifact{}, errors.New("unsupported model profile")
	}

	apiURL := fmt.Sprintf("https://huggingface.co/api/models/%s/revision/%s?blobs=true", repo, url.PathEscape(revision))
	req, err := http.NewRequestWithContext(ctx, "GET", apiURL, nil)
	if err != nil {
		return Artifact{}, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return Artifact{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return Artifact{}, fmt.Errorf("HF API returned %d", resp.StatusCode)
	}

	var data struct {
		SHA      string `json:"sha"`
		Siblings []struct {
			Rfilename string `json:"rfilename"`
			Lfs       *struct {
				Sha256 string `json:"sha256"`
				Size   int64  `json:"size"`
			} `json:"lfs"`
		} `json:"siblings"`
	}

	if err := json.NewDecoder(io.LimitReader(resp.Body, 8*1024*1024)).Decode(&data); err != nil {
		return Artifact{}, err
	}
	if !regexp.MustCompile(`^[a-f0-9]{40}$`).MatchString(data.SHA) {
		return Artifact{}, errors.New("immutable HF commit required")
	}

	var found bool
	for _, sib := range data.Siblings {
		if sib.Rfilename == filename && sib.Lfs != nil && sib.Lfs.Sha256 != "" {
			profile.SHA256 = sib.Lfs.Sha256
			profile.Bytes = sib.Lfs.Size
			if !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(profile.SHA256) || profile.Bytes <= 4 {
				return Artifact{}, errors.New("invalid HF file verification metadata")
			}
			profile.URL = fmt.Sprintf("https://huggingface.co/%s/resolve/%s/%s", repo, data.SHA, filename)
			found = true
			break
		}
	}

	if !found {
		return Artifact{}, errors.New("file not found in repo or missing LFS metadata")
	}

	identity := sha256.Sum256([]byte(repo + "/" + data.SHA + "/" + filename))
	profile.ID = "custom-" + hex.EncodeToString(identity[:10])
	profile.Source = repo + "@" + data.SHA
	profile.Experimental = true

	a.mu.Lock()
	// Check if already registered
	for _, existing := range a.registry {
		if existing.ID == profile.ID {
			a.mu.Unlock()
			return existing, nil
		}
	}
	a.customRegistry = append(a.customRegistry, profile)
	a.registry = append(a.registry, profile)
	if err := a.saveRegistry(); err != nil {
		a.customRegistry = a.customRegistry[:len(a.customRegistry)-1]
		a.registry = a.registry[:len(a.registry)-1]
		a.mu.Unlock()
		return Artifact{}, err
	}
	a.mu.Unlock()
	return profile, nil
}

func (a *AssetManager) Registry() []Artifact {
	a.mu.Lock()
	defer a.mu.Unlock()
	res := make([]Artifact, len(a.registry))
	copy(res, a.registry)
	return res
}
