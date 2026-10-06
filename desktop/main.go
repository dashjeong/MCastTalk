package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"syscall"
	"time"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--tts-worker" {
		if runNativeTTSWorker() != nil {
			os.Exit(1)
		}
		return
	}
	if e := run(); e != nil {
		log.Print(e)
		os.Exit(1)
	}
}
func run() error {
	base, e := os.UserConfigDir()
	if e != nil {
		return e
	}
	data := flag.String("data-dir", filepath.Join(base, "MCastTalkDesktop"), "자료·모델·런타임을 보관할 폴더")
	adminBind := flag.String("admin-listen", "127.0.0.1:8790", "관리 화면 주소 (loopback only)")
	noBrowser := flag.Bool("no-browser", false, "브라우저 자동 열기 생략")
	version := flag.Bool("version", false, "버전 확인")
	diagnose := flag.Bool("diagnose", false, "PC 진단 결과 JSON")
	speechProof := flag.String("native-tts-proof", "", "고정 원본 음성 패키지 폴더로 별도 자료 경로에서 로컬 음성 진단")
	tokenFile := flag.String("admin-token-file", "", "로컬 자동 시험용 관리 토큰 파일 (개인 읽기 전용)")
	flag.Parse()
	if *speechProof != "" {
		explicitData := false
		flag.Visit(func(f *flag.Flag) {
			if f.Name == "data-dir" {
				explicitData = true
			}
		})
		if !explicitData {
			return fmt.Errorf("음성 진단은 명시적인 별도 -data-dir 경로가 필요합니다")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		return runBundledTTSProof(ctx, *data, *speechProof)
	}
	if *version {
		fmt.Println(Version)
		return nil
	}
	if *diagnose {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return json.NewEncoder(os.Stdout).Encode(Diagnose(ctx, *data))
	}
	host, _, e := net.SplitHostPort(*adminBind)
	if e != nil || !loopback(host) {
		return fmt.Errorf("관리 화면은 localhost에만 열 수 있습니다")
	}
	store, e := openStore(*data)
	if e != nil {
		return e
	}
	defer store.db.Close()
	logFile, e := os.OpenFile(filepath.Join(*data, "application.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if e != nil {
		return e
	}
	defer logFile.Close()
	log.SetOutput(logFile)
	cfg := store.config()
	if cfg.SpeakerPIN == "" {
		cfg.SpeakerPIN = newID()[:8]
		if e = store.saveConfig(cfg); e != nil {
			return e
		}
	}
	if e = validateConfig(cfg); e != nil {
		return e
	}
	engine := NewEngine(*data)
	pipeline := newPipeline(store, engine)
	defer pipeline.close()
	app := &App{store: store, pipeline: pipeline, adminToken: newID() + newID(), cfg: cfg, requests: newLimiter()}
	engine.onlineConfig = func() OnlineConfig { return app.config().Online }
	app.assets = NewAssetManager(*data, func(asset InstalledAsset) error { return store.put("assets", asset.ID, asset) })
	if *tokenFile != "" {
		if e = atomicFile(*tokenFile, []byte(app.adminToken)); e != nil {
			return e
		}
		if e = os.Chmod(*tokenFile, 0600); e != nil {
			return e
		}
	}
	interrupted, e := store.recover()
	if e != nil {
		return e
	}
	if e = app.classroomService().recoverChannels(); e != nil {
		return e
	}
	filtered := []Session{}
	for _, s := range interrupted {
		if s.Kind == "broadcast" || s.Kind == "note" {
			filtered = append(filtered, s)
		}
	}
	interrupted = filtered
	if len(interrupted) > 0 && cfg.AutoResume {
		latest := interrupted[0]
		for _, s := range interrupted {
			if s.UpdatedAt.After(latest.UpdatedAt) {
				latest = s
			}
		}
		if e = pipeline.begin(latest); e != nil {
			return e
		}
		pipeline.report(fmt.Errorf("재시작 전 기록을 복원했습니다. 녹음 공백 %d회. 마이크는 직접 다시 켜세요", latest.GapCount))
	}
	pipeline.startWorkers()
	if installed := store.assets(); cfg.AutoResume && installed[cfg.TranslationModel].Path != "" && installed[cfg.STTModel].Path != "" {
		go func() {
			app.envMu.RLock()
			defer app.envMu.RUnlock()
			if app.envRestart.Load() {
				return
			}
			controller := app.setupController()
			controller.mu.Lock()
			state := controller.operation.State
			controller.mu.Unlock()
			if state == "running" || state == "failed" || state == "cancelled" || state == "interrupted" {
				return
			}
			if ready := engine.Ready(); ready.TranslationReady || ready.STTReady {
				return
			}
			ctx, cancel := context.WithTimeout(pipeline.ctx, 120*time.Second)
			defer cancel()
			if err := engine.Start(ctx, cfg, installed); err != nil {
				pipeline.report(fmt.Errorf("저장된 모델 자동 시작: %w", err))
			}
		}()
	}
	go func() {
		ctx, cancel := context.WithTimeout(pipeline.ctx, 25*time.Second)
		defer cancel()
		d := Diagnose(ctx, *data)
		app.diagMu.Lock()
		app.diagnostic = d
		app.diagMu.Unlock()
	}()
	adminServer := &http.Server{Addr: *adminBind, Handler: app.adminHandler(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384}
	adminListener, e := net.Listen("tcp", *adminBind)
	if e != nil {
		return fmt.Errorf("관리 화면 포트를 열 수 없습니다: %w", e)
	}
	defer adminListener.Close()
	publicServer := &http.Server{Addr: cfg.PublicBind, Handler: app.publicHandler(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	publicListener, e := net.Listen("tcp", cfg.PublicBind)
	if e != nil {
		pipeline.report(fmt.Errorf("청취 서버 포트를 열지 못했습니다. 설정 변경 후 다시 실행하세요: %w", e))
	} else {
		defer publicListener.Close()
		go func() {
			var e error
			if cfg.TLSCert != "" {
				e = publicServer.ServeTLS(publicListener, cfg.TLSCert, cfg.TLSKey)
			} else {
				e = publicServer.Serve(publicListener)
			}
			if e != nil && e != http.ErrServerClosed {
				pipeline.report(e)
			}
		}()
	}
	adminErrors := make(chan error, 1)
	go func() { adminErrors <- adminServer.Serve(adminListener) }()
	if !*noBrowser && runtime.GOOS == "windows" {
		cmd := exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", "http://"+*adminBind+"/#token="+app.adminToken)
		if e = cmd.Start(); e != nil {
			pipeline.report(fmt.Errorf("브라우저를 열지 못했습니다. http://%s에 접속하세요", *adminBind))
		} else {
			go cmd.Wait()
		}
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	select {
	case <-stop:
	case e = <-adminErrors:
		if e != http.ErrServerClosed {
			return e
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = adminServer.Shutdown(ctx)
	_ = publicServer.Shutdown(ctx)
	return nil
}
