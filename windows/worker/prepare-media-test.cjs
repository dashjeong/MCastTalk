'use strict';
// Produces a development-only manifest. Never edits a user's installed workspace.
const fs=require('node:fs');const path=require('node:path');const crypto=require('node:crypto');
const root=process.cwd(), assets=path.resolve('.tools/media-assets');
const output=path.resolve(process.argv[2]||'.run/media-20260922/engine');
if(!output.startsWith(path.join(root,'.run')+path.sep))throw new Error('Test output must be in .run');
async function main(){
 fs.mkdirSync(output,{recursive:true});
 if(!fs.existsSync(path.join(assets,'whisper')))fs.cpSync('.tools/whisper-b5130/runtime/Release',path.join(assets,'whisper'),{recursive:true});
 const files=[];
 function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isDirectory())walk(f);else files.push(f);}}
 for(const name of ['llama-cpu','llama-vulkan','whisper','sherpa-onnx-supertonic-3-tts-int8-2026-05-11','vits-melo-tts-zh_en'])walk(path.join(assets,name));
 for(const name of ['qwen3-4b-q4.gguf','ggml-small-q5_1.bin','ggml-large-v3-turbo-q5_0.bin','SUPERTONIC-MODEL-LICENSE.txt'])files.push(path.join(assets,name));
 const hashes={};for(const f of files){const h=crypto.createHash('sha256');for await(const b of fs.createReadStream(f))h.update(b);hashes[path.relative(assets,f).split(path.sep).join('/')]=h.digest('hex');}
 const config={assetRoot:assets,tempRoot:path.join(output,'tmp'),threads:4,backend:process.env.MCASTTALK_TEST_BACKEND||'cpu',sttModel:'ggml-small-q5_1.bin',sha256:hashes,usage:'development-evaluation; not redistribution approval'};
 fs.writeFileSync(path.join(output,'engine.json'),JSON.stringify(config,null,2));console.log(path.join(output,'engine.json'));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
