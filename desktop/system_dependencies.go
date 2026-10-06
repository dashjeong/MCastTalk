package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Authored locally by Codex. This module was not approved or implemented by
// Gemini. Microsoft payload identity was checked against the official download.
type SystemDependency struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Version      string   `json:"version"`
	URL          string   `json:"url"`
	SHA256       string   `json:"sha256"`
	Bytes        int64    `json:"bytes"`
	TermsURL     string   `json:"termsURL"`
	RequiredDLLs []string `json:"requiredDLLs"`
}
type SystemDependencyStatus struct {
	ID                     string            `json:"id"`
	Supported              bool              `json:"supported"`
	Ready                  bool              `json:"ready"`
	CheckComplete          bool              `json:"checkComplete"`
	MissingDLLs            []string          `json:"missingDLLs"`
	DLLVersions            map[string]string `json:"dllVersions"`
	InstallationInProgress bool              `json:"installationInProgress"`
	Error                  string            `json:"error,omitempty"`
}
type SystemDependencyResult struct {
	Status                  SystemDependencyStatus `json:"status"`
	InstallerPath           string                 `json:"installerPath,omitempty"`
	ExitCode                int                    `json:"exitCode"`
	RestartRequired         bool                   `json:"restartRequired"`
	RestartCheckRequired    bool                   `json:"restartCheckRequired"`
	InstallationMayContinue bool                   `json:"installationMayContinue"`
	SignatureVerified       bool                   `json:"signatureVerified"`
}

var systemDependencyPrepareMu sync.Mutex

func SystemDependencies() []SystemDependency {
	return []SystemDependency{{
		ID: "vc-redist-x64", Name: "Microsoft Visual C++ v14 Redistributable (x64)", Version: "14.51.36247.0",
		URL:    "https://download.visualstudio.microsoft.com/download/pr/ebdab8e5-1d7b-4d9f-a11b-cbb1720c3b12/843068991DAAA1F73AD9F6239BCE4D0F6A07A51F18C37EA2A867E9BECA71295C/VC_redist.x64.exe",
		SHA256: "843068991daaa1f73ad9f6239bce4d0f6a07a51f18c37ea2a867e9beca71295c", Bytes: 18731856,
		TermsURL:     "https://visualstudio.microsoft.com/license-terms/vs2026-ga-visualcpp-v14-redist-runtime/",
		RequiredDLLs: []string{"VCRUNTIME140.dll", "MSVCP140.dll", "VCRUNTIME140_1.dll", "VCOMP140.DLL", "MSVCP140_1.dll"},
	}}
}

// Older schema-1 exports described this exact installer before the Sherpa
// loader's fifth DLL dependency was discovered. Only that specific historical
// profile is compatible; target readiness always uses today's full DLL list.
func systemDependencyProfileCompatible(in, fixed SystemDependency) bool {
	if reflect.DeepEqual(in, fixed) {
		return true
	}
	if fixed.ID != "vc-redist-x64" || len(fixed.RequiredDLLs) != 5 || fixed.RequiredDLLs[4] != "MSVCP140_1.dll" {
		return false
	}
	legacy := fixed
	legacy.RequiredDLLs = legacy.RequiredDLLs[:4]
	return reflect.DeepEqual(in, legacy)
}

func CheckSystemDependencies(ctx context.Context) ([]SystemDependencyStatus, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	states := []SystemDependencyStatus{}
	for _, dep := range SystemDependencies() {
		state, err := checkSystemDependency(ctx, dep)
		states = append(states, state)
		if err != nil {
			return states, err
		}
	}
	return states, nil
}

func SystemDependencyInstallerPath(dataDir, id string) (string, error) {
	for _, dep := range SystemDependencies() {
		if dep.ID == id {
			return filepath.Join(dataDir, "dependencies", dep.ID+"-"+dep.SHA256+".exe"), nil
		}
	}
	return "", errors.New("알 수 없는 시스템 구성요소입니다")
}

func systemVersionAtLeast(actual, minimum string) bool {
	parse := func(text string) ([4]uint64, bool) {
		var out [4]uint64
		parts := strings.Split(text, ".")
		if len(parts) != 4 {
			return out, false
		}
		for i, p := range parts {
			if p == "" {
				return out, false
			}
			for _, c := range p {
				if c < '0' || c > '9' {
					return out, false
				}
			}
			v, err := strconv.ParseUint(p, 10, 16)
			if err != nil {
				return out, false
			}
			out[i] = v
		}
		return out, true
	}
	a, ok := parse(actual)
	if !ok {
		return false
	}
	b, ok := parse(minimum)
	if !ok {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return a[i] > b[i]
		}
	}
	return true
}

type systemDependencyHooks struct {
	check     func(context.Context, SystemDependency) (SystemDependencyStatus, error)
	signature func(context.Context, string) error
	install   func(context.Context, string) (int, bool, error)
	lock      func(string) (func(), error)
	client    *http.Client
}

// Prepare is called only after the setup consent/plan-fingerprint gate. It never
// modifies UAC or execution policy and cannot silently elevate the application.
func PrepareSystemDependency(ctx context.Context, dataDir, id string, progress func(DownloadProgress)) (SystemDependencyResult, error) {
	for _, dep := range SystemDependencies() {
		if dep.ID == id {
			return prepareSystemDependency(ctx, dataDir, dep, progress, systemDependencyHooks{checkSystemDependency, verifySystemInstallerSignature, runSystemInstaller, lockSystemInstaller, nil})
		}
	}
	return SystemDependencyResult{ExitCode: -1}, errors.New("알 수 없는 시스템 구성요소입니다")
}

// File preparation is usable even before native readiness can be determined.
// It downloads only the immutable installer and verifies its size/SHA. It does
// not check Authenticode, elevate, install, or claim that its DLLs are ready.
func PrepareSystemDependencyFiles(ctx context.Context, dataDir, id string, status SystemDependencyStatus, progress func(DownloadProgress)) (SystemDependencyResult, error) {
	for _, dep := range SystemDependencies() {
		if dep.ID == id {
			return prepareSystemDependencyFiles(ctx, dataDir, dep, status, progress, nil)
		}
	}
	return SystemDependencyResult{ExitCode: -1}, errors.New("알 수 없는 시스템 구성요소입니다")
}

func prepareSystemDependencyFiles(ctx context.Context, dataDir string, dep SystemDependency, status SystemDependencyStatus, progress func(DownloadProgress), client *http.Client) (SystemDependencyResult, error) {
	result := SystemDependencyResult{ExitCode: -1, Status: status}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Minute)
	defer cancel()
	path, err := cacheSystemDependency(ctx, dataDir, dep, client, progress)
	if err == nil {
		result.InstallerPath = path
	}
	if progress != nil {
		state := "done"
		if err != nil {
			state = "failed"
			if errors.Is(err, context.Canceled) {
				state = "cancelled"
			}
		}
		p := DownloadProgress{ID: dep.ID, State: state, Total: dep.Bytes}
		if err == nil {
			p.Received = dep.Bytes
		} else {
			p.Error = err.Error()
		}
		progress(p)
	}
	return result, err
}

func prepareSystemDependency(ctx context.Context, dataDir string, dep SystemDependency, progress func(DownloadProgress), hooks systemDependencyHooks) (result SystemDependencyResult, finalErr error) {
	result.ExitCode = -1
	if err := ctx.Err(); err != nil {
		return result, err
	}
	if !systemDependencyPrepareMu.TryLock() {
		return result, errors.New("시스템 구성요소 설치가 이미 진행 중입니다")
	}
	defer systemDependencyPrepareMu.Unlock()
	emit := func(state string, received int64, err error) {
		if progress != nil {
			p := DownloadProgress{ID: dep.ID, State: state, Received: received, Total: dep.Bytes}
			if err != nil {
				p.Error = err.Error()
			}
			progress(p)
		}
	}
	defer func() {
		if finalErr != nil {
			state := "failed"
			if errors.Is(finalErr, context.Canceled) {
				state = "cancelled"
			}
			emit(state, 0, finalErr)
		}
	}()
	state, err := hooks.check(ctx, dep)
	result.Status = state
	if err != nil {
		return result, err
	}
	if !state.Supported {
		return result, errors.New("Microsoft 런타임 설치는 Windows x64에서 지원합니다")
	}
	if state.InstallationInProgress {
		result.InstallationMayContinue = true
		result.RestartCheckRequired = true
		return result, errors.New("이전 Microsoft 설치가 진행 중입니다. 완료 후 다시 확인하세요")
	}
	if state.Ready {
		result.ExitCode = 0
		emit("done", 0, nil)
		return result, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Minute)
	defer cancel()
	path, err := cacheSystemDependency(ctx, dataDir, dep, hooks.client, progress)
	if err != nil {
		return result, err
	}
	result.InstallerPath = path
	release, err := hooks.lock(path)
	if err != nil {
		return result, err
	}
	defer release()
	// Recheck while write/delete is locked; an altered cache can never execute.
	if err := verifyModel(ctx, path, dep.SHA256, dep.Bytes, nil); err != nil {
		return result, err
	}
	emit("verifying-signature", dep.Bytes, nil)
	if err := hooks.signature(ctx, path); err != nil {
		return result, err
	}
	result.SignatureVerified = true
	if err := ctx.Err(); err != nil {
		return result, err
	}
	emit("awaiting-approval", dep.Bytes, nil)
	code, continuing, err := hooks.install(ctx, path)
	result.ExitCode, result.InstallationMayContinue = code, continuing
	if continuing {
		result.RestartCheckRequired = true
	}
	if err != nil {
		return result, err
	}
	if continuing {
		return result, errors.New("Microsoft 설치가 계속 진행될 수 있습니다. 완료 후 다시 확인하세요")
	}
	// Burn sometimes wraps the Windows Installer result in HRESULT_FROM_WIN32.
	normalized := uint32(code)
	if normalized&0xffff0000 == 0x80070000 {
		normalized &= 0xffff
	}
	switch normalized {
	case 0, 1638: // Existing/newer installation still requires DLL/version checks.
	case 3010:
		result.RestartRequired = true
	default:
		return result, fmt.Errorf("Microsoft 런타임 설치 실패 (코드 %d)", normalized)
	}
	if err := ctx.Err(); err != nil {
		result.RestartCheckRequired = true
		return result, err
	}
	emit("checking-system", dep.Bytes, nil)
	result.Status, err = hooks.check(ctx, dep)
	if err != nil {
		return result, err
	}
	if !result.Status.Ready {
		if result.RestartRequired {
			return result, errors.New("Microsoft 런타임 설치 후 재부팅하고 DLL 준비 상태를 다시 확인하세요")
		}
		return result, errors.New("설치 후 필요한 DLL 버전이 확인되지 않았습니다")
	}
	if result.RestartRequired {
		return result, errors.New("Microsoft 런타임 설치를 마치려면 재부팅이 필요합니다")
	}
	emit("done", dep.Bytes, nil)
	return result, nil
}

func cacheSystemDependency(ctx context.Context, dataDir string, dep SystemDependency, client *http.Client, progress func(DownloadProgress)) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	u, err := url.Parse(dep.URL)
	validID := len(dep.ID) > 0 && len(dep.ID) <= 80
	for _, c := range dep.ID {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
			validID = false
		}
	}
	if err != nil || u.Scheme != "https" || u.Host != "download.visualstudio.microsoft.com" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || dep.Bytes <= 0 || dep.Bytes > 64<<20 || len(dep.SHA256) != 64 || !validID {
		return "", errors.New("시스템 설치 파일의 고정 정보가 유효하지 않습니다")
	}
	if _, err := hex.DecodeString(dep.SHA256); err != nil {
		return "", errors.New("시스템 설치 파일의 해시 정보가 잘못되었습니다")
	}
	if err := portableLocalPath(dataDir, true); err != nil {
		return "", err
	}
	root := filepath.Join(dataDir, "dependencies")
	if err := os.MkdirAll(root, 0700); err != nil {
		return "", err
	}
	if err := portableLocalPath(root, true); err != nil {
		return "", err
	}
	path := filepath.Join(root, dep.ID+"-"+dep.SHA256+".exe")
	if st, err := os.Lstat(path); err == nil {
		if !st.Mode().IsRegular() {
			return "", errors.New("시스템 설치 파일의 링크 또는 특수 파일을 사용할 수 없습니다")
		}
		if err := verifyModel(ctx, path, dep.SHA256, dep.Bytes, nil); err == nil {
			return path, nil
		} else if ctx.Err() != nil {
			return "", ctx.Err()
		}
		if err := os.Remove(path); err != nil {
			return "", err
		}
	} else if !os.IsNotExist(err) {
		return "", err
	}
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Minute, CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > 4 || req.URL.Scheme != "https" || req.URL.Host != "download.visualstudio.microsoft.com" || req.URL.User != nil {
				return errors.New("공식 Microsoft 주소 밖으로 이동할 수 없습니다")
			}
			return nil
		}}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, dep.URL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept-Encoding", "identity")
	resp, err := client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		return "", errors.New("Microsoft 시스템 설치 파일을 내려받지 못했습니다")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK || (resp.ContentLength >= 0 && resp.ContentLength != dep.Bytes) {
		return "", errors.New("Microsoft 시스템 설치 파일의 응답·크기가 일치하지 않습니다")
	}
	f, err := os.CreateTemp(root, ".dependency-*.tmp")
	if err != nil {
		return "", err
	}
	tmp := f.Name()
	defer func() { f.Close(); os.Remove(tmp) }()
	h := sha256.New()
	reader := io.LimitReader(engineContextReader{ctx, resp.Body}, dep.Bytes+1)
	buf := make([]byte, 32<<10)
	var received int64
	for {
		n, readErr := reader.Read(buf)
		if n > 0 {
			received += int64(n)
			if received > dep.Bytes {
				return "", errors.New("시스템 설치 파일의 크기 제한을 넘었습니다")
			}
			if _, err := f.Write(buf[:n]); err != nil {
				return "", err
			}
			h.Write(buf[:n])
			if progress != nil {
				progress(DownloadProgress{ID: dep.ID, State: "downloading", Received: received, Total: dep.Bytes})
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				return "", readErr
			}
			break
		}
	}
	if received != dep.Bytes || hex.EncodeToString(h.Sum(nil)) != dep.SHA256 {
		return "", errors.New("Microsoft 시스템 설치 파일의 SHA-256 또는 크기가 일치하지 않습니다")
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if err := f.Sync(); err != nil {
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	if err := resp.Body.Close(); err != nil {
		return "", err
	}
	if err := replaceFile(tmp, path); err != nil {
		return "", err
	}
	return path, nil
}
