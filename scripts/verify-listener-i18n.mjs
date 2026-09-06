import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const base = new URL("../core/server/src/main/assets/listener/", import.meta.url);
const source = await readFile(new URL("i18n.js", base), "utf8");
const player = await readFile(new URL("player.js", base), "utf8");
const html = await readFile(new URL("index.html", base), "utf8");
const context = vm.createContext({});
vm.runInContext(source, context);
const api = context.GuideCastI18n;
for (const [key, values] of Object.entries(api.rows)) {
  assert.equal(values.length, api.locales.length, key);
  assert(values.every(value => value.length > 0 && !/[가-힣]/.test(value)), key);
}
for (const locale of api.locales) {
  const ui = api.create(`/${locale}`);
  assert.equal(ui.locale, locale);
  for (const text of html.matchAll(/>([^<>]*[가-힣][^<>]*)</g)) {
    assert(!/[가-힣]/.test(ui.text(text[1])), `${locale}: ${text[1]}`);
  }
  assert(!/[가-힣]/.test(ui.text("방송 연결 끊김 · 0.5초 후 재연결")));
  assert(!/[가-힣]/.test(ui.text("확정 후 첫 음성 1800ms (2초 목표 이내)")));
  assert.notEqual(ui.text("재생"), "재생");
  const node = {nodeValue: "재생", parentElement: {tagName: "BUTTON"}};
  let visited = false;
  const doc = {documentElement: {}, title: "DMZ 평화걷기 안내 방송", body: {},
    querySelectorAll: () => [],
    createTreeWalker: () => ({currentNode: node, nextNode: () => !visited && (visited = true)})};
  api.apply(doc, ui);
  assert.equal(node.nodeValue, ui.text("재생"));
  assert.equal(doc.documentElement.lang, locale);
  assert.equal(doc.documentElement.dir, locale === "ar" ? "rtl" : "ltr");
}
assert.equal(api.create("/zh-TW").locale, "zh-tw");
assert.equal(api.create("/source").locale, "ko");
assert.equal(api.create("/<script>").locale, "ko");
assert.match(player, /sourceBlock.textContent = `\$\{uiText\("원문"\)\}: \$\{source\}`/);
assert.doesNotMatch(source + player, /\.innerHTML\s*=|eval\(/);
console.log("PASS: 8 localized channel UIs, static copy, dynamic statuses, RTL, fallback, transcript safety");
