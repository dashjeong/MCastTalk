package main

import (
	"context"
	"errors"
	"time"
)

const engineStartupHardLimit = 660 * time.Second
const engineHealthLimit = 60 * time.Second
const engineSTTWarmupLimit = 60 * time.Second

type modelValidationPolicy struct {
	overhead, minimum, maximum time.Duration
	bytesPerUnit               int64
	unit                       time.Duration
}

func productionModelValidationPolicy() modelValidationPolicy {
	return modelValidationPolicy{15 * time.Second, 30 * time.Second, 240 * time.Second, 16 << 20, time.Second}
}

func modelValidationAllowance(bytes int64, policy modelValidationPolicy) (time.Duration, error) {
	if bytes <= 0 || bytes > portableMaxFile || policy.bytesPerUnit <= 0 || policy.unit <= 0 || policy.overhead < 0 || policy.minimum <= 0 || policy.maximum < policy.minimum || policy.maximum > 240*time.Second || policy.overhead > policy.maximum {
		return 0, errors.New("모델 검증 시간의 고정 기준을 확인하세요")
	}
	units := bytes / policy.bytesPerUnit
	if bytes%policy.bytesPerUnit != 0 {
		units++
	}
	// Clamp before multiplication so large registered models cannot overflow a
	// duration or turn a bounded allowance into an effectively unlimited wait.
	available := policy.maximum - policy.overhead
	allowance := policy.maximum
	if units <= int64(available/policy.unit) {
		allowance = policy.overhead + time.Duration(units)*policy.unit
	}
	if allowance < policy.minimum {
		allowance = policy.minimum
	}
	return allowance, nil
}

type startupModelAllowance struct {
	sha     string
	bytes   int64
	timeout time.Duration
}
type engineStartupBudget struct {
	total  time.Duration
	models map[string]startupModelAllowance
}
type engineStartupBudgetKey struct{}

// This is a bounded reserve for file validation, not a stopwatch that grants
// every later runtime phase its full base budget. The caller's cancellation or
// earlier deadline always wins; native health and warmup retain their own caps.
func newEngineStartupContext(parent context.Context, base time.Duration, cfg Config, assets map[string]InstalledAsset, profiles []Artifact) (context.Context, context.CancelFunc, error) {
	return engineStartupContextWithPolicy(parent, base, cfg, assets, profiles, productionModelValidationPolicy())
}

func engineStartupContextWithPolicy(parent context.Context, base time.Duration, cfg Config, assets map[string]InstalledAsset, profiles []Artifact, policy modelValidationPolicy) (context.Context, context.CancelFunc, error) {
	if base <= 0 || base > 180*time.Second {
		return nil, nil, errors.New("엔진 기동 시간의 고정 기준을 확인하세요")
	}
	budget := engineStartupBudget{total: base, models: map[string]startupModelAllowance{}}
	for _, selected := range []struct{ id, task string }{{cfg.TranslationModel, "translation"}, {cfg.STTModel, "stt"}} {
		var profile Artifact
		found := false
		for _, candidate := range profiles {
			if candidate.ID == selected.id {
				if found {
					return nil, nil, errors.New("등록된 모델 프로필이 중복되었습니다")
				}
				profile, found = candidate, true
			}
		}
		asset, installed := assets[selected.id]
		if !found || !installed || profile.Task != selected.task || portableProfile(profile) != nil || asset.ID != profile.ID || asset.SHA256 != profile.SHA256 || asset.Bytes != profile.Bytes {
			return nil, nil, errors.New("등록된 고정 모델 정보와 설치된 파일 정보를 확인하세요")
		}
		allowance, err := modelValidationAllowance(profile.Bytes, policy)
		if err != nil || allowance > engineStartupHardLimit-budget.total {
			return nil, nil, errors.New("모델 검증 시간이 지원 범위를 초과했습니다")
		}
		budget.models[profile.ID] = startupModelAllowance{profile.SHA256, profile.Bytes, allowance}
		budget.total += allowance
	}
	ctx, cancel := context.WithTimeout(parent, budget.total)
	return context.WithValue(ctx, engineStartupBudgetKey{}, budget), cancel, nil
}

func verifyStartupModel(ctx context.Context, asset InstalledAsset, magic []byte) error {
	return runStartupModelValidation(ctx, asset, func(validationCtx context.Context) error {
		return verifyModel(validationCtx, asset.Path, asset.SHA256, asset.Bytes, magic)
	})
}

func runStartupModelValidation(ctx context.Context, asset InstalledAsset, verify func(context.Context) error) error {
	allowance, err := modelValidationAllowance(asset.Bytes, productionModelValidationPolicy())
	if err != nil {
		return err
	}
	if budget, ok := ctx.Value(engineStartupBudgetKey{}).(engineStartupBudget); ok {
		model, found := budget.models[asset.ID]
		if !found || model.sha != asset.SHA256 || model.bytes != asset.Bytes {
			return errors.New("기동 전에 확인한 모델 구성이 변경되었습니다")
		}
		allowance = model.timeout
	}
	validationCtx, cancel := context.WithTimeout(ctx, allowance)
	defer cancel()
	if err := validationCtx.Err(); err != nil {
		return err
	}
	// Keep verification synchronous. An abandoned goroutine must never finish
	// reading a changed file after the caller has accepted its model as ready.
	err = verify(validationCtx)
	if contextErr := validationCtx.Err(); contextErr != nil {
		return contextErr
	}
	return err
}
