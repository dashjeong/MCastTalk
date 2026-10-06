package main

import (
	"errors"
	"strings"
	"testing"
	"time"
)

func TestEngineStartupProgressShowsActiveStageAndIndependentReceipts(t *testing.T) {
	e := NewEngine(t.TempDir())
	entered, release := make(chan struct{}), make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- e.startupStep("음성 인식 모델 첫 추론", "en", func() error {
			close(entered)
			<-release
			return errors.New("private capability URL must not enter receipt")
		})
	}()
	<-entered
	time.Sleep(2 * time.Millisecond)
	active := e.Ready()
	if active.StartupStage != "음성 인식 모델 첫 추론" || active.StartupMillis <= 0 || active.StartupStartedAt.IsZero() || active.STTReady {
		t.Fatalf("pending first inference was silent or marked ready: %+v", active)
	}
	close(release)
	if err := <-done; err == nil {
		t.Fatal("stage failure lost")
	}
	status := e.Ready()
	if status.StartupStage != "" || len(status.StartupChecks) != 1 || status.StartupChecks[0].Passed || status.StartupChecks[0].Language != "en" || strings.Contains(status.StartupChecks[0].Error, "private") {
		t.Fatalf("stage receipt failed to identify the stage or disclosed native details: %+v", status)
	}
	status.StartupChecks[0].Passed = true
	if e.Ready().StartupChecks[0].Passed {
		t.Fatal("caller could mutate the engine startup receipt")
	}
	err := e.finishStartup(t.Context(), defaultConfig(), []string{"startup failed"})
	status = e.Ready()
	if err == nil || len(status.StartupChecks) != 1 || status.STTReady || status.TranslationReady || status.TTSReady {
		t.Fatal("failed startup discarded actionable evidence or retained false readiness")
	}
}
