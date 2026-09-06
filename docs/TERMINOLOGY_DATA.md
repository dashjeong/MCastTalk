# 공공용어 데이터 출처와 로컬 빌드

제공처는 국립국어원이며 원출처 기관은 국립국어원, 서울특별시, 한국관광공사,
외교부, 행정안전부입니다. GitHub에는 데이터 원본 XLS와 변환 SQLite DB를 올리지 않습니다.
앱용 데이터는 로컬에서 준비하며 출처와 다운로드 위치를 앱의 라이선스 고지에도 표시합니다.

- [자료 조회 및 다운로드](https://www.korean.go.kr/front/pubWord/pubWordDataList.do?cateLarge=ALL&flag=1&langGubun=ALL&originGubun=ALL)
- [공공용어 자료 소개](https://www.korean.go.kr/front/pubWord/pubWordDataIntro.do?mn_id=164)
- [제공처 저작권 정책](https://www.korean.go.kr/front/nuri/pageView.do?mkn=3&page_id=P000189)

다운로드 화면에서 영어·중국어·일본어를 각각 선택하고 출처·한글명칭·번역어·분류를
포함해 내려받습니다. 저장소 밖의 폴더에 `public-terms-en.xls`, `public-terms-zh.xls`,
`public-terms-ja.xls`로 보관합니다. 이 버전이 검증한 원자료 SHA-256과 행수는
`app/src/main/assets/glossary/provenance.json`에 있습니다.

별도 Python 가상환경에 변환 도구의 의존성 `xlrd==2.0.2`를 설치한 뒤 실행합니다.

```sh
python scripts/build-public-glossary.py /path/to/official-downloads
./gradlew :app:verifyPackagedThirdPartyLicenseAssets
```

변환 결과는 Git에서 제외된 `app/src/main/assets/glossary/public-20260905.db.gz`에
생성됩니다. 현재 런타임과 APK 검증은 검증된 DB의 크기와 SHA-256을 고정하므로,
공식 데이터가 갱신되어 해시가 달라지면 자동으로 승인되지 않습니다. 새 자료와 변환 결과를
검토한 후 코드의 무결성 기준 및 provenance를 함께 갱신해야 합니다.

공개 CI는 데이터 없는 소스 빌드만 검사하며 APK를 게시하지 않습니다. 데이터가 없는 앱은
기본 사전 기능을 사용할 수 없습니다. 사전이 포함된 실제 앱의
배포 전에는 로컬 데이터를 준비하고 전체 APK 검증을 수행해야 합니다.

원자료의 권리는 제공처에 있으며 자료를 MCastTalk 자체 코드 라이선스로 재허가하지 않습니다.
