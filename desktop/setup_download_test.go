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
	for _, kind := range []string{"hash", "size", "range", "403", "unknown", "pre-canceled"} {
		t.Run(kind, func(t *testing.T) {
			am, data := setupDownloadTestManager(t)
			calls, retries := 0, 0
			setupDownloadTestTransport(t, func(*http.Request) (*http.Response, error) {
				calls++
				switch kind {
				case "hash":
					return setupDownloadTestResponse(bytes.Repeat([]byte{'x'}, len(data))), nil
				case "size":
					return setupDownloadTestResponse(data[:8]), nil
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
