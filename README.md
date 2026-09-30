# MCastTalk

> **현재 공개 버전: 0.2.46-beta-b — 문장 확정·E4B 번역 보정**
> **[APK 다운로드 및 변경 내용](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.46-beta-b)** · [이 버전의 소스](https://github.com/dashjeong/MCastTalk/tree/v0.2.46-beta-b)
> 기존 E2B와 일반 E4B-IT 선택을 유지합니다. 지연된 음성 입력에서 원문만 남고 번역이 대기하던 문장 확정 결함과 번역 엔진 표시를 수정했습니다.
> E4B의 발화 해석을 보완하고 Gemini 참조와 실제 로컬 추론을 비교했습니다. [문장 확정 수정·검증](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/E4B_LISTENING_REGRESSION_REPORT.md) · [샘플별 번역 비교·남은 오류](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/E4B_TRANSLATION_QUALITY_REPORT.md)
> **공개 시험판입니다.** 23문장 비교에서 20건 통과·2건 주의·1건 오역이 남았고, 첫 통역 음성 지연은 에뮬레이터 1회 측정에서 5.117초였습니다. 물리 S23의 p95 2초와 8시간 연속 안정성은 입증하지 않았습니다.

**실시간 다국어 통역방송(feat. DMZ peace walk)**

**0.2.46-beta-b — E2B·E4B 모델 선택과 실시간 통역 보완**

실시간 방송·통역 / 음성노트 / 파일 변환·재생을 세 작업 공간으로
구분했습니다. 음성노트는 녹음 중 전사와 저장 후 확인, 원문과 괄호 번역, 원음 재생,
TXT·SRT·Markdown·JSON·WAV 저장과 녹음까지 포함하는 백업·가져오기를 제공합니다.
[사용 방법과 지원 범위](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/VOICE_NOTES.md) · [문맥 교차 검토](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/COMPARATIVE_LEARNING.md)

[공개 APK: 0.2.46-beta-b](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.46-beta-b) ·
[실행한 시험과 그 한계](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/TEST_REPORT.md) ·
[GitHub CI 성공 기록](https://github.com/dashjeong/MCastTalk/actions/runs/36737153543)
버전은 `0.2.46-beta-b`, `versionCode 55`입니다. 기존 Beta와 같은 앱 ID·배포자 서명으로 덮어 설치할 수 있습니다.
최종 소스의 단위시험 결과 1,409건과 에뮬레이터 계측시험 7개를 검증했으며, 공개 파일의 서명·정렬·SHA-256을 확인했습니다.
기존 [0.2.45-beta-c](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.45-beta-c)와
[0.2.42](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.42-alpha)는 보존합니다. 0.2.43은 계속 철회 상태입니다.

아스트라 미니미는 기기 상태를 진단하고 개선안을 안내합니다. 실험실에서는 필요한 문장만
스승 API와 비교하고, 전후 리포트를 확인한 사용자가 승인·보류합니다. 승인 전에는 기존
번역을 유지합니다. 설정에서 OpenAI·Gemini·표준 OpenAI 호환 API와 문맥별 문체를 선택할 수
있으며, 외부 전송은 별도 허용이 필요합니다. [요구사항·벤치마킹·검증 기준](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/PRODUCT_EVOLUTION.md)

Android 스마트폰에서 음성을 입력받아 원음과 통역 음성을 로컬 Wi-Fi/핫스팟으로
송출합니다. 청취자는 별도 앱 없이 웹브라우저로 음성과 자막을 확인합니다.
**이 기기에서 사용**을 선택하면 청취자 서버를 열지 않고 단독으로 사용할 수 있습니다.
파일 일괄 변환·재작업, 헤드업 스크립트, 방송별 보관함과 설정·사전·스크립트의
기기 내 백업/가져오기를 제공합니다. 필요한 로컬 모델과 음성은 먼저 준비해야 합니다.

📖 **[상세 사용 설명서 포털 (User Guide Hub)](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/USER_GUIDE.md)**
└ [🇰🇷 한국어 설명서](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/USER_GUIDE_KO.md) · [🇺🇸 English Guide](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/USER_GUIDE_EN.md) · [🛠️ 현장 문제 해결](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/TROUBLESHOOTING_KO.md)

Powered by Codex, Gemini with dash.jeong — AI-assisted development.

## 개발 배경

MCastTalk가 언어의 장벽을 낮추고, 사회적 약자를 포함한 누구나 DMZ를 함께 보고,
듣고, 걸으며 즐길 수 있도록 돕기를 희망합니다. DMZ가 전 세계인이 함께하는
여행지이자 평화 소통의 공간이 되는 데 이 앱이 작은 징검다리가 되고자 합니다.

## 사용 환경

- 송신기: ARM64, Android 11/API 30 이상. 시험용 기기는 Galaxy S20 이상을 권장합니다.
- 모델·음성을 먼저 준비한 뒤 오프라인 통번역을 사용합니다. 초기 다운로드와
  일부 Android/Google SDK 서비스에는 인터넷이 필요할 수 있습니다.
- 지원 언어, 음성 및 안정적인 동시 채널 수는 기기·설치 자산에 따라 다릅니다.
- 시험판이며, 모든 기기의 지연·음질·처리량·무중단 운영을 보증하지 않습니다.

### 사용자 실기기 시험 보고 (2026-09-06)

| 기기 | 사용자가 보고한 시험 범위 |
|---|---|
| Galaxy Note9 | 실기기 시험 수행, 속도 지연 발생 |
| Galaxy S21 Ultra | 원음 외 5개 언어 동시 송출 시험 완료 |
| Galaxy S23+ | 실기기 시험 수행 |
| Galaxy S26 Ultra | 원음 외 5개 언어 동시 송출 시험 완료 |

위 내용은 사용자 보고이며 시험 APK 해시·버전, OS 버전, 지연 측정값과 시험 시간은
제출되지 않았습니다. 이 소스의 최종 APK와 동일한 파일을 검증했다는 뜻은 아니며,
Note9 시험 보고가 현재 빌드의 Android 11 이상 요구사항을 변경하지 않습니다.
시험판은 전용 시험폰에서 먼저 확인하세요. 소스에서 만든 debug 빌드의 시험 서명과
공개 APK의 배포자 서명은 다릅니다.

## 시작하기

1. 배포 파일의 버전·SHA-256을 확인하고 송신기에 설치합니다.
2. 사용할 입력과 통역 언어를 선택하고 필요한 모델·음성을 준비합니다.
3. 시험 화면에서 자막과 통역 음성을 확인합니다.
4. 핫스팟/로컬 네트워크를 열고 방송을 시작합니다.
5. 청취자는 같은 네트워크에서 QR/표시 주소로 접속하고 재생을 누릅니다.

마이크 제어와 방송 시작·중지는 별개입니다. 방송 접속은 공개 모드가 기본이며,
필요하면 지원되는 PIN/QR 입장 제어를 선택합니다. HTTP 청취 방송은 암호화되지
않으므로 신뢰하는 네트워크에서만 사용하세요. 원격 웹마이크는 브라우저의
보안 컨텍스트와 마이크 권한이 필요합니다. [개인정보·보안 안내](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/SECURITY.md)

## 소스 빌드

현재 공개 APK에 대응하는 소스는 **`v0.2.46-beta-b` 태그**입니다. 이 버전을 빌드하려면 먼저 해당 태그를 선택하세요.

```sh
git fetch origin tag v0.2.46-beta-b
git switch --detach v0.2.46-beta-b
```

JDK 17, Android SDK API 36 및 빌드 파일에 지정된 도구가 필요합니다.
공공용어 데이터는 GitHub에 포함하지 않습니다. 사전 기능을 포함한 앱을 만들려면
[공식 다운로드 및 로컬 준비 안내](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/TERMINOLOGY_DATA.md)를 먼저 따르세요.
데이터 없이도 소스 CI 빌드는 가능하지만 기본 사전 기능은 사용할 수 없습니다.

```sh
./gradlew :app:assembleDebug
node scripts/verify-listener-player.mjs
node scripts/verify-listener-i18n.mjs
node scripts/verify-speaker-mic.mjs
```

로컬 debug 서명은 배포자 서명과 다릅니다. 개인 서명키·계정 설정은 포함하지 않습니다.
별도 다운로드 모델과 일부 제3자 데이터의 이용·재배포 조건도 확인해야 합니다.

## 라이선스와 제3자 권리

Copyright 2026 dash.jeong. MCastTalk 자체 작성 소스는 [Apache License 2.0](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/LICENSE)에
따라 사용·수정·재배포 및 상업적 이용을 허용합니다. 해당 이용에 개발자의 별도 허가는
필요하지 않으며 저작권·라이선스·필요한 고지와 변경 표시 의무를 준수해야 합니다.
MCastTalk 상표·로고의 제품 브랜드 사용이나 공식 제휴·보증 표시의 허가는
`dash.jeong@gmail.com`으로 문의하세요. 정당한 출처 표시·사실 설명과 독립적인
서비스 구현에는 별도 협의를 요구하지 않습니다. [브랜드 이용 안내](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/BRANDING.md)

제3자 코드·SDK·모델·음성·데이터는 각자의 조건을 따르며, MCastTalk 자체 소스
라이선스로 함께 허가되는 것은 아닙니다.

- [NOTICE](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/NOTICE): 필수 저작권·제3자 고지
- [BRANDING.md](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/BRANDING.md): 소스 이용 허락과 구분되는 브랜드·공식 제휴 안내
- [제3자 구성과 이용조건](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/THIRD_PARTY_LICENSES.md)
- 앱의 **라이선스** 메뉴: 포함된 고지 전문과 구성별 안내

**Powered by Moonshine AI**는 해당 구성요소의 필수 고지입니다.
앱의 통합 공공용어 사전은 국립국어원과 참여 공공기관의 자료를 사용하며,
[자료 출처·다운로드 위치](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/docs/TERMINOLOGY_DATA.md)를 제공합니다.
GitHub에는 원자료와 변환 DB를 올리지 않으며 앱 빌드용 로컬 데이터로 관리합니다.
원자료는 제공처의 이용조건을 따르며 자체 소스 라이선스로 재허가하지 않습니다.
별도 다운로드 모델·음성의 이용·재배포 조건은 제3자 고지를 확인하세요.

## 문의·기여

[기여 안내](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/CONTRIBUTING.md) · [보안 문제 신고](https://github.com/dashjeong/MCastTalk/blob/v0.2.46-beta-b/SECURITY.md#3-reporting-a-vulnerability)
개발자: dash.jeong@gmail.com
