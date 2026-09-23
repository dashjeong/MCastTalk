# 설치 안내 개정 및 인터넷 차단 검증 준비

## 결과

사용자 승인에 따라 설치의 일괄 라이선스 동의 화면을 제거하고, 프로그램 기본 설명과
통상적인 설치 실행 확인으로 변경했다. 구성요소별 출처와 원문은 선택 열람이다.
Supertonic 모델의 회의 참가 시 별도 확인과 서버 검증은 변경하지 않았다.

- 설치 후보: `build/windows-packaging/media-041-notices-r1-installer/MCastTalk-0.4.1-Offline-Preview.exe`
- 크기: **4,023,020,263 bytes**
- SHA-256: `e6ebecf9c8c0ef12f60ad26cfba6823c826da03c9be24198e67db4dd44105ad1`
- app image: `build/windows-packaging/media-041-notices-r1/MCastTalk`
- host.jar SHA-256: `dd0fe6d4c8f086e4928e81ade06cc4beb9783cb03968fc932e059b0763284f6f`
  (기존 검증본과 동일). host/worker/모델 실행 코드는 수정하지 않았다.
- 기존 설치본과 사용자 작업 공간은 업그레이드·초기화하지 않았다. GitHub 작업 없음.

## 변경 사항

- `offline.iss`: 단독 Supertonic `LicenseFile` 삭제. 기본 한국어 안내, 선택적
  원문 보기 버튼, 설치 후 시작 메뉴 바로가기 제공. 설치 확인과 모델 조건 분리.
- `build_notices.py`: 59개 구성요소 항목 / 중복 제거한 80개 원문을 하나의 HTML에
  포함. 공식 출처는 선택 외부 링크이며 원문은 내부 앵커로 연결. 외부 스크립트·폰트·
  이미지·스타일을 사용하지 않고 CSP로 외부 리소스 로드를 막는다.
- Apache 자체 코드 / 모델 / 예제 코드의 조건을 구분하고 Whisper 모델(OpenAI)과
  whisper.cpp(ggml)의 서로 다른 MIT 고지를 보존했다. 고정 버전 Netty 원본 및
  NOTICE에서 참조한 부속 원문도 포함했다. 이것은 전이 의존성 법률 감사 완료가 아니다.
- 모델/런타임 다운로드는 개발 중에만 이루어졌다. 설치·앱 실행·라이선스 열람에서
  원문을 새로 다운로드하는 코드는 없다. 실제 인터넷 차단 검증은 아래 별도 게이트다.

## 이번에 실행한 검증

| 항목 | 결과 | 범위 |
|---|---|---|
| Python packaging tests | 26 PASS | 고지 생성·원문·해시·링크·누락 거부·설치 스크립트 계약 |
| Node staging syntax | PASS | stage-offline.cjs 구문 |
| Inno Setup 6.7.3 build | exit 0 | 수정 단일 EXE 컴파일 |
| 번들 무결성 | 1,782개 파일 SHA-256 PASS | 배포 app image의 모델·Python·worker 등 |
| 라이선스 HTML 해시 | PASS | `56fb7424deeb9f3e4a30262d0f6432836cab940cbe63c320d6de5c81b5132646` |
| Edge offline 문서 시험 | 8 PASS | 59개 항목·80개 원문·내부 이동·외부 요청 0·예외 0 |
| 설치 안내 GUI | **미완료** | 아래 실행 환경 제한 |
| 인터넷 차단 Windows 설치/회의 | **미실행** | Sandbox 활성화 완료, 재부팅 필요 |

증거: `.run/installer-notices-20260922/payload.json`, `browser/browser-results.json`,
`browser/license-viewer.png`. 브라우저 offline 모드 검사를 OS 전체 인터넷 차단으로
해석하지 않는다. 종전 host/worker 시험 결과를 이번에 재실행했다고 합산하지 않는다.

## GUI 실행 제약

제한된 도구 세션에서 설치 UI를 열었을 때 Windows 사용자 셸의 `localappdata`
상수를 확장하지 못해 설치 이전에 중단됐다(`ui.log`). 실제 사용자 세션에서 다시
열기 위한 computer-use 승인은 시간 초과됐다. 이를 GUI PASS로 기록하지 않는다.
테스트로 시작한 PID 38492 및 자식 PID 8588만 종료했으며 `ui-preview-only` 설치
디렉터리는 생성되지 않았다. UI/설치/제거의 새로운 실제 증거는 Sandbox에서 얻어야 한다.

## Windows Sandbox 준비와 재부팅 게이트

사용자의 ‘진행해’ 승인 후 Windows UAC를 거쳐 `Containers-DisposableClientVM`을
`Enable-WindowsOptionalFeature -All -NoRestart`로 활성화했다.
실제 결과: Disabled → Enabled, **RestartNeeded=true**, completed=true.
자동 재부팅은 하지 않았다. 결과 파일:
`.run/installer-notices-20260922/sandbox-activation/sandbox-enable-result.json`.

- 구성: `.run/installer-notices-20260922/networkless-sandbox/MCastTalk-network-disabled.wsb`
- Networking=Disable, vGPU/mic/camera/clipboard/printer=Disable, RAM 10 GiB.
- 설치본 및 시험 도구 폴더만 읽기 전용 공유; 별도 results 폴더만 쓰기 허용.
- 시험 도구는 제품 설치본에 포함하지 않는다. 실제 사용자 계정·데이터는 공유하지 않는다.
- 게스트에서 NIC/기본 경로 부재와 공인 IP TCP 실패를 설치 전후 확인한다.
  설치 SHA/전체 번들 SHA/초기 테스트 계정/모델 배치/실행 EXE/로컬 로그인 화면/
  실제 CPU STT→4개 언어 번역·TTS를 검증하도록 준비했다.
- 테스트 계정 helper는 수동 관리자 설정 GUI 시험이 아니다. synthetic 발화는
  실제 사람의 언어 품질 검수나 카메라 검수의 대체가 아니다.

재부팅 후 Sandbox를 실제 실행하고 결과를 확인하기 전에는 **‘인터넷 차단 Windows
검증 완료’, ‘오프라인 전용 설치 검수 완료’, 정식 배포 완료**라고 표시하지 않는다.
추가적인 브라우저별 회의/재시작/제거·데이터 보존 및 WAN 없는 실기기 LAN 시험은
`packaging/tests/NETWORKLESS_ACCEPTANCE.md`의 별도 항목이다.

이전의 통역 지연/중국어 ASR/GPU 안정성/미서명/실기기 검수 미완료 사항은 그대로다.
