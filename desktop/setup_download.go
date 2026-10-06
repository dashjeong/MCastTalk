package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/url"
	"time"
)

// Download retains AssetManager's verified-import and resumable .part guards.
// The callback receives the next attempt number (2 or 3).
func downloadSetupArtifact(ctx context.Context, id string, assets *AssetManager, onRetry func(attempt int)) error {
	if assets == nil {
		return errors.New("asset manager unavailable")
	}
	for attempt := 1; attempt <= 3; attempt++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := assets.Download(ctx, id)
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if attempt == 3 || !setupDownloadRetryable(err) {
			return err
		}
		if onRetry != nil {
			onRetry(attempt + 1)
		}
		backoff := time.Duration(attempt) * 500 * time.Millisecond
		var status *artifactHTTPError
		if errors.As(err, &status) && status.RetryAfter > backoff {
			backoff = status.RetryAfter
		}
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
	return errors.New("download attempts exhausted")
}

func setupDownloadRetryable(err error) bool {
	if errors.Is(err, context.Canceled) {
		return false
	}
	if errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}
	var status *artifactHTTPError
	if errors.As(err, &status) {
		return status.retryable()
	}
	// url.Error itself implements net.Error even for malformed URL/protocol
	// errors. Classify its cause instead of broadening those into retries.
	for {
		var requestErr *url.Error
		if !errors.As(err, &requestErr) {
			break
		}
		err = requestErr.Err
	}
	var networkErr net.Error
	return errors.As(err, &networkErr)
}
