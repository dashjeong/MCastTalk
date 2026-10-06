//go:build windows

package main

import (
	"golang.org/x/sys/windows"
	"os"
	"syscall"
)

func portableReparse(st os.FileInfo) bool {
	if data, ok := st.Sys().(*syscall.Win32FileAttributeData); ok {
		return data.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0
	}
	return true
}
func portableFreeDisk(path string) (int64, error) {
	p, e := windows.UTF16PtrFromString(path)
	if e != nil {
		return 0, e
	}
	var free, total, all uint64
	e = windows.GetDiskFreeSpaceEx(p, &free, &total, &all)
	return int64(free), e
}
