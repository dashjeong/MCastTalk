'use strict';
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const errors = [];
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
    await page.goto(process.env.MCASTTALK_TEST_URL, { waitUntil: 'networkidle' });
    await page.screenshot({ path: path.join(process.env.MCASTTALK_TEST_OUTPUT, 'boot.png'), fullPage: true });
    const record = { errors, title: await page.title(), loginVisible: await page.locator('#login-view').isVisible() };
    fs.writeFileSync(path.join(process.env.MCASTTALK_TEST_OUTPUT, 'boot.json'), JSON.stringify(record, null, 2));
    console.log(JSON.stringify(record));
    assert.deepEqual(errors, []);
    assert.equal(record.loginVisible, true);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
