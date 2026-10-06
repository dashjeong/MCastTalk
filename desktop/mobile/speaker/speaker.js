(() => {
  'use strict';

  const TARGET_SAMPLE_RATE = 16000;
  const BUFFER_SIZE = 2048;

  let audioContext = null;
  let mediaStream = null;
  let processorNode = null;
  let analyserNode = null;
  let animationFrameId = null;

  let websocket = null;
  let reconnectTimer = null;
  let isConnected = false;
  let appInputReady = false;
  let isStarting = false;
  let isStreaming = false;
  let permissionState = 'unknown';
  let lastError = null;
  let isAuthenticated = false;

  const statusBadge = document.getElementById('connectionStatus');
  const statusText = document.getElementById('statusText');
  const micButton = document.getElementById('micButton');
  const micButtonText = document.getElementById('micButtonText');
  const micDescription = document.getElementById('micDescription');
  const vuMeterBar = document.getElementById('vuMeterBar');

  const noticeCard = document.getElementById('noticeCard');
  const noticeIcon = document.getElementById('noticeIcon');
  const noticeTitle = document.getElementById('noticeTitle');
  const noticeMessage = document.getElementById('noticeMessage');
  const noticeRetryBtn = document.getElementById('noticeRetryBtn');

  const networkSecurityIcon = document.getElementById('networkSecurityIcon');
  const networkSecurityTitle = document.getElementById('networkSecurityTitle');
  const networkSecurityDesc = document.getElementById('networkSecurityDesc');
  const securitySetupCard = document.getElementById('securitySetupCard');
  const launchSecureMicBtn = document.getElementById('launchSecureMicBtn');
  const caFingerprint = document.getElementById('caFingerprint');
  const micPinCard = document.getElementById('micPinCard');
  const micPinInput = document.getElementById('micPin');
  const micPinSubmit = document.getElementById('micPinSubmit');
  const micPinMessage = document.getElementById('micPinMessage');

  function isContextSecure() {
    if (typeof window.isSecureContext === 'boolean') {
      return window.isSecureContext;
    }
    const hostname = window.location.hostname;
    return (
      window.location.protocol === 'https:' ||
      hostname === 'localhost' ||
      hostname === '127.0.0.1' ||
      hostname === '[::1]'
    );
  }

  function checkMediaSupport() {
    return !!(navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function');
  }

  let activeWs = null;
  let reconnectAttempts = 0;
  let sequenceNumber = 0;
  let inputProbeTimer = null;

  function setStatus(state, message) {
    if (!statusBadge || !statusText) return;
    statusBadge.className = 'status-badge';
    if (state === 'connected') {
      statusBadge.classList.add('status-connected');
    } else if (state === 'active') {
      statusBadge.classList.add('status-active');
    } else {
      statusBadge.classList.add('status-disconnected');
    }
    statusText.textContent = message;
  }

  function showNotice(title, message, type = 'warning', showRetry = false) {
    if (!noticeCard || !noticeTitle || !noticeMessage) return;
    noticeCard.className = `notice-card ${type}`;
    noticeCard.hidden = false;
    noticeTitle.textContent = title;
    noticeMessage.textContent = message;
    if (noticeIcon) {
      noticeIcon.textContent = type === 'error' ? '🚫' : '⚠️';
    }
    if (noticeRetryBtn) {
      noticeRetryBtn.hidden = !showRetry;
    }
  }

  function hideNotice() {
    if (!noticeCard) return;
    noticeCard.hidden = true;
    if (noticeRetryBtn) {
      noticeRetryBtn.hidden = true;
      noticeRetryBtn.onclick = null;
    }
  }

  function updateNetworkSecurityInfo() {
    const isHttps = window.location.protocol === 'https:';
    if (networkSecurityIcon && networkSecurityTitle && networkSecurityDesc) {
      if (isHttps) {
        networkSecurityIcon.textContent = '🔒';
        networkSecurityTitle.textContent = 'HTTPS 보안 연결';
        networkSecurityDesc.textContent = '암호화된 채널을 통해 오디오와 통신 데이터가 안전하게 전송됩니다.';
      } else {
        networkSecurityIcon.textContent = '⚠️';
        networkSecurityTitle.textContent = '로컬 직접 연결 (비암호화 HTTP)';
        networkSecurityDesc.textContent = '외부 인터넷 없는 현장 핫스팟 직접 전송이나, 현재 HTTP 연결은 암호화되지 않은 로컬 통신입니다.';
      }
    }
  }

  function updateMicButtonAvailability() {
    const isSecure = isContextSecure();
    const isMediaSupported = checkMediaSupport();

    if (!isSecure) {
      if (micButton) {
        micButton.disabled = true;
        micButton.classList.remove('live');
      }
      if (micButtonText) micButtonText.textContent = '마이크 사용 불가 (HTTPS 필요)';
      if (micDescription) {
        micDescription.textContent = '원격 브라우저에서는 보안 연결(HTTPS) 또는 전용 클라이언트 앱이 필요합니다.';
      }
      showNotice(
        '보안 연결(HTTPS) 필요',
        '모바일 브라우저 마이크는 보안 연결(HTTPS)이 필요합니다. 1회성 인증서 등록 후 안전하게 마이크를 사용하실 수 있습니다.',
        'warning',
        true
      );
      if (noticeRetryBtn) {
        noticeRetryBtn.textContent = '인증서 등록 안내 확인';
        noticeRetryBtn.onclick = () => {
          if (securitySetupCard) securitySetupCard.hidden = false;
          securitySetupCard?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
        };
      }
      return;
    }

    if (!isMediaSupported) {
      if (micButton) {
        micButton.disabled = true;
        micButton.classList.remove('live');
      }
      if (micButtonText) micButtonText.textContent = '마이크 지원 불가';
      if (micDescription) {
        micDescription.textContent = '현재 브라우저 환경에서는 마이크 장치 접근 API를 지원하지 않습니다.';
      }
      showNotice(
        '브라우저 미지원',
        '현재 브라우저는 마이크 음성 입력을 지원하지 않습니다. 최신 Chrome 또는 Safari 브라우저를 사용해 주세요.',
        'error',
        false
      );
      return;
    }

    if (!isAuthenticated) {
      if (micButton) {
        micButton.disabled = true;
        micButton.classList.remove('live');
      }
      if (micButtonText) micButtonText.textContent = '강사 인증 대기';
      if (micDescription) micDescription.textContent = '강사 PIN 인증 후 마이크를 시작할 수 있습니다.';
      return;
    }

    if (isConnected && !appInputReady) {
      if (micButton) {
        micButton.disabled = true;
        micButton.classList.remove('live');
      }
      if (micButtonText) micButtonText.textContent = '송출기 입력 준비 대기';
      if (micDescription) {
        micDescription.textContent =
          '송출기 앱에서 “강사 웹 마이크”를 선택하고 입력 시작을 누르세요.';
      }
      showNotice(
        '송출기 입력 준비 필요',
        '방송 폰에서 강사 웹 마이크를 입력으로 선택한 뒤 “입력 시작”을 누르면 자동으로 활성화됩니다.',
        'warning',
        false
      );
      return;
    }

    if (isConnected && !isStreaming && !isStarting) {
      if (micButton) {
        micButton.disabled = false;
        micButton.classList.remove('live');
      }
      if (micButtonText) micButtonText.textContent = '마이크 송출 시작';
      if (micDescription && !lastError) {
        micDescription.textContent = '버튼을 누르면 마이크가 켜지고 음성이 실시간 통역 방송으로 전달됩니다.';
      }
    }
  }

  function configureSecuritySetup() {
    const httpsPort = document.body?.dataset?.httpsPort || '';
    const fingerprint = document.body?.dataset?.caFingerprint || '';
    if (caFingerprint) caFingerprint.textContent = fingerprint || '송출기에서 지문을 읽지 못했습니다.';
    if (!launchSecureMicBtn) return;
    if (!httpsPort) {
      launchSecureMicBtn.removeAttribute('href');
      launchSecureMicBtn.textContent = 'HTTPS 마이크 서버 준비 실패 · 방송 폰 확인';
      launchSecureMicBtn.setAttribute('aria-disabled', 'true');
      return;
    }
    launchSecureMicBtn.href = `https://${window.location.hostname}:${httpsPort}/mic`;
  }

  function setPinMessage(message) {
    if (micPinMessage) micPinMessage.textContent = message || '';
  }

  async function requestMicSession(pin) {
    if (micPinSubmit) micPinSubmit.disabled = true;
    setPinMessage('강사 권한을 확인 중입니다…');
    try {
      const response = await fetch('/api/mic/join', {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
        body: pin,
      });
      if (!response.ok) {
        if (response.status === 401) throw new Error('PIN이 올바르지 않습니다.');
        if (response.status === 429) throw new Error('입력 횟수를 초과했습니다. 잠시 후 다시 시도하세요.');
        throw new Error('강사 인증 서버가 요청을 처리하지 못했습니다.');
      }
      const session = await response.json();
      if (session.authenticated !== true) throw new Error('강사 인증 세션을 만들지 못했습니다.');
      isAuthenticated = true;
      if (micPinInput) micPinInput.value = '';
      if (micPinCard) micPinCard.hidden = true;
      setPinMessage('');
      connectWebSocket();
      updateMicButtonAvailability();
    } catch (error) {
      isAuthenticated = false;
      setPinMessage(error?.message || '강사 인증에 실패했습니다.');
      updateMicButtonAvailability();
    } finally {
      if (micPinSubmit) micPinSubmit.disabled = false;
    }
  }

  async function initializeMicSession() {
    configureSecuritySetup();
    if (!isContextSecure()) {
      if (securitySetupCard) securitySetupCard.hidden = false;
      if (micPinCard) micPinCard.hidden = true;
      updateMicButtonAvailability();
      return;
    }

    try {
      const response = await fetch('/api/mic/session', {
        credentials: 'same-origin',
        cache: 'no-store',
      });
      if (!response.ok) throw new Error('강사 마이크 설정을 읽지 못했습니다.');
      const session = await response.json();
      if (session.authenticated === true) {
        isAuthenticated = true;
        connectWebSocket();
        updateMicButtonAvailability();
        return;
      }
      if (session.requiresPin === true) {
        if (micPinCard) micPinCard.hidden = false;
        setStatus('disconnected', '강사 PIN 입력 대기');
        updateMicButtonAvailability();
      } else {
        await requestMicSession('');
      }
    } catch (error) {
      showNotice(
        '강사 마이크 연결 실패',
        error?.message || '강사 마이크 설정을 불러오지 못했습니다.',
        'error',
        false
      );
      updateMicButtonAvailability();
    }
  }

  async function queryPermissionState() {
    if (!navigator.permissions || typeof navigator.permissions.query !== 'function') {
      return;
    }
    try {
      const status = await navigator.permissions.query({ name: 'microphone' });
      permissionState = status.state;
      if (status.state === 'denied') {
        showNotice(
          '마이크 권한 차단됨',
          '브라우저 사이트 설정에서 마이크 사용 권한이 차단되어 있습니다. 브라우저 주소창이나 설정에서 권한을 허용으로 변경해 주세요.',
          'error',
          true
        );
      }
      status.onchange = () => {
        permissionState = status.state;
        if (status.state === 'granted') {
          hideNotice();
          updateMicButtonAvailability();
        } else if (status.state === 'denied') {
          showNotice(
            '마이크 권한 차단됨',
            '브라우저 사이트 설정에서 마이크 사용 권한이 차단되어 있습니다. 브라우저 주소창이나 설정에서 권한을 허용으로 변경해 주세요.',
            'error',
            true
          );
        }
      };
    } catch (_) {
      // Ignore unsupported platforms
    }
  }

  function getMicErrorMessage(err) {
    const errorName = err && (err.name || err.constructor?.name);
    switch (errorName) {
      case 'NotAllowedError':
      case 'PermissionDeniedError':
        return {
          title: '마이크 권한 필요',
          status: '마이크 사용 권한이 거부되었습니다.',
          guide: '마이크 권한이 차단되었습니다. 브라우저 주소창 또는 사이트 설정에서 마이크 사용을 허용한 후 다시 시도해 주십시오.',
          showRetry: true,
        };
      case 'NotFoundError':
      case 'DevicesNotFoundError':
        return {
          title: '마이크 장치 없음',
          status: '사용 가능한 마이크 장치를 찾을 수 없습니다.',
          guide: '장치에 연결된 마이크를 찾을 수 없습니다. 마이크 하드웨어 연결 상태를 확인해 주십시오.',
          showRetry: true,
        };
      case 'NotReadableError':
      case 'TrackStartError':
        return {
          title: '마이크 접근 불가',
          status: '마이크를 시작할 수 없습니다 (다른 앱 점유).',
          guide: '다른 앱이나 음성 통화에서 마이크를 이미 사용 중입니다. 다른 앱을 종료한 후 다시 시도해 주십시오.',
          showRetry: true,
        };
      case 'AbortError':
        return {
          title: '마이크 연결 중단',
          status: '마이크 연결이 중단되었습니다.',
          guide: '장치 또는 브라우저 오류로 마이크 접근이 중단되었습니다. 다시 시도해 주십시오.',
          showRetry: true,
        };
      case 'SecurityError':
        return {
          title: '보안 정책 제약',
          status: '보안 제약으로 마이크 접근이 차단되었습니다.',
          guide: '보안 제약(비암호화 연결 또는 권한 정책)으로 인해 마이크에 접근할 수 없습니다. HTTPS 연결 또는 전용 클라이언트 앱이 필요합니다.',
          showRetry: false,
        };
      case 'OverconstrainedError':
        return {
          title: '오디오 형식 미지원',
          status: '요청한 오디오 설정을 장치가 지원하지 않습니다.',
          guide: '장치에서 요청한 오디오 형식(16kHz 모노)을 지원하지 않습니다.',
          showRetry: false,
        };
      default:
        return {
          title: '마이크 오류',
          status: '마이크 시작 실패: ' + (err?.message || '장치 오류'),
          guide: '마이크를 초기화할 수 없습니다: ' + (err?.message || '알 수 없는 오류'),
          showRetry: true,
        };
    }
  }

  function connectWebSocket() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    if (!isAuthenticated || !isContextSecure()) {
      updateMicButtonAvailability();
      return;
    }
    const url = `${protocol}//${window.location.host}/ws/speaker-input`;

    setStatus('disconnected', '서버 연결 시도 중...');
    if (micButton) micButton.disabled = true;

    try {
      const ws = new WebSocket(url);
      activeWs = ws;
      websocket = ws;
      ws.binaryType = 'arraybuffer';

      ws.onopen = () => {
        if (ws !== activeWs) return;
        reconnectAttempts = 0;
        isConnected = true;
        appInputReady = false;
        setStatus('connected', '서버 연결됨 · 입력 준비 확인 중');
        try { ws.send('probe'); } catch (_) {}
        scheduleInputProbe(ws);
        updateMicButtonAvailability();
      };

      ws.onmessage = (event) => {
        if (ws !== activeWs) return;
        try {
          const data = JSON.parse(event.data);
          if (data && data.type === 'input-state') {
            appInputReady = data.ready === true;
            if (appInputReady) {
              if (!lastError) hideNotice();
              setStatus('connected', '서버 연결됨 · 입력 준비 완료');
            } else {
              setStatus('connected', '서버 연결됨 · 송출기 입력 준비 필요');
            }
            updateMicButtonAvailability();
          } else if (data && data.type === 'ack' && data.accepted === false) {
            appInputReady = false;
            setStatus('connected', '음성 미수락 · 송출기 입력을 확인하세요');
            updateMicButtonAvailability();
          }
        } catch (_) {}
      };

      ws.onclose = (event) => {
        if (ws !== activeWs) return;
        isConnected = false;
        appInputReady = false;
        clearInputProbe();
        if (isStreaming || isStarting) stopStreaming();
        if (micButton) micButton.disabled = true;

        // Policy violations (unauthorized, invalid origin, lease busy)
        if (event.code === 1008 || event.code === 1013 || event.code === 4401 || event.code === 4403) {
          const reason = event.reason || 'Unauthorized';
          const isBusy = event.code === 1013 ||
            reason.toLowerCase().includes('active') || reason.toLowerCase().includes('busy');
          const noticeMsg = isBusy
            ? '다른 강사가 이미 방송 중입니다. 현재 강사의 방송이 종료된 후 다시 연결해 주십시오.'
            : '강사 인증에 실패했거나 올바르지 않은 접근입니다: ' + reason;
          setStatus('disconnected', '접속 거부: ' + reason);
          showNotice('접속 정책 거부', noticeMsg, 'error', false);
          if (!isBusy) {
            isAuthenticated = false;
            if (micPinCard) micPinCard.hidden = false;
            setPinMessage('방송이 다시 시작되었거나 인증이 만료됐습니다. PIN을 다시 입력하세요.');
          }
          return; // DO NOT reconnect on policy violation
        }

        reconnectAttempts++;
        const delay = Math.min(30000, Math.floor(1000 * Math.pow(1.5, reconnectAttempts)));
        setStatus('disconnected', `서버 연결 끊김 (${Math.round(delay / 1000)}초 후 재연결 시도...)`);
        reconnectTimer = setTimeout(connectWebSocket, delay);
      };

      ws.onerror = () => {
        if (ws !== activeWs) return;
        try {
          ws.close();
        } catch (_) {}
      };
    } catch (e) {
      reconnectAttempts++;
      const delay = Math.min(30000, Math.floor(1000 * Math.pow(1.5, reconnectAttempts)));
      reconnectTimer = setTimeout(connectWebSocket, delay);
    }
  }

  function clearInputProbe() {
    if (inputProbeTimer) {
      clearTimeout(inputProbeTimer);
      inputProbeTimer = null;
    }
  }

  function scheduleInputProbe(ws) {
    clearInputProbe();
    inputProbeTimer = setTimeout(() => {
      inputProbeTimer = null;
      if (ws === activeWs && ws.readyState === WebSocket.OPEN) {
        try { ws.send('probe'); } catch (_) {}
        scheduleInputProbe(ws);
      }
    }, 1000);
  }

  function cleanUpMedia() {
    if (animationFrameId) {
      cancelAnimationFrame(animationFrameId);
      animationFrameId = null;
    }
    if (vuMeterBar) {
      vuMeterBar.style.width = '0%';
    }

    if (processorNode) {
      try {
        processorNode.disconnect();
      } catch (_) {}
      processorNode.onaudioprocess = null;
      processorNode = null;
    }
    if (analyserNode) {
      try {
        analyserNode.disconnect();
      } catch (_) {}
      analyserNode = null;
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach((track) => {
        track.onended = null;
        try {
          track.stop();
          track.enabled = false;
        } catch (_) {}
      });
      mediaStream = null;
    }
    if (audioContext) {
      if (audioContext.state !== 'closed') {
        try {
          audioContext.close();
        } catch (_) {}
      }
      audioContext = null;
    }
  }

  async function startStreaming() {
    if (isStarting || isStreaming) return;
    if (!isContextSecure()) {
      updateMicButtonAvailability();
      return;
    }
    if (!checkMediaSupport()) {
      updateMicButtonAvailability();
      return;
    }
    if (!websocket || websocket.readyState !== WebSocket.OPEN) {
      showNotice('서버 연결 필요', '서버와 연결된 상태에서 마이크를 시작할 수 있습니다.', 'warning', false);
      return;
    }

    isStarting = true;
    lastError = null;
    hideNotice();
    if (micButton) micButton.disabled = true;
    if (micButtonText) micButtonText.textContent = '마이크 연결 중...';
    setStatus('active', '🎙️ 마이크 초기화 중...');

    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          sampleRate: TARGET_SAMPLE_RATE,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      if (!isStarting) {
        cleanUpMedia();
        return;
      }

      mediaStream.getAudioTracks().forEach((track) => {
        track.onended = () => {
          setStatus('disconnected', '마이크 장치 연결이 해제되었습니다.');
          if (micDescription) {
            micDescription.textContent =
              '마이크 장치 연결이 해제되었습니다. 장치 상태를 확인한 후 다시 시작해 주십시오.';
          }
          stopStreaming();
        };
      });

      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) {
        setStatus('disconnected', 'Web Audio API를 지원하지 않는 브라우저입니다.');
        if (micDescription) {
          micDescription.textContent = '현재 브라우저에서 Web Audio를 지원하지 않습니다.';
        }
        stopStreaming();
        return;
      }

      audioContext = new AudioContextClass({ sampleRate: TARGET_SAMPLE_RATE });
      if (audioContext.state === 'suspended') {
        await audioContext.resume();
      }

      if (!isStarting) {
        cleanUpMedia();
        return;
      }

      const source = audioContext.createMediaStreamSource(mediaStream);

      analyserNode = audioContext.createAnalyser();
      analyserNode.fftSize = 256;
      source.connect(analyserNode);

      processorNode = audioContext.createScriptProcessor(BUFFER_SIZE, 1, 1);
      processorNode.onaudioprocess = (event) => {
        if (!isStreaming || !websocket || websocket.readyState !== WebSocket.OPEN) return;

        // Backpressure check: if buffer bloat > 64KB, drop frame
        if (websocket.bufferedAmount > 65536) {
          return;
        }

        const inputData = event.inputBuffer.getChannelData(0);
        const pcm16 = floatTo16BitPcm(inputData, audioContext.sampleRate, TARGET_SAMPLE_RATE);

        // Prepend 4-byte big-endian sequence number
        const packet = new Uint8Array(4 + pcm16.byteLength);
        new DataView(packet.buffer).setInt32(0, sequenceNumber++, false);
        packet.set(new Uint8Array(pcm16.buffer, pcm16.byteOffset, pcm16.byteLength), 4);
        websocket.send(packet.buffer);
      };

      source.connect(processorNode);
      processorNode.connect(audioContext.destination);

      isStreaming = true;
      isStarting = false;
      if (micButton) {
        micButton.disabled = false;
        micButton.classList.add('live');
      }
      if (micButtonText) micButtonText.textContent = '마이크 송출 일시정지';
      if (micDescription) {
        micDescription.textContent =
          '🔴 음성이 방송 앱으로 실시간 송출 중입니다. 버튼을 누르면 중지됩니다.';
      }
      setStatus('active', '🎙️ 마이크 실시간 송출 중');

      startVuMeter();
    } catch (err) {
      isStarting = false;
      lastError = err;
      cleanUpMedia();

      const errorDetail = getMicErrorMessage(err);
      setStatus('disconnected', errorDetail.status);
      if (micDescription) micDescription.textContent = errorDetail.guide;
      showNotice(errorDetail.title, errorDetail.guide, 'error', errorDetail.showRetry);

      if (micButton) {
        micButton.classList.remove('live');
      }
      if (micButtonText) micButtonText.textContent = '마이크 송출 시작';
      updateMicButtonAvailability();
    }
  }

  function stopStreaming() {
    isStreaming = false;
    isStarting = false;
    cleanUpMedia();

    if (micButton) {
      micButton.classList.remove('live');
    }
    if (micButtonText) micButtonText.textContent = '마이크 송출 시작';
    if (micDescription && isContextSecure() && isConnected && !lastError) {
      micDescription.textContent =
        '버튼을 누르면 마이크가 켜지고 음성이 실시간 통역 방송으로 전달됩니다.';
    }
    updateMicButtonAvailability();

    if (isConnected) {
      setStatus('connected', '서버 연결됨 (대기)');
    }
  }

  function floatTo16BitPcm(input, inputSampleRate, targetSampleRate) {
    let samples = input;
    if (inputSampleRate !== targetSampleRate) {
      const ratio = inputSampleRate / targetSampleRate;
      const newLength = Math.round(input.length / ratio);
      const resampled = new Float32Array(newLength);
      for (let i = 0; i < newLength; i++) {
        const originIndex = i * ratio;
        const indexFloor = Math.floor(originIndex);
        const indexCeil = Math.min(input.length - 1, Math.ceil(originIndex));
        const fraction = originIndex - indexFloor;
        resampled[i] = input[indexFloor] * (1 - fraction) + input[indexCeil] * fraction;
      }
      samples = resampled;
    }

    const output = new Int16Array(samples.length);
    for (let i = 0; i < samples.length; i++) {
      const s = Math.max(-1, Math.min(1, samples[i]));
      output[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }
    return output;
  }

  function startVuMeter() {
    if (!analyserNode || !vuMeterBar) return;
    const dataArray = new Uint8Array(analyserNode.frequencyBinCount);

    function update() {
      if (!isStreaming || !analyserNode) return;
      analyserNode.getByteFrequencyData(dataArray);

      let sum = 0;
      for (let i = 0; i < dataArray.length; i++) {
        sum += dataArray[i];
      }
      const average = sum / dataArray.length;
      const percent = Math.min(100, Math.round((average / 128) * 100));
      if (vuMeterBar) {
        vuMeterBar.style.width = `${percent}%`;
      }

      animationFrameId = requestAnimationFrame(update);
    }
    update();
  }

  if (micButton) {
    micButton.addEventListener('click', () => {
      if (isStreaming || isStarting) {
        stopStreaming();
      } else {
        startStreaming();
      }
    });
  }

  if (micPinCard) {
    micPinCard.addEventListener('submit', (event) => {
      event.preventDefault();
      const pin = (micPinInput?.value || '').replace(/\D/g, '').slice(0, 8);
      if (micPinInput) micPinInput.value = pin;
      if (pin.length < 4) {
        setPinMessage('4~8자리 숫자 PIN을 입력하세요.');
        return;
      }
      requestMicSession(pin);
    });
  }

  if (noticeRetryBtn) {
    noticeRetryBtn.addEventListener('click', () => {
      hideNotice();
      updateMicButtonAvailability();
      if (!isStreaming && !isStarting && micButton && !micButton.disabled) {
        startStreaming();
      }
    });
  }

  window.addEventListener('pagehide', () => {
    stopStreaming();
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (websocket) {
      try {
        websocket.onclose = null;
        websocket.onerror = null;
        websocket.close(1000, 'Page hidden');
      } catch (_) {}
      websocket = null;
    }
    clearInputProbe();
  });

  window.addEventListener('pageshow', (event) => {
    if (event.persisted) {
      stopStreaming();
      reconnectAttempts = 0;
      if (isAuthenticated && (!websocket || websocket.readyState !== WebSocket.OPEN)) {
        connectWebSocket();
      }
    }
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && (isStreaming || isStarting)) {
      stopStreaming();
    }
  });

  window.__guideCastSpeakerDiagnostics = () => ({
    isSecureContext: isContextSecure(),
    isMediaSupported: checkMediaSupport(),
    permissionState,
    isConnected,
    appInputReady,
    isStreaming,
    isStarting,
    lastError: lastError ? (lastError.name || lastError.message) : null,
    isAuthenticated,
    audioContextState: audioContext ? audioContext.state : null,
    activeTracks: mediaStream ? mediaStream.getTracks().filter((t) => t.readyState === 'live').length : 0,
  });

  updateNetworkSecurityInfo();
  configureSecuritySetup();
  updateMicButtonAvailability();
  queryPermissionState();
  initializeMicSession();
})();
