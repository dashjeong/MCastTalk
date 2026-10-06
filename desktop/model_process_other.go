//go:build !windows

package main

import "os/exec"

func hideBackgroundModelWindow(_ *exec.Cmd) {}
