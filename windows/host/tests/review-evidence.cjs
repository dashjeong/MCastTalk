'use strict';
// Local review evidence only; never reads account data or environment secrets.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const [action, output, id, command, ...args] = process.argv.slice(2);
if (!output) throw new Error('Usage: review-evidence.cjs snapshot|run OUTPUT [ID COMMAND ARGS...]');
fs.mkdirSync(output, { recursive: true });
if (action === 'snapshot') {
  const root = path.resolve('windows');
  const files = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (['build', '.run', '__pycache__', '.venv'].includes(entry.name)) continue;
      const file = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(file);
      else if (/\.(kt|kts|java|js|cjs|html|css|md|json|py|ps1|toml|yaml|yml|webmanifest)$/.test(entry.name)) {
        const bytes = fs.readFileSync(file);
        const relative = path.relative(root, file);
        files.push({ path: relative, bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex') });
        const copy = path.join(output, 'snapshot', relative);
        fs.mkdirSync(path.dirname(copy), { recursive: true });
        fs.writeFileSync(copy, bytes);
      }
    }
  }
  walk(root);
  fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify({ at: new Date().toISOString(), files }, null, 2));
  console.log(`Snapshot: ${files.length} source/documentation files (no build or account data)`);
} else if (action === 'run') {
  if (!/^[a-zA-Z0-9_-]+$/.test(id || '') || !command) throw new Error('A safe case ID and executable are required');
  const start = Date.now();
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 600000, windowsHide: true, maxBuffer: 20 * 1024 * 1024 });
  const log = (result.stdout || '') + (result.stderr || '') + (result.error ? '\n' + result.error.message : '');
  fs.writeFileSync(path.join(output, id + '.log'), log);
  const record = { id, checkedAt: new Date().toISOString(), elapsedMs: Date.now() - start, exitCode: result.status, signal: result.signal, ok: result.status === 0, command: [command, ...args] };
  fs.writeFileSync(path.join(output, id + '.json'), JSON.stringify(record, null, 2));
  console.log(JSON.stringify(record));
  console.log(log);
  process.exitCode = record.ok ? 0 : 1;
} else throw new Error('Unknown action');
