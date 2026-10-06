// Chrome acceptance against route fixtures only. This verifies UI behavior,
// never Windows installation, hardware, downloaded files, inference, or latency.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';

const require = process.env.MCAST_NODE_MODULES ? createRequire(join(resolve(process.env.MCAST_NODE_MODULES), 'package.json')) : createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const output = process.env.MCAST_UI_OUTPUT;
if (output) await mkdir(output, { recursive: true });
const sourceHashes = {};
const files = {};
for (const name of ['index.html', 'app.js', 'style.css']) {
    files[name] = await readFile(join(root, 'web', name));
    sourceHashes[name] = createHash('sha256').update(files[name]).digest('hex');
}
const browser = await chromium.launch(process.env.MCAST_BROWSER_EXECUTABLE ? { headless: true, executablePath: process.env.MCAST_BROWSER_EXECUTABLE } : { headless: true, channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });
const fixtureToken = 'setup-ui-disposable-fixture';
const results = [], errors = [], posts = [], unauthorized = [];
page.setDefaultTimeout(10000);
page.on('pageerror', error => errors.push(error.message));
page.on('dialog', dialog => dialog.dismiss());
const model = { id: 'translation-fixture', name: '번역 모델 fixture', task: 'translation', family: 'fixture', ramGB: 8, bytes: 2000000000, license: 'fixture-only', url: 'https://huggingface.co/fixture/model/resolve/pinned/model.gguf' };
const smallSTT = { ...model, id: 'stt-small-fixture', name: '작은 음성인식 fixture', task: 'stt', bytes: 500000000, ramGB: 1 };
const cpuStarter = { ...smallSTT, id: 'whisper-small-q5', name: 'Whisper Small (Q5) · CPU 시작용', bytes: 190085487 };
let config = { publicBind: '127.0.0.1:8787', publicURL: '', tlsCert: '', tlsKey: '', maxListeners: 30, access: 'qr', listenerPin: '', speakerPin: '', sourceLanguage: 'ko', targetLanguages: ['ko', 'en', 'ja', 'zh', 'es'], translationModel: model.id, sttModel: 'stt-large-fixture', backend: 'cpu', autoResume: false, online: { endpoint: '', model: '', apiKey: '', consent: false } };
let plan = { fingerprint: 'request-a', environmentFingerprint: 'environment-a', artifacts: [model], missing: [model], downloadBytes: 2000000000, requiredDiskBytes: 5000000000, requiredRAMGB: 12, compatible: false, preparationCompatible: true, runtimeCompatible: false, runtimeIssues: ['실행에 필요한 여유 RAM이 부족합니다.'], warnings: [], systemDependencies: [], speechLanguages: { supported: true, missing: ['ko'], error: '' } };
let operation = {}, engine = {}, progress = [];
let statusEngine = {};
let rooms = [{ id: 'room-a', title: '세계 언어 수업', mode: 'lecture', capacity: 30, languages: ['ko', 'en', 'ja', 'zh', 'es'], teacherURL: '/room/teacher-fixture', studentURL: '/room/student-fixture' }, { id: 'room-b', title: '대화 연습', mode: 'conversation', capacity: 30, languages: ['ko', 'en'], teacherURL: '/room/teacher-b', studentURL: '/room/student-b' }];
let failPlan = false;
await page.route('http://127.0.0.1:18896/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (!path.startsWith('/admin/api/')) {
        const name = path === '/' ? 'index.html' : path.slice(1);
        if (!files[name]) return route.fulfill({ status: 404, body: '' });
        return route.fulfill({ status: 200, contentType: name.endsWith('.html') ? 'text/html' : name.endsWith('.css') ? 'text/css' : 'application/javascript', body: files[name] });
    }
    if (request.headers().authorization !== `Bearer ${fixtureToken}`) unauthorized.push(path);
    const reply = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    if (request.method() === 'POST') {
        const body = request.postDataJSON(); posts.push({ path, body });
        if (path === '/admin/api/setup/download') {
            operation = { id: 'setup-a', state: body.prepareOnly ? 'prepared' : 'running', phase: body.prepareOnly ? '파일 준비 완료' : '실제 구동 검증', environmentFingerprint: plan.environmentFingerprint, preparationOnly: body.prepareOnly, functionalVerified: false, artifactIDs: [model.id], completed: 1, checks: [], startupChecks: [] };
            plan = { ...plan, missing: [], downloadBytes: 0, systemDependencies: (plan.systemDependencies || []).map(dependency => ({ ...dependency, installerCached: true, cached: true })), speechLanguages: { supported: true, missing: [], error: '' } };
            engine = body.prepareOnly ? {} : { startupStage: '번역 모델 첫 추론', startupMillis: 1500, startupChecks: [] };
        }
        if (path === '/admin/api/setup/cancel') {
            operation = { ...operation, state: 'cancelled', error: 'fixture 사용자가 준비를 중단했습니다.' }; engine = {};
            return reply({ state: 'cancel_requested' });
        }
        if (path === '/admin/api/config') { config = { ...body }; plan = { ...plan, fingerprint: 'request-model-change', environmentFingerprint: 'environment-model-change' }; }
        return reply({ state: 'accepted', config, pinStatus: { listenerSet: false, speakerSet: false } });
    }
    if (path === '/admin/api/setup/plan') {
        if (failPlan) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'fixture 연결 오류' }) });
        return reply(plan);
    }
    if (path === '/admin/api/setup/status') return reply({ operation, engine, progress });
    if (path === '/admin/api/status') return reply({ config, engine: statusEngine, activeSession: null, publicURL: '', queueDepth: 0, pinStatus: { listenerSet: false, speakerSet: false } });
    if (path === '/admin/api/diagnostics') return reply({ os: 'windows', arch: 'amd64', cpu: 'route fixture', cores: 4, ramGB: 16, availableRamGB: 4, freeDiskGB: 30, measured: true, devices: [], warnings: [], recommendations: [] });
    if (path === '/admin/api/catalog') return reply({ artifacts: [model, smallSTT, cpuStarter], installed: {}, progress: [] });
    if (path === '/admin/api/rooms') return reply(rooms);
    return reply({ operation: {} });
});
const check = async (id, run) => { try { await run(); results.push({ id, passed: true }); } catch (error) { results.push({ id, passed: false, error: error.message }); } };
const nav = target => page.locator(`.nav-menu li[data-target="${target}"]`).click();
const refresh = () => page.locator('#setup-refresh').click();
const eventually = async predicate => { for (let n = 0; n < 70; n++) { if (await predicate()) return; await page.waitForTimeout(50); } throw new Error('UI did not reach expected fixture state.'); };
try {
    await page.goto(`http://127.0.0.1:18896/#token=${fixtureToken}`);
    await check('SETUP-01-consent-and-no-implicit-download', async () => {
        await eventually(() => page.locator('#setup-metrics').textContent().then(text => text.includes('2.00 GB')));
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-consent').isChecked(), false);
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('동의 항목'));
        assert.equal(posts.length, 0);
        assert.equal(new URL(page.url()).hash, '');
    });
    await check('SETUP-02-low-ram-file-preparation', async () => {
        await page.locator('#setup-consent').check();
        assert.equal(await page.locator('#setup-start').isEnabled(), true);
        assert.equal(await page.locator('#setup-start').textContent(), '파일 먼저 준비');
        await page.locator('#setup-start').click();
        await eventually(() => page.locator('#setup-state-badge').textContent().then(text => text.includes('파일 준비 완료')));
        const request = posts.find(p => p.path === '/admin/api/setup/download');
        assert.equal(request.body.prepareOnly, true); assert.equal(request.body.consent, true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        assert.equal(await page.locator('#setup-consent').isChecked(), false);
        assert.equal(await page.locator('#setup-start').textContent(), '파일 준비 완료');
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-staged-actions').isVisible(), true);
        assert.equal(await page.locator('#setup-export').isVisible(), true);
        assert.equal(await page.locator('#setup-recheck').isVisible(), true);
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('대상 PC'));
        const count = posts.filter(p => p.path === '/admin/api/setup/download').length;
        await page.locator('#setup-start').evaluate(button => button.click());
        assert.equal(posts.filter(p => p.path === '/admin/api/setup/download').length, count);
        await page.locator('#setup-export').click();
        assert.equal(await page.locator('#panel-portable').isVisible(), true);
        await nav('dashboard');
    });
    await check('SETUP-08-prepared-to-explicit-verified-run', async () => {
        plan = { ...plan, compatible: true, runtimeCompatible: true, runtimeIssues: [] };
        await page.locator('#setup-recheck').click();
        await eventually(() => page.locator('#setup-start').textContent().then(text => text === '저장된 환경 구동 확인'));
        assert.equal(await page.locator('#setup-staged-actions').isVisible(), false);
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        await page.locator('#setup-consent').check(); await page.locator('#setup-start').click();
        await eventually(() => posts.filter(p => p.path === '/admin/api/setup/download').length === 2);
        assert.equal(posts.filter(p => p.path === '/admin/api/setup/download').at(-1).body.prepareOnly, false);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        operation = { ...operation, state: 'verified', phase: '파일 구성·기동·기능 연결 확인', functionalVerified: true, startupChecks: [{ stage: '번역 모델 첫 추론', passed: true, millis: 1500 }], checks: [{ stage: '실제 WAV 음성인식', language: 'ko', passed: true, millis: 1000 }, { stage: '대상 언어 음성합성', language: 'en', passed: true, millis: 1000 }] };
        engine = {}; await refresh();
        await eventually(() => page.locator('#setup-ready-actions').isVisible());
        assert.equal(await page.locator('#setup-consent').isChecked(), false);
        assert.ok((await page.locator('#setup-checks').textContent()).includes('번역 모델 첫 추론'));
        assert.ok((await page.locator('#setup-checks').textContent()).includes('실제 WAV 음성인식'));
    });
    await check('SETUP-03-recovery-keeps-files-and-rechecks-consent', async () => {
        plan = { ...plan, compatible: true, runtimeCompatible: true, runtimeIssues: [] };
        operation = { ...operation, state: 'failed', phase: '실제 구동 검증', error: 'fixture 음성 인식 첫 추론 시간 초과', checks: [{ stage: '엔진 기동·모델 로드', passed: false, millis: 180000 }] };
        await refresh();
        await eventually(() => page.locator('#setup-status').textContent().then(text => text.includes('fixture 음성 인식')));
        assert.equal(await page.locator('#setup-start').textContent(), '저장된 파일로 이어서 준비');
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('더 작은 모델'));
        await page.locator('#setup-consent').check(); await page.locator('#setup-start').click();
        await eventually(() => posts.filter(p => p.path === '/admin/api/setup/download').length === 3);
        assert.equal(posts.filter(p => p.path === '/admin/api/setup/download').at(-1).body.prepareOnly, false);
    });
    await check('SETUP-04-native-startup-stage-and-elapsed', async () => {
        operation = { ...operation, state: 'running', phase: '실제 구동 검증', currentID: '', checks: [] };
        engine = { startupStage: '음성 인식 모델 첫 추론', startupMillis: 42700, startupChecks: [{ stage: '음성 인식 모델 로드', passed: true, millis: 2000 }] };
        await refresh();
        await eventually(() => page.locator('#setup-engine-progress').textContent().then(text => text.includes('42초 경과')));
        assert.equal(await page.locator('#setup-engine-progress').isVisible(), true);
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-cancel').isEnabled(), true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        await page.locator('#setup-checks').locator('..').evaluate(element => element.open = true);
        assert.ok((await page.locator('#setup-checks').textContent()).includes('음성 인식 모델 로드'));
        engine = { ...engine, startupStage: '언어별 음성 모델 로드', voiceStartupPhase: 'VOICE_CHILD_SUPERTONIC_LOAD', voiceStartupMillis: 8300 };
        await refresh();
        await eventually(() => page.locator('#setup-engine-progress').textContent().then(text => text.includes('한국어·다국어 음성 준비') && text.includes('8초 경과')));
        assert.ok((await page.locator('#setup-engine-progress').textContent()).includes('42초 경과'));
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        engine = { ...engine, voiceStartupPhase: 'VOICE_CHILD_KOKORO_LOAD', voiceStartupMillis: 3400 };
        await refresh();
        await eventually(() => page.locator('#setup-engine-progress').textContent().then(text => text.includes('중국어 음성 준비') && text.includes('3초 경과')));
        assert.equal((await page.locator('#setup-engine-progress').textContent()).includes('한국어·다국어 음성 준비'), false);
        const unknownPhase = '<img id="voice-phase-xss" src=x onerror="window.__unsafe=1">/private/voice-fixture';
        engine = { ...engine, voiceStartupPhase: unknownPhase, voiceStartupMillis: 12345 };
        await refresh();
        await eventually(() => page.locator('#setup-engine-progress').textContent().then(text => !text.includes('세부 작업')));
        const fallback = await page.locator('#setup-engine-progress').textContent();
        assert.ok(fallback.includes('언어별 음성 모델 로드') && fallback.includes('42초 경과'));
        assert.equal(fallback.includes(unknownPhase) || fallback.includes('/private/voice-fixture') || fallback.includes('12초 경과'), false);
        assert.equal(await page.locator('#voice-phase-xss').count(), 0);
        assert.equal(await page.evaluate(() => window.__unsafe), undefined);
        engine = { ...engine, voiceStartupPhase: undefined, voiceStartupMillis: undefined };
        await refresh();
        assert.equal((await page.locator('#setup-engine-progress').textContent()).includes('세부 작업'), false);
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
    });
    await check('SETUP-09-warmup-cancellation-endpoint-and-recovery', async () => {
        const count = posts.filter(p => p.path === '/admin/api/setup/cancel').length;
        await page.locator('#setup-cancel').click();
        await eventually(() => page.locator('#setup-status').textContent().then(text => text.includes('준비 중단됨')));
        assert.equal(posts.filter(p => p.path === '/admin/api/setup/cancel').length, count + 1);
        assert.equal(await page.locator('#setup-engine-progress').isVisible(), false);
        assert.equal(await page.locator('#setup-cancel').isDisabled(), true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        assert.equal(await page.locator('#setup-consent').isChecked(), false);
        assert.equal(await page.locator('#setup-start').textContent(), '저장된 파일로 이어서 준비');
    });
    await check('SETUP-05-file-download-progress', async () => {
        operation = { ...operation, state: 'running', phase: '모델·추론 엔진 다운로드·구성', currentID: model.id };
        engine = {}; progress = [{ id: model.id, state: 'downloading', received: 500000000, total: 2000000000 }];
        await refresh();
        await eventually(() => page.locator('#setup-progress-value').textContent().then(text => text.includes('25%')));
        assert.equal(await page.locator('#setup-progress').getAttribute('value'), '25');
        assert.equal(await page.locator('#setup-engine-progress').isVisible(), false);
    });
    await check('SETUP-06-current-verification-only', async () => {
        operation = { ...operation, state: 'verified', phase: '파일 구성·기동·기능 연결 확인', functionalVerified: true, environmentFingerprint: plan.environmentFingerprint, checks: [{ stage: '시험 문장 번역', language: 'en', passed: true, millis: 1000 }] };
        await refresh();
        await eventually(() => page.locator('#setup-ready-actions').isVisible());
        plan = { ...plan, environmentFingerprint: 'changed-environment' };
        await refresh();
        await eventually(async () => !(await page.locator('#setup-ready-actions').isVisible()));
        assert.ok((await page.locator('#setup-status').textContent()).includes('이전 환경'));
    });
    await check('DEPENDENCY-01-unresolved-restart-installation-and-fresh-check', async () => {
        const dependency = { profile: { id: 'vc-fixture', name: 'Microsoft VC fixture', version: 'fixture', bytes: 1000000, termsURL: 'https://visualstudio.microsoft.com/license-terms/' }, installerCached: true, cached: true, status: { ready: false, supported: true, checkComplete: true, installationInProgress: false } };
        operation = { ...operation, state: 'failed', functionalVerified: false, restartRequired: true, error: 'fixture 재부팅 필요' };
        plan = { ...plan, fingerprint: 'request-restart', compatible: true, preparationCompatible: true, runtimeCompatible: true, systemDependencies: [dependency] };
        await refresh();
        await eventually(() => page.locator('#setup-start').textContent().then(text => text === 'Windows 설치 확인 필요'));
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-consent').isDisabled(), true);
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('재부팅'));
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        plan = { ...plan, fingerprint: 'request-installation', compatible: false, preparationCompatible: true, runtimeCompatible: false, systemDependencies: [{ ...dependency, status: { ...dependency.status, installationInProgress: true } }] };
        operation = { ...operation, restartRequired: false, systemInstallationMayContinue: true, restartCheckRequired: true };
        await refresh();
        await eventually(() => page.locator('#setup-action-hint').textContent().then(text => text.includes('설치가 진행 중')));
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-start').textContent(), '파일 준비 완료');
        assert.equal(await page.locator('#setup-staged-actions').isVisible(), true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        const engineStarts = posts.filter(p => p.path === '/admin/api/engine/start').length;
        await page.locator('#btn-engine-toggle').click();
        assert.equal(posts.filter(p => p.path === '/admin/api/engine/start').length, engineStarts);
        plan = { ...plan, fingerprint: 'request-rechecked-ready', compatible: true, preparationCompatible: true, runtimeCompatible: true, systemDependencies: [{ ...dependency, status: { ...dependency.status, ready: true, installationInProgress: false } }] };
        await refresh();
        await eventually(() => page.locator('#setup-consent').isEnabled());
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('현재 준비 상태'));
        await page.locator('#setup-consent').check();
        assert.equal(await page.locator('#setup-start').isEnabled(), true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        plan = { ...plan, systemDependencies: [] };
    });
    await check('DEPENDENCY-02-native-check-unknown-cache-only-terminal', async () => {
        const dependency = { profile: { id: 'vc-fixture', name: 'Microsoft VC fixture', version: 'fixture', bytes: 1000000, termsURL: 'https://visualstudio.microsoft.com/license-terms/' }, installerCached: false, cached: false, status: { ready: false, supported: false, checkComplete: false, installationInProgress: false, error: 'fixture native 확인 시간 초과' } };
        operation = {};
        plan = { ...plan, fingerprint: 'request-native-unknown', compatible: false, preparationCompatible: true, runtimeCompatible: false, systemDependencies: [dependency], runtimeIssues: ['Windows 실행 기반 확인 미완료 fixture'] };
        await refresh();
        await eventually(() => page.locator('#setup-start').textContent().then(text => text === '파일 먼저 준비'));
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('동의 항목'));
        await page.locator('#setup-consent').check(); await page.locator('#setup-start').click();
        await eventually(() => page.locator('#setup-start').textContent().then(text => text === '파일 준비 완료'));
        assert.equal(posts.filter(p => p.path === '/admin/api/setup/download').at(-1).body.prepareOnly, true);
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-staged-actions').isVisible(), true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('실제 설치'));
        plan = { ...plan, compatible: true, preparationCompatible: true, runtimeCompatible: true, systemDependencies: [], runtimeIssues: [] };
    });
    await check('MODEL-01-select-before-download', async () => {
        operation = {}; engine = {}; progress = [];
        await refresh(); await nav('models');
        const card = page.locator('#models-list .card').filter({ has: page.getByRole('heading', { name: smallSTT.name, exact: true }) });
        await card.getByRole('button', { name: '이 모델로 환경 준비', exact: true }).click();
        await eventually(() => posts.some(p => p.path === '/admin/api/config' && p.body.sttModel === smallSTT.id));
        assert.equal(posts.some(p => p.path === '/admin/api/models/download'), false);
        assert.equal(config.online.apiKey, '');
    });
    await check('MODEL-02-explicit-cpu-starter-changes-only-STT', async () => {
        config = { ...config, backend: 'cpu', sttModel: 'whisper-turbo', sourceLanguage: 'ja', targetLanguages: ['ko', 'en', 'ja'], maxListeners: 73 };
        const before = structuredClone(config), saves = posts.filter(p => p.path === '/admin/api/config').length;
        await page.reload();
        await eventually(() => page.locator('#setup-cpu-model').isVisible());
        assert.equal(posts.filter(p => p.path === '/admin/api/config').length, saves);
        await page.locator('#setup-consent').check();
        await page.locator('#setup-cpu-model').click();
        await eventually(() => posts.filter(p => p.path === '/admin/api/config').length === saves + 1);
        const saved = posts.filter(p => p.path === '/admin/api/config').at(-1).body;
        assert.equal(saved.sttModel, cpuStarter.id);
        assert.deepEqual({ ...saved, sttModel: before.sttModel }, before);
        await eventually(() => page.locator('#setup-model-note').textContent().then(text => text.includes('Whisper Small (Q5)')));
        assert.equal(await page.locator('#setup-consent').isChecked(), false);
        assert.equal(await page.locator('#setup-cpu-model').isVisible(), false);
        assert.equal(posts.some(p => p.path === '/admin/api/models/download'), false);
        await nav('models');
        const manual = page.locator('#models-list .card').filter({ has: page.getByRole('heading', { name: smallSTT.name, exact: true }) });
        await eventually(() => manual.getByRole('button', { name: '이 모델로 환경 준비', exact: true }).isEnabled());
    });
    await check('MODEL-03-preset-preserves-GPU-and-custom-choice', async () => {
        config = { ...config, backend: 'cuda', sttModel: 'whisper-turbo-q5' };
        const saves = posts.filter(p => p.path === '/admin/api/config').length;
        await page.reload();
        await eventually(() => page.locator('#connection-status').textContent().then(text => text.includes('연결됨')));
        assert.equal(await page.locator('#setup-cpu-model').isVisible(), false);
        assert.equal(posts.filter(p => p.path === '/admin/api/config').length, saves);
        await nav('models');
        const manual = page.locator('#models-list .card').filter({ has: page.getByRole('heading', { name: cpuStarter.name, exact: true }) });
        await manual.getByRole('button', { name: '이 모델로 환경 준비', exact: true }).click();
        await eventually(() => posts.filter(p => p.path === '/admin/api/config').length === saves + 1);
        assert.equal(posts.filter(p => p.path === '/admin/api/config').at(-1).body.backend, 'cuda');
        assert.equal(config.sttModel, cpuStarter.id);
        config = { ...config, backend: 'cpu', sttModel: smallSTT.id };
        const customSaves = posts.filter(p => p.path === '/admin/api/config').length;
        await page.reload();
        await eventually(() => page.locator('#connection-status').textContent().then(text => text.includes('연결됨')));
        assert.equal(await page.locator('#setup-cpu-model').isVisible(), false);
        assert.equal(posts.filter(p => p.path === '/admin/api/config').length, customSaves);
        assert.equal(config.sttModel, smallSTT.id);
    });
    await check('MODEL-04-autoresume-startup-blocks-model-configuration', async () => {
        config = { ...config, backend: 'cpu', sttModel: 'whisper-turbo' };
        statusEngine = { translationReady: false, sttReady: false, ttsReady: false, startupStage: '번역 모델 해시 확인', startupMillis: 1200 };
        const saves = posts.filter(p => p.path === '/admin/api/config').length;
        await page.reload();
        await eventually(() => page.locator('#setup-cpu-model').isVisible());
        assert.equal(await page.locator('#setup-cpu-model').isDisabled(), true);
        assert.equal(await page.locator('#engine-status').evaluate(element => element.classList.contains('ready')), false);
        assert.ok((await page.locator('#engine-status').textContent()).includes('모델 준비 중'));
        await page.locator('#setup-cpu-model').evaluate(button => button.click());
        assert.equal(posts.filter(p => p.path === '/admin/api/config').length, saves);
        await nav('models');
        const manual = page.locator('#models-list .card').filter({ has: page.getByRole('heading', { name: smallSTT.name, exact: true }) });
        assert.equal(await manual.getByRole('button', { name: '이 모델로 환경 준비', exact: true }).isDisabled(), true);
        assert.equal(posts.filter(p => p.path === '/admin/api/config').length, saves);
        statusEngine = { translationReady: false, sttReady: false, ttsReady: true };
        await page.reload();
        await eventually(() => page.locator('#setup-cpu-model').isVisible());
        assert.equal(await page.locator('#setup-cpu-model').isDisabled(), true);
        assert.equal(await page.locator('#engine-status').evaluate(element => element.classList.contains('ready')), false);
        statusEngine = {};
        await page.reload();
        await eventually(() => page.locator('#setup-cpu-model').isEnabled());
        assert.equal(posts.filter(p => p.path === '/admin/api/config').length, saves);
    });
    await check('ROOM-01-search-empty-state-and-safe-dom', async () => {
        const canary = '<img src=x onerror="window.__unsafe=1">';
        rooms = [...rooms, { ...rooms[0], id: 'room-canary', title: canary }];
        await nav('rooms');
        await eventually(() => page.locator('#rooms-list').textContent().then(text => text.includes(canary)));
        assert.equal(await page.locator('#rooms-list img').count(), 0);
        assert.equal(await page.evaluate(() => window.__unsafe), undefined);
        await page.locator('#rooms-search').fill('세계');
        assert.equal(await page.locator('#rooms-list .card:visible').count(), 1);
        await page.locator('#rooms-search').fill('없는 수업');
        assert.equal(await page.locator('#rooms-list .card:visible').count(), 0);
        assert.ok((await page.locator('#rooms-count').textContent()).includes('없습니다'));
    });
    await check('SETUP-10-optional-empty-artifact-lists-no-crash', async () => {
        await nav('dashboard'); const savedPlan = plan;
        plan = { ...plan, fingerprint: 'request-null-lists', artifacts: null, missing: null };
        await refresh();
        await eventually(() => page.locator('#setup-summary').textContent().then(text => text.includes('0개 항목')));
        assert.deepEqual(errors, []);
        plan = savedPlan; await refresh();
    });
    await check('SETUP-07-plan-connection-failure-recovery', async () => {
        await nav('dashboard'); failPlan = true; await refresh();
        await eventually(() => page.locator('#setup-summary').textContent().then(text => text.includes('fixture 연결 오류')));
        assert.equal(await page.locator('#setup-start').isDisabled(), true);
        assert.equal(await page.locator('#setup-ready-actions').isVisible(), false);
        assert.ok((await page.locator('#setup-action-hint').textContent()).includes('PC 다시 확인'));
        failPlan = false; await refresh();
        await eventually(() => page.locator('#setup-metrics').textContent().then(text => text.includes('12 GB')));
    });
    if (output) await page.screenshot({ path: join(output, 'setup-desktop.png'), fullPage: true });
    await check('NAV-01-keyboard-current-and-mobile-fit', async () => {
        await page.locator('.nav-menu li[data-target="rooms"]').focus(); await page.keyboard.press('Enter');
        assert.equal(await page.locator('.nav-menu li[data-target="rooms"]').getAttribute('aria-current'), 'page');
        assert.equal(await page.locator('#panel-rooms').isVisible(), true);
        await page.setViewportSize({ width: 390, height: 844 });
        await page.locator('#nav-toggle').click(); await nav('dashboard');
        assert.equal(await page.locator('.sidebar').isVisible(), false);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth && document.querySelector('.main-content').scrollWidth <= document.querySelector('.main-content').clientWidth), true);
    });
    if (output) await page.screenshot({ path: join(output, 'setup-mobile390.png'), fullPage: true });
    await check('SECURITY-01-bearer-and-unhandled-js', async () => { assert.deepEqual(unauthorized, []); assert.deepEqual(errors, []); });
} finally {
    await browser.close();
}
const receipt = { scope: 'Chrome route-fixture UI acceptance only; no Windows/model/service performance claim', sourceHashes, results, passed: results.every(result => result.passed) };
if (output) await writeFile(join(output, 'setup-ui-receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
console.log(JSON.stringify(receipt, null, 2));
if (!receipt.passed) process.exitCode = 1;
