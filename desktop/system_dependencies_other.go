//go:build !windows

package main

import (
	"context"
	"errors"
)

func checkSystemDependency(ctx context.Context, dep SystemDependency) (SystemDependencyStatus, error) {
	return SystemDependencyStatus{ID: dep.ID, MissingDLLs: append([]string(nil), dep.RequiredDLLs...), DLLVersions: map[string]string{}, Error: "Windows x64에서 시스템 런타임을 확인하세요"}, ctx.Err()
}
func verifySystemInstallerSignature(context.Context, string) error {
	return errors.New("Microsoft Authenticode 확인은 Windows에서 수행합니다")
}
func lockSystemInstaller(string) (func(), error) {
	return nil, errors.New("Windows 설치는 이 운영체제에서 지원하지 않습니다")
}
func runSystemInstaller(context.Context, string) (int, bool, error) {
	return -1, false, errors.New("Windows 설치는 이 운영체제에서 지원하지 않습니다")
}
