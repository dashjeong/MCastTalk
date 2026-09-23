# 0.2.43 보수 작업 · 원인과 재검증 기록

2026-09-24. **최신 검증 대상은 round10 수정 네이티브 보수 APK다. 공개 재배포는 품질 게이트 미충족으로 보류한다.** 사용자의 공개·커밋 요청은 유지되며 별도 승인이 없어서 보류하는 것이 아니다. [최신 품질 보고서](QUALITY_VALIDATION_2026_09_24.md)에 실제 결과와 한계를 기록한다.

round10 실제 APK에서 네이티브·숫자·EOF 6개(37.76초), 방송·시험 버튼·파일 여정 3개(202.194초)가 통과했다. 같은 강제 배출 메모리 시험은 기준판 +19,776,000 bytes에서 수정판 −112 bytes로 바뀌었다. 이는 가속 무음 메모리 상한 시험이며 8시간 안정성 증거가 아니다. JNI 잘못된 핸들 5개 호출의 명시적 예외 및 별도 stream 재시작·정리도 확인했다. 기존 기준판은 2개 오류 호출만 실행했으므로 전부 같은 전후 표본으로 계산하지 않는다.

최종 빌드는 1분 52초에 성공했다. 보존된 단위시험 XML 1,787건은 실패·오류·건너뜀 0이며 마지막 명령에서 app Debug 513+Alpha 513=1,026건을 실제 다시 실행했다. 나머지 761건은 `UP-TO-DATE`의 유효한 이전 결과다. 초기 의존성 해시 검증 실패와 라이선스 시험 2건 실패는 수정 전 기록으로 남겼다.

본체 SHA-256 `fba37b600fc0519e07a7289bf0a0b9a63c05b5e7d9f51f05d52502eb09885610`, round10 시험 APK SHA-256 `9d981e3bbcd397e28da45c8e8962ae01b35e28976736a84f897845dffa4de9b3`. 본체는 96,309,312 bytes이며 서명·16KB 정렬·실제 APK에 포함된 네이티브 라이브러리 3개 해시 검사를 통과했다. 지정 영상의 최종 재시험 2건은 번역 요청 34/34 완료지만 선별 결함 5건 중 완전 해결은 0건이다. Antigravity 정적·해시 교차검토에서 나온 시험 보강을 반영해 같은 본체에서 round12 강화 시험 5개가 통과했다. 설정이 누락된 round11 실패와 수정 후 결과를 최신 품질 보고서에 함께 기록한다. 마이크 전체 여정은 새 APK에서 재시험하지 않아 이전 실패·미입증 상태를 유지한다.

공개 제품 소스와 다운로드는 **0.2.42**로 복구했고 0.2.43 공개 릴리스와 태그는 철회했다.
보수 브랜치 `codex/maintenance-0.2.43`의 변경을 공개 `main`에 병합하거나 APK로 재배포하지 않는다.
0.2.42 복구는 혼선을 줄이기 위한 철회 조치이며, 0.2.42의 음성 인식·의미 구간화 품질이
검증됐다는 뜻이 아니다. 새로 보고된 문장 중간 분할 문제도 보수 범위에 포함한다.

[최초 철회·재검토 기록](MAINTENANCE_0_2_43.md)과 [기존 시험 기록](TEST_REPORT.md)은
당시의 관찰로 보존한다. 아래 중간 빌드의 성공을 현재 수정본 전체의 성공으로 합산하지 않는다.

## 이전 round8 판정 — 당시 산출물의 기록

| 검증 범위 | 실제 결과 | 판정 범위 |
|---|---|---|
| round8 재빌드·단위시험 | `mcasttalk-maintenance-round8-rebuild.log` 빌드 성공. 단위시험 767회: app 507, core translation 193, core stream 28, provider 39 | 이전 실패 빌드를 지우지 않으며, 아래 실제 APK 시험과 구분 |
| 유한 입력 EOF·설치 환경·방송·DB | `FiniteSpeechInputDeviceTest` 2개, capability inventory 1개, 방송 2개, legacy DB 5개 실제 통과 | capability 1개는 7개 언어의 인식 성공이 아니라 현 환경의 가용성 기록 |
| 한국어 긴 코퍼스 30건 | 전체 입력 3,795,600ms (**63분 15.6초**), 30건 모두 PCM 크기·해시 일치. 23건 실행 완료, 7건 `NO_SEMANTIC_FINALS` 실패 | 실패 7건은 raw callback 0·pending 0. 305개 원문 단위와 305개 번역 생성은 **품질 통과가 아니다**. 합산 음원 길이는 연속 안정성 시간이 아님 |
| 사용자 지정 영상 2개 발췌 구간 | 합계 287.01초. 기준판의 미확정 꼬리 실패 1건이 수정판에서는 0건 | 선택해 비교한 의미 오류 5개는 그대로 남음. 영상 전체나 모집단 품질을 검증한 결과가 아님 |
| 직접 native 대조 `ko-12`, `ko-13` | 두 건 모두 전체 PCM 해시 일치·정상 EOF, partial/final callback 0, `NO_NATIVE_FINALS` 실패 | Galaxy 의미 조립·watchdog 없이도 무출력. 따라서 **watchdog를 원인으로 확정할 수 없다**. 모델·입력 적합성 등 원인은 추가 진단 필요 |
| JNI 반환값 직접 대조 `ko-01`, `ko-12` | 입력 JNI 성공 코드 각각 6,386회·6,332회, 전체 해시·EOF 일치. 최종 전사 29개·0개로 각각 실행 통과·실패 | 같은 모델 해시·옵션. ko12는 빈 문자열 필터 이전부터 native 행이 0개이고 검사한 반환값에 오류가 없었음. VAD/모델 단일 원인 확정 아님 |
| 증거 수집 도구 | parser와 manifest/명령 회귀 총 16개 통과 | JNI 전용 진단 인자 3개 포함. 호스트 검증이며 앱 기능 통과 건수에 더하지 않음 |
| 최종 UI 사용자 여정 | 최초 23개: 18 통과·5 실패. 실패 재시험: 3 통과·2 실패. 최종 확인: 2 통과·1 건너뜀 | 최종 확인에는 새 미지원 기기 검사 1개 포함. Gemma 부족 메모리 환경의 활성화 검사는 건너뛰었고 전체 성공으로 세지 않음 |
| 최종 가상 마이크→녹음 중 전사 여정 | **전체 실행 실패**. 5회 중 JUnit 1회 통과도 주입기 종료 시간 초과로 전체 실패. 최신 실행은 녹음 중 전사 표시 실패 | 실제 마이크 비활성화 후 공개 음원만 주입. 입력 신호가 불완전한 실행이 반복됐으며 제품 전체 여정 정상화는 입증하지 못함 |

영어·일본어·중국어·프랑스어·독일어·스페인어의 **각 30건, 총 180건 시험은 미완료**다.
설치 환경 조회에서 Android recognition service는 0개였고 en/ja/zh/es의 엔진은 사용할 수 없었다.
fr/de는 앱 원어 지원 범위에 포함되지 않아 `APP_SOURCE_UNSUPPORTED`로 기록했다.
한국어 엔진 가용성도 모델 준비·인식 정확도 증명과 구분한다. 이 현황으로 7개 언어 시험 완료나 상용 품질을 선언하지 않는다.

## 사용자 증상 → 원인 → 수정 → 완료 조건

| 사용자 경로 | 확인된 원인 또는 제한 | 보수 코드에 반영한 내용 | 현재 증거 / 남은 조건 |
|---|---|---|---|
| 홈에서 서비스 진입, 설정·시험으로 이동 | 서비스마다 공통 탐색이 달랐고 일부 홈 진입이 단독 사용·오디오 선택을 덮어썼음 | 실시간 통역·원음 방송·음성 노트·파일 변환/재생 카드와 공통 운영/시험/설정, 명시적 모드 선택, 작업 상태·돌아가기, 저장 가능한 화면 상태 | round8 기본 홈/탭·설정 보존 검사를 실행. 오래된 UI 객체와 잘못된 클릭 대상은 시험 helper를 보완한 뒤 해당 경로 재통과 |
| 원음 방송 시작/중지 | 번역 준비와 송출·입력 제어의 분산이 사용 경로를 혼란스럽게 함. 사용자 기기에서 송출 실패의 모든 원인은 아직 특정하지 않음 | 번역 모델·대상 언어 없이 원음/단독 사용 시작 가능, 입력과 방송 제어 독립 유지 | round8에서 실제 방송 UI→음성 처리/전달과 시험 버튼 반복 2개 통과. 물리 기기 송출·청취는 미검증 |
| 음성 노트 녹음 중 전사 | 철회본은 AudioRecord→WAV 저장만 수행하고 전사는 별도 후처리였음 | 기본 녹음·받아쓰기는 실제 STT 준비 후 하나의 마이크 PCM을 WAV와 인식으로 분기. 부분 전사 표시, 확정문 저장, 종료 시 잔여 결과 보존. 녹음만 선택은 별도로 명시 | round8 준비된 STT→녹음 중 저장 검사는 통과. 가상 마이크 전체 여정은 최신 실패이며 정상화 보증 없음. 세 핵심어 확인은 원문 전체 정확도 판정이 아님 |
| 음성 노트/파일 작업 중 탭 이동 | 작업 수명과 화면 수명이 일치하지 않으면 소유권을 일찍 해제하거나 다른 작업이 입력을 가져갈 수 있음 | 소유자 토큰과 종료 완료 후 해제, 진행 작업 안내·복귀, 활성 녹음 중 새 입력 시작 제한 | round8 재시험에서 녹음만→시험 탭→복귀→종료 통과. 실제 전사 마이크 경로의 안정성은 별도 미통과 |
| 파일 선택→전사→번역→보관함→재생 | 전사가 Android 플랫폼 인식 서비스에만 의존. OS 버전만으로 실제 언어 지원을 판정할 수 없음 | 지원되는 수동 원어는 앱의 준비된 로컬 STT 사용. 자동 원어/플랫폼 경로의 지원 여부는 별도 확인. 실제 디코딩 PCM을 순서대로 전달하고 종료 잔여 결과 회수 | round8 실제 파일 선택→한국어 전사→영어 번역→저장→재생 여정 통과. 지정 짧은 공개 음원 경로이며 모든 파일/언어 품질 보증 아님 |
| 이전 버전 보관함에서 원문 저장·번역 갱신 | 실제 v1 DB에는 자식 테이블 FK cascade가 없으나 v2 갱신은 부모 삭제만으로 자식 삭제를 기대함. 남은 segment 기본키가 다음 저장과 충돌 | save/delete/import의 같은 트랜잭션 안에서 해당 파일 ID의 번역·원문 행만 명시적으로 정리. 기존 다른 파일·사용자 편집과 실패 시 이전 데이터 보존 | round4 충돌 재현 후 round5 파일 여정 통과. round8에서 legacy migration 5개 재통과 |
| 번역 여부·실패 원인 표시 | 원어와 번역어가 같아 원문을 재사용해도 번역 엔진 수행으로 보일 수 있음. 실패 메시지만으로 실제 저장/변환 위치 판별이 어려움 | 같은 언어는 원문 재사용으로 표시. 단계·예외 타입·앱 코드 위치만 진단하여 원문·파일명·URI·예외 메시지는 수집하지 않음 | 관련 단위시험 포함. 사용자 진단 ZIP/원시 로그는 공개 Git과 이번 품질 보고서에서 제외 |
| 0.2.42 문장 중간 분할·의미 손상 | 무음만으로 미완성 주어·연결구까지 확정할 수 있는 경로 확인. 공급자 final과 의미 완결성도 구분해야 함 | 안정된 가설·완결 종결·앞뒤 표현을 함께 검사. 먼저 완성된 문장부터 확정하고 미완성 부분을 보존. 부정/수사/네 가지와 같은 경계 회귀 추가 | 공개 코퍼스 지정 경계 3건 개선 관찰, EOF 꼬리 회수 개선. 별도 목적표집 중대 의미 오류 10건은 남아 전체 품질 완료 아님 |

원문 인식 오류가 있으면 구간화만으로 원뜻을 복원할 수 없다. 인식 정확도, 문장 분할,
번역 의미 보존, 실제 음성 전달을 각각 확인하며, 번역이 화면에 떴다는 이유만으로 음성 전달을
성공 처리하지 않는다. 단순 시간 구간 확대나 토큰 수를 맞춘 분할을 의미 단위 판정으로 부르지 않는다.

## 실제 중간 시험 기록

환경: macOS ARM64/JDK 17, 전용 **Android 15 / API 35 AOSP ARM64 에뮬레이터**.
마이크 시험은 공개 FLEURS 한국어 음원 PCM을 에뮬레이터 가상 마이크로 입력했다.
사람의 현장 발화, 물리 Galaxy, 사람의 청취 품질 평가에 해당하지 않는다.

시험 음원은 `app/src/androidTest/assets/fixtures/fleurs-ko-1959.pcm`이며,
16 kHz/mono/signed 16-bit little-endian, 224,640 bytes, 7.02초다.
SHA-256: `b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f`.
공개 코퍼스 및 합성 데이터만 사용했으며 사용자 녹음·대화·진단 ZIP은 시험 자산이나 공개 Git에 넣지 않는다.

| 대상 | 실제 실행 결과 | 해석 한계 |
|---|---|---|
| 철회 APK + 실제 가상 마이크→녹음 화면 | `VoiceNoteMicrophoneJourneyDeviceTest` 1개 실패, 93.798초. 녹음 중 음성 전사 미표시 | 결함 재현 증거. 빌드 실패나 버튼 이름 불일치를 결함 재현으로 세지 않음 |
| round1 + 동일 가상 마이크 경로 | 같은 여정 1개 통과, 64.694초. 녹음 중 표시·종료·재열기 | 이후 전체 핵심어와 녹음 중 확정 저장 조건을 강화했으므로 최종판 재실행 필요 |
| round2 실제 캡처→한국어 인식→영어 번역→TTS→WebSocket, 실제 시험 버튼 재실행 | 2개 통과, 75.258초 | 이후 방송 시작도 실제 메뉴를 거치도록 시험 강화. 최종판 재실행 필요; 사람의 청취 검증 아님 |
| round3 핵심 여정 6개 | 실제 WAV 전사 통과. 전체 6개 중 3개 실패 | 파일 경로는 실제 DB 결함. 홈 2개는 스크롤/버튼 탐색 시험 도구 문제로 구분하고 도구 보완; 실패를 삭제하지 않음 |
| round4 파일 선택→변환 전체 여정 | 1개 실패, 31.219초. SAVE_SOURCE SQLiteConstraintException | minified APK의 mapping으로 segments INSERT 충돌까지 확인. 이후 round5 파일 여정 및 round8 DB 회귀로 별도 검증 |
| round4 홈/노트 묶음 | 홈 4개는 각 시험 종료 성공 기록 있음. 노트 STT 시험은 시작 후 완료 기록 없음 | 묶음 전체 성공으로 집계하지 않음. 중단/불완전 실행은 최종 재시험 필요 |
| round4 Alpha 단위시험/빌드 | Alpha 단위시험 495회 실패·오류·건너뜀 0. lint/Alpha APK/시험 APK/패키지 고지 검증 빌드 성공 | 이후 legacy DB, 안내 문구, 의미 구간화 변경이 있어 최종 통과 결과가 아님 |
| round5 DB/의미 검사 묶음 | 실제 legacy DB 5개 통과, 작성한 문장→ML Kit 검사 1개 실패. 전체 6개 중 1개 실패 | DB 통과와 번역 품질 경고를 분리. 묶음 전체 통과 아님 |
| round5 실제 음성/파일 여정 | `RepeatedSemanticSpeechDeviceTest`, `FileConversionUserJourneyDeviceTest` 2개 통과, 72.189초 | 짧은 공개 음원 반복 및 파일 여정의 증거이며 긴 음성 품질·8시간 안정성 증거가 아님 |
| round5 방송 UI/시험 버튼 묶음 | 방송 UI 1개 실패, 시험 버튼 반복 1개 통과 | 이미 선택된 radio의 click action이 없는 Compose 동작을 시험 helper가 오류로 판정. 선택 확인 방식으로 수정했으며 실제 방송 버튼 검증은 유지 |
| round6 작성 문장→실제 ML Kit | 서로 다른 4개 문장을 2회 처리한 8회에서 동일한 1개 문장에 경고가 두 번 발생. `qualityGatePassed=false` | “들어가면 안 됩니다”→“you should not get here.”의 어휘 anchor 검사 경고. **독립 8개 표본의 2/8 품질 실패율이 아니다.** 의미 적합성은 별도 검수 필요 |
| round6 실제 노트 전사 | `VoiceNoteLiveTranscriptionDeviceTest` 1개 통과, receipt에 양 APK 해시 확인 | 실제 로컬 STT→종료 전 문장 저장 증거. 마이크 UI 전체 여정·사람의 청취 판정과 구분 |
| round6 방송 UI 재시험 | 2개 모두 홈 진입 단계 실패. 수집 UI XML에 `System UI isn't responding` 대화상자 확인 | 에뮬레이터 System UI ANR가 화면을 가린 실행이다. 방송 기능 통과도, 앱 송출 결함 재현도 입증하지 않음. 깨끗한 환경에서 재시험 필요 |

## 긴 음성에서 실제로 드러난 종료 결함

round6 한국어 공개 음성 첫 5건의 **최초 실행은 모두 manifest 누락으로 설정 단계에서 실패**했다.
복제한 AVD의 외부 저장소에 시험 manifest/PCM이 전달되지 않은 것이며, 인식 정확도 실패로 세지 않는다.
파일을 각 기기에 배치한 다음 재실행에서는 **5건 모두 입력 PCM 전체 소비와 SHA-256 일치**를 확인했다.
그중 1건은 실행 완료, 4건은 `UNRESOLVED_PENDING_TAIL`로 실패했다. 실행 완료 1건도 품질 승인에 해당하지 않는다.

사용자가 지정한 영상 2개의 round6 기준 시험도 입력 전체 해시가 일치했다. 1건은 실행 완료,
다른 1건은 같은 미확정 꼬리 결함으로 실패했다. 두 번째 영상에는 참조 자막이 없어
`REFERENCE_UNAVAILABLE_NOT_SCORED`로 기록했으며 정답을 만들어 비교하거나 품질 점수를 부여하지 않았다.
이들은 긴 입력을 실제 처리한 결과이며, 처음 5건의 설정 실패와 구분한다.

원인은 Moonshine 클라이언트가 유한 PCM 입력 EOF 뒤에도 `awaitClose`를 계속 기다리고,
기존 worker stop은 listener를 제거한 뒤 `stopStream`을 호출하는 데 있다. 실제 SDK 0.1.5의
`stopStream`은 마지막 네이티브 전사와 callback을 동기 실행하므로 이 순서로는 마지막 결과를 잃는다.
보수 코드에는 **마지막 PCM ACK→별도 EOF 요청→listener 유지 중 native 최종 전사→자원 정리→종료 ACK**를
추가했다. 취소·오류 중지는 구분하며, 미확정 문장을 임의로 확정해 성공으로 꾸미지 않는다.
ACK 대기는 30초 상한이고 결과 수신 큐 넘침도 명시적으로 실패한다.

새 `MoonshineSttInputCompletionTest` 4개와 `FiniteSpeechInputDeviceTest` 2개는 이 순서와
실제 네이티브 유한 입력의 정상 완료를 검증하기 위해 추가했다. round7의 첫 빌드는 의미 구간화
단위시험 1개에서 실패했고, 이후 실제 EOF 시험에서는 앱 경로 1개 통과·직접 native 반복 1개 실패가 있었다.
직접 시험이 앱의 backend 사용 lease 없이 공유 서비스를 사용해 앞선 시험의 유휴 정리와 충돌할 수 있는
경로를 확인하고 시험 소유권을 보완했다. **round8에서 EOF 2개 모두 실제 통과했다.**
이 수정은 입력 종료 처리 검증이며, 위 긴 음성 7건의 무출력 및 의미 오류까지 해결했다는 뜻은 아니다.

## 중간 산출물과 증거 식별

| 산출물 | SHA-256 | 적용 범위 |
|---|---|---|
| round5 본체 APK | `32495907578d08eb0bdea92e966aa44f5063f6b96a7502235d4305a89ecb1bd9` | DB/짧은 음성/파일/초기 방송 UI 시험 |
| round6 본체 APK (96,272,357 bytes) | `a7fc258ff075f958f449910ec80b04104c2972ed31e15c217dc3b1f6cc6016f4` | 아래 두 시험 APK가 대상으로 삼은 동일 본체 |
| round6b 시험 APK | `7516232aedba16556b29bc326cd92341c686dcb4bb3d4cfaa7e416f81c15615b` | 첫 5건 공개 코퍼스, 노트, ML Kit, 방송 UI 재시험 |
| round6c 시험 APK | `f7f34a06562f89279a4f5948f058c8922a3f61b4b69b579b397aee795359de17` | 참조 자막 없는 사례 지원을 추가한 사용자 지정 영상 시험 |
| round8 본체 APK | `8fe1cb9484f45634725f9f220de0448fd54e2ae00b4625a4cd896da515bbeb34` | 최신 EOF/가용성/방송/DB/긴 코퍼스/영상/직접 native 시험의 동일 본체 |
| round8 기본 시험 APK | `d4431e48f666cb9e13abefd3c1bacc98a156215df7eec70f27bf6170799c50b1` | EOF/가용성/방송/DB 및 긴 코퍼스·영상 시험 |
| round8 직접 native 진단 추가 시험 APK | `2279eb60a12ab13126ecacaa9ac0519ef668f0e20a86e1a1e8a67a525f4b1cbf` | 직접 native 대조 2건. 본체 APK 변경 없음 |
| round8 UI helper 보완 시험 APK | `63be70a135de9df35fa0e17970d64d3ad7946719c1ca0167c385594e262f5733` | UI 실패 5개 재시험·일부 가상 마이크 시도 |
| round8 capability 보완 시험 APK | `91c6851fa1a8705c322afbd3b3108721e71e356ba98914ce17a03f2baa355d1a` | 최종 UI 2 통과·1 건너뜀 및 최신 가상 마이크 실패 |
| round8 JNI 상태 진단 시험 APK | `5ae8bd903bfc921098db17b79c93ef5a6ce5f50d1870651462227eaee6d1e535` | SDK가 버리는 JNI 반환값을 직접 관측하는 별도 시험. 본체 변경 없음 |

이 APK들은 내부 보수 비교용이다. 이전 빌드의 성공을 다른 해시의 산출물에 대입하지 않는다.
구조화 receipt는 `/private/tmp/mcasttalk-corpus-evidence/`, `mcasttalk-youtube-evidence/`,
`mcasttalk-round6-checks/` 아래에 있으며 설치된 본체/시험 APK 해시, UTC, 명령, 로그 해시와
개별 종료 상태를 포함한다. 설정 실패와 실제 실행의 receipt를 모두 보존했다.
짧은 회귀 로그는 `mcasttalk-maintenance-round5-db-meaning.log`,
`mcasttalk-maintenance-round5-real-speech-file.log`; 작성 문장 결과는
`mcasttalk-semantic-translation-round6.json`; System UI 차단 증거는
`mcasttalk-round6-ui.xml` 및 `mcasttalk-round6-systemui-anr.png`로 로컬에 보관한다.

최신 receipt는 `/private/tmp/mcasttalk-round8-finite-capability-evidence/`,
`mcasttalk-round8-broadcast-evidence/`, `mcasttalk-round8-database-evidence/`,
`mcasttalk-corpus-evidence/corrected-round8/`, `mcasttalk-youtube-evidence/corrected-round8/`,
`mcasttalk-round8-native-diagnostic-evidence/ko-12/` 및 `ko-13/`에 보관한다.
직접 native 결과와 설치 언어 가용성 결과도 로컬에 보존한다. 음성 원문·원시 transcript·키를 이 문서나 공개 Git에 복사하지 않는다.

중간 실행 로그는 작업 환경의 `/private/tmp/mcasttalk-*`에 보관되어 있고 공개 원문 로그로
추가하지 않는다. 최종 결과에는 실행 대상 소스 커밋·APK 해시·시험 명령을 묶어서 기록한다.

## 회귀시험 연결과 재실행 방법

| 회귀시험 | 검증하려는 사용자 실패 |
|---|---|
| `ServiceHomeDeviceTest` | 홈/공통 탭/모드 보존, 번역 없이 단독 사용, 활성 녹음 복귀 |
| `VoiceNoteMicrophoneJourneyDeviceTest` + `scripts/verify-emulator-microphone.py` | 실제 마이크 입력→녹음 중 전사/확정 저장→종료→노트 다시 열기 |
| `VoiceNoteLiveTranscriptionDeviceTest` | 준비된 실제 로컬 STT 결과를 녹음 종료 전에 영속 저장 |
| `BroadcastEmulatorIntegrationTest#recordedKoreanSpeechReachesEnglishListenerThroughRealPipeline` | 실제 UI 방송 시작→원음 입력→인식→번역→TTS→WebSocket 비무음 수신 |
| `BroadcastEmulatorIntegrationTest#operatorTestButtonCompletesRecordedSpeechAndCanRunAgain` | 시험 버튼으로 전체 통역 처리 후 재시험, 입력 제어 독립 |
| `PreparedFileSpeechTranscriptionDeviceTest` | 파일 디코딩→실제 로컬 인식, PCM·길이 보존 |
| `FileConversionUserJourneyDeviceTest` | Android 파일 선택→원어/번역어 지정→변환→원문/번역/해시 저장→재생 |
| `FileTranscriptLegacyMigrationDeviceTest` | FK 없는 실제 v1 DB에서 갱신·삭제/재생성·고아 행 가져오기·기존 편집 보존·트랜잭션 롤백 |
| `FiniteSpeechInputDeviceTest` | 네이티브 유한 PCM 반복 및 앱 의미 조립에서 정상 EOF·마지막 결과 보존 |
| `InstalledSpeechCapabilityDeviceTest` | 지원 언어와 현재 설치 엔진 가용성 구분. 인식 품질 시험 아님 |
| `SpeechCorpusBenchmarkDeviceTest` / `DirectNativeCorpusDeviceTest` / `NativeJniStatusCorpusDeviceTest` | 같은 공개 긴 PCM의 실제 앱 경로·직접 native 무출력·JNI 상태 대조 |

다음은 재현용 검증 명령 목록이다. round8 단위시험·재빌드 성공은 위에 별도로 집계했으며,
이 목록 전체의 완료를 뜻하지 않는다. 단위시험으로 실제 여정을 대신하지 않는다.

```sh
./gradlew testDebugUnitTest :core:stream:test :core:translation:test \
  :app:testAlphaUnitTest :app:lintAlpha :app:assembleAlpha \
  :app:assembleAlphaAndroidTest :app:verifyPackagedThirdPartyLicenseAssets \
  --offline --max-workers=4
node scripts/verify-listener-player.mjs
node scripts/verify-listener-resampling.mjs
node scripts/verify-listener-i18n.mjs
node scripts/verify-speaker-mic.mjs
node scripts/verify-public-branding.mjs
python3 scripts/test-public-snapshot.py
python3 scripts/verify-public-snapshot.py
python3 scripts/test-device-evidence.py
git diff --check
```

Android 여정은 최종 APK와 대응하는 시험 APK를 설치한 뒤 `scripts/run-device-evidence.py`에
`--apk`, `--test-apk`, `--serial`, `--class`, `--output-dir`를 지정해 기기별 직렬 실행한다.
도구는 설치된 양 APK 해시가 일치하는지 먼저 검사하고, adb 종료 코드 외에 개별 상태·JUnit 집계·
전체 종료 신호도 대조한다. 중단·누락·건너뜀을 통과로 바꾸지 않는다.
공개 코퍼스는 `--corpus-manifest`, 직접 native 대조는 `--native-corpus-manifest`로
전용 에뮬레이터의 안전한 상대 manifest 경로를 지정한다. JNI 상태 대조는 `--jni-corpus-manifest`이며 세 옵션은 상호배타다.
가상 마이크 시험에는 전용 AVD의 인증된 로컬 gRPC 설정과 위 공개 음원을
사용한다. 인증 토큰을 로그에 출력하지 않는다. 시험은 사용자 기기나 사용자 보관함이 아닌
전용 에뮬레이터와 별도 합성 DB를 사용한다.

## 보수 산출물 기록 — 공개 재배포 보류

- 소스는 이 문서와 함께 보수 브랜치에 등록하며 실행 증빙에 소스 파일별 해시와 등록 커밋을 별도 기록한다.
- 최신 검증 본체는 문서 상단의 round10 SHA-256이며 round12에서도 같은 본체를 사용했다. 아래 round8 식별값은 당시 시험의 기록이다. 최종 공개 산출물 확정은 보류.
- round8 APK는 96,288,741 bytes. 서명 및 16KB 정렬 검증 통과. 인증서 SHA-256: `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`.
- round8 설치 해시와 EOF·방송·DB 회귀 확인/통과. UI 실행별 실패·건너뜀과 최종 가상 마이크 실패는 위 표에 기록.
- 한국어 긴 코퍼스 30건 및 영상 발췌 2건: 실행·비교 완료, 무출력/의미 오류 잔존. 나머지 6언어 각 30건은 미완료.
- 라이선스 자산 빌드 검증 통과. 공개 경계와 추적 파일 검사는 등록 직전 실행 원장에 기록하며 보안 침투시험을 대체하지 않는다.
- 공개 상태: **0.2.42 유지. 0.2.43 재배포는 품질 게이트 미충족으로 보류.**

물리 Galaxy/One UI의 실제 마이크·앱 출력 캡처, Android/iPhone 브라우저 청취,
사람의 음성 자연스러움·번역 적합성 판단, S23 첫 음성 p95 2,000ms 게이트, 야외 잡음,
핫스팟 부하 및 8시간 연속 사용은 이번 중간 시험으로 입증하지 않았다. 연결 가능한 물리
기기가 없다는 조건과 사용자가 장시간 사용 중 문제를 보고하겠다는 합의를 유지한다.
사용자 장시간 확인은 코드 회귀와 실제 가능한 시험을 생략하는 근거가 아니다.
