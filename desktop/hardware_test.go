package main

import (
	"testing"
)

func TestHardware_Recommendations(t *testing.T) {
	d := Diagnostic{
		Measured:       true,
		FreeDiskGB:     40,
		AvailableRAMGB: 16.0,
		Devices: []Device{
			{Kind: "gpu", Vendor: "AMD", VRAMGB: 8.0},
			{Kind: "npu", Vendor: "Intel", VRAMGB: 0},
		},
	}
	recs := RecommendModels(d)
	if len(recs) < 3 {
		t.Errorf("expected recommendations, got %d", len(recs))
	}
	for _, r := range recs {
		if r.Status == "runnable" || r.Backend == "cuda" {
			t.Fatal("unverified AMD inventory claimed CUDA runnable")
		}
	}
}
