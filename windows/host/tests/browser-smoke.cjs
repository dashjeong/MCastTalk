// Run with NODE_PATH pointing to an installed Playwright package directory.
// This checks the real browser + packaged host; it does not mock transport.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { randomBytes } = require('node:crypto');

async function main() {
  const base = process.env.MCASTTALK_TEST_URL || 'http://127.0.0.1:8791';
  const output = path.resolve(process.env.MCASTTALK_TEST_OUTPUT || '.run/account-verification');
  const testPassword = process.env.MCASTTALK_TEST_PASSWORD;
  assert.ok(testPassword, 'Set MCASTTALK_TEST_PASSWORD to the test-only fixture password');
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const errors = [];
  const pages = [];
  const check = [];
  async function waitForText(page, selector, text) {
    await page.waitForFunction(({ selector, text }) =>
      document.querySelector(selector)?.textContent.includes(text), { selector, text });
  }
  async function submitAndSettle(page, selector) {
    await page.locator(selector).click();
    await page.waitForFunction(selector => !document.querySelector(selector).disabled, selector);
  }
  async function openLoggedIn(username, password = testPassword) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(`${username}: ${error.message}`));
    page.on('console', msg => { if (msg.type() === 'error') errors.push(`${username}: ${msg.text()}`); });
    await page.goto(base, { waitUntil: 'networkidle' });
    await page.locator('#login-view').waitFor({ state: 'visible' });
    if (username === 'alice') await page.screenshot({ path: path.join(output, 'login.png'), fullPage: true });
    await page.locator('#login-username').fill(username);
    await page.locator('#login-password').fill(password);
    await page.locator('#login-button').click();
    await page.locator('#prejoin').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#login-password').inputValue(), '');
    pages.push(page);
    return page;
  }
  async function join(name, language = 'ko', room = 'browser-smoke', participantId = null, username = name.toLowerCase()) {
    const page = await openLoggedIn(username);
    // Deliberately reuse a departed ID to exercise connection-generation safety.
    if (participantId) await page.evaluate(id => { state.participantId = id; }, participantId);
    assert.equal(await page.locator('#display-name').inputValue(), name);
    assert.equal(await page.locator('#display-name').getAttribute('readonly'), '');
    await page.locator('#room-id').fill(room);
    await page.locator('#input-language').selectOption(language);
    if (name === 'Alice') await page.screenshot({ path: path.join(output, 'prejoin.png'), fullPage: true });
    await page.locator('#join-button').click();
    await page.locator('#meeting').waitFor({ state: 'visible' });
    await waitForText(page, '#participant-gallery', name);
    return page;
  }
  try {
    const alice = await join('Alice');
    const bob = await join('Bob', 'en');
    const bobId = await bob.evaluate(() => state.participantId);
    const carol = await join('Carol', 'ja');
    const outsider = await join('Other', 'zh-CN', 'other-room');
    await waitForText(alice, '#participant-count', '3');
    check.push('three participant roster synchronizes');
    for (const page of pages) await page.locator('#chat-button').click();
    const marker = '전체 채팅 <img src=x onerror=alert(1)> ' + Date.now();
    await alice.locator('#message-input').fill(marker);
    await alice.locator('#send-button').click();
    for (const page of [alice, bob, carol]) await waitForText(page, '#chat-feed', marker);
    assert.equal(await bob.locator('#chat-feed img').count(), 0);
    assert.equal((await outsider.locator('#chat-feed').textContent()).includes(marker), false);
    check.push('room message delivered; HTML stays text; other room isolated');
    const bobValue = await alice.locator('#chat-recipient option').evaluateAll(options =>
      options.find(option => option.textContent.includes('Bob'))?.value);
    assert.ok(bobValue, 'Bob is a selectable private recipient');
    await alice.locator('#chat-recipient').selectOption(bobValue);
    const secret = 'PRIVATE-ALICE-BOB-' + Date.now();
    await alice.locator('#message-input').fill(secret);
    await alice.locator('#message-input').press('Enter');
    await waitForText(alice, '#chat-feed', secret);
    await waitForText(bob, '#chat-feed', secret);
    assert.equal((await carol.locator('#chat-feed').textContent()).includes(secret), false);
    assert.equal((await outsider.locator('#chat-feed').textContent()).includes(secret), false);
    check.push('private message visible only to sender and selected recipient');
    await alice.screenshot({ path: path.join(output, 'meeting-chat.png'), fullPage: true });
    await alice.locator('#preferences-button').click();
    await alice.locator('#listen-language').selectOption('ja');
    await waitForText(alice, '#preference-status', '저장되었습니다');
    await waitForText(bob, '#participant-list', '청취 日本語');
    check.push('language preference saved and synchronized');
    await alice.locator('#close-panel-button').click();
    await alice.locator('#speaker-button').click();
    assert.equal(await alice.locator('#speaker-button').getAttribute('aria-pressed'), 'true');
    await alice.keyboard.press('Alt+p');
    await alice.locator('#participants-panel').waitFor({ state: 'visible' });
    await alice.keyboard.press('Escape');
    await alice.locator('#side-panel').waitFor({ state: 'hidden' });
    assert.equal(await alice.locator('#microphone-button').isDisabled(), false);
    assert.equal(await alice.locator('#camera-button').isDisabled(), false);
    assert.equal(await alice.evaluate(() => meetingMedia.stream.getTracks().length), 0);
    check.push('layout, keyboard panel controls and capability gates');
    await alice.setViewportSize({ width: 390, height: 844 });
    await alice.screenshot({ path: path.join(output, 'meeting-narrow.png'), fullPage: true });
    assert.equal(await alice.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true);
    check.push('narrow viewport has no horizontal overflow');
    await bob.locator('#disconnect-button').click();
    await bob.locator('#prejoin').waitFor({ state: 'visible' });
    await waitForText(alice, '#participant-count', '2');
    assert.equal(await alice.locator('#chat-recipient').inputValue(), bobValue);
    await alice.locator('#chat-button').click();
    await alice.locator('#message-input').fill('This must not become public');
    assert.equal(await alice.locator('#send-button').isDisabled(), true);
    await waitForText(alice, '#recipient-hint', '연결이 종료되거나 변경');
    check.push('departed private recipient never silently becomes public');
    check.push('leave updates remaining roster');
    const replacement = await join('Mallory', 'en', 'browser-smoke', bobId);
    await waitForText(alice, '#participant-gallery', 'Mallory');
    assert.equal(await alice.locator('#chat-recipient').inputValue(), bobValue);
    assert.equal(await alice.locator('#send-button').isDisabled(), true);
    check.push('reused participant ID cannot reactivate a previous private recipient');
    const replacementValue = await alice.locator('#chat-recipient option').evaluateAll(options =>
      options.find(option => !option.disabled && option.textContent.includes('Mallory'))?.value);
    assert.ok(replacementValue && replacementValue !== bobValue, 'New presence has a distinct selection');
    await alice.locator('#chat-recipient').selectOption(replacementValue);
    const newSecret = 'NEW-PRESENCE-ONLY-' + Date.now();
    await alice.locator('#message-input').fill(newSecret);
    await alice.locator('#send-button').click();
    await replacement.locator('#chat-button').click();
    await waitForText(replacement, '#chat-feed', newSecret);
    await waitForText(alice, '#chat-feed', newSecret);
    assert.equal((await carol.locator('#chat-feed').textContent()).includes(newSecret), false);
    check.push('new presence receives a DM only after explicit selection');

    const admin = await openLoggedIn('smoke-admin');
    await admin.locator('#admin-button').click();
    const managedUsername = 'managed-' + Date.now();
    await admin.locator('#create-username').fill(managedUsername);
    await admin.locator('#create-display-name').fill('Managed user');
    await admin.locator('#create-password').fill(testPassword);
    assert.equal(await admin.locator('#create-password').getAttribute('minlength'), '8');
    assert.equal(await admin.locator('#create-password').getAttribute('maxlength'), '128');
    await submitAndSettle(admin, '#create-account-button');
    await waitForText(admin, '#accounts-list', managedUsername);
    assert.equal(await admin.locator('#create-password').inputValue(), '');
    const managed = await join('Managed user', 'en', 'managed-room', null, managedUsername);
    assert.equal(await managed.locator('#admin-button').isVisible(), false);
    const denied = await managed.request.get(base + '/api/v1/admin/accounts');
    assert.equal(denied.status(), 403);
    check.push('administrator creates an account; user logs in but cannot access account administration');

    await admin.locator('#accounts-list button').filter({ hasText: managedUsername }).click();
    await admin.locator('#edit-enabled').uncheck();
    await submitAndSettle(admin, '#edit-account-button');
    await waitForText(admin, '#account-status', '저장했습니다');
    await managed.locator('#login-view').waitFor({ state: 'visible' });
    assert.equal(await managed.locator('#meeting').isVisible(), false);
    assert.equal(await managed.locator('#chat-feed').textContent(), '');
    assert.equal(await alice.locator('#meeting').isVisible(), true);
    check.push('disabling an account revokes its active room connection without terminating other users');

    await admin.locator('#edit-enabled').check();
    await submitAndSettle(admin, '#edit-account-button');
    await waitForText(admin, '#account-status', '저장했습니다');
    const resetPassword = randomBytes(6).toString('base64'); // Exactly eight characters, test-only.
    await admin.locator('#reset-password').fill(resetPassword);
    assert.equal(await admin.locator('#reset-password').getAttribute('minlength'), '8');
    await submitAndSettle(admin, '#reset-password-button');
    await waitForText(admin, '#account-status', '초기화했습니다');
    const managedAgain = await openLoggedIn(managedUsername, resetPassword);
    check.push('administrator can reactivate an account and reset its password to eight characters');

    await managedAgain.locator('#my-account-button').click();
    await managedAgain.locator('#current-password').fill(resetPassword);
    const changedPassword = randomBytes(6).toString('base64');
    await managedAgain.locator('#new-password').fill(changedPassword);
    await managedAgain.locator('#confirm-password').fill(changedPassword);
    assert.equal(await managedAgain.locator('#new-password').getAttribute('minlength'), '8');
    assert.equal(await managedAgain.locator('#confirm-password').getAttribute('minlength'), '8');
    await managedAgain.locator('#password-button').click();
    await managedAgain.locator('#login-view').waitFor({ state: 'visible' });
    const changedLogin = await openLoggedIn(managedUsername, changedPassword);
    await changedLogin.locator('#logout-button').click();
    await changedLogin.locator('#login-view').waitFor({ state: 'visible' });
    check.push('own eight-character password change revokes old sessions and accepts the new password');

    const guestUsername = 'guest-' + Date.now();
    await admin.locator('#create-account-details > summary').click();
    await admin.locator('#create-username').fill(guestUsername);
    await admin.locator('#create-display-name').fill('Managed guest');
    await admin.locator('#create-password').fill(testPassword);
    await admin.locator('#create-role').selectOption('GUEST');
    await admin.locator('#create-guest-room').fill('guest-room');
    assert.ok(await admin.locator('#create-expires-at').inputValue());
    await submitAndSettle(admin, '#create-account-button');
    await waitForText(admin, '#accounts-list', guestUsername);
    const guest = await openLoggedIn(guestUsername);
    assert.equal(await guest.locator('#room-id').inputValue(), 'guest-room');
    await guest.locator('#join-button').click();
    await guest.locator('#meeting').waitFor({ state: 'visible' });
    check.push('administrator creates an expiring guest account scoped to its assigned room');
    await admin.locator('#accounts-list button').filter({ hasText: guestUsername }).click();
    await admin.locator('#edit-enabled').uncheck();
    await submitAndSettle(admin, '#edit-account-button');
    await guest.locator('#login-view').waitFor({ state: 'visible' });
    check.push('guest account revocation removes active meeting access');
    await admin.screenshot({ path: path.join(output, 'administrator-accounts.png'), fullPage: true });

    const twinOne = await join('동명이인', 'ko', 'browser-smoke', null, 'twin-one');
    const twinTwo = await join('동명이인', 'ko', 'browser-smoke', null, 'twin-two');
    await waitForText(alice, '#participant-count', '5');
    await alice.setViewportSize({ width: 1440, height: 1000 });
    const identityOptions = await alice.locator('#chat-recipient option').allTextContents();
    assert.ok(identityOptions.some(text => text.includes('(@twin-one)')));
    assert.ok(identityOptions.some(text => text.includes('(@twin-two)')));
    await alice.locator('#participants-button').click();
    await alice.locator('#participant-search').fill('@twin-one');
    assert.equal(await alice.locator('#participant-list > li').count(), 1);
    await waitForText(alice, '#participant-search-status', '검색 결과 1명');
    await alice.screenshot({ path: path.join(output, 'participant-search.png'), fullPage: true });
    check.push('duplicate display names remain distinguishable by account and searchable participant identity');
    await alice.locator('#participant-list .participant-dm').click();
    await waitForText(alice, '#recipient-hint', '@twin-one');
    const identitySecret = 'EXACT-ACCOUNT-DM-' + Date.now();
    await alice.locator('#message-input').fill(identitySecret);
    await alice.locator('#send-button').click();
    await twinOne.locator('#chat-button').click();
    await twinTwo.locator('#chat-button').click();
    await waitForText(twinOne, '#chat-feed', identitySecret);
    await waitForText(alice, '#chat-feed', identitySecret);
    assert.equal((await twinTwo.locator('#chat-feed').textContent()).includes(identitySecret), false);
    check.push('participant private-chat shortcut delivers only to the selected same-name account');
    const previousRecipient = await alice.locator('#chat-recipient').inputValue();
    await alice.locator('#message-input').fill('Keep this draft private');
    await alice.locator('#participants-button').click();
    await alice.locator('#participant-search').fill('@twin-two');
    alice.once('dialog', dialog => dialog.dismiss());
    await alice.locator('#participant-list .participant-dm').click();
    assert.equal(await alice.locator('#chat-recipient').inputValue(), previousRecipient);
    alice.once('dialog', dialog => dialog.accept());
    await alice.locator('#participant-list .participant-dm').click();
    await waitForText(alice, '#recipient-hint', '@twin-two');
    assert.equal(await alice.locator('#message-input').inputValue(), 'Keep this draft private');
    await alice.locator('#message-input').fill('');
    check.push('private shortcut confirms a draft recipient change and preserves cancellation without sending');
    for (const twin of [twinOne, twinTwo]) {
      await twin.locator('#disconnect-button').click();
      await twin.locator('#prejoin').waitFor({ state: 'visible' });
    }
    await waitForText(alice, '#participant-count', '3');

    assert.equal(await alice.locator('#operator-button').isVisible(), false);
    assert.equal((await alice.request.get(base + '/api/v1/admin/diagnostics')).status(), 403);
    const diagnosticsResponse = await admin.request.get(base + '/api/v1/admin/diagnostics');
    assert.equal(diagnosticsResponse.status(), 200);
    const diagnostics = await diagnosticsResponse.json();
    assert.deepEqual(diagnostics.membership, { activeRooms: 2, joinedConnections: 4 });
    assert.equal(diagnostics.capabilities.sttIntegrated, false);
    for (const secretValue of [identitySecret, secret, 'twin-one', 'browser-smoke', testPassword]) {
      assert.equal(JSON.stringify(diagnostics).includes(secretValue), false);
    }
    check.push('operator diagnostics enforce administrator access and expose aggregate counts without private data');
    await admin.locator('#close-account-button').click();
    await admin.locator('#operator-button').click();
    await waitForText(admin, '#diagnostics-status', '확인 완료');
    assert.equal(await admin.locator('#diagnostics-metrics > div').count(), 10);
    await waitForText(admin, '#diagnostics-capabilities', 'STT 연결미제공');
    await admin.screenshot({ path: path.join(output, 'operator-dashboard.png'), fullPage: true });
    await admin.setViewportSize({ width: 390, height: 844 });
    assert.equal(await admin.locator('#account-dialog').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    await admin.screenshot({ path: path.join(output, 'operator-narrow.png'), fullPage: true });
    await admin.locator('#close-account-button').click();
    // HTMLDialogElement dispatches its close event asynchronously.
    await admin.waitForFunction(() => !document.querySelector('#account-dialog').open &&
      document.querySelector('#diagnostics-metrics').childElementCount === 0);
    assert.equal(await admin.locator('#diagnostics-metrics > div').count(), 0);
    check.push('operator dashboard renders actual metrics and unsupported capabilities, clears on close and fits narrow screens');

    const cookies = await admin.context().cookies(base);
    assert.ok(cookies.some(cookie => cookie.httpOnly && cookie.sameSite === 'Strict'));
    assert.equal(await admin.evaluate(() => localStorage.length), 0);
    const crossTab = await alice.context().newPage();
    pages.push(crossTab);
    crossTab.on('pageerror', error => errors.push(`cross-tab: ${error.message}`));
    crossTab.on('console', msg => { if (msg.type() === 'error') errors.push(`cross-tab: ${msg.text()}`); });
    await crossTab.goto(base, { waitUntil: 'networkidle' });
    await crossTab.locator('#prejoin').waitFor({ state: 'visible' });
    // Exercise server-side cookie replacement independently of BroadcastChannel.
    const replacementStatus = await crossTab.evaluate(async password => {
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'mallory', password }),
      });
      return response.status;
    }, testPassword);
    assert.equal(replacementStatus, 200);
    await alice.locator('#meeting').waitFor({ state: 'hidden' });
    assert.equal(await alice.locator('#chat-feed').textContent(), '');
    check.push('replacing a shared browser session clears the previous account private meeting in other tabs');
    await crossTab.reload({ waitUntil: 'networkidle' });
    await crossTab.locator('#prejoin').waitFor({ state: 'visible' });
    assert.equal(await crossTab.locator('#display-name').inputValue(), 'Mallory');
    await crossTab.locator('#logout-button').click();
    await crossTab.locator('#login-view').waitFor({ state: 'visible' });
    assert.equal(await crossTab.locator('#chat-feed').textContent(), '');
    check.push('HttpOnly Strict session cookie and logout clear transient private chat');
    assert.deepEqual(errors, []);
    const result = { ok: true, checkedAt: new Date().toISOString(), base, checks: check, consoleErrors: errors };
    await fs.writeFile(path.join(output, 'browser-smoke.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } catch (error) {
    if (pages[0]) await pages[0].screenshot({ path: path.join(output, 'failure.png'), fullPage: true });
    console.error(JSON.stringify({ ok: false, checks: check, consoleErrors: errors, error: error.stack }, null, 2));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
