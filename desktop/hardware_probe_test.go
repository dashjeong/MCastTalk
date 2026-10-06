package main

import (
	"strings"
	"testing"
)

// JSON fixtures establish parsing and honest inventory states only. They do
// not execute Windows CIM/registry probes or verify GPU/NPU model capacity.
func TestWindowsHardwareProbeJSONSingleAndArray(t *testing.T) {
	for _, tc := range []struct {
		name, json string
		cpu        string
		want       int
		vendors    []string
	}{
		{"singleton", `{"cpu":"Intel CPU","gpus":{"Name":"NVIDIA 테스트 GPU","AdapterRAM":4294967296},"npus":{"Name":"Intel Neural NPU","Manufacturer":"Intel"},"gpuComplete":true,"npuComplete":true,"warnings":[]}`, "Intel CPU", 2, []string{"NVIDIA", "Intel"}},
		{"array", `{"cpu":"AMD CPU","gpus":[{"Name":"AMD Radeon GPU","AdapterRAM":2147483648},{"Name":"Intel Graphics","AdapterRAM":null}],"npus":[{"Name":"Neural processor","Manufacturer":"Qualcomm"}],"gpuComplete":true,"npuComplete":true,"warnings":[]}`, "AMD CPU", 3, []string{"AMD", "Intel", "Qualcomm"}},
		{"utf8-bom", "\xef\xbb\xbf" + `{"cpu":"가상 CPU","gpus":[{"Name":"가상 디스플레이 어댑터","AdapterRAM":0}],"npus":null,"gpuComplete":true,"npuComplete":true,"warnings":[]}`, "가상 CPU", 1, []string{"Unknown"}},
		{"empty-complete", `{"cpu":" CPU ","gpus":[],"npus":[],"gpuComplete":true,"npuComplete":true,"warnings":[]}`, "CPU", 0, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			probe, err := parseWindowsHardwareProbe([]byte(tc.json))
			if err != nil || probe.CPU != tc.cpu || len(probe.Devices) != tc.want || len(probe.Warnings) != 0 {
				t.Fatalf("parse: %+v err=%v", probe, err)
			}
			for i, device := range probe.Devices {
				if device.Vendor != tc.vendors[i] || device.Verified {
					t.Fatalf("inventory claimed backend verification or wrong vendor: %+v", device)
				}
			}
			if tc.name == "singleton" && (probe.Devices[0].VRAMGB != 4 || probe.Devices[1].VRAMGB != 0) {
				t.Fatalf("RAM parsing: %+v", probe.Devices)
			}
		})
	}
}

func TestWindowsHardwareProbePartialAndInvalidSectionRemainUnverified(t *testing.T) {
	for _, tc := range []struct {
		name, json string
		want       int
		warning    string
	}{
		{"provider-failed", `{"gpus":{"Name":"NVIDIA GPU","AdapterRAM":1073741824},"npus":[],"gpuComplete":true,"npuComplete":false,"warnings":["NPU CIM 조회 미완료"]}`, 1, "미완료"},
		{"missing-completion", `{"gpus":{"Name":"AMD GPU","AdapterRAM":1073741824},"npus":[]}`, 1, "조회를 완료하지 못했습니다"},
		{"malformed-gpu-preserves-npu", `{"gpus":"not a device","npus":{"Name":"Neural device","Manufacturer":"Intel"},"gpuComplete":true,"npuComplete":true,"warnings":[]}`, 1, "응답 형식"},
		{"negative-ram", `{"gpus":{"Name":"NVIDIA GPU","AdapterRAM":-1},"npus":[],"gpuComplete":true,"npuComplete":true,"warnings":[]}`, 1, "메모리 정보"},
		{"duplicate-and-empty", `{"gpus":[{"Name":""},{"Name":"AMD GPU","AdapterRAM":null},{"Name":" amd gpu ","AdapterRAM":123}],"npus":[],"gpuComplete":false,"npuComplete":true,"warnings":[]}`, 1, "조회를 완료하지 못했습니다"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			probe, err := parseWindowsHardwareProbe([]byte(tc.json))
			if err != nil || len(probe.Devices) != tc.want || !strings.Contains(strings.Join(probe.Warnings, " "), tc.warning) {
				t.Fatalf("partial probe: %+v err=%v", probe, err)
			}
			for _, device := range probe.Devices {
				if device.Verified || device.VRAMGB < 0 {
					t.Fatalf("partial inventory claimed readiness: %+v", device)
				}
			}
		})
	}
}

func TestWindowsHardwareProbeRejectsIncompleteOutput(t *testing.T) {
	for _, raw := range []string{"", "null", "[]", `{"cpu":`, `{"gpus":[]} trailing`, string([]byte{'{', 0xff, '}'})} {
		if result, err := parseWindowsHardwareProbe([]byte(raw)); err == nil || len(result.Devices) != 0 {
			t.Fatalf("invalid stdout accepted: %q %+v %v", raw, result, err)
		}
	}
}

func TestWindowsHardwareProbeRejectsUSBInputAsNPU(t *testing.T) {
	probe, err := parseWindowsHardwareProbe([]byte(`{"gpus":[],"npus":[{"Name":"USB Input Device"},{"Name":"Synaptics Input Controller"},{"Name":"Intel(R) AI Boost"},{"Name":"AMD NPU Device"}],"gpuComplete":true,"npuComplete":true}`))
	if err != nil || len(probe.Devices) != 2 || probe.Devices[0].Name != "Intel(R) AI Boost" || probe.Devices[1].Name != "AMD NPU Device" {
		t.Fatalf("input devices misclassified as NPU: %+v, %v", probe, err)
	}
}
