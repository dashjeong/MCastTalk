//go:build !windows || !amd64

package main

import "errors"

func newNativeTTSSynthesizer(init nativeTTSInit) (nativeTTSSynthesizer, error) {
	return nil, errors.New("이 로컬 음성 런타임은 Windows x64에서 사용합니다")
}
