package main

import (
	"context"
	"errors"
	"math"
	"os"
	"strings"
	"testing"
	"time"
)

func TestModelValidationAllowanceIsSizeBoundedAndOverflowSafe(t *testing.T) {
	policy := productionModelValidationPolicy()
	for _, tc := range []struct {
		bytes int64
		want  time.Duration
	}{
		{32, 30 * time.Second},
		{190085487, 30 * time.Second},
		{2497280256, 164 * time.Second},
		{portableMaxFile, 240 * time.Second},
	} {
		got, err := modelValidationAllowance(tc.bytes, policy)
		if err != nil || got != tc.want {
			t.Fatalf("bytes=%d allowance=%v error=%v, want=%v", tc.bytes, got, err, tc.want)
		}
	}
	for _, bytes := range []int64{0, -1, portableMaxFile + 1, math.MaxInt64} {
		if _, err := modelValidationAllowance(bytes, policy); err == nil {
			t.Fatal("invalid/overflowing file length accepted")
		}
	}
	policy.bytesPerUnit = 0
	if _, err := modelValidationAllowance(32, policy); err == nil {
		t.Fatal("invalid throughput policy accepted")
	}
}

func TestStartupBudgetUsesTrustedPinsAndPreservesBaseBound(t *testing.T) {
	cfg := defaultConfig()
	cfg.TranslationModel, cfg.STTModel = "qwen3-4b-q4", "whisper-small-q5"
	assets := map[string]InstalledAsset{}
	for _, profile := range Catalog() {
		if profile.ID == cfg.TranslationModel || profile.ID == cfg.STTModel {
			assets[profile.ID] = InstalledAsset{ID: profile.ID, SHA256: profile.SHA256, Bytes: profile.Bytes}
		}
	}
	for _, tc := range []struct {
		base, want time.Duration
	}{{180 * time.Second, 374 * time.Second}, {120 * time.Second, 314 * time.Second}} {
		ctx, cancel, err := newEngineStartupContext(t.Context(), tc.base, cfg, assets, Catalog())
		if err != nil {
			t.Fatal(err)
		}
		budget := ctx.Value(engineStartupBudgetKey{}).(engineStartupBudget)
		if budget.total != tc.want || budget.total > engineStartupHardLimit || len(budget.models) != 2 {
			t.Fatal("derived deadline lost original base, exceeded hard bound or counted unrelated files")
		}
		cancel()
	}
}

func TestStartupBudgetRejectsMissingChangedDuplicateAndOversizedMetadata(t *testing.T) {
	f := portableTestOpen(t, "startup-budget-pins")
	cfg := f.store.config()
	for _, mutation := range []string{"missing", "sha", "bytes", "asset-id", "profile-task", "duplicate", "huge", "private-profile"} {
		t.Run(mutation, func(t *testing.T) {
			assets, profiles := f.store.assets(), f.assets.Registry()
			asset := assets[cfg.TranslationModel]
			switch mutation {
			case "missing":
				delete(assets, cfg.TranslationModel)
			case "sha":
				asset.SHA256 = strings.Repeat("b", 64)
			case "bytes":
				asset.Bytes++
			case "asset-id":
				asset.ID = "other"
			}
			if mutation != "missing" {
				assets[cfg.TranslationModel] = asset
			}
			for i, profile := range profiles {
				if profile.ID != cfg.TranslationModel {
					continue
				}
				switch mutation {
				case "profile-task":
					profiles[i].Task = "runtime"
				case "duplicate":
					profiles = append(profiles, profile)
				case "huge":
					profiles[i].Bytes = math.MaxInt64
					asset.Bytes = math.MaxInt64
					assets[cfg.TranslationModel] = asset
				case "private-profile":
					profiles[i].Source = ttsPrivateCanary
				}
				break
			}
			ctx, cancel, err := newEngineStartupContext(t.Context(), 180*time.Second, cfg, assets, profiles)
			if err == nil || ctx != nil || cancel != nil || strings.Contains(err.Error(), ttsPrivateCanary) {
				t.Fatal("untrusted metadata produced a startup context or exposed private source text")
			}
		})
	}
}

func TestStartupBudgetLargestSupportedModelsReachOnlyHardBound(t *testing.T) {
	f := portableTestOpen(t, "startup-budget-hard-bound")
	cfg, assets, profiles := f.store.config(), f.store.assets(), f.assets.Registry()
	for i, profile := range profiles {
		if profile.ID != cfg.TranslationModel && profile.ID != cfg.STTModel {
			continue
		}
		profiles[i].Bytes = portableMaxFile
		asset := assets[profile.ID]
		asset.Bytes = portableMaxFile
		assets[profile.ID] = asset
	}
	ctx, cancel, err := newEngineStartupContext(t.Context(), 180*time.Second, cfg, assets, profiles)
	if err != nil {
		t.Fatal(err)
	}
	defer cancel()
	if budget := ctx.Value(engineStartupBudgetKey{}).(engineStartupBudget); budget.total != engineStartupHardLimit {
		t.Fatal("largest supported profiles did not retain the hard overall limit")
	}
	for _, base := range []time.Duration{0, -time.Second, 181 * time.Second, time.Duration(math.MaxInt64)} {
		if ctx, cancel, err := newEngineStartupContext(t.Context(), base, cfg, assets, profiles); err == nil || ctx != nil || cancel != nil {
			t.Fatal("invalid caller budget bypassed the hard overall limit")
		}
	}
}

func startupShortPolicy(allowance time.Duration) modelValidationPolicy {
	return modelValidationPolicy{0, allowance, allowance, 32, time.Millisecond}
}

func TestStartupModelLongValidationDoesNotConsumeOldBaseDeadline(t *testing.T) {
	f := portableTestOpen(t, "startup-budget-slow-hash")
	cfg, assets := f.store.config(), f.store.assets()
	ctx, cancel, err := engineStartupContextWithPolicy(t.Context(), 50*time.Millisecond, cfg, assets, f.assets.Registry(), startupShortPolicy(150*time.Millisecond))
	if err != nil {
		t.Fatal(err)
	}
	defer cancel()
	asset := assets[cfg.TranslationModel]
	err = runStartupModelValidation(ctx, asset, func(validationCtx context.Context) error {
		timer := time.NewTimer(80 * time.Millisecond)
		defer timer.Stop()
		select {
		case <-timer.C:
			return verifyModel(validationCtx, asset.Path, asset.SHA256, asset.Bytes, []byte("GGUF"))
		case <-validationCtx.Done():
			return validationCtx.Err()
		}
	})
	if err != nil || ctx.Err() != nil {
		t.Fatalf("valid file check longer than old base starved subsequent startup: %v", err)
	}
	deadline, _ := ctx.Deadline()
	if time.Until(deadline) < 100*time.Millisecond {
		t.Fatal("file reserve did not leave a bounded startup interval")
	}
}

func TestStartupModelCancellationEarlierDeadlineAndLocalExpiryWin(t *testing.T) {
	f := portableTestOpen(t, "startup-budget-cancel")
	cfg, assets := f.store.config(), f.store.assets()
	for _, mode := range []string{"caller-cancel", "earlier-parent", "hash-local-expiry", "late-nil", "late-error"} {
		t.Run(mode, func(t *testing.T) {
			parent, cancelParent := context.WithCancel(t.Context())
			if mode == "earlier-parent" {
				cancelParent()
				parent, cancelParent = context.WithTimeout(t.Context(), 30*time.Millisecond)
			}
			defer cancelParent()
			allowance := 200 * time.Millisecond
			if mode == "hash-local-expiry" || mode == "late-nil" || mode == "late-error" {
				allowance = 30 * time.Millisecond
			}
			ctx, cancel, err := engineStartupContextWithPolicy(parent, time.Second, cfg, assets, f.assets.Registry(), startupShortPolicy(allowance))
			if err != nil {
				t.Fatal(err)
			}
			defer cancel()
			entered := make(chan struct{})
			result := make(chan error, 1)
			go func() {
				result <- runStartupModelValidation(ctx, assets[cfg.TranslationModel], func(validationCtx context.Context) error {
					close(entered)
					<-validationCtx.Done()
					if mode == "late-nil" {
						return nil
					}
					if mode == "late-error" {
						return errors.New(ttsPrivateCanary)
					}
					return validationCtx.Err()
				})
			}()
			<-entered
			if mode == "caller-cancel" {
				cancelParent()
			}
			select {
			case err = <-result:
			case <-time.After(time.Second):
				t.Fatal("cancelled/expired model verification did not finish")
			}
			want := context.DeadlineExceeded
			if mode == "caller-cancel" {
				want = context.Canceled
			}
			if !errors.Is(err, want) {
				t.Fatalf("earlier bound was lost or expired validation accepted: %v", err)
			}
			if mode == "hash-local-expiry" || mode == "late-nil" || mode == "late-error" {
				if ctx.Err() != nil {
					t.Fatal("local model deadline incorrectly cancelled the entire parent")
				}
			}
		})
	}
}

func TestStartupModelMutationStillFailsAndNeverBecomesReady(t *testing.T) {
	f := portableTestOpen(t, "startup-budget-mutation")
	cfg, assets := f.store.config(), f.store.assets()
	ctx, cancel, err := newEngineStartupContext(t.Context(), 180*time.Second, cfg, assets, f.assets.Registry())
	if err != nil {
		t.Fatal(err)
	}
	defer cancel()
	asset := assets[cfg.TranslationModel]
	data, err := os.ReadFile(asset.Path)
	if err != nil {
		t.Fatal(err)
	}
	data[len(data)-1] ^= 1
	if err := os.WriteFile(asset.Path, data, 0600); err != nil {
		t.Fatal(err)
	}
	e := NewEngine(f.store.dir)
	err = e.startupStep("번역 모델 해시 확인", "", func() error { return verifyStartupModel(ctx, asset, []byte("GGUF")) })
	if err == nil || !strings.Contains(err.Error(), "sha256 mismatch") {
		t.Fatal("larger file allowance bypassed full content hashing")
	}
	if e.finishStartup(ctx, cfg, []string{err.Error()}) == nil || e.Ready().TranslationReady || e.Ready().STTReady || e.Ready().TTSReady {
		t.Fatal("altered file or failed startup became ready")
	}
	asset.Bytes++
	if runStartupModelValidation(ctx, asset, func(context.Context) error { t.Fatal("changed metadata reached verifier"); return nil }) == nil {
		t.Fatal("startup metadata changed after budget derivation")
	}
}
