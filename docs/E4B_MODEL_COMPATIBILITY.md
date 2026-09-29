# E4B 모델 선택과 호환성 검토

검토일: 2026-09-29. 기준 소스: 공개 `v0.2.45-beta-c` (`ac8039f`).
사용자 선택에 따라 일반 E4B-IT를 먼저 추가하고 번역 특화 GGUF는 후속 검토한다.

## 모델 구분

| 항목 | 기존 E2B 기본형 | 추가 대상 E4B-IT | 후속 검토 Translate Sub E4B |
|---|---|---|---|
| 형식 | LiteRT-LM | LiteRT-LM | GGUF Q4_K_XL |
| 파일 크기 | 2,588,147,712 bytes | 3,659,530,240 bytes | 5,126,307,008 bytes |
| 현재 앱 엔진 | LiteRT-LM 0.16.1 | API 35 ARM64 에뮬레이터 실제 추론 통과 | 직접 로드 불가; 별도 엔진 필요 |
| 기존 모델 처리 | 선택·파일 유지 | 별도 파일·캐시 | 이번 APK에 미포함 |

일반 E4B-IT는 번역 특화 모델과 다르다. 모델 크기가 크다는 이유만으로 번역 품질이나
동시통역 속도가 개선되었다고 판단하지 않는다. 모델 선택 자체도 기존 음성 입력 오류의
해결을 증명하지 않는다.

## 공급망 고정 정보

- 저장소: [litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)
- revision: `2eee7ac325f20eb8c9ac1d0e972f7c84663062da`
- 파일: `gemma-4-E4B-it.litertlm`
- SHA-256: `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`
- 라이선스: Apache-2.0. 앱 라이선스 목록과 오프라인 고지에 별도 기재한다.
- 기존 E2B 기본형·GPU형의 revision, 해시, 파일명, 선택 ID는 변경하지 않는다.

E4B 파일만 약 3.41 GiB이며 E2B와 함께 보관하면 두 기본 모델의 파일만 약 5.82 GiB이다.
추가 실행 캐시와 메모리가 필요하다. 다운로드 파일은 APK에 포함하지 않는다.

## 적용과 복구

설정의 AI 모델 목록에서 후보를 고른 뒤 내려받기·점검·적용을 실행한다. 파일 크기와
SHA-256 검증, 실제 번역 점검이 끝난 후 적용한다. 기존 모델 파일을 삭제하지 않으며
적용 실패 시 이전 모델을 유지한다. 모델별 캐시와 준비 상태를 분리한다.
서명된 외부 카탈로그가 E4B를 포함한 내장 모델의 ID나 파일명을 덮어쓰지 못하게 검사한다.

## 번역 특화 GGUF를 바로 넣을 수 없는 이유

[Translate Sub E4B GGUF](https://huggingface.co/17slever17/translate-gemma-4-sub-e4b-GGUF)는
현재 LiteRT-LM 엔진이 읽는 파일 형식과 다르다. 확장자 변경이나 해시 예외로 해결할 수 없다.
별도 GGUF 실행 엔진을 도입할 경우 Android 메모리·속도·취소·복구와 라이선스를 검토해야 한다.
해당 모델의 자막 프롬프트와 앱의 JSON 결과 계약도 별도 검증 대상이다. 현재 앱에서 이 GGUF의
번역 품질 또는 JSON 출력 실패를 실제 측정한 것은 아니다.

## 검증 상태

초기 다운로드 중단(호스트 디스크 1 GiB 저하), 이어받기 SHA 불일치 거부(E2B 유지), 저공간 네이티브
캐시 추가 실패(146 MiB 여유, SIGABRT)의 초기 실패 이력을 보존했다. 이후 사전 저장공간 가드
(`GemmaStoragePolicy`, 2,400 MiB 캐시 예산 + 128 MiB 런타임 안전 여유) 및 CPU XNNPack 완료 마커
(`cache_completion_marker.json`)를 도입하고 최종 패키지 검증을 완료했다.

전용 API 35 ARM64 에뮬레이터에서 3종의 개별 계측 시험이 모두 통과했다:
1. **저공간 사전 거부(Negative)**: 약 515 MiB 여유 상태에서 네이티브 JNI 진입 전 `IllegalStateException`
   ("저장 공간")으로 안전하게 거부하고 E2B 모델과 self-test(`Hello`)를 보존했다(4.228s, PASS).
2. **캐시 및 마커 생성(Positive Run 1)**: 캐시 공간 확보 후 E4B 초기화 및 실제 추론
   `안전하게 대피해 주시기 바랍니다. → Please evacuate safely.`(1,237ms)을 성공하고, 2,210,334,416 바이트의
   XNNPack 캐시와 완료 마커를 안전하게 기록했다(36.44s, PASS). E2B 롤백 self-test(`Hello`)도 통과했다.
3. **완료 마커 기반 캐시 재사용(Positive Run 2)**: 659 MiB 여유 공간에서 마커가 유효하게 인정되어
   캐시 재생성 없이 즉시 로드 후 추론(1,417ms) 및 E2B 복귀를 확인했다(17.468s, PASS).

최종 서명 패키지 `MCastTalk-0.2.46-beta.apk` (code 53, SHA-256
`3575b5f40ed10496d0cc1683632ed0b2cd72c2ca3ced5a21d4f2fe636491c5ce`)와 테스트 APK (SHA-256
`1576907e7b778f1004530fd2023166b59bdbd00221448116771fdbb3d02a777b`)가 기기에 덮어설치되었으며,
기기 내 설치 파일 해시가 완전히 일치했다. 단위 시험은 1,400건 전건 통과, 린트 0 Error 83 Warning이다.
최종 두 번의 성공 시험 후 수집한 crash logcat 버퍼는 비어 있었다. 앞선 충돌 이력은 별도로 보존한다.

이 검증은 전용 ARM64 에뮬레이터의 한 문장 연기 시험이며, 번역 품질 우위나 실기기 지연을 입증하지 않는다.
실기기 Galaxy S23, Samsung One UI, 브라우저 스케줄링, S23 첫 음성 p95 2초 게이트, 장시간 안정성,
다국어 번역 품질 우위는 여전히 미검증 상태로 유지된다. GPU 백엔드 캐시는 정책상 미검증/미지원이다.
최종 실행 명령·시험 결과·APK 검증은 `TEST_REPORT.md`에 기록한다.
