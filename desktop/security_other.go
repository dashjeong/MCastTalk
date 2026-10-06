//go:build !windows

package main

import "os"

// Non-Windows development uses private OS permissions. Windows always uses DPAPI.
func sealSecret(v []byte) ([]byte, error) { return v, nil }
func openSecret(v []byte) ([]byte, error) { return v, nil }
func replaceFile(from, to string) error   { return os.Rename(from, to) }
