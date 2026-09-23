'use strict';
// Reconstruct a review candidate from the immutable handoff snapshot, not live shared files.
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = process.cwd();
const destination = path.join(root, '.run/review-20260922/candidate');
if (fs.existsSync(destination)) throw new Error('Candidate already exists; refusing to overwrite');
fs.mkdirSync(destination, { recursive: true });
function copy(relative, from = root, to = destination) {
  const source = path.join(from, relative);
  if (!fs.existsSync(source) || !fs.statSync(source).isFile()) return;
  const target = path.join(to, relative);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.copyFileSync(source, target);
}
const files = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean);
for (const file of files) copy(file);
copy('settings.gradle.kts'); copy('.gitignore');
const baseline = path.join(root, '.run/review-20260922/baseline');
const manifest = JSON.parse(fs.readFileSync(path.join(baseline, 'manifest.json'), 'utf8'));
for (const entry of manifest.files) copy(entry.path, path.join(baseline, 'snapshot'), path.join(destination, 'windows'));
for (const file of ['ListenerSecurityTest.kt']) copy('windows/host/src/test/kotlin/app/mcasttalk/windows/host/' + file);
for (const file of ['boot-smoke.cjs', 'run-browser-review.cjs', 'review-evidence.cjs']) copy('windows/host/tests/' + file);
console.log(JSON.stringify({ destination, trackedFiles: files.length, handoffFiles: manifest.files.length }));
