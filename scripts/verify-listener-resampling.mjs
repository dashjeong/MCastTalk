import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../core/server/src/main/assets/listener/player.js', import.meta.url), 'utf8');
const context = vm.createContext({ Float32Array, Math, Number });
vm.runInContext(source.slice(source.indexOf('function resample('), source.indexOf('function stopPlayback(')) + '\nglobalThis.convert = resample;', context);
for (const [from, to] of [[16000, 44100], [24000, 48000], [48000, 16000]]) {
  const input = Float32Array.from({ length: 4801 }, (_, index) => Math.sin(index * 0.041) * 0.7);
  const whole = context.convert(input, from, to, { lastSample: null, resamplePhase: 0 });
  const state = { lastSample: null, resamplePhase: 0 };
  const fragmented = [];
  let offset = 0;
  for (let chunk = 0; offset < input.length; chunk++) {
    const end = Math.min(input.length, offset + [1, 137, 320, 83, 2, 479][chunk % 6]);
    fragmented.push(...context.convert(input.subarray(offset, end), from, to, state));
    offset = end;
  }
  assert.equal(fragmented.length, whole.length, `${from}->${to}: variable PCM frame boundaries changed duration`);
  assert.ok(fragmented.every((value, index) => Math.abs(value - whole[index]) < 0.00001), `${from}->${to}: frame boundary discontinuity`);
  assert.ok(Math.abs(whole.length - (input.length - 1) * to / from) <= 1, 'one-sample lookahead only');
}
console.log('PASS: fragmented PCM has the same duration and samples as a continuous resampling stream');
