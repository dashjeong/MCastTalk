import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const speakerJsUrl = new URL(
  "../core/server/src/main/assets/speaker/speaker.js",
  import.meta.url,
);
const speakerJs = await readFile(speakerJsUrl, "utf8");

const speakerHtmlUrl = new URL(
  "../core/server/src/main/assets/speaker/speaker.html",
  import.meta.url,
);
const speakerHtml = await readFile(speakerHtmlUrl, "utf8");

const speakerCssUrl = new URL(
  "../core/server/src/main/assets/speaker/speaker.css",
  import.meta.url,
);
const speakerCss = await readFile(speakerCssUrl, "utf8");

// 1. Static security & implementation assertions
assert.doesNotMatch(
  speakerJs,
  /\balert\s*\(/,
  "alert() must be eliminated from speaker.js",
);
assert.match(
  speakerJs,
  /isContextSecure\s*\(\)|isSecureContext/,
  "speaker.js must check for secure context",
);
assert.match(
  speakerJs,
  /NotAllowedError/,
  "speaker.js must explicitly handle NotAllowedError",
);
assert.match(
  speakerJs,
  /NotFoundError/,
  "speaker.js must explicitly handle NotFoundError",
);
assert.match(
  speakerJs,
  /NotReadableError/,
  "speaker.js must explicitly handle NotReadableError",
);
assert.match(
  speakerJs,
  /AbortError/,
  "speaker.js must explicitly handle AbortError",
);
assert.match(
  speakerJs,
  /SecurityError/,
  "speaker.js must explicitly handle SecurityError",
);
assert.match(
  speakerJs,
  /audioContext\.state\s*===\s*['"]suspended['"][\s\S]*await audioContext\.resume\(\)/,
  "speaker.js must resume suspended AudioContext within the user gesture flow",
);
assert.match(
  speakerJs,
  /addEventListener\s*\(\s*['"]pagehide['"]/,
  "speaker.js must register pagehide listener for resource cleanup",
);
assert.match(
  speakerJs,
  /addEventListener\s*\(\s*['"]visibilitychange['"]/,
  "speaker.js must register visibilitychange listener for resource cleanup",
);
assert.match(
  speakerJs,
  /window\.__guideCastSpeakerDiagnostics\s*=/,
  "speaker.js must export __guideCastSpeakerDiagnostics for deterministic testing",
);
assert.match(
  speakerJs,
  /fetch\s*\(\s*['"]\/api\/mic\/session['"]/,
  "speaker.js must obtain the microphone access mode from the HTTPS session endpoint",
);
assert.match(
  speakerJs,
  /fetch\s*\(\s*['"]\/api\/mic\/join['"]/,
  "speaker.js must authenticate open/PIN microphone sessions before opening WebSocket",
);
assert.doesNotMatch(
  speakerJs,
  /getLegacyToken|speakerToken=|[?&]token=/,
  "speaker.js must not read or create microphone credentials in page/WebSocket URLs",
);

// Verify speaker.html does not misrepresent HTTP as encrypted/secure
assert.doesNotMatch(
  speakerHtml,
  /안전하게 송출됩니다/,
  "speaker.html must not mislead users that HTTP is an encrypted secure connection",
);
assert.match(
  speakerHtml,
  /id=["']noticeCard["']/,
  "speaker.html must contain noticeCard for actionable guidance",
);
assert.match(
  speakerHtml,
  /id=["']noticeRetryBtn["']/,
  "speaker.html must contain noticeRetryBtn for retrying",
);
assert.match(
  speakerHtml,
  /id=["']micPinCard["']/,
  "speaker.html must contain the optional instructor PIN form",
);
assert.match(
  speakerHtml,
  /<strong>\/mic<\/strong>/,
  "speaker.html must describe /mic as the single user-facing route",
);
assert.doesNotMatch(
  speakerHtml,
  /speakerToken|[?&]token=|__GUIDECAST_SPEAKER_TOKEN__/,
  "speaker.html must never embed a microphone bearer token",
);
assert.match(
  speakerHtml,
  /이 송출기 설치가 만든 사설 로컬 CA이며 공식·공개 신뢰 인증서가 아닙니다/,
  "speaker.html must disclose that the installation-local CA is not publicly trusted",
);
assert.match(
  speakerHtml,
  /방송 폰 앱 화면의 SHA-256 지문과 위 다운로드 인증서 지문을 한 글자씩 비교/,
  "speaker.html must direct the instructor to the out-of-band transmitter fingerprint",
);
assert.match(
  speakerHtml,
  /다르면 변조되었거나 다른 송출기용일 수 있으므로 설치하지 마세요/,
  "speaker.html must provide an explicit mismatch warning before certificate installation",
);

// Verify speaker.css contains notice-card styling
assert.match(
  speakerCss,
  /\.notice-card/,
  "speaker.css must contain .notice-card rules",
);
assert.match(
  speakerCss,
  /\.notice-retry-btn/,
  "speaker.css must contain .notice-retry-btn rules",
);

// 2. Deterministic mock DOM and Web Audio harness
class MockClassList {
  constructor(element) {
    this.element = element;
    this.classes = new Set();
  }
  add(...names) {
    for (const name of names) this.classes.add(name);
    this.element.className = Array.from(this.classes).join(" ");
  }
  remove(...names) {
    for (const name of names) this.classes.delete(name);
    this.element.className = Array.from(this.classes).join(" ");
  }
  contains(name) {
    return this.classes.has(name);
  }
}

class MockElement {
  constructor(id = "") {
    this.id = id;
    this.listeners = new Map();
    this.children = [];
    this._className = "";
    this.disabled = false;
    this.hidden = false;
    this.textContent = "";
    this.value = "";
    this.href = "";
    this.dataset = {};
    this.attributes = new Map();
    this.style = {};
    this.classList = new MockClassList(this);
  }

  get className() {
    return this._className;
  }
  set className(val) {
    this._className = val;
    this.classList.classes = new Set(val ? val.split(/\s+/).filter(Boolean) : []);
  }

  addEventListener(type, listener) {
    const existing = this.listeners.get(type) || [];
    existing.push(listener);
    this.listeners.set(type, existing);
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
    if (name === "href") this.href = String(value);
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  removeAttribute(name) {
    this.attributes.delete(name);
    if (name === "href") this.href = "";
  }

  scrollIntoView() {}

  dispatch(type, event = {}) {
    return (this.listeners.get(type) || []).map((listener) => listener({
      preventDefault: () => {},
      ...event,
    }));
  }
}

class MockAudioTrack {
  constructor() {
    this.readyState = "live";
    this.enabled = true;
    this.stopped = false;
    this.onended = null;
  }
  stop() {
    this.readyState = "ended";
    this.stopped = true;
    if (this.onended) this.onended();
  }
}

class MockMediaStream {
  constructor() {
    this.tracks = [new MockAudioTrack()];
  }
  getTracks() {
    return this.tracks;
  }
  getAudioTracks() {
    return this.tracks;
  }
}

class MockAudioContext {
  constructor({ sampleRate = 16000 } = {}) {
    this.sampleRate = sampleRate;
    this.state = "suspended";
    this.destination = {};
    this.closeCount = 0;
    this.resumeCount = 0;
  }
  resume() {
    this.resumeCount += 1;
    this.state = "running";
    return Promise.resolve();
  }
  close() {
    this.closeCount += 1;
    this.state = "closed";
    return Promise.resolve();
  }
  createMediaStreamSource() {
    return {
      connect: () => {},
      disconnect: () => {},
    };
  }
  createAnalyser() {
    return {
      fftSize: 256,
      frequencyBinCount: 128,
      connect: () => {},
      disconnect: () => {},
      getByteFrequencyData: (arr) => {
        arr.fill(64);
      },
    };
  }
  createScriptProcessor(_bufSize, _inChan, _outChan) {
    const node = {
      onaudioprocess: null,
      connect: () => {},
      disconnect: () => {},
    };
    MockAudioContext.lastProcessorNode = node;
    return node;
  }
}

class MockWebSocket {
  static instances = [];
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  constructor(url, protocols = undefined) {
    this.url = url;
    this.protocols = protocols;
    this.binaryType = "";
    this.readyState = 0; // CONNECTING
    this.onopen = null;
    this.onclose = null;
    this.onerror = null;
    this.onmessage = null;
    this.sentMessages = [];
    MockWebSocket.instances.push(this);
  }
  send(data) {
    this.sentMessages.push(data);
  }
  close(code = 1000, reason = "") {
    this.readyState = 3; // CLOSED
    if (this.onclose) this.onclose({ code, reason });
  }
}

function mockResponse({ status = 200, json = null, text = "" } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => json,
    text: async () => text,
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createSpeakerHarness({
  isSecureContext = true,
  protocol = "https:",
  hostname = "192.168.1.50",
  host = "192.168.1.50:8443",
  pathname = "/mic",
  hash = "",
  search = "",
  httpsPort = "8443",
  caFingerprint = "AA:BB:CC:DD",
  sessionRequiresPin = false,
  fetchImpl = null,
  getUserMediaImpl = null,
  permissionsState = "prompt",
} = {}) {
  const elements = new Map();
  const initiallyHiddenIds = new Set([
    "securitySetupCard",
    "micPinCard",
    "noticeCard",
    "noticeRetryBtn",
  ]);
  const getOrCreate = (id) => {
    if (!elements.has(id)) {
      const element = new MockElement(id);
      element.hidden = initiallyHiddenIds.has(id);
      elements.set(id, element);
    }
    return elements.get(id);
  };

  const documentListeners = new Map();
  const windowListeners = new Map();
  const body = new MockElement("body");
  body.dataset.httpsPort = httpsPort;
  body.dataset.caFingerprint = caFingerprint;

  let nextTimerId = 1;
  const timers = [];

  const mockStream = new MockMediaStream();
  const defaultGetUserMedia = () => Promise.resolve(mockStream);
  const getUserMedia = getUserMediaImpl || defaultGetUserMedia;
  const fetchCalls = [];
  const defaultFetch = async (url, options = {}) => {
    if (url === "/api/mic/session") {
      return mockResponse({ json: { requiresPin: sessionRequiresPin, authenticated: false } });
    }
    if (url === "/api/mic/join") {
      return mockResponse({ json: { authenticated: true } });
    }
    throw new Error(`Unexpected fetch URL: ${url}`);
  };
  const fetchMock = async (url, options = {}) => {
    fetchCalls.push({ url, options: { ...options } });
    return (fetchImpl || defaultFetch)(url, options);
  };

  const sandbox = {
    ArrayBuffer,
    DataView,
    Float32Array,
    Int16Array,
    Uint8Array,
    Math,
    WebSocket: MockWebSocket,
    fetch: fetchMock,
    AudioContext: MockAudioContext,
    webkitAudioContext: MockAudioContext,
    setTimeout: (fn, ms) => {
      const id = nextTimerId++;
      timers.push({ id, fn, ms, active: true });
      return id;
    },
    clearTimeout: (id) => {
      const t = timers.find((item) => item.id === id);
      if (t) t.active = false;
    },
    requestAnimationFrame: (fn) => {
      const id = nextTimerId++;
      timers.push({ id, fn, ms: 16, active: true });
      return id;
    },
    cancelAnimationFrame: (id) => {
      const t = timers.find((item) => item.id === id);
      if (t) t.active = false;
    },
    document: {
      getElementById: (id) => getOrCreate(id),
      addEventListener: (type, listener) => {
        const list = documentListeners.get(type) || [];
        list.push(listener);
        documentListeners.set(type, list);
      },
      body,
      visibilityState: "visible",
    },
    window: {
      isSecureContext,
      AudioContext: MockAudioContext,
      webkitAudioContext: MockAudioContext,
      location: {
        protocol,
        hostname,
        host,
        pathname,
        hash,
        search,
        href: `${protocol}//${host}${pathname}${search}${hash}`,
      },
      addEventListener: (type, listener) => {
        const list = windowListeners.get(type) || [];
        list.push(listener);
        windowListeners.set(type, list);
      },
    },
    navigator: {
      mediaDevices: {
        getUserMedia,
      },
      permissions: {
        query: ({ name }) => {
          if (name === "microphone") {
            return Promise.resolve({
              state: permissionsState,
              onchange: null,
            });
          }
          return Promise.reject(new Error("Unsupported permission"));
        },
      },
    },
  };

  sandbox.window.window = sandbox.window;
  const context = vm.createContext(sandbox);
  vm.runInContext(speakerJs, context, { filename: speakerJsUrl.pathname });

  return {
    context,
    element: (id) => getOrCreate(id),
    diagnostics: () => context.window.__guideCastSpeakerDiagnostics(),
    dispatchDocument: (type, ev = {}) => {
      (documentListeners.get(type) || []).forEach((cb) => cb(ev));
    },
    dispatchWindow: (type, ev = {}) => {
      (windowListeners.get(type) || []).forEach((cb) => cb(ev));
    },
    lastWebSocket: () => MockWebSocket.instances[MockWebSocket.instances.length - 1],
    webSocketCount: () => MockWebSocket.instances.length,
    fetchCalls,
    serializedDom: () => Array.from(elements.values()).map((element) => [
      element.textContent,
      element.value,
      element.href,
      ...Array.from(element.attributes.values()),
    ].join(" ")).join(" "),
    flush: async () => {
      for (let i = 0; i < 30; i++) await Promise.resolve();
    },
  };
}

function markTransmitterInputReady(ws) {
  ws.onmessage({ data: '{"type":"input-state","ready":true}' });
}

// TEST 1: HTTP /mic is onboarding-only. It must neither authenticate nor open a WS.
async function testInsecureContextDisablesMic() {
  MockWebSocket.instances = [];
  const harness = createSpeakerHarness({
    isSecureContext: false,
    protocol: "http:",
    hostname: "192.168.1.100",
    host: "192.168.1.100:8787",
  });
  await harness.flush();

  assert.equal(
    harness.fetchCalls.length,
    0,
    "HTTP onboarding must not call HTTPS-only session/join endpoints",
  );
  assert.equal(
    harness.webSocketCount(),
    0,
    "HTTP /mic must never open an insecure ws:// microphone connection",
  );

  const micBtn = harness.element("micButton");
  assert.equal(micBtn.disabled, true, "Mic button must be disabled on insecure remote origin");

  const notice = harness.element("noticeCard");
  assert.equal(notice.hidden, false, "Notice card must be shown on insecure remote origin");
  assert.match(
    harness.element("noticeTitle").textContent,
    /HTTPS|보안/,
    "Notice title must mention HTTPS or security requirement",
  );
  assert.match(
    harness.element("noticeMessage").textContent,
    /클라이언트 앱|HTTPS|보안/,
    "Notice message must explain HTTPS or dedicated client app requirement",
  );

  // Must NOT claim permission denied
  assert.doesNotMatch(
    harness.element("statusText").textContent,
    /권한이 거부/,
    "Insecure context must not falsely diagnose as permission denied",
  );

  // Attempting to click must NOT start streaming
  const [clickPromise] = micBtn.dispatch("click");
  if (clickPromise) await clickPromise;
  await harness.flush();

  assert.equal(harness.diagnostics().isStreaming, false, "Streaming must not start on insecure origin");
  assert.equal(
    harness.element("securitySetupCard").hidden,
    false,
    "HTTP /mic must expose certificate installation guidance",
  );
  assert.equal(
    harness.element("launchSecureMicBtn").href,
    "https://192.168.1.100:8443/mic",
    "Onboarding must return to the same single /mic route over HTTPS",
  );
  harness.dispatchWindow("pagehide");
  await harness.flush();
  console.log("  [PASS] HTTP /mic remains onboarding-only and never opens an insecure microphone WebSocket");
}

// TEST 2: HTTPS open mode still authenticates through join before opening a token-free WSS URL.
async function testHttpsOpenModeAuthenticatesBeforeWebSocket() {
  MockWebSocket.instances = [];
  const join = deferred();
  const harness = createSpeakerHarness({
    isSecureContext: true,
    protocol: "https:",
    hostname: "guidecast.local",
    host: "guidecast.local:8443",
    sessionRequiresPin: false,
    fetchImpl: async (url) => {
      if (url === "/api/mic/session") {
        return mockResponse({ json: { requiresPin: false, authenticated: false } });
      }
      if (url === "/api/mic/join") return join.promise;
      throw new Error(`Unexpected fetch URL: ${url}`);
    },
  });
  await harness.flush();

  assert.deepEqual(
    harness.fetchCalls.map((call) => call.url),
    ["/api/mic/session", "/api/mic/join"],
    "Open mode must read session policy and perform the cookie-issuing join",
  );
  assert.equal(
    harness.fetchCalls[1].options.body,
    "",
    "Open-mode join must not invent or send a PIN",
  );
  assert.equal(
    harness.webSocketCount(),
    0,
    "WSS must stay closed until join succeeds",
  );

  join.resolve(mockResponse({ json: { authenticated: true } }));
  await harness.flush();

  assert.equal(harness.webSocketCount(), 1, "Successful open-mode join must open one WSS");
  const ws = harness.lastWebSocket();
  assert.equal(
    ws.url,
    "wss://guidecast.local:8443/ws/speaker-input",
    "WSS URL must contain no bearer credential or query string",
  );
  assert.equal(new URL(ws.url).search, "", "WSS URL must remain query-token free");
  assert.equal(harness.diagnostics().isAuthenticated, true);
  assert.doesNotMatch(
    harness.serializedDom(),
    /speakerToken|bearer|mic-token/i,
    "Authentication state must not render a credential into HTML/DOM",
  );

  harness.dispatchWindow("pagehide");
  await harness.flush();
  console.log("  [PASS] HTTPS open mode joins first and opens one credential-free WSS URL");
}

async function testHttpsExistingCookieSessionSkipsJoin() {
  MockWebSocket.instances = [];
  const harness = createSpeakerHarness({
    isSecureContext: true,
    protocol: "https:",
    hostname: "guidecast.local",
    host: "guidecast.local:8443",
    fetchImpl: async (url) => {
      if (url === "/api/mic/session") {
        return mockResponse({
          json: { requiresPin: true, authenticated: true },
        });
      }
      throw new Error(`Authenticated session must not call ${url}`);
    },
  });
  await harness.flush();

  assert.deepEqual(
    harness.fetchCalls.map((call) => call.url),
    ["/api/mic/session"],
    "An existing HttpOnly cookie session must be learned only through the session endpoint",
  );
  assert.equal(harness.webSocketCount(), 1, "Authenticated session must open one WSS");
  assert.equal(
    harness.lastWebSocket().url,
    "wss://guidecast.local:8443/ws/speaker-input",
  );
  assert.equal(harness.element("micPinCard").hidden, true);
  assert.equal(harness.diagnostics().isAuthenticated, true);

  harness.dispatchWindow("pagehide");
  await harness.flush();
  console.log("  [PASS] Existing HTTPS cookie session skips PIN join and opens token-free WSS");
}

// TEST 3: PIN mode must ignore legacy URL tokens and open WSS only after a valid join.
async function testHttpsPinModeAuthenticatesBeforeWebSocket() {
  MockWebSocket.instances = [];
  const harness = createSpeakerHarness({
    isSecureContext: true,
    protocol: "https:",
    hostname: "guidecast.local",
    host: "guidecast.local:8443",
    hash: "#speakerToken=legacy-url-secret",
    sessionRequiresPin: true,
  });
  await harness.flush();

  assert.deepEqual(
    harness.fetchCalls.map((call) => call.url),
    ["/api/mic/session"],
    "PIN mode must wait for form submission after the session lookup",
  );
  assert.equal(
    harness.webSocketCount(),
    0,
    "A legacy URL token must not bypass PIN authentication",
  );
  assert.equal(harness.element("micPinCard").hidden, false, "PIN form must be visible");

  harness.element("micPin").value = "12x";
  harness.element("micPinCard").dispatch("submit");
  await harness.flush();
  assert.equal(harness.fetchCalls.length, 1, "Malformed PIN must stay client-side");
  assert.match(harness.element("micPinMessage").textContent, /4~8자리/);

  harness.element("micPin").value = "123456";
  harness.element("micPinCard").dispatch("submit");
  await harness.flush();

  assert.equal(harness.fetchCalls.length, 2, "Valid PIN must issue one join request");
  const joinCall = harness.fetchCalls[1];
  assert.equal(joinCall.url, "/api/mic/join");
  assert.equal(joinCall.options.method, "POST");
  assert.equal(joinCall.options.credentials, "same-origin");
  assert.equal(joinCall.options.body, "123456");
  assert.equal(harness.element("micPin").value, "", "PIN input must be cleared after join");
  assert.equal(harness.webSocketCount(), 1, "PIN join success must open exactly one WSS");

  const ws = harness.lastWebSocket();
  assert.equal(ws.url, "wss://guidecast.local:8443/ws/speaker-input");
  assert.equal(new URL(ws.url).search, "", "PIN/session credentials must not enter WSS URL");
  assert.doesNotMatch(
    harness.serializedDom(),
    /legacy-url-secret|123456/,
    "Legacy token and submitted PIN must not remain in rendered HTML/DOM",
  );

  harness.dispatchWindow("pagehide");
  await harness.flush();
  console.log("  [PASS] HTTPS PIN mode ignores legacy URL tokens and joins before WSS");
}

async function testTransmitterInputReadinessGate() {
  MockWebSocket.instances = [];
  const harness = createSpeakerHarness({
    isSecureContext: true,
    protocol: "https:",
    hostname: "guidecast.local",
  });
  await harness.flush();

  const ws = harness.lastWebSocket();
  ws.readyState = 1;
  ws.onopen();
  ws.onmessage({ data: '{"type":"input-state","ready":false}' });
  await harness.flush();

  assert.equal(harness.element("micButton").disabled, true);
  assert.equal(harness.diagnostics().appInputReady, false);
  assert.match(harness.element("noticeMessage").textContent, /강사 웹 마이크.*입력 시작/);

  markTransmitterInputReady(ws);
  await harness.flush();
  assert.equal(harness.element("micButton").disabled, false);
  assert.equal(harness.diagnostics().appInputReady, true);
  harness.dispatchWindow("pagehide");
  await harness.flush();
  console.log("  [PASS] Browser mic stays gated until the transmitter input subscriber is ready");
}

// TEST 2: Denial errors mapped to actionable Korean messages without alert
async function testDenialErrorMessages() {
  const errorCases = [
    { name: "NotAllowedError", expectedNotice: /마이크 사용을 허용|마이크 접근 권한/ },
    { name: "NotFoundError", expectedNotice: /마이크.*연결 상태|마이크 하드웨어/ },
    { name: "NotReadableError", expectedNotice: /다른 앱|음성 통화/ },
    { name: "AbortError", expectedNotice: /중단되었습니다/ },
    { name: "SecurityError", expectedNotice: /보안 제약|HTTPS/ },
  ];

  for (const tc of errorCases) {
    MockWebSocket.instances = [];
    const err = new Error("Mock error: " + tc.name);
    err.name = tc.name;

    const harness = createSpeakerHarness({
      isSecureContext: true,
      protocol: "https:",
      hostname: "guidecast.local",
      getUserMediaImpl: () => Promise.reject(err),
    });
    await harness.flush();

    const ws = harness.lastWebSocket();
    ws.readyState = 1;
    ws.onopen();
    markTransmitterInputReady(ws);
    await harness.flush();

    const micBtn = harness.element("micButton");
    assert.equal(micBtn.disabled, false, "Mic button should be enabled in secure context when connected");

    const [clickPromise] = micBtn.dispatch("click");
    if (clickPromise) await clickPromise;
    await harness.flush();

    assert.equal(harness.diagnostics().isStreaming, false);
    const notice = harness.element("noticeCard");
    assert.equal(notice.hidden, false, `Notice should be visible for ${tc.name}`);
    assert.match(
      harness.element("noticeMessage").textContent,
      tc.expectedNotice,
      `Message for ${tc.name} must provide actionable instruction`,
    );
    harness.dispatchWindow("pagehide");
    await harness.flush();
  }
  console.log("  [PASS] All getUserMedia denial errors correctly mapped to actionable Korean guidance");
}

// TEST 3: Successful stream acquisition, AudioContext resume on user gesture, PCM frame transmission
async function testSuccessfulAudioTransmission() {
  MockWebSocket.instances = [];
  let getUserMediaCalled = false;

  const mockStream = new MockMediaStream();
  const harness = createSpeakerHarness({
    isSecureContext: true,
    protocol: "https:",
    hostname: "guidecast.local",
    getUserMediaImpl: () => {
      getUserMediaCalled = true;
      return Promise.resolve(mockStream);
    },
  });
  await harness.flush();

  const ws = harness.lastWebSocket();
  ws.readyState = 1;
  ws.onopen();
  markTransmitterInputReady(ws);
  await harness.flush();

  const micBtn = harness.element("micButton");
  assert.equal(micBtn.disabled, false);

  const [clickPromise] = micBtn.dispatch("click");
  if (clickPromise) await clickPromise;
  await harness.flush();

  assert.equal(getUserMediaCalled, true, "getUserMedia must be invoked");
  const diag = harness.diagnostics();
  assert.equal(diag.isStreaming, true, "isStreaming must be true after startStreaming");
  assert.equal(diag.audioContextState, "running", "AudioContext must be resumed to 'running'");
  assert.equal(micBtn.classList.contains("live"), true, "mic button must have .live class");

  // Simulate audio process event with Float32 audio samples
  const processor = MockAudioContext.lastProcessorNode;
  assert.ok(processor && typeof processor.onaudioprocess === "function", "Processor node must be created");

  const fakeFloats = new Float32Array(2048);
  fakeFloats[0] = 0.5;
  fakeFloats[1] = -0.5;
  processor.onaudioprocess({
    inputBuffer: {
      getChannelData: () => fakeFloats,
    },
  });

  const sent = ws.sentMessages.find((message) => message instanceof ArrayBuffer);
  assert.ok(sent, "Audio frames must be transmitted over WebSocket");
  assert.ok(sent instanceof ArrayBuffer, "Transmitted audio frame must be an ArrayBuffer");
  assert.ok(sent.byteLength >= 6, "Frame must contain 4-byte sequence header + PCM audio");
  assert.equal((sent.byteLength - 4) % 2, 0, "S16LE PCM payload must have even byte length");
  const seq = new DataView(sent).getInt32(0, false);
  assert.equal(seq, 0, "First transmitted frame must have sequence number 0");

  // Teardown streaming and connection
  const [stopPromise] = micBtn.dispatch("click");
  if (stopPromise) await stopPromise;
  await harness.flush();
  harness.dispatchWindow("pagehide");
  await harness.flush();
  assert.equal(harness.diagnostics().isStreaming, false, "Streaming must be stopped after teardown");

  console.log("  [PASS] Audio stream successfully acquired, AudioContext resumed, PCM transmitted over WebSocket");
}

// TEST 4: Cleanup on pagehide, visibilitychange, and WS close
async function testLifecycleCleanup() {
  MockWebSocket.instances = [];
  const mockStream = new MockMediaStream();
  const harness = createSpeakerHarness({
    isSecureContext: true,
    protocol: "https:",
    hostname: "guidecast.local",
    getUserMediaImpl: () => Promise.resolve(mockStream),
  });
  await harness.flush();

  const ws = harness.lastWebSocket();
  ws.readyState = 1;
  ws.onopen();
  markTransmitterInputReady(ws);
  await harness.flush();

  // Start streaming
  const micBtn = harness.element("micButton");
  const [clickPromise] = micBtn.dispatch("click");
  if (clickPromise) await clickPromise;
  await harness.flush();
  assert.equal(harness.diagnostics().isStreaming, true);

  // Test visibilitychange: hidden
  harness.context.document.visibilityState = "hidden";
  harness.dispatchDocument("visibilitychange");
  await harness.flush();

  assert.equal(harness.diagnostics().isStreaming, false, "visibilitychange to hidden must stop streaming");
  assert.equal(mockStream.getTracks()[0].stopped, true, "Media tracks must be stopped");

  // Restart streaming
  harness.context.document.visibilityState = "visible";
  const mockStream2 = new MockMediaStream();
  harness.context.navigator.mediaDevices.getUserMedia = () => Promise.resolve(mockStream2);
  const [clickPromise2] = micBtn.dispatch("click");
  if (clickPromise2) await clickPromise2;
  await harness.flush();
  assert.equal(harness.diagnostics().isStreaming, true);

  // Test pagehide
  harness.dispatchWindow("pagehide");
  await harness.flush();

  assert.equal(harness.diagnostics().isStreaming, false, "pagehide must stop streaming");
  assert.equal(mockStream2.getTracks()[0].stopped, true, "Media tracks must be stopped on pagehide");
  assert.equal(ws.readyState, 3, "WebSocket must be closed on pagehide");

  console.log("  [PASS] Lifecycle events (visibilitychange, pagehide) cleanly stop audio tracks and release resources");
}

// TEST 5: Policy rejection halts reconnect loop and guides the user
async function testPolicyViolationHaltsReconnect() {
  MockWebSocket.instances = [];
  const harness = createSpeakerHarness({
    isSecureContext: true,
    protocol: "https:",
    hostname: "guidecast.local",
  });
  await harness.flush();

  const ws = harness.lastWebSocket();
  ws.readyState = 1;
  ws.onopen();
  markTransmitterInputReady(ws);
  await harness.flush();

  const initialInstanceCount = MockWebSocket.instances.length;
  ws.readyState = 3;
  ws.onclose({ code: 1008, reason: "Speaker session active" });
  await harness.flush();

  const notice = harness.element("noticeCard");
  assert.equal(notice.hidden, false, "Notice card must be shown on policy violation");
  assert.ok(
    harness.element("noticeMessage").textContent.includes("다른 강사"),
    "Busy lease notice must guide the user that another speaker is broadcasting"
  );

  // Verify no new reconnect timer or websocket was created
  assert.equal(
    MockWebSocket.instances.length,
    initialInstanceCount,
    "Policy violation must NOT schedule any reconnection attempt"
  );

  console.log("  [PASS] Policy violation (1008 VIOLATED_POLICY) halts reconnect and displays guidance");
}

console.log("Running GuideCast speaker mic regression checks...");
console.log("  [DISCLAIMER] Headless Node.js mock tests verify client script state machines and IPC framing only;");
console.log("  [DISCLAIMER] They do NOT prove physical Galaxy S23, Samsung One UI, or remote mobile browser acoustic qualification.");
await testInsecureContextDisablesMic();
await testHttpsOpenModeAuthenticatesBeforeWebSocket();
await testHttpsExistingCookieSessionSkipsJoin();
await testHttpsPinModeAuthenticatesBeforeWebSocket();
await testTransmitterInputReadinessGate();
await testDenialErrorMessages();
await testSuccessfulAudioTransmission();
await testLifecycleCleanup();
await testPolicyViolationHaltsReconnect();
console.log("All GuideCast speaker mic regression checks passed successfully!");
process.exit(0);
