# 개발자 실험: 선택적 온라인 검토와 문장 사전

확인일: 2026-09-14. 새 런타임 의존성은 추가하지 않았습니다.

온라인 검토는 기본 꺼짐입니다. 개발자 정보 표시, 온라인 검토 동의, 선택한 제공자의 API 키가 모두 활성화되어야 합니다. 제공자를 변경하거나 설정을 가져오면 온라인 동의를 다시 받아야 합니다. 원문 한 문장과 현재 번역 초안만 요청 데이터로 보냅니다. 음원, 진단 로그, 이전 대화, 파일명, 세션 식별자는 보내지 않습니다. 출발·대상 언어와 선택한 문체는 고정 지침에 사용합니다.

API 키는 Android Keystore의 내보낼 수 없는 AES 키로 GCM 암호화하여 앱 전용 저장소에 보관합니다. 화면 상태에는 키 존재 여부만 담으며 설정 내보내기에 키를 포함하지 않습니다. 가져오기는 온라인 검토와 자동 저장 동의를 해제합니다.

동의를 끄면 연결 대기 후 본문 전송 직전, 응답 수신 후 사용 직전, AI 문장 저장 큐에서 실제 쓰기를 시작하기 전에 다시 확인합니다. 이미 제공자에게 보낸 요청을 회수하는 기능은 아니며, 대기 중인 검토·학습이 이전 동의를 계속 사용하는 것을 막습니다.

## 요청 계약

- OpenAI 기본 모델: `gpt-5.4-mini`. `https://api.openai.com/v1/responses`에 `store:false`와 `text.format`의 strict JSON schema를 사용합니다. `store:false`는 Responses API에서 나중에 조회하기 위한 저장을 끄는 옵션이며, 제공자의 다른 데이터 처리 정책 전체를 대체하는 약속이 아닙니다. [모델 문서](https://developers.openai.com/api/docs/models/gpt-5.4-mini), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Responses 저장 옵션](https://developers.openai.com/api/reference/cli/resources/responses/methods/create).
- Google 기본 모델: `gemini-2.5-flash-lite`. 고정된 `generativelanguage.googleapis.com`의 `v1beta/models/{model}:generateContent`를 사용합니다. 현재 공식 REST 예시의 `generationConfig.responseFormat.text.mimeType`과 `schema`로 JSON 출력을 요청하며 키는 URL에 넣지 않고 `x-goog-api-key` 헤더에 넣습니다. [현재 generateContent 구조화 출력 REST 예시](https://ai.google.dev/gemini-api/docs/generate-content/structured-output), [Generate Content API의 responseFormat 필드](https://ai.google.dev/api/generate-content), [모델 문서](https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-lite).
- 다른 호스트, HTTP, 임의 포트, URL 사용자 정보·쿼리, redirect는 허용하지 않습니다. HTTP 작업자는 고정 2개이고 대기 요청을 쌓지 않습니다. 연결 500ms, 읽기 최대 1,000ms 및 요청 전체 1,500ms 제한과 취소 시 연결 해제를 적용합니다. 응답은 최대 64KiB입니다.
- 실시간 방송은 외부 응답을 기다리지 않습니다. 자동 저장까지 선택한 경우에만 최대 분당 2회, 동시 2개의 검토를 별도로 수행하며 동일 진행 요청을 합칩니다. 결과는 이후 같은 문장의 보정에 사용할 수 있습니다. 파일 번역은 제한 시간 안에 검토가 끝나지 않으면 기존 번역 초안을 유지합니다.

제공자 계정의 모델 접근 권한, 요금, 실제 처리 지연은 로컬 시험으로 입증되지 않았습니다. 실제 키를 이용한 외부 API 호출은 실행하지 않았으며, 모델 품질이나 1,500ms 내 응답 성공률을 측정했다고 보고하지 않습니다.

새 문체나 보정 사전을 기존 파일에 적용하려면 파일을 다시 선택하고 변환합니다. 같은 해시의
원문·시간 정보는 재사용하고 선택한 번역 언어를 현재 설정으로 갱신합니다. ‘실패 재작업’은
이미 성공한 언어를 유지합니다. 파일 보관함은 최대 1,000개이며 방송 기록 500,000개 한도와 별개입니다.

## 문장 사전

이 기능은 모델 가중치를 재학습하지 않습니다. 출발 언어, 대상 언어, 문체, 공백을 정리한 원문이 정확히 일치하면 앱 전용 SQLite의 번역문을 다시 사용합니다. 사용자 확정 항목은 오프라인에서도 사용하며 AI가 덮어쓸 수 없습니다. AI 항목의 자동 사용은 개발자 정보와 자동 저장이 켜진 경우에만 수행합니다. 가져오기는 이미 존재하는 같은 항목을 덮어쓰지 않습니다.

온라인 결과는 길이·출력 형식·숫자·확인 가능한 Latin 이름/약어와 대상 문자 체계를 보수적으로 검사합니다. 이는 모든 이름이나 의미의 완전한 검증이 아니며, 불확실한 결과는 기존 번역을 유지합니다. 사용자는 문장 사전에서 AI 항목을 검토하고 수정·확정할 수 있습니다.

문장 사전은 50,000개로 제한하며 무단으로 오래된 사용자 항목을 지우지 않습니다. 화면/내보내기는 최대 1,000개씩 읽습니다. 음성 인식 힌트에는 사용자 확정 원문의 짧은 문장만 최대 32개, 언어별 합계 1,000 codepoints까지 메모리 캐시로 제공하고 AI 항목은 사용자 확정 전까지 포함하지 않습니다.

검증은 설정/동의 조합, 저장소·제공자 실패, 지연·취소, 고정 호스트·redirect 거부, 응답 크기 제한, 문장 사전 우선순위 및 Keystore/JSON 기기 회귀로 구분합니다. 실행 결과는 최종 시험 보고서의 해당 환경과 함께 확인해야 합니다.
