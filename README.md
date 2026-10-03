# MCastTalk

**실시간 다국어 통역방송(feat. DMZ peace walk)**

Android에서 음성을 통번역하고, 같은 Wi-Fi 또는 핫스팟의 청취자에게 음성과 자막을 전달하는 앱입니다. 청취자는 별도 앱 없이 웹브라우저로 접속합니다. 기기 단독 사용, 음성노트, 파일 변환·재생을 지원합니다.

현재 공개 패키지: **0.2.46-beta-c** · versionCode **56** · 공개 시험판

[APK 다운로드](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.46-beta-c) · [한국어 사용 안내](docs/USER_GUIDE_KO.md) · [English guide](docs/USER_GUIDE_EN.md)

## 설치와 사용

- 송신기: Android 11(API 30) 이상, ARM64.
- 다운로드한 APK의 SHA-256을 릴리스의 체크섬 파일과 비교하세요. 기존 공개 앱과 같은 배포 서명을 사용합니다.
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

시험판이며 번역·음성인식 오류와 통역 지연이 발생할 수 있습니다. 모든 언어·기기의 정확도, 장시간 연속 운영과 사람 수준의 통역 품질은 검증되지 않았습니다. HTTP 청취 방송은 암호화되지 않으므로 신뢰할 수 있는 네트워크에서 사용하세요. 철회된 0.2.43은 사용하지 마세요. 이전 버전으로 되돌리기 전에 데이터를 백업하세요.

[문제 해결](docs/TROUBLESHOOTING_KO.md) · [개인정보·보안](SECURITY.md)

## 소스와 라이선스

공개 APK에 대응하는 소스는 `v0.2.46-beta-c` 태그입니다. 기본 브랜치 `main`의 소스는 `0.2.44-alpha` 기준으로, 현재 공개 APK와 버전이 다릅니다. 빌드에는 JDK 17, Android SDK API 36과 Gradle 설정의 도구가 필요합니다.

```sh
git switch --detach v0.2.46-beta-c
./gradlew :app:assembleDebug
```

직접 빌드한 debug 앱은 공개 APK와 서명이 다릅니다. [용어 데이터 준비](docs/TERMINOLOGY_DATA.md)를 참고하세요.

자체 작성 소스: [Apache License 2.0](LICENSE). 제3자 구성·모델·음성·데이터는 각각의 조건을 따릅니다. [NOTICE](NOTICE) · [제3자 고지](docs/THIRD_PARTY_LICENSES.md) · [브랜드 안내](BRANDING.md)

문제 신고: [GitHub Issues](https://github.com/dashjeong/MCastTalk/issues). 공개 신고에 API 키, 개인 음성·원문·로그를 첨부하지 마세요.
