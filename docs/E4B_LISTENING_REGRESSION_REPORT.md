# MCastTalk 0.2.46-beta E4B "듣는 중" 고착 및 번역 미전달 회귀 분석 및 조치 보고서

**기준일시**: 2026-09-30

**대상 버전**: 0.2.46-beta (code 53) → 0.2.46-beta-a (code 54)

**작업 브랜치**: `codex/e4b-listening-hotfix`

---

## 1. 개요 및 보고된 증상

0.2.46-beta에서 번역 모델을 기존 E2B(`gemma-4-E2B-it.litertlm`)에서 E4B(`gemma-4-E4B-it.litertlm`)로 변경한 뒤, 사용자가 음성을 입력해도 완전한 문장이 확정되지 않고 실시간 원문만 "듣는 중" 상태로 갱신되며 번역 채널로 전달되지 않는 회귀가 보고되었습니다 (0.2.45-beta-c에서는 정상 동작).

* **사용자 관찰 현상**: "원문은 화면에 보이고 갱신되나, 번역만 나오지 않고 계속 '듣는 중'에 머무름."
* **통제된 재현에서 확인한 단계**: STT partial hypothesis → semantic final (`isFinal == true`) 미생성 → `TranslationBroadcastPipeline`의 `sourceJob` 필터(`if (!utterance.isFinal) return@collect`)에 의해 번역 큐로 미전달. 사용자 기기에서 멈춘 정확한 단계는 원문 표시 증상만으로 확정할 수 없습니다.

---

## 2. 원인 분석 (확정과 추정의 엄격한 분리)

### [확정/입증된 결함] `RealtimeInterpretationSegmenter.verifiedQuietMillis`의 관측 스케줄링 지연 오판정
* `RealtimeInterpretationSegmenter`는 오디오 프레임의 캡처 시각(`capturedAtNanos`)을 기준으로 연속 무음의 시작 시점(`continuousQuietStartedAtNanos`)과 최근 관측 시점(`lastAudioObservationAtNanos`)을 기록합니다.
* 기존 `verifiedQuietMillis(nowNanos)`는 다음 조건을 포함하고 있었습니다:
  ```kotlin
  if (elapsedMillis(latestObservation, nowNanos) > policy.maximumQuietObservationAgeMillis) {
      return 0L
  }
  return elapsedMillis(quietStart, nowNanos)
  ```
* **시계 체계**: `latestObservation`($T_{\text{cap}}$)과 세그먼터 평가 시각 `nowNanos`($T_{\text{segmenter}}$)는 **모두 동일한 단조 증가 시계(`elapsedRealtimeNanos`) 도메인**에 속합니다. 월클락(UTC)이나 시계 도메인 불일치 문제가 아닙니다.
* **관측 지연 발생 시 결함 동작**: 시스템 CPU 부하, 스레드 디스패처 기아, 오디오 버퍼 지연 등으로 인해 오디오 프레임 관측 시점이 세그먼터 틱 시점보다 500ms(`maximumQuietObservationAgeMillis`) 이상 뒤처질 경우($T_{\text{segmenter}} - T_{\text{cap}} > 500\text{ms}$):
  * 오디오 스트림 내부에서 사용자가 말을 멈추고 800ms(`sentencePauseMillis`) 이상의 명백한 무음 프레임이 연속 수신되었음에도 불구하고,
  * `verifiedQuietMillis`가 **무조건 `0L`로 강제 리셋**되었습니다.
* 그 결과 해당 재현 조건(관측 시각 지연 500ms 초과) 하에서 한국어 문장 완결 후 침묵 기준(`completeSentencePause`)이 충족되지 않아 `isFinal == true`가 방출되지 못하고, 화면에는 partial만 반영되어 "문장 이어 듣는 중"으로 고착되었습니다.

### [미입증/추정 사항] E4B CPU 경합과의 인과관계
* E4B 모델(약 3.41 GiB, 3,659,530,240 bytes)의 resident 로딩 및 XNNPACK CPU 백엔드 추론 부하가 단독으로 사용자 기기에서 오디오 캡처 지연을 500ms 이상 발생시켰는지는 독립적으로 입증되지 않았습니다.
* 수정 전 JVM의 700ms 관측 지연 조건에서 문장 미확정을 재현했습니다. 수정 후 JVM 회귀와 에뮬레이터의 800ms 지연 주입 시험이 통과했습니다. 모든 부하 조건이나 수정 전 에뮬레이터의 동일 지연 시험을 검증했다는 의미는 아닙니다.

---

## 3. 수정 내용 (`RealtimeInterpretationSegmenter.kt`)

의미 파괴를 유발하는 고정 시간 강제 분할이나 임의의 프롬프트 수정을 철저히 배제하고, 음향 쉼과 한국어 의미 경계를 엄격히 유지하는 최소 안전 패치를 적용했습니다:

```kotlin
val audioQuietMillis = elapsedMillis(quietStart, latestObservation)
val observationAgeMillis = elapsedMillis(latestObservation, nowNanos)
return if (observationAgeMillis <= policy.maximumQuietObservationAgeMillis) {
    audioQuietMillis + observationAgeMillis
} else {
    // Scheduling lag must not erase quiet already measured in contiguous PCM.
    // Freeze at the last observed frame: missing input is not additional silence.
    audioQuietMillis
}
```

### 세부 보호 원칙:
1. **지연 PCM 무음 보존**: 연속 PCM 프레임 내에서 이미 실측 검증된 무음(`audioQuietMillis`)은 스케줄링 지연이 발생하더라도 보존되어 완전한 문장이 안전하게 확정됩니다.
2. **PCM 멈춤/중단 보호**: 마지막 관측 프레임 이후 오디오 공급이 중단된 경우 가상의 무음 시간을 누적하지 않고 직전 프레임 수준에서 동결(freeze)하여, 짧은 무음 후 오디오가 멈췄을 때 미완성 문장이 조기 확정되는 현상을 방지합니다.
3. **미완성 문장 및 재발화 보호**: 연결어미(-고 등) 미완성 문장은 8초 무음+900ms 지연 환경에서도 커밋되지 않고 대기하며, 무음 중 발화가 재개되면 무음이 즉시 0으로 리셋되어 한 문장으로 결합됩니다.

---

## 4. 검증 결과 및 증거 내역

### 1) JVM 회귀 재현 및 통과 증거
* **결함 재현 (FAIL)**: `build/evidence/e4b-listening-hotfix/baseline-lagged-quiet-FAIL.xml`
  * 캡처 시각이 세그먼터 평가 시점보다 700ms 지연된 조건에서 문장 미확정 결함 확인 (`AssertionError`).
* **패치 후 회귀 (PASS)**: `build/evidence/e4b-listening-hotfix/segmenter-safety-regression-PASS.xml`
  * 신규 안전 회귀 6건 포함 전체 43개 단위 테스트 통과 (0 failures).
  * 통과 항목: 지연 오디오 무음 확정, 짧은 무음 후 PCM 멈춤 시 조기 확정 방지, 500ms 비연속 갭 리셋, 미완성 문장 보존, 무음 중 재발화 리셋, 장시간 무음 시 정확히 1회 커밋.

### 2) 온디바이스 회귀 테스트 (API 35 ARM64 에뮬레이터, code 54 서명본)
공개 FLEURS 한국어 발화 녹음(`fixtures/fleurs-ko-1959.pcm`, 224,640 bytes)을 사용했습니다. 아래 첫 다섯 행은 초기 후보의 개별 시험이며, 마지막 행은 표시 오류까지 수정한 최종 APK의 운영 시험입니다. 최종 APK의 반복 음성 4건 재시험은 다음 절에 따로 기록했습니다:

| 테스트 케이스 | 대상 모델 / 조건 | 소요 시간 | 번역 파이프라인 회귀 결과 | 증거 파일 |
|---|---|---|---|---|
| `RepeatedSemanticSpeechDeviceTest#repeatedPublicKoreanSpeechKeepsMeaningWordsInEachLiveSentence` | Code 54 기존 음성 회귀 (번역 제외) | 29.277s | PASS (3회 완료) | `device-repeated-speech-baseline.txt` |
| `RepeatedSemanticSpeechDeviceTest#e2bStandardSpeechToTranslationPipelineSucceeds` | E2B Standard (0ms 지연) | 32.054s | PASS (3회 완료) | `device-e2b-speech-translation.txt` |
| `RepeatedSemanticSpeechDeviceTest#e4bModelOptionSpeechToTranslationPipelineSucceeds` | E4B-IT (0ms 지연) | 39.440s | PASS (3회 완료) | `device-e4b-speech-translation.txt` |
| `RepeatedSemanticSpeechDeviceTest#e4bModelOptionWithDelayedPcmCaptureTimestampsSucceeds` | E4B-IT (800ms 지연 PCM) | 35.487s | PASS (3회 완료) | `device-e4b-delayed-pcm-translation.txt` |
| `MoonshineTtsDeviceIntegrationTest#sequentialEnglishGuideUtterancesSynthesizePlayablePcmWithMonotonicFrames` | Moonshine TTS en 준비 및 5문장 합성 | 38.597s | PASS (5문장 완료) | `device-moonshine-en-tts-preparation.txt` |
| `BroadcastEmulatorIntegrationTest#capturedKoreanSpeechProducesGemmaE4BEnglishAlongsideOriginalAudio` | 표시 오류 수정 후 최종 E4B-IT 운영 UI 방송 경로 | 165.083s | PASS (시작/중지·실제 번역·TTS·비무음 WebSocket PCM·독립 입력 보존) | `device-e4b-operator-provider-label-fixed.txt` |

* **특이사항 기록 (AOSP 전용 에뮬레이터 음성 선행조건 및 1차 run 결과)**:
  * 첫 번째 operator UI 실행 시, Moonshine en 음성이 캐시되지 않은 상태에서 AOSP 에뮬레이터의 Android TTS 부재(`Android TTS initialization failed: -1`)로 인해 TTS 준비 예외(`SpeechSynthesisPreparationException`)가 발생했습니다.
  * 테스트 대기 조건(`first { ... }`)이 `translationWarning`에 들어간 미준비 오류를 조기 실패로 처리하지 않고 장시간 대기 상태에 머물러, 해당 1차 run은 준비 실패 원본으로 보존(`operator-tts-unprepared-failure.log`) 후 강제 종료되었습니다.
  * 이후 `MoonshineTtsDeviceIntegrationTest`를 단독 실행하여 공식 Moonshine 영어 음성(`kokoro_af_heart`)을 다운로드·무결성 검증 완료(38.597s PASS)한 뒤, 음성이 완비된 상태에서 operator UI 재시험을 수행했습니다.

### 3) 공개 FLEURS 발화 번역 실측 및 품질 분석

최종 APK에서 `RepeatedSemanticSpeechDeviceTest` 전체 **4건 PASS, 127.972초**를
다시 확인했습니다(`final-repeated-speech.txt`). 최종 9개 번역 출력은
`final-fixture-translations.txt`에 보존했습니다. 동일 녹음의 E4B 6회에서 아래
`order all signs` 오역이 반복되어, 기능 회귀 PASS와 의미 품질을 구분합니다.
최종 번역 시간은 E2B 1909/1233/1207ms, 일반 E4B 2226/2136/2093ms,
800ms 지연 E4B 2196/2211/2174ms입니다. 마지막 반복 시험 후 crash buffer는
0 bytes였으며 과거 실패·진단 도구 충돌을 지운 결과로 해석하지 않습니다.

아래는 초기 후보의 `fixture-translations-review.txt`에 기록된 실제 번역 로그 대조 결과입니다:

* **참조 원문 발화**: "... 모든 표지판을 **지키고** 안전 경고에 세심한 주의를 기울여야 합니다."
* **실제 STT 인식 결과**: "그래도 관계자의 조언을 듣고 모든 표지판을 **시키고** 안전 경고에 세심한 주의를 기울여야 합니다." (STT에서 '지키고' → '시키고' 오인식 발생)

#### 모델별 번역 결과 및 실측 레이턴시 (ARM64 에뮬레이터 환경)

| 모델 / 회차 | 실측 추론 시간 | 모델 출력 번역문 | 품질 및 동작 분석 |
|---|---|---|---|
| **E2B Standard** (Seq 0) | 1,469 ms | However, you must listen to the advisor's advice, **follow all signs**, and pay close attention to safety warnings. | STT 오인식('시키고')에도 불구하고 문맥을 복원하여 'follow all signs'로 올바르게 번역 |
| **E2B Standard** (Seq 1) | 1,171 ms | (동일) | 정상 상태 추론 안정 |
| **E2B Standard** (Seq 2) | 1,157 ms | (동일) | 정상 상태 추론 안정 |
| **E4B-IT** (Seq 0) | 4,844 ms | Nevertheless, you must listen to the officials' advice, **order all signs**, and pay close attention to safety warnings. | 첫 회차가 더 느림(원인 별도 미계측). 'order all signs' 오역 발생 |
| **E4B-IT** (Seq 1) | 2,174 ms | (동일) | 정상 상태 추론 |
| **E4B-IT** (Seq 2) | 2,249 ms | (동일) | 정상 상태 추론 |
| **E4B-IT (800ms 지연)** (Seq 0) | 2,345 ms | (동일) | 800ms 지연 환경에서도 세그먼터 문장 확정 및 번역 정상 방출 |
| **E4B-IT (800ms 지연)** (Seq 1) | 2,101 ms | (동일) | 정상 상태 추론 |

#### 핵심 품질 판정 및 유의점
1. **통제된 멈춤 회귀 (PASS)**: 시험한 E4B 일반/800ms 지연 조건에서 각각 세 번의 발화가 확정되고 번역 결과가 반환됨. 모든 발화·기기·부하 조건의 해결을 입증한 것은 아니며, 이 시험은 운영 UI 대신 시험용 큐 소비자를 통해 실제 번역 엔진을 호출함.
2. **의미 번역 품질 판정**: E4B 모델이 E2B 대비 일반적인 번역 품질 향상을 제공한다고 주장할 수 없음. 본 공개 fixture에서는 오히려 STT 오인식 복원력에서 E2B가 우수했고 E4B는 직역 오역('order all signs')을 산출함.
3. **레이턴시 수치 유의**: 상기 ms 수치는 호스트 에뮬레이터(ARM64 v8a) 상의 실측치이며, 물리 Galaxy S23의 2,000 ms p95 릴리스 게이트와 혼동해서는 안 됨.
4. **핫픽스 범위 준수**: 제품 수정은 오디오 세그먼터 결함과 운영 화면의 공급자 표시 오류 두 부분임. 문자열 치환이나 번역 프롬프트 수정은 수행하지 않았으며, 기본 모델은 기존 E2B Standard 설정을 유지함.

### 4) 프로세스 무결성 및 외부 진단 충돌 구분
* 테스트 실행 중(23:00:14) 외부 adb 명령을 통한 `uiautomator dump` 실행 시 PID 3002가 `UiAutomationService already registered`로 충돌 종료된 로그가 확인되었습니다.
* 이는 테스트 러너(PID 2894)가 이미 독점적으로 취득한 시스템 UI 자동화 인터페이스에 외부 진단 도구가 이중 접근하여 발생한 외부 도구 충돌이며, 가이드캐스트 앱 자체나 인퍼런스 워커의 크래시가 아님을 확인했습니다.
* 해당 외부 도구 충돌과 제품/워커 종료를 구분합니다. 전체 시험 기간의 제품 프로세스 무결성은 최종 로그 검수 결과로 판단하며 장기 안정성을 추정하지 않습니다.

---

## 5. 저장소 품질 게이트 및 서명 산출물

### 운영 화면 시험에서 추가로 재현한 공급자 표시 오류

정상 6GB 메모리 안내를 시험 준비 조건에 반영한 뒤 실제 방송 시험은
104.234초에 공급자 표시 검증으로 실패했습니다(`operator-provider-label-FAIL.txt`).
실제 E4B 작업자는 52자 입력을 받아 2,999ms에 번역을 반환했지만,
TTS 준비 종료 콜백이 `updateProviderFallbackUi`의 기본값 `ML Kit`으로
채널 요약을 덮어썼습니다. 이는 번역 실행 실패와 구분되는 화면 표시 결함입니다.

기본 표시를 전달된 `gemmaPriorityActive` 상태에서 계산하도록 최소 수정했습니다.
명시적인 실패 전환/복구 표시는 그대로 존중합니다. 이 수정은 모델·인식·번역 경로를
바꾸지 않습니다. 수정 후 배포 빌드와 동일 운영 경로 재시험 결과로 최종 판정합니다.

최종 서명본에서 동일 운영 시험 **1건 PASS, 165.083초**를 확인했습니다.
실제 E4B 번역 2,994ms, 문장 확정 후 첫 음성 3,714ms,
합성 완료 관측 8,045ms였습니다. 에뮬레이터 단일 표본이며 물리 S23의
2,000ms p95 목표를 통과했다는 뜻이 아닙니다.

최종 제품 APK: 96,440,384 bytes,
SHA-256 `ae7ee7c7e8932f326af13a897530bfedce188823a690f156e6488ad21fb6bfc1`.
최종 시험 APK SHA-256:
`d7557b79a845230b21f13edc07d569bb938a3b56395ae75dce2c546f4c72c96a`.

아래 해시는 이 추가 표시 수정을 적용하기 전 첫 후보의 검증 기록입니다.
최종 배포 해시는 `TEST_REPORT.md`의 최종 산출물 항목을 기준으로 합니다.

* **빌드 로그**: `build/evidence/e4b-listening-hotfix/final-quality-build.log` (610 tasks successful)
  * Debug/Core XML 합계 1,406 tests, failures/errors/skips 0. 610개 작업 중 48개 실행, 562개 up-to-date.
  * Lint Alpha 검증 완료 (0 errors)
  * 라이선스 및 자산 무결성 통과
* **서명 및 정렬 (기존 MCastTalk 공개 릴리스 인증서)**:
  * Scheme v3 서명 완료, 16 KiB `zipalign` 검증 PASS
  * App APK SHA-256: `f01b433c1717921be5621dbe6597fc1f7fb288630eaea74ed71bfaea1d11b49a`
  * Test APK SHA-256: `1f6ea7a0c864ca8952c51a6bf675a40c5aaa900da184d6a4eb7053ee151a1b83`
  * 단말 설치 후 `sha256sum /data/app/.../base.apk` 대조 일치 확인 완료.

---

## 6. 미입증 항목 명시 (DEVELOPMENT_GUIDELINES.md 준수)

본 수정 및 검증은 JVM 단위 테스트, 로컬 파이프라인 시뮬레이션, ARM64 API 35 가상 단말(emulator-5554) 빌드 환경에서 수행되었습니다. 다음 환경은 검증되지 않았으며 물리 기기 테스트에서 별도 확인되어야 합니다:
* **물리 Galaxy S23 및 Samsung One UI 실단말 동작**: 물리 마이크 HAL의 실제 음향 지연 및 AGC/NS 하드웨어 동작은 실기기에서 검증되지 않음.
* **실제 야외 소음 환경 및 음성 자연스러움**: 공개 FLEURS 인체 발화 녹음 외의 실제 현장 발화 품질 및 음향 잡음 내성은 사람이 청취 검증해야 함.
* **2시간 장기 방송 안정성**: 장시간 발열/메모리 부하 상태에서의 안정성은 측정되지 않음.
