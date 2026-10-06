//go:build windows && amd64

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io/fs"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

type cStrings struct {
	ptrs []uintptr
	bufs [][]byte
}

func (c *cStrings) add(s string) uintptr {
	if s == "" {
		return 0
	}
	buf := append([]byte(s), 0)
	c.bufs = append(c.bufs, buf)
	ptr := uintptr(unsafe.Pointer(&buf[0]))
	c.ptrs = append(c.ptrs, ptr)
	return ptr
}

func (c *cStrings) keepAlive() {
	runtime.KeepAlive(c.bufs)
}

type ttsImpl struct {
	supertonicHandle uintptr
	kokoroHandle     uintptr
	lib              windows.Handle
	pDestroy         uintptr
	pGenerate        uintptr
	pDestroyAudio    uintptr
}

// The C API owns these addresses. Copy through Windows rather than converting
// an untrusted uintptr to a Go pointer; invalid native output fails closed.
func readNativeMemory(address uintptr, destination *byte, size uintptr) error {
	if address == 0 || destination == nil || size == 0 || address > ^uintptr(0)-size {
		return errors.New("invalid native memory range")
	}
	var copied uintptr
	if err := windows.ReadProcessMemory(windows.CurrentProcess(), address, destination, size, &copied); err != nil || copied != size {
		return errors.New("native memory could not be read")
	}
	return nil
}

func (t *ttsImpl) Close() {
	if t.supertonicHandle != 0 {
		syscall.SyscallN(t.pDestroy, t.supertonicHandle)
		t.supertonicHandle = 0
	}
	if t.kokoroHandle != 0 {
		syscall.SyscallN(t.pDestroy, t.kokoroHandle)
		t.kokoroHandle = 0
	}
	if t.lib != 0 {
		windows.FreeLibrary(t.lib)
		t.lib = 0
	}
}

func (t *ttsImpl) Generate(text, language string) ([]float32, int, error) {
	if text == "" {
		return nil, 0, errors.New("empty text")
	}

	lang := normalizeSpeechLanguage(language)
	lang = strings.Split(lang, "-")[0]
	if !bundledTTSLanguagesSupported([]string{lang}) {
		return nil, 0, errors.New("unsupported language")
	}

	var h uintptr
	genCfg := make([]byte, 56)
	binary.LittleEndian.PutUint32(genCfg[0:4], math.Float32bits(0.2))
	binary.LittleEndian.PutUint32(genCfg[4:8], math.Float32bits(1.0))

	var cstrs cStrings
	defer cstrs.keepAlive()

	textPtr := cstrs.add(text)

	if lang == "zh" {
		if t.kokoroHandle == 0 {
			return nil, 0, errors.New("model not loaded")
		}
		h = t.kokoroHandle
		binary.LittleEndian.PutUint32(genCfg[8:12], 3)
	} else {
		if t.supertonicHandle == 0 {
			return nil, 0, errors.New("requested language model not loaded")
		} else {
			h = t.supertonicHandle
			binary.LittleEndian.PutUint32(genCfg[8:12], 0)
			binary.LittleEndian.PutUint32(genCfg[40:44], 8)
			binary.LittleEndian.PutUint64(genCfg[48:56], uint64(cstrs.add(fmt.Sprintf(`{"lang":"%s"}`, lang))))
		}
	}

	audioPtr, _, _ := syscall.SyscallN(t.pGenerate, h, textPtr, uintptr(unsafe.Pointer(&genCfg[0])), 0, 0)
	runtime.KeepAlive(genCfg)
	if audioPtr == 0 {
		return nil, 0, errors.New("generation failed")
	}

	defer syscall.SyscallN(t.pDestroyAudio, audioPtr)
	var header [16]byte
	if err := readNativeMemory(audioPtr, &header[0], uintptr(len(header))); err != nil {
		return nil, 0, err
	}
	samplesPtr := uintptr(binary.LittleEndian.Uint64(header[:8]))
	n := int32(binary.LittleEndian.Uint32(header[8:12]))
	sampleRate := int32(binary.LittleEndian.Uint32(header[12:16]))

	if sampleRate < 8000 || sampleRate > 96000 {
		return nil, 0, errors.New("invalid sample rate")
	}
	if samplesPtr == 0 || n <= 0 || n > sampleRate*30 {
		return nil, 0, errors.New("invalid length")
	}

	res := make([]float32, n)
	if err := readNativeMemory(samplesPtr, (*byte)(unsafe.Pointer(&res[0])), uintptr(n)*4); err != nil {
		return nil, 0, err
	}
	for i := int32(0); i < n; i++ {
		val := res[i]
		if math.IsNaN(float64(val)) || math.IsInf(float64(val), 0) {
			return nil, 0, errors.New("non-finite sample")
		}
	}

	return res, int(sampleRate), nil
}

func newNativeTTSSynthesizer(init nativeTTSInit) (nativeTTSSynthesizer, error) {
	var dllPath string
	err := filepath.WalkDir(init.RuntimeDir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() && !d.Type().IsRegular() {
			return errors.New("invalid file type")
		}
		rel, relErr := filepath.Rel(init.RuntimeDir, path)
		if relErr != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return errors.New("path escape")
		}
		if !d.IsDir() && d.Name() == "sherpa-onnx-c-api.dll" {
			if dllPath != "" {
				return errors.New("duplicate dll")
			}
			dllPath = path
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if dllPath == "" {
		return nil, errors.New("dll not found")
	}

	absDll, err := filepath.Abs(dllPath)
	if err != nil {
		return nil, err
	}

	lib, err := windows.LoadLibraryEx(absDll, 0, windows.LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|windows.LOAD_LIBRARY_SEARCH_SYSTEM32)
	if err != nil {
		return nil, err
	}

	cleanup := func() {
		windows.FreeLibrary(lib)
	}

	pSherpaOnnxVersion, err := windows.GetProcAddress(lib, "SherpaOnnxGetVersionStr")
	if err != nil {
		cleanup()
		return nil, err
	}
	pCreate, err := windows.GetProcAddress(lib, "SherpaOnnxCreateOfflineTts")
	if err != nil {
		cleanup()
		return nil, err
	}
	pDestroy, err := windows.GetProcAddress(lib, "SherpaOnnxDestroyOfflineTts")
	if err != nil {
		cleanup()
		return nil, err
	}
	pGenerate, err := windows.GetProcAddress(lib, "SherpaOnnxOfflineTtsGenerateWithConfig")
	if err != nil {
		cleanup()
		return nil, err
	}
	pDestroyAudio, err := windows.GetProcAddress(lib, "SherpaOnnxDestroyOfflineTtsGeneratedAudio")
	if err != nil {
		cleanup()
		return nil, err
	}

	verPtr, _, _ := syscall.SyscallN(pSherpaOnnxVersion)
	if verPtr == 0 {
		cleanup()
		return nil, errors.New("version call failed")
	}

	var version []byte
	for i := uintptr(0); i < 64; i++ {
		var value byte
		if verPtr > ^uintptr(0)-i || readNativeMemory(verPtr+i, &value, 1) != nil {
			cleanup()
			return nil, errors.New("invalid native version string")
		}
		if value == 0 {
			break
		}
		version = append(version, value)
	}
	if len(version) == 64 {
		cleanup()
		return nil, errors.New("invalid native version string")
	}
	verStr := string(version)

	if verStr != "1.13.8" {
		cleanup()
		return nil, errors.New("unsupported version")
	}

	threads := init.Threads
	if threads < 1 {
		threads = 1
	}
	if threads > 4 {
		threads = 4
	}

	findBase := func(dir, target string) (string, error) {
		if dir == "" {
			return "", nil
		}
		var base string
		err := filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if !d.IsDir() && !d.Type().IsRegular() {
				return errors.New("invalid model file type")
			}
			if !d.IsDir() && d.Name() == target {
				if base != "" {
					return errors.New("ambiguous model directory")
				}
				base = filepath.Dir(path)
			}
			return nil
		})
		if err == nil && base == "" {
			err = errors.New("model config missing")
		}
		return base, err
	}

	impl := &ttsImpl{
		lib:           lib,
		pDestroy:      pDestroy,
		pGenerate:     pGenerate,
		pDestroyAudio: pDestroyAudio,
	}

	baseSup, err := findBase(init.SupertonicDir, "tts.json")
	if err != nil {
		impl.Close()
		return nil, err
	}
	if baseSup != "" {
		var cstrs cStrings
		defer cstrs.keepAlive()

		cfg := make([]byte, 448)
		binary.LittleEndian.PutUint32(cfg[56:60], uint32(threads))
		binary.LittleEndian.PutUint32(cfg[60:64], 0)
		binary.LittleEndian.PutUint64(cfg[64:72], uint64(cstrs.add("cpu")))
		binary.LittleEndian.PutUint32(cfg[424:428], 1)
		binary.LittleEndian.PutUint32(cfg[440:444], math.Float32bits(0.2))

		binary.LittleEndian.PutUint64(cfg[360:368], uint64(cstrs.add(filepath.Join(baseSup, "duration_predictor.int8.onnx"))))
		binary.LittleEndian.PutUint64(cfg[368:376], uint64(cstrs.add(filepath.Join(baseSup, "text_encoder.int8.onnx"))))
		binary.LittleEndian.PutUint64(cfg[376:384], uint64(cstrs.add(filepath.Join(baseSup, "vector_estimator.int8.onnx"))))
		binary.LittleEndian.PutUint64(cfg[384:392], uint64(cstrs.add(filepath.Join(baseSup, "vocoder.int8.onnx"))))
		binary.LittleEndian.PutUint64(cfg[392:400], uint64(cstrs.add(filepath.Join(baseSup, "tts.json"))))
		binary.LittleEndian.PutUint64(cfg[400:408], uint64(cstrs.add(filepath.Join(baseSup, "unicode_indexer.bin"))))
		binary.LittleEndian.PutUint64(cfg[408:416], uint64(cstrs.add(filepath.Join(baseSup, "voice.bin"))))

		h, _, _ := syscall.SyscallN(pCreate, uintptr(unsafe.Pointer(&cfg[0])))
		runtime.KeepAlive(cfg)
		if h != 0 {
			impl.supertonicHandle = h
		} else {
			impl.Close()
			return nil, errors.New("requested Supertonic model initialization failed")
		}
	}

	baseKok, err := findBase(init.KokoroDir, "model.int8.onnx")
	if err != nil {
		impl.Close()
		return nil, err
	}
	if baseKok != "" {
		var cstrs cStrings
		defer cstrs.keepAlive()

		cfg := make([]byte, 448)
		binary.LittleEndian.PutUint32(cfg[56:60], uint32(threads))
		binary.LittleEndian.PutUint32(cfg[60:64], 0)
		binary.LittleEndian.PutUint64(cfg[64:72], uint64(cstrs.add("cpu")))
		binary.LittleEndian.PutUint32(cfg[424:428], 1)
		binary.LittleEndian.PutUint32(cfg[440:444], math.Float32bits(0.2))

		binary.LittleEndian.PutUint64(cfg[128:136], uint64(cstrs.add(filepath.Join(baseKok, "model.int8.onnx"))))
		binary.LittleEndian.PutUint64(cfg[136:144], uint64(cstrs.add(filepath.Join(baseKok, "voices.bin"))))
		binary.LittleEndian.PutUint64(cfg[144:152], uint64(cstrs.add(filepath.Join(baseKok, "tokens.txt"))))
		binary.LittleEndian.PutUint64(cfg[152:160], uint64(cstrs.add(filepath.Join(baseKok, "espeak-ng-data"))))
		binary.LittleEndian.PutUint32(cfg[160:164], math.Float32bits(1.0))

		lexicon := filepath.Join(baseKok, "lexicon-us-en.txt") + "," + filepath.Join(baseKok, "lexicon-zh.txt")
		binary.LittleEndian.PutUint64(cfg[176:184], uint64(cstrs.add(lexicon)))
		binary.LittleEndian.PutUint64(cfg[184:192], uint64(cstrs.add("zh")))
		rules := []string{}
		for _, name := range []string{"date-zh.fst", "number-zh.fst", "phone-zh.fst"} {
			path := filepath.Join(baseKok, name)
			if st, e := os.Stat(path); e != nil || !st.Mode().IsRegular() {
				impl.Close()
				return nil, errors.New("Chinese normalization rules missing")
			}
			rules = append(rules, path)
		}
		binary.LittleEndian.PutUint64(cfg[416:424], uint64(cstrs.add(strings.Join(rules, ","))))

		h, _, _ := syscall.SyscallN(pCreate, uintptr(unsafe.Pointer(&cfg[0])))
		runtime.KeepAlive(cfg)
		if h != 0 {
			impl.kokoroHandle = h
		} else {
			impl.Close()
			return nil, errors.New("requested Kokoro model initialization failed")
		}
	}

	if impl.supertonicHandle == 0 && impl.kokoroHandle == 0 {
		impl.Close()
		return nil, errors.New("no models loaded")
	}

	return impl, nil
}
