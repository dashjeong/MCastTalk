import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const base = new URL("../core/server/src/main/assets/listener/", import.meta.url);
const player = await readFile(new URL("player.js", base), "utf8");
const html = await readFile(new URL("index.html", base), "utf8");
const css = await readFile(new URL("player.css", base), "utf8");
const start = player.indexOf("function listenerHudCaption(");
const end = player.indexOf('developerInformationToggle.addEventListener("change"', start);
assert(start >= 0 && end > start);
const controller = player.slice(start, end);
assert.match(html, /id="listener-hud"[\s\S]*role="dialog"[\s\S]*aria-modal="true"/);
assert.match(html, /id="hud-open"[\s\S]*aria-controls="listener-hud"/);
assert.match(css, /\.hud-toolbar button, \.hud-toolbar select \{ min-height: 48px/);
assert.match(css, /orientation: landscape/);
assert.match(css, /safe-area-inset/);
assert.doesNotMatch(controller, /\.innerHTML\s*=|window\.close\s*\(|window\.open\s*\(/);
assert.match(player, /function showPinDialog\([^)]*\) \{\s*listenerHud\?\.close\(\);/);

function fixture({ fullscreen = "unsupported", historyDenied = false, transcriptVisible = false } = {}) {
  const elements = new Map(), documentListeners = new Map(), windowListeners = new Map();
  const count = { startPolling: 0, stopPolling: 0, fullscreenRequests: 0, fullscreenExits: 0 };
  let document;
  class Element {
    constructor(id) {
      this.id = id; this.children = []; this.listeners = new Map(); this.attributes = new Map();
      this.disabled = false; this.hidden = false; this.inert = false; this.value = "";
      this.textContent = ""; this.style = { overflow: "" }; this.isConnected = true;
      const classes = new Set();
      this.classList = { toggle: (name, enabled) => enabled ? classes.add(name) : classes.delete(name),
        contains: name => classes.has(name) };
    }
    get options() { return this.children; }
    append(...nodes) {
      for (const node of nodes) {
        if (node.parentElement) node.parentElement.children = node.parentElement.children.filter(child => child !== node);
        node.parentElement = this; this.children.push(node);
      }
    }
    replaceChildren(...nodes) { this.children.forEach(node => { node.parentElement = null; }); this.children = []; this.append(...nodes); }
    addEventListener(type, listener) { const list = this.listeners.get(type) || []; list.push(listener); this.listeners.set(type, list); }
    dispatchEvent(event) { for (const listener of this.listeners.get(event.type) || []) listener(event); }
    dispatch(type, extra = {}) {
      const event = { type, prevented: false, preventDefault() { this.prevented = true; }, ...extra };
      this.dispatchEvent(event); return event;
    }
    setAttribute(key, value) { this.attributes.set(key, String(value)); }
    getAttribute(key) { return this.attributes.get(key) ?? null; }
    removeAttribute(key) { this.attributes.delete(key); }
    contains(node) { return node === this || this.children.some(child => child.contains(node)); }
    focus() { document.activeElement = this; for (const listener of documentListeners.get("focusin") || []) listener({ target: this }); }
  }
  const element = id => { if (!elements.has(id)) elements.set(id, new Element(id)); return elements.get(id); };
  document = { body: new Element("body"), fullscreenElement: null,
    querySelector: element, createElement: tag => new Element(tag),
    addEventListener(type, listener) { const list = documentListeners.get(type) || []; list.push(listener); documentListeners.set(type, list); },
    dispatch(type, event = {}) { for (const listener of documentListeners.get(type) || []) listener(event); },
    exitFullscreen() { count.fullscreenExits++; this.fullscreenElement = null; return Promise.resolve(); } };
  const view = { scrollX: 7, scrollY: 90,
    addEventListener(type, listener) { const list = windowListeners.get(type) || []; list.push(listener); windowListeners.set(type, list); },
    dispatch(type, event) { for (const listener of windowListeners.get(type) || []) listener(event); },
    scrollTo(x, y) { this.scrollX = x; this.scrollY = y; } };
  const history = { entries: [{ original: true }], position: 0,
    get state() { return this.entries[this.position]; },
    pushState(state) { if (historyDenied) throw new Error("Synthetic unavailable history"); this.entries.splice(this.position + 1); this.entries.push(state); this.position++; },
    back() { if (this.position > 0) { this.position--; view.dispatch("popstate", { state: this.state }); } },
    forward() { if (this.position + 1 < this.entries.length) { this.position++; view.dispatch("popstate", { state: this.state }); } } };
  const main = element("main"), hud = element("#listener-hud"), opener = element("#hud-open");
  document.body.append(main, hud); main.append(opener);
  hud.hidden = true;
  const controls = ["#hud-close", "#hud-language", "#hud-order", "#hud-captions", "#hud-status", "#hud-fullscreen-note"].map(element);
  hud.append(...controls);
  element("#hud-captions").append(element("#hud-source-pane"), element("#hud-translation-pane"));
  element("#hud-source-pane").append(element("#hud-source"));
  element("#hud-translation-pane").append(element("#hud-translation-label"), element("#hud-translation"));
  element("#hud-order").value = "source-first";
  element("#listening-mode").value = "live"; element("#transcript-scope").value = "live";
  const channel = element("#channel");
  channel.append(...["source", "en", "ja"].map(id => { const option = new Element("option"); option.value = id; option.textContent = id; return option; }));
  channel.value = "en";
  const selections = [];
  channel.addEventListener("change", () => selections.push(channel.value));
  element("#panel-transcript").hidden = !transcriptVisible;
  element("#status").textContent = "Synthetic connected state";
  document.body.style.overflow = "scroll"; opener.focus();
  let finishFullscreen = null;
  if (fullscreen !== "unsupported") hud.requestFullscreen = () => {
    count.fullscreenRequests++;
    if (fullscreen === "denied") return Promise.reject(new Error("Synthetic rejected fullscreen"));
    if (fullscreen === "delayed") return new Promise(resolve => { finishFullscreen = () => { document.fullscreenElement = hud; resolve(); }; });
    document.fullscreenElement = hud; return Promise.resolve();
  };
  const context = vm.createContext({ document, window: view, history,
    location: { href: "https://listener.invalid/en" }, channelSelect: channel,
    statusLabel: element("#status"), panelTranscript: element("#panel-transcript"),
    uiText: text => text, channelLabel: id => id, Event: class { constructor(type) { this.type = type; } },
    startTranscriptPolling: () => count.startPolling++, stopTranscriptPolling: () => count.stopPolling++ });
  vm.runInContext(controller, context);
  return { element, main, hud, opener, document, view, history, count, channel, selections,
    caption: context.listenerHudCaption, api: context.createListenerHud(), finishFullscreen: () => finishFullscreen() };
}

const rows = [{ sourceText: "Synthetic source A", translations: { en: "Synthetic EN" }, liveSegmentLanguage: "en" },
  { sourceText: "Synthetic source B", translations: { ja: "Synthetic JA" }, liveSegmentLanguage: "ja" }];
{
  const f = fixture();
  assert.equal(f.caption(rows, "en").source, "Synthetic source A", "do not pair English with another lane's source");
  assert.equal(f.caption(rows, "en").translation, "Synthetic EN");
  assert.equal(f.caption(rows, "source").source, "Synthetic source B");
  assert.equal(f.caption([], "en").translation, "");
  assert.equal(f.caption([...rows, { sourceText: "Synthetic pending", translations: {}, liveSegmentLanguage: "en" }], "en").translation, "",
    "an untranslated new turn must not keep an older translation");
  f.api.setTranscript(rows); f.api.open({ fullscreen: false });
  assert.equal(f.hud.hidden, false); assert.equal(f.main.inert, true);
  assert.equal(f.main.getAttribute("aria-hidden"), "true"); assert.equal(f.document.body.style.overflow, "hidden");
  assert.equal(f.document.activeElement, f.element("#hud-close")); assert.equal(f.count.startPolling, 1);
  f.element("#hud-language").value = "ja"; f.element("#hud-language").dispatch("change");
  assert.equal(f.channel.value, "ja"); assert.deepEqual(f.selections, ["ja"]);
  assert.equal(f.element("#hud-translation").textContent, "Synthetic JA");
  f.element("#hud-order").value = "translation-first"; f.element("#hud-order").dispatch("change");
  assert.equal(f.element("#hud-captions").children[0], f.element("#hud-translation-pane"));
  f.element("#hud-order").focus();
  assert.equal(f.hud.dispatch("keydown", { key: "Tab" }).prevented, true);
  assert.equal(f.document.activeElement, f.element("#hud-close"));
  f.hud.dispatch("keydown", { key: "Tab", shiftKey: true });
  assert.equal(f.document.activeElement, f.element("#hud-order"));
  f.opener.focus(); assert.equal(f.document.activeElement, f.element("#hud-close"), "focus must remain inside HUD");
  f.hud.dispatch("keydown", { key: "Escape" });
  assert.equal(f.api.isOpen(), false); assert.equal(f.main.inert, false);
  assert.equal(f.main.getAttribute("aria-hidden"), null); assert.equal(f.document.body.style.overflow, "scroll");
  assert.equal(f.document.activeElement, f.opener); assert.equal(f.view.scrollY, 90); assert.equal(f.count.stopPolling, 1);
  assert.equal(f.history.position, 0);
}
{
  const f = fixture(); f.api.open({ fullscreen: false }); f.history.back();
  assert.equal(f.api.isOpen(), false); f.history.forward(); assert.equal(f.api.isOpen(), true);
  assert.equal(f.history.entries.length, 2, "forward must not push an extra HUD history entry"); f.api.close();
  for (let index = 0; index < 50; index++) { f.api.open({ fullscreen: false }); f.api.close(); }
  assert.equal(f.history.entries.length, 2); assert.equal(f.history.position, 0);
  assert.equal(f.count.startPolling, f.count.stopPolling);
}
{
  const f = fixture({ historyDenied: true }); f.api.open({ fullscreen: false });
  f.hud.dispatch("keydown", { key: "Escape" }); assert.equal(f.api.isOpen(), false);
  const visible = fixture({ transcriptVisible: true }); visible.api.open({ fullscreen: false }); visible.api.close();
  assert.equal(visible.count.stopPolling, 0, "closing HUD must preserve an existing transcript tab poller");
}
{
  const f = fixture(); f.channel.value = "source"; f.api.update();
  assert.equal(f.element("#hud-translation-pane").hidden, true); assert.equal(f.element("#hud-order").disabled, true);
  f.api.setTranscript([{ sourceText: "<img src=x onerror=alert(1)>\nSynthetic line", translations: {} }]);
  assert.equal(f.element("#hud-source").textContent, "<img src=x onerror=alert(1)>\nSynthetic line", "caption payload remains plain text");
  f.channel.replaceChildren(); f.channel.value = ""; f.api.update();
  assert.equal(f.element("#hud-language").disabled, true); assert.match(f.element("#hud-status").textContent, /청취 언어/);
}
{
  const f = fixture(); f.api.setTranscript(rows);
  f.element("#listening-mode").value = "replay"; f.api.update();
  assert.notEqual(f.element("#hud-translation").textContent, "Synthetic EN", "live captions must not masquerade as recorded-audio alignment");
  f.element("#transcript-scope").value = "archive"; f.api.setTranscript(rows);
  assert.equal(f.element("#hud-translation").textContent, "Synthetic EN");
  assert.match(f.element("#hud-status").textContent, /표시 중인 스크립트/);
}
{
  const denied = fixture({ fullscreen: "denied" }); denied.api.open();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(denied.api.isOpen(), true); assert.equal(denied.element("#hud-fullscreen-note").hidden, false);
  denied.api.close();
  const delayed = fixture({ fullscreen: "delayed" }); delayed.api.open(); delayed.api.close(); delayed.finishFullscreen();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(delayed.api.isOpen(), false); assert.equal(delayed.document.fullscreenElement, null);
  assert.equal(delayed.count.fullscreenExits, 1, "late fullscreen acknowledgment must not revive closed HUD");
  const reopened = fixture({ fullscreen: "delayed" }); reopened.api.open(); reopened.api.close();
  reopened.api.open({ fullscreen: false }); reopened.finishFullscreen();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(reopened.api.isOpen(), true, "an old fullscreen completion must not dismiss a newer open HUD");
  assert.equal(reopened.count.fullscreenExits, 0); reopened.api.close();
  const accepted = fixture({ fullscreen: "accepted" }); accepted.api.open(); await new Promise(resolve => setImmediate(resolve)); accepted.api.close();
  assert.equal(accepted.document.fullscreenElement, null); assert.equal(accepted.count.fullscreenExits, 1);
  const nativeEscape = fixture({ fullscreen: "accepted" }); nativeEscape.api.open();
  await new Promise(resolve => setImmediate(resolve));
  nativeEscape.document.fullscreenElement = null; nativeEscape.document.dispatch("fullscreenchange");
  assert.equal(nativeEscape.api.isOpen(), true, "browser fullscreen exit must preserve the caption HUD");
  assert.equal(nativeEscape.element("#hud-fullscreen-note").hidden, false);
  assert.equal(nativeEscape.main.inert, true, "the HUD remains the active accessible view");
  nativeEscape.element("#hud-close").dispatch("click");
  assert.equal(nativeEscape.api.isOpen(), false, "the visible close control still leaves the HUD");
  assert.equal(nativeEscape.history.position, 0);
  const foreign = fixture(); foreign.document.fullscreenElement = foreign.main; foreign.api.open(); foreign.api.close();
  assert.equal(foreign.document.fullscreenElement, foreign.main); assert.equal(foreign.count.fullscreenExits, 0);
}
console.log("PASS: actual HUD controller with synthetic DOM; lane/source pairing, empty text, language/order, focus/Escape, Back/Forward, 50 reopen cycles, polling ownership, replay honesty, fullscreen denial/late close, text safety");
console.log("NOT RUN: rendered browser/device accessibility, touch/viewport behavior or actual audio/provider streaming");
