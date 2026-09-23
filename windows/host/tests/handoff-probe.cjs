'use strict';
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const checks = [];
  try {
    const page = await browser.newPage();
    await page.goto(process.env.MCASTTALK_TEST_URL, { waitUntil: 'networkidle' });
    const probe = async (room, suffix, subtitle = false) => page.evaluate(({ room, suffix, subtitle }) => new Promise(resolve => {
      const socket = new WebSocket('ws://' + location.host + '/ws/v1/rooms/' + room + suffix);
      const timer = setTimeout(() => { socket.close(); resolve({ timeout: true }); }, 5000);
      const done = result => { clearTimeout(timer); socket.close(); resolve(result); };
      socket.onopen = () => socket.send(JSON.stringify({ type: 'JOIN_ROOM', participantId: 'probe-' + crypto.randomUUID(), displayName: 'probe', inputLanguage: 'ko', listenLanguage: 'en' }));
      socket.onerror = () => done({ denied: true });
      socket.onmessage = event => {
        const message = JSON.parse(event.data);
        if (message.type === 'ROOM_JOINED') {
          if (!subtitle) done({ joined: true, rosterSize: message.participants.length });
          else socket.send(JSON.stringify({ type: 'SUBTITLE_CHUNK', translatedText: 'forged-test-translation', sourceLanguage: 'ko', targetLanguage: 'en' }));
        }
        if (message.type === 'SUBTITLE_CHUNK' || message.type === 'ERROR') done({ type: message.type, code: message.code });
      };
    }), { room, suffix, subtitle });
    let observed = await probe('private-review', '/listen');
    checks.push({ id: 'anonymous-room-admission', ok: observed.denied === true, observed });
    async function login(username) {
      await page.evaluate(async ({ username, password }) => {
        const response = await fetch('/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) });
        if (!response.ok) throw new Error('Fixture login failed');
      }, { username, password: process.env.MCASTTALK_TEST_PASSWORD });
    }
    await login('test-guest');
    observed = await probe('forbidden-room', '/listen');
    checks.push({ id: 'guest-room-boundary', ok: observed.denied === true, observed });
    await login('alice');
    observed = await probe('private-review', '', true);
    checks.push({ id: 'forged-subtitle', ok: observed.type === 'ERROR', observed });
    fs.writeFileSync(path.join(process.env.MCASTTALK_TEST_OUTPUT, 'handoff-probe.json'), JSON.stringify({ checks }, null, 2));
    console.log(JSON.stringify({ checks }, null, 2));
    process.exitCode = checks.every(item => item.ok) ? 0 : 1;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
