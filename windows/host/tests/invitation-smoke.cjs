// Real host/account/room transport. Clipboard is a browser-local test double
// so this test does not replace the operator's desktop clipboard contents.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

async function main() {
  const base = process.env.MCASTTALK_TEST_URL || 'http://127.0.0.1:8791';
  const password = process.env.MCASTTALK_TEST_PASSWORD;
  assert.ok(password, 'A test-only fixture password is required');
  const output = path.resolve(process.env.MCASTTALK_TEST_OUTPUT || '.run/invitation-verification');
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const errors = [], checks = [], pages = [];
  const text = async (page, selector, value) => page.waitForFunction(({ selector, value }) =>
    document.querySelector(selector)?.textContent.includes(value), { selector, value }, { timeout: 15000 });
  async function open(query) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    context.setDefaultTimeout(15000);
    await context.addInitScript(() => {
      window.testClipboard = { mode: 'success', text: null };
      Object.defineProperty(navigator, 'clipboard', { value: { writeText: async value => {
        if (window.testClipboard.mode === 'failure') throw new Error('Test denied clipboard');
        if (window.testClipboard.mode === 'pending') return new Promise(resolve => { window.testClipboard.finish = resolve; });
        window.testClipboard.text = value;
      } } });
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    pages.push(page);
    await page.goto(base + query, { waitUntil: 'networkidle' });
    await page.locator('#login-view').waitFor({ state: 'visible' });
    return page;
  }
  async function login(page, username) {
    await page.locator('#login-username').fill(username);
    await page.locator('#login-password').fill(password);
    await page.locator('#login-button').click();
    await page.locator('#prejoin').waitFor({ state: 'visible' });
  }
  try {
    const user = await open('/?room=browser-smoke&token=must-not-be-copied#ignored');
    await text(user, '#invitation-notice', '로그인한 뒤');
    assert.equal(await user.locator('#meeting').isVisible(), false);
    await login(user, 'alice');
    assert.equal(await user.locator('#room-id').inputValue(), 'browser-smoke');
    assert.equal(await user.locator('#meeting').isVisible(), false);
    checks.push('anonymous invitation requires login, prefills the room, and never joins automatically');
    await user.locator('#join-button').click();
    await user.locator('#meeting').waitFor({ state: 'visible' });
    await user.locator('#invite-button').click();
    const link = await user.locator('#invite-link').inputValue();
    assert.equal(link, base + '/?room=browser-smoke');
    await text(user, '#invite-scope', '이 PC에서만 사용할 수 있습니다');
    await text(user, '#invite-scope', 'LAN HTTPS 설정이 필요합니다');
    assert.equal(await user.locator('#invite-manage-accounts').isVisible(), false);
    checks.push('user creates a room-only link without source query credentials and sees local-only limits');
    await user.locator('#copy-invite-button').click();
    await text(user, '#invite-status', '복사했습니다');
    assert.equal(await user.evaluate(() => testClipboard.text), link);
    await user.evaluate(() => { testClipboard.mode = 'failure'; });
    await user.locator('#copy-invite-button').click();
    await text(user, '#invite-status', 'Ctrl+C');
    assert.equal(await user.locator('#invite-link').evaluate(input => input.selectionEnd - input.selectionStart), link.length);
    checks.push('clipboard success and denial/fallback contracts pass using an isolated browser test double');
    await user.setViewportSize({ width: 390, height: 844 });
    assert.equal(await user.locator('#invite-dialog').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    await user.screenshot({ path: path.join(output, 'invitation-narrow.png'), fullPage: true });
    await user.keyboard.press('Escape');
    await user.waitForFunction(() => !document.querySelector('#invite-dialog').open && document.querySelector('#invite-link').value === '');
    assert.equal(await user.evaluate(() => document.activeElement.id), 'invite-button');
    checks.push('invitation fits a narrow viewport, Escape restores focus and clears link content');
    await user.locator('#invite-button').click();
    await user.evaluate(() => { testClipboard.mode = 'pending'; });
    await user.locator('#copy-invite-button').click();
    await user.locator('#close-invite-button').click();
    await user.evaluate(() => testClipboard.finish());
    assert.equal(await user.locator('#invite-status').textContent(), '');
    checks.push('late clipboard completion cannot restore a closed invitation');

    for (const query of ['/?room=%3Cimg%20src=x%3E', '/?room=one&room=two', '/?room=ab']) {
      const bad = await open(query);
      await text(bad, '#invitation-notice', '올바르지 않습니다');
      assert.equal(await bad.locator('#invitation-notice img').count(), 0);
      assert.equal(await bad.locator('#meeting').isVisible(), false);
      await bad.context().close();
    }
    checks.push('malformed, injected and duplicate room parameters are rejected without HTML execution');

    const guest = await open('/?room=forbidden-room');
    await login(guest, 'test-guest');
    await text(guest, '#invitation-notice', '게스트 권한이 없습니다');
    assert.equal(await guest.locator('#room-id').inputValue(), 'browser-smoke');
    assert.equal(await guest.locator('#room-id').getAttribute('readonly'), '');
    assert.equal(await guest.locator('#meeting').isVisible(), false);
    checks.push('a conflicting guest invitation does not change the assigned room or enter the forbidden room');
    await guest.goto(link, { waitUntil: 'networkidle' });
    await guest.locator('#prejoin').waitFor({ state: 'visible' });
    await guest.locator('#join-button').click();
    await guest.locator('#meeting').waitFor({ state: 'visible' });
    await text(user, '#participant-count', '2');
    await guest.locator('#invite-button').click();
    assert.equal(await guest.locator('#invite-link').inputValue(), link);
    assert.equal(await guest.locator('#invite-manage-accounts').isVisible(), false);
    checks.push('valid invited guest joins the real room and can share only the room locator, not issue accounts');

    const admin = await open('/?room=browser-smoke');
    await login(admin, 'smoke-admin');
    await admin.locator('#invite-prejoin-button').click();
    await admin.screenshot({ path: path.join(output, 'administrator-invitation.png'), fullPage: true });
    await admin.locator('#invite-manage-accounts').click();
    await admin.locator('#admin-section').waitFor({ state: 'visible' });
    await text(admin, '#accounts-list', 'test-guest');
    checks.push('administrator can open account management from the prejoin invitation dialog');
    await admin.locator('#accounts-list button').filter({ hasText: 'test-guest' }).click();
    await admin.locator('#edit-enabled').uncheck();
    await admin.locator('#edit-account-button').click();
    await guest.locator('#login-view').waitFor({ state: 'visible' });
    assert.equal(await guest.locator('#invite-dialog').evaluate(el => el.open), false);
    assert.equal(await guest.locator('#invite-link').inputValue(), '');
    checks.push('administrator revocation removes the invited guest from the room and clears the invitation dialog');
    await admin.locator('#close-account-button').click();
    await admin.locator('#operator-button').click();
    await text(admin, '#diagnostics-status', '확인 완료');
    await text(admin, '#diagnostics-capabilities', '미제공');
    checks.push('operator still sees unconfigured inference and unsupported public-network capabilities after invitations');
    assert.deepEqual(errors, []);
    const result = { ok: true, checkedAt: new Date().toISOString(), checks, consoleErrors: errors,
      scope: 'isolated account fixture and browser contexts, not a Windows VM; clipboard API simulated' };
    await fs.writeFile(path.join(output, 'invitation-smoke.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } catch (error) {
    const page = pages.find(page => !page.isClosed());
    if (page) await page.screenshot({ path: path.join(output, 'invitation-failure.png'), fullPage: true });
    console.error(JSON.stringify({ ok: false, checks, errors, error: error.stack }, null, 2));
    process.exitCode = 1;
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
