package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// These tests exercise startup lifecycle and private health HTTP boundaries.
// They do not execute Windows runtimes or explain the actual C6 boot failure.
func TestProcessHealthAlreadyExitedDoesNotSendHTTP(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	done := make(chan error, 1)
	done <- errors.New("PRIVATE_PROCESS_PATH_CANARY")
	close(done)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	started := time.Now()
	err := waitForProcessHealth(ctx, server.URL+"/PRIVATE_HEALTH_CAPABILITY", "PRIVATE_BEARER_CANARY", done)
	if !errors.Is(err, errProcessExitedBeforeHealth) || calls.Load() != 0 {
		t.Fatalf("already-exited process performed health HTTP or returned the wrong status: calls=%d", calls.Load())
	}
	assertHealthFailurePrivate(t, err, server.URL)
	if time.Since(started) > time.Second {
		t.Fatal("already-exited process waited for the startup deadline")
	}
}

func TestProcessHealthExitDuringUnavailablePollReturnsImmediately(t *testing.T) {
	requested := make(chan struct{})
	var once sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		once.Do(func() { close(requested) })
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()
	done := make(chan error, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- waitForProcessHealth(ctx, server.URL, "PRIVATE_BEARER_CANARY", done) }()
	awaitHealthSignal(t, requested)
	done <- errors.New("PRIVATE_PROCESS_PATH_CANARY")
	close(done)
	select {
	case err := <-result:
		if !errors.Is(err, errProcessExitedBeforeHealth) {
			t.Fatal("503 polling did not report process exit")
		}
		assertHealthFailurePrivate(t, err, server.URL)
	case <-time.After(time.Second):
		t.Fatal("503 polling ignored process exit")
	}
}

func TestProcessHealthExitCancelsInFlightHTTPRequest(t *testing.T) {
	requested, requestCancelled := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		close(requested)
		<-r.Context().Done()
		close(requestCancelled)
	}))
	defer server.Close()
	done := make(chan error, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- waitForProcessHealth(ctx, server.URL, "", done) }()
	awaitHealthSignal(t, requested)
	done <- errors.New("PRIVATE_PROCESS_PATH_CANARY")
	close(done)
	select {
	case err := <-result:
		if !errors.Is(err, errProcessExitedBeforeHealth) {
			t.Fatal("in-flight health request did not fail on process exit")
		}
	case <-time.After(time.Second):
		t.Fatal("process exit waited for the HTTP timeout")
	}
	awaitHealthSignal(t, requestCancelled)
}

func TestProcessHealthCancellationDoesNotExposeCustomCause(t *testing.T) {
	requested := make(chan struct{})
	var once sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		once.Do(func() { close(requested) })
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	result := make(chan error, 1)
	go func() { result <- waitForProcessHealth(ctx, server.URL, "", nil) }()
	awaitHealthSignal(t, requested)
	cancel(errors.New("PRIVATE_CONTEXT_CAUSE_CANARY"))
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("caller cancellation was not preserved")
		}
		assertHealthFailurePrivate(t, err, server.URL)
	case <-time.After(time.Second):
		t.Fatal("health polling ignored caller cancellation")
	}
}

func TestProcessHealthLocalBoundCancelsStalledRequestBeforeLiveParent(t *testing.T) {
	requested, requestCancelled := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		close(requested)
		<-r.Context().Done()
		close(requestCancelled)
	}))
	defer server.Close()
	parent, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	result := make(chan error, 1)
	go func() {
		result <- waitForProcessHealthBounded(parent, server.URL+"/PRIVATE_HEALTH_CAPABILITY", "PRIVATE_BEARER_CANARY", nil, 40*time.Millisecond)
	}()
	awaitHealthSignal(t, requested)
	select {
	case err := <-result:
		if !errors.Is(err, context.DeadlineExceeded) || parent.Err() != nil {
			t.Fatal("local runtime health bound was lost or cancelled the live parent")
		}
		assertHealthFailurePrivate(t, err, server.URL)
	case <-time.After(time.Second):
		t.Fatal("stalled health request consumed the enlarged parent startup budget")
	}
	awaitHealthSignal(t, requestCancelled)
}

func TestProcessHealthEarlierParentDeadlineWinsOverLocalBound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()
	parent, cancel := context.WithTimeout(t.Context(), 30*time.Millisecond)
	defer cancel()
	err := waitForProcessHealthBounded(parent, server.URL, "PRIVATE_BEARER_CANARY", nil, time.Second)
	if !errors.Is(err, context.DeadlineExceeded) || !errors.Is(parent.Err(), context.DeadlineExceeded) {
		t.Fatal("runtime health allowance extended the caller's earlier deadline")
	}
	assertHealthFailurePrivate(t, err, server.URL)
}

func TestProcessHealthRedirectDoesNotSendBearerToAnotherEndpoint(t *testing.T) {
	var redirected atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		redirected.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer other.Close()
	done := make(chan error)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+"/PRIVATE_HEALTH_CAPABILITY", http.StatusFound)
		close(done)
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	err := waitForProcessHealth(ctx, server.URL, "PRIVATE_BEARER_CANARY", done)
	if !errors.Is(err, errProcessExitedBeforeHealth) || redirected.Load() != 0 {
		t.Fatal("health redirect was followed or process exit was ignored")
	}
	assertHealthFailurePrivate(t, err, other.URL)
}

func TestProcessHealthReadyPreservesRunningProcessAndBearer(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.Header.Get("Authorization") != "Bearer PRIVATE_BEARER_CANARY" {
			t.Error("runtime health authentication was not preserved")
		}
		if calls.Add(1) == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	done := make(chan error)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := waitForProcessHealth(ctx, server.URL, "PRIVATE_BEARER_CANARY", done); err != nil {
		t.Fatalf("live process never became ready: %v", err)
	}
	if calls.Load() != 2 || ctx.Err() != nil {
		t.Fatal("readiness should retry once and preserve the caller/process context")
	}
	close(done)
}

func awaitHealthSignal(t *testing.T, signal <-chan struct{}) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(2 * time.Second):
		t.Fatal("health lifecycle fixture did not reach the expected boundary")
	}
}

func assertHealthFailurePrivate(t *testing.T, err error, endpoint string) {
	t.Helper()
	if err == nil {
		t.Fatal("expected safe health failure")
	}
	for _, canary := range []string{endpoint, "PRIVATE_PROCESS_PATH_CANARY", "PRIVATE_BEARER_CANARY", "PRIVATE_HEALTH_CAPABILITY", "PRIVATE_CONTEXT_CAUSE_CANARY"} {
		if strings.Contains(err.Error(), canary) {
			t.Fatal("health failure disclosed private diagnostic data")
		}
	}
}
