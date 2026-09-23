'use strict';
// Build-time only. No downloader is called by the offline meeting runtime.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pipeline } = require('node:stream/promises');
const { Readable } = require('node:stream');
const root = path.resolve('.tools/media-assets');
const assets = [
  ['WHISPER-LICENSE.txt','https://raw.githubusercontent.com/ggml-org/whisper.cpp/927cfce34f31707e17f2bff35c349632fb9e2c3a/LICENSE',null],
  ['LLAMA-LICENSE.txt','https://raw.githubusercontent.com/ggml-org/llama.cpp/b10964/LICENSE',null],
  ['QWEN-LICENSE.txt','https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507/resolve/cdbee75f17c01a7cc42f958dc650907174af0554/LICENSE',null],
  ['ggml-small-q5_1.bin','https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin','ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb'],
  ['ggml-large-v3-turbo-q5_0.bin','https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin','394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2'],
  ['unicode_indexer.json','https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/onnx/unicode_indexer.json',null],
  ['M1.json','https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/voice_styles/M1.json',null],
  ['SUPERTONIC-MODEL-LICENSE.txt','https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/LICENSE',null],
  ['qwen3-4b-q4.gguf', 'https://huggingface.co/lmstudio-community/Qwen3-4B-Instruct-2507-GGUF/resolve/4edb920b6f14e3b9284d4502a6485103d72cde05/Qwen3-4B-Instruct-2507-Q4_K_M.gguf', '8cdb57cbb880d313736a9bc4e3d3d2485f145b5e19cf33783746e753e82641fc'],
  ['supertonic3.tar.bz2', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2', '82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427'],
  ['melo-zh.tar.bz2', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2', 'e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514', 'local-pin'],
];
async function sha(file) { const h=crypto.createHash('sha256'); for await (const b of fs.createReadStream(file)) h.update(b); return h.digest('hex'); }
async function main() {
  fs.mkdirSync(root, {recursive:true});
  const release = await (await fetch('https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/b10964')).json();
  for (const backend of ['cpu', 'vulkan']) {
    const asset = release.assets.find(a => new RegExp('bin-win-' + backend + '-x64.zip$').test(a.name));
    if (!asset) throw new Error('Missing pinned Windows ' + backend + ' runtime');
    assets.push(['llama-' + backend + '.zip', asset.browser_download_url, asset.digest?.replace('sha256:', '')]);
  }
  const records=[];
  for (const [name,url,expected,source='upstream'] of assets) {
    const file=path.join(root,name);
    if (!fs.existsSync(file) || (expected && await sha(file)!==expected)) {
      const response=await fetch(url, {signal:AbortSignal.timeout(1800000)});
      if (!response.ok) throw new Error(name + ' HTTP ' + response.status);
      console.log('Downloading',name,response.headers.get('content-length'));
      await pipeline(Readable.fromWeb(response.body),fs.createWriteStream(file+'.partial'));
      const digest=await sha(file+'.partial');
      if (expected && expected!==digest) throw new Error('Checksum mismatch: '+name);
      fs.renameSync(file+'.partial',file);
    }
    const record={name,url,sha256:await sha(file),bytes:fs.statSync(file).size,upstreamDigestVerified:Boolean(expected)&&source==='upstream',expectedDigestVerified:Boolean(expected)};
    records.push(record); console.log(JSON.stringify(record));
  }
  fs.writeFileSync(path.join(root,'download-evidence.json'),JSON.stringify(records,null,2));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
