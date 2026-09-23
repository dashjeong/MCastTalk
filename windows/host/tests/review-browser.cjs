'use strict';
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.MCASTTALK_TEST_URL;
const output = process.env.MCASTTALK_TEST_OUTPUT;
const password = process.env.MCASTTALK_TEST_PASSWORD;
const checks = [], errors = [], pages = [];
async function step(id, name, action) {
  const started = performance.now();
  try { await action(); checks.push({ id, name, ok: true, elapsedMs: Math.round(performance.now() - started) }); }
  catch (error) { checks.push({ id, name, ok: false, error: error.message }); throw error; }
  finally { fs.writeFileSync(path.join(output, 'review-browser.json'), JSON.stringify({ checks, errors, scope: 'Automated user-role scenarios on Windows/Edge; no real audio, human UAT, or clean VM' }, null, 2)); }
}
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  async function open(url = '/') {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
    pages.push(page);
    await page.goto(base + url, { waitUntil: 'networkidle' });
    await page.locator('#login-view').waitFor({ state: 'visible' });
    return page;
  }
  async function login(page, username) {
    await page.locator('#login-username').fill(username);
    await page.locator('#login-password').fill(password);
    await page.locator('#login-button').click();
    await page.locator('#prejoin').waitFor({ state: 'visible' });
  }
  async function text(page, selector, content) {
    await page.waitForFunction(({ selector, content }) => document.querySelector(selector)?.textContent.includes(content), { selector, content });
  }
  let listener, speaker, guest, admin;
  try {
    await step('U01', 'Startup displays login instead of a broken loading screen', async () => {
      listener = await open('/listen/browser-smoke');
      assert.equal(await listener.locator('#auth-loading').isVisible(), false);
    });
    await step('U02', 'Read-only invitation never joins anonymously', async () => {
      assert.equal(await listener.locator('#meeting').isVisible(), false);
      await text(listener, '#invitation-notice', '로그인한 뒤');
    });
    await step('U03', 'Browser CSP is respected without inline styles or scripts', async () => {
      assert.equal(await listener.locator('[style], script:not([src])').count(), 0);
      assert.deepEqual(errors, []);
    });
    await step('U04', 'Capability API discloses inference, media, and anonymous-listener gates', async () => {
      const c = await (await listener.request.get(base + '/api/v1/capabilities')).json();
      assert.equal(c.authenticationRequired, true); assert.equal(c.anonymousListener, false);
      assert.equal(c.mediaTransport, 'not-enabled'); assert.equal(c.translationIntegrated, false);
      assert.equal(c.publicNetworkReady, false);
    });
    await step('U05', 'Eight-character fixture password logs in and preserves invited room', async () => {
      assert.equal(password.length, 8); await login(listener, 'alice');
      assert.equal(await listener.locator('#room-id').inputValue(), 'browser-smoke');
      assert.equal(await listener.locator('#meeting').isVisible(), false);
    });
    await step('U06', 'Explicit read-only join uses account identity and read-only role', async () => {
      await listener.locator('#join-button').click();
      await listener.locator('#meeting').waitFor({ state: 'visible' });
      await text(listener, '#role-badge', '읽기 전용');
      await text(listener, '#participant-gallery', 'Alice');
    });
    await step('U07', 'Read-only participant cannot compose or send chat', async () => {
      await listener.locator('#chat-button').click();
      assert.equal(await listener.locator('#message-input').isDisabled(), true);
      assert.equal(await listener.locator('#send-button').isDisabled(), true);
    });
    await step('U08', 'Unavailable microphone, captions, ducking and extra services are disabled', async () => {
      for (const id of ['microphone-button', 'captions-button', 'ducking-slider', 'service-live', 'service-notes', 'service-script'])
        assert.equal(await listener.locator('#' + id).isDisabled(), true, id);
    });
    await step('U09', 'Language preference update is acknowledged for a read-only participant', async () => {
      await listener.locator('#preferences-button').click();
      await listener.locator('#listen-language').selectOption('ja');
      await text(listener, '#preference-status', '저장되었습니다');
    });
    await step('U10', 'Authenticated speaker joins the same room', async () => {
      speaker = await open('/?room=browser-smoke'); await login(speaker, 'bob');
      await speaker.locator('#join-button').click();
      await speaker.locator('#meeting').waitFor({ state: 'visible' });
      await text(listener, '#participant-count', '2');
    });
    await step('U11', 'Room chat travels from speaker to read-only participant', async () => {
      await speaker.locator('#chat-button').click();
      await speaker.locator('#message-input').fill('review public message');
      await speaker.locator('#send-button').click();
      await text(listener, '#chat-feed', 'review public message');
    });
    await step('U12', 'Leaving clears room state and allows explicit read-only rejoin', async () => {
      await listener.locator('#disconnect-button').click();
      await listener.locator('#prejoin').waitFor({ state: 'visible' });
      assert.equal(await listener.locator('#chat-feed').textContent(), '');
      await listener.locator('#listener-quick-button').dblclick();
      await listener.locator('#meeting').waitFor({ state: 'visible' });
      await text(listener, '#participant-count', '2');
    });
    await step('U13', 'Invitations accurately disclose loopback-only access', async () => {
      await listener.locator('#invite-button').click();
      assert.equal(await listener.locator('#listener-invite-link').inputValue(), base + '/listen/browser-smoke');
      await text(listener, '#invite-scope', '다른 PC·휴대전화에서는 접속할 수 없습니다');
      await text(listener, '#invite-account-note', '권한을 부여하지 않습니다');
    });
    await step('U14', 'Denied clipboard falls back to selected read-only link', async () => {
      await listener.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error('test denial')) } }));
      await listener.locator('#copy-listener-button').click();
      await text(listener, '#invite-status', 'Ctrl+C');
      assert.equal(await listener.locator('#listener-invite-link').evaluate(el => el.selectionEnd - el.selectionStart), (base + '/listen/browser-smoke').length);
    });
    await step('U15', 'Late clipboard completion cannot resurrect closed invitation data', async () => {
      await listener.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => new Promise(resolve => { window.finishTestCopy = resolve; }) } }));
      await listener.locator('#copy-listener-button').click();
      await listener.locator('#close-invite-button').click();
      await listener.evaluate(() => window.finishTestCopy());
      assert.equal(await listener.locator('#invite-status').textContent(), '');
      assert.equal(await listener.locator('#listener-invite-link').inputValue(), '');
    });
    await step('U16', 'A reopened invitation has no permanently disabled copy button', async () => {
      await listener.locator('#invite-button').click();
      assert.equal(await listener.locator('#copy-listener-button').isEnabled(), true);
      await listener.keyboard.press('Escape');
    });
    await step('U17', 'A 390px viewport contains the invitation without horizontal overflow', async () => {
      await listener.setViewportSize({ width: 390, height: 844 });
      await listener.locator('#invite-button').click();
      assert.equal(await listener.locator('#invite-dialog').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
      await listener.screenshot({ path: path.join(output, 'read-only-narrow.png'), fullPage: true });
      await listener.keyboard.press('Escape');
    });
    await step('U18', 'Conflicting read-only invitation cannot replace a guest room assignment', async () => {
      guest = await open('/listen/forbidden-room'); await login(guest, 'test-guest');
      await text(guest, '#invitation-notice', '게스트 권한이 없습니다');
      assert.equal(await guest.locator('#room-id').inputValue(), 'browser-smoke');
      assert.equal(await guest.locator('#meeting').isVisible(), false);
    });
    await step('U19', 'Malformed listener paths are not interpreted as valid invitations', async () => {
      const bad = await open('/listen/ab');
      await text(bad, '#invitation-notice', '올바르지 않습니다');
      assert.equal(await bad.locator('#meeting').isVisible(), false);
      await bad.context().close();
    });
    await step('U20', 'Contradictory listener path and query are rejected', async () => {
      const bad = await open('/listen/room-one?room=room-two');
      await text(bad, '#invitation-notice', '올바르지 않습니다');
      await bad.context().close();
    });
    await step('A21', 'Administrator can revoke a connected read-only participant', async () => {
      admin = await open(); await login(admin, 'smoke-admin');
      await admin.locator('#admin-button').click();
      await text(admin, '#accounts-list', 'alice');
      await admin.locator('#accounts-list button').filter({ hasText: 'alice' }).click();
      await admin.locator('#edit-enabled').uncheck();
      await admin.locator('#edit-account-button').click();
      await listener.locator('#login-view').waitFor({ state: 'visible' });
      assert.equal(await listener.locator('#chat-feed').textContent(), '');
      assert.equal(await listener.locator('#meeting').isVisible(), false);
    });
    await step('O22', 'Operator sees actual unavailable inference rather than success claims', async () => {
      await admin.locator('#close-account-button').click();
      await admin.locator('#operator-button').click();
      await text(admin, '#diagnostics-status', '확인 완료');
      await text(admin, '#diagnostics-capabilities', '미제공');
    });
    await step('U23', 'Guest has no administrative or operator UI', async () => {
      assert.equal(await guest.locator('#admin-button').isVisible(), false);
      assert.equal(await guest.locator('#operator-button').isVisible(), false);
    });
    await step('Q24', 'No browser runtime or CSP errors across the completed role scenarios', async () => {
      assert.deepEqual(errors, []);
      await speaker.screenshot({ path: path.join(output, 'speaker-final.png'), fullPage: true });
    });
    console.log(JSON.stringify({ ok: true, checks, errors }, null, 2));
  } catch (error) {
    console.error(JSON.stringify({ ok: false, checks, errors, error: error.stack }, null, 2));
    for (const page of pages.filter(p => !p.isClosed()).slice(0, 2)) await page.screenshot({ path: path.join(output, 'failure-' + pages.indexOf(page) + '.png'), fullPage: true });
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
