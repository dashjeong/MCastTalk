package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

type setupPlan struct {
	EnvironmentFingerprint string                  `json:"environmentFingerprint"`
	Fingerprint            string                  `json:"fingerprint"`
	Artifacts              []Artifact              `json:"artifacts"`
	Missing                []Artifact              `json:"missing"`
	DownloadBytes          int64                   `json:"downloadBytes"`
	RequiredDiskBytes      int64                   `json:"requiredDiskBytes"`
	RequiredRAMGB          int                     `json:"requiredRAMGB"`
	Compatible             bool                    `json:"compatible"`
	PreparationCompatible  bool                    `json:"preparationCompatible"`
	RuntimeCompatible      bool                    `json:"runtimeCompatible"`
	RuntimeIssues          []string                `json:"runtimeIssues"`
	Warnings               []string                `json:"warnings"`
	SystemDependencies     []setupSystemDependency `json:"systemDependencies"`
	SpeechLanguages        SpeechLanguageStatus    `json:"speechLanguages"`
}
type setupSystemDependency struct {
	Profile         SystemDependency       `json:"profile"`
	Status          SystemDependencyStatus `json:"status"`
	Cached          bool                   `json:"cached"`
	InstallerCached bool                   `json:"installerCached"`
}
type setupCheck struct {
	Stage    string `json:"stage"`
	Language string `json:"language,omitempty"`
	Passed   bool   `json:"passed"`
	Millis   int64  `json:"millis"`
	Error    string `json:"error,omitempty"`
}
type setupOperation struct {
	EnvironmentFingerprint        string                   `json:"environmentFingerprint"`
	ID                            string                   `json:"id"`
	State                         string                   `json:"state"`
	Phase                         string                   `json:"phase"`
	ArtifactIDs                   []string                 `json:"artifactIDs"`
	CurrentID                     string                   `json:"currentID,omitempty"`
	Completed                     int                      `json:"completed"`
	Error                         string                   `json:"error,omitempty"`
	Checks                        []setupCheck             `json:"checks"`
	StartedAt                     time.Time                `json:"startedAt"`
	FinishedAt                    time.Time                `json:"finishedAt,omitempty"`
	FunctionalVerified            bool                     `json:"functionalVerified"`
	PreparationOnly               bool                     `json:"preparationOnly"`
	StartupChecks                 []setupCheck             `json:"startupChecks"`
	QualityVerified               bool                     `json:"qualityVerified"`
	LiveSLAVerified               bool                     `json:"liveSLAVerified"`
	DependencyProgress            DownloadProgress         `json:"dependencyProgress"`
	DependencyResults             []SystemDependencyResult `json:"dependencyResults"`
	RestartRequired               bool                     `json:"restartRequired"`
	RestartCheckRequired          bool                     `json:"restartCheckRequired"`
	SystemInstallationMayContinue bool                     `json:"systemInstallationMayContinue"`
}
type setupController struct {
	mu        sync.Mutex
	operation setupOperation
	cancel    context.CancelFunc
	validate  func(Artifact) error // unexported fixture hook; nil uses trusted production profiles
}

func (a *App) setupController() *setupController {
	a.setupStateMu.Lock()
	defer a.setupStateMu.Unlock()
	if a.setup == nil {
		a.setup = &setupController{}
		_ = a.store.get("settings", "setup-operation", &a.setup.operation)
		if a.setup.operation.State == "running" {
			a.setup.operation.State = "interrupted"
			a.setup.operation.Error = "구성 중 프로그램이 중단되었습니다. 설치한 파일은 재사용하고 구성·구동 검증을 다시 시작하세요"
			_ = a.store.put("settings", "setup-operation", a.setup.operation)
		}
	}
	return a.setup
}
func setupInstalled(base string, a Artifact, in InstalledAsset) bool {
	if in.ID != a.ID || in.SHA256 != a.SHA256 || in.Bytes != a.Bytes {
		return false
	}
	folder := "models"
	if a.Task == "runtime" {
		folder = "runtimes"
	}
	if !engineManagedPath(base, folder, in.Path) {
		return false
	}
	st, e := os.Lstat(in.Path)
	if e != nil {
		return false
	}
	if artifactBundle(a) {
		if !st.IsDir() {
			return false
		}
		return portableLocalPath(filepath.Join(in.Path, "installed-manifest.json"), false) == nil
	}
	return st.Mode().IsRegular() && st.Size() == a.Bytes
}
func (a *App) makeSetupPlan(cfg Config) (setupPlan, error) {
	c := a.setupController()
	plan := setupPlan{Missing: []Artifact{}, Warnings: []string{"다운로드·해시 검증 후 실제 로컬 엔진과 언어별 음성을 시험합니다. 통번역 정확도·실시간 지연·동시 수용량은 별도 검증입니다.", "선택 언어의 로컬 음성 모델도 함께 구성합니다. GPU/NPU 드라이버는 대상 Windows에서 별도로 준비해야 합니다."}}
	registry := map[string]Artifact{}
	for _, art := range a.assets.Registry() {
		registry[art.ID] = art
	}
	ids := []string{cfg.TranslationModel, cfg.STTModel, "runtime-llama-cpu", "runtime-whisper-cpu"}
	if c.validate == nil {
		ids = append(ids, bundledTTSAssetIDs(append([]string{cfg.SourceLanguage}, cfg.TargetLanguages...))...)
	}
	switch cfg.Backend {
	case "cpu":
	case "cuda":
		ids = append(ids, "runtime-llama-cuda", "runtime-llama-cudart")
		if _, ok := registry["runtime-whisper-cuda"]; ok {
			ids = append(ids, "runtime-whisper-cuda")
		}
	case "vulkan":
		ids = append(ids, "runtime-llama-vulkan")
		if _, ok := registry["runtime-whisper-vulkan"]; ok {
			ids = append(ids, "runtime-whisper-vulkan")
		}
	case "openvino-npu":
		ids = append(ids, "runtime-llama-openvino")
	default:
		return plan, errors.New("지원하지 않는 구동 방식")
	}
	installed := a.store.assets()
	seen := map[string]bool{}
	for _, id := range ids {
		if seen[id] {
			continue
		}
		seen[id] = true
		art, ok := registry[id]
		if !ok {
			return plan, fmt.Errorf("필요한 프로필이 없습니다: %s", id)
		}
		if id == cfg.TranslationModel && art.Task != "translation" || id == cfg.STTModel && art.Task != "stt" {
			return plan, errors.New("선택한 모델의 작업 형식이 다릅니다")
		}
		check := c.validate
		if check == nil {
			check = portableProfile
		}
		if e := check(art); e != nil {
			return plan, e
		}
		plan.Artifacts = append(plan.Artifacts, art)
		if art.Task != "runtime" {
			plan.RequiredRAMGB += art.RAMGB
		}
		if !setupInstalled(a.store.dir, art, installed[id]) {
			plan.Missing = append(plan.Missing, art)
			plan.DownloadBytes += art.Bytes
			plan.RequiredDiskBytes += art.Bytes * 2
			if artifactBundle(art) {
				plan.RequiredDiskBytes += 1 << 30
			}
		}
	}
	plan.RequiredRAMGB += 3
	plan.RequiredDiskBytes += portableDiskReserve
	a.diagMu.Lock()
	d := a.diagnostic
	a.diagMu.Unlock()
	plan.PreparationCompatible = d.Measured && d.OS == "windows" && d.Arch == "amd64" && d.FreeDiskGB*float64(1<<30) >= float64(plan.RequiredDiskBytes)
	plan.Compatible = plan.PreparationCompatible && d.AvailableRAMGB >= float64(plan.RequiredRAMGB)
	plan.RuntimeIssues = []string{}
	if d.Measured && d.AvailableRAMGB < float64(plan.RequiredRAMGB) {
		plan.RuntimeIssues = append(plan.RuntimeIssues, fmt.Sprintf("현재 여유 RAM %.1fGB, 선택한 구성의 기동 기준 %dGB입니다. 다른 앱을 닫거나 더 작은 음성 인식 모델을 선택하세요. 모델 파일은 지금 준비할 수 있습니다.", d.AvailableRAMGB, plan.RequiredRAMGB))
	}
	var systemProfiles []SystemDependency
	if runtime.GOOS == "windows" && c.validate == nil {
		plan.SpeechLanguages = bundledSpeechStatus(cfg, installed)
		if plan.SpeechLanguages.Error != "" {
			plan.Warnings = append(plan.Warnings, "언어별 음성 준비 상태를 확인하지 못했습니다: "+plan.SpeechLanguages.Error)
		} else if len(plan.SpeechLanguages.Missing) > 0 {
			plan.Warnings = append(plan.Warnings, "별도 로컬 음성 모델을 준비할 언어: "+strings.Join(plan.SpeechLanguages.Missing, ", ")+". 해당 모델과 음성 런타임을 함께 내려받습니다. OS 음성팩 없이 실제 합성·통번역 구동을 검증합니다.")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		states, checkErr := CheckSystemDependencies(ctx)
		cancel()
		if checkErr != nil {
			plan.Compatible = false
			plan.Warnings = append(plan.Warnings, "Windows 실행 기반 진단을 완료하지 못했습니다: "+checkErr.Error())
			plan.RuntimeIssues = append(plan.RuntimeIssues, "Windows 실행 기반 확인이 아직 끝나지 않았습니다. 파일을 먼저 준비할 수 있으며, 실제 구동 검증 전에 실행 기반을 다시 확인합니다.")
		}
		for _, profile := range SystemDependencies() {
			dep := setupSystemDependency{Profile: profile}
			for _, state := range states {
				if state.ID == profile.ID {
					dep.Status = state
				}
			}
			if checkErr != nil {
				// Platform compatibility is known from the measured Windows
				// diagnostic, but incomplete native checks never imply readiness.
				dep.Status.ID = profile.ID
				dep.Status.Supported = d.OS == "windows" && d.Arch == "amd64"
				dep.Status.Ready = false
				dep.Status.CheckComplete = false
			}
			if !dep.Status.Supported || !dep.Status.CheckComplete || dep.Status.InstallationInProgress {
				plan.Compatible = false
			}
			path, pathErr := SystemDependencyInstallerPath(a.store.dir, profile.ID)
			// A completed/expired native-check context must not make an
			// already verified installer disappear from the file plan.
			cacheCtx, cacheCancel := context.WithTimeout(context.Background(), 5*time.Second)
			dep.InstallerCached = pathErr == nil && portableLocalPath(path, false) == nil && verifyModel(cacheCtx, path, profile.SHA256, profile.Bytes, nil) == nil
			cacheCancel()
			dep.Cached = dep.InstallerCached
			if !dep.InstallerCached {
				plan.DownloadBytes += profile.Bytes
				plan.RequiredDiskBytes += profile.Bytes * 2
			}
			if !dep.Status.Ready {
				plan.RequiredDiskBytes += 512 << 20
				plan.Warnings = append(plan.Warnings, "파일 준비에서는 Microsoft VC++ 공식 설치 파일만 보관합니다. 실제 구동 검증 중 설치가 필요하면 Windows 관리자 승인(UAC)을 요청하며, 재부팅 필요·거절·취소는 완료로 판정하지 않습니다.")
			}
			systemProfiles = append(systemProfiles, profile)
			plan.SystemDependencies = append(plan.SystemDependencies, dep)
		}
		if d.FreeDiskGB*float64(1<<30) < float64(plan.RequiredDiskBytes) {
			plan.Compatible = false
			plan.PreparationCompatible = false
		}
	}
	plan.RuntimeCompatible = plan.Compatible
	if !plan.Compatible {
		plan.Warnings = append(plan.Warnings, fmt.Sprintf("Windows x64 실측 진단, 여유 RAM %dGB·디스크 %.2fGB를 확인하세요. 모델 선택을 바꾸거나 이미 구성한 환경을 가져올 수 있습니다.", plan.RequiredRAMGB, float64(plan.RequiredDiskBytes)/float64(1<<30)))
	}
	environmentData, _ := json.Marshal(struct {
		Config portableConfig
		Assets []Artifact
		System []SystemDependency
	}{portableConfig{cfg.SourceLanguage, cfg.TargetLanguages, cfg.TranslationModel, cfg.STTModel, cfg.MaxListeners, cfg.Backend}, plan.Artifacts, systemProfiles})
	envHash := sha256.Sum256(environmentData)
	plan.EnvironmentFingerprint = hex.EncodeToString(envHash[:])
	// Consent describes the immutable files/terms and which files are cached.
	// Native readiness can change during a cold check without changing that
	// file plan; it is checked separately before a full runtime operation.
	type dependencyConsent struct {
		Profile         SystemDependency `json:"profile"`
		InstallerCached bool             `json:"installerCached"`
	}
	consentDependencies := []dependencyConsent{}
	for _, dependency := range plan.SystemDependencies {
		consentDependencies = append(consentDependencies, dependencyConsent{dependency.Profile, dependency.InstallerCached})
	}
	fingerprintData, _ := json.Marshal(struct {
		Config  portableConfig
		Assets  []Artifact
		Missing []Artifact
		System  []dependencyConsent
		Speech  SpeechLanguageStatus
	}{portableConfig{cfg.SourceLanguage, cfg.TargetLanguages, cfg.TranslationModel, cfg.STTModel, cfg.MaxListeners, cfg.Backend}, plan.Artifacts, plan.Missing, consentDependencies, plan.SpeechLanguages})
	h := sha256.Sum256(fingerprintData)
	plan.Fingerprint = hex.EncodeToString(h[:])
	return plan, nil
}
func setupSample(language string) string {
	switch strings.Split(language, "-")[0] {
	case "ko":
		return "안녕하세요. 수업은 열 시에 시작합니다."
	case "ja":
		return "こんにちは。授業は十時に始まります。"
	case "zh":
		return "你好。课程十点开始。"
	case "es":
		return "Hola. La clase comienza a las diez."
	default:
		return "Hello. The class begins at ten."
	}
}
func (a *App) verifySetup(ctx context.Context, cfg Config, c *setupController) error {
	// Readiness must exercise the downloaded local translation model. Online
	// routing would otherwise allow a remote provider to mask a broken model.
	cfg.Online = OnlineConfig{}
	record := func(stage, lang string, start time.Time, e error) {
		c.mu.Lock()
		defer c.mu.Unlock()
		r := setupCheck{Stage: stage, Language: lang, Passed: e == nil, Millis: time.Since(start).Milliseconds()}
		if e != nil {
			r.Error = e.Error()
		}
		c.operation.Checks = append(c.operation.Checks, r)
	}
	if runtime.GOOS == "windows" && c.validate == nil {
		start := time.Now()
		status := bundledSpeechStatus(cfg, a.store.assets())
		var err error
		if status.Error != "" || !status.Supported {
			err = fmt.Errorf("언어별 로컬 음성 준비를 확인하지 못했습니다: %s", status.Error)
		} else if len(status.Missing) > 0 {
			err = fmt.Errorf("로컬 음성 모델이 준비되지 않았습니다: %s. 환경 일괄 구성에서 음성 모델·런타임을 받거나 검증된 환경을 가져오세요. 받은 모델 파일은 재사용합니다", strings.Join(status.Missing, ", "))
		}
		record("언어별 로컬 음성 파일 준비", "", start, err)
		if err != nil {
			return err
		}
	}
	start := time.Now()
	startCtx, cancel := context.WithTimeout(ctx, 180*time.Second)
	err := a.pipeline.engine.Start(startCtx, cfg, a.store.assets())
	cancel()
	c.mu.Lock()
	c.operation.StartupChecks = append([]setupCheck(nil), a.pipeline.engine.Ready().StartupChecks...)
	c.mu.Unlock()
	record("엔진 기동·모델 로드", cfg.Backend, start, err)
	if err != nil {
		return err
	}
	ready := a.pipeline.engine.Ready()
	if !ready.TTSReady {
		return errors.New("로컬 음성합성을 사용할 수 없습니다. 음성 모델·런타임을 준비하고 저장된 환경을 다시 검증하세요")
	}
	if !ready.TranslationReady || !ready.STTReady {
		return errors.New("번역·음성인식·음성합성 중 준비되지 않은 엔진이 있습니다")
	}
	stepCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	start = time.Now()
	pcm, err := a.pipeline.engine.Synthesize(stepCtx, setupSample(cfg.SourceLanguage), cfg.SourceLanguage)
	if err == nil && (len(pcm) < 3200 || len(pcm) > 16000*2*30 || len(pcm)%2 != 0) {
		err = errors.New("시험 음성의 PCM 형식·길이가 잘못되었습니다")
	}
	record("입력 언어 시험 음성", cfg.SourceLanguage, start, err)
	if err != nil {
		return err
	}
	start = time.Now()
	source, err := a.pipeline.engine.Transcribe(stepCtx, wavBytes(pcm), cfg.SourceLanguage)
	if err == nil && strings.TrimSpace(source) == "" {
		err = errors.New("시험 음성 전사 결과가 없습니다")
	}
	record("실제 WAV 음성인식", cfg.SourceLanguage, start, err)
	if err != nil {
		return err
	}
	langs := append([]string{cfg.SourceLanguage}, cfg.TargetLanguages...)
	seen := map[string]bool{}
	var issues []string
	for _, lang := range langs {
		if seen[lang] {
			continue
		}
		seen[lang] = true
		langCtx, langCancel := context.WithTimeout(ctx, 60*time.Second)
		start = time.Now()
		translated := source
		var e error
		if lang != cfg.SourceLanguage {
			translated, e = a.pipeline.engine.Translate(langCtx, source, cfg.SourceLanguage, lang, "", nil)
		}
		if e == nil && strings.TrimSpace(translated) == "" {
			e = errors.New("번역 결과가 없습니다")
		}
		record("시험 문장 번역", lang, start, e)
		if e == nil {
			start = time.Now()
			audio, voiceErr := a.pipeline.engine.Synthesize(langCtx, translated, lang)
			if voiceErr == nil && (len(audio) < 320 || len(audio)%2 != 0) {
				voiceErr = errors.New("합성 음성 출력이 없습니다")
			}
			record("대상 언어 음성합성", lang, start, voiceErr)
			e = voiceErr
			if e == nil && lang != cfg.SourceLanguage {
				start = time.Now()
				input, inputErr := a.pipeline.engine.Transcribe(langCtx, wavBytes(audio), lang)
				if inputErr == nil && strings.TrimSpace(input) == "" {
					inputErr = errors.New("대상 언어 시험 음성 전사 결과가 없습니다")
				}
				record("참여자 언어 WAV 음성인식", lang, start, inputErr)
				e = inputErr
				if e == nil {
					start = time.Now()
					reply, replyErr := a.pipeline.engine.Translate(langCtx, input, lang, cfg.SourceLanguage, "", nil)
					if replyErr == nil && strings.TrimSpace(reply) == "" {
						replyErr = errors.New("역방향 번역 결과가 없습니다")
					}
					record("참여자 → 입력 언어 번역", lang, start, replyErr)
					e = replyErr
				}
			}
		}
		langCancel()
		if e != nil {
			issues = append(issues, lang+": "+e.Error())
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
	}
	if len(issues) > 0 {
		return errors.New(strings.Join(issues, "; "))
	}
	return ctx.Err()
}
func (a *App) setupAPI(w http.ResponseWriter, r *http.Request, path string) bool {
	if !strings.HasPrefix(path, "/setup/") {
		return false
	}
	c := a.setupController()
	if r.Method == "GET" && path == "/setup/plan" {
		plan, e := a.makeSetupPlan(a.config())
		if e != nil {
			apiError(w, e)
		} else {
			jsonReply(w, 200, plan)
		}
		return true
	}
	if r.Method == "GET" && path == "/setup/status" {
		c.mu.Lock()
		op := c.operation
		op.Checks = append([]setupCheck(nil), op.Checks...)
		op.StartupChecks = append([]setupCheck(nil), op.StartupChecks...)
		op.DependencyResults = append([]SystemDependencyResult(nil), op.DependencyResults...)
		c.mu.Unlock()
		progress := a.assets.Progress()
		if op.DependencyProgress.ID != "" {
			progress = append(progress, op.DependencyProgress)
		}
		jsonReply(w, 200, map[string]any{"operation": op, "progress": progress, "engine": a.pipeline.engine.Ready()})
		return true
	}
	if r.Method == "POST" && path == "/setup/cancel" {
		c.mu.Lock()
		state := c.operation.State
		if c.cancel != nil {
			c.cancel()
			state = "cancel_requested"
		}
		c.mu.Unlock()
		jsonReply(w, 202, map[string]string{"state": state})
		return true
	}
	if r.Method != "POST" || path != "/setup/download" {
		http.NotFound(w, r)
		return true
	}
	var in struct {
		Fingerprint string `json:"fingerprint"`
		Consent     bool   `json:"consent"`
		PrepareOnly bool   `json:"prepareOnly"`
	}
	if e := readJSON(w, r, &in); e != nil {
		apiError(w, e)
		return true
	}
	if !in.Consent {
		apiError(w, errors.New("다운로드 용량과 모델·엔진 이용조건을 확인하고 선택하세요"))
		return true
	}
	if a.envRestart.Load() {
		apiError(w, errors.New("환경 가져오기 후 프로그램을 먼저 다시 실행하세요"))
		return true
	}
	cfg := a.config()
	plan, e := a.makeSetupPlan(cfg)
	if e != nil {
		apiError(w, e)
		return true
	}
	if plan.Fingerprint != in.Fingerprint {
		apiError(w, errors.New("모델 구성 또는 설치 상태가 바뀌었습니다. 구성을 다시 확인하세요"))
		return true
	}
	if !plan.Compatible && !(in.PrepareOnly && plan.PreparationCompatible) {
		apiError(w, errors.New("대상 PC의 진단·RAM·디스크 조건을 먼저 확인하세요"))
		return true
	}
	c.mu.Lock()
	if c.cancel != nil {
		c.mu.Unlock()
		jsonReply(w, 409, map[string]string{"error": "환경 구성 작업이 진행 중입니다"})
		return true
	}
	if !a.envMu.TryLock() {
		c.mu.Unlock()
		jsonReply(w, 409, map[string]string{"error": "현재 엔진·세션·접속·환경 이전을 완료하거나 종료한 뒤 구성하세요"})
		return true
	}
	cfg = a.config()
	plan, e = a.makeSetupPlan(cfg)
	if e != nil || plan.Fingerprint != in.Fingerprint || (!plan.Compatible && !(in.PrepareOnly && plan.PreparationCompatible)) {
		a.envMu.Unlock()
		c.mu.Unlock()
		apiError(w, errors.New("PC 조건 또는 구성이 바뀌었습니다. 진단·구성을 다시 확인하세요"))
		return true
	}
	free, diskErr := portableFreeDisk(a.store.dir)
	if diskErr != nil || free < plan.RequiredDiskBytes {
		a.envMu.Unlock()
		c.mu.Unlock()
		apiError(w, errors.New("현재 설치할 디스크 공간을 확인할 수 없거나 부족합니다"))
		return true
	}
	if e = a.importIdle(); e != nil {
		a.envMu.Unlock()
		c.mu.Unlock()
		apiError(w, e)
		return true
	}
	ctx, cancel := context.WithCancel(a.pipeline.ctx)
	c.cancel = cancel
	c.operation = setupOperation{ID: newID(), State: "running", Phase: "파일 구성", ArtifactIDs: []string{}, StartedAt: time.Now().UTC(), EnvironmentFingerprint: plan.EnvironmentFingerprint, PreparationOnly: in.PrepareOnly}
	for _, dep := range plan.SystemDependencies {
		if !dep.InstallerCached || (!in.PrepareOnly && !dep.Status.Ready) {
			c.operation.ArtifactIDs = append(c.operation.ArtifactIDs, dep.Profile.ID)
		}
	}
	for _, art := range plan.Missing {
		c.operation.ArtifactIDs = append(c.operation.ArtifactIDs, art.ID)
	}
	op := c.operation
	if e = a.store.put("settings", "setup-operation", op); e != nil {
		cancel()
		c.cancel = nil
		a.envMu.Unlock()
		c.mu.Unlock()
		apiError(w, e)
		return true
	}
	a.assetTasks.Add(1)
	c.mu.Unlock()
	go func() {
		defer a.envMu.Unlock()
		defer a.assetTasks.Add(-1)
		defer cancel()
		var err error
		for _, dep := range plan.SystemDependencies {
			if dep.InstallerCached && (in.PrepareOnly || dep.Status.Ready) {
				continue
			}
			c.mu.Lock()
			c.operation.Phase = "Microsoft 실행 기반 다운로드·설치"
			if in.PrepareOnly || !dep.InstallerCached {
				c.operation.Phase = "Microsoft 공식 설치 파일 준비 (설치하지 않음)"
			}
			c.operation.CurrentID = dep.Profile.ID
			c.mu.Unlock()
			progress := func(p DownloadProgress) {
				c.mu.Lock()
				c.operation.DependencyProgress = p
				c.mu.Unlock()
			}
			var result SystemDependencyResult
			var depErr error
			if in.PrepareOnly || !dep.InstallerCached {
				result, depErr = PrepareSystemDependencyFiles(ctx, a.store.dir, dep.Profile.ID, dep.Status, progress)
			} else {
				result, depErr = PrepareSystemDependency(ctx, a.store.dir, dep.Profile.ID, progress)
			}
			if depErr == nil && !in.PrepareOnly && !dep.Status.Ready && !dep.InstallerCached {
				c.mu.Lock()
				c.operation.Phase = "Microsoft 실행 기반 확인·설치"
				c.mu.Unlock()
				result, depErr = PrepareSystemDependency(ctx, a.store.dir, dep.Profile.ID, progress)
			}
			c.mu.Lock()
			c.operation.DependencyResults = append(c.operation.DependencyResults, result)
			c.operation.RestartRequired = c.operation.RestartRequired || result.RestartRequired
			c.operation.RestartCheckRequired = c.operation.RestartCheckRequired || result.RestartCheckRequired
			c.operation.SystemInstallationMayContinue = c.operation.SystemInstallationMayContinue || result.InstallationMayContinue
			if depErr == nil {
				c.operation.Completed++
			}
			c.mu.Unlock()
			if depErr != nil {
				err = depErr
				break
			}
		}
		for _, art := range plan.Missing {
			if err != nil {
				break
			}
			c.mu.Lock()
			c.operation.Phase = "모델·추론 엔진 다운로드·구성"
			c.operation.CurrentID = art.ID
			c.mu.Unlock()
			if err = downloadSetupArtifact(ctx, art.ID, a.assets, func(attempt int) {
				c.mu.Lock()
				c.operation.Phase = fmt.Sprintf("모델·추론 엔진 다운로드·구성 (재시도 %d/3)", attempt)
				c.mu.Unlock()
			}); err != nil {
				break
			}
			c.mu.Lock()
			c.operation.Completed++
			c.mu.Unlock()
		}
		if err == nil && !in.PrepareOnly {
			c.mu.Lock()
			c.operation.Phase = "실제 구동 검증"
			c.operation.CurrentID = ""
			c.mu.Unlock()
			err = a.verifySetup(ctx, cfg, c)
		}
		a.finishSetup(ctx, c, err)
	}()
	jsonReply(w, 202, op)
	return true
}

func (a *App) finishSetup(ctx context.Context, c *setupController, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	// Serialize final acceptance with cancellation. A request arriving after
	// this point observes a completed operation instead of cancelling it.
	if err == nil {
		err = ctx.Err()
	}
	c.cancel = nil
	if err != nil {
		a.pipeline.engine.Stop()
	}
	c.operation.State = "verified"
	if err == nil {
		c.operation.Phase = "파일 구성·기동·기능 연결 확인"
		if c.operation.PreparationOnly {
			c.operation.State = "prepared"
			c.operation.Phase = "파일 준비 완료 · PC 조건을 확인한 뒤 실제 구동 검증을 시작하세요"
		}
	}
	c.operation.FunctionalVerified = err == nil && !c.operation.PreparationOnly
	c.operation.FinishedAt = time.Now().UTC()
	if err != nil {
		c.operation.Error = err.Error()
		c.operation.State = "failed"
		if errors.Is(err, context.Canceled) {
			c.operation.State = "cancelled"
		}
	}
	if saveErr := a.store.put("settings", "setup-operation", c.operation); saveErr != nil {
		if err == nil {
			a.pipeline.engine.Stop()
		}
		c.operation.State = "failed"
		c.operation.Phase = "검증 결과 저장 실패"
		c.operation.FunctionalVerified = false
		c.operation.Error = fmt.Sprintf("%s 검증 결과를 저장하지 못했습니다. 저장 공간·자료 폴더를 확인하고 다시 검증하세요: %v", c.operation.Error, saveErr)
	}
}
