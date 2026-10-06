package main

import (
	"context"
	"errors"
	"time"
)

// Only fixed stage names and bounded error text are exposed. Runtime URLs,
// access capabilities and native stdout/stderr are never included here.
func (e *Engine) startupStep(stage, language string, run func() error) error {
	started := time.Now()
	e.readyMu.Lock()
	e.ready.StartupStage = stage
	e.ready.StartupStartedAt = started.UTC()
	e.readyMu.Unlock()
	err := run()
	check := setupCheck{Stage: stage, Language: language, Passed: err == nil, Millis: time.Since(started).Milliseconds()}
	if err != nil {
		// The full failure remains in the existing local engine error path.
		// This receipt is sufficient to distinguish the failing component.
		check.Error = "이 단계의 준비 확인에 실패했습니다"
		var workerErr *ttsWorkerStartupError
		if errors.As(err, &workerErr) {
			check.Error = workerErr.Error()
		}
	}
	e.readyMu.Lock()
	e.ready.StartupChecks = append(e.ready.StartupChecks, check)
	e.ready.StartupStage = ""
	e.ready.StartupStartedAt = time.Time{}
	e.readyMu.Unlock()
	return err
}

// Child progress reports contain only the bounded, ordered phase enum. A
// subsequent phase means the prior phase completed; final readiness still
// requires the worker handshake and every language's first-synthesis check.
func (e *Engine) startTTSWorkerTracked(ctx context.Context, init nativeTTSInit) (*ttsWorker, error) {
	progress, finish := e.ttsStartupProgress()
	worker, err := startTTSWorkerWithProgress(ctx, init, progress)
	finish(err)
	return worker, err
}

func (e *Engine) ttsStartupProgress() (func(ttsStartupPhase), func(error)) {
	var active ttsStartupPhase
	var started time.Time
	record := func(err error) {
		if active.title() == "" {
			return
		}
		check := setupCheck{Stage: active.title(), Passed: err == nil, Millis: time.Since(started).Milliseconds()}
		if err != nil {
			check.Error = "이 단계의 준비 확인에 실패했습니다"
			var failure *ttsWorkerStartupError
			if errors.As(err, &failure) {
				check.Error = failure.Error()
			}
		}
		e.readyMu.Lock()
		e.ready.StartupChecks = append(e.ready.StartupChecks, check)
		e.readyMu.Unlock()
	}
	progress := func(phase ttsStartupPhase) {
		record(nil)
		active, started = phase, time.Now()
		e.readyMu.Lock()
		e.ready.VoiceStartupPhase = string(phase)
		e.ready.VoiceStartupStartedAt = started.UTC()
		e.readyMu.Unlock()
	}
	finish := func(err error) {
		record(err)
		active = ""
		e.readyMu.Lock()
		e.ready.VoiceStartupPhase = ""
		e.ready.VoiceStartupStartedAt = time.Time{}
		e.readyMu.Unlock()
	}
	return progress, finish
}
