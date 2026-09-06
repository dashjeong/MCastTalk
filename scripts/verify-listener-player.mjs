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
  /response\.status === 429/,
  "listener must handle 429 rate limits",
);
assert.match(
  source,
  /Retry-After/,
  "listener must parse Retry-After backoff header",
);
assert.match(
  source,
  /#transcript-status/,
  "listener must bind transcript status element",
);
assert.match(
  source,
  /visibilitychange/,
  "listener must observe tab visibility changes",
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
    const classes = new Set();
    this.classList = {
      add: (...tokens) => {
        for (const token of tokens) classes.add(token);
        this.className = [...classes].join(" ");
      },
      remove: (...tokens) => {
        for (const token of tokens) classes.delete(token);
        this.className = [...classes].join(" ");
      },
      contains: (token) => classes.has(token),
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
  element("#transcript-status").hidden = true;

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
  const documentListeners = new Map();
  const mockDocument = {
    querySelector: element,
    createElement: (tag) => new MockElement(tag),
    createDocumentFragment: () => new MockElement("fragment"),
    title: "",
    visibilityState: "visible",
    addEventListener: (type, listener) => {
      const list = documentListeners.get(type) || [];
      list.push(listener);
      documentListeners.set(type, list);
    },
    dispatch: (type, event = {}) => {
      return (documentListeners.get(type) || []).map((listener) => listener(event));
    },
  };
  let currentTimeMs = 1_700_000_000_000;
  class MockDate extends Date {
    constructor(...args) {
      if (args.length === 0) {
        super(currentTimeMs);
      } else {
        super(...args);
      }
    }
    static now() {
      return currentTimeMs;
    }
    static parse(str) {
      return Date.parse(str);
    }
  }

  const sandbox = {
    ArrayBuffer,
    DataView,
    Date: MockDate,
    Float32Array,
    Math,
    Number,
    URLSearchParams,
    WebSocket: MockWebSocket,
    clearInterval: () => {},
    clearTimeout,
    console,
    document: mockDocument,
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
    advanceTime: (ms) => { currentTimeMs += ms; },
    contexts,
    diagnostics: () => sandbox.window.__guideCastDiagnostics(),
    document: mockDocument,
    element,
    flush: async () => {
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    },
    loadTranscripts: (forceRefresh) => context.loadTranscripts(forceRefresh),
    location: sandbox.location,
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

async function verifyTranscriptRetainsOn429WithRetryAfterBackoff() {
  const transcriptRequests = [];
  let responseMode = "initial";
  const payload = {
    transcripts: [{
      sequence: 1,
      sourceText: "도착 안내 방송입니다.",
      isFinal: true,
      translations: { en: "This is an arrival announcement." },
    }],
    count: 1,
  };

  const harness = createListenerHarness({
    fetchImpl: (url, options) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "public" }));
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (!url.startsWith("/api/transcripts")) return Promise.reject(new Error(`Unexpected request: ${url}`));
      transcriptRequests.push({ options, url });
      if (responseMode === "initial") {
        return Promise.resolve(mockJsonResponse(payload, { etag: '"gc-429-test"' }));
      }
      if (responseMode === "rateLimited") {
        return Promise.resolve({
          ok: false,
          status: 429,
          headers: {
            get: (name) => name.toLowerCase() === "retry-after" ? "5" : null,
          },
          json: async () => null,
          text: async () => "Rate limit exceeded",
        });
      }
      if (responseMode === "recovered") {
        return Promise.resolve({
          ok: false,
          status: 304,
          headers: { get: () => '"gc-429-test"' },
          json: async () => null,
          text: async () => { throw new Error("304 has no body"); },
        });
      }
      throw new Error(`Unknown mode: ${responseMode}`);
    },
  });
  await harness.flush();

  const transcriptList = harness.element("#transcript-list");
  const transcriptStatus = harness.element("#transcript-status");

  // Step 1: initial 200 load
  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 1);
  assert.match(collectText(transcriptList), /도착 안내 방송입니다\./);
  assert.equal(transcriptStatus.hidden, true);
  assert.equal(transcriptList.classList.contains("is-stale"), false);

  // Step 2: second poll receives 429 with Retry-After: 5 seconds
  harness.advanceTime(1000);
  responseMode = "rateLimited";
  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 2);
  // Transcript text must be RETAINED! Not cleared!
  assert.match(collectText(transcriptList), /도착 안내 방송입니다\./);
  // Status indicator must be visible and warn about delay
  assert.equal(transcriptStatus.hidden, false);
  assert.match(transcriptStatus.textContent, /스크립트 갱신 지연/);
  assert.equal(transcriptList.classList.contains("is-stale"), true);

  // Step 3: manual refresh (forceRefresh) during backoff window must ALSO be blocked
  harness.advanceTime(2000); // 2s into 5s backoff
  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 2, "forceRefresh during active backoff must not flood the server");

  // Step 4: after backoff window expires, subsequent poll executes and clears stale status
  harness.advanceTime(4000); // Now 6s since 429, backoff expired
  responseMode = "recovered";
  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 3);
  assert.match(collectText(transcriptList), /도착 안내 방송입니다\./);
  assert.equal(transcriptStatus.hidden, true, "stale status must hide upon recovery");
  assert.equal(transcriptList.classList.contains("is-stale"), false, "is-stale class must be removed upon recovery");
}

async function verifyTranscript429ParsesHttpDateHeader() {
  const transcriptRequests = [];
  let responseMode = "initial";
  const httpDateString = new Date(1_700_000_000_000 + 10_000).toUTCString();

  const harness = createListenerHarness({
    fetchImpl: (url, options) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "public" }));
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (!url.startsWith("/api/transcripts")) return Promise.reject(new Error(`Unexpected: ${url}`));
      transcriptRequests.push({ options, url });
      if (responseMode === "initial") {
        return Promise.resolve(mockJsonResponse({ transcripts: [{ sequence: 1, sourceText: "테스트", isFinal: true }], count: 1 }));
      }
      if (responseMode === "rateLimited") {
        return Promise.resolve({
          ok: false,
          status: 429,
          headers: { get: (name) => name.toLowerCase() === "retry-after" ? httpDateString : null },
          json: async () => null,
          text: async () => "Rate limited",
        });
      }
      return Promise.resolve(mockJsonResponse({ transcripts: [{ sequence: 1, sourceText: "테스트", isFinal: true }], count: 1 }));
    },
  });
  await harness.flush();

  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 1);

  // Receive 429 with HTTP-date header (+10 seconds)
  responseMode = "rateLimited";
  harness.advanceTime(1000);
  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 2);

  // At +5 seconds, backoff is still active
  harness.advanceTime(4000);
  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 2, "HTTP-date backoff must block requests during window");

  // At +11 seconds, backoff expired
  harness.advanceTime(6000);
  responseMode = "recovered";
  await harness.loadTranscripts(true);
  assert.equal(transcriptRequests.length, 3, "HTTP-date backoff must allow requests once expired");
}

async function verifyTranscriptClearsOnAuthLoss() {
  let statusToReturn = 200;
  const payload = {
    transcripts: [{
      sequence: 1,
      sourceText: "인증 테스트입니다.",
      isFinal: true,
      translations: { en: "Auth test." },
    }],
    count: 1,
  };
  const harness = createListenerHarness({
    fetchImpl: (url) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "pin" }));
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (!url.startsWith("/api/transcripts")) return Promise.reject(new Error(`Unexpected: ${url}`));
      if (statusToReturn === 200) {
        return Promise.resolve(mockJsonResponse(payload, { etag: '"gc-auth-1"' }));
      }
      return Promise.resolve({
        ok: false,
        status: statusToReturn,
        headers: { get: () => null },
        json: async () => null,
        text: async () => "Auth failure",
      });
    },
  });
  await harness.flush();

  const transcriptList = harness.element("#transcript-list");
  const transcriptStatus = harness.element("#transcript-status");
  await harness.loadTranscripts(true);
  assert.match(collectText(transcriptList), /인증 테스트입니다\./);

  // Auth lost (401)
  statusToReturn = 401;
  await harness.loadTranscripts(true);
  assert.doesNotMatch(collectText(transcriptList), /인증 테스트입니다\./);
  assert.match(collectText(transcriptList), /아직 수신한 스크립트가 없습니다\./);
  assert.equal(transcriptStatus.hidden, true);
  assert.equal(transcriptList.classList.contains("is-stale"), false);

  // Recover 200
  statusToReturn = 200;
  await harness.loadTranscripts(true);
  assert.match(collectText(transcriptList), /인증 테스트입니다\./);

  // Access denied (403 Forbidden) must also clear prior transcript!
  statusToReturn = 403;
  await harness.loadTranscripts(true);
  assert.doesNotMatch(collectText(transcriptList), /인증 테스트입니다\./, "403 Forbidden must clear prior transcript");
  assert.match(collectText(transcriptList), /아직 수신한 스크립트가 없습니다\./);
  assert.equal(transcriptStatus.hidden, true);
  assert.equal(transcriptList.classList.contains("is-stale"), false);
}

async function verifyTranscriptClearsOnSessionChange() {
  let joinToken = "token-session-1";
  const session1Payload = {
    transcripts: [{
      sequence: 1,
      sourceText: "세션 1 스크립트",
      isFinal: true,
      translations: { en: "Session 1 script" },
    }],
    count: 1,
  };
  const session2Payload = {
    transcripts: [{
      sequence: 2,
      sourceText: "세션 2 스크립트",
      isFinal: true,
      translations: { en: "Session 2 script" },
    }],
    count: 1,
  };

  const harness = createListenerHarness({
    fetchImpl: (url, options = {}) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "pin" }));
      if (url === "/api/join") {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => null },
          json: async () => null,
          text: async () => joinToken,
        });
      }
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (url.startsWith("/api/transcripts")) {
        const auth = options.headers?.Authorization || "";
        if (auth.includes("token-session-1")) {
          return Promise.resolve(mockJsonResponse(session1Payload));
        }
        if (auth.includes("token-session-2")) {
          return Promise.resolve(mockJsonResponse(session2Payload));
        }
        return Promise.resolve({
          ok: false,
          status: 401,
          headers: { get: () => null },
          json: async () => null,
          text: async () => "Unauthorized",
        });
      }
      return Promise.reject(new Error(`Unexpected: ${url}`));
    },
  });
  await harness.flush();

  // Join session 1
  harness.element("#pin").value = "1234";
  const [join1] = harness.element("#pin-form").dispatch("submit");
  await join1;
  await harness.flush();

  const transcriptList = harness.element("#transcript-list");
  await harness.loadTranscripts(true);
  assert.match(collectText(transcriptList), /세션 1 스크립트/);

  // Switch to session 2 by joining with new credentials
  joinToken = "token-session-2";
  harness.element("#pin").value = "5678";
  const [join2] = harness.element("#pin-form").dispatch("submit");
  await join2;
  await harness.flush();

  // Loading transcripts under new session must clear old session text
  await harness.loadTranscripts(true);
  assert.doesNotMatch(collectText(transcriptList), /세션 1 스크립트/);
  assert.match(collectText(transcriptList), /세션 2 스크립트/);
}

async function verifyTranscriptScopeChangeResetsBackoff() {
  let joinToken = "token-backoff-1";
  const requests = [];

  const harness = createListenerHarness({
    fetchImpl: (url, options = {}) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "pin" }));
      if (url === "/api/join") {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => null },
          json: async () => null,
          text: async () => joinToken,
        });
      }
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (url.startsWith("/api/transcripts")) {
        requests.push({ url, auth: options.headers?.Authorization });
        if (options.headers?.Authorization?.includes("token-backoff-1")) {
          return Promise.resolve({
            ok: false,
            status: 429,
            headers: { get: (name) => name.toLowerCase() === "retry-after" ? "60" : null },
            json: async () => null,
            text: async () => "Rate limited for 60s",
          });
        }
        return Promise.resolve(mockJsonResponse({
          transcripts: [{ sequence: 1, sourceText: "새 세션", isFinal: true }],
          count: 1,
        }));
      }
      return Promise.reject(new Error(`Unexpected: ${url}`));
    },
  });
  await harness.flush();

  // Join session 1
  harness.element("#pin").value = "1111";
  const [j1] = harness.element("#pin-form").dispatch("submit");
  await j1;
  await harness.flush();

  // Load triggers 429 with 60s backoff
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 1);

  // Switch to session 2
  joinToken = "token-backoff-2";
  harness.element("#pin").value = "2222";
  const [j2] = harness.element("#pin-form").dispatch("submit");
  await j2;
  await harness.flush();

  // Scope change must have cleared old 60s backoff so new request goes through immediately!
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 2, "scope change must reset old scope backoff immediately");
  assert.ok(requests[1].auth.includes("token-backoff-2"));
}

async function verifyTransient5xxAppliesBackoff() {
  const requests = [];
  let statusToReturn = 200;
  const harness = createListenerHarness({
    fetchImpl: (url) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "public" }));
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (url.startsWith("/api/transcripts")) {
        requests.push(url);
        if (statusToReturn === 200) {
          return Promise.resolve(mockJsonResponse({ transcripts: [{ sequence: 1, sourceText: "정상", isFinal: true }], count: 1 }));
        }
        return Promise.resolve({
          ok: false,
          status: statusToReturn,
          headers: { get: () => null },
          json: async () => null,
          text: async () => "Server Error",
        });
      }
      return Promise.reject(new Error(`Unexpected: ${url}`));
    },
  });
  await harness.flush();

  await harness.loadTranscripts(true);
  assert.equal(requests.length, 1);

  // Next poll hits 500
  statusToReturn = 500;
  harness.advanceTime(1000);
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 2);

  const transcriptList = harness.element("#transcript-list");
  const transcriptStatus = harness.element("#transcript-status");
  // Stale content retained
  assert.match(collectText(transcriptList), /정상/);
  assert.equal(transcriptStatus.hidden, false);

  // Immediate poll before backoff window is blocked
  await harness.loadTranscripts(false);
  assert.equal(requests.length, 2, "transient failure backoff must delay next poll");

  // Advance time past transient backoff (1s)
  harness.advanceTime(1500);
  statusToReturn = 200;
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 3, "poll must proceed after transient backoff window");
  assert.equal(transcriptStatus.hidden, true);
}

async function verifyBackgroundTabSkipsRegularPolling() {
  let fetchCount = 0;
  const harness = createListenerHarness({
    fetchImpl: (url) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "public" }));
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (url.startsWith("/api/transcripts")) {
        fetchCount += 1;
        return Promise.resolve(mockJsonResponse({ transcripts: [], count: 0 }));
      }
      return Promise.reject(new Error(`Unexpected: ${url}`));
    },
  });
  await harness.flush();

  // Tab visible: initial refresh works
  await harness.loadTranscripts(true);
  assert.equal(fetchCount, 1);

  // Tab hidden
  harness.document.visibilityState = "hidden";
  // Regular interval poll must be skipped
  await harness.loadTranscripts(false);
  assert.equal(fetchCount, 1, "hidden tab must not poll regular transcript intervals");

  // Tab becomes visible again
  harness.document.visibilityState = "visible";
  // Refresh on visibility change works
  harness.advanceTime(1500);
  await harness.loadTranscripts(true);
  assert.equal(fetchCount, 2);
}

async function verifyEmptyCache429WithRepeatedManualClicks() {
  const requests = [];
  const harness = createListenerHarness({
    fetchImpl: (url) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "public" }));
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (url.startsWith("/api/transcripts")) {
        requests.push(url);
        return Promise.resolve({
          ok: false,
          status: 429,
          headers: { get: (name) => name.toLowerCase() === "retry-after" ? "4" : null },
          json: async () => null,
          text: async () => "Too Many Requests",
        });
      }
      return Promise.reject(new Error(`Unexpected: ${url}`));
    },
  });
  await harness.flush();

  // Initial load on empty cache -> receives 429 with Retry-After: 4
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 1);
  const transcriptList = harness.element("#transcript-list");
  assert.match(collectText(transcriptList), /스크립트를 읽지 못했습니다\./);

  // User spam-clicks refresh button 5 times over the next 2 seconds (during backoff window)
  for (let click = 0; click < 5; click += 1) {
    harness.advanceTime(400);
    await harness.loadTranscripts(true);
  }
  assert.equal(requests.length, 1, "repeated manual clicks during initial 429 backoff must not flood the server");

  // Advance time past 4 seconds
  harness.advanceTime(3000);
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 2, "manual click after backoff expires must be allowed");
}

async function verifyInitialNetworkFailureAppliesBackoff() {
  const requests = [];
  let shouldFail = true;
  const harness = createListenerHarness({
    fetchImpl: (url) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "public" }));
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (url.startsWith("/api/transcripts")) {
        requests.push(url);
        if (shouldFail) {
          return Promise.reject(new Error("Network connection dropped"));
        }
        return Promise.resolve(mockJsonResponse({
          transcripts: [{ sequence: 1, sourceText: "복구 완료", isFinal: true }],
          count: 1,
        }));
      }
      return Promise.reject(new Error(`Unexpected: ${url}`));
    },
  });
  await harness.flush();

  // Initial load fails with network error
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 1);
  assert.match(collectText(harness.element("#transcript-list")), /Network connection dropped/);

  // Immediate refresh must be blocked by transient failure backoff
  await harness.loadTranscripts(false);
  assert.equal(requests.length, 1, "immediate poll after network failure must be delayed");

  // Advance time past transient backoff (1s) and recover network
  harness.advanceTime(1500);
  shouldFail = false;
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 2);
  assert.match(collectText(harness.element("#transcript-list")), /복구 완료/);
}

async function verifyEmptyCacheScopeChangeClearsBackoffEvenWithoutSnapshot() {
  let joinToken = "token-empty-1";
  const requests = [];

  const harness = createListenerHarness({
    fetchImpl: (url, options = {}) => {
      if (url === "/api/session") return Promise.resolve(mockJsonResponse({ access: "pin" }));
      if (url === "/api/join") {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => null },
          json: async () => null,
          text: async () => joinToken,
        });
      }
      if (url.startsWith("/api/status")) return Promise.resolve(mockJsonResponse({ channels: [{ id: "en", name: "영어", languageTag: "en-US" }] }));
      if (url.startsWith("/api/transcripts")) {
        requests.push({ url, auth: options.headers?.Authorization });
        if (options.headers?.Authorization?.includes("token-empty-1")) {
          return Promise.resolve({
            ok: false,
            status: 429,
            headers: { get: (name) => name.toLowerCase() === "retry-after" ? "60" : null },
            json: async () => null,
            text: async () => "Rate limit",
          });
        }
        return Promise.resolve(mockJsonResponse({
          transcripts: [{ sequence: 1, sourceText: "새 토큰 성공", isFinal: true }],
          count: 1,
        }));
      }
      return Promise.reject(new Error(`Unexpected: ${url}`));
    },
  });
  await harness.flush();

  // Join session 1
  harness.element("#pin").value = "1000";
  const [j1] = harness.element("#pin-form").dispatch("submit");
  await j1;
  await harness.flush();

  // Initial fetch fails with 429 (no snapshot is ever created)
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 1);

  // Manual click on session 1 is suppressed
  harness.advanceTime(1000);
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 1);

  // Switch to session 2 (scope changes, even though no cached transcript snapshot ever existed!)
  joinToken = "token-empty-2";
  harness.element("#pin").value = "2000";
  const [j2] = harness.element("#pin-form").dispatch("submit");
  await j2;
  await harness.flush();

  // Request under new session must proceed immediately despite session 1's active 60s backoff!
  await harness.loadTranscripts(true);
  assert.equal(requests.length, 2, "scope change without snapshot must reset backoff immediately");
  assert.ok(requests[1].auth.includes("token-empty-2"));
  assert.match(collectText(harness.element("#transcript-list")), /새 토큰 성공/);
}

await verifyPinnedLanguageCanSwitchToOriginal();
await verifyDelayedResumeCannotUndoPause();
await verifyRapidLanguageSwitchKeepsOnlyNewestGeneration();
await verifyTranscriptPollingCoalescesAndRevalidates();
await verifyTranscriptLanguageChangeRepaintsDuringNotModifiedRequest();
await verifySlowPlaybackQueueStaysBoundedForFiveMinutes();
await verifyClosedSocketReconnectsAndStopCancelsRetry();
await verifyTranscriptRetainsOn429WithRetryAfterBackoff();
await verifyTranscript429ParsesHttpDateHeader();
await verifyTranscriptClearsOnAuthLoss();
await verifyTranscriptClearsOnSessionChange();
await verifyTranscriptScopeChangeResetsBackoff();
await verifyTransient5xxAppliesBackoff();
await verifyBackgroundTabSkipsRegularPolling();
await verifyEmptyCache429WithRepeatedManualClicks();
await verifyInitialNetworkFailureAppliesBackoff();
await verifyEmptyCacheScopeChangeClearsBackoffEvenWithoutSnapshot();

console.log("GuideCast listener scheduling regression checks passed");
