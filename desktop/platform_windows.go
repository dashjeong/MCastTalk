//go:build windows

package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"unicode/utf16"
)

func getPowershellCommand(script string) []string {
	encoded := utf16LEBase64(script)
	return []string{"powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded}
}

func utf16LEBase64(s string) string {
	encoded := utf16.Encode([]rune(s))
	b := make([]byte, len(encoded)*2)
	for i, v := range encoded {
		binary.LittleEndian.PutUint16(b[i*2:], v)
	}
	return base64.StdEncoding.EncodeToString(b)
}

const ttsScript = `
param()
$ErrorActionPreference = "Stop"
$json = [Console]::In.ReadToEnd() | ConvertFrom-Json
if (-not $json) { exit 1 }
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $found = $false
    $baseLanguage = ($json.lang -split '-')[0]
    $available = @($synth.GetInstalledVoices() | Where-Object { $_.Enabled })
    $matching = @($available | Where-Object { $_.VoiceInfo.Culture.Name -eq $json.lang })
    if ($matching.Count -eq 0) {
        $matching = @($available | Where-Object { $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq $baseLanguage })
    }
    foreach ($voice in $matching) {
        if ($voice.Enabled) {
            $synth.SelectVoice($voice.VoiceInfo.Name)
            $found = $true
            break
        }
    }
    if (-not $found) {
        Write-Error "Voice not found for language: $($json.lang)"
        exit 2
    }
    $format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    $synth.SetOutputToWaveFile($json.path, $format)
    $synth.Speak($json.text)
} finally {
    $synth.Dispose()
}
`

const listVoicesScript = `
param()
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $voices = @()
    foreach ($voice in $synth.GetInstalledVoices()) {
        if ($voice.Enabled) {
            $voices += @{
                Culture = $voice.VoiceInfo.Culture.TwoLetterISOLanguageName
            }
        }
    }
    $voices | ConvertTo-Json -Compress | Write-Output
} finally {
    $synth.Dispose()
}
`

func CheckTTSReady(ctx context.Context) bool {
	cmdArgs := getPowershellCommand(listVoicesScript)
	cmd := exec.CommandContext(ctx, cmdArgs[0], cmdArgs[1:]...)
	out, err := cmd.Output()
	if err != nil {
		return false
	}
	var voices []struct {
		Culture string `json:"Culture"`
	}
	if err := json.Unmarshal(out, &voices); err != nil {
		var single struct {
			Culture string `json:"Culture"`
		}
		if json.Unmarshal(out, &single) != nil || single.Culture == "" {
			return false
		}
		return true
	}
	return len(voices) > 0
}

func SynthesizePrivatePlatform(ctx context.Context, text, lang string) ([]byte, error) {
	if text == "" {
		return nil, errors.New("empty text")
	}

	lang = strings.ToLower(strings.ReplaceAll(lang, "_", "-"))

	tempDir, err := os.MkdirTemp("", "tts-run-*")
	if err != nil {
		return nil, err
	}
	// Secure temp dir
	os.Chmod(tempDir, 0700)
	defer os.RemoveAll(tempDir)

	wavPath := tempDir + "\\out.wav"

	inputData := map[string]string{
		"text": text,
		"lang": lang,
		"path": wavPath,
	}
	inputJSON, _ := json.Marshal(inputData)

	cmdArgs := getPowershellCommand(ttsScript)
	cmd := exec.CommandContext(ctx, cmdArgs[0], cmdArgs[1:]...)
	cmd.Stdin = bytes.NewReader(inputJSON)

	out, err := cmd.CombinedOutput()
	if err != nil {
		if bytes.Contains(out, []byte("Voice not found for language:")) {
			return nil, fmt.Errorf("%s 언어의 Windows 음성팩이 없습니다. Windows 언어·음성 설정에서 해당 음성을 준비하고 다시 검증하세요", lang)
		}
		return nil, fmt.Errorf("TTS execution failed: %v, output: %s", err, string(out))
	}

	wav, err := os.ReadFile(wavPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read WAV: %v", err)
	}

	if err := validateWAV(wav); err != nil {
		return nil, err
	}

	var pcm []byte
	for off := 12; off+8 <= len(wav); {
		size := int(binary.LittleEndian.Uint32(wav[off+4 : off+8]))
		if size > len(wav)-off-8 {
			return nil, errors.New("invalid TTS WAV chunk bounds")
		}
		tag := string(wav[off : off+4])
		if tag == "data" {
			pcm = wav[off+8 : off+8+size]
			break
		}
		if tag == "fmt " {
			// Validate 16kHz mono 16-bit
			if size >= 16 {
				channels := binary.LittleEndian.Uint16(wav[off+10 : off+12])
				sampleRate := binary.LittleEndian.Uint32(wav[off+12 : off+16])
				bitsPerSample := binary.LittleEndian.Uint16(wav[off+22 : off+24])
				if channels != 1 || sampleRate != 16000 || bitsPerSample != 16 {
					return nil, fmt.Errorf("invalid WAV format: %d channels, %d Hz, %d bits", channels, sampleRate, bitsPerSample)
				}
			}
		}
		off += 8 + size + (size % 2)
	}

	if len(pcm) == 0 {
		return nil, errors.New("TTS WAV data chunk missing")
	}

	return pcm, nil
}
