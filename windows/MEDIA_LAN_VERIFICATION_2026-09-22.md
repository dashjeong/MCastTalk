# 0.4.1 오프라인 음성·영상·LAN 검증 기록

검증일: 2026-09-22 KST. 구현·시험 담당: Codex. Antigravity의 0.4.0 인계 이후
공유 작업 큐로 읽기 전용 재검토를 요청했다. 이번 미디어 변경의 공동 PASS 회신은
아직 없으며, 이를 독립 합의 완료로 표시하지 않는다. GitHub staging/commit/push/
PR/remote 변경은 수행하지 않는다.

## 판단과 범위

과거 0.4.0의 168+58 시험은 채팅/보안 기반 검증이었다. 이번에는 실제 로컬 모델과
인증된 회의 경로, WebRTC 미디어, 사설 IPv4 HTTPS/WSS를 구현했다. 개발 후보이지
무결점·정식 배포·실제 사용자 품질 승인 선언은 아니다. 기존 0.3.1 설치, 사용자의
`Desktop/work` 계정과 저장된 launcher 설정을 변경하지 않았다.

현재 핵심 구성:

| 경로 | 구현 | 한계 |
|---|---|---|
| 음성 입력 | AudioWorklet, 16 kHz PCM, VAD, 0.25~6초 MCT1 발화 | 연속 스트리밍 STT가 아님 |
| STT | whisper.cpp 1.9.4, small Q5_1, CPU 4 threads, beam/best 1, context 512 | 중국어 합성 문장에서 단어 오류 |
| 번역 | Qwen3-4B-Instruct-2507 Q4_K_M, llama.cpp b10964 | Vulkan 시작/요청 실패 시 CPU 1회 복구, 정확도 보증 아님 |
| 음성 출력 | sherpa-onnx 1.13.8, Supertonic 3 ko/en/ja, Melo zh-CN, CPU | OpenRAIL-M 모델 조건 준수 필요 |
| 회의 전달 | 언어별 1회 계산, 참가자별 자막/음성, 비공개 대상 snapshot | 서버 직렬 작업 큐 4, presence별 1건 |
| 영상·원음 | 인증/방/presence 기반 WebRTC LAN mesh, 화면 track 교체 | 최대 8명 보호 한도; SFU/외부 STUN/TURN 없음 |
| LAN | 명시한 RFC1918 IPv4, TLS CA/SAN, Secure HttpOnly, 엄격한 Origin | 클라이언트 CA 신뢰/방화벽 설정 수동 |
| 설치 | 단일 Inno EXE, JVM+Python+모델+CPU/Vulkan+라이선스, 데이터 폴더 선택 | 미서명, 깨끗한 VM/사람의 최초 설정 UAT 미완료 |

## 최종 파일 식별

- 앱 이미지: `build/windows-packaging/media-041-final/MCastTalk`
- host.jar SHA-256: `dd0fe6d4c8f086e4928e81ade06cc4beb9783cb03968fc932e059b0763284f6f`
- 오프라인 번들: 1,782개 파일, 3,867,181,697 bytes. `manifest.properties`에 각 파일
  SHA-256, `asset-hashes.properties`에 모델/네이티브 런타임 SHA-256 기록.
- 단일 설치파일: `build/windows-packaging/media-041-installer/MCastTalk-0.4.1-Offline-Preview.exe`
  (4,022,375,855 bytes, 미서명, 모델 재다운로드 없음).
- 설치파일 SHA-256: `93bcff745f21ce6d60787c445b907ee6d805b6828d8014d6e58171fee6c3696c`.
  같은 폴더의 `.sha256` 파일로 대조할 수 있다. 최초 실패한 압축 설치본의 해시와 다르다.
- 예전 `media-041-candidate`는 중간 실패 재현용이며 최종 배포 파일이 아니다.

## 자동시험 — 최종 서버/작업자

| 묶음 | 통과 | 증거 |
|---|---:|---|
| Kotlin host | 122 | `host/build/test-results/test/TEST-*.xml`, 최종 Gradle BUILD SUCCESSFUL |
| Python worker | 39 | `python -m unittest discover -s windows/worker/tests -q` |
| bootstrap | 38 | `python -m unittest discover -s windows/bootstrap/tests -q` |
| packaging 계약 | 4 | `python -m unittest discover -s windows/packaging/tests -q` |
| 초대 규칙 Node | 10 | `node --test windows/host/tests/room-invitation.test.cjs` |
| 합계 | 213 | 모델 결과를 가짜 단위시험으로 대체한 수치가 아님; 단위/계약시험 구분 |

인증/게스트/청취 권한/잘못된 PCM/재입장 세대/탈퇴자·늦은 입장자 차단/비공개
통역 대상/큐 한도/실패 원문 전달/모델 해시/경로 이탈/취소 재개/CPU 복구를 포함한다.
20회 이상의 반복 실행을 부풀려 세지 않고 서로 다른 검증 항목으로 기록한다.

## 실제 브라우저·로컬 모델 시험

Playwright + 실제 Edge, 실제 Ktor/WebSocket/TLS/WebRTC를 사용했다. agent-browser
실행 도구가 없어 검증 스킬의 browser→API→worker→UI 경계를 기존 Playwright로
추적했다. 모든 계정·암호·작업 공간은 새 시험 fixture이며 사용자 계정이 아니다.
브라우저는 headless, 카메라/음성은 명시적 합성 입력이다. 사람 페르소나/자동 역할
시험을 실제 사람이 참여한 사용자 시험이라고 부르지 않는다.

확정된 최종 서버 시험:

- `final-media-browser/media-results.json`: **25개 통과**, 양방향 영상 decode/실제 RTP,
  원음 수신, 3인 연결, 다른 방 격리, 카메라/마이크 종료, 퇴장/재입장, 청취 전용,
  브라우저 예외 0. 실제 사설 IP로 실행했다.
- `final-lan-browser/lan-results.json`: **11개 통과**, 실제 192.168.0.5에서 CA와 SAN
  검증, Host 위조/교차 Origin 차단, Secure HttpOnly cookie, WSS 참가, LAN 초대.
  브라우저 인증서 우회는 시험 context에만 적용했고 Node HTTPS가 별도로 CA를 검증했다.
- `final-verified-browser-smoke/browser-smoke.json`: **23개 통과**, 관리자/사용자/
  게스트, 비활성화·암호 변경·세션 철회, 개인 채팅, 동명이인, 초대 대상 보호,
  좁은 화면/키보드/운영 지표. 콘솔 오류 0.
- `final-invitation-verified/invitation-smoke.json`: **11개 통과**, 초대 링크/클립보드
  실패/비정상 query/게스트 범위/관리자 철회. 브라우저 예외 0.
- `final-offline-lan-inference/inference-results.json`: 최종 앱 이미지의 **실제 EXE와
  포함 Python/모델**로 **18개 통과**. 4개 언어, 영어 발화→한/일/중 자막·음성,
  한국어 발화→영어 자막·음성, 영상 동시 연결, 비공개 번역, 자동 재생 차단/복구,
  운영 backend 조회. 별도 개발 Python 대신 데이터 폴더로 검증·복사된 번들을 사용.
- 브라우저 합계 **88개**. 호스트 종료 후 자기 작업자와 도우미 정리는
  `parent-guard.json`의 실제 프로세스 수명 **3개 확인**으로 별도 기록한다.

증거 루트는 저장소 기준 `.run/media-20260922/`이다. 임시 계정/서버 TLS 개인키 등이
포함되므로 이 디렉터리 전체를 Git에 추가하지 않는다. 공개할 자료는 별도 정제한다.

## 실패를 통한 보완

1. WebRTC 한쪽 영상만 전달: answerer가 offer 이전에 중복 transceiver를 만들던
   경로를 수정. 이후 실제 양방향 frame decode와 RTP를 검증했다.
2. LAN Node 요청은 정상이나 Edge 로그인 403: HTTP/2의 authority와 기존 Host
   검사 경계가 달랐다. HTTP/2 협상을 끄고 HTTP/1.1/WSS의 엄격한 Origin 검사를
   유지했다. 검사 완화로 해결하지 않았다.
3. 모델 번역에서 간헐적 길이 한도 종료: greedy에서 권장 sampling으로 변경했으나
   첫 패키지의 두 번째 비공개 번역에서도 실패했다. 같은 문장 직접 반복 6회는
   Vulkan에서 정상이라 캐시/드라이버 원인을 단정하지 않는다. 시작 성공 후에도
   GPU 번역 요청 실패는 CPU로 한 번 복구하도록 추가하고, 부분 결과를 성공처럼
   전송하지 않는다. 계속 실패하면 원문만 정확한 수신자에게 보낸다.
4. 자동 음성 재생 차단: 대기 발화를 유지하고 사용자 재생 허용 버튼으로 복구.
   입력/출력 큐 초과도 사용자에게 표시한다. 오래된 발화를 무한 적재하지 않는다.
5. 과거 UI의 '원문만/번역 미지원' 문구와 기존 6개 운영 지표 시험을 최신 기능 및
   10개 지표에 맞춰 정정했다. 과거 실패 기록은 삭제하지 않았다.
6. 첫 Inno 컴파일의 잘못된 UTF-8 API 이름을 공식 `SaveStringsToUTF8File`로 수정했다.
7. 이전 deadline이 다음 worker를 종료할 수 없도록 요청 당시 process reference를
   캡처했다. 모델 worker는 소유 host 생존을 관찰하고 자기 프로세스 트리만 정리한다.
8. 첫 실제 Inno 설치가 종료 코드 1: `InitializeWizard` 단계에서는 `{app}`이 아직
   유효하지 않았다. 폴더 선택 이후와 `ssInstall`에서만 기본 작업 공간을 계산하도록
   수정했다. `installer-check/install.log`에 원래 실패를 보존한다. 약 4.02GB 원본
   앱은 단일 EXE 한도 안이므로 모델 재압축 대신 무압축 번들 조립으로 바꿨다.

## 성능·품질 측정

측정 PC: Radeon Pro 560X 4 GiB VRAM, 시스템 RAM 16 GiB. 8GB VRAM으로 계산하지
않았다. 다른 AMD/Intel/NVIDIA GPU의 실행 가능성은 Vulkan 경로의 확장성이지 실측
인증이 아니다. 현재 STT/TTS는 CPU이며 GPU 보정 상태는 `unverified`로 표시한다.

`stt-context-comparison.json`은 같은 네 합성 WAV의 CPU STT 설정 비교다.

| 언어 | 기본 context | context 512 | 내용 관찰 |
|---|---:|---:|---|
| 영어 | 8,728 ms | 3,441 ms | 주요 내용 동일 |
| 한국어 | 9,319 ms | 3,411 ms | 주요 내용 동일 |
| 일본어 | 9,311 ms | 3,415 ms | 주요 내용 동일, 문장 부호 차이 |
| 중국어 | 9,253 ms | 3,367 ms | 양쪽 모두 회의 단어 오류 |

`stt-chinese-turbo.json`: 포함한 large-v3-turbo Q5_0도 같은 중국어 합성 예제에
13,229 ms가 걸렸고 단어 오류가 유지됐다. 더 큰 모델이라는 이유로 기본값을 바꾸지
않았다. 합성 발음과 ASR 각각의 원인 분리는 사람의 청취/실제 발화 자료로 해야 한다.

초기 `engine/cpu/results.json`의 단일 영어→한국어 음성 전체 처리 22,428 ms,
`engine-vulkan/results/results.json`은 13,040 ms였다. 이는 설정 개선 이전의 모델
단일 요청이며 다른 시점의 시스템 부하가 통제되지 않았다. p95나 공식 비교 우승
선언이 아니다. `inference-configured-sampling` 초기 브라우저 결과는 합성 발화 시작
부터 수신 재생까지 19,040 ms. 최신 4인 통합 측정과 혼동하지 않는다.

최종 앱 이미지 4인 시험은 설치 EXE 압축 작업과 동시에 실행되어 부하가 통제되지
않았다. 첫 번역 요청에 약 157초, 이후 영어→한/일/중 음성 45,252 ms, 한국어→
다른 언어 음성 38,548 ms(발화 시작부터 수신 재생)를 기록했다. 실제 Vulkan 요청
실패 후 **CPU 복구**가 작동했으며 운영 상태는 `vulkan-translation-request-failed`,
CPU, 마지막 모델 처리 33,574 ms를 정확히 표시했다. 이는 기능/복구 통과이나
**낮은 지연의 실시간 성능 목표는 미달**이다. 고부하를 숨기거나 과거 빠른 단일
요청값으로 대체하지 않는다. 압축 종료 후 설치 실행 시험 결과를 별도 기록한다.

## 라이선스·배포·남은 승인 게이트

- Supertonic 모델은 OpenRAIL-M. MIT 예제 코드 라이선스와 혼동하지 않았고 전문을
  번들/웹 UI에 포함했다. 의료·사법 등 금지 용도 제한과 참가자 동의가 있다.
- Whisper/llama/Melo, Qwen, Python, NumPy/sherpa, JVM의 실제 배포 고지 보존.
  해시는 무결성 증거이지 코드 서명이나 모든 전이 의존성 법률 검수의 대체물이 아니다.
- 모델의 데이터상 '최고/SOTA/GitHub stars'만으로 사용 품질을 인증하지 않는다.
  12개 방향 언어쌍의 독립 정량 평가, 사투리/소음/숫자/부정/고유명사, 실제 마이크와
  스피커, 8인·장시간 동시 부하, 메모리/VRAM p95, 전원·네트워크 장애 복구가 필요하다.
- 물리적으로 다른 LAN 기기, 조직 CA 배포, 방화벽/AP 격리, clean Windows VM,
  네트워크 차단 상태 설치/실행, 사람이 조작하는 최초 GUI는 아직 승인되지 않았다.
- 인터넷 NAT 중계/SFU, Open WebUI, 녹화/기록 보관, 코드 서명은 미완료다.
- UX 후속: 더킹 슬라이더의 실제 값은 원음 0~100%지만 양끝의 원음/번역음 100%
  문구는 그 방향과 일치하지 않는다. 수치/aria 설명은 실제 원음 비율이며, 끝점 문구는
  다음 UI 보완 대상이다. 화면 공유 구현은 실제 사용자 picker/실기기 시험이 남아 있다.
- Antigravity 독립 재검토와 사용자 보고/승인 전 GitHub 작업 보류. 현재 origin은
  GitHub가 아닌 로컬 감사 저장소이므로 승인 후 최신 원격 base와 변경 범위를 다시 확인한다.

## 참고한 1차 자료

- [Qwen 공식 모델 카드](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507): sampling 설정.
- [Supertonic 모델 조건](https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE).
- [whisper.cpp 고정 CLI 소스](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/examples/cli/cli.cpp): audio context.
- [llama.cpp b10964](https://github.com/ggml-org/llama.cpp/releases/tag/b10964).
- [Inno UTF-8 파일 API](https://jrsoftware.org/ishelp/topic_isxfunc_savestringstoutf8file.htm).

## 최종 설치 검증

확정 결과: `installer-lifecycle-results.json`, `installer-verified/install.log`,
`installer-verified/uninstall.log`, `installed-offline-lan-inference/`.

- 최종 단일 EXE **4,022,375,855 bytes**, SHA-256은 위 식별 항목과 일치.
  Authenticode는 **NotSigned**. 서명됐다고 표시하지 않는다.
- 이 PC의 `.run/media-20260922/installer-verified/app`에 **설치 종료 코드 0**,
  재부팅 불필요, **HKCU 사용자별 설치**. 기존 0.3.1 AppID/설치 경로와 별개다.
- `workspace.txt`가 설치 경로 아래 `MCastTalkData`를 저장했다. 실제 설치된 EXE를
  **--data-dir 없이** 실행해 이 저장된 경로를 사용함을 검증했다. 시험 helper가
  새 관리자/참가자 fixture와 해시 검증된 모델을 미리 준비했으므로 사람이 관리자
  설정 창을 조작한 증거는 아니다.
- 설치된 EXE + 설치된 번들로 앞의 **18개 통합 시나리오를 다시 모두 통과**.
  이는 새로운 18개 기능으로 중복 합산하지 않는다. 첫 chat 약 **79초**,
  영어 음성 fanout **41,218 ms**, 한국어 역방향 **33,823 ms**, 마지막 모델 처리
  **28,995 ms**. 압축 작업은 종료된 상태였지만 일반 Windows 시스템 부하는
  통제하지 않았다. 이번에도 Vulkan 요청 실패 후 CPU 복구가 작동했다.
- 따라서 이 Radeon PC에서 **GPU 실회의 안정성과 낮은 지연의 실시간 성능은
  승인하지 않는다**. CPU 복구가 회의를 계속하게 하는 것과 충분한 성능은 다르다.
  다음 우선순위는 GPU/드라이버 및 VRAM 부하별 재현, 부분 GPU offload/다른 런타임
  비교, 작은 모델과 품질-지연 실측이다. 4인 데이터로 8인 용량을 주장하지 않는다.
- 시험 호스트/작업자 종료 후 해당 격리 설치만 제거: **제거 종료 코드 0**,
  시험 실행파일과 HKCU 등록 삭제. 데이터가 남아 상위 앱 폴더가 비어 있지 않은
  것은 의도한 보존 결과다. 시험 데이터의 계정/identity/설정 **5개 파일 SHA 동일**,
  복사된 모델도 보존됐다.
- 기존 0.3.1 실행파일·저장된 launcher 설정·사용자 관리자 계정 파일 **3개 SHA가
  제거 전후 동일**. 기존 설치를 업그레이드/제거하거나 비밀번호를 바꾸지 않았다.
- 소스 공개 준비 휴리스틱 검사: 137개 후보 파일, 비밀키/대용량 실행파일 경고 0,
  staged paths 0. 최종 문서 포함 재검사 결과도 동일하며 전문 보안 감사의 대체는 아니다.
  증거는 `commit-preparation-final/commit-audit.json`이다.

결론: **음성 통번역·영상·LAN의 기능 구현, 오프라인 단일 설치 후보와 이 PC에서의
설치/실행/제거 시험은 완료. 사용자가 요구한 실시간 성능·언어 품질·실기기 및
clean-VM 최종 검수는 미완료이므로 정식 서비스 완료/무결점 판정은 보류한다.**
