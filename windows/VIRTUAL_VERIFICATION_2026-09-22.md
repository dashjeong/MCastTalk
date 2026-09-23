# 가상 검증 준비·회귀시험 결과 — 2026-09-22

이 문서는 **재부팅 전 기록**이다. 재부팅 후 실제 VM 실행, 발견한 DLL 누락,
정적 네이티브 실행기 보완 및 최종 판정은
`POSTBOOT_OFFLINE_VERIFICATION_2026-09-22.md`를 우선한다.

## 판정

**실제 인터넷 차단 Windows 가상 시험은 미실행이다. 통과한 것으로 커밋하지 않는다.**
사용자의 검증 통과 후 커밋 요청을 조건부 승인으로 기록했다. Windows Sandbox 기능
활성화 후 재부팅이 필요하며, 이번 작업에서는 재부팅·호스트 네트워크 변경·실사용자
설치본/계정 변경·stage/commit/push를 수행하지 않았다.

읽기 전용 재확인: HypervisorPresent=false, WindowsSandbox.exe 없음,
마지막 부팅 2026-09-21 20:21:23 +09:00. 게스트 acceptance.json도 없다.
자동·모의 시험은 이 실제 OS 격리 시험을 대체하지 않는다.

## 이번에 보완한 검증

- 빈 파일 목록, 중복/대소문자 별칭, 잘못된 해시, 경로 이탈/Windows 대체 스트림,
  변조·누락 파일을 정상으로 판단하지 않도록 번들 검증기를 강화했다.
- 실패한 CLI 검증은 종료 코드 1과 passed=false를 기록하여 이전 PASS JSON을 남기지 않는다.
- Windows 네트워크 조회 오류를 빈 네트워크로 취급하던 경로를 fail-closed로 변경했다.
- 게스트 결과에 실행 시각/설치본 SHA를 기록하고 실패 시 비정상 종료한다.
- 검증기 회귀시험 29개를 추가했다. 모의 네트워크 조회는 실제 호스트 설정을 바꾸지 않으며,
  양성 대조와 실패 원인 확인을 포함한다. 이 시험을 OS 격리 증거로 표시하지 않는다.
- 독립 JVM 시작 시험에서 부모의 Java 옵션이 흘러들지 않게 했다. 합성 홈은 OS Temp가
  아닌 source/.run/st 아래로 분리하고, TEMP/TMP는 계속 긴 경로로 설정한다.
  이는 앱 런타임 변경이 아니라 테스트 환경 격리 보완이다.

## 실행 결과

| 구분 | 이번 실행 결과 | 범위 |
|---|---:|---|
| worker | 39 PASS | 단위·계약 시험, 실제 모델 품질 점수 아님 |
| bootstrap | 38 PASS | 하드웨어/모델/경로 정책 모의 시험 |
| packaging | 55 PASS | 기존 26 + 증거 검증기 29 |
| 초대 링크 | 10 PASS | Node 단위 시험 |
| Windows host | 122 PASS | 최종 재실행: 실패/오류/건너뜀 모두 0 |
| 번들 무결성 | 1,782 파일 SHA 일치 | 앱 이미지 검증, VM 설치 실행 아님 |
| 라이선스 뷰어 | SHA 일치 | 오프라인 HTML 무결성 |
| 인터넷 차단 Windows | 미실행 | 재부팅 필요, 통과 아님 |

이번 최종 자동·모의 회귀시험 합계는 **264 PASS**다. 반복 실행 횟수나 파일 해시 검사는
시험 개수에 더하지 않았다. 서버 JUnit 23개 suite/122개 시험을 19:29 KST에 확인했다.
긴 TEMP 및 Java 옵션 미상속 독립 JVM 시험도 통과했다. GUI/사람 UAT/실제 VM 264회라는
뜻이 아니며 실제 격리 Windows 통과 조건은 여전히 미충족이다.

Gradle는 `--offline`으로 실행했다. 기본 Windows Temp 아래 JDK AF_UNIX 연결에서
`Invalid argument: connect`가 재현되어 이번 빌드 프로세스에만
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<source>/.run/jvm-tmp`를 설정했다.
앱 자체 시작 시험의 자식 JVM에서는 이 옵션을 제거하여 우회 설정에 의존하지 않는지 확인한다.
시스템 환경변수·보안 정책·방화벽 설정은 바꾸지 않았다. 과거 PASS를 이번 재실행 PASS로
대체 기재하지 않는다. 이전 실패 XML은 로컬 증거 폴더에 보존한다.

## 설치본 및 재개 경로

설치본: `build/windows-packaging/media-041-notices-r1-installer/MCastTalk-0.4.1-Offline-Preview.exe`

- SHA-256: `e6ebecf9c8c0ef12f60ad26cfba6823c826da03c9be24198e67db4dd44105ad1`
- 배포 앱 host.jar: `dd0fe6d4c8f086e4928e81ade06cc4beb9783cb03968fc932e059b0763284f6f`
- 이번 변경은 시험/검증/문서이며 기존 설치본 런타임은 교체하지 않았다.
- 새 키트: `.run/virtual-review-20260922/networkless-sandbox/MCastTalk-network-disabled.wsb`
- 키트 4개 스크립트의 원본 SHA 일치, Networking=Disable, 입력 2개 읽기 전용 및
  전용 결과 폴더 1개만 쓰기 가능을 확인했다. 실제 VM 실행은 아직 하지 않았다.
- 로컬 증거: `.run/virtual-review-20260922/`의 시험 로그, payload.json,
  windows-prerequisites.json, socket-fixture-before-fix.xml 및 커밋 감사 JSON.
- 서버 최종 증거: `host-tests.log`, `host-test-summary.json`, `host-junit/`.
- 커밋 후보 187개 파일의 휴리스틱 검사에서 문제 0, staged 파일 0을 확인했다.

재부팅 후 하이퍼바이저·Sandbox 가용성을 다시 확인하고 새 키트를 실행한다.
`packaging/tests/NETWORKLESS_ACCEPTANCE.md`의 실제 게스트 시험 및 추가 GUI/회의/재시작·제거 시험을
완료한 뒤 결과를 판정한다. 인터넷 없는 LAN 실기기 시험은 완전 무네트워크 Sandbox와
별도다. 기존 음성 지연·중국어 ASR·GPU·미서명 프리뷰 한계와 사람에 의한 UAT도 남아 있다.

커밋 대상은 명시적인 Windows 소스·시험·문서와 관련 빌드 설정이다. `.run`, 실제 계정/키,
모델·실행파일은 제외한다. 비밀정보 검사 휴리스틱은 완전한 보안 감사를 의미하지 않는다.
Antigravity의 이번 변경 독립 PASS를 수신한 것처럼 기록하지 않는다.
