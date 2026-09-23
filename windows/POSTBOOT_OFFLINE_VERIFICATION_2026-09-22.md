# 재부팅 후 실제 인터넷 차단 Windows 검증

현재 상태: **최종 r6 실제 인터넷 차단 Windows VM 통과 — 23:18 KST**.
사용자가 요청한 실제 오프라인 가상 검증 후 소스 커밋 조건을 충족했다.
이는 합성 미디어 기반 개발 시험판의 검증이며, 정식 출시나 저지연·실기기
LAN/GPU·인간 언어 품질 승인과 다르다. 단위시험으로 VM 결과를 대신하지 않았다.

## 실제 실행한 환경과 결과

- 재부팅 후 HypervisorPresent=true, Windows Sandbox 실행 가능 확인.
- Windows Sandbox 앱 0.8.107.0, 게스트 Windows build 26100.9444,
  RAM 8 GiB, vGPU/네트워크/마이크/카메라/클립보드 비활성화.
- 설치 파일과 시험 도구는 읽기 전용 공유, 결과 폴더만 쓰기 허용.
  실제 사용자 설치·관리자 계정·작업 공간을 사용하지 않음.
- 대상 구 설치 후보 SHA-256:
  `e6ebecf9c8c0ef12f60ad26cfba6823c826da03c9be24198e67db4dd44105ad1`.

| 실제 실행 | 확인 결과 | 판정 |
|---|---|---|
| attempt-01 | 활성 어댑터 0, 기본 경로 0, 두 공인 IP TCP 차단, 새 설치 성공, 1,782개 오프라인 파일 SHA 및 고지 HTML 검증 | 시험 도구가 jpackage의 제거된 java.exe를 가정해 중단 |
| attempt-02 | 동일한 인터넷 차단·새 설치·전체 해시 성공, 별도 fixture JVM으로 시험 계정 및 실제 번들 준비 성공 | 실제 음성인식 EXE가 0xC0000135로 종료: 제품 패키징 실패 |
| attempt-03 | 정적 실행기 DLL 검사·새 설치·1,655개 SHA 성공 | strict worker가 1.9.4-dev 표기를 거부해 미통과 |
| attempt-05 | 새 설치, 실제 4언어 CPU 엔진, 계정 UI 13개, 통역/채팅/영상 18개, 재실행·제거·작업 공간 보존, 시작/종료 인터넷 차단 | r5 전체 PASS (22:54:32–23:01:14 KST) |
| attempt-06 | r6 새 설치, 실제 4언어 CPU 엔진, 계정/화면 UI 15개, 통역/채팅/영상 18개, 재실행·제거·작업 공간 보존, 시작/종료 인터넷 차단 | 최종 r6 전체 PASS (23:11:35–23:18:35 KST) |

attempt-04는 시험 키트만 만들었으며 VM 실행 또는 통과로 계산하지 않는다.
attempt-05 화면 검수에서 참가 안내문이 좁은 열로 갈라지는 현상을 발견했다.
동일 안내문/조건을 유지하면서 레이아웃만 보완했고, 1440px·390px 브라우저
회귀검사를 추가한 r6 설치본으로 attempt-06을 다시 실행해 통과했다.
두 너비의 실제 스크린샷도 확인해 문단 분리와 체크박스 크기를 검수했다.

첫 실패는 **시험 도구 오류**였으며 설치 앱의 JVM을 변경하지 않고 별도 시험용
JVM 사본을 사용하도록 수정했다. 두 번째 실패는 **실제 제품 결함**이다.
whisper.cpp의 MSVC/OpenMP DLL 누락을 PE import 검사로 확인했고, llama.cpp도
동일 계열 외부 CRT 의존성이 있었다. 개발 PC에 존재하는 DLL은 폐쇄망 신규
Windows 설치본에 포함돼 있다는 증거가 아니었다. 브라우저·미디어 후속 단계는
이 실패 때문에 실행하지 않았으며 성공으로 계산하지 않는다.

## 보완

- 사용자에게 확인되지 않은 Visual Studio 재배포 권한을 있다고 간주하지 않음.
- 같은 소스 버전·같은 모델을 사용한 MinGW 정적 네이티브 실행기 빌드 완료.
- 정상/지연 로드 PE import 검사, 외부 CRT 및 공유 DLL 잔재 차단을 빌드에 추가.
- GCC Runtime Library Exception/MinGW-w64/상위 라이브러리 원문 고지 보존.
- 시험 엔진 stderr/stdout을 별도 파일로 보존하도록 개선.
- attempt-03의 strict 버전 검사를 완화하지 않고 `WHISPER_BUILD_IS_DEV=OFF`로
  기존 검토 버전 `1.9.4`를 유지. 빌드 시 실제 `--version` 출력까지 검증하며
  dev 표기/실행 실패를 거부하는 회귀시험을 추가했다.
- 새 실제 호스트 CPU 엔진 시험은 STT → 한·영·일·중 번역/합성 성공, 전체
  33,328ms. 이는 짧은 합성 음성 1건의 실제 측정이며 실시간 지연 목표 달성이나
  인간 언어 품질 합격을 뜻하지 않는다. 게스트 재시험으로 대체하지 않는다.
- 시험이 만든 Python bytecode 캐시를 배포에서 제외하고, 실제 파일 목록과
  manifest의 완전 일치를 검증한다. 게스트 시험은 PYTHONDONTWRITEBYTECODE=1.
- 처음 두 시험의 로그·실패 결과는 로컬 `.run/networkless-postboot-20260922/`
  아래 보존하고, 해당 시험용 VM만 종료했다. 사용자 데이터는 제거하지 않았다.

## 최종 수정 후보 r6

- `build/windows-packaging/media-041-native-r6-installer/MCastTalk-0.4.1-Offline-Preview.exe`
- 3,953,178,110 bytes (단일 EXE, 4 GiB 미만), 미서명 개발 시험판.
- SHA-256 `1ebebc357b912d1a465f48576c13e5c820f1712013cc7fea3122f565d6438a76`.
- 새 번들 1,655개 파일 SHA 목록, 라이선스 HTML 60개 구성요소/91개 원문.
- 설치 host.jar SHA-256:
  `1922aa510ee29120654c307a9a66674074614a7287c1172ed1aa68d5f7ba59d5`.
  새 installDist와 일치. 다른 의존 JAR도 동일하며, ZIP 메타데이터가 달랐던
  stream.jar 32개/translation.jar 163개 항목은 내부 파일 SHA까지 대조했다.
- Whisper CPU, llama CPU/Vulkan 모두 정적 빌드 및 PE 의존성 검사를 통과.
  LLVM/MSVC 공유 런타임의 우회 복사는 하지 않음. CPU는 x64 AVX2 프로필.
- r5 설치본 SHA `355472b858b195baeae2f2647b73268490f15ce0b18e1b5954fa7e2c889f9658`
  은 attempt-05 통과본이며 보존한다. 그 통과를 변경된 r6의 증거로 대신하지 않는다.
- 최종 r6 attempt-06은 23:11:35–23:18:35 KST에 실행했고 `passed=true`다.
  시작/종료 모두 활성 어댑터 0·기본 경로 0·두 공인 IP의 443 TCP 차단을 확인했다.
  계정/화면 검사 15개 및 다국어 회의 검사 18개가 통과했고, 브라우저 예외와
  외부 앱 리소스 요청은 0이었다. 13개 상위 수명주기 체크포인트는 이 검사를
  포함한 단계 요약이므로 별도 고유 시험 수로 더하지 않는다.
- 이번 회귀시험: packaging 77 / worker 39 / bootstrap 38 / Node 초대 10 /
  host 122 = **286 PASS**, host 실패/오류/건너뜀 0. 화면 수정 후 host 122와
  packaging 77을 다시 통과했다. 반복 실행 수를 새 고유 시험 수로 중복 합산하지 않는다.

최종 실제 VM 측정: CPU STT→4언어 번역/TTS 전체 **47,157ms**,
설치 EXE의 양방향 음성 전달 **33,174ms / 30,849ms**.
시작 준비 중 두 번의 2초 health 요청 timeout은 제한된 준비 재시도에서 발생했고,
초기 실행과 재실행 모두 이후 health 성공을 확인했다. 성공 없는 timeout을
통과로 처리하지 않는다. 설치·제거 종료 코드 0, 제거 후 시험 작업 공간의
설정/계정 파일 SHA 보존을 확인했다. 기존 사용자 설치/계정은 사용하지 않았다.

| 최종 원본 증거 (attempt-06/results 기준) | SHA-256 |
|---|---|
| acceptance.json | `9300929a4a818cf1477d107a2174b2de3bfe4554cc9e2617c4532b32d1f89174` |
| browser/account-browser.json | `8f784d93f3cbbbb96db5c1a13f6a6f2824bdc5657a70b5e614bfd0e0bbcd46ef` |
| browser/inference-results.json | `8da5e6512f7a23c711dc8ca94aa363f508dd65444802fb7f6d6612eeed48da97` |
| engine/engine-results.json | `401b47615483be689ac91136d8e3a989bbacafc13e48ade65f716ca441b1acd9` |

## 증거·한계

원본 증거는 `.run/networkless-postboot-20260922/attempt-05/results`와
`attempt-06/results`에 보존한다. `acceptance.json`은 설치본 SHA와 시작/종료
네트워크 증거를 포함한다. 브라우저 원본, 합성 음성 및 로그에는 시험 데이터가
있으므로 `.run` 전체를 Git 또는 공개 배포에 넣지 않는다.

attempt-05에서 활성 어댑터 0, 기본 경로 0, 1.1.1.1/8.8.8.8의 443 TCP 실패를
시작과 종료에 확인했다. 외부 앱 리소스 요청과 브라우저 JS 예외는 0이었다.
명시적 CPU 엔진의 STT→4언어 번역/TTS 전체 41,062ms, 설치 EXE의 브라우저
양방향 음성 전달은 33,135ms/28,290ms였다. **저지연 실시간 성능 목표 미달**이다.
브라우저는 제품의 자동 백엔드 설정을 사용해 `translationBackend=vulkan`,
`gpuCalibration=unverified`를 보고했다. 이는 선택한 실행기 경로이며, vGPU가
비활성인 VM에서 물리 GPU 가속 또는 실제 offload를 확인했다는 뜻이 아니다.

실제 LAN 단말/육성·영상 장치/인간 번역 품질/장시간·동시 부하/지연 성능/
Radeon GPU와 수동 설치 마법사 시험은 남아 있다. 설치는 silent 모드, 계정은
실제 저장소 API를 호출한 시험용 helper로 생성했다. 사람이 마법사를 끝까지
조작한 시험으로 표시하지 않는다. Internet NAT/SFU, Open WebUI, 서명 정식
배포는 완료 범위가 아니다. Antigravity 독립 검토 또는 공동 PASS도 미수신이다.

네트워크 어댑터가 전혀 없는 VM에서는 시험 브라우저에 Chromium의
`--allow-loopback-in-peer-connection`을 명시한다. 실제 WebRTC 미디어를
루프백으로 통과시키는 합성 시험이며 설치 앱이나 일반 브라우저 설정은 변경하지
않는다. 정상 LAN의 ICE 탐색/별도 장치 연결 통과로 해석하지 않는다.
공식 근거: https://chromium.googlesource.com/chromium/chromium/+/HEAD/content/public/common/content_switches.cc
