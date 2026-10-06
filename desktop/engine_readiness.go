package main

import (
	"context"
	"errors"
	"net/http"
	"time"
)

var errProcessExitedBeforeHealth = errors.New("모델 프로세스가 준비 확인 중 종료되었습니다")

// waitForProcessHealth observes cmd.Wait's completion while waiting for the
// private runtime health endpoint. It only cancels health requests; the engine
// owns the process lifetime and cleanup. No process error, URL, key or response
// body becomes part of the returned error.
func waitForProcessHealth(ctx context.Context, url, key string, done <-chan error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case <-done:
		return errProcessExitedBeforeHealth
	default:
	}

	healthCtx, cancel := context.WithCancelCause(ctx)
	watchStopped := make(chan struct{})
	go func() {
		defer close(watchStopped)
		select {
		case <-done:
			// cmd.Wait's error may contain private paths. Discard it.
			cancel(errProcessExitedBeforeHealth)
		case <-healthCtx.Done():
		}
	}()
	defer func() {
		cancel(nil)
		<-watchStopped
	}()

	resultError := func() error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if context.Cause(healthCtx) == errProcessExitedBeforeHealth {
			return errProcessExitedBeforeHealth
		}
		// Do not expose a parent's custom cancellation cause.
		return healthCtx.Err()
	}
	client := http.Client{
		Timeout: 2 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	for {
		if err := resultError(); err != nil {
			return err
		}
		select {
		case <-done:
			return errProcessExitedBeforeHealth
		default:
		}
		req, err := http.NewRequestWithContext(healthCtx, http.MethodGet, url, nil)
		if err != nil {
			return errors.New("모델 준비 확인 요청을 만들 수 없습니다")
		}
		if key != "" {
			req.Header.Set("Authorization", "Bearer "+key)
		}
		resp, requestErr := client.Do(req)
		status := 0
		if resp != nil {
			status = resp.StatusCode
			_ = resp.Body.Close()
		}
		if err := resultError(); err != nil {
			return err
		}
		select {
		case <-done:
			return errProcessExitedBeforeHealth
		default:
		}
		if requestErr == nil && status == http.StatusOK {
			return nil
		}

		// Transport errors and non-ready statuses may be transient during load.
		// The caller's startup deadline bounds the overall wait.
		timer := time.NewTimer(500 * time.Millisecond)
		select {
		case <-healthCtx.Done():
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			return resultError()
		case <-timer.C:
		}
	}
}
