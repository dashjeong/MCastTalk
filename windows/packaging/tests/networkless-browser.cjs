'use strict';
// Runs only inside the acceptance guest, against the installed application.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.MCASTTALK_TEST_URL;
const output = process.env.MCASTTALK_TEST_OUTPUT;
const password = process.env.MCASTTALK_TEST_PASSWORD;
assert.equal(base, 'http://127.0.0.1:18791');
assert.equal(password?.length, 8);
assert.ok(output?.startsWith('C:\\MCastTalkResults\\'));
const checks = [], errors = [], externalRequests = [];
async function step(name, action) {
  try { await action(); checks.push({name, passed:true}); }
  catch(error) { checks.push({name, passed:false, error:error.message}); throw error; }
  finally {
    fs.writeFileSync(path.join(output, 'account-browser.json'), JSON.stringify({checks,errors,externalRequests,
      scope:'Network-disabled Windows Sandbox, installed EXE, guest Edge; automated synthetic accounts, not manual first-run GUI'},null,2));
  }
}
async function main() {
  const browser = await chromium.launch({channel:'msedge', headless:true});
  let user, guest, admin;
  async function page(route='/') {
    const context = await browser.newContext({viewport:{width:1440,height:960}});
    const p = await context.newPage(); p.setDefaultTimeout(20000);
    p.on('pageerror',e=>errors.push(e.message));
    p.on('request',r=>{const u=new URL(r.url()); if(!['file:','data:','blob:'].includes(u.protocol)&&u.origin!==base)externalRequests.push(r.url());});
    await p.goto(base+route,{waitUntil:'networkidle'});
    await p.locator('#login-view').waitFor({state:'visible'});
    return p;
  }
  async function login(p, name) {
    await p.locator('#login-username').fill(name);
    await p.locator('#login-password').fill(password);
    await p.locator('#login-button').click();
    await p.locator('#prejoin').waitFor({state:'visible'});
  }
  try {
    await step('Local login page renders without a blank page or script error',async()=>{
      user=await page('/?room=browser-smoke');
      assert.ok((await user.locator('body').innerText()).trim().length>100);
      assert.equal(await user.locator('#login-button').isVisible(),true);
      await user.screenshot({path:path.join(output,'offline-login.png'),fullPage:true});
      assert.deepEqual(errors,[]);
    });
    await step('Unauthenticated invitation never joins a meeting',async()=>{
      assert.equal(await user.locator('#meeting').isVisible(),false);
      assert.equal(await user.locator('#invitation-notice').isVisible(),true);
    });
    await step('Eight-character password authenticates and preserves the invited room',async()=>{
      await login(user,'alice');
      assert.equal(await user.locator('#room-id').inputValue(),'browser-smoke');
      assert.equal(await user.locator('#meeting').isVisible(),false);
    });
    await step('Regular user cannot access administrative accounts',async()=>{
      assert.equal(await user.locator('#admin-button').isVisible(),false);
      assert.equal((await user.request.get(base+'/api/v1/admin/accounts')).status(),403);
    });
    await step('Offline model terms remain a distinct participant-level control',async()=>{
      await user.waitForFunction(()=>INFERENCE_ENABLED);
      assert.equal(await user.locator('#model-terms-accepted').isChecked(),false);
      assert.equal((await user.request.get(base+'/tts-model-license.txt')).status(),200);
    });
    for (const width of [1440,390]) {
      await step(`Model terms remain readable at ${width}px without narrow text columns`,async()=>{
        await user.setViewportSize({width,height:960});
        const layout=await user.locator('#model-terms').evaluate(el=>{
          const box=e=>{const r=e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,bottom:r.bottom,right:r.right};};
          return {container:box(el),label:box(el.querySelector('label')),
            text:box(el.querySelector('label > span')),checkbox:box(el.querySelector('input')),
            paragraph:box(el.querySelector('p')),scrollWidth:el.scrollWidth,clientWidth:el.clientWidth};
        });
        assert.ok(layout.text.width>=layout.container.width-28,'Label text must use the available line width');
        assert.ok(layout.paragraph.y>=layout.label.bottom,'Explanation must follow, not share a column with, the label');
        assert.equal(layout.checkbox.width,18,'Checkbox must not shrink');
        assert.ok(layout.scrollWidth<=layout.clientWidth+1,'Terms must not overflow horizontally');
        await user.locator('#model-terms').screenshot({path:path.join(output,`offline-model-terms-${width}.png`)});
      });
    }
    await user.setViewportSize({width:1440,height:960});
    await step('Room invitation contains only the local origin and chosen room',async()=>{
      await user.locator('#invite-prejoin-button').click();
      assert.equal(await user.locator('#invite-link').inputValue(),base+'/?room=browser-smoke');
      await user.locator('#close-invite-button').click();
    });
    await step('Guest invitation cannot widen its assigned room',async()=>{
      guest=await page('/?room=forbidden-room'); await login(guest,'test-guest');
      assert.equal(await guest.locator('#room-id').inputValue(),'browser-smoke');
      assert.equal(await guest.locator('#room-id').getAttribute('readonly'),'');
      assert.equal(await guest.locator('#meeting').isVisible(),false);
    });
    await step('Guest has neither administrator UI nor administrator API access',async()=>{
      assert.equal(await guest.locator('#admin-button').isVisible(),false);
      assert.equal(await guest.locator('#operator-button').isVisible(),false);
      assert.equal((await guest.request.get(base+'/api/v1/admin/accounts')).status(),403);
    });
    await step('Administrator can open the real account list',async()=>{
      admin=await page(); await login(admin,'smoke-admin');
      await admin.locator('#admin-button').click();
      await admin.waitForFunction(()=>document.querySelector('#accounts-list').textContent.includes('alice'));
      assert.equal((await admin.request.get(base+'/api/v1/admin/accounts')).status(),200);
      await admin.screenshot({path:path.join(output,'offline-admin.png'),fullPage:true});
      await admin.locator('#close-account-button').click();
    });
    await step('Operator diagnostics are available locally',async()=>{
      assert.equal((await admin.request.get(base+'/api/v1/admin/diagnostics')).status(),200);
    });
    await step('Local license HTML renders and contains component references without internet',async()=>{
      const license=await browser.newPage({viewport:{width:1440,height:960}});
      license.on('request',r=>{if(!r.url().startsWith('file:'))externalRequests.push(r.url());});
      await license.goto('file:///C:/MCastTalkAcceptance/app/legal/THIRD_PARTY_LICENSES.html');
      assert.ok((await license.locator('body').innerText()).includes('Whisper'));
      assert.ok(await license.locator('a[href^="#"]').count()>50);
      await license.screenshot({path:path.join(output,'offline-licenses.png'),fullPage:false});
      await license.close();
    });
    await step('Logout invalidates the authenticated session',async()=>{
      await user.locator('#logout-button').click();
      await user.locator('#login-view').waitFor({state:'visible'});
      assert.equal((await(await user.request.get(base+'/api/v1/auth/session')).json()).authenticated,false);
    });
    await step('No browser runtime errors or external application resource requests',async()=>{
      assert.deepEqual(errors,[]); assert.deepEqual(externalRequests,[]);
    });
  } finally { await browser.close(); }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
