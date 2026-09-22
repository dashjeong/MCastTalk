# 0.2.43 개발 업데이트 검증 — 2026-09-21

이 문서는 초기 개발 체크포인트의 역사적 기록입니다. 최종 Alpha 배포의 시험 환경,
결과와 설치 파일 식별자는 [현재 시험 보고서](TEST_REPORT.md)를 확인하세요.

기준: 공개 main `efb254d8d6246f894ac53b04048d628fdb437986`.
기능·지원 범위는 [음성노트와 서비스 홈](VOICE_NOTES.md)을 참고하세요.

## 수행 결과

- `:app:testDebugUnitTest`: 420개 통과, 실패·오류·건너뜀 0.
  새 8개 테스트는 원문/괄호 번역, TXT/SRT 시각·문단, WAV 데이터·헤더,
  중단 녹음 복구와 크기 제한을 확인합니다.
- `:app:assembleDebug`: 성공.
- `:app:lintDebug`: 성공, 오류 0, 경고 77, 참고 6. 새 저장 공간 검사 2곳은
  `usableSpace` 대신 할당 가능 공간 API도 고려하라는 비차단 경고입니다.
- `:app:compileAlphaAndroidTestKotlin`: 성공. 기기 테스트 소스 컴파일만 확인했습니다.
- `apksigner verify --verbose`: APK v2 서명 검증 성공.
- `verify-public-branding.mjs`, `verify-listener-player.mjs`,
  `verify-listener-i18n.mjs`, `verify-speaker-mic.mjs`: 통과.
- `test-public-snapshot.py`: 9개 통과. `verify-public-snapshot.py`: 통과.
- `git diff --cached --check`: 통과.

Windows 빌드에서는 공식 Google 배포본과 대조한 aapt2 Windows SHA-256을
의존성 검증 목록에 추가했습니다. 검증 모드를 완화하지 않았습니다.
ONNX 고지문의 원본 해시를 유지하도록 해당 파일의 LF 체크아웃을 지정했습니다.

## 시험용 설치 파일

- 이름: `MCastTalk-0.2.43-debug.apk`, 132,376,210 bytes.
- 패키지: `app.guidecast.transmitter.debug`, `versionCode 49`, `0.2.43-debug`.
- Android 11/API 30 이상, target/compile API 36. 받아쓰기는 Android 13 이상 필요.
- SHA-256: `8c32c064589d64d168ebbfad90336741b65d4f6657a2c980fdf4ee7ab5b9336b`.

개발용 서명이므로 기존 공개 alpha 앱과 별도로 설치합니다. 기존 앱의 설정·노트를
자동 이전하는 업데이트 APK가 아닙니다. 공개 저장소에서 제외된 별도 용어사전 DB는
이 소스 빌드에 포함되지 않았습니다. 새 공개 릴리스나 정식 서명 APK는 게시하지 않았습니다.

## 실행하지 못한 검증

이번 환경에서 기기 테스트를 실행할 Android 기기/에뮬레이터 연결을 확보하지 못했습니다.
새 서비스 홈 왕복·음성노트 저장소 기기 테스트와 기존 운영자 화면 테스트를 추가·수정했지만
실제 UI, 마이크 녹음, 한국어/영어/일본어/중국어 전환, 번역 품질, 복구, 문서 선택기
내보내기는 기기에서 검증하지 않았습니다. 이전 버전의 기기 시험 결과를 이번 APK의
검증으로 간주하지 않습니다. 배포 전 [실기기 수용 시험](VOICE_NOTES.md#후속-실기기-수용-시험)이 필요합니다.
