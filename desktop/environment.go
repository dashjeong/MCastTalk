package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

type environmentOperation struct {
	ID              string    `json:"id"`
	Kind            string    `json:"kind"`
	State           string    `json:"state"`
	Path            string    `json:"path"`
	StartedAt       time.Time `json:"startedAt"`
	FinishedAt      time.Time `json:"finishedAt,omitempty"`
	Result          any       `json:"result,omitempty"`
	Error           string    `json:"error,omitempty"`
	RestartRequired bool      `json:"restartRequired"`
	Phase           string    `json:"phase,omitempty"`
	CurrentFile     string    `json:"currentFile,omitempty"`
	CompletedBytes  int64     `json:"completedBytes"`
	TotalBytes      int64     `json:"totalBytes"`
}
type environmentController struct {
	manager   *PortableManager
	mu        sync.Mutex
	operation environmentOperation
	cancel    context.CancelFunc
}

func (a *App) environmentController() *environmentController {
	a.envStateMu.Lock()
	defer a.envStateMu.Unlock()
	if a.environment == nil {
		a.environment = &environmentController{manager: NewPortableManager(a.store, a.assets)}
		_ = a.store.get("settings", "environment-operation", &a.environment.operation)
		if a.environment.operation.State == "running" {
			a.environment.operation.State = "interrupted"
			a.environment.operation.Error = "이전 작업 중 프로그램이 중단되었습니다. 설치 상태를 확인하고 패키지를 다시 검사하세요"
			_ = a.store.put("settings", "environment-operation", a.environment.operation)
		}
	}
	return a.environment
}
func (a *App) environmentRequest(w http.ResponseWriter, r *http.Request) bool {
	if !a.envMu.TryRLock() {
		jsonReply(w, http.StatusConflict, map[string]string{"error": "환경 검증·이전 중입니다. 완료 또는 취소 후 다시 시도하세요"})
		return false
	}
	return true
}
func (a *App) importIdle() error {
	if a.assetTasks.Load() != 0 {
		return errors.New("진행 중인 모델 다운로드·가져오기를 완료하거나 취소하세요")
	}
	if engine, ok := a.pipeline.engine.(*Engine); ok {
		if !engine.environmentIdle() {
			return errors.New("실행 중이거나 시작 중인 엔진을 먼저 중지하세요")
		}
	} else {
		r := a.pipeline.engine.Ready()
		if r.TranslationReady || r.STTReady || r.TTSReady {
			return errors.New("엔진을 먼저 중지하세요")
		}
	}
	a.pipeline.mu.Lock()
	busy := a.pipeline.active != nil || a.pipeline.pending != nil || len(a.pipeline.input) > 0
	a.pipeline.mu.Unlock()
	if busy || a.pipeline.running.Load() != 0 || a.store.queueDepth() != 0 || a.inputOwned.Load() {
		return errors.New("현재 세션·입력·대기 작업을 마친 뒤 가져오세요")
	}
	rows, e := a.store.all("rooms")
	if e != nil {
		return e
	}
	for _, raw := range rows {
		var room Room
		if e = json.Unmarshal(raw, &room); e != nil {
			return e
		}
		if room.State == "active" {
			return errors.New("열려 있는 강의실을 먼저 닫으세요")
		}
	}
	jobs, e := a.store.all("jobs")
	if e != nil {
		return e
	}
	for _, raw := range jobs {
		var job Job
		if e = json.Unmarshal(raw, &job); e != nil {
			return e
		}
		if job.State == "queued" || job.State == "running" || job.State == "collecting" {
			return errors.New("재개할 작업이 남아 있습니다. 작업을 완료한 뒤 가져오세요")
		}
	}
	return nil
}
func (a *App) environmentAPI(w http.ResponseWriter, r *http.Request, path string) bool {
	if !strings.HasPrefix(path, "/environment/") {
		return false
	}
	c := a.environmentController()
	if path == "/environment/status" && r.Method == "GET" {
		c.mu.Lock()
		op := c.operation
		c.mu.Unlock()
		jsonReply(w, 200, map[string]any{"operation": op, "restartRequired": a.envRestart.Load(), "excludes": []string{"인증키·PIN·TLS 개인키", "개인 대화·강의 기록·녹음", "참여자·초대·로그"}})
		return true
	}
	if path == "/environment/cancel" && r.Method == "POST" {
		c.mu.Lock()
		if c.cancel != nil {
			c.cancel()
		}
		c.mu.Unlock()
		jsonReply(w, 202, map[string]string{"state": "cancel_requested"})
		return true
	}
	kind := strings.TrimPrefix(path, "/environment/")
	if r.Method != "POST" || (kind != "export" && kind != "inspect" && kind != "import") {
		http.NotFound(w, r)
		return true
	}
	var in struct {
		Path        string                `json:"path"`
		Options     PortableExportOptions `json:"options"`
		Fingerprint string                `json:"fingerprint"`
	}
	if e := readJSON(w, r, &in); e != nil {
		apiError(w, e)
		return true
	}
	if kind == "import" && !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(in.Fingerprint) {
		apiError(w, errors.New("패키지를 검사한 뒤 가져오세요"))
		return true
	}
	c.mu.Lock()
	if c.cancel != nil {
		c.mu.Unlock()
		jsonReply(w, 409, map[string]string{"error": "다른 환경 이전 작업이 진행 중입니다"})
		return true
	}
	if !a.envMu.TryLock() {
		c.mu.Unlock()
		jsonReply(w, 409, map[string]string{"error": "사용 중인 연결·작업이 있습니다. 완료하거나 연결을 종료한 뒤 다시 시도하세요"})
		return true
	}
	if a.assetTasks.Load() != 0 {
		a.envMu.Unlock()
		c.mu.Unlock()
		apiError(w, errors.New("모델 다운로드·가져오기를 먼저 완료하세요"))
		return true
	}
	if kind == "import" {
		if e := a.importIdle(); e != nil {
			a.envMu.Unlock()
			c.mu.Unlock()
			apiError(w, e)
			return true
		}
	}
	ctx, cancel := context.WithCancel(a.pipeline.ctx)
	c.manager.expectedManifestSHA = in.Fingerprint
	c.cancel = cancel
	c.operation = environmentOperation{ID: newID(), Kind: kind, State: "running", Path: in.Path, StartedAt: time.Now().UTC()}
	c.manager.progress = func(phase, file string, done, total int64) {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.operation.Phase = phase
		c.operation.CurrentFile = file
		c.operation.CompletedBytes = done
		c.operation.TotalBytes = total
	}
	op := c.operation
	if e := a.store.put("settings", "environment-operation", op); e != nil {
		cancel()
		c.cancel = nil
		a.envMu.Unlock()
		c.mu.Unlock()
		apiError(w, e)
		return true
	}
	c.mu.Unlock()
	go func() {
		defer a.envMu.Unlock()
		defer cancel()
		var result any
		var err error
		switch kind {
		case "export":
			result, err = c.manager.Export(ctx, in.Path, in.Options)
		case "inspect":
			result, err = c.manager.Inspect(ctx, in.Path)
		case "import":
			result, err = c.manager.Import(ctx, in.Path)
			if err == nil {
				a.cfgMu.Lock()
				a.cfg = a.store.config()
				a.cfgMu.Unlock()
				a.envRestart.Store(true)
			}
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		c.operation.Result = result
		c.operation.FinishedAt = time.Now().UTC()
		c.operation.RestartRequired = a.envRestart.Load()
		c.operation.State = "completed"
		if err != nil {
			c.operation.Error = err.Error()
			c.operation.State = "failed"
			if errors.Is(err, context.Canceled) {
				c.operation.State = "cancelled"
			}
		}
		c.cancel = nil
		_ = a.store.put("settings", "environment-operation", c.operation)
	}()
	jsonReply(w, http.StatusAccepted, op)
	return true
}
