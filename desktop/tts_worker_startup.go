package main

import (
	"context"
	"errors"
	"io"
)

type ttsStartupFailure uint8

const (
	ttsStartupProtocol ttsStartupFailure = iota
	ttsStartupDeadline
	ttsStartupCancelled
	ttsStartupExited
	ttsStartupNativeInit
	ttsStartupProcessStart
	ttsStartupInitWrite
)

type ttsStartupPhase string

const (
	ttsPhaseRuntimeVerify ttsStartupPhase = "VOICE_CHILD_RUNTIME_VERIFY"
	ttsPhaseSuperVerify   ttsStartupPhase = "VOICE_CHILD_SUPERTONIC_VERIFY"
	ttsPhaseKokoroVerify  ttsStartupPhase = "VOICE_CHILD_KOKORO_VERIFY"
	ttsPhaseDLLLoad       ttsStartupPhase = "VOICE_CHILD_DLL_LOAD"
	ttsPhaseDLLVersion    ttsStartupPhase = "VOICE_CHILD_DLL_VERSION"
	ttsPhaseSuperLoad     ttsStartupPhase = "VOICE_CHILD_SUPERTONIC_LOAD"
	ttsPhaseKokoroLoad    ttsStartupPhase = "VOICE_CHILD_KOKORO_LOAD"
)

func (p ttsStartupPhase) title() string {
	switch p {
	case ttsPhaseRuntimeVerify:
		return "음성 작업: 런타임 파일 확인"
	case ttsPhaseSuperVerify:
		return "음성 작업: 다국어 음성 파일 확인"
	case ttsPhaseKokoroVerify:
		return "음성 작업: 중국어 음성 파일 확인"
	case ttsPhaseDLLLoad:
		return "음성 작업: DLL 로드"
	case ttsPhaseDLLVersion:
		return "음성 작업: DLL 버전 확인"
	case ttsPhaseSuperLoad:
		return "음성 작업: 다국어 음성 모델 로드"
	case ttsPhaseKokoroLoad:
		return "음성 작업: 중국어 음성 모델 로드"
	default:
		return ""
	}
}

func expectedTTSStartupPhases(init nativeTTSInit) []ttsStartupPhase {
	var phases []ttsStartupPhase
	if init.RuntimeDir != "" {
		phases = append(phases, ttsPhaseRuntimeVerify)
	}
	if init.SupertonicDir != "" {
		phases = append(phases, ttsPhaseSuperVerify)
	}
	if init.KokoroDir != "" {
		phases = append(phases, ttsPhaseKokoroVerify)
	}
	if init.RuntimeDir != "" {
		phases = append(phases, ttsPhaseDLLLoad, ttsPhaseDLLVersion)
	}
	if init.SupertonicDir != "" {
		phases = append(phases, ttsPhaseSuperLoad)
	}
	if init.KokoroDir != "" {
		phases = append(phases, ttsPhaseKokoroLoad)
	}
	return phases // Closed set: at most seven, independent of input lengths.
}

func reportNativeTTSPhase(init nativeTTSInit, phase ttsStartupPhase) error {
	if init.progress != nil {
		return init.progress(phase)
	}
	return nil
}

// Only a closed failure code and fixed text cross the worker boundary. In
// particular, JSON errors and native error strings may contain private data.
type ttsWorkerStartupError struct {
	failure ttsStartupFailure
	cause   error // Only a known context sentinel; never a child/native error.
	phase   ttsStartupPhase
}

func (e *ttsWorkerStartupError) code() string {
	switch e.failure {
	case ttsStartupDeadline:
		return "VOICE_WORKER_DEADLINE"
	case ttsStartupCancelled:
		return "VOICE_WORKER_CANCELLED"
	case ttsStartupExited:
		return "VOICE_WORKER_EXITED"
	case ttsStartupNativeInit:
		return "VOICE_WORKER_NATIVE_INIT"
	case ttsStartupProcessStart:
		return "VOICE_WORKER_START"
	case ttsStartupInitWrite:
		return "VOICE_WORKER_INIT_WRITE"
	default:
		return "VOICE_WORKER_PROTOCOL"
	}
}

func (e *ttsWorkerStartupError) Error() string {
	message := "다운로드한 로컬 음성 모델을 기동하지 못했습니다. 실행 기반·파일 검증과 진단을 확인하세요"
	switch e.failure {
	case ttsStartupDeadline:
		message = "로컬 음성 모델 기동 제한시간을 초과했습니다. 저장된 파일을 유지하고 기동 진단을 확인하세요"
	case ttsStartupCancelled:
		message = "로컬 음성 모델 기동이 취소되었습니다"
	case ttsStartupExited:
		message = "준비 응답 전에 로컬 음성 작업 프로세스가 종료되었습니다"
	case ttsStartupNativeInit:
		message = "로컬 음성 모델 초기화에 실패했습니다. 실행 기반·모델 진단을 확인하세요"
	case ttsStartupProcessStart:
		message = "로컬 음성 작업 프로세스를 시작하지 못했습니다"
	case ttsStartupInitWrite:
		message = "로컬 음성 환경을 전달하지 못했습니다"
	}
	if e.phase.title() != "" {
		message += " (" + e.phase.title() + ")"
	}
	return message + " [" + e.code() + "]"
}

func (e *ttsWorkerStartupError) Unwrap() error { return e.cause }

func ttsStartupReceiveError(ctx context.Context, err error) error {
	// Closing the timed-out child can race its EOF reply. Prefer the actual
	// context state to avoid misreporting a deadline as a spontaneous exit.
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return &ttsWorkerStartupError{failure: ttsStartupDeadline, cause: context.DeadlineExceeded}
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		return &ttsWorkerStartupError{failure: ttsStartupCancelled, cause: context.Canceled}
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return &ttsWorkerStartupError{failure: ttsStartupExited}
	}
	return &ttsWorkerStartupError{failure: ttsStartupProtocol}
}

func withTTSStartupPhase(err error, phase ttsStartupPhase) error {
	var failure *ttsWorkerStartupError
	if errors.As(err, &failure) && phase.title() != "" {
		copy := *failure
		copy.phase = phase
		return &copy
	}
	return err
}
