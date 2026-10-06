//go:build !windows

package main

import (
	"context"
	"runtime"
	"time"
)

func Diagnose(ctx context.Context, dataDir string) Diagnostic {
	d := Diagnostic{
		OS:               runtime.GOOS,
		Arch:             runtime.GOARCH,
		CPU:              "Unix CPU",
		Cores:            runtime.NumCPU(),
		Measured:         false,
		CheckedAt:        time.Now().UTC(),
		Devices:          []Device{{Name: "CPU", Kind: "cpu", Vendor: "Unknown", VRAMGB: 0, Verified: false}},
		Warnings:         []string{"Windows hardware required for full capability. Unix APIs mocked."},
		MobileEquivalent: false,
	}
	d.Recommendations = RecommendModels(d)
	return d
}
