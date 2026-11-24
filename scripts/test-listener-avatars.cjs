/* 여섯 청취자와 통역사가 같은 방송에 접속한다. 실제 청취 웹과 합성 방송 응답을 사용한다. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const assets = path.join(__dirname, '../core/server/src/main/assets/listener');
const read = file => fs.readFileSync(path.join(assets, file), 'utf8');
const javascript = ['i18n.js', 'player.js', 'replay.js'].map(read).join('\n');
const translations = {
  en: 'Museum entrance', ja: '展示館の入口です', zh: '展馆入口', 'zh-TW': '展館入口',
  ar: 'مدخل المتحف', vi: 'Lối vào bảo tàng',
};
const channels = Object.keys(translations).map(tag => ({
  id: tag.toLowerCase(), name: tag, languageTag: tag, sampleRate: 24000,
  interpreterState: 'AI', audioReadiness: 'READY', transcriptReadiness: 'READY',
}));
const line = {
  sequence: 1, sourceText: '전시관 입구입니다.', isFinal: true, translations,
  translationProviders: { EN: 'ML_KIT', ja: 'GEMMA' },
  translationLatencyMillis: {}, firstAudioLatencyMillis: {}, synthesisLatencyMillis: {},
};

const interpreterSession = provider => ({
  name: '영어', language: 'en', state: 'READY', live: true, briefing: '', glossary: [],
  captions: { transcripts: [{ ...line, translationProviders: { EN: provider } }] },
});

const personas = [
  {
    name: 'Alex', locale: 'en-US', entry: '/interpreter', interpreter: true, scenario: '통역 초안 제공자가 바뀔 때 출처 갱신',
    async check(page) {
      const credit = page.locator('#draft-credit');
      await credit.waitFor();
      assert.equal(await credit.getAttribute('href'), 'https://translate.google.com/');
      assert.match(await credit.evaluate(node => getComputedStyle(node).backgroundImage), /^url\("data:image\/png;base64,/);
      assert.equal(await page.locator('#draft').innerText(), translations.en);
      let provider = 'ML_KIT_PARTIAL';
      await page.route('**/api/interpreter/session?*', route => route.fulfill({ json: interpreterSession(provider) }));
      await page.locator('#draft-credit-scope').waitFor();
      assert.ok(await credit.isVisible());
      provider = 'GEMMA';
      await credit.waitFor({ state: 'hidden' });
      await page.locator('summary').click();
      await page.locator('.google-translate-disclaimer').waitFor();
      assert.equal(await page.locator('#draft').innerText(), translations.en);
    },
  },
  {
    name: 'Maya', locale: 'en-US', entry: '/en', scenario: '영어 초대 링크에서 자막 확인',
    async check(page) {
      await page.getByText(translations.en, { exact: true }).first().waitFor();
      const credit = page.locator('#current-caption .google-translate-credit');
      assert.equal(await credit.getAttribute('href'), 'https://translate.google.com/');
      const badgeUrl = await page.waitForFunction(() => {
        const link = document.querySelector('#current-caption .google-translate-credit');
        return link && getComputedStyle(link).backgroundImage.match(/data:image\/png;base64,[\w+/=]+/)?.[0];
      });
      const dimensions = await page.evaluate(async url => {
        const image = new Image();
        image.src = url;
        await image.decode();
        return [image.naturalWidth, image.naturalHeight];
      }, badgeUrl);
      assert.deepEqual(dimensions, [352, 32]);
      await page.locator('#more-options-toggle').click();
      await page.locator('.google-translate-disclaimer').waitFor();
      assert.equal(new URL(page.url()).hash, '');
      assert.equal(await page.locator('#channel').inputValue(), 'en');
    },
  },
  {
    name: 'Yuki', locale: 'ja-JP', entry: '/', scenario: '일본어 자동 선택과 지난 원문 확인',
    async check(page) {
      assert.equal(await page.locator('#channel').inputValue(), 'ja');
      await page.locator('#current-caption').getByText(translations.ja, { exact: true }).waitFor();
      assert.equal(await page.locator('#current-caption .google-translate-credit').count(), 0);
      await page.locator('#tab-transcript').focus();
      await page.keyboard.press('Enter');
      assert.equal(await page.evaluate(() => document.activeElement.id), 'tab-player');
      await page.locator('#transcript-language').selectOption('source');
      await page.locator('#transcript-list').getByText(line.sourceText, { exact: false }).first().waitFor();
      assert.equal(await page.locator('#transcript-list .google-translate-credit').count(), 0);
      await page.locator('#transcript-scope').selectOption('archive');
      await page.locator('#transcript-language').selectOption('all');
      const archivedCredit = page.locator('#transcript-list .google-translate-credit');
      await archivedCredit.first().waitFor();
      assert.equal(await archivedCredit.count(), 2);
      assert.match(await archivedCredit.first().locator('..').innerText(), /Museum entrance/);
      await page.locator('#more-options-toggle').click();
      await page.locator('#hud-open').click();
      const hudCredit = page.locator('#hud-translation .google-translate-credit');
      await hudCredit.first().waitFor();
      assert.equal(await hudCredit.count(), 2);
      assert.equal(await page.locator('#hud-translation .translation-credit-scope').count(), 1);
      assert.match(await hudCredit.first().evaluate(link => link.previousSibling.textContent), /Museum entrance$/);
      assert.match(await hudCredit.first().evaluate(link => link.nextSibling.textContent), /Local museum guide/);
    },
  },
  {
    name: 'Mei', locale: 'zh-TW', entry: '/', scenario: '일반 초대 링크에서 번체 화면·음성·자막 선택',
    async check(page) {
      assert.equal(await page.locator('html').getAttribute('lang'), 'zh-tw');
      assert.equal(await page.locator('#channel').inputValue(), 'zh-tw');
      assert.equal(await page.locator('#transcript-language').inputValue(), 'zh-tw');
      await page.getByText(translations['zh-TW'], { exact: true }).first().waitFor();
    },
  },
  {
    name: 'Samira', locale: 'ar-EG', entry: '/ar', scenario: '오른쪽부터 읽는 큰 자막과 키보드 복귀',
    async check(page) {
      assert.equal(await page.locator('html').getAttribute('dir'), 'rtl');
      await page.locator('#more-options-toggle').click();
      await page.locator('#hud-open').click();
      await page.locator('#hud-order').selectOption('translation-first');
      assert.equal(await page.locator('#hud-captions > :first-child').getAttribute('id'), 'hud-translation-pane');
      await page.keyboard.press('Escape');
      assert.ok(await page.locator('#listener-hud').isHidden());
      await page.waitForFunction(() => document.activeElement.id === 'hud-open', null, { timeout: 3000 });
    },
  },
  {
    name: 'Daeho', locale: 'ko-KR', entry: '/en', scenario: '작은 화면에서 자막 확대 및 되돌리기',
    async check(page) {
      const caption = page.locator('#current-caption');
      const fontSize = () => caption.evaluate(el => parseFloat(getComputedStyle(el).fontSize));
      const initial = await fontSize();
      await page.locator('#caption-size').click();
      assert.ok(await fontSize() > initial);
      await page.locator('#caption-size').click();
      await page.locator('#caption-size').click();
      assert.equal(await fontSize(), initial);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    },
  },
  {
    name: 'Linh', locale: 'vi-VN', entry: '/vi', pin: true, scenario: '잘못된 입장 번호 수정 후 베트남어 청취',
    async check(page) {
      await page.locator('#pin').fill('1111');
      await page.locator('#pin-form button').click();
      await page.waitForFunction(() => !!document.querySelector('#pin-error').textContent);
      await page.locator('#pin').fill('1234');
      await page.locator('#pin-form button').click();
      await page.getByText(translations.vi, { exact: true }).first().waitFor();
      assert.ok(await page.locator('#pin-dialog').isHidden());
    },
  },
];

async function runPersona(browser, persona) {
  const context = await browser.newContext({ locale: persona.locale, viewport: { width: 375, height: 667 } });
  try {
    await context.route('**/*', route => {
      const endpoint = new URL(route.request().url()).pathname;
      switch (endpoint) {
        case '/interpreter':
          return route.fulfill({ contentType: 'text/html;charset=utf-8', body: read('../interpreter/index.html') });
        case '/speaker.css':
          return route.fulfill({ contentType: 'text/css', body: read('../speaker/speaker.css') + '\n' + read('../translation-credit.css') });
        case '/interpreter/interpreter.css':
          return route.fulfill({ contentType: 'text/css', body: read('../interpreter/interpreter.css') });
        case '/interpreter/interpreter.js':
          return route.fulfill({ contentType: 'application/javascript', body: read('../interpreter/interpreter.js') });
        case '/api/interpreter/join':
          return route.fulfill({ json: { authenticated: true } });
        case '/api/interpreter/session':
          return route.fulfill({ json: interpreterSession('ML_KIT') });
        case '/api/session':
          return route.fulfill({ json: { access: persona.pin ? 'pin' : 'qr_token' } });
        case '/api/status':
          return route.fulfill({ json: {
            generation: 1, active: true, liveAudioEnabled: true, broadcast: { phase: 'LIVE', nextAction: 'NONE' }, channels,
          } });
        case '/api/transcripts':
          return route.fulfill({ json: { transcripts: [line] } });
        case '/api/replay-captions':
          return route.fulfill({ json: [line, {
            ...line, sequence: 2, sourceText: '박물관 안내입니다.', translations: { en: 'Local museum guide' },
            translationProviders: { en: 'ML_KIT_PARTIAL' },
          }] });
        case '/api/join':
          return route.fulfill({ status: route.request().postData() === '1234' ? 200 : 403, body: 'synthetic-avatar-token' });
        case '/player.css':
          return route.fulfill({ contentType: 'text/css', body: read('player.css') + '\n' + read('../translation-credit.css') });
        case '/player.js':
          return route.fulfill({ contentType: 'application/javascript', body: javascript });
        default:
          if (endpoint === persona.entry) return route.fulfill({ contentType: 'text/html;charset=utf-8', body: read('index.html') });
          return route.fulfill({ status: 404, body: '' });
      }
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.setDefaultTimeout(45000);
    const fragment = persona.interpreter ? '#lang=en&invite=synthetic-invite' : persona.pin ? '' : '#token=synthetic-avatar-token';
    await page.goto('http://localhost:18799' + persona.entry + fragment, { waitUntil: 'domcontentloaded' });
    if (!persona.pin && !persona.interpreter) await page.waitForFunction(() => document.getElementById('channel').options.length);
    await persona.check(page);
    assert.deepEqual(errors, []);
  } finally {
    await context.close();
  }
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH, headless: true });
  try {
    const results = await Promise.allSettled(personas.map(persona => runPersona(browser, persona)));
    results.forEach((result, index) => {
      const { name, scenario } = personas[index];
      if (result.status === 'fulfilled') console.log('PASS', name, scenario);
      else {
        console.error('FAIL', name, scenario, result.reason);
        process.exitCode = 1;
      }
    });
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
