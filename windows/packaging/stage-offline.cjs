'use strict';
// Reproducible local assembly; no downloads, credentials or user workspaces.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const [appArg,pythonArg,nativeArg]=process.argv.slice(2),root=process.cwd();
if(!appArg||!pythonArg||!nativeArg)throw new Error('Usage: stage-offline.cjs APP_IMAGE PYTHON_ROOT STATIC_NATIVE_ROOT');
const app=path.resolve(appArg),python=path.resolve(pythonArg),offline=path.join(app,'offline');
const native=path.resolve(nativeArg);
if(!app.startsWith(path.join(root,'build/windows-packaging')+path.sep))throw new Error('App image must be under build/windows-packaging');
if(fs.existsSync(offline))throw new Error('Refusing to replace a staged offline bundle');
const assets=path.resolve('.tools/media-assets'),site=path.resolve('.tools/media-python/Lib/site-packages');
function copy(source,dest,filter=()=>true){fs.cpSync(source,dest,{recursive:true,errorOnExist:true,force:false,filter});}
function walk(dir){const files=[];for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isSymbolicLink())throw new Error('No links in bundle');if(e.isDirectory())files.push(...walk(f));else files.push(f);}return files.sort();}
async function hash(file){const h=crypto.createHash('sha256');for await(const b of fs.createReadStream(file))h.update(b);return h.digest('hex');}
async function main(){
 const dependencyCheck=spawnSync(path.join(python,'python.exe'),[path.resolve('windows/packaging/verify_native_dependencies.py'),'--assets',native],{stdio:'inherit',windowsHide:true});
 if(dependencyCheck.status!==0)throw new Error('Native runtime dependency check failed; offline bundle was not staged');
 if(!fs.existsSync(path.join(native,'native-runtime-evidence.json')))throw new Error('Static runtime build provenance is required');
 fs.mkdirSync(offline,{recursive:true});
 for(const name of ['llama-cpu','llama-vulkan','whisper','native-licenses','native-runtime-evidence.json'])copy(path.join(native,name),path.join(offline,'assets',name));
 for(const name of ['sherpa-onnx-supertonic-3-tts-int8-2026-05-11','vits-melo-tts-zh_en',
   'qwen3-4b-q4.gguf','ggml-small-q5_1.bin','ggml-large-v3-turbo-q5_0.bin','SUPERTONIC-MODEL-LICENSE.txt','WHISPER-LICENSE.txt','LLAMA-LICENSE.txt','QWEN-LICENSE.txt','download-evidence.json'])copy(path.join(assets,name),path.join(offline,'assets',name));
 for(const name of ['python.exe','python3.dll','python312.dll','vcruntime140.dll','vcruntime140_1.dll','LICENSE.txt','DLLs'])copy(path.join(python,name),path.join(offline,'python',name));
 copy(path.join(python,'Lib'),path.join(offline,'python/Lib'),source=>!path.relative(path.join(python,'Lib'),source).split(path.sep).some(s=>['site-packages','__pycache__','test','tests','idlelib','turtledemo'].includes(s)));
 for(const name of fs.readdirSync(site).filter(n=>/^(numpy|sherpa_onnx)([._-]|$)/.test(n)&&!n.endsWith('.whl')))
   copy(path.join(site,name),path.join(offline,'python/Lib/site-packages',name),source=>!source.split(path.sep).includes('__pycache__'));
 copy(path.resolve('windows/worker/mcasttalk_worker'),path.join(offline,'worker/mcasttalk_worker'),source=>!source.split(path.sep).includes('__pycache__'));
 const assetHashes=[];
 for(const file of walk(path.join(offline,'assets')))assetHashes.push(path.relative(path.join(offline,'assets'),file).split(path.sep).join('/')+'='+await hash(file));
 fs.writeFileSync(path.join(offline,'asset-hashes.properties'),assetHashes.join('\n')+'\n');
 copy(path.resolve('windows/packaging/OFFLINE_NOTICES.md'),path.join(offline,'OFFLINE_NOTICES.md'));
 const hashes=[];let bytes=0;
 for(const file of walk(offline)){hashes.push(path.relative(offline,file).split(path.sep).join('/')+'='+await hash(file));bytes+=fs.statSync(file).size;}
 fs.writeFileSync(path.join(offline,'manifest.properties'),hashes.join('\n')+'\n');
 fs.writeFileSync(path.join(app,'bundle-evidence.json'),JSON.stringify({files:hashes.length,bytes,pythonSource:python,worker:'mcasttalk_worker',networkAtRuntime:'localhost translation API only',releaseStatus:'development candidate; no clean-VM or physical-device UAT claim'},null,2));
 const notices=spawnSync(path.join(python,'python.exe'),[path.resolve('windows/packaging/build_notices.py'),'--app',app,'--repo',root],{stdio:'inherit',windowsHide:true});
 if(notices.status!==0)throw new Error('Offline license inventory failed; installer must not be built');
 console.log(JSON.stringify({offline,files:hashes.length,bytes}));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
