import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const playerUrl = new URL(
  "../core/server/src/main/assets/listener/player.js",
  import.meta.url,
);
const source = await readFile(playerUrl, "utf8");
const listenerIndexUrl = new URL(
  "../core/server/src/main/assets/listener/index.html",
  import.meta.url,
);
const listenerIndex = await readFile(listenerIndexUrl, "utf8");
const scheduleStart = source.indexOf("function scheduleSamples(samples, request)");
const scheduleEnd = source.indexOf("function jumpToLiveEdge()", scheduleStart);
assert.notEqual(scheduleStart, -1, "scheduleSamples must exist");
assert.notEqual(scheduleEnd, -1, "jumpToLiveEdge must follow scheduleSamples");

const schedule = source.slice(scheduleStart, scheduleEnd);
assert.match(
  schedule,
  /let startAt = Math\.max\(nextPlayTime,/,
  "each PCM frame must be scheduled after the previous frame",
);
assert.doesNotMatch(
  schedule,
  /startAt\s*=\s*now\s*;/,
  "automatic queue rewinds overlap burst TTS frames and produce chirpy audio",
);
assert.match(source, /firstAudioLatencyMillis/, "listener must render first-audio latency");
assert.match(source, /transcriptFollow\.checked/, "listener must preserve manual transcript scrolling");
assert.doesNotMatch(
  source.slice(source.lastIndexOf('switchTabs("player")')),
  /startTranscriptPolling\(\);/,
  "audio-only listeners must not poll the transcript endpoint",
);
assert.match(source, /textContent\s*=/, "transcripts must be rendered as text, not injected HTML");
assert.doesNotMatch(
  source,
  /\.innerHTML\s*=|insertAdjacentHTML\s*\(|document\.write\s*\(/,
  "listener content must never be rendered through HTML injection sinks",
);
assert.match(
  source,
  /channelApiUrl\("\/api\/status"\)/,
  "a language-pinned page must request only its own channel status",
);
assert.match(
  source,
  /channelApiUrl\("\/api\/transcripts"\)/,
  "a language-pinned page must request only its own transcript",
);
assert.match(
  source,
  /const requestedChannel = channelSelect\.value;[\s\S]*channelId: requestedChannel,[\s\S]*\/ws\/\$\{encodeURIComponent\(request\.channelId\)\}/,
  "initial and reconnected websockets must remain bound to the captured channel",
);

assert.match(source, /let playbackGeneration = 0;/, "playback requests need a monotonic generation");
assert.match(
  source,
  /targetSocket\.onopen = null;[\s\S]*targetSocket\.onmessage = null;[\s\S]*targetSocket\.onerror = null;[\s\S]*targetSocket\.onclose = null;/,
  "all websocket callbacks must be detached before a runtime is closed",
);
assert.match(
  source,
  /headers\["If-None-Match"\] = transcriptEtag;/,
  "transcript polling must reuse the last server ETag",
);
assert.match(
  source,
  /if \(response\.status === 304\) return;/,
  "a 304 response must preserve the rendered transcript",
);
assert.match(
  source,
  /if \(transcriptRequestInFlight\) \{[\s\S]*return transcriptRequestInFlight;/,
  "overlapping transcript refreshes must share one in-flight request",
);
assert.match(
  source,
  /const MAX_CACHED_TRANSCRIPT_CHARS = 512 \* 1024;/,
  "language-only transcript repaints need a bounded last-payload cache",
);
assert.match(
  source,
  /left\.accessToken === right\.accessToken[\s\S]*left\.url === right\.url/,
  "cached transcripts and ETags must remain scoped to the channel URL and listener credential",
);
assert.match(
  source,
  /const MAX_BUFFERED_AUDIO_SECONDS = 4;/,
  "the browser audio queue needs a finite live-listening bound",
);
assert.match(
  source,
  /discardScheduledAudio\(request\.audioContext\);[\s\S]*startAt = now \+ minimumLead;/,
  "moving an over-limit queue to live must stop scheduled PCM before resetting its clock",
);
assert.match(
  source,
  /RECONNECT_BASE_DELAY_MS \* \(2 \*\* Math\.min\(reconnectAttempt, 8\)\)/,
  "websocket retry must use bounded exponential backoff",
);
assert.match(
  source,
  /function stopRuntime\(\) \{[\s\S]*cancelPlaybackReconnect\(\);/,
  "pause and stop must cancel a pending websocket retry",
);
assert.doesNotMatch(
  source,
  /source:\s*"원문\(한국어\)"/,
  "the listener must not claim that a dynamically selected input language is Korean",
);
assert.doesNotMatch(
  listenerIndex,
  />원문\(한국어\)</,
  "the initial transcript option must use the dynamic source label",
);
assert.match(
  listenerIndex,
  /느린 배속의 누적 지연은 최대 4초입니다/,
  "listeners must be told when slow playback automatically returns to live",
);

class MockElement {
  constructor(name = "") {
    this.name = name;
    this.listeners = new Map();
    this.children = [];
    this.className = "";
    this.disabled = false;
    this.hidden = false;
    this.open = false;
    this.checked = false;
    this.scrollTop = 0;
    this.scrollHeight = 0;
    this.clientHeight = 0;
    this.tabIndex = 0;
    this.textContent = "";
    this.value = "";
    this.classList = {
      add: () => {},
      remove: () => {},
    };
  }

  addEventListener(type, listener) {
    const existing = this.listeners.get(type) || [];
    existing.push(listener);
    this.listeners.set(type, existing);
  }

  dispatch(type, event = {}) {
    return (this.listeners.get(type) || []).map((listener) => listener({
      preventDefault: () => {},
      key: "",
      ...event,
    }));
  }

  append(...children) {
    this.children.push(...children);
  }

  replaceChildren(...children) {
    this.children = children;
    if (this.name === "#channel" || this.name === "#transcript-language") {
      const values = children.map((child) => child.value);
      if (!values.includes(this.value)) this.value = values[0] || "";
    }
  }

  setAttribute() {}
  focus() {}

  showModal() {
    this.open = true;
  }

  close() {
    this.open = false;
  }
}

function mockJsonResponse(payload, { status = 200, etag = "" } = {}) {
  const encoded = JSON.stringify(payload);
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (name) => name.toLowerCase() === "etag" && etag ? etag : null,
    },
    json: async () => payload,
    text: async () => encoded,
  };
}

function createListenerHarness({
  resumeModes = [],
  fetchImpl = null,
  locationHash = "",
  locationPath = "/",
} = {}) {
  const elements = new Map();
  const element = (selector) => {
    if (!elements.has(selector)) elements.set(selector, new MockElement(selector));
    return elements.get(selector);
  };
  element("#playback-rate").value = "1";
  element("#transcript-follow").checked = true;

  const contexts = [];
  class MockAudioContext {
    constructor() {
      this.mode = resumeModes[contexts.length] || "immediate";
      this.state = "suspended";
      this.sampleRate = 48000;
      this.currentTime = 1;
      this.baseLatency = 0.01;
      this.destination = {};
      this.sources = [];
      this.closeCount = 0;
      this.resumeResolver = null;
      contexts.push(this);
    }

    createGain() {
      return {
        gain: { value: 0 },
        connect: () => {},
        disconnect: () => {},
      };
    }

    resume() {
      if (this.mode === "delayed") {
        return new Promise((resolve) => {
          this.resumeResolver = () => {
            this.state = "running";
            resolve();
          };
        });
      }
      this.state = "running";
      return Promise.resolve();
    }

    close() {
      this.closeCount += 1;
      this.state = "closed";
      return Promise.resolve();
    }

    createBuffer(_channels, length, sampleRate) {
      return {
        duration: length / sampleRate,
        copyToChannel: () => {},
      };
    }

    createBufferSource() {
      const sourceNode = {
        playbackRate: { value: 1 },
        connect: () => {},
        disconnect: () => {},
        stop: () => { sourceNode.stopped = true; },
        start: (startAt) => {
          sourceNode.startAt = startAt;
          this.sources.push(sourceNode);
        },
        stopped: false,
        onended: null,
      };
      return sourceNode;
    }
  }

  const sockets = [];
  class MockWebSocket {
    constructor(url) {
      this.url = url;
      this.binaryType = "";
      this.closed = false;
      this.onopen = null;
      this.onmessage = null;
      this.onerror = null;
      this.onclose = null;
      this.handlersAtClose = null;
      sockets.push(this);
    }

    close() {
      this.handlersAtClose = {
        open: this.onopen,
        message: this.onmessage,
        error: this.onerror,
        close: this.onclose,
      };
      this.closed = true;
    }
  }

  let nextTimeoutId = 1;
  const timeouts = [];
  const setTimeout = (callback, delayMillis) => {
    const timer = {
      active: true,
      callback,
      delayMillis,
      id: nextTimeoutId,
    };
    nextTimeoutId += 1;
    timeouts.push(timer);
    return timer.id;
  };
  const clearTimeout = (timerId) => {
    const timer = timeouts.find((candidate) => candidate.id === timerId);
    if (timer) timer.active = false;
  };

  const channels = [
    { id: "en", name: "영어", languageTag: "en-US" },
    { id: "ja", name: "일본어", languageTag: "ja-JP" },
  ];
  const sessionValues = new Map();
  const sandbox = {
    ArrayBuffer,
    DataView,
    Float32Array,
    Math,
    Number,
    URLSearchParams,
    WebSocket: MockWebSocket,
    clearInterval: () => {},
    clearTimeout,
    console,
    document: {
      querySelector: element,
      createElement: (tag) => new MockElement(tag),
      createDocumentFragment: () => new MockElement("fragment"),
      title: "",
    },
    fetch: (url, options = {}) => {
      if (fetchImpl) return fetchImpl(url, options);
      const payload = url === "/api/session"
        ? { access: "public" }
        : url.startsWith("/api/status")
          ? { channels }
          : [];
      return Promise.resolve(mockJsonResponse(payload));
    },
    history: { replaceState: () => {} },
    location: {
      hash: locationHash,
      host: "guidecast.test:8787",
      pathname: locationPath,
      protocol: "http:",
      search: "",
    },
    sessionStorage: {
      getItem: (key) => sessionValues.get(key) || null,
      removeItem: (key) => sessionValues.delete(key),
      setItem: (key, value) => sessionValues.set(key, value),
    },
    setInterval: () => 1,
    setTimeout,
  };
  sandbox.window = sandbox;
  sandbox.AudioContext = MockAudioContext;
  const context = vm.createContext(sandbox);
  vm.runInContext(source, context, { filename: playerUrl.pathname });

  return {
    contexts,
    diagnostics: () => sandbox.window.__guideCastDiagnostics(),
    element,
    flush: async () => {
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    },
    loadTranscripts: (forceRefresh) => context.loadTranscripts(forceRefresh),
    pendingTimeouts: () => timeouts.filter((timer) => timer.active),
    runNextTimeout: () => {
      const timer = timeouts.find((candidate) => candidate.active);
      if (!timer) return null;
      timer.active = false;
      timer.callback();
      return timer;
    },
    sockets,
  };
}

async function verifyDelayedResumeCannotUndoPause() {
  const harness = createListenerHarness({ resumeModes: ["delayed"] });
  await harness.flush();

  const [playRequest] = harness.element("#play").dispatch("click");
  assert.equal(harness.contexts.length, 1, "play must create one audio context");
  assert.equal(typeof harness.contexts[0].resumeResolver, "function");

  harness.element("#pause").dispatch("click");
  assert.equal(harness.element("#status").textContent, "일시정지됨");
  harness.contexts[0].resumeResolver();
  await playRequest;
  await harness.flush();

  assert.equal(harness.sockets.length, 0, "a resume completed after pause must not open a websocket");
  assert.equal(harness.diagnostics().desiredState, "paused");
  assert.equal(harness.diagnostics().scheduledSources, 0);
  assert.equal(harness.element("#status").textContent, "일시정지됨");
}

function captureSocketCallbacks(socket) {
  return {
    close: socket.onclose,
    error: socket.onerror,
    message: socket.onmessage,
    open: socket.onopen,
  };
}

function assertSocketDetached(socket) {
  assert.equal(socket.onopen, null);
  assert.equal(socket.onmessage, null);
  assert.equal(socket.onerror, null);
  assert.equal(socket.onclose, null);
  assert.equal(socket.closed, true);
  assert.deepEqual(
    socket.handlersAtClose,
    { open: null, message: null, error: null, close: null },
    "websocket callbacks must be detached before close() is invoked",
  );
}

function audiblePcmFrame() {
  const samples = new Int16Array([0, 4096, -4096, 2048]);
  return samples.buffer.slice(samples.byteOffset, samples.byteOffset + samples.byteLength);
}

function twentyMillisecondPcmFrame() {
  const samples = new Int16Array(320);
  for (let index = 0; index < samples.length; index += 1) {
    samples[index] = index % 2 === 0 ? 4096 : -4096;
  }
  return samples.buffer.slice(samples.byteOffset, samples.byteOffset + samples.byteLength);
}

async function verifyRapidLanguageSwitchKeepsOnlyNewestGeneration() {
  const harness = createListenerHarness();
  await harness.flush();
  const channel = harness.element("#channel");

  const [firstPlay] = harness.element("#play").dispatch("click");
  await firstPlay;
  const firstEnSocket = harness.sockets[0];
  const staleEn = captureSocketCallbacks(firstEnSocket);

  channel.value = "ja";
  channel.dispatch("change");
  await harness.flush();
  const jaSocket = harness.sockets[1];
  const staleJa = captureSocketCallbacks(jaSocket);

  channel.value = "en";
  channel.dispatch("change");
  await harness.flush();
  const currentEnSocket = harness.sockets[2];

  assert.deepEqual(
    harness.sockets.map((socket) => socket.url),
    [
      "ws://guidecast.test:8787/ws/en",
      "ws://guidecast.test:8787/ws/ja",
      "ws://guidecast.test:8787/ws/en",
    ],
  );
  assertSocketDetached(firstEnSocket);
  assertSocketDetached(jaSocket);

  currentEnSocket.onopen();
  const currentStatus = harness.element("#status").textContent;
  const pcm = audiblePcmFrame();
  for (const stale of [staleEn, staleJa]) {
    stale.open();
    stale.message({ data: pcm });
    stale.error();
    stale.close();
  }

  assert.equal(
    harness.element("#status").textContent,
    currentStatus,
    "stale websocket callbacks must not replace the newest channel status",
  );
  assert.equal(harness.contexts[2].sources.length, 0, "stale PCM must not be scheduled");
  assert.equal(harness.diagnostics().receivedFrames, 0, "stale PCM must not update diagnostics");

  currentEnSocket.onmessage({ data: JSON.stringify({ type: "config", sampleRate: 16000 }) });
  currentEnSocket.onmessage({ data: pcm });
  assert.equal(harness.contexts[2].sources.length, 1, "the newest channel PCM must play");
  assert.equal(harness.diagnostics().receivedFrames, 1);
  assert.equal(harness.element("#status").textContent, "음성 수신·재생 중");
}

async function verifyTranscriptPollingCoalescesAndRevalidates() {
  const transcriptRequests = [];
  let resolveFirstTranscript = null;
  let notModifiedTextRead = false;
  const channels = [
    { id: "en", name: "영어", languageTag: "en-US" },
  ];
  const harness = createListenerHarness({
    fetchImpl: (url, options) => {
      if (url === "/api/session") {
        return Promise.resolve(mockJsonResponse({ access: "public" }));
      }
      if (url.startsWith("/api/status")) {
        return Promise.resolve(mockJsonResponse({ channels }));
      }
      if (!url.startsWith("/api/transcripts")) {
        return Promise.reject(new Error(`Unexpected request: ${url}`));
      }
      transcriptRequests.push({ options, url });
      if (transcriptRequests.length === 1) {
        return new Promise((resolve) => {
          resolveFirstTranscript = resolve;
        });
      }
      return Promise.resolve({
        ok: false,
        status: 304,
        headers: { get: () => '"gc-transcript-one"' },
        json: async () => null,
        text: async () => {
          notModifiedTextRead = true;
          throw new Error("304 responses have no representation body");
        },
      });
    },
  });
  await harness.flush();

  const first = harness.loadTranscripts(true);
  const duplicate = harness.loadTranscripts(true);
  assert.strictEqual(first, duplicate, "manual refresh and polling must share one request");
  assert.equal(transcriptRequests.length, 1);
  assert.equal(typeof resolveFirstTranscript, "function");

  resolveFirstTranscript(mockJsonResponse({
    transcripts: [{
      sequence: 1,
      sourceText: "평화를 함께 걷습니다.",
      isFinal: true,
      translations: { en: "We walk together for peace." },
    }],
    count: 1,
  }, { etag: '"gc-transcript-one"' }));
  await Promise.all([first, duplicate]);

  const revalidation = harness.loadTranscripts(true);
  const renderedBeforeNotModified = harness.element("#transcript-list").children[0];
  await revalidation;

  assert.equal(transcriptRequests.length, 2);
  assert.equal(transcriptRequests[1].options.cache, "no-cache");
  assert.equal(
    transcriptRequests[1].options.headers["If-None-Match"],
    '"gc-transcript-one"',
  );
  assert.equal(notModifiedTextRead, false, "304 must not try to parse an absent body");
  assert.strictEqual(
    harness.element("#transcript-list").children[0],
    renderedBeforeNotModified,
    "the 304 response itself must leave the repainted transcript and scroll state untouched",
  );
}

function collectText(element) {
  return [
    element.textContent,
    ...element.children.map((child) => collectText(child)),
  ].filter(Boolean).join(" ");
}

async function verifyTranscriptLanguageChangeRepaintsDuringNotModifiedRequest() {
  const transcriptRequests = [];
  let resolveConditionalTranscript = null;
  const payload = {
    transcripts: [{
      sequence: 1,
      sourceText: "평화를 함께 걷습니다.",
      isFinal: true,
      translations: { en: "We walk together for peace." },
    }],
    count: 1,
  };
  const channels = [
    { id: "source", name: "원음", languageTag: "ko" },
    { id: "en", name: "영어", languageTag: "en-US" },
  ];
  const harness = createListenerHarness({
    fetchImpl: (url, options) => {
      if (url === "/api/session") {
        return Promise.resolve(mockJsonResponse({ access: "public" }));
      }
      if (url.startsWith("/api/status")) {
        return Promise.resolve(mockJsonResponse({ channels }));
      }
      if (!url.startsWith("/api/transcripts")) {
        return Promise.reject(new Error(`Unexpected request: ${url}`));
      }
      transcriptRequests.push({ options, url });
      if (transcriptRequests.length === 1) {
        return Promise.resolve(mockJsonResponse(payload, { etag: '"gc-language-static"' }));
      }
      return new Promise((resolve) => {
        resolveConditionalTranscript = resolve;
      });
    },
  });
  await harness.flush();

  const language = harness.element("#transcript-language");
  const transcriptList = harness.element("#transcript-list");
  language.value = "source";
  await harness.loadTranscripts(true);
  assert.match(collectText(transcriptList), /선택 표시: 확정/);

  harness.element("#transcript-follow").checked = false;
  transcriptList.scrollTop = 37;
  const conditionalRequest = harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 2);
  assert.equal(
    transcriptRequests[1].options.headers["If-None-Match"],
    '"gc-language-static"',
  );

  language.value = "en";
  const [languageRefresh] = language.dispatch("change");
  assert.strictEqual(
    languageRefresh,
    conditionalRequest,
    "a language change during polling must reuse the active request",
  );
  assert.equal(transcriptRequests.length, 2, "language repaint must not start a fetch storm");
  assert.match(collectText(transcriptList), /영어: We walk together for peace\./);
  assert.doesNotMatch(collectText(transcriptList), /선택 표시: 확정/);
  assert.equal(transcriptList.scrollTop, 37, "language repaint must preserve manual scroll position");

  resolveConditionalTranscript({
    ok: false,
    status: 304,
    headers: { get: () => '"gc-language-static"' },
    text: async () => {
      throw new Error("304 responses have no representation body");
    },
  });
  await Promise.all([conditionalRequest, languageRefresh]);

  assert.match(collectText(transcriptList), /영어: We walk together for peace\./);
  assert.doesNotMatch(collectText(transcriptList), /선택 표시: 확정/);
  assert.equal(transcriptList.scrollTop, 37);
}

async function verifySlowPlaybackQueueStaysBoundedForFiveMinutes() {
  const harness = createListenerHarness();
  await harness.flush();
  harness.element("#playback-rate").value = "0.75";
  harness.element("#playback-rate").dispatch("change");

  const [playRequest] = harness.element("#play").dispatch("click");
  await playRequest;
  const listenerSocket = harness.sockets[0];
  listenerSocket.onopen();
  listenerSocket.onmessage({
    data: JSON.stringify({ type: "config", sampleRate: 16000 }),
  });

  const frame = twentyMillisecondPcmFrame();
  let maximumObservedBuffer = 0;
  for (let frameIndex = 0; frameIndex < 15_000; frameIndex += 1) {
    harness.contexts[0].currentTime += 0.02;
    listenerSocket.onmessage({ data: frame });
    if (frameIndex % 50 === 0) {
      maximumObservedBuffer = Math.max(
        maximumObservedBuffer,
        harness.diagnostics().bufferedSeconds,
      );
    }
  }

  const diagnostics = harness.diagnostics();
  maximumObservedBuffer = Math.max(maximumObservedBuffer, diagnostics.bufferedSeconds);
  assert.equal(diagnostics.receivedFrames, 15_000);
  assert.ok(
    diagnostics.automaticLiveEdgeDrops > 0,
    "0.75× live playback must eventually drop stale scheduled audio",
  );
  assert.ok(
    maximumObservedBuffer <= diagnostics.maxBufferedAudioSeconds + 0.001,
    `five-minute fake live input exceeded its queue bound: ${maximumObservedBuffer}`,
  );
  assert.ok(
    diagnostics.scheduledSources < 700,
    "scheduled AudioBufferSource nodes must remain bounded between live-edge drops",
  );
}

async function verifyClosedSocketReconnectsAndStopCancelsRetry() {
  const harness = createListenerHarness({ locationHash: "#token=listener-token" });
  await harness.flush();

  const [playRequest] = harness.element("#play").dispatch("click");
  await playRequest;
  const expectedUrl = "ws://guidecast.test:8787/ws/en?token=listener-token";
  harness.sockets[0].onopen();
  const retryDelays = [];

  for (let attempt = 0; attempt < 8; attempt += 1) {
    const disconnectedSocket = harness.sockets[harness.sockets.length - 1];
    const closeCallback = disconnectedSocket.onclose;
    closeCallback();
    closeCallback();
    assert.equal(
      harness.pendingTimeouts().length,
      1,
      "duplicate close delivery must not schedule duplicate reconnects",
    );
    retryDelays.push(harness.pendingTimeouts()[0].delayMillis);
    harness.runNextTimeout();
  }

  assert.deepEqual(retryDelays, [250, 500, 1000, 2000, 4000, 5000, 5000, 5000]);
  assert.equal(harness.contexts.length, 1, "reconnect must reuse the active AudioContext");
  assert.ok(
    harness.sockets.every((candidate) => candidate.url === expectedUrl),
    "every retry must retain the original channel and bearer token",
  );

  const beforePauseSocketCount = harness.sockets.length;
  harness.sockets[harness.sockets.length - 1].onclose();
  assert.equal(harness.pendingTimeouts().length, 1);
  harness.element("#pause").dispatch("click");
  assert.equal(harness.pendingTimeouts().length, 0, "pause must cancel reconnect");
  assert.equal(harness.runNextTimeout(), null);
  assert.equal(harness.sockets.length, beforePauseSocketCount);
  assert.equal(harness.contexts[0].closeCount, 1);

  const [restartedPlay] = harness.element("#play").dispatch("click");
  await restartedPlay;
  const restartedSocket = harness.sockets[harness.sockets.length - 1];
  restartedSocket.onclose();
  assert.equal(harness.pendingTimeouts().length, 1);
  harness.element("#stop").dispatch("click");
  assert.equal(harness.pendingTimeouts().length, 0, "stop must cancel reconnect");
  assert.equal(harness.runNextTimeout(), null);
}

async function verifyPinnedLanguageCanSwitchToOriginal() {
  const channels = [
    { id: "source", name: "원음", languageTag: "ko" },
    { id: "en", name: "영어", languageTag: "en" },
  ];
  const harness = createListenerHarness({
    locationPath: "/en",
    fetchImpl: (url) => Promise.resolve(mockJsonResponse(
      url === "/api/session" ? { access: "public" } :
        url.startsWith("/api/status") ? { channels } : [],
    )),
  });
  await harness.flush();
  const selector = harness.element("#channel");
  assert.equal(selector.disabled, false, "language link must allow original audio");
  assert.equal(selector.value, "en", "language URL keeps its default translation");
  assert.deepEqual(selector.children.map((entry) => entry.value), ["source", "en"]);
  harness.element("#play").dispatch("click");
  await harness.flush();
  selector.value = "source";
  selector.dispatch("change");
  await harness.flush();
  assert.match(harness.sockets.at(-1).url, /\/source(?:\?|$)/);
  assert.equal(harness.sockets.length, 2);
  assert.equal(harness.sockets[0].closed, true, "old translation socket must close on source switch");
}

await verifyPinnedLanguageCanSwitchToOriginal();
await verifyDelayedResumeCannotUndoPause();
await verifyRapidLanguageSwitchKeepsOnlyNewestGeneration();
await verifyTranscriptPollingCoalescesAndRevalidates();
await verifyTranscriptLanguageChangeRepaintsDuringNotModifiedRequest();
await verifySlowPlaybackQueueStaysBoundedForFiveMinutes();
await verifyClosedSocketReconnectsAndStopCancelsRetry();

console.log("GuideCast listener scheduling regression checks passed");
