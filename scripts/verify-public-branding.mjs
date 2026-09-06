import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const strings = read('app/src/main/res/values/strings.xml');
const label = strings.match(/name="app_name">([^<]+)</)?.[1];
const subtitle = strings.match(/name="app_subtitle">([^<]+)</)?.[1];
assert.equal(label, 'MCastTalk');
assert.equal(subtitle, '실시간 다국어 통역방송(feat. DMZ peace walk)');
const build = read('app/build.gradle.kts');
const version = build.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
assert.equal(version, '0.2.39');
assert.match(build, /versionCode\s*=\s*45\b/);
assert.match(read('app/src/main/AndroidManifest.xml'), /android:label="@string\/app_name"/);
const screen = read('app/src/main/java/app/guidecast/transmitter/MainActivity.kt');
assert.match(screen, /stringResource\(R\.string\.app_name\)/);
assert.match(screen, /stringResource\(R\.string\.app_subtitle\)/);
const html = read('core/server/src/main/assets/listener/index.html');
assert.match(html, /<title>[^<]*MCastTalk[^<]*<\/title>/);
assert.ok(read('core/server/src/main/assets/listener/player.js').includes('MCastTalk'));
assert.match(read('core/server/src/main/assets/speaker/speaker.html'), /<title>MCastTalk 강사 원격 마이크<\/title>/);
assert.ok(read('core/server/src/main/assets/speaker/setup-trust.html').includes('보안 마이크 인증서 안내 · MCastTalk'));
assert.match(read('client-app/src/main/res/values/strings.xml'), /name="app_name">MCastTalk Client</);
const readme = read('README.md');
assert.ok(readme.startsWith(`# ${label}\n`));
assert.ok(readme.includes(subtitle));
assert.ok(readme.includes(`${version}-alpha`));
assert.equal(read('LICENSE').trimEnd(), read('app/src/main/assets/licenses/APACHE-2.0.txt').trimEnd(),
  'The in-app license text must match the repository license (ignoring trailing whitespace).');
for (const path of ['LICENSE', 'NOTICE', 'README.md', 'CONTRIBUTING.md',
  'docs/THIRD_PARTY_LICENSES.md', 'app/src/main/java/app/guidecast/transmitter/ThirdPartyLicenseCatalog.kt']) {
  assert.doesNotMatch(read(path), /PolyForm|Noncommercial/i, `Obsolete first-party license in ${path}`);
}
assert.ok(read('BRANDING.md').includes('독립적인 서비스 구현'));
for (const path of ['README.md', 'NOTICE', 'SECURITY.md',
  'app/src/main/java/app/guidecast/transmitter/ThirdPartyLicenseCatalog.kt']) {
  const projectLinks = [...read(path).matchAll(/https:\/\/github\.com\/dashjeong\/([A-Za-z0-9_.-]+)/g)];
  assert.ok(projectLinks.every(match => match[1] === 'MCastTalk'),
    `Unexpected project repository link in ${path}`);
}
console.log('PASS: public branding, version, license parity, and repository-link regression');
