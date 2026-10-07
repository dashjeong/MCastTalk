# MCastTalk

**실시간 다국어 통역방송(feat. DMZ peace walk)**

Android에서 음성을 통번역하고, 같은 Wi-Fi 또는 핫스팟의 청취자에게 음성과 자막을 전달하는 앱입니다. 청취자는 별도 앱 없이 웹브라우저로 접속합니다. 기기 단독 사용, 음성노트, 파일 변환·재생을 지원합니다.

## 현재 공개 버전 · 2026-10-07 확인

| 설치 채널 | 최신 공개 패키지 | 설치·검증 범위 |
| --- | --- | --- |
| 모바일 별도 시험 앱 | **[0.2.51-r3-lab](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.51-r3-lab)** · 앱 내부 0.2.51-debug · versionCode 63 | 별도 `.debug` 앱. 단위시험 1,101 PASS, lint 오류 0·경고 101. 실기·5초 지연·Gemini 품질은 미검증. |
| 모바일 기존 alpha 앱 업데이트 | **[0.2.50-beta](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.50-beta)** · versionCode 62 | 기존 공개 앱 업데이트용 시험판. r3-lab의 별도 수정·시험 결과를 이 APK에 적용해 해석하지 마세요. |
| Windows 데스크톱 | **[0.2.49-desktop](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.49-desktop)** | Windows x64 사전 공개판. 모바일과 별도 버전이며 검증 범위는 해당 릴리스 안내를 따릅니다. |

**[최신 모바일 시험 APK 다운로드](https://github.com/dashjeong/MCastTalk/releases/download/v0.2.51-r3-lab/MCastTalk-0.2.51-r3-lab.apk)** · [전체 릴리스](https://github.com/dashjeong/MCastTalk/releases) · [한국어 사용 안내](docs/USER_GUIDE_KO.md) · [English guide](docs/USER_GUIDE_EN.md)

모두 시험판(prerelease)이며 정식 안정판으로 승격한 것이 아닙니다. 시험판도 버전은 올라갑니다. GitHub의 [`Latest` API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)는 초안·시험판을 제외하므로 현재 정식 Latest는 없습니다. 위 채널별 링크로 선택하세요.

## 설치와 사용

- 송신기: Android 11(API 30) 이상, ARM64.
- 다운로드한 APK의 SHA-256을 해당 릴리스의 체크섬과 비교하세요. r3-lab은 `SHA256SUMS-public-source-v2`를 사용합니다.
- **0.2.51-r3-lab:** 패키지 `app.guidecast.transmitter.debug`, 전용 debug 서명으로 일반 공개 앱과 별도 설치됩니다. 기존 앱 데이터는 자동 이전되지 않습니다. 같은 `.debug` 앱이 다른 서명으로 설치돼 있으면 업데이트가 거절될 수 있습니다. 충돌 해결을 위해 먼저 삭제하지 마세요. 삭제하면 해당 앱의 로컬 데이터가 지워집니다. 필요한 데이터를 내보내거나 다른 시험 기기를 사용하세요.
- **0.2.50-beta:** 기존 alpha 계열 공개 앱 업데이트용입니다. 별도 r3-lab과 설치·서명 경로가 다릅니다.
- 통역 언어를 선택하고 필요한 모델·음성을 먼저 준비합니다. 초기 다운로드에는 인터넷이 필요합니다.
- 기기 내 통역은 준비된 모델을 사용합니다. 온라인 API는 제공자·모델·키와 외부 전송 허용을 설정해야 하며 제공자 요금이 발생할 수 있습니다.
- 방송을 시작한 뒤 청취자는 같은 네트워크에서 표시된 주소 또는 QR로 접속해 재생을 누릅니다.

## 제공 기능

- 로컬 E2B/E4B 모델 선택, 온라인 번역 API 선택, 언어별 음성·자막.
- 방송별 최근 문맥 참고와 음성팩 준비·복구 안내.
- 음성노트의 원음·원문·번역 보관, 편집, 재생, 내보내기와 백업·가져오기.
- 파일 통번역·재생, 실시간 스크립트, 사용자 사전과 설정 이관.

지원 언어와 동시 처리 성능은 선택한 인식·번역·음성 엔진, 설치 자산과 기기에 따라 다릅니다.

## 알려진 제한

이번 공개는 현장 실험용입니다. 실제 마이크 통역의 무출력·중단 문제가 남아 있으며, 실제 LAN 청취·발화 종료 후 5초 이내 출력·30분 이상 연속 운용·도메인 자료에 따른 품질 향상은 완료 판정 전입니다. [후속 조치와 현장 결과 #24](https://github.com/dashjeong/MCastTalk/issues/24)에서 추적합니다.

시험판이며 번역·음성인식 오류와 통역 지연이 발생할 수 있습니다. 러시아어 음성 출력은 호환되는 러시아어 오프라인 음성 데이터를 기기에 설치해야 합니다. 모든 언어·기기의 정확도, 장시간 연속 운영과 사람 수준의 통역 품질은 검증되지 않았습니다. HTTP 청취 방송은 암호화되지 않으므로 신뢰할 수 있는 네트워크에서 사용하세요. 철회된 0.2.43은 사용하지 마세요. 이전 버전으로 되돌리기 전에 데이터를 백업하세요.

[문제 해결](docs/TROUBLESHOOTING_KO.md) · [개인정보·보안](SECURITY.md)

## 소스와 라이선스

`main`의 문서나 버전 표시는 각 배포 APK의 빌드 소스와 동일하다는 뜻이 아닙니다.

- **0.2.51-r3-lab:** 태그는 공개 기준 커밋 `bd27b7adc366663f819495f07cdf677ff65e42ec`를 가리킵니다. GitHub 자동 제공 Source code zip/tar는 이 APK의 정확한 빌드 소스가 아닙니다. 첨부 [source-public-v2.zip](https://github.com/dashjeong/MCastTalk/releases/download/v0.2.51-r3-lab/MCastTalk-0.2.51-r3-lab-source-public-v2.zip)은 검증된 고정 소스에서 네 파일의 주석과 시험 진단 문자열을 공개용으로 정리한 export입니다. `SOURCE_MANIFEST.json`은 공개 파일 해시, `SOURCE_EXPORT_CHANGES.json`은 빌드 입력과의 변경 전후 해시를 제공합니다. **빌드 입력과 모든 바이트가 같지는 않으며**, 새 빌드·새 시험을 수행한 것으로 주장하지 않습니다. 1,101 PASS는 원래 고정 입력에 대한 결과입니다.
- **0.2.50-beta:** 공개 소스 기준은 `v0.2.50-beta` 태그의 `bd27b7adc366663f819495f07cdf677ff65e42ec`입니다. r3-lab과 별개입니다.
- **0.2.49-desktop:** 소스·실행파일 연결은 [데스크톱 릴리스](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.49-desktop)에 명시되어 있습니다.

모바일 빌드에는 JDK 17, Android SDK API 36과 선택한 소스의 Gradle 설정이 필요합니다. r3-lab 공개 export의 `VALIDATION_SOURCE.md`를 따르세요. 직접 빌드한 debug 앱의 서명은 배포본과 다를 수 있습니다. [용어 데이터 준비](docs/TERMINOLOGY_DATA.md)를 참고하세요.

자체 작성 소스: [Apache License 2.0](LICENSE). 제3자 구성·모델·음성·데이터는 각각의 조건을 따릅니다. [NOTICE](NOTICE) · [제3자 고지](docs/THIRD_PARTY_LICENSES.md) · [브랜드 안내](BRANDING.md)

문제 신고: [GitHub Issues](https://github.com/dashjeong/MCastTalk/issues). 공개 신고에 API 키, 개인 음성·원문·로그를 첨부하지 마세요.
