package main

import (
	"context"
	"crypto/subtle"
	"embed"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

//go:embed web mobile
var uiFS embed.FS

type App struct {
	setupStateMu sync.Mutex
	setup        *setupController
	envMu        sync.RWMutex
	envStateMu   sync.Mutex
	environment  *environmentController
	assetTasks   atomic.Int64
	envRestart   atomic.Bool
	roomsMu      sync.Mutex
	rooms        *RoomService
	store        *Store
	pipeline     *Pipeline
	assets       *AssetManager
	adminToken   string
	cfgMu        sync.RWMutex
	cfg          Config
	diagMu       sync.Mutex
	diagnostic   Diagnostic
	inputOwned   atomic.Bool
	inputMu      sync.Mutex
	inputLease   *microphoneLease
	requests     *requestLimiter
}
type requestLimiter struct {
	mu    sync.Mutex
	peers map[string]*peerLimit
}
type peerLimit struct {
	since   time.Time
	count   int
	sockets int
}

func newLimiter() *requestLimiter { return &requestLimiter{peers: map[string]*peerLimit{}} }
func (l *requestLimiter) allow(peer string) bool {
	return l.allowBudget(peer, 180)
}
func (l *requestLimiter) allowBudget(peer string, budget int) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	for k, p := range l.peers {
		if now.Sub(p.since) > 2*time.Minute && p.sockets == 0 {
			delete(l.peers, k)
		}
	}
	p := l.peers[peer]
	if p == nil {
		if len(l.peers) >= 8192 {
			return false
		}
		p = &peerLimit{since: now}
		l.peers[peer] = p
	}
	if now.Sub(p.since) > time.Minute {
		p.count = 0
		p.since = now
	}
	p.count++
	return p.count <= budget
}
func (l *requestLimiter) socket(peer string, delta int) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	p := l.peers[peer]
	if p == nil {
		return false
	}
	if delta > 0 && p.sockets >= 8 {
		return false
	}
	p.sockets += delta
	return true
}
func (a *App) config() Config {
	a.cfgMu.RLock()
	defer a.cfgMu.RUnlock()
	c := a.cfg
	c.TargetLanguages = append([]string(nil), c.TargetLanguages...)
	return c
}
func redacted(c Config) Config {
	c.Online.APIKey = ""
	c.ListenerPIN = ""
	c.SpeakerPIN = ""
	return c
}
func pinStatus(c Config) map[string]bool {
	return map[string]bool{"listenerSet": c.ListenerPIN != "", "speakerSet": c.SpeakerPIN != ""}
}
func jsonReply(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
func apiError(w http.ResponseWriter, err error) {
	jsonReply(w, 400, map[string]string{"error": err.Error()})
}
func readJSON(w http.ResponseWriter, r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 1024*1024)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if e := d.Decode(v); e != nil {
		return e
	}
	var tail any
	if d.Decode(&tail) != io.EOF {
		return errors.New("하나의 JSON 요청만 허용됩니다")
	}
	return nil
}
func equalToken(a, b string) bool {
	return len(a) > 0 && len(a) == len(b) && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	return ""
}
func peerIP(r *http.Request) string { host, _, _ := net.SplitHostPort(r.RemoteAddr); return host }
func loopback(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
func validOrigin(r *http.Request, require bool) bool {
	h := r.Header.Values("Origin")
	if len(h) == 0 {
		return !require
	}
	if len(h) != 1 {
		return false
	}
	u, e := url.Parse(h[0])
	if e != nil || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return false
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return u.Scheme == scheme && strings.EqualFold(u.Host, r.Host)
}
func securityHeaders(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; media-src 'self' blob:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
}
func (a *App) hostAllowed(r *http.Request, admin bool) bool {
	host := r.Host
	if h, _, e := net.SplitHostPort(host); e == nil {
		host = h
	}
	if admin {
		return loopback(host) && loopback(peerIP(r))
	}
	if loopback(host) {
		return true
	}
	cfg := a.config()
	u, _ := url.Parse(cfg.PublicURL)
	if u != nil && strings.EqualFold(host, u.Hostname()) {
		return true
	}
	bind, _, _ := net.SplitHostPort(cfg.PublicBind)
	if host == bind && net.ParseIP(host) != nil {
		return true
	}
	addresses, _ := net.InterfaceAddrs()
	for _, ad := range addresses {
		ip, _, e := net.ParseCIDR(ad.String())
		if e == nil && host == ip.String() {
			return true
		}
	}
	return false
}
func (a *App) adminHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		securityHeaders(w)
		if !a.hostAllowed(r, true) {
			http.Error(w, "Invalid Host", 421)
			return
		}
		if !validOrigin(r, r.Method != "GET") {
			http.Error(w, "Invalid Origin", 403)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/admin/") {
			token := bearer(r)
			if r.URL.Path == "/admin/ws/input" {
				token = r.URL.Query().Get("token")
			}
			if !equalToken(token, a.adminToken) {
				http.Error(w, "Unauthorized", 401)
				return
			}
			if r.URL.Path == "/admin/ws/input" {
				if !a.environmentRequest(w, r) {
					return
				}
				defer a.envMu.RUnlock()
				a.inputSocket(w, r, false)
				return
			}
			a.adminAPI(w, r)
			return
		}
		a.serveAdminFile(w, r)
	})
}
func (a *App) serveAdminFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "Method not allowed", 405)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/")
	if path == "" {
		path = "index.html"
	}
	sub, _ := fs.Sub(uiFS, "web")
	http.FileServer(http.FS(sub)).ServeHTTP(w, r.Clone(r.Context()))
}

var languagePattern = regexp.MustCompile(`^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?$`)

func validateConfig(c Config) error {
	if c.Backend != "cpu" && c.Backend != "cuda" && c.Backend != "vulkan" && c.Backend != "openvino-npu" {
		return errors.New("CPU/CUDA/Vulkan/OpenVINO NPU 중 지원 후보를 선택하세요")
	}
	host, port, e := net.SplitHostPort(c.PublicBind)
	if e != nil || net.ParseIP(host) == nil {
		return errors.New("서버 주소는 IP:포트 형태로 입력하세요")
	}
	p, _ := strconv.Atoi(port)
	if p < 1024 || p > 65535 {
		return errors.New("서버 포트는 1024~65535입니다")
	}
	if c.MaxListeners < 1 || c.MaxListeners > 2048 {
		return errors.New("청취자 정원은 1~2048명입니다. 실제 수용량은 부하 시험으로 확인하세요")
	}
	if c.Access != "open" && c.Access != "qr" && c.Access != "pin" {
		return errors.New("입장 방식 오류")
	}
	// Public join requests are bounded to 32 UTF-8 bytes and trim outer space.
	// Reject credentials that could be saved but never supplied consistently.
	if c.ListenerPIN != strings.TrimSpace(c.ListenerPIN) || c.SpeakerPIN != strings.TrimSpace(c.SpeakerPIN) {
		return errors.New("PIN 앞뒤에 공백을 넣을 수 없습니다")
	}
	if len(c.ListenerPIN) > 32 || (c.Access == "pin" && len(c.ListenerPIN) < 6) {
		return errors.New("청취 PIN은 UTF-8 기준 6~32바이트입니다")
	}
	if len(c.SpeakerPIN) < 6 || len(c.SpeakerPIN) > 32 {
		return errors.New("원격 마이크 PIN은 UTF-8 기준 6~32바이트입니다")
	}
	if !languagePattern.MatchString(c.SourceLanguage) || len(c.TargetLanguages) > 32 {
		return errors.New("언어 선택 오류")
	}
	seen := map[string]bool{}
	for _, l := range c.TargetLanguages {
		if !languagePattern.MatchString(l) || l == "source" || seen[l] {
			return errors.New("중복 또는 잘못된 출력 언어")
		}
		seen[l] = true
	}
	if c.PublicURL != "" {
		u, e := url.Parse(c.PublicURL)
		if e != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
			return errors.New("외부 접속 주소는 HTTPS 기본 주소를 입력하세요")
		}
		if c.TLSCert == "" || c.TLSKey == "" {
			return errors.New("외부 서버에는 DDNS 이름에 맞는 TLS 인증서와 키가 필요합니다")
		}
	}
	if c.Online.Endpoint != "" {
		u, e := url.Parse(c.Online.Endpoint)
		if e != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
			return errors.New("온라인 제공자 주소는 HTTPS여야 합니다")
		}
	}
	return nil
}
func (a *App) adminAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/admin/api")
	if a.environmentAPI(w, r, path) {
		return
	}
	if a.setupAPI(w, r, path) {
		return
	}
	if r.Method != "GET" {
		if !a.environmentRequest(w, r) {
			return
		}
		defer a.envMu.RUnlock()
	}
	if a.adminRooms(w, r, path) {
		return
	}
	parts := strings.Split(strings.Trim(path, "/"), "/")
	switch {
	case r.Method == "GET" && path == "/status":
		depth := a.store.queueDepth() + int(a.pipeline.running.Load())
		cfg := a.config()
		public := cfg.PublicURL
		if public == "" {
			scheme := "http"
			if cfg.TLSCert != "" {
				scheme = "https"
			}
			public = scheme + "://" + cfg.PublicBind
		}
		s := a.pipeline.current()
		if s != nil && cfg.Access != "open" {
			public += "/#token=" + s.Token
		}
		a.diagMu.Lock()
		d := a.diagnostic
		a.diagMu.Unlock()
		jsonReply(w, 200, map[string]any{"version": Version, "config": redacted(cfg), "pinStatus": pinStatus(cfg), "engine": a.pipeline.engine.Ready(), "diagnostic": d, "activeSession": s, "queueDepth": depth, "publicURL": public, "lastError": a.pipeline.lastIssue(), "listeners": a.pipeline.hub.count(), "droppedFrames": a.pipeline.hub.dropped.Load(), "deliveredFrames": a.pipeline.hub.delivered.Load()})
	case r.Method == "GET" && path == "/diagnostics":
		ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
		defer cancel()
		d := Diagnose(ctx, a.store.dir)
		a.diagMu.Lock()
		a.diagnostic = d
		a.diagMu.Unlock()
		jsonReply(w, 200, d)
	case r.Method == "GET" && path == "/catalog":
		jsonReply(w, 200, map[string]any{"artifacts": a.assets.Registry(), "installed": a.store.assets(), "progress": a.assets.Progress()})
	case r.Method == "POST" && path == "/models/register":
		var in struct {
			Repo     string   `json:"repo"`
			Revision string   `json:"revision"`
			Filename string   `json:"filename"`
			Profile  Artifact `json:"profile"`
		}
		if err := readJSON(w, r, &in); err != nil {
			apiError(w, err)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		artifact, err := a.assets.Register(ctx, in.Repo, in.Revision, in.Filename, in.Profile)
		if err != nil {
			apiError(w, err)
			return
		}
		jsonReply(w, http.StatusCreated, artifact)
	case r.Method == "POST" && (path == "/models/download" || path == "/models/import" || path == "/models/cancel"):
		var in struct {
			ID   string `json:"id"`
			Path string `json:"path"`
		}
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		if path == "/models/cancel" {
			a.assets.Cancel(in.ID)
		} else {
			a.assetTasks.Add(1)
			go func() {
				defer a.assetTasks.Add(-1)
				a.envMu.RLock()
				defer a.envMu.RUnlock()
				var e error
				if in.Path != "" && path == "/models/import" {
					e = a.assets.Import(a.pipeline.ctx, in.ID, in.Path)
				} else if path == "/models/download" {
					e = a.assets.Download(a.pipeline.ctx, in.ID)
				} else {
					e = errors.New("파일 경로가 필요합니다")
				}
				a.pipeline.report(e)
			}()
		}
		jsonReply(w, 202, map[string]string{"state": "accepted"})
	case r.Method == "POST" && path == "/engine/start":
		if a.envRestart.Load() {
			apiError(w, errors.New("환경을 가져왔습니다. 프로그램을 다시 실행한 뒤 대상 PC를 진단하고 엔진을 시작하세요"))
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 120*time.Second)
		defer cancel()
		if e := a.pipeline.engine.Start(ctx, a.config(), a.store.assets()); e != nil {
			a.pipeline.report(e)
			apiError(w, e)
			return
		}
		jsonReply(w, 200, a.pipeline.engine.Ready())
	case r.Method == "POST" && path == "/engine/stop":
		a.pipeline.engine.Stop()
		jsonReply(w, 200, a.pipeline.engine.Ready())
	case r.Method == "POST" && path == "/config":
		var c Config
		if e := readJSON(w, r, &c); e != nil {
			apiError(w, e)
			return
		}
		old := a.config()
		if c.Online.APIKey == "" {
			c.Online.APIKey = old.Online.APIKey
		}
		if c.ListenerPIN == "" {
			c.ListenerPIN = old.ListenerPIN
		}
		if c.SpeakerPIN == "" {
			c.SpeakerPIN = old.SpeakerPIN
		}
		if e := validateConfig(c); e != nil {
			apiError(w, e)
			return
		}
		if e := a.store.saveConfig(c); e != nil {
			apiError(w, e)
			return
		}
		a.cfgMu.Lock()
		a.cfg = c
		a.cfgMu.Unlock()
		if engine, ok := a.pipeline.engine.(interface{ SetOnlineConfig(OnlineConfig) }); ok {
			engine.SetOnlineConfig(c.Online)
		}
		jsonReply(w, 200, map[string]any{"config": redacted(c), "pinStatus": pinStatus(c), "restartRequired": c.PublicBind != old.PublicBind || c.PublicURL != old.PublicURL || c.TLSCert != old.TLSCert || c.TLSKey != old.TLSKey, "engineRestartRequired": c.Backend != old.Backend || c.TranslationModel != old.TranslationModel || c.STTModel != old.STTModel})
	case r.Method == "GET" && path == "/sessions":
		data, e := a.store.all("sessions")
		if e != nil {
			apiError(w, e)
			return
		}
		filtered := []json.RawMessage{}
		for _, raw := range data {
			var session Session
			if json.Unmarshal(raw, &session) == nil && session.Kind != "private" {
				session.Token = ""
				encoded, _ := json.Marshal(session)
				filtered = append(filtered, encoded)
			}
		}
		jsonReply(w, 200, filtered)
	case r.Method == "POST" && path == "/sessions":
		var in struct {
			Kind           string   `json:"kind"`
			Title          string   `json:"title"`
			SourceLanguage string   `json:"sourceLanguage"`
			Targets        []string `json:"targets"`
		}
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		if in.Kind != "broadcast" && in.Kind != "note" && in.Kind != "file" {
			apiError(w, errors.New("작업 유형 오류"))
			return
		}
		cfg := a.config()
		if in.SourceLanguage == "" {
			in.SourceLanguage = cfg.SourceLanguage
		}
		if in.Targets == nil {
			in.Targets = cfg.TargetLanguages
		}
		trial := cfg
		trial.SourceLanguage = in.SourceLanguage
		trial.TargetLanguages = in.Targets
		if e := validateConfig(trial); e != nil {
			apiError(w, e)
			return
		}
		if len(in.Title) > 512 {
			apiError(w, errors.New("제목이 너무 깁니다"))
			return
		}
		s := Session{ID: newID(), Kind: in.Kind, Title: in.Title, SourceLanguage: in.SourceLanguage, Targets: in.Targets, Token: newID() + newID(), StartedAt: time.Now().UTC()}
		if s.Kind == "file" {
			s.State = "stopped"
			s.UpdatedAt = s.StartedAt
			if e := a.store.put("sessions", s.ID, s); e != nil {
				apiError(w, e)
				return
			}
		} else {
			if e := a.pipeline.begin(s); e != nil {
				apiError(w, e)
				return
			}
			s = *a.pipeline.current()
		}
		jsonReply(w, 201, s)
	case r.Method == "POST" && path == "/sessions/stop":
		if e := a.pipeline.stop(); e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 200, map[string]bool{"stopped": true})
	case r.Method == "POST" && path == "/input/flush":
		var in struct {
			SessionID string `json:"sessionId"`
		}
		if e := readJSON(w, r, &in); e != nil || !validID(in.SessionID) {
			http.Error(w, "Input session is required", http.StatusBadRequest)
			return
		}
		if e := a.pipeline.flushInputForSession(in.SessionID); e != nil {
			http.Error(w, e.Error(), http.StatusConflict)
			return
		}
		jsonReply(w, 200, map[string]bool{"flushed": true})
	case r.Method == "GET" && len(parts) == 3 && parts[0] == "sessions" && parts[2] == "lines":
		if a.store.isPrivateSession(parts[1]) {
			http.Error(w, "Private channel requires participant authentication", 403)
			return
		}
		after, _ := strconv.ParseUint(r.URL.Query().Get("after"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		lines, e := a.store.lines(parts[1], after, limit)
		if e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 200, lines)
	case r.Method == "GET" && len(parts) == 3 && parts[0] == "sessions" && parts[2] == "export":
		a.exportSession(w, r, parts[1])
	case r.Method == "GET" && len(parts) == 3 && parts[0] == "audio":
		a.audioFile(w, r, parts[1], parts[2])
	case r.Method == "POST" && path == "/text":
		var in struct {
			SessionID string `json:"sessionId"`
			Text      string `json:"text"`
		}
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		l, e := a.pipeline.text(in.SessionID, in.Text)
		if e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 201, l)
	case r.Method == "POST" && path == "/files":
		a.uploadAudio(w, r)
	case r.Method == "GET" && path == "/jobs":
		data, e := a.store.all("jobs")
		if e != nil {
			apiError(w, e)
			return
		}
		filtered := []json.RawMessage{}
		for _, raw := range data {
			var job Job
			if json.Unmarshal(raw, &job) == nil && !a.store.isPrivateSession(job.SessionID) {
				filtered = append(filtered, raw)
			}
		}
		jsonReply(w, 200, filtered)
	case r.Method == "POST" && len(parts) == 3 && parts[0] == "jobs" && parts[2] == "retry":
		var j Job
		if e := a.store.get("jobs", parts[1], &j); e != nil {
			apiError(w, e)
			return
		}
		if j.State != "failed" {
			apiError(w, errors.New("실패한 작업만 재시도할 수 있습니다"))
			return
		}
		j.State = "queued"
		j.Error = ""
		if e := a.store.put("jobs", j.ID, j); e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 200, j)
	case r.Method == "GET" && path == "/glossary":
		jsonReply(w, 200, a.pipeline.glossary())
	case r.Method == "POST" && path == "/glossary":
		var terms []GlossaryTerm
		if e := readJSON(w, r, &terms); e != nil {
			apiError(w, e)
			return
		}
		if len(terms) > 10000 {
			apiError(w, errors.New("한 번에 10,000개 이하의 용어를 저장하세요"))
			return
		}
		for _, t := range terms {
			if len(t.Source) > 512 || len(t.Target) > 512 || !languagePattern.MatchString(t.Language) {
				apiError(w, errors.New("용어 형식 오류"))
				return
			}
		}
		if e := a.store.put("glossary", "terms", terms); e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 200, terms)
	case r.Method == "GET" && (path == "/scripts" || path == "/lessons"):
		data, e := a.store.all(strings.TrimPrefix(path, "/"))
		if e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 200, data)
	case r.Method == "POST" && path == "/scripts":
		var in struct {
			Title string `json:"title"`
			Text  string `json:"text"`
		}
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		id := newID()
		v := map[string]string{"id": id, "title": in.Title, "text": in.Text}
		if e := a.store.put("scripts", id, v); e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 201, v)
	case r.Method == "POST" && path == "/lessons":
		var in Lesson
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		if !languagePattern.MatchString(in.Language) || len(in.Source) > 8192 || strings.TrimSpace(in.Proposed) == "" {
			apiError(w, errors.New("학습 비교문 형식 오류"))
			return
		}
		in.ID = newID()
		in.Status = "pending"
		in.CreatedAt = time.Now().UTC()
		if e := a.store.put("lessons", in.ID, in); e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 201, in)
	case r.Method == "POST" && len(parts) == 3 && parts[0] == "lessons" && parts[2] == "decision":
		var in struct {
			Status string `json:"status"`
		}
		if e := readJSON(w, r, &in); e != nil {
			apiError(w, e)
			return
		}
		if in.Status != "approved" && in.Status != "held" {
			apiError(w, errors.New("승인 또는 보류를 선택하세요"))
			return
		}
		var l Lesson
		if e := a.store.get("lessons", parts[1], &l); e != nil {
			apiError(w, e)
			return
		}
		l.Status = in.Status
		if e := a.store.put("lessons", l.ID, l); e != nil {
			apiError(w, e)
			return
		}
		jsonReply(w, 200, l)
	case r.Method == "POST" && path == "/benchmark":
		a.benchmark(w, r)
	case r.Method == "GET" && path == "/backup":
		a.backup(w, r)
	case r.Method == "POST" && path == "/restore":
		a.restore(w, r)
	default:
		http.Error(w, "Not found", 404)
	}
}

func (a *App) publicHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		securityHeaders(w)
		if a.envRestart.Load() && !loopback(peerIP(r)) {
			http.Error(w, "환경 적용 후 프로그램을 다시 실행하세요", 503)
			return
		}
		if r.Method != "GET" || websocket.IsWebSocketUpgrade(r) {
			if !a.environmentRequest(w, r) {
				return
			}
			defer a.envMu.RUnlock()
		}
		if !a.hostAllowed(r, false) {
			http.Error(w, "Invalid Host", 421)
			return
		}
		if !validOrigin(r, r.Method != "GET") {
			http.Error(w, "Invalid Origin", 403)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/classroom/") {
			a.classroomAPI(w, r)
			return
		}
		if r.URL.Path == "/room/" || strings.HasPrefix(r.URL.Path, "/room/") {
			sub, _ := fs.Sub(uiFS, "web")
			http.FileServer(http.FS(sub)).ServeHTTP(w, r)
			return
		}
		peer := peerIP(r)
		if !a.requests.allow(peer) {
			w.Header().Set("Retry-After", "60")
			http.Error(w, "Too many requests", 429)
			return
		}
		path := r.URL.Path
		cfg := a.config()
		s := a.pipeline.current()
		if strings.HasPrefix(path, "/admin/") {
			http.Error(w, "Not found", 404)
			return
		}
		if path == "/api/session" && r.Method == "GET" {
			access := cfg.Access
			if access == "qr" {
				access = "qrtoken"
			}
			jsonReply(w, 200, map[string]string{"access": access})
			return
		}
		if path == "/api/join" && r.Method == "POST" {
			r.Body = http.MaxBytesReader(w, r.Body, 32)
			b, e := io.ReadAll(r.Body)
			if e != nil || cfg.Access != "pin" || s == nil || !equalToken(strings.TrimSpace(string(b)), cfg.ListenerPIN) {
				http.Error(w, "Invalid PIN", 401)
				return
			}
			w.Header().Set("Content-Type", "text/plain")
			io.WriteString(w, s.Token)
			return
		}
		if strings.HasPrefix(path, "/api/mic/") {
			a.micAPI(w, r)
			return
		}
		if path == "/ws/speaker-input" {
			a.inputSocket(w, r, true)
			return
		}
		if strings.HasPrefix(path, "/api/") || strings.HasPrefix(path, "/ws/") {
			token := bearer(r)
			if strings.HasPrefix(path, "/ws/") {
				token = r.URL.Query().Get("token")
			}
			if s == nil {
				http.Error(w, "No active broadcast", 410)
				return
			}
			if cfg.Access != "open" && !equalToken(token, s.Token) {
				http.Error(w, "Unauthorized", 401)
				return
			}
			channel := r.URL.Query().Get("channel")
			if channel != "" && !sessionHasChannel(s, channel) {
				http.Error(w, "Unknown channel", 404)
				return
			}
			switch {
			case path == "/api/status":
				channels := []map[string]any{}
				for _, c := range append([]string{"source"}, s.Targets...) {
					if channel == "" || c == channel || c == "source" {
						channels = append(channels, map[string]any{"id": c, "name": c, "displayName": c, "languageTag": c, "sampleRate": 16000, "listenerPath": "/" + c})
					}
				}
				jsonReply(w, 200, map[string]any{"generation": a.pipeline.hub.gen(), "active": true, "scope": "session", "channels": channels, "listenerCount": a.pipeline.hub.count(), "maxListeners": cfg.MaxListeners, "droppedFrames": a.pipeline.hub.dropped.Load(), "webSocketDeliveredFrames": a.pipeline.hub.delivered.Load()})
			case path == "/api/transcripts" || path == "/api/replay-captions":
				after, _ := strconv.ParseUint(r.URL.Query().Get("afterSequence"), 10, 64)
				lines, e := a.store.lines(s.ID, after, 100)
				if e != nil {
					apiError(w, e)
					return
				}
				if channel != "" {
					for i := range lines {
						selected := lines[i].Translations[channel]
						lines[i].Translations = map[string]string{}
						if selected != "" {
							lines[i].Translations[channel] = selected
						}
					}
				}
				if path == "/api/transcripts" {
					jsonReply(w, 200, map[string]any{"transcripts": lines, "count": len(lines)})
				} else {
					rows := []map[string]any{}
					for _, l := range lines {
						raw, _ := json.Marshal(l)
						v := map[string]any{}
						_ = json.Unmarshal(raw, &v)
						v["part"] = 0
						rows = append(rows, v)
					}
					jsonReply(w, 200, rows)
				}
			case path == "/api/replay" || strings.HasPrefix(path, "/api/replay/"):
				a.publicReplay(w, r, s)
			case strings.HasPrefix(path, "/ws/"):
				c := strings.TrimPrefix(path, "/ws/")
				if !sessionHasChannel(s, c) {
					http.Error(w, "Unknown channel", 404)
					return
				}
				a.listenSocket(w, r, c)
			default:
				http.Error(w, "Not found", 404)
			}
			return
		}
		if r.Method != "GET" {
			http.Error(w, "Method not allowed", 405)
			return
		}
		file := ""
		switch path {
		case "/player.js":
			parts := []string{"mobile/listener/i18n.js", "mobile/listener/player.js", "mobile/listener/replay.js"}
			w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
			for _, p := range parts {
				b, _ := uiFS.ReadFile(p)
				w.Write(b)
				io.WriteString(w, "\n")
			}
			return
		case "/player.css":
			file = "mobile/listener/player.css"
		case "/speaker.js":
			file = "mobile/speaker/speaker.js"
		case "/speaker.css":
			file = "mobile/speaker/speaker.css"
		case "/mic", "/speaker":
			file = "mobile/speaker/speaker.html"
		default:
			if path == "/" || (s != nil && sessionHasChannel(s, strings.TrimPrefix(path, "/"))) {
				file = "mobile/listener/index.html"
			}
		}
		if file == "" {
			http.Error(w, "Not found", 404)
			return
		}
		b, e := uiFS.ReadFile(file)
		if e != nil {
			http.Error(w, "Asset missing", 500)
			return
		}
		if strings.HasSuffix(file, ".html") {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
		}
		if strings.HasSuffix(file, ".css") {
			w.Header().Set("Content-Type", "text/css; charset=utf-8")
		}
		if strings.HasSuffix(file, ".js") {
			w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		}
		w.Write(b)
	})
}
func sessionHasChannel(s *Session, c string) bool {
	if c == "source" {
		return true
	}
	for _, lang := range s.Targets {
		if lang == c {
			return true
		}
	}
	return false
}

var wsUpgrader = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return validOrigin(r, true) }, ReadBufferSize: 4096, WriteBufferSize: 4096}

func (a *App) listenSocket(w http.ResponseWriter, r *http.Request, channel string) {
	peer := peerIP(r)
	if !a.requests.socket(peer, 1) {
		http.Error(w, "Connection limit", 429)
		return
	}
	defer a.requests.socket(peer, -1)
	sub, e := a.pipeline.hub.subscribe(channel, a.config().MaxListeners)
	if e != nil {
		http.Error(w, e.Error(), 429)
		return
	}
	defer a.pipeline.hub.unsubscribe(sub)
	c, e := wsUpgrader.Upgrade(w, r, nil)
	if e != nil {
		return
	}
	defer c.Close()
	c.SetReadLimit(4096)
	_ = c.SetReadDeadline(time.Now().Add(45 * time.Second))
	c.SetPongHandler(func(string) error { return c.SetReadDeadline(time.Now().Add(45 * time.Second)) })
	_ = c.WriteJSON(map[string]any{"type": "config", "format": "pcm_s16le", "channels": 1, "sampleRate": 16000, "generation": a.pipeline.hub.gen()})
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			if _, _, e := c.ReadMessage(); e != nil {
				return
			}
		}
	}()
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-sub.done:
			return
		case <-a.pipeline.ctx.Done():
			return
		case <-ticker.C:
			if c.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)) != nil {
				return
			}
		case pcm := <-sub.frames:
			_ = c.SetWriteDeadline(time.Now().Add(3 * time.Second))
			if c.WriteMessage(websocket.BinaryMessage, pcm) != nil {
				return
			}
			a.pipeline.hub.delivered.Add(1)
		}
	}
}
func (a *App) micAPI(w http.ResponseWriter, r *http.Request) {
	secure := r.TLS != nil || loopback(peerIP(r))
	if !secure {
		http.Error(w, "HTTPS is required for microphone access", 426)
		return
	}
	cfg := a.config()
	cookie, _ := r.Cookie("guidecast_speaker_session")
	authenticated := cookie != nil && a.validSpeakerCookie(cookie.Value)
	if r.URL.Path == "/api/mic/session" && r.Method == "GET" {
		jsonReply(w, 200, map[string]bool{"requiresPin": true, "authenticated": authenticated})
		return
	}
	if r.URL.Path != "/api/mic/join" || r.Method != "POST" {
		http.Error(w, "Not found", 404)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 32)
	b, e := io.ReadAll(r.Body)
	if e != nil || !equalToken(strings.TrimSpace(string(b)), cfg.SpeakerPIN) {
		http.Error(w, "Invalid PIN", 401)
		return
	}
	token, e := a.makeSpeakerCookie()
	if e != nil {
		apiError(w, e)
		return
	}
	http.SetCookie(w, &http.Cookie{Name: "guidecast_speaker_session", Value: token, Path: "/", MaxAge: 4 * 60 * 60, HttpOnly: true, Secure: r.TLS != nil, SameSite: http.SameSiteStrictMode})
	jsonReply(w, 200, map[string]bool{"authenticated": true})
}

type microphoneLease struct {
	sessionID  string
	connection *websocket.Conn
}

func (a *App) claimMicrophone(sessionID string) (*microphoneLease, <-chan struct{}, error) {
	// Lock order is pipeline then input. Recheck the session at registration,
	// not only before the network handshake, to prevent stale owner takeover.
	a.pipeline.mu.Lock()
	defer a.pipeline.mu.Unlock()
	if a.pipeline.active == nil || a.pipeline.active.ID != sessionID {
		return nil, nil, errors.New("Microphone session is no longer active")
	}
	a.inputMu.Lock()
	defer a.inputMu.Unlock()
	if a.inputLease != nil {
		if a.inputLease.sessionID == sessionID {
			return nil, nil, errors.New("Another microphone is active")
		}
		if a.inputLease.connection != nil {
			_ = a.inputLease.connection.Close()
		}
	}
	lease := &microphoneLease{sessionID: sessionID}
	a.inputLease = lease
	a.inputOwned.Store(true)
	return lease, a.pipeline.sessionDone, nil
}

func (a *App) releaseMicrophone(lease *microphoneLease) {
	a.inputMu.Lock()
	defer a.inputMu.Unlock()
	if a.inputLease == lease {
		a.inputLease = nil
		a.inputOwned.Store(false)
	}
}

func (a *App) inputSocket(w http.ResponseWriter, r *http.Request, remote bool) {
	if remote {
		cookie, e := r.Cookie("guidecast_speaker_session")
		if e != nil || !a.validSpeakerCookie(cookie.Value) || (r.TLS == nil && !loopback(peerIP(r))) {
			http.Error(w, "Unauthorized microphone", 401)
			return
		}
	}
	sessionID := r.URL.Query().Get("sessionId")
	active := a.pipeline.current()
	if remote && sessionID == "" && active != nil {
		sessionID = active.ID
	}
	if !validID(sessionID) || active == nil || active.ID != sessionID {
		http.Error(w, "Microphone session is no longer active", http.StatusConflict)
		return
	}
	lease, sessionDone, e := a.claimMicrophone(sessionID)
	if e != nil {
		http.Error(w, e.Error(), http.StatusConflict)
		return
	}
	defer a.releaseMicrophone(lease)
	c, e := wsUpgrader.Upgrade(w, r, nil)
	if e != nil {
		return
	}
	defer c.Close()
	a.inputMu.Lock()
	if a.inputLease != lease {
		a.inputMu.Unlock()
		return
	}
	lease.connection = c
	a.inputMu.Unlock()
	connectionDone := make(chan struct{})
	defer close(connectionDone)
	go func() {
		select {
		case <-sessionDone:
			_ = c.Close()
		case <-connectionDone:
		}
	}()
	c.SetReadLimit(65540)
	_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
	var seq int64 = -1
	for {
		typ, b, e := c.ReadMessage()
		if e != nil {
			// Closing an old socket must never flush a newer session's input.
			_ = a.pipeline.flushInputForSession(sessionID)
			return
		}
		_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
		if typ == websocket.TextMessage {
			if string(b) == "probe" {
				current := a.pipeline.current()
				ready := current != nil && current.ID == sessionID
				_ = c.WriteJSON(map[string]any{"type": "input-status", "ready": ready, "appInputReady": ready})
				if !ready {
					return
				}
			}
			continue
		}
		if typ != websocket.BinaryMessage {
			continue
		}
		if remote {
			if len(b) < 6 {
				return
			}
			n := int64(binary.BigEndian.Uint32(b[:4]))
			if n <= seq {
				return
			}
			seq = n
			b = b[4:]
		}
		if e = a.pipeline.inputPCMForSession(sessionID, b); e != nil {
			a.pipeline.report(e)
			_ = c.WriteJSON(map[string]string{"type": "error", "message": e.Error()})
			return
		}
	}
}

func (a *App) uploadAudio(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 256*1024*1024)
	if e := r.ParseMultipartForm(4 * 1024 * 1024); e != nil {
		apiError(w, e)
		return
	}
	defer r.MultipartForm.RemoveAll()
	f, _, e := r.FormFile("audio")
	if e != nil {
		apiError(w, e)
		return
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, 256*1024*1024))
	if e != nil {
		apiError(w, e)
		return
	}
	j, e := a.pipeline.queueFile(r.URL.Query().Get("sessionId"), b)
	if e != nil {
		apiError(w, e)
		return
	}
	jsonReply(w, 202, j)
}
func (a *App) benchmark(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Text   string `json:"text"`
		Source string `json:"sourceLanguage"`
		Target string `json:"targetLanguage"`
	}
	if e := readJSON(w, r, &in); e != nil {
		apiError(w, e)
		return
	}
	if len(in.Text) == 0 || len(in.Text) > 8192 || !languagePattern.MatchString(in.Source) || !languagePattern.MatchString(in.Target) {
		apiError(w, errors.New("시험 문장과 언어를 확인하세요"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
	defer cancel()
	values := []int64{}
	var output string
	for i := 0; i < 3; i++ {
		start := time.Now()
		var e error
		output, e = a.pipeline.engine.Translate(ctx, in.Text, in.Source, in.Target, "", a.pipeline.glossary())
		if e != nil {
			apiError(w, e)
			return
		}
		values = append(values, time.Since(start).Milliseconds())
	}
	jsonReply(w, 200, map[string]any{"measured": true, "samples": values, "translation": output, "scope": "준비된 모델의 단일 문장 번역 3회. 음성 인식·합성·동시 청취 부하는 별도 시험이 필요합니다", "targetMillis": 2000})
}

var _ = fmt.Sprintf
