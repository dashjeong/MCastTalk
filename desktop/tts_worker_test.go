package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// These tests exercise real child-process pipes with a synthetic protocol peer.
// They do not load a native DLL/model or establish Windows voice quality or SLA.
const ttsPrivateCanary = "PRIVATE-TTS-FIXTURE-CANARY-DO-NOT-LOG"

func TestTTSWorkerHelperProcess(t *testing.T) {
	mode := os.Getenv("MCAST_TTS_TEST_HELPER")
	if mode == "" {
		return
	}
	ttsHelperRun(mode)
	os.Exit(0) // Do not append the test runner's PASS text to the protocol stream.
}

func ttsHelperRun(mode string) {
	reader := bufio.NewReader(os.Stdin)
	raw, err := readTTSLine(reader, 64<<10)
	var init nativeTTSInit
	if err != nil || json.Unmarshal(raw, &init) != nil || init.Threads != 2 {
		os.Exit(2)
	}
	_, _ = io.WriteString(os.Stderr, ttsPrivateCanary)
	writer := json.NewEncoder(os.Stdout)
	switch mode {
	case "handshake-id":
		_ = writer.Encode(ttsWorkerResponse{ID: 1})
	case "handshake-error":
		_ = writer.Encode(ttsWorkerResponse{Error: ttsPrivateCanary})
	case "handshake-pcm":
		_ = writer.Encode(ttsWorkerResponse{PCM: make([]byte, 320)})
	case "handshake-malformed":
		_, _ = io.WriteString(os.Stdout, "{\n")
	case "handshake-hang":
		time.Sleep(time.Hour)
	default:
		_ = writer.Encode(ttsWorkerResponse{})
	}
	if strings.HasPrefix(mode, "handshake-") {
		_, _ = io.Copy(io.Discard, reader)
		return
	}
	if mode == "no-read" {
		time.Sleep(time.Hour)
		return
	}
	for expectedID := uint64(1); ; expectedID++ {
		raw, err = readTTSLine(reader, 32<<10)
		if err != nil {
			return
		}
		var request ttsWorkerRequest
		if json.Unmarshal(raw, &request) != nil || request.ID != expectedID {
			os.Exit(3)
		}
		pcm := make([]byte, 320)
		binary.LittleEndian.PutUint32(pcm, uint32(os.Getpid()))
		binary.LittleEndian.PutUint64(pcm[4:], request.ID)
		if request.Language == "en-us" {
			pcm[12] = 1
		}
		switch mode {
		case "hang":
			time.Sleep(time.Hour)
		case "mismatch":
			_ = writer.Encode(ttsWorkerResponse{ID: request.ID + 1, PCM: pcm})
		case "malformed":
			_, _ = io.WriteString(os.Stdout, "{\n")
		case "invalid-utf8":
			_, _ = os.Stdout.Write([]byte{'{', '"', 'e', 'r', 'r', 'o', 'r', '"', ':', '"', 0xff, '"', '}', '\n'})
		case "eof":
			return
		case "unterminated":
			raw, _ = json.Marshal(ttsWorkerResponse{ID: request.ID, PCM: pcm})
			_, _ = os.Stdout.Write(raw)
			return
		case "odd":
			_ = writer.Encode(ttsWorkerResponse{ID: request.ID, PCM: make([]byte, 321)})
		case "short":
			_ = writer.Encode(ttsWorkerResponse{ID: request.ID, PCM: make([]byte, 318)})
		case "overlong-pcm":
			_ = writer.Encode(ttsWorkerResponse{ID: request.ID, PCM: make([]byte, 16000*2*30+2)})
		case "overlong-error":
			_ = writer.Encode(ttsWorkerResponse{ID: request.ID, Error: strings.Repeat("e", 513)})
		case "error-and-pcm":
			_ = writer.Encode(ttsWorkerResponse{ID: request.ID, Error: ttsPrivateCanary, PCM: pcm})
		case "overlong-line":
			_, _ = io.CopyN(os.Stdout, strings.NewReader(strings.Repeat("x", (8<<20)+1)), (8<<20)+1)
			_, _ = io.WriteString(os.Stdout, "\n")
		case "error-once":
			if request.ID == 1 {
				_ = writer.Encode(ttsWorkerResponse{ID: request.ID, Error: ttsPrivateCanary})
			} else {
				_ = writer.Encode(ttsWorkerResponse{ID: request.ID, PCM: pcm})
			}
		default:
			_ = writer.Encode(ttsWorkerResponse{ID: request.ID, PCM: pcm})
		}
	}
}

func ttsHelperCommand(t *testing.T, mode string) (*exec.Cmd, context.CancelFunc, string) {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	childCtx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(childCtx, exe, "-test.run=^TestTTSWorkerHelperProcess$")
	cmd.Env = append(os.Environ(), "MCAST_TTS_TEST_HELPER="+mode, "GORACE=atexit_sleep_ms=0")
	dir := t.TempDir()
	cmd.Dir = dir
	t.Cleanup(cancel)
	return cmd, cancel, dir
}

func ttsStartHelper(t *testing.T, mode string) (*ttsWorker, string) {
	t.Helper()
	cmd, cancel, dir := ttsHelperCommand(t, mode)
	ctx, done := context.WithTimeout(context.Background(), 3*time.Second)
	defer done()
	w, err := startTTSWorkerCommand(ctx, cmd, cancel, nativeTTSInit{DataDir: dir, Threads: 2})
	if err != nil {
		t.Fatalf("start %s: %v", mode, err)
	}
	t.Cleanup(func() {
		w.Close()
		select {
		case <-w.done:
		case <-time.After(3 * time.Second):
			t.Errorf("helper %s was not reaped", mode)
		}
	})
	return w, dir
}

func ttsAssertStopped(t *testing.T, w *ttsWorker) {
	t.Helper()
	select {
	case <-w.done:
	case <-time.After(3 * time.Second):
		t.Fatal("invalid/cancelled worker was not killed and reaped")
	}
	if w.cmd.ProcessState == nil {
		t.Fatal("child process was not reaped")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if pcm, err := w.Synthesize(ctx, "later request", "en"); err == nil || len(pcm) != 0 {
		t.Fatal("terminated worker accepted another request")
	}
}

func TestTTSWorkerHandshakeRejectsInvalidPeer(t *testing.T) {
	for _, mode := range []string{"handshake-id", "handshake-error", "handshake-pcm", "handshake-malformed", "handshake-hang"} {
		t.Run(mode, func(t *testing.T) {
			cmd, cancel, dir := ttsHelperCommand(t, mode)
			cancelled := make(chan struct{})
			wrappedCancel := func() { cancel(); close(cancelled) }
			ctx, done := context.WithTimeout(context.Background(), 250*time.Millisecond)
			defer done()
			w, err := startTTSWorkerCommand(ctx, cmd, wrappedCancel, nativeTTSInit{DataDir: dir, Threads: 2})
			if err == nil || w != nil {
				if w != nil {
					w.Close()
				}
				t.Fatal("invalid handshake produced a ready worker")
			}
			if strings.Contains(err.Error(), ttsPrivateCanary) {
				t.Fatal("private child error escaped into the returned error")
			}
			select {
			case <-cancelled:
			case <-time.After(time.Second):
				t.Fatal("failed startup did not cancel the child")
			}
		})
	}
}

func TestTTSWorkerResidentRequestsAndPrivateError(t *testing.T) {
	w, dir := ttsStartHelper(t, "error-once")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if pcm, err := w.Synthesize(ctx, ttsPrivateCanary, "ko"); err == nil || len(pcm) != 0 || strings.Contains(err.Error(), ttsPrivateCanary) {
		t.Fatal("valid generation error should return a generic error without audio or private text")
	}
	for _, id := range []uint64{2, 3} {
		pcm, err := w.Synthesize(ctx, "비공개 문장 "+ttsPrivateCanary, " EN_US ")
		if err != nil {
			t.Fatal(err)
		}
		if binary.LittleEndian.Uint32(pcm) != uint32(w.cmd.Process.Pid) || binary.LittleEndian.Uint64(pcm[4:]) != id || pcm[12] != 1 {
			t.Fatal("requests did not retain one resident child, ordered IDs and normalized language")
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("protocol wrote conversation/error data to its working directory: entries=%d error=%v", len(entries), err)
	}
}

func TestTTSWorkerCancellationAfterSuccessPreservesResidentChild(t *testing.T) {
	w, _ := ttsStartHelper(t, "ok")
	for i := 0; i < 100; i++ {
		ctx, cancel := context.WithCancel(context.Background())
		pcm, err := w.Synthesize(ctx, "완료된 요청", "ko")
		cancel()
		if err != nil || len(pcm) == 0 {
			t.Fatalf("post-completion cancellation killed resident request %d: %v", i, err)
		}
	}
	pcm, err := w.Synthesize(context.Background(), "마지막 요청", "en")
	if err != nil || len(pcm) == 0 {
		t.Fatalf("resident child unavailable after completed requests: %v", err)
	}
}

func TestTTSWorkerInvalidResponsesFailClosed(t *testing.T) {
	for _, mode := range []string{"mismatch", "malformed", "invalid-utf8", "eof", "unterminated", "odd", "short", "overlong-pcm", "overlong-error", "error-and-pcm", "overlong-line"} {
		t.Run(mode, func(t *testing.T) {
			w, _ := ttsStartHelper(t, mode)
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			pcm, err := w.Synthesize(ctx, ttsPrivateCanary, "ko")
			if err == nil || len(pcm) != 0 {
				t.Fatal("invalid response exposed audio")
			}
			if strings.Contains(err.Error(), ttsPrivateCanary) {
				t.Fatal("private child error escaped")
			}
			if errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("invalid response was only rejected by the timeout")
			}
			// An EOF peer can exit successfully itself; all remaining modes wait
			// for another request, so termination demonstrates parent cancellation.
			if mode == "eof" || mode == "unterminated" {
				select {
				case <-w.done:
				case <-time.After(3 * time.Second):
					t.Fatal("EOF child was not reaped")
				}
			} else {
				ttsAssertStopped(t, w)
			}
		})
	}
}

func TestTTSWorkerDeadlineHardKillsChild(t *testing.T) {
	w, _ := ttsStartHelper(t, "hang")
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	pcm, err := w.Synthesize(ctx, "blocked generation", "en")
	if !errors.Is(err, context.DeadlineExceeded) || len(pcm) != 0 {
		t.Fatalf("deadline returned audio or wrong error: bytes=%d error=%v", len(pcm), err)
	}
	ttsAssertStopped(t, w)
	if w.cmd.ProcessState.Success() {
		t.Fatal("hung child was not forcibly terminated")
	}
}

func TestTTSWorkerConcurrentRequestsKeepProtocolOrder(t *testing.T) {
	w, _ := ttsStartHelper(t, "resident")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	type result struct {
		pcm []byte
		err error
	}
	results := make(chan result, 2)
	start := make(chan struct{})
	for _, lang := range []string{"ko", "ja"} {
		go func(language string) {
			<-start
			pcm, err := w.Synthesize(ctx, "same resident process", language)
			results <- result{pcm, err}
		}(lang)
	}
	close(start)
	ids := map[uint64]bool{}
	for range 2 {
		r := <-results
		if r.err != nil || len(r.pcm) != 320 {
			t.Fatalf("concurrent protocol failure: bytes=%d error=%v", len(r.pcm), r.err)
		}
		if binary.LittleEndian.Uint32(r.pcm) != uint32(w.cmd.Process.Pid) {
			t.Fatal("concurrent request started a different child")
		}
		id := binary.LittleEndian.Uint64(r.pcm[4:])
		if ids[id] || id < 1 || id > 2 {
			t.Fatal("concurrent requests mixed or duplicated response IDs")
		}
		ids[id] = true
	}
}

// Windows pipe capacity can be smaller than one valid request. This deterministic
// pipe wrapper waits for the real child's death to model a write under backpressure.
type ttsBlockedWriter struct {
	io.WriteCloser
	done <-chan struct{}
}

func (b ttsBlockedWriter) Write([]byte) (int, error) {
	<-b.done
	return 0, io.ErrClosedPipe
}

func TestTTSWorkerDeadlineCoversBlockedWrite(t *testing.T) {
	w, _ := ttsStartHelper(t, "no-read")
	w.input = ttsBlockedWriter{WriteCloser: w.input, done: w.done}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	result := make(chan error, 1)
	go func() {
		pcm, err := w.Synthesize(ctx, strings.Repeat("x", 8192), "en")
		if len(pcm) != 0 {
			err = errors.New("blocked write returned audio")
		}
		result <- err
	}()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("blocked write succeeded after cancellation")
		}
	case <-time.After(3 * time.Second):
		w.Close()
		<-result
		t.Fatal("deadline did not interrupt the blocked stdin write")
	}
	ttsAssertStopped(t, w)
}

func TestTTSWorkerRejectsInputWithoutSending(t *testing.T) {
	w, _ := ttsStartHelper(t, "resident")
	for _, text := range []string{"", " \n\t", strings.Repeat("x", 8193), string([]byte{0xff}), "visible\x00truncated"} {
		if pcm, err := w.Synthesize(context.Background(), text, "en"); err == nil || len(pcm) != 0 {
			t.Fatal("invalid input was accepted")
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	pcm, err := w.Synthesize(ctx, "valid input", "en")
	if err != nil || binary.LittleEndian.Uint64(pcm[4:]) != 1 {
		t.Fatal("invalid requests reached the child or killed its resident process")
	}
}

func TestReadTTSLineBoundsAndEOF(t *testing.T) {
	t.Run("fragmented exact limit", func(t *testing.T) {
		want := []byte(strings.Repeat("a", 100) + "\n")
		got, err := readTTSLine(bufio.NewReaderSize(bytes.NewReader(want), 16), len(want))
		if err != nil || !bytes.Equal(got, want) {
			t.Fatal("fragmented message at exact limit was changed or rejected")
		}
	})
	t.Run("newline counts toward limit", func(t *testing.T) {
		got, err := readTTSLine(bufio.NewReader(strings.NewReader("1234\n")), 4)
		if err == nil || len(got) != 0 {
			t.Fatal("oversized message was accepted")
		}
	})
	t.Run("unterminated message", func(t *testing.T) {
		got, err := readTTSLine(bufio.NewReader(strings.NewReader("{}")), 4)
		if !errors.Is(err, io.EOF) || string(got) != "{}" {
			t.Fatal("unterminated message must remain an EOF failure")
		}
	})
}

func TestPCMFromNativeDurationDCAndClipping(t *testing.T) {
	for _, tc := range []struct {
		name  string
		level float32
		want  int16
	}{{"DC", .25, 8192}, {"positive clip", 2, 32767}, {"negative clip", -2, -32767}} {
		t.Run(tc.name, func(t *testing.T) {
			samples := make([]float32, 2400)
			for i := range samples {
				samples[i] = tc.level
			}
			pcm, err := pcmFromNative(samples, 24000)
			if err != nil || len(pcm) != 1600*2 {
				t.Fatalf("100ms mono output: bytes=%d error=%v", len(pcm), err)
			}
			for i := 0; i < len(pcm); i += 2 {
				if int16(binary.LittleEndian.Uint16(pcm[i:])) != tc.want {
					t.Fatal("DC level/clipping was not preserved in little-endian PCM16")
				}
			}
		})
	}
}

func TestPCMFromNativeSinePreservesFrequencyAndEnergy(t *testing.T) {
	samples := make([]float32, 2400)
	for i := range samples {
		samples[i] = float32(.25 * math.Sin(2*math.Pi*1000*float64(i)/24000))
	}
	pcm, err := pcmFromNative(samples, 24000)
	if err != nil || len(pcm) != 3200 {
		t.Fatalf("sine conversion: bytes=%d error=%v", len(pcm), err)
	}
	var sum, squares float64
	var crossings int
	var previous int16
	for i := 0; i < len(pcm); i += 2 {
		v := int16(binary.LittleEndian.Uint16(pcm[i:]))
		sum += float64(v)
		squares += float64(v) * float64(v)
		if previous <= 0 && v > 0 {
			crossings++
		}
		previous = v
	}
	count := float64(len(pcm) / 2)
	rms := math.Sqrt(squares / count)
	wantRMS := .25 * 32767 / math.Sqrt2
	if math.Abs(sum/count) > 2 || crossings < 99 || crossings > 101 || math.Abs(rms-wantRMS)/wantRMS > .05 {
		t.Fatalf("1kHz 100ms signal changed: mean=%.2f cycles=%d RMS=%.2f", sum/count, crossings, rms)
	}
}

func TestPCMFromNativeRejectsInvalidSamples(t *testing.T) {
	valid := make([]float32, 2400)
	for _, rate := range []int{0, 7999, 96001} {
		if pcm, err := pcmFromNative(valid, rate); err == nil || len(pcm) != 0 {
			t.Fatal("unsupported sample rate produced PCM")
		}
	}
	for _, f := range []float32{float32(math.NaN()), float32(math.Inf(1)), float32(math.Inf(-1))} {
		samples := append([]float32(nil), valid...)
		samples[123] = f
		if pcm, err := pcmFromNative(samples, 24000); err == nil || len(pcm) != 0 {
			t.Fatal("nonfinite sample produced PCM")
		}
	}
	for _, samples := range [][]float32{nil, make([]float32, 239), make([]float32, 24000*30+1)} {
		if pcm, err := pcmFromNative(samples, 24000); err == nil || len(pcm) != 0 {
			t.Fatal("empty, under-duration or over-duration data produced PCM")
		}
	}
}
