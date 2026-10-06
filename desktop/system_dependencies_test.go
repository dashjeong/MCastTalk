package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Synthetic payloads and adapters verify the install/download control flow.
// They do not establish Windows UAC, actual Microsoft signature acceptance,
// DLL installation, model quality, or Windows runtime readiness.
type systemDependencyTestTransport func(*http.Request) (*http.Response, error)

func TestSystemDependencyLegacyProfileDoesNotWeakenTargetChecks(t *testing.T) {
	fixed := SystemDependencies()[0]
	legacy := fixed
	legacy.RequiredDLLs = append([]string(nil), fixed.RequiredDLLs[:4]...)
	if !systemDependencyProfileCompatible(legacy, fixed) {
		t.Fatal("exact historical installer profile should remain portable")
	}
	for _, mutate := range []func(*SystemDependency){
		func(x *SystemDependency) { x.RequiredDLLs = x.RequiredDLLs[:3] },
		func(x *SystemDependency) { x.RequiredDLLs[0] = "unexpected.dll" },
		func(x *SystemDependency) { x.SHA256 = strings.Repeat("0", 64) },
		func(x *SystemDependency) { x.Version = "0.0.0.0" },
		func(x *SystemDependency) { x.URL = "https://invalid.example/installer.exe" },
	} {
		changed := legacy
		changed.RequiredDLLs = append([]string(nil), legacy.RequiredDLLs...)
		mutate(&changed)
		if systemDependencyProfileCompatible(changed, fixed) {
			t.Fatal("changed historical policy or installer identity was accepted")
		}
	}
	if len(SystemDependencies()[0].RequiredDLLs) != 5 {
		t.Fatal("legacy compatibility mutated current target DLL requirements")
	}
}

func (f systemDependencyTestTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func systemDependencyTestFixture() (SystemDependency, []byte) {
	b := []byte("MZ synthetic installer fixture, never executed")
	d := SystemDependencies()[0]
	h := sha256.Sum256(b)
	d.Bytes, d.SHA256 = int64(len(b)), hex.EncodeToString(h[:])
	return d, b
}

func systemDependencyTestDir(t *testing.T) string {
	t.Helper()
	dir, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	dir = filepath.Join(dir, "한글 Windows 준비")
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	return dir
}

func systemDependencyTestClient(b []byte, calls *int) *http.Client {
	return &http.Client{Transport: systemDependencyTestTransport(func(r *http.Request) (*http.Response, error) {
		*calls++
		if r.Method != http.MethodGet || r.URL.Host != "download.visualstudio.microsoft.com" || r.Header.Get("Accept-Encoding") != "identity" {
			return nil, errors.New("unexpected fixture request")
		}
		return &http.Response{StatusCode: http.StatusOK, ContentLength: int64(len(b)), Body: io.NopCloser(bytes.NewReader(b)), Header: http.Header{}}, nil
	})}
}

func TestSystemDependencyPinnedDescriptorAndVersion(t *testing.T) {
	d := SystemDependencies()[0]
	if d.ID != "vc-redist-x64" || d.Version != "14.51.36247.0" || d.Bytes != 18731856 || d.SHA256 != "843068991daaa1f73ad9f6239bce4d0f6a07a51f18c37ea2a867e9beca71295c" || !strings.HasPrefix(d.URL, "https://download.visualstudio.microsoft.com/") || !strings.HasPrefix(d.TermsURL, "https://visualstudio.microsoft.com/license-terms/") {
		t.Fatalf("pinned installer identity changed: %+v", d)
	}
	want := []string{"VCRUNTIME140.dll", "MSVCP140.dll", "VCRUNTIME140_1.dll", "VCOMP140.DLL", "MSVCP140_1.dll"}
	if !reflect.DeepEqual(d.RequiredDLLs, want) {
		t.Fatalf("runtime dependencies: %v", d.RequiredDLLs)
	}
	d.RequiredDLLs[0] = "mutated"
	if SystemDependencies()[0].RequiredDLLs[0] != want[0] {
		t.Fatal("caller mutated the pinned descriptor")
	}
	path, err := SystemDependencyInstallerPath("/source", d.ID)
	if err != nil || filepath.Base(path) != d.ID+"-"+d.SHA256+".exe" {
		t.Fatalf("cache identity: %s %v", path, err)
	}
	if _, err := SystemDependencyInstallerPath("/source", "../unknown"); err == nil {
		t.Fatal("unknown dependency was accepted")
	}
	for _, tc := range []struct {
		actual string
		want   bool
	}{
		{"14.51.36247.0", true}, {"14.51.36248.0", true}, {"14.52.1.0", true}, {"15.0.0.0", true},
		{"14.51.36246.65535", false}, {"14.50.65535.0", false}, {"13.99.65535.0", false},
		{"14.51.36247", false}, {"14.51.36247.0.1", false}, {"14.51.65536.0", false}, {"14.51.-1.0", false},
		{" 14.51.36247.0", false}, {"14.51.36247.0x", false}, {"", false},
	} {
		if got := systemVersionAtLeast(tc.actual, "14.51.36247.0"); got != tc.want {
			t.Errorf("version %q accepted=%v; want %v", tc.actual, got, tc.want)
		}
	}
	if systemVersionAtLeast("14.51.36247.0", "invalid") {
		t.Fatal("invalid minimum version was accepted")
	}
}

func TestSystemDependencyCacheVerifiedReuseAndRepair(t *testing.T) {
	d, b := systemDependencyTestFixture()
	dir := systemDependencyTestDir(t)
	requests := 0
	client := systemDependencyTestClient(b, &requests)
	var progress []DownloadProgress
	path, err := cacheSystemDependency(context.Background(), dir, d, client, func(p DownloadProgress) { progress = append(progress, p) })
	if err != nil {
		t.Fatal(err)
	}
	if requests != 1 || len(progress) == 0 || progress[len(progress)-1].Received != d.Bytes {
		t.Fatalf("initial verified download requests=%d progress=%v", requests, progress)
	}
	for i := 0; i < 2; i++ {
		if got, err := cacheSystemDependency(context.Background(), dir, d, client, nil); err != nil || got != path {
			t.Fatalf("verified cache reuse: %s %v", got, err)
		}
	}
	if requests != 1 {
		t.Fatalf("verified cached file caused %d network requests", requests)
	}
	corrupt := bytes.Repeat([]byte{'x'}, len(b))
	if err := os.WriteFile(path, corrupt, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := cacheSystemDependency(context.Background(), dir, d, client, nil); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(got, b) || requests != 2 {
		t.Fatalf("corrupt cache was not repaired: requests=%d err=%v", requests, err)
	}
	files, err := os.ReadDir(filepath.Dir(path))
	if err != nil || len(files) != 1 {
		t.Fatalf("temporary download files left behind: %v %v", files, err)
	}
}

func TestSystemDependencyCacheRejectsHostileResponsesAndPaths(t *testing.T) {
	d, b := systemDependencyTestFixture()
	for _, tc := range []struct {
		name          string
		status        int
		body          []byte
		contentLength int64
	}{
		{"http-failure", 403, b, int64(len(b))},
		{"truncated", 200, b[:len(b)-1], -1},
		{"oversized", 200, append(append([]byte{}, b...), 'x'), -1},
		{"wrong-hash", 200, bytes.Repeat([]byte{'x'}, len(b)), int64(len(b))},
		{"wrong-length-header", 200, b, int64(len(b) + 1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := systemDependencyTestDir(t)
			client := &http.Client{Transport: systemDependencyTestTransport(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: tc.status, ContentLength: tc.contentLength, Body: io.NopCloser(bytes.NewReader(tc.body)), Header: http.Header{}}, nil
			})}
			if _, err := cacheSystemDependency(context.Background(), dir, d, client, nil); err == nil {
				t.Fatal("unverified payload was accepted")
			}
			files, err := os.ReadDir(filepath.Join(dir, "dependencies"))
			if err != nil || len(files) != 0 {
				t.Fatalf("failed download published or leaked files: %v %v", files, err)
			}
		})
	}
	for _, tc := range []struct{ name, url, id string }{
		{"http", "http://download.visualstudio.microsoft.com/x", d.ID},
		{"other-host", "https://example.com/x", d.ID},
		{"credentials", "https://user@download.visualstudio.microsoft.com/x", d.ID},
		{"port", "https://download.visualstudio.microsoft.com:444/x", d.ID},
		{"query", d.URL + "?token=x", d.ID},
		{"traversal-id", d.URL, "../vc"},
		{"dot-id", d.URL, ".."},
	} {
		t.Run(tc.name, func(t *testing.T) {
			bad := d
			bad.URL, bad.ID = tc.url, tc.id
			calls := 0
			if _, err := cacheSystemDependency(context.Background(), systemDependencyTestDir(t), bad, systemDependencyTestClient(b, &calls), nil); err == nil || calls != 0 {
				t.Fatalf("unsafe metadata reached network: calls=%d err=%v", calls, err)
			}
		})
	}
	t.Run("linked-cache", func(t *testing.T) {
		dir := systemDependencyTestDir(t)
		root := filepath.Join(dir, "dependencies")
		if err := os.Mkdir(root, 0700); err != nil {
			t.Fatal(err)
		}
		outside := filepath.Join(dir, "outside.exe")
		if err := os.WriteFile(outside, b, 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(outside, filepath.Join(root, d.ID+"-"+d.SHA256+".exe")); err != nil {
			t.Skipf("host does not allow symlink fixture: %v", err)
		}
		calls := 0
		if _, err := cacheSystemDependency(context.Background(), dir, d, systemDependencyTestClient(b, &calls), nil); err == nil || calls != 0 {
			t.Fatalf("linked cache accepted: calls=%d err=%v", calls, err)
		}
	})
	t.Run("network-cancel", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		client := &http.Client{Transport: systemDependencyTestTransport(func(*http.Request) (*http.Response, error) {
			cancel()
			return nil, context.Canceled
		})}
		if _, err := cacheSystemDependency(ctx, systemDependencyTestDir(t), d, client, nil); !errors.Is(err, context.Canceled) {
			t.Fatalf("download lost cancellation: %v", err)
		}
	})
}

func TestSystemDependencyNoDownloadWhenReadyUnsupportedOrCancelled(t *testing.T) {
	d, b := systemDependencyTestFixture()
	for _, tc := range []struct {
		name       string
		state      SystemDependencyStatus
		cancel     bool
		wantErr    bool
		continuing bool
	}{
		{"ready", SystemDependencyStatus{ID: d.ID, Supported: true, Ready: true}, false, false, false},
		{"unsupported", SystemDependencyStatus{ID: d.ID}, false, true, false},
		{"already-installing", SystemDependencyStatus{ID: d.ID, Supported: true, InstallationInProgress: true}, false, true, true},
		{"cancelled", SystemDependencyStatus{ID: d.ID, Supported: true}, true, true, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if tc.cancel {
				cancel()
			}
			requests, executions := 0, 0
			hooks := systemDependencyHooks{
				check:   func(context.Context, SystemDependency) (SystemDependencyStatus, error) { return tc.state, nil },
				client:  systemDependencyTestClient(b, &requests),
				install: func(context.Context, string) (int, bool, error) { executions++; return 0, false, nil },
			}
			result, err := prepareSystemDependency(ctx, systemDependencyTestDir(t), d, nil, hooks)
			if (err != nil) != tc.wantErr || requests != 0 || executions != 0 || result.InstallationMayContinue != tc.continuing || result.RestartCheckRequired != tc.continuing {
				t.Fatalf("unnecessary action result=%+v err=%v requests=%d executions=%d", result, err, requests, executions)
			}
		})
	}
	if _, err := PrepareSystemDependency(context.Background(), systemDependencyTestDir(t), "unknown", nil); err == nil {
		t.Fatal("public prepare accepted unknown dependency")
	}
}

func TestSystemDependencySignatureAndLockedHashGateExecution(t *testing.T) {
	d, b := systemDependencyTestFixture()
	for _, tamper := range []bool{false, true} {
		name := "signature-rejected"
		if tamper {
			name = "changed-before-lock"
		}
		t.Run(name, func(t *testing.T) {
			requests, signatures, executions := 0, 0, 0
			hooks := systemDependencyHooks{
				check: func(context.Context, SystemDependency) (SystemDependencyStatus, error) {
					return SystemDependencyStatus{ID: d.ID, Supported: true}, nil
				},
				client: systemDependencyTestClient(b, &requests),
				lock: func(path string) (func(), error) {
					if tamper {
						if err := os.WriteFile(path, bytes.Repeat([]byte{'x'}, len(b)), 0600); err != nil {
							return nil, err
						}
					}
					return func() {}, nil
				},
				signature: func(context.Context, string) error { signatures++; return errors.New("fixture publisher rejected") },
				install:   func(context.Context, string) (int, bool, error) { executions++; return 0, false, nil },
			}
			result, err := prepareSystemDependency(context.Background(), systemDependencyTestDir(t), d, nil, hooks)
			if err == nil || executions != 0 || result.SignatureVerified || requests != 1 || (!tamper && signatures != 1) || (tamper && signatures != 0) {
				t.Fatalf("unverified installer reached execution: %+v err=%v requests=%d signatures=%d executions=%d", result, err, requests, signatures, executions)
			}
		})
	}
}

func TestSystemDependencyExitAndCancellationRequirePostCheck(t *testing.T) {
	d, b := systemDependencyTestFixture()
	for _, tc := range []struct {
		name          string
		code          int
		ready         bool
		continuing    bool
		cancel        bool
		wantErr       bool
		wantRestart   bool
		wantPostCheck bool
	}{
		{"installed", 0, true, false, false, false, false, true},
		{"newer-installed", 1638, true, false, false, false, false, true},
		{"newer-hresult", int(0x80070666), true, false, false, false, false, true},
		{"missing-dlls", 0, false, false, false, true, false, true},
		{"existing-but-missing", 1638, false, false, false, true, false, true},
		{"restart-with-dlls", 3010, true, false, false, true, true, true},
		{"restart-missing-dlls", 3010, false, false, false, true, true, true},
		{"installer-failed", 1603, true, false, false, true, false, false},
		{"cancel-continues", -1, false, true, true, true, false, false},
		{"continuing-without-error", 0, true, true, false, true, false, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			requests, checks, signatures, executions, releases := 0, 0, 0, 0, 0
			var states []string
			hooks := systemDependencyHooks{
				check: func(context.Context, SystemDependency) (SystemDependencyStatus, error) {
					checks++
					return SystemDependencyStatus{ID: d.ID, Supported: true, Ready: checks > 1 && tc.ready}, nil
				},
				client: systemDependencyTestClient(b, &requests),
				lock:   func(string) (func(), error) { return func() { releases++ }, nil },
				signature: func(c context.Context, path string) error {
					signatures++
					return verifyModel(c, path, d.SHA256, d.Bytes, nil)
				},
				install: func(c context.Context, path string) (int, bool, error) {
					executions++
					if signatures != 1 {
						t.Error("install ran before signature check")
					}
					if tc.cancel {
						cancel()
						return tc.code, tc.continuing, c.Err()
					}
					return tc.code, tc.continuing, nil
				},
			}
			result, err := prepareSystemDependency(ctx, systemDependencyTestDir(t), d, func(p DownloadProgress) { states = append(states, p.State) }, hooks)
			if (err != nil) != tc.wantErr || requests != 1 || signatures != 1 || executions != 1 || releases != 1 || result.RestartRequired != tc.wantRestart || result.InstallationMayContinue != tc.continuing || result.RestartCheckRequired != tc.continuing || !result.SignatureVerified || result.ExitCode != tc.code {
				t.Fatalf("result=%+v err=%v requests=%d signatures=%d executions=%d releases=%d states=%v", result, err, requests, signatures, executions, releases, states)
			}
			if (checks == 2) != tc.wantPostCheck {
				t.Fatalf("DLL post-check calls=%d wantPostCheck=%v", checks, tc.wantPostCheck)
			}
			if tc.cancel && !errors.Is(err, context.Canceled) {
				t.Fatalf("elevated cancellation lost: %v", err)
			}
			for _, state := range states {
				if tc.wantErr && state == "done" {
					t.Fatalf("failed/restart/cancelled installation announced completion: %v", states)
				}
			}
			if !tc.wantErr && (!result.Status.Ready || states[len(states)-1] != "done") {
				t.Fatalf("post-checked installation not completed: %+v %v", result, states)
			}
		})
	}
}
