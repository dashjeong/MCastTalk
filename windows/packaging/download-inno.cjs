'use strict';
// Build-time tooling only; never called by the installed application.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
async function main(){
 const root=path.resolve('.tools/inno673');fs.mkdirSync(root,{recursive:true});
 const response=await fetch('https://api.github.com/repos/jrsoftware/issrc/releases/tags/is-6_7_3',{headers:{'User-Agent':'MCastTalk-build'}});
 if(!response.ok)throw new Error('Inno release metadata: '+response.status);
 const release=await response.json(),asset=release.assets.find(a=>a.name==='innosetup-6.7.3.exe');
 if(!asset||!asset.digest?.startsWith('sha256:'))throw new Error('Missing upstream asset digest');
 const file=path.join(root,asset.name);
 if(!fs.existsSync(file)){
  const r=await fetch(asset.browser_download_url);if(!r.ok)throw new Error('Download: '+r.status);
  const bytes=Buffer.from(await r.arrayBuffer());fs.writeFileSync(file,bytes,{flag:'wx'});
 }
 const hash=crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
 if('sha256:'+hash!==asset.digest)throw new Error('Inno digest mismatch');
 fs.writeFileSync(path.join(root,'evidence.json'),JSON.stringify({url:asset.browser_download_url,sha256:hash,bytes:fs.statSync(file).size},null,2));console.log(file);
}
main().catch(e=>{console.error(e);process.exitCode=1;});
