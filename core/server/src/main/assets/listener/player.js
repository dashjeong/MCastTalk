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
const developerInformationToggle = document.querySelector("#developer-information");
const DEVELOPER_INFORMATION_PREFERENCE = "mcasttalk-developer-information";
let developerInformationEnabled = false;
try {
  developerInformationEnabled = localStorage.getItem(DEVELOPER_INFORMATION_PREFERENCE) === "true";
} catch (_) { /* Private browsing can disable storage; listening still works. */ }
developerInformationToggle.checked = developerInformationEnabled;
diagnosticsLabel.hidden = !developerInformationEnabled;
const pinDialog = document.querySelector("#pin-dialog");
const pinForm = document.querySelector("#pin-form");
const pinInput = document.querySelector("#pin");
const pinError = document.querySelector("#pin-error");
const playbackRateSelect = document.querySelector("#playback-rate");
const liveEdgeButton = document.querySelector("#live-edge");
const defaultPlayButtonText = playButton.textContent;
const defaultLiveEdgeButtonText = liveEdgeButton.textContent;
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
let listenerHud = null;

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
function readSessionToken() {
  try { return sessionStorage.getItem("guidecast-token") || ""; }
  catch (_) { return ""; }
}
function saveSessionToken(token) {
  try {
    if (token) sessionStorage.setItem("guidecast-token", token);
    else sessionStorage.removeItem("guidecast-token");
  } catch (_) { /* Restricted storage: credentials remain in this page's memory only. */ }
}
let accessToken = fragmentToken || readSessionToken();
let sessionAccessMode = "";
if (fragmentToken) {
  saveSessionToken(fragmentToken);
  history.replaceState(null, "", `${location.pathname}${location.search}`);
}

window.addEventListener("hashchange", () => {
  const nextToken = new URLSearchParams(location.hash.slice(1)).get("token");
  if (nextToken && nextToken !== accessToken) location.reload();
});

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
const BUFFER_WARNING_SECONDS = 4;
const MAX_BUFFERED_AUDIO_SECONDS = 20;
// Bound decoding/resampling allocations before converting a binary frame. The wire buffer
// itself is allocated by the browser; the server must also enforce a WebSocket message limit.
const MAX_PCM_FRAME_BYTES = 1024 * 1024;
const MAX_PCM_FRAME_SECONDS = 10;
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
let rejectedAudioSeconds = 0;
let skippedAudioSeconds = 0;
let outputOverloaded = false;
// These loss counters describe this page session and survive pause/reconnect/channel changes.
let unreceivedAudioUnknown = false;
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
let broadcastPhase = null;
let broadcastNextAction = "NONE";
let liveAudioEnabled = null;
let liveAudioEpoch = null;
const channelReadiness = new Map();
let statusRequestInFlight = null;
let statusPollHandle = null;
let statusRefreshFailed = false;
let statusConnectionLost = false;
let statusBackoffUntil = 0;
let statusBackoffScope = null;

async function withListenerRequestTimeout(request, parentSignal = null, timeoutMillis = 8000) {
  const controller = new AbortController();
  let rejectDeadline;
  const deadline = new Promise((_, reject) => { rejectDeadline = reject; });
  const abort = () => {
    controller.abort();
    rejectDeadline(new Error("Listener request interrupted"));
  };
  if (parentSignal?.aborted) abort();
  else parentSignal?.addEventListener("abort", abort, { once: true });
  const timeout = setTimeout(abort, timeoutMillis);
  try {
    return await Promise.race([request(controller.signal), deadline]);
  } finally {
    clearTimeout(timeout);
    parentSignal?.removeEventListener("abort", abort);
  }
}

function isReplayMode() {
  return document.querySelector("#listening-mode")?.value === "replay";
}

function selectedChannelReadiness() {
  return channelReadiness.get(channelSelect.value);
}

function canStartLivePlayback() {
  if (listenerNeedsLink()) return false;
  const readiness = selectedChannelReadiness()?.audioReadiness;
  return Boolean(channelSelect.value) && !["IDLE", "PREPARING", "COMPLETED", "FAILED"].includes(broadcastPhase) &&
    (!readiness || readiness === "READY" || (broadcastPhase === "LIVE" && readiness === "PREPARING"));
}

function broadcastPresentation() {
  if (listenerNeedsLink()) return { text: LISTENER_LINK_INSTRUCTION, kind: "warning" };
  if (isReplayMode()) return null;
  if (statusRefreshFailed) return { text: statusConnectionLost
    ? "방송 종료 또는 연결 문제 · 진행자에게 방송 상태를 확인하세요"
    : "방송 상태 확인 지연 · 연결과 방송 상태를 확인하세요", kind: "warning" };
  const buffered = audioContext && nextPlayTime - audioContext.currentTime > 0.02;
  if (broadcastPhase === "PREPARING") return { text: "방송 준비 중 · 송출기 준비가 끝나면 재생하세요", kind: "idle" };
  if (broadcastPhase === "PAUSED") return { text: buffered ? "방송 일시정지 · 받은 음성은 계속 재생됩니다" : "방송 일시정지 · 송출기 재개 대기", kind: "idle" };
  if (broadcastPhase === "COMPLETED") return { text: buffered ? "방송 완료 · 받은 음성 재생 중 · 저장 구간은 돌려보기에서 확인하세요" : "방송 완료 · 실시간 음성이 끝났습니다 · 저장 구간은 돌려보기에서 확인하세요", kind: "idle" };
  if (broadcastPhase === "FAILED") return { text: "방송 오류 · 송출기에서 상태를 확인하고 다시 시작하세요", kind: "error" };
  if (broadcastPhase === "IDLE") return { text: "방송 종료 · 새 방송을 기다리세요", kind: "idle" };
  if (liveAudioEnabled === false) return { text: buffered ? "음성 송출 일시정지 · 받은 음성은 계속 재생됩니다" : "음성 송출 일시정지 · 송출기 재개 대기", kind: "idle" };
  const selected = selectedChannelReadiness();
  if (selected?.audioReadiness === "SUBTITLES_ONLY") return { text: "선택한 언어는 자막만 제공됩니다 · 통역 스크립트에서 확인하세요", kind: "idle" };
  if (selected?.audioReadiness === "PREPARING") {
    const waiting = desiredState === "playing";
    const connected = waiting && socket?.readyState === WebSocket.OPEN;
    return { text: connected ? "연결됨 · 선택한 언어의 방송 음성 대기 중" : waiting
      ? "방송 연결 중 · 선택한 언어의 음성 대기 중"
      : "선택한 언어의 음성 준비 중 · 듣기 시작을 눌러 미리 연결하세요", kind: "idle" };
  }
  if (selected?.audioReadiness === "RECOVERING") return { text: "선택한 언어의 음성 복구 대기 · 송출기 상태를 확인하세요", kind: "warning" };
  if (selected?.audioReadiness === "UNAVAILABLE") return { text: "선택한 언어의 음성을 사용할 수 없습니다 · 다른 채널 또는 스크립트를 직접 선택하세요", kind: "warning" };
  return null;
}

function updateLivePlaybackControls() {
  if (listenerNeedsLink()) { playButton.disabled = true; return; }
  if (isReplayMode()) return;
  playButton.disabled = !canStartLivePlayback() || (desiredState === "playing" && !outputOverloaded);
  if (["IDLE", "COMPLETED", "FAILED"].includes(broadcastPhase)) cancelPlaybackReconnect();
}

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
  const presentation = broadcastPresentation();
  if (presentation) { text = presentation.text; kind = presentation.kind; }
  const losses = playbackLossSummary();
  statusLabel.textContent = uiText(text) + (losses ? ` · ${losses}` : "");
  statusDot.className = `dot ${kind}`;
  listenerHud?.update();
}

function playbackLossSummary() {
  const parts = [];
  if (skippedAudioSeconds > 0) parts.push(uiText(`받은 음성 건너뛰기 누적 ${skippedAudioSeconds.toFixed(2)}초`));
  if (rejectedAudioSeconds > 0) parts.push(uiText(`거절된 음성 누적 ${rejectedAudioSeconds.toFixed(3)}초`));
  if (unreceivedAudioUnknown) parts.push(uiText("수신 중단 이후 미수신 음성량 UNKNOWN"));
  return parts.join(" · ");
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

  if (translationLanguages.some((language) => language === existing) || existing === "source" ||
      (existing === "all" && translationLanguages.length > 0)) {
    transcriptSelect.value = existing;
  } else {
    transcriptSelect.value = translationLanguages.length > 0 ? translationLanguages[0] : "source";
  }
}

function setDiagnostics() {
  levelMeter.value = Math.min(100, Math.round(lastRms * 500));
  const audioState = audioContext ? audioContext.state : "closed";
  const bufferedSeconds = audioContext ? Math.max(0, nextPlayTime - audioContext.currentTime) : 0;
  const losses = playbackLossSummary();
  const recovery = losses ? ` · ${losses}` : "";
  diagnosticsLabel.hidden = !developerInformationEnabled;
  diagnosticsLabel.textContent = developerInformationEnabled
    ? uiText(`오디오 ${audioState} · ${receivedFrames} 프레임 · 재생 대기 ${bufferedSeconds.toFixed(1)}초 · ${playbackRate.toFixed(2)}×${recovery}`)
    : "";
  if (!isReplayMode()) liveEdgeButton.disabled = !canStartLivePlayback() || desiredState !== "playing" || bufferedSeconds < 0.35;
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

function clearCurrentLiveCaption() {
  if (document.querySelector("#listening-mode")?.value === "replay" ||
      document.querySelector("#transcript-scope")?.value === "archive") return;
  const caption = document.querySelector("#current-caption");
  if (caption) caption.textContent = "";
}

function renderNoTranscript(message) {
  clearCurrentLiveCaption();
  renderedTranscriptScope = null;
  setTranscriptStale(false);
  const line = document.createElement("p");
  line.className = "transcript-empty";
  line.textContent = uiText(message);
  transcriptList.replaceChildren(line);
  listenerHud?.setTranscript([]);
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
  listenerHud?.setTranscript(lines);
  const caption = document.querySelector("#current-caption");
  const latest = listenerHudCaption(lines, channelSelect.value);
  if (caption && document.querySelector("#listening-mode")?.value !== "replay" && document.querySelector("#transcript-scope")?.value !== "archive") {
    caption.textContent = [latest.source, latest.translation].filter(value => typeof value === "string" && value.trim()).join("\n");
  }
  const fragment = document.createDocumentFragment();
  for (const line of listenerCaptionDisplayGroups(lines)) {
    if (!line || typeof line !== "object") continue;
    if (selectedLanguage !== "source" && selectedLanguage !== "all" &&
        !captionContainsLanguage(line, selectedLanguage)) continue;
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

    if (selectedLanguage === "all") {
      const translationBlock = document.createElement("div");
      translationBlock.className = "translation-block";
      const keys = Object.keys(translations);
      if (keys.length === 0) {
        const empty = document.createElement("p");
        empty.className = "translated-text";
        empty.textContent = nativeMissingCaptionLabel(line, "all") || uiText("번역 텍스트가 없습니다.");
        translationBlock.append(empty);
      } else {
        for (const key of keys) {
          const translated = document.createElement("p");
          translated.className = "translated-text";
          const translateMs = formatLatency(translationLatencies[key]);
          const firstAudioMs = formatFirstAudioLatency(firstAudioLatencies[key]);
          const speechMs = formatLatency(synthesisLatencies[key]);
          const latency = developerInformationEnabled ? [
            translateMs && `${uiText("번역")} ${translateMs}`,
            firstAudioMs,
            speechMs && `${uiText("합성")} ${speechMs}`,
          ].filter(Boolean).join(" · ") : "";
          translated.textContent = `${channelLabel(key)}: ${translations[key]}${latency ? ` (${latency})` : ""}`;
          translationBlock.append(translated);
        }
      }
      if (keys.length > 0) {
        const missing = nativeMissingCaptionLabel(line, "all");
        if (missing) {
          const absent = document.createElement("p");
          absent.className = "translated-text";
          absent.textContent = missing;
          translationBlock.append(absent);
        }
      }
      row.append(translationBlock);
    } else if (selectedLanguage !== "source") {
      const translated = document.createElement("p");
      translated.className = "translated-text";
      const translatedText = captionLanguageValue(translations, selectedLanguage);
      if (translatedText) {
        const translateMs = formatLatency(captionLanguageValue(translationLatencies, selectedLanguage));
        const firstAudioMs = formatFirstAudioLatency(captionLanguageValue(firstAudioLatencies, selectedLanguage));
        const speechMs = formatLatency(captionLanguageValue(synthesisLatencies, selectedLanguage));
        const latency = developerInformationEnabled ? [
          translateMs && `${uiText("번역")} ${translateMs}`,
          firstAudioMs,
          speechMs && `${uiText("합성")} ${speechMs}`,
        ].filter(Boolean).join(" · ") : "";
        translated.textContent = `${channelLabel(selectedLanguage)}: ${translatedText}${latency ? ` (${latency})` : ""}`;
      } else {
        translated.textContent = nativeMissingCaptionLabel(line, selectedLanguage) || `${channelLabel(selectedLanguage)}: ${uiText("아직 번역이 없습니다.")}`;
      }
      row.append(translated);
    }

    if (line.displaySegments?.length > 1) {
      const notice = document.createElement("p");
      notice.className = "transcript-state";
      notice.textContent = uiText("가까운 인식 구간을 함께 표시 · 발화 정렬 미확인");
      row.append(notice);
    }
    const nativeState = nativeCaptionStateLabel(line, selectedLanguage);
    if (nativeState || !nativeMissingCaptionLabel(line, selectedLanguage)) {
      const state = document.createElement("p");
      state.className = "transcript-state";
      state.textContent = nativeState || uiText(isFinal ? "확정" : "받는 중");
      row.append(state);
    }

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
  clearCurrentLiveCaption();
  transcriptEtag = "";
  transcriptEtagScope = null;
  cachedTranscript = null;
  renderedTranscriptScope = null;
  setTranscriptStale(false);
  listenerHud?.setTranscript([]);
}

function loadTranscripts(forceRefresh = false) {
  if (listenerNeedsLink()) return Promise.resolve();
  if (document.querySelector("#transcript-scope")?.value === "archive") return globalThis.GuideCastReplay?.loadCaptions(forceRefresh);
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
      const result = await withListenerRequestTimeout(async signal => {
        const response = await fetch(scope.url, { cache: "no-cache", headers, signal });
        return { response, payload: response.ok && response.status !== 304 ? await response.text() : null };
      });
      const { response, payload } = result;
      if (!isSameTranscriptScope(currentTranscriptScope(), scope) || document.querySelector("#transcript-scope")?.value === "archive") return;
      if (response.status === 401 || response.status === 403) {
        if (sessionAccessMode === "qr_token") { showListenerLinkRequired(); return; }
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
      if (!isSameTranscriptScope(currentTranscriptScope(), scope) || document.querySelector("#transcript-scope")?.value === "archive") return;
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
      if (!isSameTranscriptScope(currentTranscriptScope(), scope) || document.querySelector("#transcript-scope")?.value === "archive") return;
      transientTranscriptFailureCount += 1;
      const backoffMs = Math.min(10_000, 1000 * (2 ** Math.min(transientTranscriptFailureCount - 1, 3)));
      transcriptBackoffUntil = Date.now() + backoffMs;
      transcriptBackoffScope = scope;
      if (cachedTranscript && isSameTranscriptScope(cachedTranscript.scope, scope)) {
        setTranscriptStale(true);
        return;
      }
      clearTranscriptSnapshot();
      renderNoTranscript("스크립트 수신 지연 · 다시 시도하세요");
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

function canPollListenerTranscripts() {
  if (listenerNeedsLink()) return false;
  if (document.visibilityState === "hidden" || pinDialog.open) return false;
  return document.querySelector("#transcript-scope")?.value !== "archive" ||
    !panelTranscript.hidden || Boolean(listenerHud?.isOpen());
}

function startTranscriptPolling() {
  if (listenerNeedsLink()) return;
  if (transcriptPollHandle) return;
  if (canPollListenerTranscripts()) loadTranscripts(true);
  transcriptPollHandle = setInterval(() => {
    if (!canPollListenerTranscripts()) return;
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
    startTranscriptPolling();
    transcriptRefreshButton.disabled = listenerNeedsLink();
  });
  tabTranscript.addEventListener("click", () => {
    switchTabs("transcript");
    startTranscriptPolling();
    loadTranscripts(true);
    transcriptRefreshButton.disabled = listenerNeedsLink();
  });
  transcriptRefreshButton.addEventListener("click", () => loadTranscripts(true));
  transcriptSelect.addEventListener("change", () => { listenerHud?.update(); loadTranscripts(true); });
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
      startTranscriptPolling();
    });
  }
  if (typeof document !== "undefined" && typeof document.addEventListener === "function") {
    document.addEventListener("visibilitychange", () => {
      isDocumentVisible = document.visibilityState !== "hidden";
      if (isDocumentVisible && canPollListenerTranscripts()) {
        loadTranscripts(true);
      }
    });
  }
}

const LISTENER_LINK_INSTRUCTION = "방송 진행자의 QR을 스캔하거나 공유받은 청취 링크로 입장하세요";
function listenerNeedsLink() {
  return sessionAccessMode === "qr_token" && !accessToken;
}

function showListenerLinkRequired() {
  accessToken = "";
  saveSessionToken("");
  statusRefreshFailed = false;
  statusConnectionLost = false;
  if (statusPollHandle !== null) clearInterval(statusPollHandle);
  statusPollHandle = null;
  stopTranscriptPolling();
  cancelPlaybackReconnect();
  clearTranscriptSnapshot();
  renderNoTranscript(LISTENER_LINK_INSTRUCTION);
  channelSelect.disabled = true;
  transcriptRefreshButton.disabled = true;
  playButton.disabled = true;
  // Keep Pause/Stop available for any audio already buffered or playing.
  setStatus(LISTENER_LINK_INSTRUCTION, "warning");
}

async function initialize() {
  try {
    const session = await withListenerRequestTimeout(async signal => {
      const response = await fetch("/api/session", { cache: "no-store", signal });
      if (!response.ok) throw new Error("방송 정보를 읽지 못했습니다.");
      return response.json();
    });
    sessionAccessMode = session.access;
    if (listenerNeedsLink()) { showListenerLinkRequired(); return; }
    if (session.access === "pin" && !accessToken) {
      showPinDialog();
      return;
    }
    await loadChannels();
    startStatusPolling();
  } catch (error) {
    if (listenerNeedsLink()) { showListenerLinkRequired(); return; }
    setStatus("방송 연결 지연 · 입장 정보와 네트워크를 확인하고 다시 시도하세요", "error");
    startStatusPolling();
  }
}

function startStatusPolling() {
  if (listenerNeedsLink()) return;
  if (statusPollHandle !== null) return;
  statusPollHandle = setInterval(() => {
    if (listenerNeedsLink() || document.visibilityState === "hidden" || pinDialog.open) return;
    loadChannels(true).catch(() => {
      if (listenerNeedsLink()) return;
      statusRefreshFailed = true;
      statusConnectionLost = true;
      setStatus("방송 상태 확인 지연", "warning");
    });
  }, 2400);
}

function loadChannels(isRefresh = false) {
  if (listenerNeedsLink()) return Promise.resolve();
  if (statusRequestInFlight) return statusRequestInFlight;
  const scope = `${accessToken}\n${channelApiUrl("/api/status")}`;
  if (statusBackoffScope === scope && Date.now() < statusBackoffUntil) return Promise.resolve();
  const request = refreshChannels(isRefresh);
  statusRequestInFlight = request;
  const clear = () => { if (statusRequestInFlight === request) statusRequestInFlight = null; };
  request.then(clear, clear);
  return request;
}

async function refreshChannels(isRefresh) {
  const requestToken = accessToken;
  const requestUrl = channelApiUrl("/api/status");
  const { response, status } = await withListenerRequestTimeout(async signal => {
    const response = await fetch(requestUrl, { cache: "no-store", headers: authHeaders(requestToken), signal });
    return { response, status: response.ok ? await response.json() : null };
  });
  if (requestToken !== accessToken || requestUrl !== channelApiUrl("/api/status")) return;
  if (response.status === 429) {
    statusBackoffScope = `${requestToken}\n${requestUrl}`;
    statusBackoffUntil = Date.now() + parseRetryAfterHeader(response.headers?.get("Retry-After"));
    statusRefreshFailed = true;
    statusConnectionLost = false;
    if (!isReplayMode()) setStatus("방송 상태 확인 지연", "warning");
    return;
  }
  statusBackoffScope = null;
  statusBackoffUntil = 0;
  if ((response.status === 401 || response.status === 403) && sessionAccessMode === "qr_token") {
    showListenerLinkRequired();
    return;
  }
  if (response.status === 401 && sessionAccessMode === "pin") {
    accessToken = "";
    clearTranscriptSnapshot();
    saveSessionToken("");
    showPinDialog("입장 정보가 만료됐습니다. PIN을 다시 입력하세요.");
    return;
  }
  if (response.status === 401) throw new Error("방송 입장 정보가 올바르지 않습니다.");
  if (!response.ok) throw new Error("방송 상태를 읽지 못했습니다.");
  if (!status || typeof status !== "object") throw new Error("방송 상태를 읽지 못했습니다.");
  const channels = Array.isArray(status.channels) ? status.channels.filter(channel =>
    channel && typeof channel.id === "string" && typeof channel.name === "string" && typeof channel.languageTag === "string") : [];
  const previousStatusRefreshFailed = statusRefreshFailed;
  statusRefreshFailed = false;
  statusConnectionLost = false;
  const phases = ["IDLE", "PREPARING", "LIVE", "PAUSED", "COMPLETED", "FAILED"];
  const readinessStates = ["READY", "PREPARING", "SUBTITLES_ONLY", "RECOVERING", "UNAVAILABLE"];
  const actions = ["NONE", "WAIT_FOR_BROADCASTER", "ASK_BROADCASTER_TO_RETRY", "SELECT_ANOTHER_CHANNEL", "USE_REPLAY"];
  const previousPhase = broadcastPhase;
  const previousLiveAudioEnabled = liveAudioEnabled;
  const previousReadiness = selectedChannelReadiness()?.audioReadiness;
  broadcastPhase = phases.includes(status.broadcast?.phase) ? status.broadcast.phase : status.active === false ? "IDLE" : null;
  broadcastNextAction = actions.includes(status.broadcast?.nextAction) ? status.broadcast.nextAction : "NONE";
  liveAudioEnabled = typeof status.liveAudioEnabled === "boolean" ? status.liveAudioEnabled : null;
  liveAudioEpoch = Number.isSafeInteger(status.liveAudioEpoch) && status.liveAudioEpoch >= 0 ? status.liveAudioEpoch : null;
  channelReadiness.clear();
  for (const channel of channels) channelReadiness.set(channel.id, {
    audioReadiness: readinessStates.includes(channel.audioReadiness) ? channel.audioReadiness : null,
    transcriptReadiness: readinessStates.includes(channel.transcriptReadiness) ? channel.transcriptReadiness : null,
    nextAction: actions.includes(channel.nextAction) ? channel.nextAction : "NONE",
  });
  const selectedAudioChannel = channelSelect.value;
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
  if (selectedAudioChannel && !pinnedChannelId) {
    if (channels.some(channel => channel.id === selectedAudioChannel)) channelSelect.value = selectedAudioChannel;
    else {
      const unavailable = document.createElement("option");
      unavailable.value = selectedAudioChannel;
      unavailable.textContent = `${channelLabel(selectedAudioChannel)} · ${uiText("채널을 사용할 수 없습니다")}`;
      unavailable.disabled = true;
      channelSelect.append(unavailable);
      channelSelect.value = selectedAudioChannel;
      channelReadiness.set(selectedAudioChannel, { audioReadiness: "UNAVAILABLE", transcriptReadiness: "UNAVAILABLE" });
    }
  }
  channelSelect.disabled = channels.length < 2;
  updateTranscriptLanguageOptions(channels);
  if (pinnedChannelId) {
    const channel = channels.find((candidate) => candidate.id === pinnedChannelId);
    channelSelect.value = channel.id;
    pinnedChannelName.textContent = `${channelLabel(channel.id)} · ${channel.languageTag}`;
    pinnedChannelBanner.hidden = false;
    pageDescription.textContent = uiText("가이드가 송출 중인 언어를 고르고 재생을 누르세요.");
    document.title = `${channelLabel(channel.id)} · ${uiText("MCastTalk")}`;
  }
  updateLivePlaybackControls();
  if (!isRefresh || previousStatusRefreshFailed || broadcastPresentation() || desiredState !== "playing" || previousPhase !== broadcastPhase ||
      previousLiveAudioEnabled !== liveAudioEnabled ||
      previousReadiness !== selectedChannelReadiness()?.audioReadiness) {
    if (!isReplayMode()) setStatus(desiredState === "playing" ? "방송 연결됨 · 선택한 언어의 음성 대기 중" :
      channels.length ? "재생을 눌러 청취하세요" : "송출 채널 대기 중");
  }
  setDiagnostics();
  listenerHud?.update();
  startTranscriptPolling();
}

function showPinDialog(message = "") {
  listenerHud?.close();
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
    saveSessionToken(accessToken);
    pinInput.value = "";
    pinDialog.close();
    await loadChannels();
    startStatusPolling();
  } catch (error) {
    pinError.textContent = uiText(error.message || "입장하지 못했습니다.");
  }
});

playButton.addEventListener("click", () => {
  if (listenerNeedsLink()) { showListenerLinkRequired(); return; }
  if (document.querySelector("#listening-mode")?.value === "replay") return globalThis.GuideCastReplay?.start();
  return startPlayback();
});
pauseButton.addEventListener("click", () => { globalThis.GuideCastReplay?.pause(); stopPlayback("paused"); });
stopButton.addEventListener("click", () => { globalThis.GuideCastReplay?.stop(); stopPlayback("stopped"); });
playbackRateSelect.addEventListener("change", () => {
  const requested = Number(playbackRateSelect.value);
  playbackRate = Number.isFinite(requested) ? Math.min(1.5, Math.max(0.75, requested)) : 1;
  if (document.querySelector("#listening-mode")?.value !== "replay" && playbackRate < 1) {
    playbackRate = 1;
    playbackRateSelect.value = "1";
    setStatus("실시간 듣기는 1배속 이상 · 느리게 듣기는 돌려보기에서 선택하세요", "warning");
  }
  setDiagnostics();
});
liveEdgeButton.addEventListener("click", () => {
  if (document.querySelector("#listening-mode")?.value === "replay") { globalThis.GuideCastReplay?.returnLive(); } else jumpToLiveEdge();
});
channelSelect.addEventListener("change", () => {
  globalThis.GuideCastReplay?.channelChanged();
  updateLivePlaybackControls();
  if (desiredState === "playing" && document.querySelector("#listening-mode")?.value !== "replay") startPlayback();
  else if (!isReplayMode()) setStatus("재생을 눌러 청취하세요");
  listenerHud?.update();
  if (canPollListenerTranscripts()) loadTranscripts(true);
});

async function startPlayback() {
  if (!canStartLivePlayback()) {
    setStatus("선택한 언어의 음성을 기다리고 있습니다");
    updateLivePlaybackControls();
    return;
  }
  const previousSkippedAudioSeconds = skippedAudioSeconds;
  stopRuntime();
  const newlySkippedAudioSeconds = skippedAudioSeconds - previousSkippedAudioSeconds;
  if (playbackRate < 1) { playbackRate = 1; playbackRateSelect.value = "1"; }
  desiredState = "playing";
  const generation = ++playbackGeneration;
  const requestedChannel = channelSelect.value;
  const requestToken = accessToken;
  playButton.disabled = true;
  pauseButton.disabled = false;
  stopButton.disabled = false;
  setStatus(newlySkippedAudioSeconds > 0
    ? `받은 대기 음성 ${newlySkippedAudioSeconds.toFixed(2)}초를 건너뛰고 방송 연결 중`
    : "방송 연결 중");

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
  if (request.overloaded) return;
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
    try { handleAudioMessage(event, request); }
    catch (_) {
      haltPlaybackIntake(request);
      setStatus("음성 처리 오류 · 받은 음성 재생 후 다시 연결하세요", "warning");
      setDiagnostics();
    }
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
  if (request.overloaded) return;
  if (["IDLE", "COMPLETED", "FAILED"].includes(broadcastPhase)) {
    setStatus("방송 음성 수신이 끝났습니다");
    updateLivePlaybackControls();
    return;
  }
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
    if (["IDLE", "COMPLETED", "FAILED"].includes(broadcastPhase)) return;
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
      if (config.type === "config" && Number.isInteger(config.sampleRate) &&
          config.sampleRate >= 8000 && config.sampleRate <= 192000) {
        request.sourceSampleRate = config.sampleRate;
        reconnectAttempt = 0;
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

  if (!(event.data instanceof ArrayBuffer) || event.data.byteLength === 0 || event.data.byteLength % 2 !== 0) return;
  const frameSamples = event.data.byteLength / 2;
  const maximumFrameSamples = request.sourceSampleRate * MAX_PCM_FRAME_SECONDS;
  if (event.data.byteLength > MAX_PCM_FRAME_BYTES || frameSamples > maximumFrameSamples) {
    receivedFrames += 1;
    receivedBytes += event.data.byteLength;
    rejectedAudioSeconds += frameSamples / request.sourceSampleRate;
    haltPlaybackIntake(request);
    setStatus("한 번에 받은 음성이 너무 커 새 음성 수신을 멈췄습니다 · 받은 음성은 계속 재생됩니다. 다시 연결하면 대기 음성을 건너뜁니다.", "warning");
    setDiagnostics();
    return;
  }
  const view = new DataView(event.data);
  reconnectAttempt = 0;

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
  const overloaded = scheduleSamples(playableSamples, request);

  if (overloaded) {
    setStatus(
      "수신량이 많아 새 음성 수신을 멈췄습니다 · 받은 음성은 계속 재생됩니다. 다시 연결하거나 현재 방송으로 이동하면 대기 음성을 건너뜁니다. 누락 구간은 돌려보기에서 확인하세요.",
      "warning",
    );
  } else if (nextPlayTime - request.audioContext.currentTime > BUFFER_WARNING_SECONDS) {
    setStatus("음성을 순서대로 재생 중 · 재생 대기가 늘었습니다", "warning");
  } else if (lastRms >= 0.002 || lastPeak >= 0.01) {
    setStatus("음성 수신·재생 중", "live");
  } else if (receivedFrames >= 8) {
    setStatus(request.channelId === "source"
      ? "원음 수신 중 · 현재는 조용한 구간입니다"
      : "통역 음성 수신 중 · 현재는 조용한 구간입니다", "idle");
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
  const now = request.audioContext.currentTime;
  for (const source of activeSources) if (source.guideCastEndsAt <= now) {
    activeSources.delete(source);
    runSafely(() => source.disconnect());
  }
  // Burst PCM keeps its complete content and ordering. Stop intake at the memory budget;
  // never silently trim a phrase or cancel audio already accepted for playback.
  const deviceLead = Number.isFinite(request.audioContext.baseLatency)
    ? request.audioContext.baseLatency + 0.02
    : 0.06;
  const minimumLead = Math.max(0.06, deviceLead);
  const isContinuous = nextPlayTime > now + 0.005;
  let startAt = Math.max(nextPlayTime, isContinuous ? nextPlayTime : now + minimumLead);
  const playbackDuration = samples.length /
    request.audioContext.sampleRate / playbackRate;
  if (startAt + playbackDuration - now > MAX_BUFFERED_AUDIO_SECONDS || activeSources.size >= 2048) {
    rejectedAudioSeconds += samples.length / request.audioContext.sampleRate;
    haltPlaybackIntake(request);
    return true;
  }

  const buffer = request.audioContext.createBuffer(
    1,
    samples.length,
    request.audioContext.sampleRate,
  );
  buffer.copyToChannel(samples, 0);
  const source = request.audioContext.createBufferSource();
  source.buffer = buffer;
  source.playbackRate.value = playbackRate;
  source.guideCastReceivedAt = now;
  source.guideCastStartsAt = startAt;
  source.guideCastEndsAt = startAt + playbackDuration;
  source.guideCastRate = playbackRate;
  source.connect(request.gainNode);
  activeSources.add(source);
  source.onended = () => {
    const wasScheduled = activeSources.delete(source);
    runSafely(() => source.disconnect());
    if (wasScheduled && isCurrentPlaybackRequest(request.generation, request.channelId,
      request.audioContext, request.gainNode, request.socket)) {
      if (broadcastPresentation()) setStatus("받은 음성 재생이 끝났습니다");
      else if (!outputOverloaded && !request.overloaded && reconnectTimer === null &&
          request.socket?.readyState === WebSocket.OPEN && request.audioContext.state === "running") {
        const bufferedSeconds = Math.max(0, nextPlayTime - request.audioContext.currentTime);
        if (bufferedSeconds > BUFFER_WARNING_SECONDS)
          setStatus("음성을 순서대로 재생 중 · 재생 대기가 늘었습니다", "warning");
        else if (bufferedSeconds > 0.02) setStatus("음성 수신·재생 중", "live");
        else setStatus("방송 연결됨 · 선택한 언어의 음성 대기 중");
      }
    }
    setDiagnostics();
  };
  source.start(startAt);
  nextPlayTime = startAt + (buffer.duration / playbackRate);
  return false;
}

function haltPlaybackIntake(request) {
  outputOverloaded = request.overloaded = true;
  unreceivedAudioUnknown = true;
  const blockedSocket = request.socket;
  request.socket = null;
  socket = null;
  cancelPlaybackReconnect();
  if (blockedSocket) { detachSocketHandlers(blockedSocket); runSafely(() => blockedSocket.close()); }
  playButton.disabled = false;
  playButton.textContent = uiText("대기 음성 건너뛰고 다시 연결");
  liveEdgeButton.textContent = uiText("대기 음성 건너뛰고 현재 방송");
}

function jumpToLiveEdge() {
  if (!canStartLivePlayback()) { setStatus("현재 방송 음성을 사용할 수 없습니다"); return; }
  if (!audioContext || desiredState !== "playing") return;
  discardScheduledAudio(audioContext);
  if (outputOverloaded) { startPlayback(); return; }
  nextPlayTime = audioContext.currentTime + 0.04;
  setStatus("대기 중인 음성을 건너뛰고 현재 방송으로 이동했습니다", "live");
  setDiagnostics();
}

function discardScheduledAudio(targetAudioContext) {
  for (const source of activeSources) {
    skippedAudioSeconds += Math.max(0, source.guideCastEndsAt - Math.max(targetAudioContext.currentTime, source.guideCastStartsAt)) * source.guideCastRate;
  }
  activeSources.forEach((source) => runSafely(() => source.stop()));
  activeSources.clear();
  nextPlayTime = targetAudioContext.currentTime;
}

function resample(source, sourceRate, targetRate, request = null) {
  if (!source.length || !Number.isFinite(sourceRate) || !Number.isFinite(targetRate) || sourceRate <= 0 || targetRate <= 0) return new Float32Array(0);
  const state = request || {};
  const rates = `${sourceRate}/${targetRate}`;
  if (state.resampleRates !== rates) {
    state.resampleRates = rates;
    state.resamplePhase = 0;
    state.lastSample = null;
  }
  if (sourceRate === targetRate) return source;
  const hasTail = state.lastSample !== null;
  const length = source.length + (hasTail ? 1 : 0);
  const sample = (index) => hasTail && index === 0 ? state.lastSample : source[index - (hasTail ? 1 : 0)];
  const ratio = sourceRate / targetRate;
  const phase = state.resamplePhase || 0;
  // Keep one source sample as lookahead. Never extrapolate a frame tail or reset fractional phase.
  const outputLength = Math.max(0, Math.ceil((length - 1 - phase) / ratio - 1e-10));
  const output = new Float32Array(outputLength);
  for (let index = 0; index < outputLength; index += 1) {
    const position = phase + index * ratio;
    const left = Math.floor(position);
    const fraction = position - left;
    const leftSample = sample(left);
    const rightSample = sample(left + 1);
    output[index] = leftSample + (rightSample - leftSample) * fraction;
  }
  state.resamplePhase = Math.max(0, phase + outputLength * ratio - (length - 1));
  state.lastSample = source[source.length - 1];
  return output;
}

function stopPlayback(nextState) {
  desiredState = nextState;
  stopRuntime();
  playButton.disabled = false;
  pauseButton.disabled = true;
  stopButton.disabled = true;
  updateLivePlaybackControls();
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
  if (audioContext) discardScheduledAudio(audioContext);
  else {
    activeSources.forEach((source) => runSafely(() => source.stop()));
    activeSources.clear();
  }
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
  outputOverloaded = false;
  playButton.textContent = defaultPlayButtonText;
  liveEdgeButton.textContent = defaultLiveEdgeButtonText;
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
  rejectedAudioSeconds,
  skippedAudioSeconds,
  outputOverloaded,
  unreceivedAudioUnknown,
  lossTrackingScope: "page-session",
  maxPcmFrameBytes: MAX_PCM_FRAME_BYTES,
  maxPcmFrameSeconds: MAX_PCM_FRAME_SECONDS,
  oldestPendingReceiptAgeSeconds: audioContext ? Math.max(0, ...Array.from(activeSources)
    .filter(source => source.guideCastStartsAt > audioContext.currentTime)
    .map(source => audioContext.currentTime - source.guideCastReceivedAt)) : 0,
  maxBufferedAudioSeconds: MAX_BUFFERED_AUDIO_SECONDS,
  reconnectAttempt,
  reconnectPending: reconnectTimer !== null,
  broadcastPhase,
  liveAudioEnabled,
  liveAudioEpoch,
  audioReadiness: selectedChannelReadiness()?.audioReadiness || "UNKNOWN",
  transcriptReadiness: selectedChannelReadiness()?.transcriptReadiness || "UNKNOWN",
});

/** Group only rows explicitly associated by the server's conservative presentation policy. */
function listenerCaptionDisplayGroups(lines) {
  const rows = Array.isArray(lines) ? lines.filter(row => row && typeof row === "object") : [];
  const candidates = new Map();
  for (const row of rows) {
    const key = listenerCaptionDisplayGroupKey(row);
    if (key === null) continue;
    const group = candidates.get(key) || [];
    group.push(row); candidates.set(key, group);
  }
  const valid = new Map();
  for (const [id, group] of candidates) {
    const source = typeof group[0].sourceText === "string" ? group[0].sourceText.trim().replace(/\s+/g, " ") : "";
    if (group.length < 2 || !source || new Set(group.map(row => typeof row.liveSegmentLanguage === "string" ? row.liveSegmentLanguage.toLowerCase() : row.liveSegmentLanguage)).size !== group.length ||
        group.some(row => typeof row.sourceText !== "string" || row.sourceText.trim().replace(/\s+/g, " ") !== source)) continue;
    valid.set(id, group);
  }
  const consumed = new Set();
  const displayed = rows.flatMap(row => {
    const key = listenerCaptionDisplayGroupKey(row);
    const group = valid.get(key);
    if (!group) return [row];
    if (consumed.has(key)) return [];
    consumed.add(key);
    const merged = { ...group[0], liveSegmentLanguage: null, displaySegments: group,
      isFinal: group.every(segment => segment.isFinal === true) };
    for (const field of ["translations", "translationLatencyMillis", "firstAudioLatencyMillis", "synthesisLatencyMillis"]) {
      merged[field] = Object.assign({}, ...group.map(segment => segment[field] || {}));
    }
    const captureTimes = group.map(segment => segment.capturedAtElapsedRealtimeNanos).filter(Number.isFinite);
    if (captureTimes.length === group.length) merged.capturedAtElapsedRealtimeNanos = Math.min(...captureTimes);
    return [merged];
  });
  // Provider sequence ranges are lane-specific. Chronological presentation must not
  // treat the final lane's largest sequence as the latest microphone utterance.
  if (displayed.every(row => Number.isFinite(row.capturedAtElapsedRealtimeNanos))) {
    displayed.sort((a, b) => a.capturedAtElapsedRealtimeNanos - b.capturedAtElapsedRealtimeNanos);
  }
  return displayed;
}

function listenerCaptionDisplayGroupKey(row) {
  if (!Number.isSafeInteger(row.displayGroupSequence) || !row.liveSegmentLanguage) return null;
  if (Object.prototype.hasOwnProperty.call(row, "part")) {
    if (!Number.isSafeInteger(row.part) || row.part < 0 || row.nativeMetadataVersion !== 1 ||
        !Number.isSafeInteger(row.nativeAudioSessionId) || row.nativeAudioSessionId < 0 ||
        typeof row.sourceLanguageTag !== "string" || !row.sourceLanguageTag.trim() ||
        row.liveSourceFailed === true || row.liveSourceExpired === true) return null;
    return JSON.stringify([row.part, row.nativeAudioSessionId, row.sourceLanguageTag, row.displayGroupSequence]);
  }
  return row.displayGroupSequence;
}

function captionLanguageEquals(left, right) {
  return typeof left === "string" && typeof right === "string" && left.toLowerCase() === right.toLowerCase();
}

function captionLanguageValue(values, language) {
  if (!values || typeof values !== "object" || typeof language !== "string") return undefined;
  if (Object.prototype.hasOwnProperty.call(values, language)) return values[language];
  const keys = Object.keys(values).filter(key => captionLanguageEquals(key, language));
  return keys.length === 1 ? values[keys[0]] : undefined;
}

function captionContainsLanguage(row, language) {
  if (row.displaySegments) return row.displaySegments.some(segment => captionContainsLanguage(segment, language));
  return !row.liveSegmentLanguage || captionLanguageEquals(row.liveSegmentLanguage, language) ||
    captionLanguageValue(row.translations, language) !== undefined;
}

/** Native output completion is separate from original-text finality and listener playback. */
function nativeCaptionStateLabel(row, language = "all") {
  if (!row || typeof row !== "object") return "";
  if (row.displaySegments) return row.displaySegments.map(segment => {
    const label = nativeCaptionStateLabel(segment, language);
    return label && (language === "all" || language === "source") ? `${channelLabel(segment.liveSegmentLanguage)}: ${label}` : label;
  }).filter(Boolean).join("\n");
  const lane = typeof row.liveSegmentLanguage === "string" ? row.liveSegmentLanguage.trim() : "";
  if (!lane && row.alignment !== "NATIVE_PAIR_UNCONFIRMED") return "";
  if (typeof pinnedChannelId !== "undefined" && pinnedChannelId && (!lane || !captionLanguageEquals(lane, pinnedChannelId))) return "";
  if (lane && language !== "all" && language !== "source" && !captionLanguageEquals(lane, language)) return "";
  const output = row.liveOutputState || row.outputState;
  const labels = { QUEUED: "통역 중", GENERATING: "통역 중", GENERATED: "통역 완료",
    CANCELLED: "통역 중단", INCOMPLETE: "통역 미완료" };
  const label = uiText(labels[output] || "통역 상태 미확인");
  const terminal = output === "GENERATED" || output === "CANCELLED" || output === "INCOMPLETE";
  const sourceCheck = row.liveSourceFailed === true || row.liveSourceExpired === true ||
    (terminal && row.liveSourceFinal === false);
  return sourceCheck ? `${label} · ${uiText("원문 확인 필요")}` : label;
}

function nativeMissingCaptionLabel(row, language = "all") {
  if (!row || typeof row !== "object" || language === "source") return "";
  if (row.displaySegments) return row.displaySegments.map(segment => {
    const label = nativeMissingCaptionLabel(segment, language);
    return label && language === "all" ? `${channelLabel(segment.liveSegmentLanguage)}: ${label}` : label;
  }).filter(Boolean).join("\n");
  const lane = typeof row.liveSegmentLanguage === "string" ? row.liveSegmentLanguage.trim() : "";
  const savedNative = row.alignment === "NATIVE_PAIR_UNCONFIRMED";
  if (!lane && !savedNative) return "";
  if (typeof pinnedChannelId !== "undefined" && pinnedChannelId && (!lane || !captionLanguageEquals(lane, pinnedChannelId))) return "";
  if (lane && language !== "all" && !captionLanguageEquals(language, lane)) return "";
  const translations = row.translations || {};
  // A saved native row without lane metadata can only describe the entire row.
  if (Object.values(translations).some(value => typeof value === "string" && value.trim())) return "";
  const label = { GENERATED: "통역 처리 완료 · 번역 자막 없음", CANCELLED: "통역 취소됨 · 번역 자막 없음",
    INCOMPLETE: "통역 미완료 · 번역 자막 없음" }[row.liveOutputState || row.outputState];
  return label ? uiText(label) : "";
}

function listenerHudCaption(lines, language) {
  const rows = listenerCaptionDisplayGroups(lines);
  const text = value => typeof value === "string" ? value : "";
  let selected = null;
  for (let index = rows.length - 1; index >= 0; index--) {
    const row = rows[index];
    if (language === "source" ? text(row.sourceText).trim() : language === "all" ?
      Object.values(row.translations || {}).some(value => text(value).trim()) || nativeMissingCaptionLabel(row, language) :
      captionLanguageEquals(row.liveSegmentLanguage, language) || row.displaySegments?.some(segment => captionLanguageEquals(segment.liveSegmentLanguage, language)) ||
      captionLanguageValue(row.translations, language) !== undefined ||
      (!row.liveSegmentLanguage && nativeMissingCaptionLabel(row, language))) {
      selected = row;
      break;
    }
  }
  // Keep a selected lane's source and translation on the same row. Never pair it
  // with a newer source belonging to another language or provider turn.
  if (!selected && language !== "source") {
    for (let index = rows.length - 1; index >= 0; index--) {
      if (text(rows[index].sourceText).trim()) { selected = rows[index]; break; }
    }
  }
  const translation = language === "all" ? [Object.entries(selected?.translations || {})
    .filter(([, value]) => text(value).trim())
    .map(([id, value]) => `${channelLabel(id)}\n${value}`).join("\n\n"),
    nativeMissingCaptionLabel(selected, language)].filter(Boolean).join("\n\n") :
    language === "source" ? "" : text(captionLanguageValue(selected?.translations, language));
  return { source: text(selected?.sourceText), translation: translation.trim() ? translation : nativeMissingCaptionLabel(selected, language),
    notice: selected?.displaySegments?.length > 1 ? uiText("가까운 인식 구간을 함께 표시 · 발화 정렬 미확인") : "" };
}

function listenerHudStoredCaptions(lines, language) {
  const page = Array.isArray(lines) ? lines.filter(row => row && typeof row === "object").slice(0, 100) : [];
  const rows = listenerCaptionDisplayGroups(page);
  const sources = [], translations = [], notices = new Set();
  rows.forEach((row, index) => {
    const text = listenerHudCaption([row], language);
    sources.push(`${index + 1}. ${text.source.trim() || uiText("저장된 원문이 없습니다.")}`);
    if (language !== "source") translations.push(`${index + 1}. ${text.translation.trim() || uiText("선택한 언어의 저장된 번역이 없습니다.")}`);
    if (text.notice) notices.add(text.notice);
    else if (row.alignment === "NATIVE_PAIR_UNCONFIRMED" || row.liveSegmentLanguage) {
      notices.add(uiText("원문·통역의 대응 관계 미확인"));
    }
  });
  return { source: sources.join("\n\n"), translation: translations.join("\n\n"), notice: Array.from(notices).join(" · ") };
}

function createListenerHud() {
  const hud = document.querySelector("#listener-hud");
  const opener = document.querySelector("#hud-open");
  const closer = document.querySelector("#hud-close");
  const language = document.querySelector("#hud-language");
  const order = document.querySelector("#hud-order");
  const sourcePane = document.querySelector("#hud-source-pane");
  const translationPane = document.querySelector("#hud-translation-pane");
  const captions = document.querySelector("#hud-captions");
  const sourceText = document.querySelector("#hud-source");
  const translationText = document.querySelector("#hud-translation");
  const translationLabel = document.querySelector("#hud-translation-label");
  const status = document.querySelector("#hud-status");
  const note = document.querySelector("#hud-fullscreen-note");
  const archiveControls = document.querySelector("#hud-archive-controls");
  const archivePrevious = document.querySelector("#hud-archive-prev");
  const archiveNext = document.querySelector("#hud-archive-next");
  const main = document.querySelector("main");
  if (!document.body || typeof window.addEventListener !== "function" ||
      [hud, opener, closer, language, order, sourcePane, translationPane, captions,
        sourceText, translationText, translationLabel, status, note, main].some(node => !node)) return null;
  let opened = false, rows = [], optionSignature = "", previous = null, generation = 0;
  let ownsHistory = false;
  let ownsFullscreen = false;
  const historyMarker = `caption-hud-${Date.now()}`;
  const historyKey = "guideCastCaptionHud";
  const ownedHistory = state => Boolean(state && state[historyKey] === historyMarker);
  const setText = (node, value) => { if (node.textContent !== value) node.textContent = value; };
  function update() {
    const options = Array.from(transcriptSelect.options || transcriptSelect.children || []);
    const signature = JSON.stringify(options.map(option => [option.value, option.textContent]));
    if (optionSignature !== signature) {
      language.replaceChildren(...options.map(option => {
        const copy = document.createElement("option");
        copy.value = option.value; copy.textContent = option.textContent;
        return copy;
      }));
      optionSignature = signature;
    }
    language.disabled = transcriptSelect.disabled || !options.length;
    const captionLanguage = transcriptSelect.value;
    language.value = captionLanguage;
    const sourceOnly = captionLanguage === "source";
    translationPane.hidden = sourceOnly;
    captions.classList.toggle("is-source-only", sourceOnly);
    order.disabled = sourceOnly;
    const recorded = document.querySelector("#listening-mode")?.value === "replay";
    const archive = document.querySelector("#transcript-scope")?.value === "archive";
    const saved = globalThis.GuideCastReplay?.captionSnapshot?.();
    const selected = recorded || archive ? listenerHudStoredCaptions(saved?.rows || [], captionLanguage) : listenerHudCaption(rows, captionLanguage);
    const emptySaved = saved?.error ? uiText("저장 스크립트를 불러오지 못했습니다") : saved?.pending ?
      uiText("저장 스크립트를 불러오는 중입니다.") : uiText("저장된 스크립트가 없습니다.");
    setText(sourceText, selected.source.trim() ? selected.source : recorded || archive ? emptySaved : uiText("원문을 기다리고 있습니다."));
    setText(translationText, selected.translation.trim() ? selected.translation : recorded || archive ? emptySaved : uiText("선택한 언어의 번역을 기다리고 있습니다."));
    sourceText.classList.toggle("is-empty", !selected.source.trim());
    translationText.classList.toggle("is-empty", !selected.translation.trim());
    setText(translationLabel, `${uiText("번역")} · ${captionLanguage === "all" ? uiText("전체 번역") : channelLabel(captionLanguage) || ""}`);
    if (archiveControls) archiveControls.hidden = !(recorded || archive);
    if (archivePrevious) archivePrevious.disabled = !saved?.hasPrevious || saved?.pending;
    if (archiveNext) archiveNext.disabled = !saved?.hasNext || saved?.pending;
    const context = recorded || archive ? [uiText("저장 스크립트 · 음성과 자동 동기화되지 않습니다."),
      saved?.error ? uiText("저장 스크립트를 불러오지 못했습니다") : ""].filter(Boolean).join(" · ") : "";
    setText(status, !captionLanguage ? uiText("스크립트 언어를 먼저 선택하세요.") :
      [context, selected.notice, statusLabel.textContent].filter(Boolean).join(" · "));
  }
  function close({ fromHistory = false, restore = true } = {}) {
    if (!opened) return false;
    opened = false; generation++;
    ownsFullscreen = false;
    hud.hidden = true;
    opener.setAttribute("aria-expanded", "false");
    document.body.style.overflow = previous.overflow;
    main.inert = previous.inert;
    if (previous.ariaHidden === null) main.removeAttribute("aria-hidden");
    else main.setAttribute("aria-hidden", previous.ariaHidden);
    if (document.fullscreenElement === hud && typeof document.exitFullscreen === "function") {
      try { Promise.resolve(document.exitFullscreen()).catch(() => {}); } catch (_) {}
    }
    if (restore) {
      window.scrollTo(previous.x, previous.y);
      const target = previous.focus?.isConnected !== false ? previous.focus : opener;
      try { (target || opener).focus({ preventScroll: true }); } catch (_) { opener.focus(); }
    }
    if (!fromHistory && ownsHistory && ownedHistory(history.state)) history.back();
    ownsHistory = false;
    return true;
  }
  function open({ fromHistory = false, fullscreen = true } = {}) {
    if (opened) return false;
    opened = true;
    const openingGeneration = ++generation;
    previous = { focus: document.activeElement, x: window.scrollX || 0, y: window.scrollY || 0,
      overflow: document.body.style.overflow, inert: main.inert, ariaHidden: main.getAttribute("aria-hidden") };
    main.inert = true; main.setAttribute("aria-hidden", "true");
    document.body.style.overflow = "hidden";
    hud.hidden = false; note.hidden = true;
    opener.setAttribute("aria-expanded", "true");
    update(); closer.focus({ preventScroll: true });
    ownsHistory = fromHistory;
    if (!fromHistory) {
      try {
        const state = history.state && typeof history.state === "object" ? history.state : {};
        history.pushState({ ...state, [historyKey]: historyMarker }, "", location.href);
        ownsHistory = true;
      } catch (_) { /* Close and Escape still work when history changes are unavailable. */ }
    }
    startTranscriptPolling();
    if (document.querySelector("#listening-mode")?.value === "replay" ||
        document.querySelector("#transcript-scope")?.value === "archive") globalThis.GuideCastReplay?.loadCaptions(true);
    if (fullscreen && !document.fullscreenElement && typeof hud.requestFullscreen === "function") {
      try {
        Promise.resolve(hud.requestFullscreen()).then(() => {
          if (!opened && document.fullscreenElement === hud) {
            ownsFullscreen = false;
            return document.exitFullscreen?.();
          }
          if (opened && document.fullscreenElement === hud) ownsFullscreen = true;
        }).catch(() => {
          if (opened && generation === openingGeneration) {
            setText(note, uiText("전체 화면 없이도 HUD를 사용할 수 있습니다.")); note.hidden = false;
          }
        });
      } catch (_) { setText(note, uiText("전체 화면 없이도 HUD를 사용할 수 있습니다.")); note.hidden = false; }
    }
    return true;
  }
  opener.addEventListener("click", () => open());
  closer.addEventListener("click", () => close());
  language.addEventListener("change", () => {
    if (!Array.from(transcriptSelect.options || []).some(option => option.value === language.value)) return;
    transcriptSelect.value = language.value;
    transcriptSelect.dispatchEvent(new Event("change", { bubbles: true }));
    update();
  });
  order.addEventListener("change", () => {
    if (order.value === "translation-first") captions.append(translationPane, sourcePane);
    else captions.append(sourcePane, translationPane);
  });
  archivePrevious?.addEventListener("click", () => globalThis.GuideCastReplay?.previousCaptionPage());
  archiveNext?.addEventListener("click", () => globalThis.GuideCastReplay?.nextCaptionPage());
  hud.addEventListener("keydown", event => {
    if (!opened) return;
    if (event.key === "Escape") { event.preventDefault(); close(); return; }
    if (event.key !== "Tab") return;
    const controls = [closer, language, order, archivePrevious, archiveNext]
      .filter(control => control && !control.disabled && !control.hidden &&
        !(archiveControls?.hidden && (control === archivePrevious || control === archiveNext)));
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && (document.activeElement === first || !hud.contains(document.activeElement))) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && (document.activeElement === last || !hud.contains(document.activeElement))) {
      event.preventDefault(); first.focus();
    }
  });
  document.addEventListener("focusin", event => { if (opened && !hud.contains(event.target)) closer.focus(); });
  document.addEventListener("fullscreenchange", () => {
    if (opened && document.fullscreenElement === hud) ownsFullscreen = true;
    else if (opened && ownsFullscreen) {
      ownsFullscreen = false;
      setText(note, uiText("전체 화면 없이도 HUD를 사용할 수 있습니다."));
      note.hidden = false;
    }
  });
  window.addEventListener("popstate", event => {
    if (ownedHistory(event.state)) { if (!opened) open({ fromHistory: true, fullscreen: false }); }
    else if (opened) close({ fromHistory: true });
  });
  window.addEventListener("pagehide", () => close({ fromHistory: true, restore: false }));
  document.querySelector("#listening-mode")?.addEventListener("change", update);
  document.querySelector("#transcript-scope")?.addEventListener("change", () => { rows = []; update(); });
  return { open, close, update, isOpen: () => opened, setTranscript: lines => { rows = Array.isArray(lines) ? lines : []; update(); } };
}

developerInformationToggle.addEventListener("change", () => {
  developerInformationEnabled = developerInformationToggle.checked;
  try {
    localStorage.setItem(DEVELOPER_INFORMATION_PREFERENCE, String(developerInformationEnabled));
  } catch (_) { /* The current page can use the selection without persistent storage. */ }
  setDiagnostics();
  renderCachedTranscript(currentTranscriptScope());
});

switchTabs("player");
setupTabEvents();
listenerHud = createListenerHud();
window.GuideCastHud = listenerHud;
window.addEventListener("pagehide", () => {
  stopTranscriptPolling();
  if (statusPollHandle !== null) clearInterval(statusPollHandle);
  statusPollHandle = null;
});
window.addEventListener("pageshow", event => {
  if (!event.persisted) return;
  startStatusPolling();
  startTranscriptPolling();
});
initialize();
