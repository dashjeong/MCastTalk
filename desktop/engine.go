package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

type Engine struct {
	dataDir string

	lifeMu  sync.Mutex
	readyMu sync.RWMutex

	cfg        Config
	ready      EngineStatus
	generation uint64
	profile    Artifact
	tts        *ttsWorker

	llamaCmd   *exec.Cmd
	whisperCmd *exec.Cmd
	llamaURL   string
	whisperURL string
	llamaKey   string
	// Optional client is injected only by in-process protocol tests.
	onlineClient *http.Client
	// Bound once before serving. Operator consent is independent of model
	// startup snapshots, which may have been read before a settings change.
	onlineConfig func() OnlineConfig

	llamaDone   chan error
	whisperDone chan error

	cancel context.CancelFunc
}

func NewEngine(dataDir string) *Engine {
	return &Engine{dataDir: dataDir}
}

func (e *Engine) environmentIdle() bool {
	if !e.lifeMu.TryLock() {
		return false
	}
	defer e.lifeMu.Unlock()
	return e.llamaCmd == nil && e.whisperCmd == nil
}

func (e *Engine) Start(ctx context.Context, cfg Config, assets map[string]InstalledAsset) error {
	e.lifeMu.Lock()
	defer e.lifeMu.Unlock()

	// Stop previous instance safely
	e.stopUnlocked()

	e.readyMu.Lock()
	e.cfg = cfg
	e.ready = EngineStatus{Backend: cfg.Backend}
	e.generation++
	e.readyMu.Unlock()
	if cfg.TranslationModel == "" || cfg.STTModel == "" {
		return errors.New("번역 모델과 음성 인식 모델을 선택하세요")
	}
	for _, id := range []string{cfg.TranslationModel, cfg.STTModel} {
		a, ok := assets[id]
		if !ok || !engineManagedPath(e.dataDir, "models", a.Path) {
			return fmt.Errorf("관리 폴더의 검증된 모델이 필요합니다: %s", id)
		}
	}
	profiles := NewAssetManager(e.dataDir, nil).Registry()
	for _, id := range []string{cfg.TranslationModel, cfg.STTModel} {
		found := false
		for _, profile := range profiles {
			if profile.ID != id {
				continue
			}
			asset := assets[id]
			if profile.SHA256 != asset.SHA256 || profile.Bytes != asset.Bytes {
				return errors.New("installed model metadata differs from registered profile")
			}
			if id == cfg.TranslationModel {
				e.readyMu.Lock()
				e.profile = profile
				e.readyMu.Unlock()
			}
			found = true
		}
		if !found {
			return errors.New("registered model profile required")
		}
	}
	for id, asset := range assets {
		if strings.HasPrefix(id, "runtime-") && !engineManagedPath(e.dataDir, "runtimes", asset.Path) {
			return errors.New("runtime outside managed folder")
		}
	}

	engineCtx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel

	var startErrs []string

	if err := e.startBundledTTS(ctx, cfg, assets); err != nil {
		return e.finishStartup(ctx, cfg, []string{"로컬 음성: " + err.Error()})
	}

	if cfg.TranslationModel != "" {
		if err := e.startLlama(ctx, engineCtx, cfg, assets); err != nil {
			startErrs = append(startErrs, "llama-server: "+err.Error())
		}
	}

	if cfg.STTModel != "" {
		if err := e.startWhisper(ctx, engineCtx, cfg, assets); err != nil {
			startErrs = append(startErrs, "whisper-server: "+err.Error())
		}
	}

	return e.finishStartup(ctx, cfg, startErrs)
}

// The caller holds lifeMu. A failed startup is never a partial success, even
// when an earlier component published its own readiness before a later failure.
func (e *Engine) finishStartup(ctx context.Context, cfg Config, startErrs []string) error {
	if err := ctx.Err(); err != nil {
		startErrs = append(startErrs, err.Error())
	}
	if len(startErrs) == 0 {
		e.readyMu.RLock()
		ready := e.ready.TranslationReady && e.ready.STTReady && e.ready.TTSReady
		e.readyMu.RUnlock()
		if ready {
			return nil
		}
		startErrs = append(startErrs, "모델 준비 시험을 완료하지 못했습니다")
	}
	message := strings.Join(startErrs, "; ")
	e.readyMu.RLock()
	checks := append([]setupCheck(nil), e.ready.StartupChecks...)
	e.readyMu.RUnlock()
	e.stopUnlocked()
	e.readyMu.Lock()
	e.ready.Error = message
	e.ready.Backend = cfg.Backend
	e.ready.StartupChecks = checks
	e.readyMu.Unlock()
	return errors.New(message)
}

func (e *Engine) startLlama(ctx, engineCtx context.Context, cfg Config, assets map[string]InstalledAsset) error {
	asset, ok := assets[cfg.TranslationModel]
	if !ok {
		return errors.New("translation model not installed")
	}

	if err := e.startupStep("번역 모델 해시 확인", "", func() error {
		return verifyStartupModel(ctx, asset, []byte{0x47, 0x47, 0x55, 0x46})
	}); err != nil {
		return fmt.Errorf("model verification failed: %v", err)
	}

	backend := cfg.Backend
	runtimeID := "runtime-llama-" + backend
	if backend == "openvino-npu" {
		runtimeID = "runtime-llama-openvino"
	}
	rtAsset, ok := assets[runtimeID]

	if !ok || verifyRuntimeAsset(ctx, runtimeID, rtAsset) != nil {
		if backend != "cpu" {
			// fallback
			backend = "cpu"
			runtimeID = "runtime-llama-cpu"
			rtAsset, ok = assets[runtimeID]
			if !ok || verifyRuntimeAsset(ctx, runtimeID, rtAsset) != nil {
				return errors.New("CPU fallback runtime not verified")
			}
			e.readyMu.Lock()
			e.ready.Backend = "cpu"
			e.ready.Error += " CPU fallback used;"
			e.readyMu.Unlock()
		} else {
			return errors.New("runtime not verified")
		}
	}

	exePath, err := findExecutable(rtAsset.Path, "llama-server")
	if err != nil {
		return err
	}

	port, err := getFreePort()
	if err != nil {
		return err
	}
	e.readyMu.Lock()
	e.llamaURL = fmt.Sprintf("http://127.0.0.1:%d", port)
	e.llamaKey = newID() + newID()
	url, key, generation := e.llamaURL, e.llamaKey, e.generation
	e.readyMu.Unlock()

	contextPerSlot := e.profile.Context
	if contextPerSlot < 2048 {
		contextPerSlot = 2048
	}
	if contextPerSlot > 4096 {
		contextPerSlot = 4096
	}
	args := []string{"-m", asset.Path, "--port", fmt.Sprintf("%d", port), "--host", "127.0.0.1", "--api-key", key, "--jinja", "--parallel", "2", "--ctx-size", fmt.Sprint(contextPerSlot * 2)}

	if backend == "cpu" {
		// CPU readiness still requires the authenticated translation warmup below.
		// Avoid an additional native empty-input warmup before health becomes ready.
		args = append(args, "--n-gpu-layers", "0", "--no-warmup")
	} else if backend == "cuda" || backend == "vulkan" {
		args = append(args, "--n-gpu-layers", "99")
	}

	cmd := exec.CommandContext(engineCtx, exePath, args...)
	cmd.Dir = filepath.Dir(exePath)
	cmd.Env = os.Environ()
	cmd.Env = append(cmd.Env, "PATH="+filepath.Dir(exePath)+string(os.PathListSeparator)+os.Getenv("PATH"))
	if backend == "cuda" {
		cudart, ok := assets["runtime-llama-cudart"]
		if !ok || verifyRuntimeAsset(ctx, "runtime-llama-cudart", cudart) != nil {
			return errors.New("검증된 CUDA DLL 런타임을 함께 설치하세요")
		}
		dirs := []string{filepath.Dir(exePath)}
		err := filepath.Walk(cudart.Path, func(p string, st os.FileInfo, err error) error {
			if err != nil {
				return err
			}
			if !st.IsDir() && strings.HasSuffix(strings.ToLower(p), ".dll") {
				d := filepath.Dir(p)
				found := false
				for _, old := range dirs {
					if old == d {
						found = true
					}
				}
				if !found {
					dirs = append(dirs, d)
				}
			}
			return nil
		})
		if err != nil {
			return err
		}
		cmd.Env = append(cmd.Env, "PATH="+strings.Join(dirs, string(os.PathListSeparator))+string(os.PathListSeparator)+os.Getenv("PATH"))
	}

	if backend == "openvino-npu" {
		cmd.Env = append(cmd.Env, "GGML_OPENVINO_DEVICE=NPU", "GGML_OPENVINO_STATEFUL_EXECUTION=0")
	}

	if err := cmd.Start(); err != nil {
		return err
	}

	e.llamaCmd = cmd
	e.llamaDone = make(chan error, 1)
	done := e.llamaDone
	go func() {
		err := cmd.Wait()
		e.readyMu.Lock()
		if e.generation == generation {
			e.ready.TranslationReady = false
			e.ready.Error = "번역 프로세스가 종료되었습니다"
		}
		done <- err
		close(done)
		e.readyMu.Unlock()
	}()

	if err := e.startupStep("번역 모델 로드", "", func() error {
		return waitForProcessHealth(ctx, url+"/health", key, done)
	}); err != nil {
		return fmt.Errorf("llama-server 준비 확인 실패: %w", err)
	}
	select {
	case <-done:
		return fmt.Errorf("llama-server 준비 확인 실패: %w", errProcessExitedBeforeHealth)
	default:
	}

	e.readyMu.RLock()
	prompt := e.profile.Prompt
	e.readyMu.RUnlock()
	return e.startupStep("번역 모델 첫 추론", "", func() error {
		return e.warmLlama(ctx, url, key, cfg.TranslationModel, prompt, generation, done)
	})
}

// This uses the same local request protocol as TranslatePrivate without
// publishing public readiness or allowing an online fallback during warmup.
func (e *Engine) warmLlama(ctx context.Context, url, key, modelName, prompt string, generation uint64, done <-chan error) error {
	warmCtx, warmCancel := context.WithTimeout(ctx, 30*time.Second)
	defer warmCancel()
	if _, err := translateLocalRequest(warmCtx, url, key, modelName, prompt, "Hello.", "en", "ko", "", nil); err != nil {
		return fmt.Errorf("번역 준비 시험 실패: %w", err)
	}
	e.readyMu.Lock()
	defer e.readyMu.Unlock()
	if err := warmCtx.Err(); err != nil {
		return err
	}
	if e.generation != generation {
		return errors.New("번역 엔진 세대가 변경되었습니다")
	}
	// cmd.Wait closes done under this same lock after clearing readiness, so
	// successful warmup cannot overwrite an already-observed process exit.
	select {
	case <-done:
		return errProcessExitedBeforeHealth
	default:
	}
	e.ready.TranslationReady = true
	return nil
}

func (e *Engine) startWhisper(ctx, engineCtx context.Context, cfg Config, assets map[string]InstalledAsset) error {
	asset, ok := assets[cfg.STTModel]
	if !ok {
		return errors.New("STT model not installed")
	}

	if err := e.startupStep("음성 인식 모델 해시 확인", "", func() error {
		return verifyStartupModel(ctx, asset, []byte{'l', 'm', 'g', 'g'})
	}); err != nil {
		return fmt.Errorf("model verification failed: %v", err)
	}

	backend := cfg.Backend
	runtimeID := "runtime-whisper-" + backend
	if backend == "openvino-npu" {
		backend = "cpu"
		runtimeID = "runtime-whisper-cpu"
	}
	rtAsset, ok := assets[runtimeID]
	if !ok || verifyRuntimeAsset(ctx, runtimeID, rtAsset) != nil {
		if backend != "cpu" {
			backend = "cpu"
			runtimeID = "runtime-whisper-cpu"
			rtAsset, ok = assets[runtimeID]
			if !ok || verifyRuntimeAsset(ctx, runtimeID, rtAsset) != nil {
				return errors.New("CPU fallback runtime not verified")
			}
		} else {
			return errors.New("runtime not verified")
		}
	}

	exePath, err := findExecutable(rtAsset.Path, "whisper-server")
	if err != nil {
		return err
	}

	port, err := getFreePort()
	if err != nil {
		return err
	}
	e.readyMu.Lock()
	// whisper.cpp has no bearer-key flag. Its verified --request-path option
	// protects inference/load/health with an unguessable, per-start capability.
	// Keep it private; local same-user process access is outside this boundary.
	requestPath := "/" + newID() + newID()
	e.whisperURL = fmt.Sprintf("http://127.0.0.1:%d%s", port, requestPath)
	url, generation := e.whisperURL, e.generation
	e.readyMu.Unlock()

	args := []string{"-m", asset.Path, "--port", fmt.Sprintf("%d", port), "--host", "127.0.0.1", "--request-path", requestPath}
	if backend == "cpu" {
		args = append(args, "--no-gpu")
	}

	cmd := exec.CommandContext(engineCtx, exePath, args...)
	cmd.Dir = filepath.Dir(exePath)
	cmd.Env = os.Environ()
	cmd.Env = append(cmd.Env, "PATH="+filepath.Dir(exePath)+string(os.PathListSeparator)+os.Getenv("PATH"))

	if err := cmd.Start(); err != nil {
		return err
	}

	e.whisperCmd = cmd
	e.whisperDone = make(chan error, 1)
	done := e.whisperDone
	go func() {
		err := cmd.Wait()
		e.readyMu.Lock()
		if e.generation == generation {
			e.ready.STTReady = false
			e.ready.Error = "음성 인식 프로세스가 종료되었습니다"
		}
		done <- err
		close(done)
		e.readyMu.Unlock()
	}()

	if err := e.startupStep("음성 인식 모델 로드", "", func() error {
		return waitForProcessHealth(ctx, url+"/health", "", done)
	}); err != nil {
		return fmt.Errorf("whisper-server 준비 확인 실패: %w", err)
	}

	// Warmup
	wavData := makeSilenceWAV()
	if err := e.startupStep("음성 인식 모델 첫 추론", "", func() error {
		warmCtx, cancel := context.WithTimeout(ctx, engineSTTWarmupLimit)
		defer cancel()
		_, err := transcribeRequest(warmCtx, url, wavData, "en")
		if warmCtx.Err() != nil {
			return warmCtx.Err()
		}
		if err != nil && err.Error() == "empty transcription" {
			return nil
		}
		return err
	}); err != nil {
		return fmt.Errorf("음성 인식 준비 시험 실패: %w", err)
	}
	e.readyMu.Lock()
	defer e.readyMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	if e.generation != generation {
		return errors.New("음성 인식 엔진 세대가 변경되었습니다")
	}
	select {
	case <-done:
		return fmt.Errorf("whisper-server 준비 확인 실패: %w", errProcessExitedBeforeHealth)
	default:
	}
	e.ready.STTReady = true
	return nil
}

func getFreePort() (int, error) {
	addr, err := net.ResolveTCPAddr("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	l, err := net.ListenTCP("tcp", addr)
	if err != nil {
		return 0, err
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return port, nil
}

func verifyModel(ctx context.Context, path, expectedSHA string, expectedBytes int64, magic []byte) error {
	if len(expectedSHA) != 64 || expectedBytes <= 0 {
		return errors.New("model verification metadata missing")
	}
	if st, err := os.Lstat(path); err != nil || !st.Mode().IsRegular() {
		return errors.New("regular managed file required")
	}
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return err
	}
	if stat.Size() != expectedBytes {
		return errors.New("size mismatch")
	}

	if len(magic) > 0 {
		m := make([]byte, len(magic))
		if _, err := io.ReadFull(f, m); err != nil {
			return err
		}
		if !bytes.Equal(m, magic) {
			return errors.New("magic mismatch")
		}
		f.Seek(0, io.SeekStart)
	}

	h := sha256.New()
	if _, err := io.Copy(h, engineContextReader{ctx, f}); err != nil {
		return err
	}
	if hex.EncodeToString(h.Sum(nil)) != expectedSHA {
		return errors.New("sha256 mismatch")
	}
	return nil
}

func verifyRuntimeAsset(ctx context.Context, id string, asset InstalledAsset) error {
	for _, profile := range Catalog() {
		if profile.ID == id && profile.Task == "runtime" && asset.ID == id && profile.SHA256 == asset.SHA256 && profile.Bytes == asset.Bytes {
			return verifyPinnedArtifactBundle(ctx, asset.Path, profile)
		}
	}
	return errors.New("runtime metadata differs from trusted catalogue profile")
}

// Structural validation is kept for explicit archive fixtures. Executable
// startup requires verifyRuntimeAsset and its immutable source tree pin.
func verifyRuntime(ctx context.Context, dir string) error {
	manifestPath := filepath.Join(dir, "installed-manifest.json")
	b, err := os.ReadFile(manifestPath)
	if err != nil {
		return fmt.Errorf("runtime manifest required: %w", err)
	}
	if len(b) > 2*1024*1024 {
		return errors.New("runtime manifest too large")
	}
	var manifest struct {
		Files []struct {
			Path   string `json:"path"`
			SHA256 string `json:"sha256"`
			Bytes  int64  `json:"bytes"`
		} `json:"files"`
	}
	if err := json.Unmarshal(b, &manifest); err != nil {
		return err
	}
	if len(manifest.Files) == 0 || len(manifest.Files) > 4096 {
		return errors.New("runtime manifest files missing or excessive")
	}
	known := map[string]bool{}
	for _, f := range manifest.Files {
		if f.Path == "" || strings.ContainsAny(f.Path, "\\:") || filepath.IsAbs(f.Path) || strings.Contains(f.Path, "..") {
			return errors.New("unsafe runtime manifest path")
		}
		p := filepath.Join(dir, f.Path)
		if !engineManagedPath(dir, "", p) || known[p] {
			return errors.New("unsafe or duplicate runtime file")
		}
		known[p] = true
		if err := verifyModel(ctx, p, f.SHA256, f.Bytes, nil); err != nil {
			return err
		}
	}
	return filepath.Walk(dir, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return errors.New("runtime symlink rejected")
		}
		if !info.IsDir() && p != manifestPath && !known[p] {
			return errors.New("unverified extra runtime file")
		}
		return nil
	})
}

type engineContextReader struct {
	context context.Context
	reader  io.Reader
}

func (r engineContextReader) Read(b []byte) (int, error) {
	if err := r.context.Err(); err != nil {
		return 0, err
	}
	return r.reader.Read(b)
}
func engineManagedPath(base, folder, path string) bool {
	root, err := filepath.Abs(filepath.Join(base, folder))
	if err != nil {
		return false
	}
	p, err := filepath.Abs(path)
	if err != nil {
		return false
	}
	rel, err := filepath.Rel(root, p)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) || filepath.IsAbs(rel) {
		return false
	}
	for cur := p; cur != root; cur = filepath.Dir(cur) {
		st, e := os.Lstat(cur)
		if e != nil || st.Mode()&os.ModeSymlink != 0 {
			return false
		}
	}
	st, err := os.Lstat(root)
	return err == nil && st.IsDir() && st.Mode()&os.ModeSymlink == 0
}

func findExecutable(dir, base string) (string, error) {
	if runtime.GOOS == "windows" {
		base += ".exe"
	}
	var exes []string
	filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() && info.Name() == base {
			if info.Mode()&os.ModeSymlink == 0 {
				exes = append(exes, path)
			}
		}
		return nil
	})
	if len(exes) == 0 {
		return "", errors.New("executable not found")
	}
	if len(exes) > 1 {
		return "", errors.New("multiple executables found")
	}
	return exes[0], nil
}

func (e *Engine) stopUnlocked() {
	e.readyMu.Lock()
	tts := e.tts
	e.tts = nil
	e.ready.TTSReady = false
	e.readyMu.Unlock()
	if tts != nil {
		tts.Close()
	}
	if e.cancel != nil {
		e.cancel()
		e.cancel = nil
	}
	if e.llamaCmd != nil && e.llamaCmd.Process != nil {
		_ = e.llamaCmd.Process.Kill()
		<-e.llamaDone
		e.llamaCmd = nil
	}
	if e.whisperCmd != nil && e.whisperCmd.Process != nil {
		_ = e.whisperCmd.Process.Kill()
		<-e.whisperDone
		e.whisperCmd = nil
	}
	e.readyMu.Lock()
	e.ready = EngineStatus{}
	e.readyMu.Unlock()
}

func (e *Engine) Stop() {
	e.lifeMu.Lock()
	defer e.lifeMu.Unlock()
	e.stopUnlocked()
}

func (e *Engine) Ready() EngineStatus {
	e.readyMu.RLock()
	defer e.readyMu.RUnlock()
	status := e.ready
	status.StartupChecks = append([]setupCheck(nil), status.StartupChecks...)
	if status.StartupStage != "" && !status.StartupStartedAt.IsZero() {
		status.StartupMillis = time.Since(status.StartupStartedAt).Milliseconds()
	}
	if status.VoiceStartupPhase != "" && !status.VoiceStartupStartedAt.IsZero() {
		status.VoiceStartupMillis = time.Since(status.VoiceStartupStartedAt).Milliseconds()
	}
	return status
}

func (e *Engine) Translate(ctx context.Context, text, source, target, contextText string, glossary []GlossaryTerm) (string, error) {
	cfg := e.currentOnlineConfig()
	if cfg.Endpoint != "" && cfg.Consent {
		return e.TranslateOnline(ctx, text, source, target, contextText, glossary)
	}
	return e.TranslatePrivate(ctx, text, source, target, contextText, glossary)
}

func (e *Engine) TranslateOnline(ctx context.Context, text, source, target, recent string, terms []GlossaryTerm) (string, error) {
	cfg := e.currentOnlineConfig()
	return translateOnlineRequest(ctx, cfg, text, source, target, recent, selectOnlineGlossary(text, target, terms), e.onlineClient)
}

func (e *Engine) currentOnlineConfig() OnlineConfig {
	if e.onlineConfig != nil {
		return e.onlineConfig()
	}
	e.readyMu.RLock()
	cfg := e.cfg.Online
	e.readyMu.RUnlock()
	return cfg
}

// Update the fallback configuration for engines without an app callback.
// Production treats app.config().Online as authoritative; the settings handler
// persists and updates that configuration before calling this optional setter.
func (e *Engine) SetOnlineConfig(cfg OnlineConfig) {
	e.readyMu.Lock()
	e.cfg.Online = cfg
	e.readyMu.Unlock()
}

func (e *Engine) TranslatePrivate(ctx context.Context, text, source, target, recent string, terms []GlossaryTerm) (string, error) {
	e.readyMu.RLock()
	url := e.llamaURL
	key := e.llamaKey
	ready := e.ready.TranslationReady
	modelName := e.cfg.TranslationModel
	prompt := e.profile.Prompt
	e.readyMu.RUnlock()

	if !ready || url == "" {
		return "", errors.New("local translation engine not ready")
	}
	return translateLocalRequest(ctx, url, key, modelName, prompt, text, source, target, recent, terms)
}

func translateLocalRequest(ctx context.Context, url, key, modelName, prompt, text, source, target, recent string, terms []GlossaryTerm) (string, error) {
	if source == target {
		return text, nil
	}
	if len(text) > 8192 {
		return "", errors.New("input text too large")
	}

	var reqBody map[string]interface{}

	if prompt == "translategemma" || (prompt == "" && strings.Contains(strings.ToLower(modelName), "translategemma")) {
		contentObj := map[string]interface{}{
			"type":             "text",
			"source_lang_code": source,
			"target_lang_code": target,
			"text":             text,
		}
		// real ARRAY not jsonstring
		reqBody = map[string]interface{}{
			"messages": []map[string]interface{}{
				{"role": "user", "content": []interface{}{contentObj}},
			},
			"max_tokens":  1024,
			"temperature": 0.0,
		}
	} else {
		// Generic
		sysPrompt := fmt.Sprintf("You are a professional translator. Translate from %s to %s. Preserve formatting, numbers, units, and negations. Output only the translation.", source, target)

		userData := map[string]interface{}{
			"source": source,
			"target": target,
			"text":   text,
		}
		if recent != "" {
			if len(recent) > 4096 {
				recent = recent[len(recent)-4096:]
			}
			userData["context"] = recent
		}
		if len(terms) > 0 {
			if len(terms) > 100 {
				terms = terms[:100]
			}
			userData["glossary"] = terms
		}
		userDataStr, _ := json.Marshal(userData)

		reqBody = map[string]interface{}{
			"messages": []map[string]interface{}{
				{"role": "system", "content": sysPrompt},
				{"role": "user", "content": string(userDataStr)},
			},
			"max_tokens":  1024,
			"temperature": 0.0,
			"chat_template_kwargs": map[string]interface{}{
				"enable_thinking": false,
			},
		}
	}

	b, _ := json.Marshal(reqBody)
	req, err := http.NewRequestWithContext(ctx, "POST", url+"/v1/chat/completions", bytes.NewReader(b))
	if err != nil {
		return "", errors.New("로컬 번역 요청을 만들지 못했습니다")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+key)

	client := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		return "", errors.New("로컬 번역 서버에 연결하지 못했습니다")
	}
	defer resp.Body.Close()

	lr := io.LimitReader(resp.Body, 1024*1024)

	if resp.StatusCode != 200 {
		return "", fmt.Errorf("llama-server returned %d", resp.StatusCode)
	}

	var res struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
	}
	if err := json.NewDecoder(lr).Decode(&res); err != nil {
		return "", err
	}

	if len(res.Choices) == 0 {
		return "", errors.New("empty choices from translation")
	}

	out := strings.TrimSpace(res.Choices[0].Message.Content)
	if out == "" || res.Choices[0].FinishReason == "length" {
		return "", errors.New("empty or truncated translation result")
	}

	return out, nil
}

func (e *Engine) Transcribe(ctx context.Context, wav []byte, lang string) (string, error) {
	return e.TranscribePrivate(ctx, wav, lang)
}

func (e *Engine) TranscribePrivate(ctx context.Context, wav []byte, lang string) (string, error) {
	e.readyMu.RLock()
	url := e.whisperURL
	ready := e.ready.STTReady
	e.readyMu.RUnlock()

	if !ready || url == "" {
		return "", errors.New("local transcription engine not ready")
	}

	if err := validateWAV(wav); err != nil {
		return "", err
	}

	return transcribeRequest(ctx, url, wav, lang)
}

func transcribeRequest(ctx context.Context, url string, wav []byte, lang string) (string, error) {
	var b bytes.Buffer
	w := multipart.NewWriter(&b)
	fw, err := w.CreateFormFile("file", "audio.wav")
	if err != nil {
		return "", err
	}
	fw.Write(wav)
	_ = w.WriteField("response_format", "json")
	_ = w.WriteField("temperature", "0")
	if lang != "" {
		_ = w.WriteField("language", lang)
	}
	w.Close()

	req, err := http.NewRequestWithContext(ctx, "POST", url+"/inference", &b)
	if err != nil {
		return "", errors.New("음성 인식 요청을 만들지 못했습니다")
	}
	req.Header.Set("Content-Type", w.FormDataContentType())

	client := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		return "", errors.New("로컬 음성 인식 서버에 연결하지 못했습니다")
	}
	defer resp.Body.Close()

	lr := io.LimitReader(resp.Body, 64*1024)

	if resp.StatusCode != 200 {
		return "", fmt.Errorf("whisper-server returned %d", resp.StatusCode)
	}

	var res struct {
		Text string `json:"text"`
	}
	if err := json.NewDecoder(lr).Decode(&res); err != nil {
		return "", err
	}

	out := strings.TrimSpace(res.Text)
	if out == "" {
		return "", errors.New("empty transcription")
	}

	return out, nil
}

func makeSilenceWAV() []byte {
	return wavBytes(make([]byte, 32000))
}

func (e *Engine) Synthesize(ctx context.Context, text, lang string) ([]byte, error) {
	return e.SynthesizePrivate(ctx, text, lang)
}

func (e *Engine) SynthesizePrivate(ctx context.Context, text, language string) ([]byte, error) {
	e.readyMu.RLock()
	tts, ready := e.tts, e.ready.TTSReady
	e.readyMu.RUnlock()
	if tts == nil || !ready {
		return nil, errors.New("로컬 음성 모델이 준비되지 않았습니다")
	}
	pcm, err := tts.Synthesize(ctx, text, language)
	if err != nil {
		select {
		case <-tts.done:
			e.readyMu.Lock()
			if e.tts == tts {
				e.ready.TTSReady = false
				e.ready.Error = "로컬 음성 작업 프로세스가 종료되었습니다. 엔진을 다시 시작하세요"
			}
			e.readyMu.Unlock()
		default:
		}
	}
	return pcm, err
}

func (e *Engine) startBundledTTS(ctx context.Context, cfg Config, assets map[string]InstalledAsset) error {
	init := nativeTTSInit{DataDir: e.dataDir, Threads: 2}
	languages := append([]string{cfg.SourceLanguage}, cfg.TargetLanguages...)
	err := e.startupStep("음성 모델·런타임 해시 확인", "", func() error {
		for _, id := range bundledTTSAssetIDs(languages) {
			asset, ok := assets[id]
			folder := "models"
			if id == "runtime-sherpa-tts" {
				folder = "runtimes"
			}
			if !ok || !engineManagedPath(e.dataDir, folder, asset.Path) {
				return fmt.Errorf("관리 경로의 음성 패키지가 필요합니다: %s", id)
			}
			var profile Artifact
			for _, p := range Catalog() {
				if p.ID == id {
					profile = p
					break
				}
			}
			if profile.ID == "" || profile.SHA256 != asset.SHA256 || profile.Bytes != asset.Bytes || verifyPinnedArtifactBundle(ctx, asset.Path, profile) != nil {
				return errors.New("로컬 음성 패키지 검증 실패")
			}
			switch id {
			case "runtime-sherpa-tts":
				init.RuntimeDir = asset.Path
			case "tts-supertonic3":
				init.SupertonicDir = asset.Path
			case "tts-kokoro-zh":
				init.KokoroDir = asset.Path
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	if init.RuntimeDir == "" {
		return errors.New("선택한 언어에 사용할 로컬 음성 모델이 없습니다")
	}
	var worker *ttsWorker
	err = e.startupStep("언어별 음성 모델 로드", "", func() error {
		var err error
		worker, err = e.startTTSWorkerTracked(ctx, init)
		return err
	})
	if err != nil {
		return err
	}
	warmed := map[string]bool{}
	for _, language := range languages {
		language = normalizeSpeechLanguage(language)
		if warmed[language] {
			continue
		}
		warmed[language] = true
		warmCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		err = e.startupStep("언어별 음성 첫 합성", language, func() error {
			_, err := worker.Synthesize(warmCtx, setupSample(language), language)
			return err
		})
		cancel()
		if err != nil {
			worker.Close()
			return fmt.Errorf("%s 로컬 음성 준비 시험 실패: %w", language, err)
		}
	}
	e.readyMu.Lock()
	e.tts = worker
	e.ready.TTSReady = true
	e.readyMu.Unlock()
	return nil
}
