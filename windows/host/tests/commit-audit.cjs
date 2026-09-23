'use strict';
// Read-only commit preparation. This script never stages, commits, pushes, or contacts a remote.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const output = process.argv[2];
if (!output || !path.resolve(output).startsWith(path.join(process.cwd(), '.run') + path.sep)) throw new Error('Output must be under source/.run');
const git = args => execFileSync('git', args, { encoding: 'utf8' });
const paths = [...new Set(['.gitignore', 'settings.gradle.kts', 'gradle/verification-metadata.xml', ...git(['ls-files', '--cached', '--others', '--exclude-standard', '-z', 'windows']).split('\0').filter(Boolean)])].sort();
const issues = [], files = [];
const sensitive = /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}|\bsk-proj-[A-Za-z0-9_-]{30,}/;
for (const file of paths) {
  const stat = fs.lstatSync(file);
  if (stat.isSymbolicLink()) { issues.push({ file, type: 'symlink-requires-review' }); continue; }
  if (stat.size > 5 * 1024 * 1024 || /\.(exe|dll|gguf|bin|zip|db|sqlite3?|keystore|jks)$/i.test(file)) issues.push({ file, type: 'binary-or-large-asset' });
  const data = fs.readFileSync(file);
  if (sensitive.test(data.toString('utf8'))) issues.push({ file, type: 'possible-secret-manual-review-required' });
  files.push({ path: file, bytes: data.length, sha256: crypto.createHash('sha256').update(data).digest('hex') });
}
const result = { checkedAt: new Date().toISOString(), head: git(['rev-parse', 'HEAD']).trim(),
  proposedFiles: files.length, totalBytes: files.reduce((n, f) => n + f.bytes, 0),
  stagedPaths: git(['diff', '--cached', '--name-only']).trim().split('\n').filter(Boolean), issues, files,
  limitations: 'Heuristic secret/asset checks are not a complete security audit. Test fixture passwords are synthetic; real .run account data must stay ignored.' };
fs.mkdirSync(output, { recursive: true });
fs.writeFileSync(path.join(output, 'commit-audit.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify({ ...result, files: undefined }, null, 2));
process.exitCode = issues.length ? 1 : 0;
