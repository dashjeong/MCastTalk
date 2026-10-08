"use strict";

// Already generated PCM only. Finite 5-second reads; never creates a cloud connection.
function locateRecordedAudio(segments, seconds) {
  let start = 0;
  for (const segment of segments) {
    const duration = segment.bytes / (segment.sampleRate * 2);
    if (seconds < start + duration) return { segment, offset: Math.floor(Math.max(0, seconds - start) * segment.sampleRate) * 2, start };
    start += duration;
  }
  return null;
}
function recordedAudioDisposition(recordingState, broadcastPhase) {
  if (recordingState === "FAILED" || recordingState === "INTERRUPTED" || broadcastPhase === "FAILED") return "interrupted";
  if (recordingState === "COMPLETED" || broadcastPhase === "COMPLETED") return "completed";
  if (recordingState === "PAUSED" || broadcastPhase === "PAUSED") return "paused";
  return "waiting";
}
if (typeof module !== "undefined") module.exports = { locateRecordedAudio, recordedAudioDisposition };
if (typeof document !== "undefined" && document.querySelector("#listening-mode")) {
  const mode = document.querySelector("#listening-mode");
  const controls = document.querySelector("#replay-controls");
  const slider = document.querySelector("#replay-position");
  const clock = document.querySelector("#replay-clock");
  const warning = document.querySelector("#replay-warning");
  let captionCursor = null, captionRows = [], captionPending = null, lastCaptionLoad = 0, captionEpoch = 0, captionController = null, captionError = "";
  const previousPages = [];
  function invalidateCaptions() {
    captionEpoch++; captionController?.abort(); captionController = null; captionPending = null;
  }
  async function loadCaptions(force = false) {
    if (captionPending) return captionPending;
    if (!force && Date.now() - lastCaptionLoad < 5000) return;
    lastCaptionLoad = Date.now();
    const activeCaptionEpoch = captionEpoch;
    const captionSignal = (captionController = new AbortController()).signal;
    const suffix = captionCursor ? `?afterPart=${captionCursor.part}&afterSequence=${captionCursor.sequence}` : "";
    captionPending = (async () => {
      try {
        const rows = await withListenerRequestTimeout(async signal => {
          const response = await fetch(`/api/replay-captions${suffix}`, { headers: authHeaders(), signal });
          if (!response.ok) throw new Error("저장 스크립트를 불러오지 못했습니다");
          const payload = await response.text();
          if (payload.length > MAX_CACHED_TRANSCRIPT_CHARS) throw new Error("저장 스크립트 형식 오류");
          return JSON.parse(payload);
        }, captionSignal);
        if (!Array.isArray(rows) || rows.length > 100) throw new Error("저장 스크립트 형식 오류");
        if (activeCaptionEpoch !== captionEpoch || (document.querySelector("#transcript-scope").value !== "archive" && mode.value !== "replay")) return;
        captionRows = rows; captionError = "";
        if (document.querySelector("#transcript-scope").value === "archive") renderTranscripts(JSON.stringify(rows));
        document.querySelector("#archive-next").disabled = rows.length < 100;
        document.querySelector("#archive-prev").disabled = previousPages.length === 0;
      } catch (_) {
        if (activeCaptionEpoch === captionEpoch && !captionSignal.aborted) {
          captionError = "저장 스크립트를 불러오지 못했습니다";
          if (document.querySelector("#transcript-scope").value === "archive") renderNoTranscript(captionError);
        }
      } finally {
        if (activeCaptionEpoch === captionEpoch) { captionPending = null; captionController = null; globalThis.GuideCastHud?.update(); }
      }
    })();
    globalThis.GuideCastHud?.update();
    return captionPending;
  }
  let epoch = 0, context = null, source = null, position = 0, scheduledStart = 0, scheduledPosition = 0, scheduledRate = 1, manifest = null, playing = false, ended = false, controller = null, finishWaiting = null;
  const format = value => `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, "0")}`;
  const currentPosition = () => source && context ? scheduledPosition + Math.max(0, context.currentTime - scheduledStart) * scheduledRate : position;
  function render() {
    const duration = (manifest?.segments || []).reduce((sum, s) => sum + s.bytes / (2 * s.sampleRate), 0);
    slider.max = duration; slider.value = Math.min(duration, currentPosition());
    clock.textContent = `${format(Number(slider.value))} / ${format(duration)}`;
    warning.textContent = uiText(manifest?.storageFailed ? "저장 공간 오류 · 녹음이 불완전합니다" : manifest?.recordingGaps > 0 ? `녹음 공백 ${manifest.recordingGaps}회` : "");
  }
  async function fetchManifest(signal) {
    const result = await withListenerRequestTimeout(async requestSignal => {
      const response = await fetch(`/api/replay?channel=${encodeURIComponent(channelSelect.value)}`, { headers: authHeaders(), signal: requestSignal });
      if (!response.ok) throw new Error(response.status === 401 ? "방송에 다시 입장하세요" : "저장 구간을 가져오지 못했습니다");
      return response.json();
    }, signal);
    if (!Array.isArray(result.segments) || result.segments.some(s => !Number.isSafeInteger(s.bytes) || s.bytes < 0 || !Number.isInteger(s.sampleRate) || s.sampleRate < 8000 || s.sampleRate > 48000)) throw new Error("저장 구간 정보 오류");
    return result;
  }
  function cancel(reset = false) {
    position = currentPosition(); epoch++; playing = false;
    controller?.abort(); controller = null;
    finishWaiting?.(); finishWaiting = null;
    if (source) { source.onended = null; try { source.stop(); } catch (_) {} source = null; }
    if (context) { try { Promise.resolve(context.close()).catch(() => {}); } catch (_) {} context = null; }
    if (reset) { position = 0; ended = false; playButton.textContent = defaultPlayButtonText; }
    render();
  }
  async function start() {
    const restart = ended;
    cancel(); if (restart) position = 0;
    ended = false; playButton.textContent = defaultPlayButtonText;
    stopRuntime(); desiredState = "playing";
    loadCaptions();
    const activeEpoch = ++epoch; playing = true;
    let audio;
    playButton.disabled = true; pauseButton.disabled = false; stopButton.disabled = false; liveEdgeButton.disabled = false;
    try {
      audio = new (window.AudioContext || window.webkitAudioContext)(); context = audio;
      controller = new AbortController(); const signal = controller.signal;
      await audio.resume();
      if (audio.state !== "running") throw new Error("재생 버튼을 다시 눌러 음성을 허용하세요");
      while (playing && activeEpoch === epoch) {
        manifest = await fetchManifest(signal); if (activeEpoch !== epoch) return;
        render();
        const located = locateRecordedAudio(manifest.segments, position);
        if (!located) {
          const disposition = recordedAudioDisposition(manifest.state, broadcastPhase);
          if (disposition === "completed" || disposition === "interrupted") {
            cancel(); ended = true; desiredState = "stopped";
            playButton.disabled = false; pauseButton.disabled = true; stopButton.disabled = true;
            playButton.textContent = uiText("처음부터 다시 듣기");
            setStatus(disposition === "completed" ? "저장 음성 재생 완료" : "저장된 음성 끝 · 방송이 중단되었습니다", disposition === "completed" ? "idle" : "warning");
            return;
          }
          setStatus(disposition === "paused" ? "저장된 구간 끝 · 방송 재개 대기" : "저장된 구간 끝 · 새 음성 대기", "idle");
          await new Promise(resolve => { const timeout = setTimeout(resolve, 5000); finishWaiting = () => { clearTimeout(timeout); resolve(); }; }); if (activeEpoch !== epoch) return; finishWaiting = null; continue;
        }
        const s = located.segment;
        const count = Math.min(s.bytes - located.offset, 5 * s.sampleRate * 2, 262144);
        const path = `/api/replay/${s.part}/${s.segment}?channel=${encodeURIComponent(channelSelect.value)}&offset=${located.offset}&count=${count}`;
        const bytes = await withListenerRequestTimeout(async requestSignal => {
          const response = await fetch(path, { headers: authHeaders(), signal: requestSignal });
          if (!response.ok) throw new Error("재생 구간 수신 실패 · 다시 재생하세요");
          return response.arrayBuffer();
        }, signal); if (activeEpoch !== epoch) return;
        if (bytes.byteLength !== count || bytes.byteLength % 2) throw new Error("불완전한 녹음 구간");
        const pcm = new DataView(bytes), buffer = audio.createBuffer(1, bytes.byteLength / 2, s.sampleRate), samples = buffer.getChannelData(0);
        for (let n = 0; n < samples.length; n++) samples[n] = pcm.getInt16(n * 2, true) / 32768;
        source = audio.createBufferSource(); source.buffer = buffer; source.playbackRate.value = playbackRate; source.connect(audio.destination);
        scheduledStart = audio.currentTime; scheduledPosition = position; scheduledRate = playbackRate;
        setStatus("저장 음성 재생 중", "live");
        await new Promise(resolve => { finishWaiting = resolve; source.onended = resolve; source.start(); }); if (activeEpoch !== epoch) return; finishWaiting = null;
        if (activeEpoch !== epoch) return;
        source = null; position += buffer.duration; render();
      }
    } catch (error) {
      if (activeEpoch === epoch) {
        cancel(); desiredState = "paused"; playButton.disabled = false; pauseButton.disabled = true; stopButton.disabled = true;
        const messages = ["방송에 다시 입장하세요", "저장 구간을 가져오지 못했습니다", "저장 구간 정보 오류",
          "재생 버튼을 다시 눌러 음성을 허용하세요", "재생 구간 수신 실패 · 다시 재생하세요", "불완전한 녹음 구간"];
        setStatus(messages.includes(error?.message) ? error.message : "저장 음성 재생 실패 · 연결을 확인하고 다시 시도하세요", "error");
      }
    }
  }
  mode.addEventListener("change", () => { cancel(); stopPlayback("stopped"); controls.hidden = mode.value !== "replay"; liveEdgeButton.disabled = mode.value !== "replay";
    document.querySelector("#current-caption").textContent = uiText(mode.value === "replay" ? "저장 스크립트는 HUD 또는 통역 스크립트 탭에서 볼 수 있습니다." : "");
    if (mode.value === "replay") loadCaptions(true);
  });
  slider.addEventListener("input", () => { const resume = playing, requestedPosition = Number(slider.value); cancel(); ended = false; playButton.textContent = defaultPlayButtonText; position = requestedPosition; render(); if (resume) start(); });
  setInterval(() => { if (playing) render(); }, 500);
  window.addEventListener("pagehide", () => { cancel(); invalidateCaptions(); });
  document.querySelector("#transcript-scope").addEventListener("change", () => {
    invalidateCaptions();
    captionCursor = null; captionRows = []; captionError = ""; previousPages.length = 0;
    document.querySelector("#current-caption").textContent = "";
    loadTranscripts(true);
    if (mode.value === "replay") loadCaptions(true);
  });
  function previousCaptionPage() {
    if (!previousPages.length || captionPending) return;
    invalidateCaptions(); captionCursor = previousPages.pop() || null; loadCaptions(true);
  }
  function nextCaptionPage() {
    if (captionRows.length < 100 || captionPending) return;
    invalidateCaptions(); previousPages.push(captionCursor);
    const row = captionRows[captionRows.length - 1]; captionCursor = { part: row.part, sequence: row.sequence }; loadCaptions(true);
  }
  document.querySelector("#archive-first").addEventListener("click", () => { invalidateCaptions(); captionCursor = null; previousPages.length = 0; loadCaptions(true); });
  document.querySelector("#archive-prev").addEventListener("click", previousCaptionPage);
  document.querySelector("#archive-next").addEventListener("click", nextCaptionPage);
  globalThis.GuideCastReplay = {
    start, loadCaptions, previousCaptionPage, nextCaptionPage,
    captionSnapshot: () => ({ rows: captionRows.slice(), pending: Boolean(captionPending), error: captionError,
      hasPrevious: previousPages.length > 0, hasNext: captionRows.length === 100 }),
    pause: () => cancel(), stop: () => cancel(true),
    returnLive: () => {
      cancel(); mode.value = "live"; controls.hidden = true;
      document.querySelector("#current-caption").textContent = "";
      return startPlayback();
    },
    channelChanged: () => { const resume = playing; cancel(true); manifest = null; if (resume && mode.value === "replay") start(); }
  };
}
