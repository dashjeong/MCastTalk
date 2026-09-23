# GitHub 커밋 준비 — 검증 통과 조건부 승인

## 2026-09-23 r8 게시 승인 및 최신 범위

사용자가 최종 시험을 마친 뒤 GitHub에 커밋하도록 명시적으로 승인했다.
이하의 이전 게시 승인 대기 문구는
당시 기록이다. 최신 기능/오프라인 판정은
`BILINGUAL_ADMISSION_VERIFICATION_2026-09-23.md`를 따른다.

- 대상: `dashjeong/MCastTalk`, 전용 브랜치 `codex/windows-offline-r8-20260923`.
- GitHub main `7e5d915207521ff6b561549ec0ed707f7166e8cc`의 모바일 변경은 보존한다.
  검증한 Windows 기준 `9f6b8611af614f58b0cbfc7f5291ad4e4ea86182`에서 분기하며,
  main 강제 갱신/자동 병합/원격 설정 변경을 하지 않는다.
- Git 명령줄 인증은 없으므로 연결된 GitHub 도구를 사용한다. 최종 GitHub tree를
  로컬 커밋 tree와 비교해 내용 일치를 확인한다. 두 경로의 작성자/부모 이력이 달라
  커밋 SHA는 다를 수 있지만 파일 내용은 같아야 한다.
- 공개 범위는 Windows 소스·시험·문서·패키징 및 필요한 Gradle/ignore 설정이다.
  실행 EXE/모델/실계정/키/로그/`.run`/`.tools`는 올리지 않는다. 새 브랜치는
  개발 시험판 소스 보존이며 5초 성능·실물 LAN·언어 품질·정식 출시 승인이 아니다.

## 최신 요청: 인터넷 차단 Windows 검증 후 커밋

사용자는 이후 “인터넷 차단 윈도우 시험 … 완료 후에 커밋”, “통과결과 가상 검증 후에
커밋”을 요청했다. 따라서 소스 커밋은 **요구한 검증 통과를 조건으로 승인**된 상태다.
이전의 일괄 승인 대기 설명은 당시 기록이다. 자동·모의 시험으로 실제 Windows
Sandbox 통과를 대신했다고 표시하지 않는다. 상세 결과와 남은 조건은
`VIRTUAL_VERIFICATION_2026-09-22.md`에서 관리한다.

재부팅 후 실제 VM에서 구 설치본의 VC++/OpenMP DLL 누락을 발견했다. 정적
MinGW 실행기로 보완한 최종 r6가 attempt-06에서 23:18 KST에 통과했다.
실제 새 설치·4언어 모델·계정/화면 15개·회의 18개·재실행·제거/보존을 확인해
사용자의 오프라인 검증 후 **소스 커밋 조건이 충족됐다**. 자동 회귀시험은
286 PASS다. 성능 목표, 실제 LAN/GPU/육성, 수동 설정 마법사와 정식 출시는 별도다.
최신 판정: `POSTBOOT_OFFLINE_VERIFICATION_2026-09-22.md`.

현재 실행 범위는 검증한 Windows 소스의 로컬 커밋이다. GitHub에는 새 모바일
변경이 있으므로 main을 무단 덮어쓰거나 force push하지 않는다. 별도 검토 브랜치
게시 또는 로컬 커밋 유지 선택은 사용자에게 물었다. 응답 전 GitHub 게시나 PR은
진행하지 않는다. 로컬 origin은 유지한다. 설치 EXE/모델/실데이터/로그는 제외한다.

커밋 메시지: `feat(windows): add verified offline meeting preview and static native runtimes`.
커밋 직전 명시적 파일 목록·staged diff·비밀정보/바이너리 제외를 다시 확인한다.
Antigravity 독립 PASS는 미수신이며 공유 작업 큐에 실제 증거와 후속 검토를 인계했다.

최종 staging 점검: 196개 파일, 실행 파일/모델/실계정/개인키/로그 제외, 휴리스틱
비밀정보 경고 0. `git diff --cached --check`는 보존한 Netty 라이선스 원문의
공백/EOF, 기존 검토 문서의 Markdown 두 칸 줄바꿈 2곳, 검증한 HTML의 빈 줄
공백 1곳을 보고했다. 원문 및 시험한 패키지와의 동일성을 위해 그대로 보존한다.
위 명시적 위치 이외의 공백 오류는 허용하지 않는다. 전체 검사가 무경고였다고
표시하지 않는다. Git 전역 신뢰/작성자 설정은 변경하지 않는다.

읽기 전용 GitHub 확인: main은 `7e5d915207521ff6b561549ec0ed707f7166e8cc`,
로컬 source HEAD `9f6b8611af614f58b0cbfc7f5291ad4e4ea86182`보다 모바일 커밋 2개가 앞선다.
원격 windows 변경은 없지만 .gitignore/Gradle 검증 메타데이터가 겹친다.
원격 main과 로컬 Windows 변경을 무단 덮어쓰지 않는다.
source의 origin은 여전히 로컬 감사 저장소다. 원격 변경이나 강제 push는 하지 않았다.

## 이하: 실제 오프라인 VM 검증 전의 역사적 준비 기록

아래의 미완료/커밋 보류 문장은 당시 상태이며 위 최신 판정이 우선한다.

### 0.4.1 미디어·LAN 후속 검토

현재 검토의 최신 기준은 `MEDIA_LAN_VERIFICATION_2026-09-22.md`이다. 실제 로컬
STT/번역/TTS, 인증 WebRTC, 사설 LAN HTTPS, 단일 오프라인 설치 후보가 추가됐다.
이하 168+58 및 미디어 미연결 설명은 **0.4.0 인계 당시의 역사적 기록**이다.
최신 계약/단위 시험은 213개, 서로 다른 브라우저 검증은 88개이며, 실제 설치 실행
재시험/제거 결과와 알려진 지연·언어 품질 한계는 최신 보고서에 분리 기록한다.

추가 포함 후보: Netty/TLS 의존성의 `gradle/verification-metadata.xml`, 미디어/
추론/인증서 코드, worker/패키징/새 시험, 모델 조건 전문 및 검증 문서.
추가 제외: 4.02GB 설치 EXE, 모델/런타임, 실제 시험 계정/CA 개인키/로그/전체 `.run`.
Git ignore로 해당 바이너리/데이터가 제외되는 것을 확인했다. 단일 EXE는 Git 소스
커밋 대상이 아니며 배포 경로와 서명·라이선스·UAT 게이트는 별도 승인한다.

새 제안 메시지: `feat(windows): integrate offline interpretation and authenticated LAN media`
본문에 개발 후보, CPU/Vulkan 복구, 성능 목표 미달과 실제 기기/clean-VM 검수 미완료를
명시한다. Antigravity의 이번 미디어 변경 독립 PASS는 아직 수신하지 않았다.
**실제 격리 Windows 검증 조건이 충족되기 전에는 git add/commit/push를 수행하지 않는다.**

## 0.4.0 인계 당시 기록

검토 결과: `REVIEW_2026-09-22.md`. 현재 작업은 **소스 검토와 보완**이며 정식 미디어 제품 출시가 아니다.
사용자의 결과 보고 후 승인 조건에 따라 git add/commit/push/PR/remote 변경을 실행하지 않았다.

## 현재 상태

- 저장소: 공유 작업 폴더의 `source/`. `main`, HEAD `9f6b8611af614f58b0cbfc7f5291ad4e4ea86182`.
- 기존 변경 `.gitignore`(Windows 도구/시험 데이터 제외), `settings.gradle.kts`(Windows host 모듈 포함).
- Windows 트리는 아직 전부 untracked다. 수정 몇 파일만 커밋하는 것으로 최초 Windows 구현이 모두 포함되지는 않는다.
- `origin`은 로컬 임시 감사 저장소를 가리킨다. `dashjeong/MCastTalk` GitHub 원격이 아니다.
- 시험: 자동 168 + 브라우저 58 성공. 수정 전 보안 반례 3건도 최종 거부 확인.
- `commit-audit.cjs`의 휴리스틱 비밀정보/대용량·실행 파일 검사는 문제를 찾지 못했다. 전문 침투시험/완전한 비밀정보 탐지를 의미하지 않는다.

## 제안 커밋 범위

포함 후보: `.gitignore`, `settings.gradle.kts`, `windows/`의 소스·계약·개발 문서·시험·패키징 스크립트.
모바일 기존 tracked 변경, 다른 `latest/` 작업, 사용자의 실데이터, `.tools/`, `.run/`, `build/`,
EXE/JAR/모델 weight·시험 계정·로그·개인 스크린샷은 포함하지 않는다.
증거 원본은 로컬에 보존하고 공개 저장소에는 비식별 검토 요약만 포함한다.

제안 메시지: `feat(windows): add authenticated meeting preview and verified safety gates`
부제/본문에 실제 음성 통번역·영상·외부 네트워크가 미완료인 개발 checkpoint임을 명시한다.
프로덕션 release/tag 또는 새 설치본 완성을 의미하는 이름은 사용하지 않는다.

## 승인 후 실행 순서 (아직 수행하지 않음)

1. 사용자 승인 범위가 소스 커밋인지, GitHub push/PR까지인지 확인한다.
2. 대상 `dashjeong/MCastTalk`, 인증 권한, 최신 기본 브랜치와 공동 작업 변경을 읽기 전용으로 확인한다.
3. 현재 로컬 origin을 무단 덮어쓰지 않고 원격/작업 브랜치를 정한다. main에 강제 push하지 않는다.
4. 검토 이후 파일 hash 변경을 대조한다. 변경됐다면 관련 시험을 다시 실행한다.
5. 명시적 파일 목록으로만 staging하고 staged diff/secret/바이너리 제외를 재검토한다.
6. 승인된 범위에서 커밋 후 push/PR을 수행한다. PR을 만들었다면 현재 작업에 연결하고 결과를 보고한다.
7. 모델·LAN·미디어·설치본 출시 게이트와 독립 최종 리뷰는 소스 커밋 승인과 별개로 유지한다.

Antigravity가 작성한 안전 보완은 유지/통합했다. 최종 통합 파일의 독립 재검토와 최종 아티팩트 합의는 공유 작업 큐에서 요청 중이다.
