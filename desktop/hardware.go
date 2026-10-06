package main

import "strings"

func RecommendModels(d Diagnostic) []ModelRecommendation {
	var recs []ModelRecommendation
	reserveGB := 6.0 // OS + Whisper + Context
	avail := d.AvailableRAMGB - reserveGB

	if d.Measured && avail >= 4.0 && d.FreeDiskGB >= 8 {
		recs = append(recs, ModelRecommendation{ID: "translategemma-4b-q4", Status: "candidate", Backend: "cpu", Reason: "Candidate system RAM"})
		recs = append(recs, ModelRecommendation{ID: "qwen3-4b-q4", Status: "candidate", Backend: "cpu", Reason: "Candidate system RAM"})
	} else {
		recs = append(recs, ModelRecommendation{ID: "translategemma-4b-q4", Status: "unavailable", Backend: "cpu", Reason: "Insufficient RAM"})
		recs = append(recs, ModelRecommendation{ID: "qwen3-4b-q4", Status: "unavailable", Backend: "cpu", Reason: "Insufficient RAM"})
	}

	for _, dev := range d.Devices {
		if !d.Measured || d.FreeDiskGB < 8 || d.AvailableRAMGB < 6 {
			continue
		}
		if dev.Kind == "gpu" && dev.VRAMGB >= 4.0 {
			backend := "vulkan"
			if dev.Vendor == "NVIDIA" {
				backend = "cuda"
			}
			recs = append(recs, ModelRecommendation{ID: "translategemma-4b-q4", Status: "candidate", Backend: backend, Reason: "장치 목록 후보입니다. 드라이버·모델 구동·실측 지연 검증이 필요합니다"})
		} else if dev.Kind == "npu" && (dev.Vendor == "Intel" || strings.Contains(strings.ToLower(dev.Name), "intel")) {
			recs = append(recs, ModelRecommendation{ID: "qwen3-4b-q4", Status: "candidate", Backend: "openvino-npu", Reason: "Intel NPU 후보입니다. 모델 호환성과 실제 실행 검증이 필요합니다"})
		}
	}

	return recs
}
