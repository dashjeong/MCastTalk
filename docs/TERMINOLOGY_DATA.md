# 공공용어 데이터 출처와 빌드

앱에는 국립국어원 공공용어에서 변환한 영어·중국어·일본어 고정 용어 데이터가 포함됩니다. 통합 제공처는 국립국어원이며 행별 원출처에는 국립국어원, 서울특별시, 한국관광공사, 외교부와 행정안전부가 포함됩니다.

- [공공데이터포털의 해당 자료와 이용허락범위](https://www.data.go.kr/data/15123639/fileData.do) · [공공누리 제1유형 출처표시](https://www.kogl.or.kr/info/licenseType1.do)
- [자료 조회·다운로드](https://www.korean.go.kr/front/pubWord/pubWordDataList.do?cateLarge=ALL&flag=1&langGubun=ALL&originGubun=ALL)
- [자료 소개](https://www.korean.go.kr/front/pubWord/pubWordDataIntro.do?mn_id=164)
- [제공처 저작권 정책](https://www.korean.go.kr/front/nuri/pageView.do?mkn=3&page_id=P000189)

공개 빌드 입력은 `app/src/main/assets/glossary/public-20260905.db.gz`이며 APK에는 압축을 푼 `assets/glossary/public-20260905.db`가 포함됩니다. 출처·행수·해시는 `app/src/main/assets/glossary/provenance.json`, 필수 고지는 `app/src/main/assets/licenses/PUBLIC-TERMINOLOGY-NOTICES.txt`에 있습니다. 원본 XLS와 사용자가 만든 자료·DB는 포함하지 않습니다.

현재 자료 판본은 `public-20261125`입니다. 2026년 11월 24~25일 국립국어원 언어별 XLS를 대조했으며 영어 51,320행, 중국어 24,675행, 일본어 24,565행을 보존했습니다. 9월 고정본에서 달라진 1행은 일본어 정거장명 ‘송도달빛축제공원’의 ソンドダルピッチュクチェゴンウォン → ソンドダルビッチュクチェゴンウォン입니다. 설치 호환성을 위해 자산 파일명은 유지합니다. 앱은 최초 사전 연결 시 설치된 기본 DB의 크기와 SHA-256을 검사해 필요할 때만 교체합니다. 사용자 수정은 별도 `user.db`를 사용합니다.

공공데이터포털의 2026-09-09 XLSX는 한국어 명칭 6,616행의 별도 파일입니다. SHA-256은 `9b89a5533a30b9313ae832f4c6dccdde7d51beeea5572663df5fb93c3b4ed674`입니다. 영어·중국어·일본어를 같은 행에 열로 제공하며 해당 일본어 정거장명에는 기존 표기가 들어 있습니다.

제공된 고정 데이터로 앱을 빌드할 수 있습니다. 원본에서 재생성하려면 공식 자료의 영어·중국어·일본어 XLS를 저장소 밖에 `public-terms-en.xls`, `public-terms-zh.xls`, `public-terms-ja.xls`로 보관하고 별도 Python 환경에 `xlrd==2.0.2`를 설치한 뒤 실행합니다.

```sh
python scripts/build-public-glossary.py /path/to/official-downloads
./gradlew :app:verifyPackagedThirdPartyLicenseAssets
```

런타임과 APK 검증은 고정 DB의 크기·SHA-256을 검사합니다. 자료가 갱신되면 새 결과를 자동 승인하지 않습니다. 출처·이용 조건과 데이터 변경을 확인한 뒤 코드의 무결성 기준과 provenance를 함께 갱신해야 합니다. 제공처의 기존 권리와 이용 조건은 유지되며 MCastTalk 코드 라이선스로 자료를 재허가하지 않습니다.
