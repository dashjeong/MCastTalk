package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"strings"
	"unicode/utf8"
)

// Pure parser for the fixed Windows inventory probe. Inventory never verifies
// that a model backend or driver is usable.
type windowsHardwareProbe struct {
	CPU      string
	Devices  []Device
	Warnings []string
}

// Word boundaries matter: "USB Input Device" contains the letters "npu".
// Device inventory remains a candidate until the backend is actually tested.
var npuDeviceName = regexp.MustCompile(`(?i)\b(?:NPU|Neural|AI Boost|Ryzen AI|Hexagon)\b`)

func parseWindowsHardwareProbe(raw []byte) (windowsHardwareProbe, error) {
	out := windowsHardwareProbe{Devices: []Device{}, Warnings: []string{}}
	raw = bytes.TrimSpace(bytes.TrimPrefix(raw, []byte{0xef, 0xbb, 0xbf}))
	if len(raw) == 0 || len(raw) > 1<<20 || !utf8.Valid(raw) {
		return out, errors.New("Windows 장치 진단의 UTF-8 응답이 없거나 유효하지 않습니다")
	}
	if raw[0] != '{' {
		return out, errors.New("Windows 장치 진단의 JSON 객체가 없습니다")
	}
	var doc struct {
		CPU         string          `json:"cpu"`
		GPUs        json.RawMessage `json:"gpus"`
		NPUs        json.RawMessage `json:"npus"`
		GPUComplete *bool           `json:"gpuComplete"`
		NPUComplete *bool           `json:"npuComplete"`
		Warnings    []string        `json:"warnings"`
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	if err := decoder.Decode(&doc); err != nil {
		return out, errors.New("Windows 장치 진단 JSON을 읽지 못했습니다")
	}
	var trailing any
	if decoder.Decode(&trailing) != io.EOF {
		return out, errors.New("Windows 장치 진단 JSON 뒤에 잘못된 응답이 있습니다")
	}
	out.CPU = strings.TrimSpace(doc.CPU)
	out.Warnings = append(out.Warnings, doc.Warnings...)
	type record struct {
		Name         string `json:"Name"`
		Manufacturer string `json:"Manufacturer"`
		AdapterRAM   int64  `json:"AdapterRAM"`
	}
	parseRecords := func(raw json.RawMessage) ([]record, error) {
		raw = bytes.TrimSpace(raw)
		if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
			return nil, nil
		}
		if raw[0] == '[' {
			var rows []record
			err := json.Unmarshal(raw, &rows)
			return rows, err
		}
		var row record
		if raw[0] != '{' || json.Unmarshal(raw, &row) != nil {
			return nil, errors.New("invalid device record")
		}
		return []record{row}, nil
	}
	vendor := func(name, manufacturer string) string {
		s := strings.ToLower(name + " " + manufacturer)
		switch {
		case strings.Contains(s, "nvidia"):
			return "NVIDIA"
		case strings.Contains(s, "intel"):
			return "Intel"
		case strings.Contains(s, "amd") || strings.Contains(s, "radeon"):
			return "AMD"
		case strings.Contains(s, "qualcomm"):
			return "Qualcomm"
		default:
			return "Unknown"
		}
	}
	for _, section := range []struct {
		kind     string
		raw      json.RawMessage
		complete *bool
	}{{"gpu", doc.GPUs, doc.GPUComplete}, {"npu", doc.NPUs, doc.NPUComplete}} {
		if section.complete == nil || !*section.complete || len(section.raw) == 0 {
			out.Warnings = append(out.Warnings, strings.ToUpper(section.kind)+" 장치 조회를 완료하지 못했습니다. 장치가 없다는 의미는 아닙니다.")
		}
		rows, err := parseRecords(section.raw)
		if err != nil {
			out.Warnings = append(out.Warnings, strings.ToUpper(section.kind)+" 장치 응답 형식이 잘못되어 목록을 확인하지 못했습니다.")
			continue
		}
		seen := map[string]bool{}
		for _, row := range rows {
			name := strings.TrimSpace(row.Name)
			if name == "" {
				continue
			}
			if section.kind == "npu" && !npuDeviceName.MatchString(name) {
				continue
			}
			key := strings.ToLower(name)
			if seen[key] {
				continue
			}
			seen[key] = true
			vram := float64(0)
			if section.kind == "gpu" && row.AdapterRAM > 0 {
				vram = float64(row.AdapterRAM) / (1 << 30)
			}
			if row.AdapterRAM < 0 {
				out.Warnings = append(out.Warnings, "GPU 메모리 정보가 유효하지 않습니다. 실제 여유 VRAM 검증이 필요합니다.")
			}
			out.Devices = append(out.Devices, Device{Name: name, Kind: section.kind, Vendor: vendor(name, row.Manufacturer), VRAMGB: vram, Verified: false})
		}
	}
	return out, nil
}
