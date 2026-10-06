package main

import (
	"context"
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

func TestEngineStartupReceiptRetainsOnlyClosedWorkerFailure(t *testing.T) {
	e := NewEngine(t.TempDir())
	failure := &ttsWorkerStartupError{failure: ttsStartupDeadline, cause: context.DeadlineExceeded}
	err := e.startupStep("언어별 음성 모델 로드", "", func() error {
		return errors.Join(errors.New(ttsPrivateCanary), failure)
	})
	status := e.Ready()
	if !errors.Is(err, context.DeadlineExceeded) || len(status.StartupChecks) != 1 || status.StartupChecks[0].Passed || status.TTSReady {
		t.Fatal("typed worker deadline was lost or treated as readiness")
	}
	if status.StartupChecks[0].Error != failure.Error() || strings.Contains(status.StartupChecks[0].Error, ttsPrivateCanary) {
		t.Fatal("startup receipt lost fixed failure code or exposed arbitrary error text")
	}
}

func TestEngineStartupReceiptRetainsOnlyContextSentinel(t *testing.T) {
	for _, sentinel := range []error{context.DeadlineExceeded, context.Canceled} {
		t.Run(sentinel.Error(), func(t *testing.T) {
			e := NewEngine(t.TempDir())
			err := e.startupStep("번역 모델 해시 확인", "", func() error {
				return errors.Join(errors.New(ttsPrivateCanary), sentinel)
			})
			status := e.Ready()
			if !errors.Is(err, sentinel) || len(status.StartupChecks) != 1 || status.StartupChecks[0].Passed || status.TranslationReady {
				t.Fatal("context failure was lost or published as readiness")
			}
			if status.StartupChecks[0].Error != sentinel.Error() || strings.Contains(status.StartupChecks[0].Error, ttsPrivateCanary) {
				t.Fatal("context receipt omitted its fixed status or exposed arbitrary private data")
			}
		})
	}
}

func TestEngineVoiceProgressKeepsOverallStageAndNoFalseReadiness(t *testing.T) {
	e := NewEngine(t.TempDir())
	entered, release := make(chan struct{}), make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- e.startupStep("언어별 음성 모델 로드", "", func() error {
			progress, finish := e.ttsStartupProgress()
			progress(ttsPhaseRuntimeVerify)
			progress(ttsPhaseSuperVerify)
			close(entered)
			<-release
			err := &ttsWorkerStartupError{failure: ttsStartupDeadline, cause: context.DeadlineExceeded, phase: ttsPhaseSuperVerify}
			finish(err)
			return err
		})
	}()
	<-entered
	time.Sleep(2 * time.Millisecond)
	active := e.Ready()
	if active.StartupStage != "언어별 음성 모델 로드" || active.VoiceStartupPhase != string(ttsPhaseSuperVerify) || active.VoiceStartupMillis <= 0 || active.VoiceStartupStartedAt.IsZero() || active.TTSReady {
		t.Fatalf("voice phase hid overall stage, lacked timing or invented readiness: %+v", active)
	}
	if len(active.StartupChecks) != 1 || !active.StartupChecks[0].Passed || active.StartupChecks[0].Stage != ttsPhaseRuntimeVerify.title() {
		t.Fatal("completed child verification phase did not retain its independent receipt")
	}
	close(release)
	if !errors.Is(<-done, context.DeadlineExceeded) {
		t.Fatal("voice phase lost typed failure")
	}
	status := e.Ready()
	if status.VoiceStartupPhase != "" || status.StartupStage != "" || len(status.StartupChecks) != 3 || status.TTSReady {
		t.Fatal("voice failure retained live phase or invented readiness")
	}
	for _, check := range status.StartupChecks[1:] {
		if check.Passed || !strings.Contains(check.Error, "VOICE_WORKER_DEADLINE") || !strings.Contains(check.Error, ttsPhaseSuperVerify.title()) {
			t.Fatal("failed child phase or overall load lost its safe diagnostic")
		}
	}
}
