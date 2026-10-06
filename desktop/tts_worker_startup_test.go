package main

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

// These fake directories are used only by the synthetic pipe peer. No native
// library/model verification, inference accuracy or Windows SLA is certified.
func ttsPhaseFixtureInit(dir string) nativeTTSInit {
	return nativeTTSInit{DataDir: dir, RuntimeDir: dir + "/runtime", SupertonicDir: dir + "/supertonic", KokoroDir: dir + "/kokoro", Threads: 2}
}

func TestTTSWorkerOrderedProgressRequiresFinalReadiness(t *testing.T) {
	cmd, cancel, dir := ttsHelperCommand(t, "phase-ok")
	init := ttsPhaseFixtureInit(dir)
	var observed []ttsStartupPhase
	ctx, done := context.WithTimeout(context.Background(), 3*time.Second)
	defer done()
	worker, err := startTTSWorkerCommandWithProgress(ctx, cmd, cancel, init, func(phase ttsStartupPhase) {
		observed = append(observed, phase)
	})
	if err != nil || worker == nil {
		t.Fatalf("ordered progress plus final handshake failed: %v", err)
	}
	defer func() {
		worker.Close()
		select {
		case <-worker.done:
		case <-time.After(3 * time.Second):
			t.Error("successful phase peer was not reaped")
		}
	}()
	expected := expectedTTSStartupPhases(init)
	if len(observed) != 7 || len(expected) != len(observed) {
		t.Fatal("phase sequence was omitted or unbounded")
	}
	for i, phase := range expected {
		if observed[i] != phase {
			t.Fatal("progress was not the exact requested fixed sequence")
		}
	}
	pcm, err := worker.Synthesize(ctx, ttsPrivateCanary, "ko")
	if err != nil || len(pcm) != 320 {
		t.Fatalf("phase messages were confused with subsequent audio response: %v", err)
	}
}

func TestTTSWorkerProgressIsBoundedPrivateAndFailClosed(t *testing.T) {
	for _, mode := range []string{
		"phase-unknown", "phase-skip", "phase-duplicate", "phase-extra", "phase-id", "phase-error", "phase-pcm",
		"phase-early-ready", "phase-unknown-field", "phase-trailing-json", "phase-native-error", "phase-hang", "phase-drip",
	} {
		t.Run(mode, func(t *testing.T) {
			cmd, cancel, dir := ttsHelperCommand(t, mode)
			cancelled := make(chan struct{})
			cancelChild := func() { cancel(); close(cancelled) }
			duration := 3 * time.Second
			want := ttsStartupProtocol
			if mode == "phase-native-error" {
				want = ttsStartupNativeInit
			}
			if mode == "phase-hang" || mode == "phase-drip" {
				duration = 350 * time.Millisecond
				want = ttsStartupDeadline
			}
			ctx, done := context.WithTimeout(context.Background(), duration)
			defer done()
			var observed []ttsStartupPhase
			start := time.Now()
			worker, err := startTTSWorkerCommandWithProgress(ctx, cmd, cancelChild, ttsPhaseFixtureInit(dir), func(phase ttsStartupPhase) {
				observed = append(observed, phase)
			})
			var failure *ttsWorkerStartupError
			if worker != nil || !errors.As(err, &failure) || failure.failure != want {
				t.Fatalf("invalid/incomplete progress produced readiness or wrong failure kind: %v", err)
			}
			if len(observed) > 7 || strings.Contains(err.Error(), ttsPrivateCanary) {
				t.Fatal("progress escaped its fixed bound or exposed private text")
			}
			for _, phase := range observed {
				if phase.title() == "" {
					t.Fatal("unrecognized phase escaped to the observer")
				}
			}
			if want == ttsStartupDeadline {
				if !errors.Is(err, context.DeadlineExceeded) || failure.phase.title() == "" || time.Since(start) > time.Second {
					t.Fatal("progress extended the deadline or lost the last safe phase")
				}
			}
			if mode == "phase-native-error" && failure.phase != ttsPhaseSuperLoad {
				t.Fatal("native initialization failure lost its last fixed phase")
			}
			select {
			case <-cancelled:
			default:
				t.Fatal("failed initialization did not cancel the child")
			}
		})
	}
}

func TestTTSWorkerUnknownPhaseCannotEnterDiagnosticText(t *testing.T) {
	failure := &ttsWorkerStartupError{failure: ttsStartupDeadline, cause: context.DeadlineExceeded, phase: ttsStartupPhase(ttsPrivateCanary)}
	if strings.Contains(failure.Error(), ttsPrivateCanary) || !errors.Is(failure, context.DeadlineExceeded) {
		t.Fatal("unknown phase exposed text or lost fixed context sentinel")
	}
}
