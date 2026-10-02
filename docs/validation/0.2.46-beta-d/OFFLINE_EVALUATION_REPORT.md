# 오프라인 의미 평가와 공통 출력 계약 검수 보고

이 후보는 공개 릴리스 합격품이 아니다. 실시간 10초 실패 증거는 보존했고, 의미·지연·실기기 게이트가 남아 있다. 아래는 협업자의 최종 검수 인계 기록이다. 인계 당시 HEAD `0fb9730f`의 CI run 37037194807은 성공했지만 이 후속 변경을 포함하지 않았으며, 협업자는 커밋·푸시·메인 병합·릴리스를 수행하지 않았다. Codex의 최종 독립 검수 내용은 [TEST_REPORT](../../TEST_REPORT.md)의 2026-10-03 후속 항목을 참고한다. 검토용 소스 커밋과 CI는 APK 공개 배포 승인이 아니다.

## 변경과 시간 계약

- `GemmaTranslationProvider.offlineEvaluationEngineFor`만 명시적 오프라인 60초 경로를 제공한다. 외부 예산은 모델 선택 및 생성 lane 대기를 포함하고 기존 private translate/Binder/취소/정리를 재사용한다. `engineFor`의 10,000ms, native `GemmaWorkerDeadline`의 120,000ms, 원문 600자 제한은 유지했다.
- 가상시간 회귀는 입장 대기, 대기+추론 합산, 완료, 외부 취소/정리, 대기 시간 초과가 앞선 lane 소유 요청을 취소하지 않음을 확인한다. coroutine-test는 테스트 의존성만 추가했다.
- `validateAndRepairGemmaTranslation`을 모든 Gemma 모델에 공통 적용한다. 기존 복사 원문 및 좁은 목표 문자권 검증 뒤 기존 TextFidelityGuard의 유일 통화·단일 명시적 오기 보정만 사용한다. E2B/E4B 프롬프트, TRAIN, 고정 시험 자료, 사용자 원문·문맥·학습자료·승인된 정확 번역은 변경하지 않았다. 다중 통화/인용/행위자는 추측 보정하지 않는다.
- 첫 비어 있지 않은 Content.Text의 CAS 성공 시 즉시 시간만 기록한다. model/backend/maxOutputToken/responseFormat도 숫자·열거값만 기록한다. 원문, 부분 번역, 세션 기억, 키 및 raw logcat은 기록·전송하지 않았다.
- 설치된 LiteRT-LM 0.16.1 공개 API의 `enableResponseFormat`, `ResponseFormat.json`, `sendMessageAsync(...responseFormat)`으로 **E4B 오프라인 전용 선택 플래그**를 추가했다. 기본값/실시간은 OFF, E2B ON은 native 제출 전 거부한다. schema에는 `{translation: string}` 구조만 있고 정답·원문은 없다. native onDone 전 close/queue release는 변경하지 않았다. API 및 SDK 기본값 OFF를 로컬 AAR javap로 확인했다. 생산 SDK/의존성 버전은 바꾸지 않았다.

## 실제 실행 결과

모든 행은 에뮬레이터 native 엔진, 고정 작성 문장, 60초 요청 예산, 600자 선택 참고 예산이다. E2B와 E4B는 프롬프트/보정이 다른 현재 앱 경로 비교이며 가중치만의 비교가 아니다. 모델 준비 시간은 요청 예산과 별도다. 첫 실패 시 중단하며 미시도 분모를 보존했다.

| 단계/모델 | 계획 | 시도 | 완료 | 실패 | 미시도 | 실제 관측 |
|---|---:|---:|---:|---:|---:|---|
| 최초 E4B recovery |48|1|0|1|47|RC01 en OFF 60,046ms TIMEOUT; 준비17,083ms|
| 최초 E4B MC 선택 회귀 |18|1|0|1|17|MC01 en OFF 60,094ms TIMEOUT; 준비15,507ms|
| 공통 보정 전 E2B recovery |48|48|48|0|0|JUnit120.613s; 요청 p50 2101/p95 2587ms|
| 공통 보정 전 E2B MC |18|18|18|0|0|JUnit56.941s; 요청 p50 2075/p95 2766ms|
| 최소 숫자 진단 E4B recovery |48|1|0|1|47|RC01 en OFF 60,057ms TIMEOUT; 첫 Content.Text 6,465ms|
| 공통 보정 후 E2B recovery |48|33|32|1|15|RC01 zh OFF 1479ms `GEMMA_TARGET_SCRIPT_MISMATCH`; JUnit FAIL76.154s|
| 공통 보정 후 E2B MC |18|18|18|0|0|JUnit52.057s; 실제 출력 변화는 정확3행|
| 최종 APK E2B MC 재확인 |18|18|18|0|0|JUnit47.699s; 입력/문맥/style/출력 18건 모두 이전 수정 후 결과와 동일|
| 최종 APK E4B JSON ON |1|1|0|1|0|RC01 en OFF 60,051ms TIMEOUT; 준비16,815ms; 첫 Text7,240ms|
| 최종 APK E4B JSON OFF |1|1|1|0|0|RC01 en OFF 43,333ms 완료; 준비12,351ms; 첫 Text5,532ms|

최종 JSON 비교는 같은 source/context/style/model, 도메인 OFF, maxOutputToken130, CPU, 같은 APK이며 ON→OFF 각1회다. 준비 시간도 달라 속도 차이의 인과 판정이나 형식 제약의 효율 향상을 주장하지 않는다. JSON ON은 합격하지 않았으므로 기본 OFF를 유지한다. OFF 실제 출력은 “The team leader told me to revise the proposal; he didn't say he would revise it himself.”이며 역할·부정은 보존, 성별 `he` 추론은 잠정 MINOR다. 이 1건을 전체 48건 합격으로 확대하지 않는다. 모든 정상 완료/실패 JSON의 cleanupCompleted는 true다.

## 의미 검토와 남은 오류

기능 완료는 의미 품질 합격이 아니다. AI 잠정 검토는 인간 정답/청취 검증과 다르며, 독립 담당자의 판정과 평균 합산하지 않는다. 개선 전 recovery48은 26 PASS/10 MINOR/12 MAJOR, MC18은 11/3/4, 공통 보정 후 MC18은14/3/1로 검토했다. 원본 및 행별 실제 출력을 첨부했다.

실제 변경은 MC01 ja OFF의 円→ウォン, MC05 ja/zh OFF의 명시적 단일 오기 ‘품질 하략’ 원문 인용 복원 3행이다. 수정 후 recovery 완료32건 출력은 이전 출력과 모두 동일하고, 중국어 요청의 영어 출력은 오류로 격리됐다. 기존 설정의 fallback이 처리할 오류 경로이며 **실제 MLKit fallback 음성을 실행·합격 검증한 것은 아니다**.

미해결: RC04 다중 통화의 일본어 오류/혼합 문자, RC05 다중 인용의 관계 반전 및 원문 보존 실패, MC09 zh ON의 부정→반문 변경, RC03 중국어 직급/행위자 구별 붕괴가 남는다. 정답 주입이나 원문 변경으로 덮지 않았다. RC06 일본어 励まされる는 존경형 해석도 가능하므로 확정 피동 역할 반전으로 단정하지 않는다. 직급 대응에는 조직별 용어 합의와 새 holdout 검증이 필요하다.

## 단계 진단의 한계

RC01에서 첫 Content.Text는 관측됐으므로 첫 텍스트 이전에만 멈춘 상황은 아니다. 이것은 순수 prefill 시간이나 첫 음성 시간이 아니다. 실제 토큰 수, 후속 생성 지속 여부, callback/IPC/종료 및 취소 지연 원인은 아직 미측정이다. 60초 실패 후 ActivityManager의 소유 worker kill 보고와 native 120초 보호 발동은 구분한다. 현재 증거로 driver bug나 CPU 절대 사용 불가를 주장하지 않는다. 최종 OFF 1건은 native terminal까지 완료했다.

## 정확 산출물과 게이트

- 최종 제품 SHA256 `2e699db1554c456aa13e0fa861b7e3097cddd2898afa7814c56ab4b406bb6e48`
- 시험 APK SHA256 `5af68436fab006a8c15e5e7469feed25d151ee4850ca80846ed21694644bb9cc`
- 기존 release certificate/v3 및 zipalign16KiB PASS. 소유 emulator-only 설치 때 reserve null→20MiB→null, restorationMatches=true. 사용자/모델 데이터 삭제 및 물리폰 설정 변경 없음.
- 최종 게이트 BUILD SUCCESSFUL3m51: Gemma163/core translation292/app Alpha589 실패0; lintAlpha/packaged licenses PASS. 정확 APK 기본12개(도메인 저장소·원음 웹 PCM·방송 제어) PASS13.481s. 이는 에뮬레이터 loopback/합성 시험으로 실제 무선 환경·사용자 청취와 다르다.
- 최초 MC 시험은 TEST JSON 누락으로 추론 전 setupFAIL; 기존 frozen JSON만 복사해 재시험했다. 기본 회귀에서 전체32개 클래스를 잘못 선택한 실행은 중단 및 실패 기록을 보존하고, 기존 정확12개 선택을 다시 검증했다. 해당 중단 실행은 PASS로 계산하지 않는다.

APK는 로컬 `build/evidence/domain-learning-20261002/`에 있다. 검토용 JSON은 [offline-evaluation/MANIFEST.json](offline-evaluation/MANIFEST.json)에 파일별 SHA와 함께 보관했다. 원문 없는 숫자 진단과 작성된 고정 합성 시험 출력만 포함한다. 본 후보에 대한 새 물리폰/발열/PSS/마이크ASR/TTS 지연/무선 방송 실측은 수행하지 않았다. 이전 S23+ PCM 결과와 APK·환경이 다르며 동일시하지 않는다.

## 검수 이후 우선순위

1. 이 공통 출력 계약/오프라인 평가 후보를 담당자가 검수하고 명시적 파일 stage와 draft PR CI로 검증한다. JSON ON은 기본 OFF 유지한다.
2. 중대 의미 오류를 언어·역할·인용·통화별 새 holdout과 조직 용어 합의로 재검증한다. 모델 출력보다 사용자 승인된 정확 번역을 존중하며, 다중 자산을 추측 치환하지 않는다.
3. 같은 정확 APK를 실제 휴대폰에서 의미·실시간 지연·발열·PSS·실제 첫 소리·중단/복구·무선 방송까지 검증한다. 현재 실시간 실패와 미완료 분모를 보존한다.
4. 이 게이트를 충족하기 전 공개 릴리스/메인 병합/완성품 배포로 선언하지 않는다. Antigravity UI 승인도 Mac 잠금 해소 전 완료했다고 표현하지 않는다.
