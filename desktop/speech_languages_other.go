//go:build !windows

package main

import "context"

func InspectSpeechLanguages(ctx context.Context, required []string) SpeechLanguageStatus {
	status := SpeechLanguageStatus{Available: []string{}, Missing: []string{}, Error: "Windows System.Speech 음성 조회는 이 운영체제에서 지원하지 않습니다"}
	if ctx.Err() != nil {
		status.Error = "음성 조회가 취소되었습니다"
	}
	return status
}
