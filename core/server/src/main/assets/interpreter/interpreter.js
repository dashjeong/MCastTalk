(() => {
  'use strict';
  // 화자 입력의 순번 프레임과 청취 웹의 연속 표본 변환 방식을 사용한다.
  const params = new URLSearchParams(location.hash.slice(1));
  const language = params.get('lang') || '';
  const $ = id => document.getElementById(id);
  const micButton = $('microphone');
  const listenButton = $('listen');
  let inputSocket = null, sourceSocket = null, context = null, microphone = null, processor = null, inputNode = null, mute = null;
  let inputSeq = 0, heartbeat = null, poll = null, reconnect = null, sourceReconnect = null, listening = false, talking = false, disposed = false, nextPlay = 0;
  let authenticated = false, live = false, micStarting = false, pollBusy = false, lastAck = 0;
  let micEpoch = 0;
  const sources = new Set();
  const stateText = state => ({ HUMAN: '사람 통역 중', WAITING: '읽던 문장을 마치는 중 · 잠시 기다려 주세요', AI: 'AI로 복귀', READY: '대기 · 통역을 시작해 주세요', IDLE: '대기' })[state] || '대기';
  const wsUrl = path => `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}`;
  const draftFor = line => Object.entries(line.translations || {}).find(([tag]) => tag.toLowerCase() === language.toLowerCase())?.[1] || '';

  function resample(input, from, to, state) {
    if (from === to) return input;
    const offset = state.tail == null ? 0 : 1;
    const samples = new Float32Array(input.length + offset);
    if (offset) samples[0] = state.tail;
    samples.set(input, offset);
    const ratio = from / to, phase = state.phase || 0;
    const count = Math.max(0, Math.ceil((samples.length - 1 - phase) / ratio - 1e-10));
    const output = new Float32Array(count);
    for (let i = 0; i < count; i++) {
      const at = phase + i * ratio, left = Math.floor(at);
      output[i] = samples[left] + (samples[left + 1] - samples[left]) * (at - left);
    }
    state.phase = Math.max(0, phase + count * ratio - (samples.length - 1));
    state.tail = samples[samples.length - 1];
    return output;
  }

  async function audioContext() {
    if (!context) context = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
    await context.resume();
    return context;
  }

  function stopMicrophone() {
    micEpoch++;
    talking = false;
    micStarting = false;
    if (processor) { processor.onaudioprocess = null; processor.disconnect(); }
    inputNode?.disconnect(); mute?.disconnect();
    microphone?.getTracks().forEach(track => track.stop());
    microphone = processor = inputNode = mute = null;
    $('input-level').value = 0;
    micButton.textContent = '통역 시작';
    micButton.setAttribute('aria-pressed', 'false');
    micButton.disabled = !live || inputSocket?.readyState !== WebSocket.OPEN;
    if (inputSocket?.readyState === WebSocket.OPEN) inputSocket.send('pause');
  }

  async function startMicrophone() {
    if (micStarting || talking || !live || inputSocket?.readyState !== WebSocket.OPEN) return;
    micStarting = true;
    const epoch = ++micEpoch;
    micButton.disabled = true;
    let acquired = null;
    try {
      const audio = await audioContext();
      acquired = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false });
      if (epoch !== micEpoch || disposed || !live || inputSocket?.readyState !== WebSocket.OPEN) { acquired.getTracks().forEach(t => t.stop()); return; }
      microphone = acquired;
      inputNode = audio.createMediaStreamSource(microphone);
      processor = audio.createScriptProcessor(2048, 1, 1);
      mute = audio.createGain(); mute.gain.value = 0;
      const resampling = {};
      let pending = [];
      processor.onaudioprocess = event => {
        if (!talking || inputSocket?.readyState !== WebSocket.OPEN) return;
        const input = event.inputBuffer.getChannelData(0);
        let energy = 0;
        for (const value of input) energy += value * value;
        $('input-level').value = Math.min(1, Math.sqrt(energy / input.length) * 4);
        const samples = resample(input, audio.sampleRate, 16000, resampling);
        for (const sample of samples) pending.push(Math.round(Math.max(-1, Math.min(1, sample)) * 32767));
        while (pending.length >= 320) {
          if (inputSocket.bufferedAmount > 32000) {
            stopMicrophone(); $('connection').textContent = '소리 전달이 늦어 잠시 멈췄어요. 다시 시작해 주세요'; return;
          }
          const packet = new ArrayBuffer(644), view = new DataView(packet);
          view.setUint32(0, inputSeq++, false);
          for (let i = 0; i < 320; i++) view.setInt16(4 + i * 2, pending[i], true);
          pending = pending.slice(320);
          inputSocket.send(packet);
        }
      };
      talking = true;
      inputNode.connect(processor); processor.connect(mute); mute.connect(audio.destination);
      microphone.getTracks().forEach(track => { track.onended = () => { if (epoch === micEpoch) { stopMicrophone(); $('connection').textContent = '마이크가 꺼졌어요. 다시 시작해 주세요'; } }; });
      micButton.textContent = '잠시 멈춤'; micButton.setAttribute('aria-pressed', 'true');
    } catch (_) {
      acquired?.getTracks().forEach(t => t.stop());
      if (epoch === micEpoch) {
        stopMicrophone();
        $('connection').textContent = '마이크 사용을 허용한 뒤 다시 시작해 주세요';
      }
    } finally {
      if (epoch === micEpoch) {
        micStarting = false;
        micButton.disabled = !live || inputSocket?.readyState !== WebSocket.OPEN;
      }
    }
  }

  function openInput() {
    if (disposed || !authenticated || inputSocket) return;
    const socket = new WebSocket(wsUrl(`/ws/interpreter-input?lang=${encodeURIComponent(language)}`));
    inputSocket = socket;
    socket.onopen = () => {
      inputSeq = 0; lastAck = Date.now();
      $('connection').textContent = '연결됨';
      clearInterval(heartbeat);
      heartbeat = setInterval(() => {
        if (Date.now() - lastAck > 6000) { stopMicrophone(); socket.close(); return; }
        if (socket.readyState === WebSocket.OPEN) socket.send('heartbeat');
      }, 1000);
    };
    socket.onmessage = event => {
      if (typeof event.data !== 'string' || socket !== inputSocket) return;
      try {
        const status = JSON.parse(event.data);
        lastAck = Date.now(); live = status.live === true;
        $('floor').textContent = stateText(status.state);
        if (!live && (talking || micStarting)) stopMicrophone();
        micButton.disabled = !live || micStarting;
      } catch (_) { /* 불완전한 상태 알림은 다음 알림에서 다시 확인한다. */ }
    };
    socket.onclose = event => {
      if (socket !== inputSocket) return;
      clearInterval(heartbeat); inputSocket = null; stopMicrophone();
      $('connection').textContent = event.code === 1013 ? '이미 다른 통역사가 맡고 있어요. 방송 담당자에게 확인해 주세요' : '연결이 끊겨 AI로 돌아갔어요';
      if (!disposed && event.code !== 1013 && event.code !== 1008) reconnect = setTimeout(openInput, 1500);
    };
    socket.onerror = () => { $('connection').textContent = '연결을 확인하고 있어요'; };
  }

  function stopOriginal() {
    listening = false;
    clearTimeout(sourceReconnect); sourceReconnect = null;
    if (sourceSocket) { sourceSocket.onclose = null; sourceSocket.close(); sourceSocket = null; }
    for (const source of sources) { try { source.stop(); } catch (_) {} }
    sources.clear(); nextPlay = 0;
    listenButton.textContent = '원래 목소리 듣기';
  }

  function openOriginal() {
    if (disposed || !listening || sourceSocket) return;
    let rate = 16000;
    const socket = new WebSocket(wsUrl('/ws/source'));
    sourceSocket = socket; socket.binaryType = 'arraybuffer';
    socket.onopen = () => { $('listen-status').textContent = '원래 목소리 연결됨'; };
    socket.onmessage = event => {
      if (socket !== sourceSocket || !listening) return;
      if (typeof event.data === 'string') {
        try { const config = JSON.parse(event.data); if (config.sampleRate >= 8000 && config.sampleRate <= 48000) rate = config.sampleRate; } catch (_) {}
        return;
      }
      if (!live || context.state !== 'running' || event.data.byteLength % 2) return;
      if (nextPlay - context.currentTime > 0.5) {
        for (const source of sources) { try { source.stop(); } catch (_) {} }
        sources.clear(); nextPlay = 0;
        $('listen-status').textContent = '원래 목소리가 밀려 현재 소리로 맞췄어요';
      }
      const view = new DataView(event.data);
      if (!view.byteLength) return;
      const buffer = context.createBuffer(1, view.byteLength / 2, rate), samples = buffer.getChannelData(0);
      for (let i = 0; i < samples.length; i++) samples[i] = view.getInt16(i * 2, true) / 32768;
      const source = context.createBufferSource(); source.buffer = buffer; source.connect(context.destination);
      sources.add(source); source.onended = () => sources.delete(source);
      const at = Math.max(context.currentTime + 0.04, nextPlay);
      source.start(at); nextPlay = at + buffer.duration;
    };
    socket.onclose = () => {
      if (socket !== sourceSocket) return;
      sourceSocket = null;
      if (listening && !disposed) {
        $('listen-status').textContent = '원래 목소리를 다시 연결하고 있어요';
        sourceReconnect = setTimeout(openOriginal, 1500);
      }
    };
  }

  async function refresh() {
    if (pollBusy || disposed) return;
    pollBusy = true;
    try {
      const response = await fetch(`/api/interpreter/session?lang=${encodeURIComponent(language)}`, { cache: 'no-store', signal: AbortSignal.timeout(4000) });
      if (response.status === 401 || response.status === 410) {
        authenticated = false; stopMicrophone(); stopOriginal(); inputSocket?.close(); clearInterval(poll);
        $('connection').textContent = '방송폰의 새 통역사 QR로 연결해 주세요'; return;
      }
      if (!response.ok) throw new Error();
      const session = await response.json();
      authenticated = true; live = session.live === true;
      $('language').textContent = `${session.name} 통역`;
      $('floor').textContent = stateText(session.state);
      const lines = session.captions?.transcripts || [];
      const final = lines.filter(line => line.isFinal).at(-1);
      const preview = lines.filter(line => !line.isFinal).at(-1);
      $('source').textContent = final?.sourceText || '말씀을 기다리고 있어요';
      $('preview').textContent = preview && (!final || preview.sequence > final.sequence) ? `받는 중 · ${preview.sourceText}` : '';
      const draft = lines.filter(line => draftFor(line)).at(-1);
      $('draft').textContent = draft ? draftFor(draft) : '초안을 기다리고 있어요';
      $('briefing').textContent = session.briefing || '등록된 행사 정보가 없어요';
      $('glossary').replaceChildren();
      for (const term of session.glossary || []) {
        const li = document.createElement('li'); li.textContent = `${term.source} → ${term.target}`; $('glossary').append(li);
      }
      if (!$('glossary').children.length) { const li = document.createElement('li'); li.textContent = '해당하는 용어가 없어요'; $('glossary').append(li); }
      listenButton.disabled = false;
      if (!live && (talking || micStarting)) stopMicrophone();
    } catch (_) { $('connection').textContent = '자막 연결을 다시 확인하고 있어요'; }
    finally { pollBusy = false; }
  }

  micButton.addEventListener('click', () => talking ? stopMicrophone() : startMicrophone());
  listenButton.addEventListener('click', async () => {
    if (listening) { stopOriginal(); $('listen-status').textContent = '원래 목소리 듣기를 멈췄어요'; return; }
    try { await audioContext(); listening = true; listenButton.textContent = '원래 목소리 잠시 멈춤'; openOriginal(); }
    catch (_) { $('listen-status').textContent = '소리 재생을 허용한 뒤 다시 눌러 주세요'; }
  });
  document.addEventListener('visibilitychange', () => { if (document.hidden) stopMicrophone(); });
  window.addEventListener('pagehide', () => {
    disposed = true; clearInterval(poll); clearInterval(heartbeat); clearTimeout(reconnect);
    stopMicrophone(); stopOriginal(); inputSocket?.close(); context?.close();
  });
  (async () => {
    try {
      if (!/^[a-z0-9][a-z0-9_-]{0,23}$/.test(language)) throw new Error();
      const invite = params.get('invite');
      history.replaceState(null, '', `${location.pathname}#lang=${encodeURIComponent(language)}`);
      if (invite) {
        const response = await fetch(`/api/interpreter/join?lang=${encodeURIComponent(language)}`, { method: 'POST', body: invite, cache: 'no-store' });
        if (!response.ok) throw new Error();
      }
      await refresh();
      if (!authenticated) throw new Error();
      openInput(); poll = setInterval(refresh, 1000);
    } catch (_) { $('connection').textContent = '연결할 수 없어요. 방송폰의 새 통역사 QR로 다시 접속해 주세요'; }
  })();
})();
