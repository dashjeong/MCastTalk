# 0.2.45 Beta validation

Validated source: conversational sentence finalization, committed-prefix revision alignment,
unified broadcast entry and content-first voice notes. Detailed before/after evidence and
failure accounting: [Beta validation report](BETA_0_2_45_VALIDATION.md).

## Artifact and build

- Package `app.guidecast.transmitter.alpha`, code `51`, installed version `0.2.45-beta`.
- Optimized ARM64 APK: **96,424,000 bytes**, SHA-256
  `64e994a7d0e1c128ae4c131f05f609cbad208f6757119636b97edc289eaa3239`.
- Matching test APK: **4,829,417 bytes**, SHA-256
  `8f3c2b02f30669fa25d57659bb8452d04c34e203184cc9c665a828f6a557b541`.
- Existing certificate SHA-256
  `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`.
  `apksigner verify`, 16 KiB `zipalign` and installed-version metadata checks pass.
- Final build succeeded in **6m 39s**, 615 tasks: 72 executed, 543 up-to-date.
  Command: `./gradlew testDebugUnitTest :core:stream:test :core:translation:test
  :app:testAlphaUnitTest :app:lintAlpha :app:assembleAlpha :app:assembleAlphaAndroidTest
  :app:verifyPackagedThirdPartyLicenseAssets --offline --max-workers=1` with JDK 17.
- Current-result XML: app Alpha **534**, translation core **255**, stream core **28**;
  zero failures/errors/skips. These are source-matching result counts; reused tasks are
  not claimed as freshly rerun or added to Debug counts as unique cases.
- The newly added explanatory/contracted-past regression failed before the ending fix.
  All 255 translation-core cases then passed, including conditional, noun and quotation
  counterexamples. No translation-quality assertion was weakened.
- GitHub verification for source commit `904ebb2` completed successfully:
  [run 35946865041](https://github.com/dashjeong/MCastTalk/actions/runs/35946865041).
  Source-boundary, web regression, unit tests, lint and assembly passed. This CI result
  does not substitute for the microphone journey or translation-quality gates below.

## Device verification status — APK handoff currently blocked

All current device runs use an Android 15/API 35 ARM64 AOSP emulator. They are not physical
Samsung/One UI measurements. The exact final artifact above was installed and hash-checked.

| Executed group | Result | JUnit elapsed |
| --- | --- | ---: |
| Final APK: four home/navigation journeys and five real-model broadcast/test/file/dictionary/repeated-speech journeys | 9/9 PASS | 307.070 s |
| Final APK: public 136-second monologue, original amplitude | Execution PASS; 34/34 translations completed, pending 0 | 188.212 s |
| Final APK: same monologue, amplitude ×0.125 | Execution PASS; 26/26 translations completed, pending 0 | 181.797 s |
| Antigravity UI cross-check: home, two note viewport configurations, actual editing/persistence and playback | 7/7 PASS on the UI candidate below | 586.641 s |
| Final APK: virtual-microphone live transcription/save/reopen, first attempt | FAIL; expected live text did not appear | 95.751 s |
| Final APK: same microphone journey after emulator restart | FAIL; same live-text assertion | 96.172 s |
| Final APK: client-paced microphone injection experiment | FAIL; live-text assertion retained | See retained receipt |
| Final APK: full Qt emulator instead of headless | FAIL; live text did not appear, host and JUnit failed | See retained receipt |

The UI candidate SHA-256 is
`1dfbae5753fa2c68d2a1ba2dfadd74056755156b3fe1fdcd86dd436fd4b1cac4`.
Its UI source and instrumentation match the final artifact; only the final explanatory/past-ending
core fix followed it. Its seven-test result is not relabeled as seven executions on the final APK.
The earlier UI candidate failed the short-viewport path; separating note tools fixed the same
test without weakening the edit/export assertions.

Microphone investigation: the two failed runs submitted the complete public 7.02-second FLEURS
fixture, but recorded nearly silent WAVs (peaks 8; 894/2,299 nonzero samples compared with the
fixture's 112,048). An independent foreground AudioRecord collector, without note UI or ASR,
also captured only 4,770 nonzero samples. Its read-gap maximum was 73.74 ms with an 8,000-frame
(500 ms) buffer and no observed Android silencing. Its collection test executed successfully,
but **input integrity is not proven and that result is not a microphone-quality PASS**.
Root cause remains under investigation; product capture code has not been changed speculatively.
Antigravity executed the full-Qt comparison with the same APK and original injection helper.
The resulting 90,320 ms WAV contained only 510 nonzero samples, peak 8 and RMS
0.0000033535768924956625. Changing the emulator window mode did not resolve this observation;
it does not establish a HAL defect or prove the product capture path correct.
The unchanged strict waveform verifier also failed: correlation 0.0294334, all 140 active
windows failed. Receipt SHA-256 and numeric waveform results are retained locally with this run.
APK publication is withheld until the live-transcription journey is resolved or explicitly
reported as an unresolved release blocker. Failed receipts are retained.

Separate existing ML Kit semantic-quality failure remains FAIL; see the Beta report. None of
the passing execution groups erases it. Physical Galaxy first-audio p95, human voice quality,
the full multilingual 30-clip corpus and eight-hour endurance remain unproven.

## Historical 0.2.44 Alpha validation and 0.2.43 maintenance

> **최신 공개 후보 0.2.44 Alpha / code50:** 사용자 요청에 따라 현재 수정본을 패키징했다.
> APK SHA-256 `4c344a8375a7ebb0f5724eac79b18b3b8c3a808c21a3e97ade8de2092c0efa0c`.
> 정확히 이 설치본의 음성 송출·시험 재실행·파일 변환/재생·사전 우선권 4건 통과(164.518초).
> 동일 APK에서 문맥/큐 검토 26건, ML Kit 21건, Gemma 15건 실행. 문맥 의미 검사는 **FAIL 유지**:
> 자동 항목 23/26, 동일 출력의 AI 대조는 의미 보존 24/명확 오류 1/인용 중의성 1이다.
> 전체 빌드·단위 회귀·lint·라이선스 검사 통과. 1,887개 기록의 JUnit 스냅샷은 재사용 결과를 포함한다.
> 서명·16KiB 정렬·업데이트 설치·전후 해시와 Antigravity의 독립 APK 무결성 확인을 완료했다.
> 알려진 오역을 해결했다고 표현하지 않으며 0.2.43은 철회 상태를 유지한다. [릴리스 범위](RELEASE_0_2_44.md).
>
> **Round20 — 실제 오역 수정 및 당시 품질 보류:** [오역 검토 및 에뮬레이터 검증](MISTRANSLATION_REVIEW_2026_09_24.md).
> 사용자 지시에 따라 다국어·실기기 검증 환경은 에뮬레이터로 대체했다. 제품 APK SHA-256은
> `59b789fd7b381a61cd2b3ac7fe023c517c9b4b23f0cc1339da82e599be89c3ea`다.
> 실제 선택적 검토·큐·공개 사전 경로 26/26 실행·채택, 시간 초과 0. 의미 항목 검사는 23/26으로 FAIL이며
> `4년째/만 3년`의 관계 오류가 남는다. 숫자 표기 거부·사람/시간 혼동·공개 사전 의미 충돌을 보완했다.
> 같은 APK에서 ML Kit 7원어 21건, Gemma 지원 5원어 15건을 실제 실행했다. 번역 전용 검사이며
> 다국어 음성인식·TTS·8시간 시험을 대신했다고 주장하지 않는다. 프랑스어·독일어 Gemma는 미실행이다.
> 아래 과거 `56년째` 판정과 7/6/8 항목 수는 당시 기준 기록으로 보존하며 최신 판정과 구분한다.

> **이전 Round15–17 기록 — 당시 품질 조건 미충족:** [문맥 계약·가상 마이크·실제 로컬 모델 시험](FIX_VALIDATION_2026_09_24.md).
> 최종 보수 APK는 round15와 동일한 SHA-256 `a1a2d4dd30101e73c58be93e92906aee28be816f0c16ed2931cc92e4c80a9ccc`다. 실제 방송·시험 버튼·파일 여정 3건과 강화 톡 노트 1건은 통과했다.
> 문맥 검토는 선별 10건 중 7건만 의미 검사를 통과했다. 추가 프롬프트 실험은 6건으로 회귀해 제거했고, 원문 직접 번역 대조는 8건이나 여전히 실패다. 실제 복구·재진입 1건 통과는 번역 품질 통과가 아니다.
> 최종 단위 결과 1,828/0/0/0은 캐시·기존 결과를 재사용한 스냅샷이다. 전면 상태를 보장한 독립 수집도 엄격한 파형 타이밍 검사는 실패했다. Antigravity 교차검토는 호스트 20건과 정적 검토 범위이며, 실기기·8시간·나머지 언어 코퍼스 입증은 아니다.
> 아래 round8–12 결과와 당시 보류 판단은 역사적 기록으로 보존한다. 최신 결과를 대체하지 않는다.

> **현재 보수 진행 기록:** [원인·수정·중간 시험·남은 검증](MAINTENANCE_REPAIR_0_2_43.md).
> **상세 품질 보고서:** [최신 실제 검증·네이티브 수정·남은 품질 조건](QUALITY_VALIDATION_2026_09_24.md).
> **2026-09-24 최신 round10:** 수정 네이티브 APK의 네이티브·숫자·EOF 6개(37.76초), 방송·시험 버튼·파일 여정 3개(202.194초) 실제 통과.
> 같은 강제 배출 메모리 검사의 heap 증가는 기준판 +19,776,000 bytes→수정판 −112 bytes. 합성 가속 시험이며 8시간 안정성 입증이 아니다.
> 빌드 1분 52초 성공. 보존 XML 1,787건 실패·오류·건너뜀 0 중 마지막 명령의 실제 재실행은 app Debug/Alpha 합계 1,026건, 나머지 761건은 UP-TO-DATE 이전 결과다.
> 본체 SHA-256 `fba37b600fc0519e07a7289bf0a0b9a63c05b5e7d9f51f05d52502eb09885610`, 96,309,312 bytes. 서명·16KB 정렬·실제 APK의 네이티브 3개 해시 검사 통과.
> 지정 영상 최종 재시험 2건은 완료: 번역 요청 34/34, 선별 결함은 부분 개선 2·동일 3·완전 해결 0이다.
> Antigravity 정적·해시 교차검토의 지적을 반영한 round12 강화 시험 5개가 통과했다. 이전 round11의 설정 누락 실패를 삭제하거나 합격으로 바꾸지 않는다. 상세 조건은 최신 품질 보고서에 있다.
> 마이크 전체 여정은 새 APK에서 재시험하지 않았으며 이전 실패·미입증 상태다.
> **이전 round8 기록:** 재빌드·단위시험 767회, 실제 EOF 2개·설치 가용성 기록 1개·방송 2개·DB 5개 통과.
> 한국어 긴 코퍼스 30건(합계 63분 15.6초)은 입력 해시 모두 일치, 23건 실행 완료·7건 무출력 실패.
> 원문 단위 305개·번역 305개 생성은 품질 통과가 아니다. 영상 발췌 2건의 꼬리 실패는 1→0이나 선택된 의미 오류 5개는 남았다.
> 직접 native 대조 2건도 무출력 실패해 watchdog 원인으로 확정하지 않는다. 나머지 6언어 각 30건은 미완료다.
> UI 최초 묶음 18 통과·5 실패, 해당 재시험 3 통과·2 실패, 최종 확인 2 통과·1 건너뜀(새 미지원 기기 검사 포함).
> 최종 가상 마이크 전체 여정은 실패다. JUnit 통과 시도도 호스트 주입기 종료 실패로 전체 통과가 아니었다.
> **공개 재배포 보류: 품질 게이트 미충족.** 사용자 공개 요청은 유지하며 보수 소스는 별도 브랜치에 보존한다. 공개본은 **0.2.42 유지**.
> 본체/시험 APK 해시와 실행별 한계는 위 보수 기록에 있다. **아래 본문은 철회 당시의 역사적 기록이며 현재판 통과 주장이 아니다.**

> **WITHDRAWN 2026-09-23 — NOT RELEASE APPROVAL.** Users reported failed audio broadcast
> and test flows. Voice notes record WAV but do not transcribe while recording, missing
> the required note-taking experience. The results below are historical component checks;
> they did not establish complete user journeys. See [maintenance review](MAINTENANCE_0_2_43.md).
> The public release and tag have been withdrawn; public product source is restored to 0.2.42.

Validated on 2026-09-22. Package `app.guidecast.transmitter.alpha`, versionCode `49`.
This was a public Alpha release and is now withdrawn. Physical-device latency, prolonged operation and human
translation/voice quality remain unverified; automated results are not substitutes.

## Product changes

- 녹톡 / 라이브톡 / 통역톡 / 스크립톡 home; local voice-note recording, editing,
  speaker labels, segment playback and document exports.
- Portable script backup now includes note audio and metadata. Import validates hashes,
  WAV structure, references, size and paths before merging; existing edits are preserved.
  Settings > script backup and the note screen provide export/import entry points.
- Service-scoped automatic preparation, cancelled-preview cleanup, stale-session rejection,
  file-translation checkpoints and browser resampling regression coverage.
- Optional comparative teacher review with independent providers, explicit transmission
  consent and approval, context-specific reuse, conflict checks and rollback.
  This updates approved correction memory, not model weights.

## Build and automated gates

Environment: macOS ARM64, JDK 17, Android SDK 36, build-tools 35.0.0, Gradle 8.13.
All commands below completed successfully against the release source:

```sh
./gradlew testDebugUnitTest :core:stream:test :core:translation:test \
  :app:testAlphaUnitTest :app:lintAlpha :app:assembleAlpha :app:assembleRelease \
  :app:assembleAlphaAndroidTest :app:verifyPackagedThirdPartyLicenseAssets \
  --offline --max-workers=4
node scripts/verify-listener-player.mjs
node scripts/verify-listener-resampling.mjs
node scripts/verify-listener-i18n.mjs
node scripts/verify-speaker-mic.mjs
node scripts/verify-public-branding.mjs
python3 scripts/test-public-snapshot.py
python3 scripts/verify-public-snapshot.py
git diff --check
```

- JVM: **1,664 executions, 0 failures/errors/skips**, 249 XML suites. Debug and Alpha
  executions are counted separately; this is not 1,664 unique cases.
- Alpha lint: **0 errors, 56 warnings, 6 hints** on this macOS build.
- Browser-script simulations: player ordering, resampling, localization and speaker capture pass.
- Public source boundary: 622 tracked files pass credential/artifact/archive checks;
  the gate's 9 self-tests pass. These checks are not an exhaustive penetration test.
- Packaged glossary and all 40 third-party license assets pass verification. The glossary
  database is 29,405,184 bytes, SHA-256
  `5f39f27faf0c68b305d06e67c09eb929398e0242dd0278eb8430c49070d2ccb3`.
- APK size: 96,174,053 bytes; DEX total 14,992,212 bytes, within 110 MiB / 18 MiB budgets.

## Exact distributable and update

`MCastTalk-0.2.43-alpha.apk` is the optimized, signed ARM64 Alpha artifact
(minimum Android API 30, target API 36). It passes APK signature verification and
`zipalign -c -P 16 4`. No debug signing key is used.

- APK SHA-256: `342f5383f31bbf070727669d9b17a6fa9ed1f7e51992abcdab9f46b261fe5838`
- Signing certificate SHA-256: `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`

The exact distributable was installed with `adb install -r` over the public 0.2.42 APK
on a dedicated Android 15 / API 35 AOSP ARM64 emulator. `AppUpdateDeviceTest` ran a
seed phase before installation and a verify phase afterwards. Settings, a user-confirmed
sentence correction, a file transcript translation, and all 23 downloaded English/Japanese
model-cache files (241,061,938 bytes, compared by SHA-256) survived the update.
These are synthetic records and actual downloaded models; no operator content was used.

The 9 new `VoiceNoteTransferDeviceTest` cases pass on the signed APK: real repository
export/import, note/WAV/edits/speaker round trip, preservation of current corrections,
tampered/missing/unreferenced/traversal audio rejection, invalid metadata/reference rejection,
interrupted notes, cancellation/retry cleanup, orphan-audio conflict, legacy script archives,
and comparison preferences without restoring cloud consent.

## Signed-APK regression on API 35

All **87 tests in 28 bounded instrumentation groups pass, with no skipped cases**:
home navigation; voice-note repository, playback, large-font/short-viewport editing;
teacher learning, API validation and approval UI; recognition reconnection; HUD and app
search; settings/dictionary/script exports and imports; file decoding, playback, batch
selection, checkpoint/library handling; transcript and sentence-memory screens; operator,
privacy and display preferences; transcript archive retention; and five broadcast scenarios.
The latter exercise original PCM/WebSocket delivery, operator start authority, seven
translation channels plus original audio, rapid stop/restart and deferred-restart cancellation.
The UI run captured 13 synthetic screenshots; no real recordings or user content were used.

The update seed/verify phases and 9 new migration cases above are additional to these 87.
Instrumentation uses `app.guidecast.transmitter.GuideCastTestRunner` against the same
signed APK, with each named `*DeviceTest` group or selected broadcast method passed via
`adb shell am instrument -w -r -e class ...`.

Four additional real-model gates pass on the exact signed APK: killing the English TTS
worker leaves Japanese non-silent PCM running; five first-PCM cancellations allow the
next complete synthesis; English Moonshine PCM reaches the listener WebSocket; and a
cancelled translation preview cannot supersede the new broadcast audio session.
These use actual downloaded Moonshine models, not mock synthesis. In total, **100 Android
regression executions plus the two update phases pass**. Emulator PCM assertions do not
establish physical loudspeaker sound or human voice-naturalness approval.

## Scope not proven

- No physical Galaxy/Samsung One UI, Android/iPhone browser, outdoor noise, hotspot load,
  heat/power, or elapsed 2-hour/8-hour reliability claim. Long virtual-time tests do not
  establish elapsed device uptime. The user will report prolonged real-device behavior.
- Prepared-model first-audio p95 <= 2,000 ms on Galaxy S23 remains unmeasured.
- No real OpenAI/Gemini comparison calls or semantic quality assurance without API credentials.
  Provider protocol/security tests use controlled responses; human meaning/voice assessment remains.
- Model preparation/reuse is not proof of full offline STT→translation→TTS interoperability.
- API 29 cannot install this API-30-minimum artifact. Historical API-29 evidence is not counted.
- No app can be guaranteed resistant to every AI-assisted or other attack. Backup validation,
  cloud-consent boundaries, authentication and bounded-resource regressions are tested controls.

Only public product source, documentation, signed APK and checksum are released. Raw diagnostic
archives, recordings, detailed private test logs, credentials and signing keys are excluded.
Earlier 0.2.42 counts and private candidate runs are not included in these results.
