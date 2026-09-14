# 제3자 소프트웨어·모델 라이선스

이 문서는 MCastTalk `0.2.40-alpha` (`versionCode 46`)에 포함되거나 앱에서 별도로
다운로드하는 제3자 구성요소의 라이선스와 고지 위치를 안내합니다. MCastTalk가 작성한
자체 코드는 저장소 루트의 Apache License 2.0에 따라 상업적 이용을 포함한
사용·수정·재배포가 허용됩니다. 저작권·라이선스·필요한 고지 및 변경 표시 의무를 준수하세요.
이미 Apache License 2.0으로 적법하게 제공된 사본·버전의 기존 권리는 철회되지 않습니다.
제3자 소프트웨어·모델·음성·데이터에는 아래의 별도 조건이 적용되며, 자체 코드
라이선스로 다시 허가되지 않습니다.

현재 앱의 라이선스 화면에는 47개 항목이 등록되어 있습니다. APK에는 정적 고지 36개와
LiteRT-LM·ML Kit AAR에서 추출한 고지 4개, 총 40개 원문·고지 파일이 들어 있습니다.
앱의 `라이선스` 화면 또는 APK의 `assets/licenses/`에서 해당 문서를 오프라인으로 볼 수
있습니다. 공식 약관처럼 변경될 수 있는 문서는 복제본 대신 아래 공식 링크를 기준으로 합니다.
각 원문에 포함된 저작권자명·저작권문·필수 연락처는 요약 과정에서 삭제하거나 바꾸지 않습니다.

이 목록과 고지 제공은 상업 사용, 상표 사용, 모델·음성·데이터 재배포에 대한 별도 허가를
의미하지 않습니다.

## APK 런타임

| 권리자·구성 | 패키지·버전 | 라이선스·조건 | 고지 위치 |
|---|---|---|---|
| Android Open Source Project 및 AndroidX 기여자 | AndroidX Core `1.17.0`, Activity `1.11.0`, Lifecycle `2.9.4`, SavedState `1.3.1`, Collection `1.5.0`, Work `2.9.1`, Room `2.5.0`, SQLite `2.3.0`, AppCompat `1.6.1` 및 전이 AndroidX 모듈 | Apache-2.0 | `assets/licenses/APACHE-2.0.txt` 및 앱 카탈로그 |
| Android Open Source Project 및 Compose 기여자 | Compose UI·Foundation·Runtime·Animation `1.9.0`, Material 3 `1.3.2`, Material Icons Core `1.7.8` | Apache-2.0 | `assets/licenses/APACHE-2.0.txt` |
| JetBrains 및 Kotlin 기여자 | Kotlin stdlib·reflect `2.3.21`, Coroutines `1.11.0`, Serialization `1.11.0`, kotlinx-io `0.9.1` | Apache-2.0 | `assets/licenses/APACHE-2.0.txt` |
| JetBrains 및 Ktor 기여자 | Ktor server core/CIO/WebSockets/body-limit 및 전이 모듈 `3.5.2` | Apache-2.0 | `assets/licenses/APACHE-2.0.txt` |
| Lightbend·SLF4J 저작권자 | Typesafe Config `1.4.9`; SLF4J API `2.0.18` | Apache-2.0; MIT | `assets/licenses/APACHE-2.0.txt`, `SLF4J-2.0.18-LICENSE.txt` |
| ZXing 저작권자 | `com.google.zxing:core:3.5.3` | Apache-2.0 | `assets/licenses/APACHE-2.0.txt` |
| Google | LiteRT-LM Android `0.16.1` | Apache-2.0 및 포함 제3자 조건 | `assets/licenses/upstream/LICENSE`, `upstream/THIRD_PARTY_NOTICE.txt` |
| Google | ML Kit Translate `17.0.3`, ML Kit Common `18.11.0`, Play services Base `18.5.0`, Basement `18.4.0`, Tasks `18.2.0` | [ML Kit Terms](https://developers.google.com/ml-kit/terms), [Google APIs Terms](https://developers.google.com/terms), [Android SDK License Agreement](https://developer.android.com/studio/terms.html) 및 AAR 제3자 조건 | `assets/licenses/upstream/third_party_licenses.txt`, `third_party_licenses.json` |
| Useful Sensors, Inc. (Moonshine AI) | `ai.moonshine:moonshine-voice:0.1.5` | SDK 코드는 기본적으로 MIT. 모델·TTS·G2P 데이터 조건은 아래에서 별도 구분 | `assets/licenses/MOONSHINE-0.1.5-LICENSE.txt`, `MOONSHINE-NOTICE.txt`, `MODEL-AND-VOICE-NOTICES.txt` |
| Microsoft 및 ONNX Runtime 제3자 저작권자 | ONNX Runtime Android `1.23.2` ARM64 | MIT 및 포함 제3자 조건 | `assets/licenses/MOONSHINE-ONNXRUNTIME-MIT.txt`, `ONNXRUNTIME-1.23.2-THIRD-PARTY-NOTICES.txt` |
| Xiph.Org Foundation·Jean-Marc Valin | RNNoise `0.2`, model `0b50c45` | BSD-3-Clause | `assets/licenses/RNNOISE-BSD-3-CLAUSE.txt` |
| OpenCC 저작권자 | OpenCC 사전 데이터, revision `26753884f1984add422f3b0249ccee8613deaff6` | Apache-2.0 | `assets/licenses/OPENCC-APACHE-2.0.txt`, `OPENCC-README.txt` |

ML Kit AAR 고지에는 OkHttp `4.12.0`, Okio `3.6.0`, Gson `2.13.2`, Google Data
Transport, Firebase Components/Encoders 및 지원 annotation 라이브러리의 조건도 포함됩니다.

## Moonshine 0.1.5 네이티브 포함물

| 구성·원저작권자 | 고정 버전 | 라이선스 | 오프라인 고지 |
|---|---|---|---|
| Eigen 기여자 | `3.4.0`, Moonshine의 MPL-only subset | MPL-2.0 | `MOONSHINE-EIGEN-MPL-2.0.txt`, `MOONSHINE-EIGEN-README.txt` |
| kaldi-native-fbank 기여자 | `1.22.3` | Apache-2.0 | `MOONSHINE-KALDI-NATIVE-FBANK-APACHE-2.0.txt`, `MOONSHINE-KALDI-NATIVE-FBANK-README.txt` |
| Mark Borgerding 및 kissfft 기여자 | `v131.1.0`, commit `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` | BSD-3-Clause | `MOONSHINE-KISSFFT-NOTICE-AND-LICENSE.txt`, `MOONSHINE-KISSFFT-README.txt` |
| Niels Lohmann 및 기여자 | nlohmann/json `3.11.3` subset | MIT | `MOONSHINE-NLOHMANN-MIT.txt`, `MOONSHINE-NLOHMANN-README.txt` |
| utf8cpp 기여자 | Moonshine `0.1.5` vendored snapshot | BSL-1.0 | `MOONSHINE-UTF8CPP-BSL-1.0.txt`, `MOONSHINE-UTF8CPP-README.txt` |
| utf8proc 및 Unicode 저작권자 | utf8proc `2.9.0` | MIT/expat 및 Unicode 데이터 조건 | `MOONSHINE-UTF8PROC-MIT-UNICODE.txt`, `MOONSHINE-UTF8PROC-README.txt` |
| cpp-annote·pyannote.ai 저작권자 | Moonshine `0.1.5` snapshot; community-1 PLDA/config | 코드 MIT; 포함 데이터 CC-BY-4.0 | `MOONSHINE-CPP-ANNOTE-MIT.txt`, `MOONSHINE-CPP-ANNOTE-README.txt` |
| Silero VAD 저작권자 | commit `b163605b3f44c3aadf28f97b125a2f7c461e9a7f` | MIT | `SILERO-VAD-PINNED-MIT.txt` |

APK의 Eigen 실행 코드는 MPL-2.0 Covered Software를 포함합니다. 수령자가 정확한 대응
소스와 수정 사항을 MPL-2.0 조건으로 취득할 수 있어야 하며, 취득 방법을 함께 알려야
합니다. 기준 소스는 [Moonshine v0.1.5의 Eigen subset](https://github.com/moonshine-ai/moonshine/tree/v0.1.5/core/third-party/Eigen)입니다.
공개 APK 배포 전에 실제 바이너리와 이 소스 revision·수정분이 일치하는지, 그리고 지속적인
소스 제공 방법이 마련되었는지 별도로 확인해야 합니다.

## 별도 다운로드 모델·음성

아래 파일은 Git 저장소나 기본 APK에 포함되지 않고 앱 사용 중 별도로 다운로드됩니다.
다운로드 방식은 사용·제공·재배포 조건을 면제하지 않습니다.

| 모델·음성 | 정확한 식별자 | 적용 조건 | 확인 상태 |
|---|---|---|---|
| Gemma 4 E2B IT 기본형 | `gemma-4-E2B-it.litertlm`, revision `6e5c4f1e395deb959c494953478fa5cec4b8008f` | [Gemma 4 Apache License 2.0](https://ai.google.dev/gemma/apache_2) | 고정 [모델 파일](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm) 사용 |
| Gemma 4 E2B IT GPU형 | `gemma-4-E2B-it-gpu.litertlm`, revision `6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94` | [Gemma 4 Apache License 2.0](https://ai.google.dev/gemma/apache_2) | 고정 [모델 파일](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it-gpu.litertlm) 사용 |
| ML Kit 번역 모델 | ML Kit Translate `17.0.3` 관리 모델 | ML Kit Terms 및 Google APIs Terms | 현재 공식 약관 적용 |
| Moonshine 한국어 STT | `ko`, TINY, legacy non-streaming; Moonshine Voice `0.1.5` | **Moonshine AI Community License** | 상업 사용 자격과 배포 형태를 권리자 조건에 따라 별도 확인해야 함 |
| Kokoro 영어 | `kokoro_af_heart` | 모델·voice·ONNX Apache-2.0; CMUdict 고지 | 부속 데이터 attribution·재배포 조건 확인 필요 |
| Kokoro 일본어 | `kokoro_jf_alpha` | Kokoro Apache-2.0; G2P CC-BY-SA-4.0; EDICT/ipa-dict CC-BY-SA-3.0 | 동일조건 배포와 attribution 확인 필요 |
| Kokoro 중국어 | `kokoro_zf_xiaoxiao` | Kokoro·G2P Apache-2.0; 사전 원자료 Unicode License·CC-BY-3.0 | 데이터별 고지·재배포 조건 확인 필요 |
| Piper 네덜란드어 | `piper_nl_NL-mls-medium` | piper-voices MIT; MLS CC-BY-4.0; Dutch INT ipa-dict는 CC-BY 버전 미특정 | 정확한 CC-BY 버전과 음성 가중치 권리 미확인 |
| Piper 스페인어 | `piper_es_MX-ald-medium` | piper-voices MIT; ALD 데이터 Unlicense; 기반 davefx 데이터 CC0 | 음성 가중치와 파생 관계의 재배포 권리 확인 필요 |
| Piper 아랍어·G2P | `piper_ar_JO-kareem-medium`, `arabertv02_tashkeel_fadel`, `dict.tsv` | piper-voices MIT; 학습 데이터 저장소와 diacritizer checkpoint는 명시적 라이선스 없음; CAMeL Tools 코드 MIT와 사용 데이터 조건은 별도 | 명시적 권리 확인 전 상업 제공·재배포 금지 |

모델·음성의 상세 출처는 `assets/licenses/MODEL-AND-VOICE-NOTICES.txt`와 다음 공식 원문에서
확인할 수 있습니다: [Moonshine v0.1.5 LICENSE](https://github.com/moonshine-ai/moonshine/blob/v0.1.5/LICENSE),
[Moonshine TTS data](https://github.com/moonshine-ai/moonshine/tree/v0.1.5/core/moonshine-tts/data),
[Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M),
[Piper voices](https://huggingface.co/rhasspy/piper-voices).

### Moonshine 한국어 TINY 필수 고지

Moonshine 한국어 legacy non-streaming TINY에는 SDK의 MIT가 아니라 Moonshine AI Community
License가 적용됩니다. 상업 목적 사용·배포에는 권리자 등록이 필요하며, 라이선스에 정한
매출 기준을 넘는 경우 별도 enterprise license가 필요합니다. 모델 또는 이를 사용하는
제품·서비스를 제공할 때에는 계약 전문과 아래 Notice를 보존하고 `Powered by Moonshine AI`를
눈에 띄게 표시해야 합니다.

> This Moonshine AI Model is licensed under the Moonshine AI Community License, Copyright © Moonshine AI Ltd. All Rights Reserved

정확한 조건은 [공식 v0.1.5 LICENSE](https://github.com/moonshine-ai/moonshine/blob/v0.1.5/LICENSE)를
기준으로 합니다. APK는 `MOONSHINE-0.1.5-LICENSE.txt`와 `MOONSHINE-NOTICE.txt`를 포함하지만,
그 사실만으로 상업 사용 또는 모델·음성 재배포가 승인되는 것은 아닙니다.

## 공공용어 통합 데이터

로컬 빌드 입력 `app/src/main/assets/glossary/public-20260905.db.gz`와 APK의
`assets/glossary/public-20260905.db`는 국립국어원 공공용어 XLS를 변환한 영어·중국어·일본어
공공용어 데이터입니다. 통합 제공처는 국립국어원이며 행별 원출처에는 국립국어원, 서울시,
한국관광공사, 외교부, 행정안전부가 포함됩니다. 출처·행수·해시는
`app/src/main/assets/glossary/provenance.json`, 고지는
`app/src/main/assets/licenses/PUBLIC-TERMINOLOGY-NOTICES.txt`에 기록되어 있습니다.

원본 XLS와 변환 DB는 GitHub 공개 대상에서 제외하고 앱 빌드용 로컬 입력으로 관리합니다.
[자료 출처·다운로드 및 준비 방법](TERMINOLOGY_DATA.md)을 제공하며, 앱에도 출처와
다운로드 위치를 고지합니다. [국립국어원 저작권 정책](https://www.korean.go.kr/front/nuri/pageView.do?mkn=3&page_id=P000189)은
홈페이지 제공 자료의 이용을 허용하며 별도로 표시된 공공누리 유형의 조건을 따르도록 안내합니다.
자료의 기존 권리와 이용조건은 유지되며 MCastTalk 코드 라이선스로 재허가하지 않습니다.

## 개발자 음성·문체 실험의 API와 검토 출처 (2026-09-14)

이 변경은 새 음성 SDK·가중치·클라우드 TTS 의존성을 추가하지 않습니다. Android 설치 음성의
표현 시험은 기존 [TextToSpeech.setPitch](https://developer.android.com/reference/android/speech/tts/TextToSpeech#setPitch(float))와
[setSpeechRate](https://developer.android.com/reference/android/speech/tts/TextToSpeech#setSpeechRate(float))를
사용합니다. Android framework API 사용 조건과 설치한 음성 제공자의 음성·서비스 조건이
각각 적용됩니다. 이 기능은 사용자 문체·문장 부호에 따른 제한된 속도·높낮이 요청이며,
원음의 감정 인식·음성 복제 또는 감정 재현 모델을 포함한다고 표시하지 않습니다.
Moonshine 네이티브 합성에는 이 Android 설정을 적용하지 않습니다. Gemma 문체 지침은
기존 선택 모델과 기존 라이선스를 사용하며 모델이나 원문 문장을 다시 허가하지 않습니다.

업그레이드 검토와 실제 포함 상태를 구분합니다.

| 검토 대상 | 공식 출처·버전 | 현재 적용·제한 |
|---|---|---|
| Moonshine Voice | [v0.1.5 release/tag](https://github.com/moonshine-ai/moonshine/releases/tag/v0.1.5), [해당 LICENSE](https://github.com/moonshine-ai/moonshine/blob/v0.1.5/LICENSE) | 현재 포함 SDK와 같은 릴리스. 코드 MIT와 한국어 legacy 모델의 Community License를 구분하며 위 NOTICE를 유지합니다. 확인되지 않은 모델 교체 없음 |
| ML Kit GenAI Speech Recognition | [Android 공식 문서](https://developers.google.com/ml-kit/genai/speech-recognition/android), `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1` | **미포함 검토 옵션**. 기본 모드는 Android 31 이상, 고급 모드는 문서상 Pixel 10·11. 기기·언어별 상태 확인과 모델 준비가 필요하고 alpha 호환성 변경 가능. Galaxy에서 고급 모드 동작이나 지연·정확도를 검증하지 않음 |
| ML Kit GenAI 조건 | [ML Kit Terms](https://developers.google.com/ml-kit/terms), [Google APIs Terms](https://developers.google.com/terms) | SDK 추가 시 약관·AAR 제3자 고지·기기 지원·모델 크기·오프라인 동작을 검토하고 시험한 뒤 별도로 기록해야 함. 현재 포함 고지 수는 이 검토만으로 증가하지 않음 |

GitHub 소스 공개 시 새 의존성이 생기면 package 좌표·정확한 tag 또는 commit·SPDX 식별자·
LICENSE/NOTICE 원문·변경분을 함께 기록합니다. 문서에 링크만 추가한 검토 대상은 APK 포함
구성요소로 세지 않습니다. 공식 API 문서의 설명은 구현 호환성의 근거이며 해당 서비스의
음성·모델·상표를 재배포할 권한을 대신하지 않습니다.

## 소스 배포 도구

저장소에는 Gradle Wrapper `8.13`(Apache-2.0)이 포함됩니다. Android Gradle Plugin
`8.13.2`와 Kotlin Android/JVM/Compose Compiler plugins `2.3.21`은 Apache-2.0,
JUnit `4.13.2`는 EPL-1.0이며 개발·시험 시 별도로 내려받습니다.
