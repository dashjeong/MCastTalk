package main

import (
	"context"
	"reflect"
	"runtime"
	"testing"
)

func TestSpeechLanguagesEnabledCulturesAndBaseFallback(t *testing.T) {
	for _, tc := range []struct {
		name, raw                    string
		required, available, missing []string
	}{
		{"single", `{"culture":"EN-US","enabled":true}`, []string{"en_US", "en-GB", "EN", "ko"}, []string{"en-us"}, []string{"ko"}},
		{"array-dedup-disabled", `[{"culture":"ko-KR","enabled":false},{"culture":"ja-JP","enabled":true},{"culture":"en-US","enabled":true},{"culture":"EN-us","enabled":true}]`, []string{"KO", "ja", "en", "ko"}, []string{"en-us", "ja-jp"}, []string{"ko"}},
		{"chinese-base", `[{"culture":"zh-TW","enabled":true}]`, []string{"zh-CN", "zh-Hans", "ja", "JA"}, []string{"zh-tw"}, []string{"ja"}},
		{"no-installed-voices", `[]`, []string{"ko", "en"}, []string{}, []string{"en", "ko"}},
		{"utf8-bom", "\xef\xbb\xbf" + `[{"culture":"ko-KR","enabled":true}]`, []string{"ko"}, []string{"ko-kr"}, []string{}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			status := parseSpeechLanguages([]byte(tc.raw), tc.required)
			if !status.Supported || status.Error != "" || !reflect.DeepEqual(status.Available, tc.available) || !reflect.DeepEqual(status.Missing, tc.missing) {
				t.Fatalf("speech status %+v", status)
			}
		})
	}
}

func TestSpeechLanguagesProbeErrorsDoNotAssertMissing(t *testing.T) {
	for _, raw := range []string{"", "null", "not json", `{"culture":"en-US"}`, `[{"culture":"unknown","enabled":true}]`, `[{"enabled":true}]`, `[{"culture":"en-US","enabled":"true"}]`, `[] trailing`} {
		status := parseSpeechLanguages([]byte(raw), []string{"ko"})
		if status.Error == "" || len(status.Missing) != 0 || len(status.Available) != 0 {
			t.Fatalf("unknown probe claimed voice availability: %q %+v", raw, status)
		}
	}
	status := parseSpeechLanguages([]byte(`[]`), []string{"invalid language"})
	if status.Error == "" || len(status.Missing) != 0 {
		t.Fatalf("invalid requested language claimed missing voice: %+v", status)
	}
}

func TestSpeechLanguagesUnsupportedAndCancelled(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("pure tests do not invoke actual Windows voice APIs")
	}
	status := InspectSpeechLanguages(context.Background(), []string{"ko"})
	if status.Supported || status.Error == "" || len(status.Missing) != 0 {
		t.Fatalf("unsupported platform claims missing voice: %+v", status)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	status = InspectSpeechLanguages(ctx, []string{"ko"})
	if status.Supported || status.Error == "" || len(status.Missing) != 0 {
		t.Fatalf("cancelled probe claims missing voice: %+v", status)
	}
}
