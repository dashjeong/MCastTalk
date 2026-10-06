//go:build windows

package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
)

func InspectSpeechLanguages(ctx context.Context, required []string) SpeechLanguageStatus {
	status := SpeechLanguageStatus{Supported: true, Available: []string{}, Missing: []string{}}
	if ctx.Err() != nil {
		status.Error = "Windows 음성 조회가 취소되었습니다"
		return status
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	const script = `[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Speech;$s=[System.Speech.Synthesis.SpeechSynthesizer]::new();try{$v=@($s.GetInstalledVoices()|ForEach-Object {[pscustomobject]@{culture=$_.VoiceInfo.Culture.Name;enabled=$_.Enabled}});ConvertTo-Json -InputObject $v -Depth 3 -Compress}finally{$s.Dispose()}`
	root := os.Getenv("SystemRoot")
	if root == "" || !filepath.IsAbs(root) {
		status.Error = "Windows 시스템 폴더를 확인하지 못했습니다"
		return status
	}
	cmd := exec.CommandContext(ctx, filepath.Join(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe"), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	raw, err := cmd.Output()
	if err != nil {
		if ctx.Err() != nil {
			status.Error = "Windows 음성 조회가 시간 제한 또는 취소로 미완료되었습니다"
		} else {
			status.Error = "Windows 설치 음성 목록을 확인하지 못했습니다"
		}
		return status
	}
	return parseSpeechLanguages(raw, required)
}
