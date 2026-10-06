//go:build windows

package main

import (
	"context"
	"debug/pe"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

var dependencyKernel = syscall.NewLazyDLL("kernel32.dll")
var dependencyVersion = syscall.NewLazyDLL("version.dll")
var dependencyShell = syscall.NewLazyDLL("shell32.dll")
var dependencyOLE = syscall.NewLazyDLL("ole32.dll")
var dependencyWinTrust = syscall.NewLazyDLL("wintrust.dll")
var dependencyInstallerActive atomic.Bool

func dependencySystemDirectory() (string, error) {
	b := make([]uint16, 32768)
	n, _, err := dependencyKernel.NewProc("GetSystemDirectoryW").Call(uintptr(unsafe.Pointer(&b[0])), uintptr(len(b)))
	if n == 0 || n >= uintptr(len(b)) {
		return "", fmt.Errorf("Windows 시스템 폴더를 확인하지 못했습니다: %v", err)
	}
	return syscall.UTF16ToString(b[:n]), nil
}
func dependencyFileVersion(path string) (string, error) {
	name, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return "", err
	}
	size, _, _ := dependencyVersion.NewProc("GetFileVersionInfoSizeW").Call(uintptr(unsafe.Pointer(name)), 0)
	if size == 0 || size > 1<<20 {
		return "", errors.New("DLL 파일 버전 정보가 없습니다")
	}
	b := make([]byte, size)
	r, _, _ := dependencyVersion.NewProc("GetFileVersionInfoW").Call(uintptr(unsafe.Pointer(name)), 0, size, uintptr(unsafe.Pointer(&b[0])))
	if r == 0 {
		return "", errors.New("DLL 파일 버전을 읽지 못했습니다")
	}
	query, _ := syscall.UTF16PtrFromString("\\")
	var value uintptr
	var length uint32
	r, _, _ = dependencyVersion.NewProc("VerQueryValueW").Call(uintptr(unsafe.Pointer(&b[0])), uintptr(unsafe.Pointer(query)), uintptr(unsafe.Pointer(&value)), uintptr(unsafe.Pointer(&length)))
	base := uintptr(unsafe.Pointer(&b[0]))
	if r == 0 || length < 52 || len(b) < 52 || value < base || value-base > uintptr(len(b)-52) {
		return "", errors.New("DLL 버전 구조가 잘못되었습니다")
	}
	offset := int(value - base)
	fixed := b[offset : offset+52]
	if binary.LittleEndian.Uint32(fixed) != 0xfeef04bd {
		return "", errors.New("DLL 버전 서명이 잘못되었습니다")
	}
	ms, ls := binary.LittleEndian.Uint32(fixed[8:]), binary.LittleEndian.Uint32(fixed[12:])
	version := fmt.Sprintf("%d.%d.%d.%d", ms>>16, ms&65535, ls>>16, ls&65535)
	runtime.KeepAlive(b)
	return version, nil
}
func checkSystemDependency(ctx context.Context, dep SystemDependency) (SystemDependencyStatus, error) {
	state := SystemDependencyStatus{ID: dep.ID, DLLVersions: map[string]string{}, MissingDLLs: []string{}, Supported: runtime.GOARCH == "amd64", InstallationInProgress: dependencyInstallerActive.Load()}
	if err := ctx.Err(); err != nil {
		return state, err
	}
	if !state.Supported {
		state.CheckComplete = true
		state.Error = "Windows x64 프로그램에서 런타임을 확인하세요"
		return state, nil
	}
	root, err := dependencySystemDirectory()
	if err != nil {
		state.Error = err.Error()
		return state, err
	}
	for _, name := range dep.RequiredDLLs {
		if err := ctx.Err(); err != nil {
			return state, err
		}
		path := filepath.Join(root, name)
		good := portableLocalPath(path, false) == nil
		if good {
			file, err := pe.Open(path)
			good = err == nil
			if file != nil {
				good = file.Machine == pe.IMAGE_FILE_MACHINE_AMD64
				file.Close()
			}
		}
		version, err := dependencyFileVersion(path)
		if err == nil {
			state.DLLVersions[name] = version
		}
		if !good || err != nil || !systemVersionAtLeast(version, dep.Version) {
			state.MissingDLLs = append(state.MissingDLLs, name)
		}
	}
	state.Ready = len(state.MissingDLLs) == 0 && !state.InstallationInProgress
	state.CheckComplete = true
	return state, nil
}
func lockSystemInstaller(path string) (func(), error) {
	name, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	h, err := syscall.CreateFile(name, syscall.GENERIC_READ, syscall.FILE_SHARE_READ, nil, syscall.OPEN_EXISTING, syscall.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return nil, errors.New("설치 파일을 읽기 전용으로 보호하지 못했습니다")
	}
	var once sync.Once
	return func() { once.Do(func() { syscall.CloseHandle(h) }) }, nil
}

type dependencyGUID struct {
	Data1        uint32
	Data2, Data3 uint16
	Data4        [8]byte
}
type dependencyTrustFile struct {
	Size    uint32
	Path    *uint16
	File    uintptr
	Subject *dependencyGUID
}
type dependencyTrustData struct {
	Size                              uint32
	Policy, SIP                       uintptr
	UIChoice, Revocation, UnionChoice uint32
	File                              *dependencyTrustFile
	StateAction                       uint32
	State                             uintptr
	URL                               *uint16
	ProviderFlags, UIContext          uint32
	SignatureSettings                 uintptr
}

func dependencyNativeLayoutValid() bool {
	// Windows amd64 ABI: pointer fields retain the documented padding.
	return runtime.GOARCH == "amd64" && unsafe.Sizeof(dependencyGUID{}) == 16 &&
		unsafe.Sizeof(dependencyTrustFile{}) == 32 && unsafe.Offsetof(dependencyTrustFile{}.Path) == 8 &&
		unsafe.Sizeof(dependencyTrustData{}) == 88 && unsafe.Offsetof(dependencyTrustData{}.File) == 40 &&
		unsafe.Offsetof(dependencyTrustData{}.State) == 56 && unsafe.Offsetof(dependencyTrustData{}.ProviderFlags) == 72 &&
		unsafe.Sizeof(dependencyShellInfo{}) == 112 && unsafe.Offsetof(dependencyShellInfo{}.Process) == 104
}

func verifySystemInstallerTrust(ctx context.Context, path string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !dependencyNativeLayoutValid() {
		return errors.New("Windows x64 서명 검증 구조를 지원하지 않습니다")
	}
	// No network retrieval is permitted, even when Windows lacks the root,
	// intermediate, or revocation cache. Such failures block installation.
	done := make(chan error, 1)
	go func() {
		release, err := lockSystemInstaller(path)
		if err != nil {
			done <- err
			return
		}
		defer release()
		name, err := syscall.UTF16PtrFromString(path)
		if err != nil {
			done <- err
			return
		}
		action := dependencyGUID{0x00aac56b, 0xcd44, 0x11d0, [8]byte{0x8c, 0xc2, 0x00, 0xc0, 0x4f, 0xc2, 0x95, 0xee}}
		file := dependencyTrustFile{Path: name}
		file.Size = uint32(unsafe.Sizeof(file))
		data := dependencyTrustData{UIChoice: 2, UnionChoice: 1, File: &file, StateAction: 1,
			ProviderFlags: 0x1000 | 0x80 | 0x2000, UIContext: 1}
		data.Size = uint32(unsafe.Sizeof(data))
		proc := dependencyWinTrust.NewProc("WinVerifyTrust")
		// The return value is LONG: only zero is trusted, never SUCCEEDED(hr).
		code, _, _ := proc.Call(^uintptr(0), uintptr(unsafe.Pointer(&action)), uintptr(unsafe.Pointer(&data)))
		data.StateAction = 2
		proc.Call(^uintptr(0), uintptr(unsafe.Pointer(&action)), uintptr(unsafe.Pointer(&data)))
		runtime.KeepAlive(name)
		runtime.KeepAlive(file)
		runtime.KeepAlive(data)
		if uint32(code) != 0 {
			done <- fmt.Errorf("오프라인 Microsoft 서명 신뢰 확인에 실패했습니다 (코드 0x%08x). Windows에 신뢰 체인·해지 정보가 캐시되어 있지 않거나 서명이 유효하지 않습니다. 인터넷 조회나 설치를 실행하지 않았습니다", uint32(code))
			return
		}
		done <- nil
	}()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func verifySystemInstallerSignature(ctx context.Context, path string) error {
	root, err := dependencySystemDirectory()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if err := verifySystemInstallerTrust(ctx, path); err != nil {
		return err
	}
	// CreateFromSignedFile extracts the local certificate without chain builds.
	// The path is passed as data, never interpolated into PowerShell source.
	const script = `$ErrorActionPreference='Stop';try{$c=[System.Security.Cryptography.X509Certificates.X509Certificate]::CreateFromSignedFile($env:MCAST_SYSTEM_INSTALLER_PATH);if($null -eq $c){exit 41};$n=$c.Subject;if($n -notmatch '(?:^|,\s*)CN=Microsoft Corporation(?:,|$)' -or $n -notmatch '(?:^|,\s*)O=Microsoft Corporation(?:,|$)'){exit 42};$c.Dispose();exit 0}catch{exit 43}`
	cmd := exec.CommandContext(ctx, filepath.Join(root, "WindowsPowerShell", "v1.0", "powershell.exe"), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
	cmd.Env = append(os.Environ(), "MCAST_SYSTEM_INSTALLER_PATH="+path)
	cmd.Stdout, cmd.Stderr = io.Discard, io.Discard
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return errors.New("Microsoft Authenticode 서명·게시자 검증에 실패했습니다. 설치를 실행하지 않았습니다")
	}
	return nil
}

type dependencyShellInfo struct {
	Size, Mask                        uint32
	Window                            uintptr
	Verb, File, Parameters, Directory *uint16
	Show                              int32
	Instance, IDList                  uintptr
	Class                             *uint16
	ClassKey                          uintptr
	HotKey                            uint32
	Icon, Process                     uintptr
}
type dependencyInstallOutcome struct {
	code        int
	mayContinue bool
	err         error
}

func runSystemInstaller(ctx context.Context, path string) (int, bool, error) {
	if err := ctx.Err(); err != nil {
		return -1, false, err
	}
	if !dependencyNativeLayoutValid() {
		return -1, false, errors.New("Windows x64 설치 UI 구조를 지원하지 않습니다")
	}
	if !dependencyInstallerActive.CompareAndSwap(false, true) {
		return -1, true, errors.New("Microsoft 설치가 이미 진행 중입니다")
	}
	done := make(chan dependencyInstallOutcome, 1)
	go func() {
		defer dependencyInstallerActive.Store(false)
		finish := func(outcome dependencyInstallOutcome) {
			// A successful waiter immediately checks DLL readiness. Clear the
			// activity flag before publishing, so it cannot race that check.
			dependencyInstallerActive.Store(false)
			done <- outcome
		}
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		// Keep a second immutable file handle even when the caller stops waiting.
		release, err := lockSystemInstaller(path)
		if err != nil {
			finish(dependencyInstallOutcome{-1, false, err})
			return
		}
		defer release()
		if ctx.Err() != nil {
			finish(dependencyInstallOutcome{-1, false, ctx.Err()})
			return
		}
		hr, _, _ := dependencyOLE.NewProc("CoInitializeEx").Call(0, 2)
		if uint32(hr) == 0 || uint32(hr) == 1 {
			defer dependencyOLE.NewProc("CoUninitialize").Call()
		} else if uint32(hr) != 0x80010106 {
			finish(dependencyInstallOutcome{-1, false, errors.New("Windows 설치 UI를 초기화하지 못했습니다")})
			return
		}
		verb, _ := syscall.UTF16PtrFromString("runas")
		file, err := syscall.UTF16PtrFromString(path)
		if err != nil {
			finish(dependencyInstallOutcome{-1, false, err})
			return
		}
		args, _ := syscall.UTF16PtrFromString("/install /passive /norestart")
		dir, _ := syscall.UTF16PtrFromString(filepath.Dir(path))
		info := dependencyShellInfo{Mask: 0x40 | 0x100, Verb: verb, File: file, Parameters: args, Directory: dir, Show: 1}
		info.Size = uint32(unsafe.Sizeof(info))
		r, _, callErr := dependencyShell.NewProc("ShellExecuteExW").Call(uintptr(unsafe.Pointer(&info)))
		runtime.KeepAlive(verb)
		runtime.KeepAlive(file)
		runtime.KeepAlive(args)
		runtime.KeepAlive(dir)
		if r == 0 {
			if errors.Is(callErr, syscall.Errno(1223)) {
				finish(dependencyInstallOutcome{-1, false, fmt.Errorf("%w: Windows UAC 승인이 취소되었습니다", context.Canceled)})
			} else {
				finish(dependencyInstallOutcome{-1, false, errors.New("Windows UAC 설치를 시작하지 못했습니다")})
			}
			return
		}
		if info.Process == 0 {
			finish(dependencyInstallOutcome{-1, true, errors.New("설치 프로세스를 확인하지 못했습니다")})
			return
		}
		defer syscall.CloseHandle(syscall.Handle(info.Process))
		// Cancelling never force-kills an elevated MSI transaction. The monitor
		// retains handles/lock until it exits; the API returns promptly on ctx.
		r, _, _ = dependencyKernel.NewProc("WaitForSingleObject").Call(info.Process, 0xffffffff)
		if r != 0 {
			finish(dependencyInstallOutcome{-1, true, errors.New("설치 프로세스 종료 상태를 확인하지 못했습니다")})
			return
		}
		var code uint32
		r, _, _ = dependencyKernel.NewProc("GetExitCodeProcess").Call(info.Process, uintptr(unsafe.Pointer(&code)))
		if r == 0 {
			finish(dependencyInstallOutcome{-1, false, errors.New("Microsoft 설치 종료 코드를 읽지 못했습니다")})
			return
		}
		finish(dependencyInstallOutcome{int(code), false, nil})
	}()
	select {
	case result := <-done:
		return result.code, result.mayContinue, result.err
	case <-ctx.Done():
		return -1, true, ctx.Err()
	}
}
