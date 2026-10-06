package main

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Model files can take hours to download on a slow connection. Bound stalled
// requests, rather than imposing a total duration that discards healthy work.
const artifactDownloadIdleTimeout = 2 * time.Minute

type artifactHTTPError struct {
	Status     int
	RetryAfter time.Duration
}

func (e *artifactHTTPError) Error() string {
	return fmt.Sprintf("다운로드 서버가 HTTP %d 응답을 반환했습니다", e.Status)
}

func (e *artifactHTTPError) retryable() bool {
	return e.Status == http.StatusRequestTimeout || e.Status == http.StatusTooManyRequests || e.Status == http.StatusInternalServerError || e.Status == http.StatusBadGateway || e.Status == http.StatusServiceUnavailable || e.Status == http.StatusGatewayTimeout
}

func boundedArtifactRetryAfter(value string, now time.Time) time.Duration {
	value = strings.TrimSpace(value)
	var delay time.Duration
	if seconds, err := strconv.ParseInt(value, 10, 64); err == nil {
		if seconds <= 0 {
			return 0
		}
		if seconds >= 30 {
			return 30 * time.Second
		}
		delay = time.Duration(seconds) * time.Second
	} else if at, err := http.ParseTime(value); err == nil {
		delay = at.Sub(now)
	}
	if delay < 0 {
		return 0
	}
	if delay > 30*time.Second {
		return 30 * time.Second
	}
	return delay
}

type artifactIdleError struct{}

func (*artifactIdleError) Error() string {
	return "다운로드가 2분 동안 진행되지 않았습니다. 받은 파일을 보관하고 연결을 다시 시도합니다"
}
func (*artifactIdleError) Timeout() bool   { return true }
func (*artifactIdleError) Temporary() bool { return true }

func artifactDownloadClient() *http.Client {
	transport := http.DefaultClient.Transport
	if transport == nil {
		transport = http.DefaultTransport
	}
	if base, ok := transport.(*http.Transport); ok {
		bounded := base.Clone()
		bounded.ResponseHeaderTimeout = 45 * time.Second
		bounded.TLSHandshakeTimeout = 15 * time.Second
		transport = bounded
	}
	// Preserve the configured transport/proxy, redirects and certificate checks.
	// Protocol fixtures inject a transport instead of requiring a live service.
	return &http.Client{Transport: transport, CheckRedirect: http.DefaultClient.CheckRedirect}
}

func artifactDownloadContext(parent context.Context, idle time.Duration) (context.Context, func(), func()) {
	ctx, cancel := context.WithCancel(parent)
	timer := time.AfterFunc(idle, cancel)
	touch := func() { timer.Reset(idle) }
	stop := func() { timer.Stop(); cancel() }
	return ctx, touch, stop
}
