package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

// Tiny local transport fixtures verify retry/integrity behavior, not actual
// HF/GitHub availability, model quality, or Windows capacity.
func setupDownloadTestManager(t *testing.T) (*AssetManager, []byte) {
	t.Helper()
	data := []byte("GGUF tiny verified retry fixture")
	hash := sha256.Sum256(data)
	am := NewAssetManager(t.TempDir(), nil)
	am.registry = append(am.registry, Artifact{ID: "setup-retry-fixture", Format: "gguf", URL: "https://huggingface.co/fixture/model.gguf", Bytes: int64(len(data)), SHA256: hex.EncodeToString(hash[:])})
	return am, data
}
func setupDownloadTestTransport(t *testing.T, f func(*http.Request) (*http.Response, error)) {
	t.Helper()
	old := http.DefaultClient.Transport
	http.DefaultClient.Transport = &mockTransport{roundTripFunc: f}
	t.Cleanup(func() { http.DefaultClient.Transport = old })
}
func setupDownloadTestResponse(data []byte) *http.Response {
	return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(data)), Header: make(http.Header)}
}

func TestSetupDownloadDNSRetrySucceeds(t *testing.T) {
	am, data := setupDownloadTestManager(t)
	calls := 0
	setupDownloadTestTransport(t, func(*http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return nil, &net.DNSError{Err: "no such host", Name: "huggingface.co", IsNotFound: true}
		}
		return setupDownloadTestResponse(data), nil
	})
	var retries []int
	if err := downloadSetupArtifact(context.Background(), "setup-retry-fixture", am, func(n int) { retries = append(retries, n) }); err != nil {
		t.Fatal(err)
	}
	if calls != 2 || !reflect.DeepEqual(retries, []int{2}) {
		t.Fatalf("calls=%d retries=%v", calls, retries)
	}
	if err := verifyModel(context.Background(), filepath.Join(am.dataDir, "models", "setup-retry-fixture.gguf"), am.registry[len(am.registry)-1].SHA256, int64(len(data)), nil); err != nil {
		t.Fatal(err)
	}
}

func TestSetupDownloadDNSExhaustionAndCancelableBackoff(t *testing.T) {
	for _, cancelBackoff := range []bool{false, true} {
		t.Run(fmt.Sprintf("cancel-%v", cancelBackoff), func(t *testing.T) {
			am, _ := setupDownloadTestManager(t)
			calls := 0
			setupDownloadTestTransport(t, func(*http.Request) (*http.Response, error) {
				calls++
				return nil, &net.DNSError{Err: "no such host", Name: "huggingface.co", IsNotFound: true}
			})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			var retries []int
			err := downloadSetupArtifact(ctx, "setup-retry-fixture", am, func(n int) {
				retries = append(retries, n)
				if cancelBackoff {
					cancel()
				}
			})
			if cancelBackoff {
				if !errors.Is(err, context.Canceled) || calls != 1 || !reflect.DeepEqual(retries, []int{2}) {
					t.Fatalf("cancel err=%v calls=%d retries=%v", err, calls, retries)
				}
			} else {
				var dns *net.DNSError
				if !errors.As(err, &dns) || calls != 3 || !reflect.DeepEqual(retries, []int{2, 3}) {
					t.Fatalf("exhaustion err=%v calls=%d retries=%v", err, calls, retries)
				}
			}
		})
	}
}

type setupDownloadInterruptedBody struct {
	data []byte
	read bool
}

func (b *setupDownloadInterruptedBody) Read(p []byte) (int, error) {
	if b.read {
		return 0, io.ErrUnexpectedEOF
	}
	b.read = true
	return copy(p, b.data), io.ErrUnexpectedEOF
}
func (*setupDownloadInterruptedBody) Close() error { return nil }

func TestSetupDownloadUnexpectedEOFResumesSamePart(t *testing.T) {
	am, data := setupDownloadTestManager(t)
	calls := 0
	setupDownloadTestTransport(t, func(req *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return &http.Response{StatusCode: 200, Body: &setupDownloadInterruptedBody{data: data[:8]}, Header: make(http.Header)}, nil
		}
		if req.Header.Get("Range") != "bytes=8-" {
			t.Errorf("part not reused: %q", req.Header.Get("Range"))
		}
		r := setupDownloadTestResponse(data[8:])
		r.StatusCode = 206
		r.Header.Set("Content-Range", fmt.Sprintf("bytes 8-%d/%d", len(data)-1, len(data)))
		return r, nil
	})
	if err := downloadSetupArtifact(context.Background(), "setup-retry-fixture", am, nil); err != nil || calls != 2 {
		t.Fatalf("resume err=%v calls=%d", err, calls)
	}
}

func TestSetupDownloadIntegrityHTTPAndInputErrorsDoNotRetry(t *testing.T) {
	for _, kind := range []string{"hash", "oversize", "range", "403", "unknown", "pre-canceled"} {
		t.Run(kind, func(t *testing.T) {
			am, data := setupDownloadTestManager(t)
			calls, retries := 0, 0
			setupDownloadTestTransport(t, func(*http.Request) (*http.Response, error) {
				calls++
				switch kind {
				case "hash":
					return setupDownloadTestResponse(bytes.Repeat([]byte{'x'}, len(data))), nil
				case "oversize":
					return setupDownloadTestResponse(append(append([]byte(nil), data...), 'x')), nil
				case "range":
					r := setupDownloadTestResponse(data)
					r.StatusCode = 206
					r.Header.Set("Content-Range", "bytes 1-2/3")
					return r, nil
				case "403":
					r := setupDownloadTestResponse(nil)
					r.StatusCode = 403
					return r, nil
				}
				return setupDownloadTestResponse(data), nil
			})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			id := "setup-retry-fixture"
			wantCalls := 1
			if kind == "unknown" {
				id = "does-not-exist"
				wantCalls = 0
			}
			if kind == "pre-canceled" {
				cancel()
				wantCalls = 0
			}
			if err := downloadSetupArtifact(ctx, id, am, func(int) { retries++ }); err == nil || calls != wantCalls || retries != 0 {
				t.Fatalf("non-network retried: err=%v calls=%d retries=%d", err, calls, retries)
			}
			if kind == "hash" {
				if _, err := os.Stat(filepath.Join(am.dataDir, "downloads", id+".part")); !os.IsNotExist(err) {
					t.Fatalf("hash-failed part preserved: %v", err)
				}
			}
		})
	}
	if setupDownloadRetryable(&url.Error{Op: "Get", URL: "bad", Err: errors.New("unsupported protocol")}) {
		t.Fatal("URL protocol error classified as network failure")
	}
	if !setupDownloadRetryable(&net.OpError{Op: "read", Net: "tcp", Err: context.DeadlineExceeded}) {
		t.Fatal("network request timeout not retryable")
	}
}

func TestSetupDownloadCleanEOFResumesSamePart(t *testing.T) {
	am, data := setupDownloadTestManager(t)
	calls := 0
	setupDownloadTestTransport(t, func(req *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return setupDownloadTestResponse(data[:8]), nil
		}
		if req.Header.Get("Range") != "bytes=8-" {
			t.Errorf("clean EOF part not reused: %q", req.Header.Get("Range"))
		}
		r := setupDownloadTestResponse(data[8:])
		r.StatusCode = 206
		r.Header.Set("Content-Range", fmt.Sprintf("bytes 8-%d/%d", len(data)-1, len(data)))
		return r, nil
	})
	if err := downloadSetupArtifact(context.Background(), "setup-retry-fixture", am, nil); err != nil || calls != 2 {
		t.Fatalf("chunked EOF resume err=%v calls=%d", err, calls)
	}
}

func TestSetupDownloadTransientHTTPStatusesRetry(t *testing.T) {
	for _, status := range []int{408, 429, 500, 502, 503, 504} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			am, data := setupDownloadTestManager(t)
			calls := 0
			setupDownloadTestTransport(t, func(*http.Request) (*http.Response, error) {
				calls++
				if calls == 1 {
					r := setupDownloadTestResponse(nil)
					r.StatusCode = status
					return r, nil
				}
				return setupDownloadTestResponse(data), nil
			})
			if err := downloadSetupArtifact(context.Background(), "setup-retry-fixture", am, nil); err != nil || calls != 2 {
				t.Fatalf("HTTP %d recovery err=%v calls=%d", status, err, calls)
			}
		})
	}
	for _, status := range []int{401, 403, 404, 410, 416, 501} {
		if setupDownloadRetryable(&artifactHTTPError{Status: status}) {
			t.Fatalf("permanent HTTP %d marked retryable", status)
		}
	}
	if setupDownloadRetryable(errors.New("invalid hash 429 marker")) {
		t.Fatal("message text caused a retry of an integrity error")
	}
}

func TestArtifactDownloadStalledResponseIsBoundedAndRetainsPart(t *testing.T) {
	am, data := setupDownloadTestManager(t)
	am.downloadIdleTimeout = 30 * time.Millisecond
	setupDownloadTestTransport(t, func(req *http.Request) (*http.Response, error) {
		<-req.Context().Done()
		return nil, req.Context().Err()
	})
	part := filepath.Join(am.dataDir, "downloads", "setup-retry-fixture.part")
	if err := os.MkdirAll(filepath.Dir(part), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(part, data[:8], 0600); err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	err := am.Download(context.Background(), "setup-retry-fixture")
	var timeout *artifactIdleError
	if !errors.As(err, &timeout) || !setupDownloadRetryable(err) || time.Since(started) > time.Second {
		t.Fatalf("stalled request remained unbounded: %v", err)
	}
	retained, readErr := os.ReadFile(part)
	if readErr != nil || !bytes.Equal(retained, data[:8]) || am.Progress()[0].State != "failed" {
		t.Fatal("idle timeout discarded the partial file or did not expose a failure")
	}
}

func TestArtifactDownloadIdleDeadlineMovesWithProgress(t *testing.T) {
	ctx, touch, stop := artifactDownloadContext(context.Background(), 250*time.Millisecond)
	defer stop()
	for range 6 {
		time.Sleep(100 * time.Millisecond)
		touch()
		if ctx.Err() != nil {
			t.Fatal("healthy progress was subject to a total download timeout")
		}
	}
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("idle deadline never expired after progress stopped")
	}
}

func TestArtifactRetryAfterIsBoundedAndCancelable(t *testing.T) {
	now := time.Date(2026, 10, 6, 12, 0, 0, 0, time.UTC)
	for value, want := range map[string]time.Duration{
		"": 0, "invalid": 0, "-1": 0, "1": time.Second, "9999999999": 30 * time.Second,
		now.Add(2 * time.Second).Format(http.TimeFormat): 2 * time.Second,
		now.Add(time.Hour).Format(http.TimeFormat):       30 * time.Second,
		now.Add(-time.Hour).Format(http.TimeFormat):      0,
	} {
		if got := boundedArtifactRetryAfter(value, now); got != want {
			t.Fatalf("Retry-After %q=%v want %v", value, got, want)
		}
	}
	am, _ := setupDownloadTestManager(t)
	calls := 0
	setupDownloadTestTransport(t, func(*http.Request) (*http.Response, error) {
		calls++
		r := setupDownloadTestResponse(nil)
		r.StatusCode = http.StatusTooManyRequests
		r.Header.Set("Retry-After", "30")
		return r, nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	started := time.Now()
	err := downloadSetupArtifact(ctx, "setup-retry-fixture", am, func(int) { cancel() })
	if !errors.Is(err, context.Canceled) || calls != 1 || time.Since(started) > time.Second {
		t.Fatal("server-requested backoff was not cancellable")
	}
}
