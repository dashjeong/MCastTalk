'use strict';
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage();
    await page.goto(process.env.MCASTTALK_TEST_URL, { waitUntil: 'networkidle' });
    await page.evaluate(async password => {
      const response = await fetch('/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'smoke-admin', password }) });
      if (!response.ok) throw new Error('Fixture login failed');
    }, process.env.MCASTTALK_TEST_PASSWORD);
    const evidence = await page.evaluate(async () => {
      const before = await (await fetch('/api/v1/admin/diagnostics')).json();
      const socket = new WebSocket('ws://' + location.host + '/ws/v1/rooms/performance-review');
      const inbox = [], pending = [];
      socket.onmessage = event => { const m = JSON.parse(event.data); if (pending.length) pending.shift()(m); else inbox.push(m); };
      const next = () => new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Response deadline exceeded')), 5000);
        const done = value => { clearTimeout(timer); resolve(value); };
        if (inbox.length) done(inbox.shift()); else pending.push(done);
      });
      try {
        await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
        socket.send(JSON.stringify({ type: 'JOIN_ROOM', participantId: 'performance-speaker', inputLanguage: 'ko', listenLanguage: 'ko' }));
        if ((await next()).type !== 'ROOM_JOINED' || (await next()).type !== 'PARTICIPANT_JOINED') throw new Error('Join contract failed');
        const timings = [];
        // Serialized control RTT only: not a speech latency, concurrent-user, or maximum-throughput benchmark.
        for (let i = 0; i < 100; i++) {
          const start = performance.now();
          socket.send(JSON.stringify({ type: 'PING' }));
          if ((await next()).type !== 'PONG') throw new Error('Unexpected control response');
          timings.push(performance.now() - start);
        }
        for (let i = 0; i < 50; i++) {
          socket.send(JSON.stringify({ type: 'CHAT_SEND', text: 'synthetic bounded message ' + i }));
          const reply = await next();
          if (reply.type !== 'CHAT_MESSAGE' || reply.originalText !== 'synthetic bounded message ' + i) throw new Error('Chat ordering failed');
        }
        const after = await (await fetch('/api/v1/admin/diagnostics')).json();
        return { timings, orderedChats: 50, before: before.runtime, after: after.runtime };
      } finally { socket.close(); }
    });
    assert.equal(evidence.timings.length, 100);
    const sorted = [...evidence.timings].sort((a, b) => a - b);
    const result = { ok: true, checkedAt: new Date().toISOString(), sampleCount: sorted.length, orderedChats: evidence.orderedChats,
      rttMs: { p50: sorted[49], p95: sorted[94], p99: sorted[98], max: sorted[99] }, heap: { before: evidence.before, after: evidence.after },
      scope: 'Single loopback client, sequential control RTT, 50 synthetic chat echoes; NOT STT/NMT/TTS, GPU, load capacity, thermal soak, or human quality evidence' };
    fs.writeFileSync(path.join(process.env.MCASTTALK_TEST_OUTPUT, 'control-performance.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
