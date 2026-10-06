//go:build !windows

package main

import (
	"context"
	"errors"
)

func CheckTTSReady(ctx context.Context) bool {
	return false
}

func SynthesizePrivatePlatform(ctx context.Context, text, lang string) ([]byte, error) {
	return nil, errors.New("TTS local is unsupported on NonWindows")
}
