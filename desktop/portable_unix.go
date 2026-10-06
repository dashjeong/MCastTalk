//go:build !windows

package main

import (
	"golang.org/x/sys/unix"
	"os"
)

func portableReparse(os.FileInfo) bool { return false }
func portableFreeDisk(path string) (int64, error) {
	var s unix.Statfs_t
	err := unix.Statfs(path, &s)
	if err != nil {
		return 0, err
	}
	return int64(s.Bavail) * int64(s.Bsize), nil
}
