# 사용자·운영자 관점 벤치마킹 및 개선

검토 ID: `CODEX-USER-OPERATOR-20260919-V1` / 공식 자료 조회일: 2026-09-19.
AI가 사용자·운영자·품질 검수 관점으로 검토한 결과다. 실제 사람의 UAT, 원어민
평가, Antigravity의 동의 또는 최종 모델 실측을 대신하지 않는다.

## 결론

기존 Whisper/MADLAD/Melo만으로 최고 조합을 확정하지 않는다. 최신 도전자까지
비교하되 **언어 품질 → 오프라인/재배포 적합성 → 같은 PC의 지연·메모리·지속부하**를
통과한 조합만 채택한다. GitHub 스타는 생태계 참고 지표이고 선정 점수가 아니다.
현재 노트북은 앞선 장치 탐지 기준 Radeon Pro 560X의 device-local VRAM 4GiB다.
8GiB 전용 VRAM을 가정하지 않고 CPU fallback을 유지한다.

## 범용 제품에서 가져올 원칙

| 공식 비교 대상 | 관찰과 적용 결정 |
|---|---|
| [Jitsi Meet 설정](https://jitsi.github.io/handbook/docs/dev-guide/dev-guide-configuration/), [참가자 개인 채팅 명령](https://jitsi.github.io/handbook/docs/dev-guide/dev-guide-iframe-commands/) | 참가 전 설정·참가자 중심 액션·전체/비공개 채팅이라는 익숙한 흐름을 적용. 이번에 참가자 검색과 목록에서 바로 개인 채팅을 구현. 소스나 브랜드 복제는 하지 않음. |
| [Open WebUI 권한 체계](https://docs.openwebui.com/features/authentication-access/rbac/) | 일반 이용 화면과 운영 권한 분리, 화면 숨김뿐 아니라 서버 권한 검사. 이번 운영 API도 ADMIN만 허용. Open WebUI의 계정/그룹과 회의실 권한을 같은 것으로 간주하지 않음. |
| [LiveKit self-hosting](https://docs.livekit.io/transport/self-hosting/), [배포 요구](https://docs.livekit.io/transport/self-hosting/deployment/) | 검증된 SFU를 다음 미디어 단계 후보로 유지. 원본 미디어와 번역 작업을 분리하고 TLS/TURN·방별 토큰·대역폭 시험을 선행. Windows native 오프라인 번들 성공을 주장하지 않음. |
| [Open WebUI LICENSE](https://github.com/open-webui/open-webui/blob/main/LICENSE) | 현행 라이선스에 branding 조건/예외가 있음. 단순 MIT로 표기하거나 로고를 임의 교체해 번들하지 않음. 정확한 배포 버전·NOTICE·사용자 범위 검토 후 독립 컴포넌트로 연동. 현재 UI를 Open WebUI라고 부르지 않음. |

### 인기·활동 참고값

GitHub 공개 REST `/repos/{owner}/{repo}`를 직접 조회했다. 2026-09-19 19:40 KST
스냅샷이며 자세한 metadata는 `RESEARCH_SNAPSHOT_2026-09-19.json`에 있다.

| 저장소 | stars | 참고할 역할 |
|---|---:|---|
| [Open WebUI](https://github.com/open-webui/open-webui) | 152,525 | 로컬 AI 관리/대화 UI·권한 구성 |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | 128,787 | 이기종 로컬 추론 runtime 후보 |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | 53,773 | Windows CPU/Vulkan STT 기준 경로 |
| [Jitsi Meet](https://github.com/jitsi/jitsi-meet) | 29,941 | 성숙한 회의 UX·미디어 구조 |
| [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | 25,468 | 동일 Whisper 계열의 runtime 비교 |
| [LiveKit](https://github.com/livekit/livekit) | 20,995 | self-host SFU 후보 |
| [Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS) | 13,457 | 최신 다국어 TTS 비교 |
| [MeloTTS](https://github.com/myshell-ai/MeloTTS) | 7,643 | 4언어 TTS baseline |
| [Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR) | 3,569 | 최신 다국어 STT 비교 |

stars·최근 push·Apache/MIT 표기만으로 보안 유지보수나 모든 weight 재배포 권리를
보장하지 않는다. 예를 들어 Qwen/Melo의 최근 push 시각은 ggml과 다르므로
릴리스·이슈 대응·의존성 취약점을 별도로 검토해야 한다.

## 모델 비교 계획 보완

| 목적 | 후보 | 이번 판단 |
|---|---|---|
| 호환성 STT | whisper.cpp + Whisper small / large-v3-turbo / large-v3, faster-whisper 비교 | 기존 tiny 1건 smoke는 품질 모델 선택이 아님. 같은 weight·decode·언어별 사례로 CPU/Vulkan 및 가능한 CUDA 비교. [공식 지원](https://github.com/ggml-org/whisper.cpp) |
| 품질 STT | Qwen3-ASR 0.6B/1.7B | ko/en/ja/zh 지원. 공급자 SOTA·배치 throughput 주장을 회의 1발화 p95로 대체하지 않음. 현재 streaming vLLM 경로와 Windows 호환성은 별도 검증. [공식 프로젝트](https://github.com/QwenLM/Qwen3-ASR) |
| 품질 STT 도전자 | Cohere Transcribe 2B | 공식 14언어 목록에 4언어가 포함되고 Apache-2.0 표기. 모델 접근 시 연락처 공유 동의를 요구하므로 자동 동의/다운로드하지 않음. [모델 카드](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026) |
| 번역 | MADLAD-400 3B baseline + TranslateGemma 4B 우선, 12B/27B 상위 PC 후보 | TranslateGemma는 전용 template과 Gemma 조건 검토 필요. 게시된 WMT 평균을 CJK 모든 방향 성능으로 확대하지 않음. [모델 카드](https://huggingface.co/google/translategemma-4b-it) |
| TTS | Melo baseline + Qwen3-TTS 0.6B/1.7B CustomVoice | 정해진 음색만 비교. 각 언어의 발음·자연스러움과 첫 소리 지연을 평가. 사용자 음성 복제는 기본 제외. CUDA 예제가 Radeon/Vulkan 지원 증거가 아님. [공식 프로젝트](https://github.com/QwenLM/Qwen3-TTS) |

NLLB/F5 공식 비상업 weight의 제품 번들 차단은 유지한다. 새 후보의 라이선스
문턱을 완화하지 않으며, 권리 검토 전에는 모델을 설치·선택 완료 상태로 표시하지 않는다.

### 재현 가능한 실측 명세 (앞으로 수행할 시험)

- STT: 언어당 최소 100개 검토된 발화로 시작. 조용함/잡음/겹침/숫자/고유명사/
  코드 스위칭을 별도 slice로 기록. 무음 환각, CER/WER, partial 수정 횟수,
  final p50/p95, CPU/GPU 자원을 측정. 표본 수는 계획이며 아직 확보·수행한 수치 아님.
- NMT: ko/en/ja/zh의 **12방향** 각각 최소 100문장. 용어·수치·부정·존댓말·문맥·
  프롬프트 주입형 원문을 포함. chrF++/COMET와 사람 검수를 병행하고 의미 반전/
  누락은 별도 차단 사례로 기록. 영어 경유 번역과 원문 직접 번역을 혼동하지 않음.
- TTS: 언어당 최소 50문장, 숫자·고유명사·혼용 포함. first-audio p95, RTF,
  peak memory, 이해도 및 원어민 청취 평가. ASR 왕복 점수만으로 음질 승인하지 않음.
- 부하: 참가 연결 N, 동시 발화 S, 고유 출력 언어 L, 영상 V를 명시. 4인/2발화/4언어
  시작, 동일 하드웨어에서 warm/cold·10분 calibration·60분 서비스 부하를 구분.
  요구 품질을 만족하지 못하면 출력 지연 누적·문장 생략으로 성공 처리하지 않음.
- 증거: 기존 schema v2의 dataset/evaluator/workload/model/runtime/settings hash와
  동일 case 집합을 사용. 동시 비교 가능한 품질·지연을 갖춘 Pareto 후보에서 운영자가
  품질 우선/반응성 우선을 선택. 미실측 상태는 `unverified`이며 ‘최고’ 배지 없음.

## 이번에 구현하는 개선과 수용 기준

1. 동명이인: 표시 이름 + @계정 + 접속 식별 표시, 참가자 검색, 비공개 채팅 바로가기.
   기존 presence 기반 ACL 유지. draft 대상 변경 확인/취소, stale 연결 재선택 시험.
2. 운영 상태: 관리자 API에서 활성 방/연결 수·JVM heap·프로세서·작업 공간 가용 용량을
   실제 조회. 통번역/미디어/GPU 검증 미완료를 명시. 익명/USER/GUEST 차단,
   비공개 본문·참가자/방 식별자·파일 경로 미노출, 세션 idle 연장 금지.
3. 기능 capability 선언을 서버 한곳에서 공유해 공개 capability와 관리자 상태의
   불일치를 방지. 관측 값이 없는 디스크는 0이 아니라 ‘확인 불가’로 표시.
4. 실제 Edge에서 UI→API→원래 방/계정 상태→응답과 390px 화면을 확인하고,
   단일 설치 EXE는 검증된 host로 다시 생성. 생성과 실제 설치 시험은 구분.

## 다음 단계 우선순위

P0: 방 소유권/입장권·대기실·강퇴/종료 정책, TLS 및 인증 연동; 그 뒤 LAN 공개.
P1: 원본 음성·장치 테스트·SFU 연결; 독립 local worker를 host에 연결하고 위 후보 실측.
P1: 모델 팩 권리/hash/오프라인 의존성 확보, 장애·메모리 부족·백업/복구 운영 시험.
P2: 회의 내 사용자 권한 그룹, 자막 revision·개인별 TTS queue·원음/번역 믹싱,
모바일 참가자 UX, 명시 동의 기반 녹음/보관 정책.

Antigravity에는 이 문서와 실제 시험 증거의 독립 재현을 요청한다. 응답 전 공동 PASS나
무결점 소프트웨어로 표기하지 않는다.
