# 공공용어 데이터 출처와 빌드

앱에는 국립국어원 공공용어에서 변환한 영어·중국어·일본어 고정 용어 데이터가 포함됩니다. 통합 제공처는 국립국어원이며 행별 원출처에는 국립국어원, 서울특별시, 한국관광공사, 외교부와 행정안전부가 포함됩니다.

- [자료 조회·다운로드](https://www.korean.go.kr/front/pubWord/pubWordDataList.do?cateLarge=ALL&flag=1&langGubun=ALL&originGubun=ALL)
- [자료 소개](https://www.korean.go.kr/front/pubWord/pubWordDataIntro.do?mn_id=164)
- [제공처 저작권 정책](https://www.korean.go.kr/front/nuri/pageView.do?mkn=3&page_id=P000189)

공개 빌드 입력은 `app/src/main/assets/glossary/public-20260905.db.gz`이며 APK에는 압축을 푼 `assets/glossary/public-20260905.db`가 포함됩니다. 출처·행수·해시는 `app/src/main/assets/glossary/provenance.json`, 필수 고지는 `app/src/main/assets/licenses/PUBLIC-TERMINOLOGY-NOTICES.txt`에 있습니다. 원본 XLS와 사용자가 만든 자료·DB는 포함하지 않습니다.

제공된 고정 데이터로 앱을 빌드할 수 있습니다. 원본에서 재생성하려면 공식 자료의 영어·중국어·일본어 XLS를 저장소 밖에 `public-terms-en.xls`, `public-terms-zh.xls`, `public-terms-ja.xls`로 보관하고 별도 Python 환경에 `xlrd==2.0.2`를 설치한 뒤 실행합니다.

```sh
python scripts/build-public-glossary.py /path/to/official-downloads
./gradlew :app:verifyPackagedThirdPartyLicenseAssets
```

런타임과 APK 검증은 고정 DB의 크기·SHA-256을 검사합니다. 자료가 갱신되면 새 결과를 자동 승인하지 않습니다. 출처·이용 조건과 데이터 변경을 확인한 뒤 코드의 무결성 기준과 provenance를 함께 갱신해야 합니다. 제공처의 기존 권리와 이용 조건은 유지되며 MCastTalk 코드 라이선스로 자료를 재허가하지 않습니다.
