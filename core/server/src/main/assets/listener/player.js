"use strict";

const listenerUi = globalThis.GuideCastI18n?.create(location.pathname);
const uiText = (text) => listenerUi ? listenerUi.text(text) : text;
if (listenerUi) globalThis.GuideCastI18n.apply(document, listenerUi);

const channelSelect = document.querySelector("#channel");
const playButton = document.querySelector("#play");
const pauseButton = document.querySelector("#pause");
const stopButton = document.querySelector("#stop");
const statusLabel = document.querySelector("#status");
const statusDot = document.querySelector("#status-dot");
const levelMeter = document.querySelector("#audio-level");
const diagnosticsLabel = document.querySelector("#diagnostics");
const pinDialog = document.querySelector("#pin-dialog");
const pinForm = document.querySelector("#pin-form");
const pinInput = document.querySelector("#pin");
const pinError = document.querySelector("#pin-error");
const playbackRateSelect = document.querySelector("#playback-rate");
const liveEdgeButton = document.querySelector("#live-edge");
const tabPlayer = document.querySelector("#tab-player");
const tabTranscript = document.querySelector("#tab-transcript");
const panelPlayer = document.querySelector("#panel-player");
const panelTranscript = document.querySelector("#panel-transcript");
const transcriptSelect = document.querySelector("#transcript-language");
const transcriptRefreshButton = document.querySelector("#transcript-refresh");
const transcriptFollow = document.querySelector("#transcript-follow");
const transcriptList = document.querySelector("#transcript-list");
const transcriptStatus = document.querySelector("#transcript-status");
const pageDescription = document.querySelector("#page-description");
const pinnedChannelBanner = document.querySelector("#pinned-channel");
const pinnedChannelName = document.querySelector("#pinned-channel-name");

const pinnedChannelId = (() => {
  const segments = location.pathname.split("/").filter(Boolean);
  if (segments.length !== 1) return "";
  try {
    return decodeURIComponent(segments[0]);
  } catch (_) {
    return "";
  }
})();

const fragmentToken = new URLSearchParams(location.hash.slice(1)).get("token") || "";
let accessToken = fragmentToken || sessionStorage.getItem("guidecast-token") || "";
let sessionAccessMode = "";
if (fragmentToken) {
  sessionStorage.setItem("guidecast-token", fragmentToken);
  history.replaceState(null, "", `${location.pathname}${location.search}`);
}

const languageLabels = {
  source: "원문",
  en: "영어",
  ja: "일본어",
  zh: "중국어(간체)",
  "zh-tw": "중국어(번체)",
  vi: "베트남어",
  nl: "네덜란드어",
  ar: "아랍어",
  es: "스페인어",
  ko: "한국어",
};
const activeChannelLabels = new Map();
const MAX_BUFFERED_AUDIO_SECONDS = 4;
const MAX_CACHED_TRANSCRIPT_CHARS = 512 * 1024;
const RECONNECT_BASE_DELAY_MS = 250;
const RECONNECT_MAX_DELAY_MS = 5000;

let socket = null;
let audioContext = null;
let gainNode = null;
let playbackGeneration = 0;
let desiredState = "stopped";
let nextPlayTime = 0;
let receivedFrames = 0;
let receivedBytes = 0;
let lastRms = 0;
let lastPeak = 0;
let activeSources = new Set();
let playbackRate = 1;
let automaticLiveEdgeDrops = 0;
let reconnectTimer = null;
let reconnectAttempt = 0;
let transcriptPollHandle = null;
let lastTranscriptPoll = 0;
let transcriptRequestInFlight = null;
let transcriptRequestScope = null;
let transcriptEtag = "";
let transcriptEtagScope = null;
let cachedTranscript = null;
let transcriptBackoffUntil = 0;
let transcriptBackoffScope = null;
let transientTranscriptFailureCount = 0;
let renderedTranscriptScope = null;
let isDocumentVisible = true;

function parseRetryAfterHeader(headerValue) {
  if (!headerValue) return 3000;
  const trimmed = String(headerValue).trim();
  const seconds = parseInt(trimmed, 10);
  if (/^\d+$/.test(trimmed) && Number.isFinite(seconds)) {
    return Math.min(60_000, Math.max(1000, seconds * 1000));
  }
  const parsedDate = Date.parse(trimmed);
  if (!Number.isNaN(parsedDate)) {
    const delta = parsedDate - Date.now();
    return Math.min(60_000, Math.max(1000, delta));
  }
  return 3000;
}

function authHeaders(token = accessToken) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function channelApiUrl(path) {
  return pinnedChannelId
    ? `${path}?channel=${encodeURIComponent(pinnedChannelId)}`
    : path;
}

function channelLabel(channelId) {
  return listenerUi?.name(channelId) || activeChannelLabels.get(channelId) || languageLabels[channelId] || channelId;
}

function setStatus(text, kind = "idle") {
  statusLabel.textContent = uiText(text);
  statusDot.className = `dot ${kind}`;
}

function switchTabs(nextPanel) {
  const playingPanel = nextPanel === "transcript" ? panelTranscript : panelPlayer;
  const hiddenPanel = nextPanel === "transcript" ? panelPlayer : panelTranscript;
  const activeTab = nextPanel === "transcript" ? tabTranscript : tabPlayer;
  const inactiveTab = nextPanel === "transcript" ? tabPlayer : tabTranscript;

  hiddenPanel.hidden = true;
  playingPanel.hidden = false;
  activeTab.classList.add("active");
  inactiveTab.classList.remove("active");
  activeTab.setAttribute("aria-selected", "true");
  inactiveTab.setAttribute("aria-selected", "false");
  activeTab.tabIndex = 0;
  inactiveTab.tabIndex = -1;
}

function updateTranscriptLanguageOptions(channels) {
  const existing = transcriptSelect.value;
  transcriptSelect.replaceChildren();

  if (pinnedChannelId) {
    const channel = channels.find((candidate) => candidate.id === pinnedChannelId);
    if (!channel) {
      transcriptSelect.disabled = true;
      return;
    }
    const option = document.createElement("option");
    option.value = channel.id === "source" ? "source" : channel.id;
    option.textContent = channel.id === "source"
      ? channelLabel("source")
      : `${channelLabel(channel.id)} (${channel.languageTag})`;
    transcriptSelect.append(option);
    transcriptSelect.value = option.value;
    if (channel.id !== "source") {
      const originalOption = document.createElement("option");
      originalOption.value = "source";
      originalOption.textContent = channelLabel("source");
      transcriptSelect.append(originalOption);
      if (existing === "source") transcriptSelect.value = "source";
    }
    transcriptSelect.disabled = channel.id === "source";
    return;
  }

  transcriptSelect.disabled = false;
  const sourceOption = document.createElement("option");
  sourceOption.value = "source";
  sourceOption.textContent = channelLabel("source");
  transcriptSelect.append(sourceOption);

  const translationLanguages = [];
  for (const channel of channels) {
    if (channel.id !== "source") {
      translationLanguages.push(channel.id);
      const option = document.createElement("option");
      option.value = channel.id;
      option.textContent = `${channelLabel(channel.id)} (${channel.languageTag})`;
      transcriptSelect.append(option);
    }
  }

  const allOption = document.createElement("option");
  allOption.value = "all";
  allOption.textContent = uiText("전체 번역");
  if (translationLanguages.length > 0) transcriptSelect.append(allOption);

  if (translationLanguages.some((language) => language === existing) || existing === "source") {
    transcriptSelect.value = existing;
  } else {
    transcriptSelect.value = translationLanguages.length > 0 ? translationLanguages[0] : "source";
  }
}

function setDiagnostics() {
  levelMeter.value = Math.min(100, Math.round(lastRms * 500));
  const audioState = audioContext ? audioContext.state : "closed";
  const bufferedSeconds = audioContext ? Math.max(0, nextPlayTime - audioContext.currentTime) : 0;
  const recovery = automaticLiveEdgeDrops > 0
    ? ` · 자동 실시간 복귀 ${automaticLiveEdgeDrops}회`
    : "";
  diagnosticsLabel.textContent = uiText(`오디오 ${audioState} · ${receivedFrames} 프레임 · 지연 ${bufferedSeconds.toFixed(1)}초 · ${playbackRate.toFixed(2)}×${recovery}`);
  liveEdgeButton.disabled = desiredState !== "playing" || bufferedSeconds < 0.35;
}

function setTranscriptStale(isStale, message = "스크립트 갱신 지연 · 이전 내용 유지") {
  if (transcriptStatus) {
    if (isStale) {
      transcriptStatus.textContent = uiText(message);
      transcriptStatus.hidden = false;
    } else {
      transcriptStatus.textContent = "";
      transcriptStatus.hidden = true;
    }
  }
  if (transcriptList) {
    if (isStale) {
      transcriptList.classList.add("is-stale");
    } else {
      transcriptList.classList.remove("is-stale");
    }
  }
}

function renderNoTranscript(message) {
  renderedTranscriptScope = null;
  setTranscriptStale(false);
  const line = document.createElement("p");
  line.className = "transcript-empty";
  line.textContent = uiText(message);
  transcriptList.replaceChildren(line);
}

function formatLatency(value) {
  return typeof value === "number" && Number.isFinite(value) ? `${value}ms` : null;
}

function formatFirstAudioLatency(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return uiText(`확정 후 첫 음성 ${value}ms (${value <= 2000 ? "2초 목표 이내" : "2초 초과"})`);
}

function renderTranscripts(responseText) {
  const previousScrollTop = transcriptList.scrollTop;
  const nearBottom = transcriptList.scrollHeight - transcriptList.scrollTop -
    transcriptList.clientHeight < 56;
  let payload = null;
  try {
    payload = JSON.parse(responseText);
  } catch (_) {
    renderNoTranscript("스크립트 수신 형식이 올바르지 않습니다.");
    return false;
  }

  const lines = Array.isArray(payload) ? payload : (payload?.transcripts || []);
  if (!Array.isArray(lines) || lines.length === 0) {
    renderNoTranscript("아직 수신한 스크립트가 없습니다.");
    return true;
  }

  const selectedLanguage = transcriptSelect.value;
  const fragment = document.createDocumentFragment();
  for (const line of lines) {
    if (!line || typeof line !== "object") continue;
    const row = document.createElement("article");
    row.className = "transcript-row";

    const source = typeof line.sourceText === "string" ? line.sourceText : "";
    const isFinal = line.isFinal;
    const translations = line.translations || {};
    const translationLatencies = line.translationLatencyMillis || {};
    const firstAudioLatencies = line.firstAudioLatencyMillis || {};
    const synthesisLatencies = line.synthesisLatencyMillis || {};

    const sourceBlock = document.createElement("p");
    sourceBlock.className = "source-text";
    sourceBlock.textContent = `${uiText("원문")}: ${source}`;
    row.append(sourceBlock);

    if (selectedLanguage === "source") {
      const translatedText = document.createElement("p");
      translatedText.className = "translated-text";
      translatedText.textContent = uiText(`선택 표시: ${isFinal ? "확정" : "진행 중"}`);
      row.append(translatedText);
    } else if (selectedLanguage === "all") {
      const translationBlock = document.createElement("div");
      translationBlock.className = "translation-block";
      const keys = Object.keys(translations);
      if (keys.length === 0) {
        const empty = document.createElement("p");
        empty.className = "translated-text";
        empty.textContent = uiText("번역 텍스트가 없습니다.");
        translationBlock.append(empty);
      } else {
        for (const key of keys) {
          const translated = document.createElement("p");
          translated.className = "translated-text";
          const translateMs = formatLatency(translationLatencies[key]);
          const firstAudioMs = formatFirstAudioLatency(firstAudioLatencies[key]);
          const speechMs = formatLatency(synthesisLatencies[key]);
          const latency = [
            translateMs && `${uiText("번역")} ${translateMs}`,
            firstAudioMs,
            speechMs && `${uiText("합성")} ${speechMs}`,
          ].filter(Boolean).join(" · ");
          translated.textContent = `${channelLabel(key)}: ${translations[key]}${latency ? ` (${latency})` : ""}`;
          translationBlock.append(translated);
        }
      }
      row.append(translationBlock);
    } else {
      const translated = document.createElement("p");
      translated.className = "translated-text";
      const translatedText = translations[selectedLanguage];
      if (translatedText) {
        const translateMs = formatLatency(translationLatencies[selectedLanguage]);
        const firstAudioMs = formatFirstAudioLatency(firstAudioLatencies[selectedLanguage]);
        const speechMs = formatLatency(synthesisLatencies[selectedLanguage]);
        const latency = [
          translateMs && `${uiText("번역")} ${translateMs}`,
          firstAudioMs,
          speechMs && `${uiText("합성")} ${speechMs}`,
        ].filter(Boolean).join(" · ");
        translated.textContent = `${channelLabel(selectedLanguage)}: ${translatedText}${latency ? ` (${latency})` : ""}`;
      } else {
        translated.textContent = `${channelLabel(selectedLanguage)}: ${uiText("아직 번역이 없습니다.")}`;
      }
      row.append(translated);
    }

    const state = document.createElement("p");
    state.className = "transcript-state";
    state.textContent = uiText(isFinal ? "확정" : "받는 중");
    row.append(state);

    fragment.append(row);
  }

  transcriptList.replaceChildren(fragment);
  if (transcriptFollow.checked && nearBottom) {
    transcriptList.scrollTop = transcriptList.scrollHeight;
  } else {
    transcriptList.scrollTop = previousScrollTop;
  }
  return true;
}

function currentTranscriptScope() {
  return {
    accessToken,
    url: channelApiUrl("/api/transcripts"),
  };
}

function isSameTranscriptScope(left, right) {
  return Boolean(left && right) &&
    left.accessToken === right.accessToken &&
    left.url === right.url;
}

function renderCachedTranscript(scope) {
  if (!cachedTranscript || !isSameTranscriptScope(cachedTranscript.scope, scope)) return false;
  const rendered = renderTranscripts(cachedTranscript.payload);
  if (rendered) {
    renderedTranscriptScope = scope;
  }
  return rendered;
}

function clearTranscriptSnapshot() {
  transcriptEtag = "";
  transcriptEtagScope = null;
  cachedTranscript = null;
  renderedTranscriptScope = null;
  setTranscriptStale(false);
}

function loadTranscripts(forceRefresh = false) {
  const scope = currentTranscriptScope();

  if (transcriptBackoffScope && !isSameTranscriptScope(transcriptBackoffScope, scope)) {
    transcriptBackoffUntil = 0;
    transcriptBackoffScope = null;
    transientTranscriptFailureCount = 0;
  }

  if (renderedTranscriptScope && !isSameTranscriptScope(renderedTranscriptScope, scope)) {
    clearTranscriptSnapshot();
    renderNoTranscript("아직 수신한 스크립트가 없습니다.");
  } else if (cachedTranscript && !isSameTranscriptScope(cachedTranscript.scope, scope)) {
    clearTranscriptSnapshot();
  }

  if (forceRefresh) renderCachedTranscript(scope);
  if (transcriptRequestInFlight) {
    if (isSameTranscriptScope(transcriptRequestScope, scope)) return transcriptRequestInFlight;
    return transcriptRequestInFlight.then(() => loadTranscripts(forceRefresh));
  }
  const now = Date.now();
  if (now < transcriptBackoffUntil) {
    return Promise.resolve();
  }
  if (!forceRefresh) {
    if (typeof document !== "undefined" && document.visibilityState === "hidden") {
      return Promise.resolve();
    }
    if (now - lastTranscriptPoll < 900) {
      return Promise.resolve();
    }
  }
  lastTranscriptPoll = now;

  const request = (async () => {
    const headers = authHeaders(scope.accessToken);
    if (transcriptEtag && isSameTranscriptScope(transcriptEtagScope, scope)) {
      headers["If-None-Match"] = transcriptEtag;
    }
    try {
      const response = await fetch(scope.url, {
        cache: "no-cache",
        headers,
      });
      if (!isSameTranscriptScope(currentTranscriptScope(), scope)) return;
      if (response.status === 401 || response.status === 403) {
        transcriptBackoffUntil = 0;
        transcriptBackoffScope = null;
        transientTranscriptFailureCount = 0;
        clearTranscriptSnapshot();
        renderNoTranscript("아직 수신한 스크립트가 없습니다.");
        return;
      }
      if (response.status === 429) {
        const retryHeader = response.headers?.get("Retry-After");
        const backoffMs = parseRetryAfterHeader(retryHeader);
        transcriptBackoffUntil = Date.now() + backoffMs;
        transcriptBackoffScope = scope;
        if (cachedTranscript && isSameTranscriptScope(cachedTranscript.scope, scope)) {
          setTranscriptStale(true);
          return;
        }
        clearTranscriptSnapshot();
        renderNoTranscript("스크립트를 읽지 못했습니다.");
        return;
      }
      if (response.status === 304) {
        transcriptBackoffUntil = 0;
        transcriptBackoffScope = null;
        transientTranscriptFailureCount = 0;
        setTranscriptStale(false);
      }
      if (response.status === 304) return;
      if (!response.ok) {
        transientTranscriptFailureCount += 1;
        const backoffMs = Math.min(10_000, 1000 * (2 ** Math.min(transientTranscriptFailureCount - 1, 3)));
        transcriptBackoffUntil = Date.now() + backoffMs;
        transcriptBackoffScope = scope;
        if (cachedTranscript && isSameTranscriptScope(cachedTranscript.scope, scope)) {
          setTranscriptStale(true);
          return;
        }
        clearTranscriptSnapshot();
        renderNoTranscript("스크립트를 읽지 못했습니다.");
        return;
      }
      const payload = await response.text();
      if (!isSameTranscriptScope(currentTranscriptScope(), scope)) return;
      if (renderTranscripts(payload) && payload.length <= MAX_CACHED_TRANSCRIPT_CHARS) {
        cachedTranscript = { payload, scope };
        transcriptEtag = response.headers?.get("ETag") || "";
        transcriptEtagScope = scope;
        renderedTranscriptScope = scope;
        transcriptBackoffUntil = 0;
        transcriptBackoffScope = null;
        transientTranscriptFailureCount = 0;
        setTranscriptStale(false);
      } else {
        clearTranscriptSnapshot();
      }
    } catch (error) {
      if (!isSameTranscriptScope(currentTranscriptScope(), scope)) return;
      transientTranscriptFailureCount += 1;
      const backoffMs = Math.min(10_000, 1000 * (2 ** Math.min(transientTranscriptFailureCount - 1, 3)));
      transcriptBackoffUntil = Date.now() + backoffMs;
      transcriptBackoffScope = scope;
      if (cachedTranscript && isSameTranscriptScope(cachedTranscript.scope, scope)) {
        setTranscriptStale(true);
        return;
      }
      clearTranscriptSnapshot();
      renderNoTranscript(error.message || "스크립트 수신 오류");
    }
  })();
  transcriptRequestInFlight = request;
  transcriptRequestScope = scope;
  const clearInFlight = () => {
    if (transcriptRequestInFlight === request) {
      transcriptRequestInFlight = null;
      transcriptRequestScope = null;
    }
  };
  request.then(clearInFlight, clearInFlight);
  return request;
}

function startTranscriptPolling() {
  if (transcriptPollHandle) return;
  loadTranscripts(true);
  transcriptPollHandle = setInterval(() => {
    loadTranscripts();
  }, 1200);
}

function stopTranscriptPolling() {
  if (!transcriptPollHandle) return;
  clearInterval(transcriptPollHandle);
  transcriptPollHandle = null;
}

function setupTabEvents() {
  tabPlayer.addEventListener("click", () => {
    switchTabs("player");
    stopTranscriptPolling();
    transcriptRefreshButton.disabled = false;
  });
  tabTranscript.addEventListener("click", () => {
    switchTabs("transcript");
    startTranscriptPolling();
    loadTranscripts(true);
    transcriptRefreshButton.disabled = false;
  });
  transcriptRefreshButton.addEventListener("click", () => loadTranscripts(true));
  transcriptSelect.addEventListener("change", () => loadTranscripts(true));
  transcriptFollow.addEventListener("change", () => {
    if (transcriptFollow.checked) transcriptList.scrollTop = transcriptList.scrollHeight;
  });
  for (const tab of [tabPlayer, tabTranscript]) {
    tab.addEventListener("keydown", (event) => {
      if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
      event.preventDefault();
      const nextPanel = tab === tabPlayer ? "transcript" : "player";
      switchTabs(nextPanel);
      (nextPanel === "transcript" ? tabTranscript : tabPlayer).focus();
      if (nextPanel === "transcript") {
        startTranscriptPolling();
      } else {
        stopTranscriptPolling();
      }
    });
  }
  if (typeof document !== "undefined" && typeof document.addEventListener === "function") {
    document.addEventListener("visibilitychange", () => {
      isDocumentVisible = document.visibilityState !== "hidden";
      if (isDocumentVisible && panelTranscript && !panelTranscript.hidden) {
        loadTranscripts(true);
      }
    });
  }
}

async function initialize() {
  try {
    const sessionResponse = await fetch("/api/session", { cache: "no-store" });
    if (!sessionResponse.ok) throw new Error("방송 정보를 읽지 못했습니다.");
    const session = await sessionResponse.json();
    sessionAccessMode = session.access;
    if (session.access === "pin" && !accessToken) {
      showPinDialog();
      return;
    }
    await loadChannels();
  } catch (error) {
    setStatus(error.message || "연결 실패", "error");
  }
}

async function loadChannels() {
  const response = await fetch(channelApiUrl("/api/status"), {
    cache: "no-store",
    headers: authHeaders(),
  });
  if (response.status === 401 && sessionAccessMode === "pin") {
    accessToken = "";
    clearTranscriptSnapshot();
    sessionStorage.removeItem("guidecast-token");
    showPinDialog("입장 정보가 만료됐습니다. PIN을 다시 입력하세요.");
    return;
  }
  if (response.status === 401) throw new Error("방송 입장 정보가 올바르지 않습니다.");
  if (!response.ok) throw new Error("방송 상태를 읽지 못했습니다.");
  const status = await response.json();
  const channels = Array.isArray(status.channels) ? status.channels : [];
  activeChannelLabels.clear();
  for (const channel of channels) {
    activeChannelLabels.set(channel.id, channel.name);
  }

  if (pinnedChannelId && !channels.some((channel) => channel.id === pinnedChannelId)) {
    throw new Error("선택한 통역 채널이 방송 중이 아닙니다.");
  }

  channelSelect.replaceChildren(...channels.map((channel) => {
    const option = document.createElement("option");
    option.value = channel.id;
    option.textContent = `${channelLabel(channel.id)} (${channel.languageTag})`;
    return option;
  }));
  channelSelect.disabled = channels.length < 2;
  playButton.disabled = channels.length === 0;
  updateTranscriptLanguageOptions(channels);
  if (pinnedChannelId) {
    const channel = channels.find((candidate) => candidate.id === pinnedChannelId);
    channelSelect.value = channel.id;
    pinnedChannelName.textContent = `${channelLabel(channel.id)} · ${channel.languageTag}`;
    pinnedChannelBanner.hidden = false;
    pageDescription.textContent = uiText("가이드가 송출 중인 언어를 고르고 재생을 누르세요.");
    document.title = `${channelLabel(channel.id)} · ${uiText("MCastTalk")}`;
  }
  setStatus(channels.length ? "재생을 눌러 청취하세요" : "송출 채널 대기 중");
  setDiagnostics();
}

function showPinDialog(message = "") {
  pinError.textContent = uiText(message);
  if (!pinDialog.open) pinDialog.showModal();
  pinInput.focus();
}

pinForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  pinError.textContent = "";
  try {
    const response = await fetch("/api/join", {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "text/plain;charset=UTF-8" },
      body: pinInput.value,
    });
    if (response.status === 429) throw new Error("입력 횟수가 많습니다. 잠시 후 다시 시도하세요.");
    if (!response.ok) throw new Error("PIN이 올바르지 않습니다.");
    accessToken = await response.text();
    clearTranscriptSnapshot();
    sessionStorage.setItem("guidecast-token", accessToken);
    pinInput.value = "";
    pinDialog.close();
    await loadChannels();
  } catch (error) {
    pinError.textContent = uiText(error.message || "입장하지 못했습니다.");
  }
});

playButton.addEventListener("click", startPlayback);
pauseButton.addEventListener("click", () => stopPlayback("paused"));
stopButton.addEventListener("click", () => stopPlayback("stopped"));
playbackRateSelect.addEventListener("change", () => {
  const requested = Number(playbackRateSelect.value);
  playbackRate = Number.isFinite(requested) ? Math.min(1.5, Math.max(0.75, requested)) : 1;
  setDiagnostics();
});
liveEdgeButton.addEventListener("click", jumpToLiveEdge);
channelSelect.addEventListener("change", () => {
  if (desiredState === "playing") startPlayback();
});

async function startPlayback() {
  stopRuntime();
  desiredState = "playing";
  const generation = ++playbackGeneration;
  const requestedChannel = channelSelect.value;
  const requestToken = accessToken;
  playButton.disabled = true;
  pauseButton.disabled = false;
  stopButton.disabled = false;
  setStatus("방송 연결 중");

  let playbackRequest = null;
  let requestAudioContext = null;
  let requestGainNode = null;
  try {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) throw new Error("이 브라우저는 오디오 재생을 지원하지 않습니다.");

    requestAudioContext = new AudioContextClass({ latencyHint: "interactive" });
    requestGainNode = requestAudioContext.createGain();
    requestGainNode.gain.value = 1;
    requestGainNode.connect(requestAudioContext.destination);
    audioContext = requestAudioContext;
    gainNode = requestGainNode;

    await requestAudioContext.resume();
    if (!isCurrentPlaybackRequest(
      generation,
      requestedChannel,
      requestAudioContext,
      requestGainNode,
    )) {
      disposePlaybackRequest(null, requestAudioContext, requestGainNode);
      return;
    }
    if (requestAudioContext.state !== "running") {
      throw new Error("브라우저 오디오가 차단되었습니다. 미디어 음량을 확인하고 다시 재생하세요.");
    }

    playbackRequest = {
      generation,
      channelId: requestedChannel,
      token: requestToken,
      socket: null,
      audioContext: requestAudioContext,
      gainNode: requestGainNode,
      sourceSampleRate: 24000,
      resamplePhase: 0,
      lastSample: null,
    };
    connectPlaybackSocket(playbackRequest);

    setDiagnostics();
  } catch (error) {
    const requestSocket = playbackRequest?.socket || null;
    if (!isCurrentPlaybackRequest(
      generation,
      requestedChannel,
      requestAudioContext,
      requestGainNode,
      requestSocket,
    )) {
      disposePlaybackRequest(requestSocket, requestAudioContext, requestGainNode);
      return;
    }
    desiredState = "stopped";
    stopRuntime();
    playButton.disabled = false;
    pauseButton.disabled = true;
    stopButton.disabled = true;
    setStatus(error.message || "오디오 재생을 시작하지 못했습니다.", "error");
  }
}

function connectPlaybackSocket(request) {
  if (!isCurrentPlaybackRequest(
    request.generation,
    request.channelId,
    request.audioContext,
    request.gainNode,
  )) return;
  if (request.socket || socket) return;

  const scheme = location.protocol === "https:" ? "wss" : "ws";
  const tokenQuery = request.token ? `?token=${encodeURIComponent(request.token)}` : "";
  let targetSocket = null;
  try {
    targetSocket = new WebSocket(
      `${scheme}://${location.host}/ws/${encodeURIComponent(request.channelId)}${tokenQuery}`,
    );
  } catch (_) {
    schedulePlaybackReconnect(request);
    return;
  }

  request.socket = targetSocket;
  socket = targetSocket;
  targetSocket.binaryType = "arraybuffer";
  targetSocket.onopen = () => {
    if (!isCurrentPlaybackRequest(
      request.generation,
      request.channelId,
      request.audioContext,
      request.gainNode,
      targetSocket,
    )) return;
    setStatus("연결됨 · 음성 데이터 대기 중");
  };
  targetSocket.onmessage = (event) => {
    if (!isCurrentPlaybackRequest(
      request.generation,
      request.channelId,
      request.audioContext,
      request.gainNode,
      targetSocket,
    )) return;
    // A server config or PCM frame proves that the reconnected stream is usable. Merely opening
    // a socket is not enough: an authorization/policy close may follow immediately and must keep
    // backing off instead of retrying four times per second forever.
    reconnectAttempt = 0;
    handleAudioMessage(event, request);
  };
  targetSocket.onerror = () => {
    if (!isCurrentPlaybackRequest(
      request.generation,
      request.channelId,
      request.audioContext,
      request.gainNode,
      targetSocket,
    )) return;
    setStatus("방송 연결 오류 · 재연결 대기", "warning");
  };
  targetSocket.onclose = () => {
    if (!isCurrentPlaybackRequest(
      request.generation,
      request.channelId,
      request.audioContext,
      request.gainNode,
      targetSocket,
    )) return;
    detachSocketHandlers(targetSocket);
    request.socket = null;
    socket = null;
    schedulePlaybackReconnect(request);
  };
}

function schedulePlaybackReconnect(request) {
  if (reconnectTimer !== null || !isCurrentPlaybackRequest(
    request.generation,
    request.channelId,
    request.audioContext,
    request.gainNode,
  )) return;

  const delayMillis = Math.min(
    RECONNECT_MAX_DELAY_MS,
    RECONNECT_BASE_DELAY_MS * (2 ** Math.min(reconnectAttempt, 8)),
  );
  reconnectAttempt += 1;
  const delaySeconds = delayMillis >= 1000
    ? `${(delayMillis / 1000).toFixed(1)}초`
    : `${delayMillis}ms`;
  setStatus(`방송 연결 끊김 · ${delaySeconds} 후 재연결`, "warning");
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (!isCurrentPlaybackRequest(
      request.generation,
      request.channelId,
      request.audioContext,
      request.gainNode,
    )) return;
    connectPlaybackSocket(request);
  }, delayMillis);
}

function cancelPlaybackReconnect() {
  if (reconnectTimer !== null) clearTimeout(reconnectTimer);
  reconnectTimer = null;
  reconnectAttempt = 0;
}

function isCurrentPlaybackRequest(
  generation,
  channelId,
  requestAudioContext,
  requestGainNode,
  requestSocket = null,
) {
  return generation === playbackGeneration &&
    desiredState === "playing" &&
    channelSelect.value === channelId &&
    (!requestAudioContext || audioContext === requestAudioContext) &&
    (!requestGainNode || gainNode === requestGainNode) &&
    (!requestSocket || socket === requestSocket);
}

function detachSocketHandlers(targetSocket) {
  targetSocket.onopen = null;
  targetSocket.onmessage = null;
  targetSocket.onerror = null;
  targetSocket.onclose = null;
}

function disposePlaybackRequest(targetSocket, targetAudioContext, targetGainNode) {
  if (targetSocket) {
    detachSocketHandlers(targetSocket);
    runSafely(() => targetSocket.close());
  }
  if (targetGainNode) runSafely(() => targetGainNode.disconnect());
  if (targetAudioContext) runSafely(() => targetAudioContext.close());
}

function handleAudioMessage(event, request) {
  if (!isCurrentPlaybackRequest(
    request.generation,
    request.channelId,
    request.audioContext,
    request.gainNode,
    request.socket,
  )) return;

  if (typeof event.data === "string") {
    try {
      const config = JSON.parse(event.data);
      if (config.type === "config" && Number.isFinite(config.sampleRate)) {
        request.sourceSampleRate = config.sampleRate;
      }
    } catch (_) {
      setStatus("방송 형식 오류", "error");
    }
    return;
  }
  if (request.audioContext.state !== "running") {
    setStatus("브라우저 오디오가 멈췄습니다. 재생을 다시 누르세요.", "error");
    return;
  }

  const view = new DataView(event.data);
  if (view.byteLength === 0 || view.byteLength % 2 !== 0) return;

  const samples = new Float32Array(view.byteLength / 2);
  let sumSquares = 0;
  let peak = 0;
  for (let index = 0; index < samples.length; index += 1) {
    const sample = view.getInt16(index * 2, true) / 32768;
    samples[index] = sample;
    sumSquares += sample * sample;
    peak = Math.max(peak, Math.abs(sample));
  }
  lastRms = Math.sqrt(sumSquares / samples.length);
  lastPeak = peak;
  receivedFrames += 1;
  receivedBytes += view.byteLength;

  const playableSamples = resample(
    samples,
    request.sourceSampleRate,
    request.audioContext.sampleRate,
    request,
  );
  const returnedToLive = scheduleSamples(playableSamples, request);

  if (returnedToLive) {
    setStatus(
      `누적 지연 ${MAX_BUFFERED_AUDIO_SECONDS}초 상한 · 현재 방송으로 자동 복귀`,
      "warning",
    );
  } else if (lastRms >= 0.002 || lastPeak >= 0.01) {
    setStatus("음성 수신·재생 중", "live");
  } else if (receivedFrames >= 8) {
    setStatus("무음 데이터 수신 중 · 송출기 마이크를 확인하세요", "warning");
  }
  setDiagnostics();
}

function scheduleSamples(samples, request) {
  if (!samples.length || !isCurrentPlaybackRequest(
    request.generation,
    request.channelId,
    request.audioContext,
    request.gainNode,
    request.socket,
  )) return false;
  let now = request.audioContext.currentTime;

  // TTS returns a completed phrase as a fast burst of 20 ms PCM frames. Every frame must remain
  // behind the previous frame. When the explicit queue bound is crossed, every scheduled source
  // is stopped before the clock moves to the live edge; resetting the clock without stopping them
  // would overlap PCM and recreate the fast, high-pitched chirp defect.
  const deviceLead = Number.isFinite(request.audioContext.baseLatency)
    ? request.audioContext.baseLatency + 0.02
    : 0.06;
  const minimumLead = Math.max(0.06, deviceLead);
  const maxFrameSamples = Math.max(
    1,
    Math.floor(
      (MAX_BUFFERED_AUDIO_SECONDS - minimumLead) *
      request.audioContext.sampleRate * playbackRate,
    ),
  );
  let boundedSamples = samples;
  let returnedToLive = false;
  if (boundedSamples.length > maxFrameSamples) {
    boundedSamples = boundedSamples.subarray(boundedSamples.length - maxFrameSamples);
    discardScheduledAudio(request.audioContext);
    automaticLiveEdgeDrops += 1;
    returnedToLive = true;
    now = request.audioContext.currentTime;
  }

  const isContinuous = nextPlayTime > now + 0.005;
  let startAt = Math.max(nextPlayTime, isContinuous ? nextPlayTime : now + minimumLead);
  const playbackDuration = boundedSamples.length /
    request.audioContext.sampleRate / playbackRate;
  if (startAt + playbackDuration - now > MAX_BUFFERED_AUDIO_SECONDS) {
    discardScheduledAudio(request.audioContext);
    automaticLiveEdgeDrops += 1;
    returnedToLive = true;
    now = request.audioContext.currentTime;
    startAt = now + minimumLead;
  }

  const buffer = request.audioContext.createBuffer(
    1,
    boundedSamples.length,
    request.audioContext.sampleRate,
  );
  buffer.copyToChannel(boundedSamples, 0);
  const source = request.audioContext.createBufferSource();
  source.buffer = buffer;
  source.playbackRate.value = playbackRate;
  source.connect(request.gainNode);
  activeSources.add(source);
  source.onended = () => {
    activeSources.delete(source);
    runSafely(() => source.disconnect());
  };
  source.start(startAt);
  nextPlayTime = startAt + (buffer.duration / playbackRate);
  return returnedToLive;
}

function jumpToLiveEdge() {
  if (!audioContext || desiredState !== "playing") return;
  discardScheduledAudio(audioContext);
  nextPlayTime = audioContext.currentTime + 0.04;
  setStatus("현재 방송으로 이동했습니다", "live");
  setDiagnostics();
}

function discardScheduledAudio(targetAudioContext) {
  activeSources.forEach((source) => runSafely(() => source.stop()));
  activeSources.clear();
  nextPlayTime = targetAudioContext.currentTime;
}

function resample(source, sourceRate, targetRate, request = null) {
  if (sourceRate === targetRate || source.length < 2) {
    if (request && source.length > 0) request.lastSample = source[source.length - 1];
    return source;
  }
  const outputLength = Math.max(1, Math.floor(source.length * targetRate / sourceRate));
  const output = new Float32Array(outputLength);
  const ratio = sourceRate / targetRate;
  for (let index = 0; index < outputLength; index += 1) {
    const position = index * ratio;
    const left = Math.floor(position);
    const right = left + 1;
    const fraction = position - left;
    const leftSample = left < source.length ? source[left] : source[source.length - 1];
    const rightSample = right < source.length
      ? source[right]
      : (leftSample + (leftSample - (left > 0 ? source[left - 1] : leftSample)));
    output[index] = leftSample + (rightSample - leftSample) * fraction;
  }
  if (request && source.length > 0) {
    request.lastSample = source[source.length - 1];
  }
  return output;
}

function stopPlayback(nextState) {
  desiredState = nextState;
  stopRuntime();
  playButton.disabled = false;
  pauseButton.disabled = true;
  stopButton.disabled = true;
  setStatus(nextState === "paused" ? "일시정지됨" : "재생 중지됨");
  setDiagnostics();
}

function stopRuntime() {
  playbackGeneration += 1;
  cancelPlaybackReconnect();

  const socketToClose = socket;
  socket = null;
  if (socketToClose) {
    detachSocketHandlers(socketToClose);
    runSafely(() => socketToClose.close());
  }
  activeSources.forEach((source) => runSafely(() => source.stop()));
  activeSources.clear();
  const gainToDisconnect = gainNode;
  gainNode = null;
  if (gainToDisconnect) {
    runSafely(() => gainToDisconnect.disconnect());
  }
  const contextToClose = audioContext;
  audioContext = null;
  if (contextToClose) {
    runSafely(() => contextToClose.close());
  }
  nextPlayTime = 0;
  receivedFrames = 0;
  receivedBytes = 0;
  lastRms = 0;
  lastPeak = 0;
  automaticLiveEdgeDrops = 0;
}

function runSafely(block) {
  try { block(); } catch (_) {}
}

window.__guideCastDiagnostics = () => ({
  desiredState,
  audioContextState: audioContext ? audioContext.state : "closed",
  receivedFrames,
  receivedBytes,
  rms: lastRms,
  peak: lastPeak,
  scheduledSources: activeSources.size,
  playbackRate,
  bufferedSeconds: audioContext ? Math.max(0, nextPlayTime - audioContext.currentTime) : 0,
  automaticLiveEdgeDrops,
  maxBufferedAudioSeconds: MAX_BUFFERED_AUDIO_SECONDS,
  reconnectAttempt,
  reconnectPending: reconnectTimer !== null,
});

switchTabs("player");
setupTabEvents();
initialize();
