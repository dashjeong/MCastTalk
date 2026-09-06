# 화면 캡처 관리

이 폴더의 이미지는 `MCastTalk-0.2.38-alpha-apache2.apk`에 포함된 청취자·강사 웹 화면 HTML/CSS를 기준으로 문서용 예시 상태를 재현한 것입니다. 실제 사용자 데이터, PIN 또는 IP 주소는 포함하지 않았습니다.

| 파일 | 용도 |
| --- | --- |
| `listener-overview-ko.png` | 한국어 청취 화면 |
| `listener-overview-en.png` | 영문 청취 화면 |
| `listener-transcript-ko.png` | 원문·번역 스크립트 화면 |
| `remote-mic-ko.png` | 한국어 강사 웹 마이크 화면 |
| `remote-mic-en.png` | 영문 강사 웹 마이크 화면 |

버전이 바뀌면 실제 기기에서 다음 조건으로 캡처를 새로 만드세요.

- 알림과 상태 표시줄에 개인정보가 없는 시험 기기를 사용합니다.
- 실제 행사 PIN, IP 주소, 참가자 이름과 발언은 사용하지 않습니다.
- 한글과 영문 이미지의 화면 크기와 순서를 맞춥니다.
- 핵심 조작 위치만 보여 주고 장식용 이미지는 추가하지 않습니다.
- 파일명은 유지해 기존 Markdown 링크가 깨지지 않도록 합니다.
- 교체 후 GitHub의 밝은 화면과 어두운 화면에서 가독성을 확인합니다.

Android 운영자 화면을 추가하려면 다음 이름을 권장합니다.

```text
operator-home-ko.png
operator-language-model-ko.png
operator-input-ko.png
operator-test-ko.png
operator-broadcast-ko.png
operator-home-en.png
operator-language-model-en.png
operator-input-en.png
operator-test-en.png
operator-broadcast-en.png
```
