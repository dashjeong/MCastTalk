'use strict';
const {chromium}=require('playwright');const https=require('node:https');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const base=process.env.MCASTTALK_TEST_URL,output=process.env.MCASTTALK_TEST_OUTPUT;
const ca=fs.readFileSync(path.join(process.env.MCASTTALK_TEST_DATA,'config/tls/MCastTalk-LAN.crt'));
function request(route,headers={},method='GET',body){return new Promise((resolve,reject)=>{
 const req=https.request(base+route,{ca,...(headers.Host?{servername:'localhost'}:{}),method,headers,timeout:5000},res=>{let text='';res.on('data',d=>text+=d);res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,text}));});
 req.on('error',reject);req.on('timeout',()=>req.destroy());if(body)req.write(body);req.end();
});}
async function main(){
 const checks=[];const pass=x=>{checks.push(x);console.log('PASS',x);};
 let r=await request('/health/ready');assert.equal(r.status,200);pass('HTTPS via actual private LAN IP with pinned CA and SAN validation');
 r=await request('/api/v1/auth/session');assert.equal(r.status,200);pass('same-host LAN account API allowed');
 r=await request('/api/v1/auth/session',{Host:'evil.invalid:8791'});assert.equal(r.status,403);pass('Host spoof blocked');
 r=await request('/api/v1/auth/login',{'Origin':'https://evil.invalid','Content-Type':'application/json'},'POST','{}');assert.equal(r.status,403);pass('cross-origin login blocked');
 r=await request('/api/v1/auth/login',{'Origin':base,'Content-Type':'application/json'},'POST',JSON.stringify({username:'alice',password:process.env.MCASTTALK_TEST_PASSWORD}));
 assert.equal(r.status,200);assert.ok(r.headers['set-cookie'][0].includes('Secure'));assert.ok(r.headers['set-cookie'][0].includes('HttpOnly'));pass('LAN login uses Secure HttpOnly cookie');
 const browser=await chromium.launch({channel:'msedge',headless:true});
 let page;const errors=[],network=[];
 try{
  // Browser context bypass is test-only; node:https above validates the actual certificate.
  page=await browser.newPage({ignoreHTTPSErrors:true});page.on('pageerror',e=>errors.push(e.message));
  page.on('response',async r=>{if(r.status()>=400)network.push({path:new URL(r.url()).pathname,status:r.status(),error:await r.text().catch(()=>null)});});
  page.on('requestfailed',r=>network.push({path:new URL(r.url()).pathname,failure:r.failure()}));
  await page.goto(base);assert.equal(await page.evaluate(()=>isSecureContext),true);pass('LAN browser is a secure context');
  await page.locator('#login-username').fill('bob');await page.locator('#login-password').fill(process.env.MCASTTALK_TEST_PASSWORD);await page.locator('#login-button').click();await page.locator('#prejoin').waitFor({state:'visible'});pass('LAN browser login works');
  await page.locator('#room-id').fill('lan-test');await page.locator('#invite-prejoin-button').click();
  assert.equal(await page.locator('#invite-link').inputValue(),base+'/?room=lan-test');pass('invitation uses routable HTTPS LAN address');
  await page.locator('#close-invite-button').click();await page.locator('#join-button').click();await page.locator('#meeting').waitFor({state:'visible'});pass('authenticated WSS room join over LAN IP');
  assert.equal(await page.locator('#camera-button').isEnabled(),true);pass('camera available on LAN secure context');
  await page.screenshot({path:path.join(output,'lan-https.png'),fullPage:true});assert.deepEqual(errors,[]);pass('no LAN page exceptions');
  fs.writeFileSync(path.join(output,'lan-results.json'),JSON.stringify({checks,scope:'actual host private IP; same PC browser, not a second physical device; OS trust store/firewall unchanged'},null,2));
 }catch(error){
  if(page)await page.screenshot({path:path.join(output,'lan-failure.png'),fullPage:true});
  fs.writeFileSync(path.join(output,'failure.json'),JSON.stringify({checks,errors,network,message:error.message},null,2));
  console.error(JSON.stringify({errors,network}));throw error;
 }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
