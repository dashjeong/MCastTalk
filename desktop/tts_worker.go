package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type nativeTTSInit struct {
	DataDir, RuntimeDir, SupertonicDir, KokoroDir string
	Threads                                       int
}
type nativeTTSSynthesizer interface {
	Generate(text, language string) ([]float32, int, error)
	Close()
}
type ttsWorkerRequest struct {
	ID       uint64 `json:"id"`
	Text     string `json:"text"`
	Language string `json:"language"`
}
type ttsWorkerResponse struct {
	ID    uint64 `json:"id"`
	PCM   []byte `json:"pcm,omitempty"`
	Error string `json:"error,omitempty"`
}
type ttsWorker struct {
	cmd    *exec.Cmd
	cancel context.CancelFunc
	input  io.WriteCloser
	output *bufio.Reader
	gate   chan struct{}
	stop   sync.Once
	done   chan struct{}
	id     uint64
}

// The same executable runs a resident, isolated native worker. No conversation
// text, audio, credentials or temporary WAV is written to the filesystem.
func startTTSWorker(ctx context.Context, init nativeTTSInit) (*ttsWorker, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	workerCtx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(workerCtx, exe, "--tts-worker")
	return startTTSWorkerCommand(ctx, cmd, cancel, init)
}

func startTTSWorkerCommand(ctx context.Context, cmd *exec.Cmd, cancel context.CancelFunc, init nativeTTSInit) (*ttsWorker, error) {
	cmd.Stderr = io.Discard
	input, err := cmd.StdinPipe()
	if err != nil {
		cancel()
		return nil, err
	}
	output, err := cmd.StdoutPipe()
	if err != nil {
		cancel()
		input.Close()
		return nil, err
	}
	w := &ttsWorker{cmd: cmd, cancel: cancel, input: input, output: bufio.NewReaderSize(output, 64<<10), gate: make(chan struct{}, 1), done: make(chan struct{})}
	if err = cmd.Start(); err != nil {
		cancel()
		input.Close()
		return nil, errors.New("로컬 음성 작업 프로세스를 시작하지 못했습니다")
	}
	go func() { cmd.Wait(); close(w.done) }()
	waitCtx, waitCancel := context.WithTimeout(ctx, 45*time.Second)
	defer waitCancel()
	stopWatching := watchTTSContext(waitCtx, w)
	defer stopWatching()
	raw, err := json.Marshal(init)
	if err == nil {
		_, err = input.Write(append(raw, '\n'))
	}
	if err != nil {
		w.Close()
		return nil, errors.New("로컬 음성 환경을 전달하지 못했습니다")
	}
	response, err := w.receive(waitCtx)
	if err != nil || response.ID != 0 || response.Error != "" || len(response.PCM) != 0 {
		w.Close()
		return nil, errors.New("다운로드한 로컬 음성 모델을 기동하지 못했습니다. 실행 기반·파일 검증과 진단을 확인하세요")
	}
	return w, nil
}
func (w *ttsWorker) Close() { w.stop.Do(func() { w.cancel(); w.input.Close() }) }

func watchTTSContext(ctx context.Context, w *ttsWorker) func() {
	done := make(chan struct{})
	var mu sync.Mutex
	finished := false
	go func() {
		select {
		case <-ctx.Done():
			mu.Lock()
			if !finished {
				w.Close()
			}
			mu.Unlock()
		case <-done:
		}
	}()
	return func() {
		mu.Lock()
		if !finished {
			finished = true
			close(done)
		}
		mu.Unlock()
	}
}
func (w *ttsWorker) receive(ctx context.Context) (ttsWorkerResponse, error) {
	type result struct {
		value ttsWorkerResponse
		err   error
	}
	ch := make(chan result, 1)
	go func() {
		raw, err := readTTSLine(w.output, 8<<20)
		var response ttsWorkerResponse
		if err == nil && !utf8.Valid(raw) {
			err = errors.New("음성 응답 UTF-8 형식 오류")
		}
		if err == nil {
			err = json.Unmarshal(raw, &response)
		}
		ch <- result{response, err}
	}()
	select {
	case r := <-ch:
		return r.value, r.err
	case <-ctx.Done():
		w.Close()
		return ttsWorkerResponse{}, ctx.Err()
	}
}
func (w *ttsWorker) Synthesize(ctx context.Context, text, language string) ([]byte, error) {
	if !utf8.ValidString(text) || strings.IndexByte(text, 0) >= 0 || len(text) > 8192 || strings.TrimSpace(text) == "" {
		return nil, errors.New("음성합성 입력이 유효하지 않습니다")
	}
	select {
	case w.gate <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-w.done:
		return nil, errors.New("로컬 음성 작업 프로세스가 종료되었습니다")
	}
	defer func() { <-w.gate }()
	select {
	case <-w.done:
		return nil, errors.New("로컬 음성 작업 프로세스가 종료되었습니다")
	default:
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	w.id++
	stopWatching := watchTTSContext(ctx, w)
	defer stopWatching()
	req := ttsWorkerRequest{w.id, text, normalizeSpeechLanguage(language)}
	raw, err := json.Marshal(req)
	if err == nil {
		_, err = w.input.Write(append(raw, '\n'))
	}
	if err != nil {
		w.Close()
		return nil, errors.New("로컬 음성 요청 전송에 실패했습니다")
	}
	response, err := w.receive(ctx)
	if err != nil {
		w.Close()
		return nil, err
	}
	if response.ID != req.ID {
		w.Close()
		return nil, errors.New("로컬 음성 응답 순서가 잘못되었습니다")
	}
	if response.Error != "" {
		if len(response.Error) > 512 || !utf8.ValidString(response.Error) || len(response.PCM) != 0 {
			w.Close()
			return nil, errors.New("로컬 음성 오류 응답의 형식이 잘못되었습니다")
		}
		return nil, errors.New("요청한 언어의 로컬 음성을 생성하지 못했습니다")
	}
	if len(response.PCM) < 320 || len(response.PCM) > 16000*2*30 || len(response.PCM)%2 != 0 {
		w.Close()
		return nil, errors.New("로컬 음성 PCM 형식 또는 길이가 잘못되었습니다")
	}
	if err := ctx.Err(); err != nil {
		w.Close()
		return nil, err
	}
	return response.PCM, nil
}
func readTTSLine(reader *bufio.Reader, limit int) ([]byte, error) {
	var raw []byte
	for {
		part, err := reader.ReadSlice('\n')
		if len(raw)+len(part) > limit {
			return nil, errors.New("음성 작업 메시지가 너무 큽니다")
		}
		raw = append(raw, part...)
		if err == bufio.ErrBufferFull {
			continue
		}
		return raw, err
	}
}
func pcmFromNative(samples []float32, rate int) ([]byte, error) {
	if rate < 8000 || rate > 96000 || len(samples) == 0 || len(samples) > rate*30 {
		return nil, errors.New("로컬 음성 표본 형식이 잘못되었습니다")
	}
	for _, f := range samples {
		if math.IsNaN(float64(f)) || math.IsInf(float64(f), 0) {
			return nil, errors.New("로컬 음성 표본이 유효하지 않습니다")
		}
	}
	// Average each output sample's source interval before 16kHz PCM conversion.
	// This avoids treating float bytes as PCM and reduces downsampling aliasing.
	count := len(samples) * 16000 / rate
	if count < 160 {
		return nil, errors.New("로컬 음성 출력이 너무 짧습니다")
	}
	pcm := make([]byte, count*2)
	for i := 0; i < count; i++ {
		lo := float64(i) * float64(rate) / 16000
		hi := float64(i+1) * float64(rate) / 16000
		var sum, weight float64
		for j := int(lo); j < int(math.Ceil(hi)) && j < len(samples); j++ {
			span := math.Min(hi, float64(j+1)) - math.Max(lo, float64(j))
			if span > 0 {
				sum += float64(samples[j]) * span
				weight += span
			}
		}
		f := sum / weight
		f = math.Max(-1, math.Min(1, f))
		binary.LittleEndian.PutUint16(pcm[i*2:], uint16(int16(math.Round(f*32767))))
	}
	return pcm, nil
}
func runNativeTTSWorker() error {
	reader := bufio.NewReaderSize(os.Stdin, 64<<10)
	raw, err := readTTSLine(reader, 64<<10)
	if err != nil {
		return err
	}
	var init nativeTTSInit
	if json.Unmarshal(raw, &init) != nil {
		return errors.New("음성 작업 환경 형식 오류")
	}
	for _, entry := range []struct{ dir, folder string }{{init.RuntimeDir, "runtimes"}, {init.SupertonicDir, "models"}, {init.KokoroDir, "models"}} {
		if entry.dir == "" {
			continue
		}
		if !engineManagedPath(init.DataDir, entry.folder, entry.dir) || verifyInstalledBundle(context.Background(), entry.dir) != nil {
			return errors.New("관리 경로의 검증된 음성 패키지가 필요합니다")
		}
	}
	synth, err := newNativeTTSSynthesizer(init)
	writer := json.NewEncoder(os.Stdout)
	if err != nil {
		_ = writer.Encode(ttsWorkerResponse{Error: "로컬 음성 모델 초기화 실패"})
		return err
	}
	defer synth.Close()
	if err = writer.Encode(ttsWorkerResponse{}); err != nil {
		return err
	}
	for {
		raw, err = readTTSLine(reader, 32<<10)
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		var request ttsWorkerRequest
		if json.Unmarshal(raw, &request) != nil || request.ID == 0 || !utf8.ValidString(request.Text) || strings.IndexByte(request.Text, 0) >= 0 || len(request.Text) > 8192 || strings.TrimSpace(request.Text) == "" || !validSpeechLanguage(normalizeSpeechLanguage(request.Language)) {
			return errors.New("음성 요청 형식 오류")
		}
		response := ttsWorkerResponse{ID: request.ID}
		samples, rate, genErr := synth.Generate(request.Text, request.Language)
		if genErr == nil {
			response.PCM, genErr = pcmFromNative(samples, rate)
		}
		if genErr != nil {
			response.Error = "요청한 언어의 로컬 음성을 생성하지 못했습니다"
		}
		if err = writer.Encode(response); err != nil {
			return err
		}
	}
}
