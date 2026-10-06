package main

import "time"

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
	}
	e.readyMu.Lock()
	e.ready.StartupChecks = append(e.ready.StartupChecks, check)
	e.ready.StartupStage = ""
	e.ready.StartupStartedAt = time.Time{}
	e.readyMu.Unlock()
	return err
}
