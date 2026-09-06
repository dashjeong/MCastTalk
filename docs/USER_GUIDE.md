# MCastTalk 사용 설명서 (User Guide)

> **MCastTalk** — 실시간 다국어 통역방송 (feat. DMZ peace walk)  
> *Real-time Multilingual On-Device Interpretation & Broadcast Server*

---

## 목차 (Table of Contents)

- [한국어 설명서 (Korean)](#한국어-설명서-korean)
  - [1. 시스템 개요 및 특징](#1-시스템-개요-및-특징)
  - [2. 요구 사양 및 설치 방법](#2-요구-사양-및-설치-방법)
  - [3. 가이드(진행자) 운영 가이드](#3-가이드진행자-운영-가이드)
  - [4. 청취자(참여자) 이용 가이드](#4-청취자참여자-이용-가이드)
  - [5. 문제 해결 및 FAQ](#5-문제-해결-및-faq)
- [English Guide](#english-guide)
  - [1. Overview & Key Features](#1-overview--key-features)
  - [2. System Requirements & Installation](#2-system-requirements--installation)
  - [3. Operator (Broadcaster) Guide](#3-operator-broadcaster-guide)
  - [4. Listener Guide](#4-listener-guide)
  - [5. Troubleshooting & FAQ](#5-troubleshooting--faq)

---

# 한국어 설명서 (Korean)

## 1. 시스템 개요 및 특징

**MCastTalk**는 인터넷이 연결되지 않는 야외 현장(DMZ 평화 걷기, 오지 투어, 전시장 등)에서도 가이드의 스마트폰 하나로 **실시간 음성 인식, 다국어 번역, 음성 합성(TTS), 웹 스트리밍 송출**을 100% 온디바이스로 수행하는 오프라인 통역 방송 솔루션입니다.

- **100% 온디바이스 독립형**: 클라우드 서버나 외부 인터넷 연결 없이 기기 자체 AI로 동작합니다.
- **청취자 무설치(Zero-Install) 웹 수신**: 청취자는 별도 앱 설치 없이 스마트폰 카메라로 QR 코드를 스캔하여 브라우저에서 즉시 청취합니다.
- **초저지연 다채널 송출**: 원음 방송 외에 최대 5개 이상의 다국어 번역 음성 채널을 동시에 독립 스트리밍합니다.
- **장애 격리 설계**: 특정 언어 번역에 일시적 오류가 발생해도 원음 방송과 다른 언어 채널은 중단 없이 정상 유지됩니다.

---

## 2. 요구 사양 및 설치 방법

### 하드웨어 권장 사양

| 구분 | 지원 기종 및 권장 사양 | 비고 |
| :--- | :--- | :--- |
| **권장 기종** | **삼성 갤럭시 S20 시리즈 이상** (S21 Ultra, S23+, S26 Ultra 등) | 고성능 NPU 및 RAM 탑재 기종 |
| **다국어 풀 송출** | **갤럭시 S21 Ultra / S26 Ultra** | **원음 + 5개 언어 동시 송출** 실기기 검증 완료 |
| **주의 기종** | 갤럭시 Note 9 등 구형 기종 | 구동은 가능하나 온디바이스 처리 지연(Latency) 발생 |
| **운영체제** | Android 11 (API 30) 이상, ARM64-v8a | |

> [!NOTE]
> 공개 배포 파일(`MCastTalk-0.2.38-alpha-apache2.apk`)은 개발자 개인 서명키가 포함되지 않은 **미서명(Unsigned) 또는 테스트 서명 APK**입니다. 연구·개발 목적 또는 전용 시험 스마트폰에서의 사용을 권장합니다.

### 설치 방법

#### 방법 1: 스마트폰 직접 설치
1. `MCastTalk-0.2.38-alpha-apache2.apk` 파일을 스마트폰의 `내 파일(My Files)` 또는 다운로드 폴더로 복사합니다.
2. APK 파일을 탭하여 설치를 진행합니다.
3. *"출처를 알 수 없는 앱 설치"* 팝업이 뜨면 **[설정] > [이 출처 허용]**을 활성화한 후 설치를 완료합니다.

#### 방법 2: ADB(Android Debug Bridge) PC 설치
```bash
adb install -r MCastTalk-0.2.38-alpha-apache2.apk
```

---

## 3. 가이드(진행자) 운영 가이드

앱은 하단 3개의 탭(**설정 / 시험 / 운영**)으로 구성되어 있습니다.

```mermaid
graph LR
    A[Step 1: Wi-Fi 핫스팟 실행] --> B[Step 2: '설정' 탭 - 언어 및 모델 준비]
    B --> C[Step 3: '시험' 탭 - 마이크/번역 사전 점검]
    C --> D[Step 4: '운영' 탭 - 방송 시작 & QR 안내]
```

### Step 1. 현장 Wi-Fi 핫스팟(모바일 핫스팟) 켜기
- 안드로이드 설정에서 **[모바일 핫스팟]**을 켭니다. (데이터 통신이 불가능한 오프라인 환경이어도 로컬 Wi-Fi AP로 정상 작동합니다.)
- 핫스팟 이름(SSID)과 비밀번호를 간결하게 설정합니다.

### Step 2. [설정] 탭 — 방송 언어 및 모델 관리
- 앱 하단의 **[설정]** 탭으로 이동합니다.
- **송출 언어 선택**: 방송할 대상 언어(영어, 일본어, 중국어 간체/번체, 스페인어, 베트남어 등)를 활성화합니다.
- **오프라인 모델 준비**: 현장 출발 전(Wi-Fi 환경)에 사용할 언어 팩 및 AI 모델(Gemma, ML Kit, Moonshine)의 다운로드 및 준비 상태를 확인합니다.

### Step 3. [시험] 탭 — 방송 전 사전 점검
- 하단의 **[시험]** 탭으로 이동합니다.
- 시험 발화를 통해 마이크 입력 레벨 게이지가 정상 반응하는지 확인합니다.
- 실시간 음성 인식(STT) 및 번역 결과 텍스트가 올바르게 생성되는지 미리 확인합니다.
- [음성 미리듣기]를 눌러 각 언어별 TTS 음성이 매끄럽게 합성되는지 청취합니다.

### Step 4. [운영] 탭 — 안내방송 시작 및 청취자 안내
- 하단의 **[운영]** 탭으로 이동합니다.
- **[방송 시작]** 버튼을 누릅니다. 로컬 웹서버가 가동되며 상태 표시등이 녹색으로 켜집니다.
- 화면 중앙에 표시되는 **대형 QR 코드**를 청취자들에게 보여줍니다. (접속 주소: `http://192.168.43.1:8080/listener`)
- 마이크 제어(PTT 버튼 또는 음소거 해제)를 통해 방송을 진행합니다.
- 방송 중에는 각 언어별 송출 상태, 청취자 접속 수, 인식된 자막을 실시간 모니터링할 수 있습니다.
- 행사가 끝나면 **[방송 종료]** 버튼을 누릅니다.

---

## 4. 청취자(참여자) 이용 가이드

> **청취자는 별도의 앱을 설치할 필요가 없습니다.**

1. **와이파이 연결**: 안내원의 모바일 핫스팟 Wi-Fi에 접속합니다.
2. **QR 코드 스캔**: 스마트폰 기본 카메라 앱을 열어 진행자 화면의 **QR 코드**를 비춥니다.
3. **웹페이지 접속**: 나타나는 웹 링크를 누르면 `MCastTalk 청취자 페이지`가 브라우저에 열립니다.
4. **언어 선택 및 청취**:
   - 상단에서 원하는 언어(한국어 원음, English, 日本語, 简体中文 등)를 선택합니다.
   - **[재생(Play)]** 버튼을 누르면 실시간 통역 음성이 이어폰으로 들립니다.
   - 화면 하단에서 실시간 통역 자막을 함께 확인할 수 있습니다.

---

## 5. 문제 해결 및 FAQ

> [!TIP]
> **Q. 청취자 페이지에서 소리가 들리지 않아요.**  
> 최신 모바일 브라우저는 사용자의 직접 터치 없이 오디오를 자동 재생하지 못하도록 차단합니다. 화면의 **[재생 / 듣기 시작] 버튼을 반드시 한 번 탭**해 주세요.

> [!WARNING]
> **Q. 음성 인식 및 번역에 지연(Lag)이 심합니다.**  
> 기기 성능이 부족할 때 발생할 수 있습니다. 갤럭시 S20 이상의 기기를 사용하시고, 동시 송출 언어 수를 1~2개로 줄여 부하를 조절해 주세요.

> [!IMPORTANT]
> **Q. 번역 실패 알림이 뜹니다.**  
> MCastTalk는 장애 격리 설계가 적용되어 있어, 특정 언어 번역 엔진이 일시 지연되더라도 **원음 방송은 절대 중단되지 않습니다.** 가이드 목소리는 원음 채널을 통해 모든 청취자에게 실시간 전달됩니다.

---
---

# English Guide

## 1. Overview & Key Features

**MCastTalk** is a 100% on-device, offline multilingual interpretation and live broadcast platform. Designed for outdoor field tours (e.g., DMZ Peace Walk), cultural exhibitions, and events without internet access, it transforms a single Android smartphone into a complete broadcast station.

- **100% On-Device Independence**: Performs Speech-to-Text (STT), Machine Translation (MT), and Text-to-Speech (TTS) locally on the phone without any cloud servers.
- **Zero-Install Web Listener**: Tour members do not need to install any app. Simply scan a QR code with a smartphone camera to listen via any web browser.
- **Ultra-Low Latency Multichannel Audio**: Broadcasts original guide audio alongside up to 5+ simultaneous translated channels.
- **Fault-Isolated Architecture**: If a specific language translation provider encounters an issue, the original audio and other language streams remain unaffected.

---

## 2. System Requirements & Installation

### Hardware Recommendations

| Tier | Devices & Recommendations | Details |
| :--- | :--- | :--- |
| **Recommended** | **Samsung Galaxy S20 series or newer** (S21 Ultra, S23+, S26 Ultra) | High-performance NPU & RAM |
| **Full Multilingual** | **Galaxy S21 Ultra / S26 Ultra** | Verified for **Original + 5 Translated Streams** simultaneously |
| **Caution** | Legacy devices (e.g., Galaxy Note 9) | Operable, but experiences latency during heavy on-device AI inference |
| **OS Minimum** | Android 11 (API 30) or higher, ARM64-v8a | |

> [!NOTE]
> The public release artifact (`MCastTalk-0.2.38-alpha-apache2.apk`) is an **unsigned / test-signed build**. It is intended for research, development, and dedicated field-testing devices.

### Installation Steps

#### Option A: Direct Device Installation
1. Copy `MCastTalk-0.2.38-alpha-apache2.apk` to your phone's storage.
2. Tap the APK file in **My Files** or **Downloads**.
3. When prompted, enable **"Install unknown apps"** for your file manager or browser, then complete installation.

#### Option B: ADB Install via PC
```bash
adb install -r MCastTalk-0.2.38-alpha-apache2.apk
```

---

## 3. Operator (Broadcaster) Guide

The app features three intuitive navigation tabs: **Settings (설정)**, **Test (시험)**, and **Broadcast (운영)**.

```mermaid
graph LR
    A[Step 1: Enable Mobile Hotspot] --> B[Step 2: 'Settings' Tab - Select Languages & Models]
    B --> C[Step 3: 'Test' Tab - Check Mic & Translation]
    C --> D[Step 4: 'Broadcast' Tab - Start & Share QR]
```

### Step 1. Turn on Mobile Hotspot
- Enable **Mobile Hotspot (Wi-Fi Tethering)** in Android settings.
- Internet/cellular data is NOT required; local Wi-Fi connectivity is sufficient.

### Step 2. [Settings] Tab — Language & AI Models
- Navigate to the **Settings (설정)** tab.
- Select target broadcast languages (English, Japanese, Simplified/Traditional Chinese, Spanish, Vietnamese, etc.).
- Ensure necessary on-device AI model packs (Gemma LiteRT, ML Kit, Moonshine STT/TTS) are downloaded and prepared prior to entering offline zones.

### Step 3. [Test] Tab — Pre-Broadcast Inspection
- Switch to the **Test (시험)** tab.
- Speak into the microphone to verify audio input levels and noise suppression.
- Confirm that real-time transcription and translation outputs appear accurately.
- Use the **Audio Preview** button to sample synthesized voice output for each language.

### Step 4. [Broadcast] Tab — Live Operation
- Switch to the **Broadcast (운영)** tab.
- Tap **Start Broadcast (방송 시작)**. The built-in web server launches and the status indicator turns green.
- Present the on-screen **QR Code** to listeners (URL: `http://192.168.43.1:8080/listener`).
- Begin speaking using either Push-To-Talk (PTT) or open microphone mode.
- Monitor active listener counts, channel states, and real-time subtitles.
- Tap **Stop Broadcast (방송 종료)** when the session ends.

---

## 4. Listener Guide

> **No App Installation Required for Listeners!**

1. **Connect to Wi-Fi**: Join the guide's Mobile Hotspot Wi-Fi network.
2. **Scan QR Code**: Open the standard camera app on any iPhone or Android phone and scan the QR code displayed on the guide's screen.
3. **Open Web Player**: Tap the pop-up link to open the `MCastTalk Listener Web Player`.
4. **Choose Language & Listen**:
   - Select your preferred language channel (Original, English, Japanese, Chinese, etc.).
   - Tap the **[Play]** button to begin low-latency live audio streaming.
   - View real-time translated subtitles on screen.

---

## 5. Troubleshooting & FAQ

> [!TIP]
> **Q. No sound is playing on the listener's phone.**  
> Modern mobile browsers (iOS Safari, Android Chrome) block audio auto-play. The user must explicitly tap the **[Play]** button on the web page to allow audio playback.

> [!WARNING]
> **Q. Translation is falling behind the speaker's voice.**  
> High latency indicates hardware resource constraints. Recommend using Galaxy S20 or newer, or reducing the number of active translated channels.

> [!IMPORTANT]
> **Q. A translation provider error message is shown.**  
> MCastTalk isolates failures per channel. If a specific translation engine stutters, **original audio streaming continues uninterrupted**, ensuring the guide's voice always reaches the audience.

---

## License & Attribution

- **First-Party Source Code**: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- **Brand & Trademark Policy**: See [BRANDING.md](BRANDING.md)
- **Third-Party Notices**: See [docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md)
- **Developed with**: AI-assisted Vibe Coding (Codex, Gemini with dash.jeong)
