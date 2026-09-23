'use strict';
// Read-only local document test. Browser offline mode is NOT OS/VM isolation.
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
async function main(){
 const [appArg,outArg]=process.argv.slice(2),app=path.resolve(appArg),output=path.resolve(outArg);
 if(!output.startsWith(path.resolve('.run')+path.sep))throw new Error('Output must stay under .run');
 const manifest=JSON.parse(fs.readFileSync(path.join(app,'legal/manifest.json'),'utf8'));
 fs.mkdirSync(output,{recursive:true});
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const checks=[],errors=[],externalRequests=[];
 try{
  const context=await browser.newContext({offline:true,viewport:{width:1280,height:900}});
  const page=await context.newPage();
  page.on('pageerror',e=>errors.push(e.message));
  page.on('request',request=>{if(/^https?:/.test(request.url()))externalRequests.push(request.url());});
  await page.goto(pathToFileURL(path.join(app,'legal/THIRD_PARTY_LICENSES.html')).href);
  assert.equal(await page.title(),'MCastTalk 구성요소·라이선스 정보');checks.push('Korean local document loads with browser offline');
  assert.equal(await page.locator('tbody tr').count(),manifest.components.length);checks.push('All component rows rendered');
  assert.equal(await page.locator('section').count(),manifest.documentCount);checks.push('Every bundled original rendered');
  assert.ok((await page.locator('body').innerText()).includes('설치 동의서가 아니라'));checks.push('Notice is distinguished from installation consent');
  await page.screenshot({path:path.join(output,'license-viewer.png')});
  const first=page.locator('a[href^="#doc-"]').first();
  const hash=await first.getAttribute('href');await first.click();
  assert.equal(new URL(page.url()).hash,hash);assert.ok(await page.locator(hash).isVisible());checks.push('Original-license link opens inside the same offline document');
  await page.locator(hash).getByRole('link',{name:'목록으로'}).click();
  assert.equal(new URL(page.url()).hash,'#components');checks.push('Return-to-list link works offline');
  assert.deepEqual(externalRequests,[]);checks.push('No external resource request');
  assert.deepEqual(errors,[]);checks.push('No page exceptions');
  fs.writeFileSync(path.join(output,'browser-results.json'),JSON.stringify({passed:true,checks,externalRequests,errors,
   scope:'Edge browser offline mode, local license document only; NOT internet-blocked Windows acceptance'},null,2));
  console.log(JSON.stringify({passed:true,checks:checks.length,externalRequests:externalRequests.length}));
 }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
