document.addEventListener('DOMContentLoaded', () => {
    const fragment = new URLSearchParams(window.location.hash.slice(1));
    const launchToken = fragment.get('token') || fragment.get('admin');
    if (launchToken) {
        sessionStorage.setItem('adminToken', launchToken);
        window.history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    const token = sessionStorage.getItem('adminToken');
    if (!token) { document.body.textContent = '관리자 인증이 필요합니다. PC 프로그램이 연 관리 화면에서 다시 시작하세요.'; return; }

    const headers = { 'Authorization': `Bearer ${token}` };
    async function boundedAdminFetch(url, options, readResponse, timeout = 30000) {
        const controller = new AbortController();
        const upstream = options.signal;
        const relayAbort = () => controller.abort();
        let timedOut = false;
        if (upstream?.aborted) controller.abort();
        else upstream?.addEventListener('abort', relayAbort, { once: true });
        const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeout);
        try {
            const response = await fetch(url, { ...options, cache: 'no-store', signal: controller.signal });
            // Keep the deadline and parent cancellation active through body consumption.
            return await readResponse(response);
        } catch (error) {
            if (timedOut) throw new Error('서버 응답 대기 시간이 지났습니다. 접수 여부를 확인한 뒤 직접 다시 시도하세요.');
            if (controller.signal.aborted) throw new Error('요청이 취소되었습니다. 자료 접수 여부를 확인하세요.');
            throw error;
        } finally {
            clearTimeout(timer);
            upstream?.removeEventListener('abort', relayAbort);
        }
    }
    async function api(path, options = {}) {
        options.headers = { ...headers, ...(options.headers || {}) };
        if (!(options.body instanceof FormData) && !options.headers['Content-Type']) {
            options.headers['Content-Type'] = 'application/json';
        }
        return boundedAdminFetch(`/admin/api${path}`, options, async res => {
            const ct = res.headers.get('content-type') || '';
            const data = ct.includes('application/json') ? await res.json() : await res.text();
            if (!res.ok) throw new Error(typeof data === 'string' ? data : (data.error || '요청 처리 실패'));
            return data;
        }, path === '/engine/start' ? 130000 : 30000);
    }
    async function authFetchBlob(url) {
        if (!url.startsWith('/admin/api/')) throw new Error('허용되지 않은 자료 주소입니다.');
        return boundedAdminFetch(url, { headers }, async res => {
            if (!res.ok) throw new Error('자료 내려받기 실패');
            return await res.blob();
        });
    }

    // Navigation
    document.querySelectorAll('.nav-menu li').forEach(li => {
        li.setAttribute('tabindex', '0');
        li.setAttribute('role', 'button');
        const activate = () => {
            document.querySelectorAll('.nav-menu li').forEach(el => el.classList.remove('active'));
            li.classList.add('active');
            document.querySelectorAll('.panel').forEach(p => {
                const selected = p.id === `panel-${li.dataset.target}`;
                p.classList.toggle('active', selected);
                p.classList.toggle('hidden', !selected);
            });
            if (li.dataset.target === 'rooms') fetchRooms();
            if (li.dataset.target === 'models') fetchDiagnostics();
            if (li.dataset.target === 'settings') fetchConfig();
            if (li.dataset.target === 'portable') fetchEnvironment();
            if (li.dataset.target === 'history') fetchHistory();
            if (li.dataset.target === 'glossary') fetchGlossary();
            if (li.dataset.target === 'scripts') fetchScripts();
            if (li.dataset.target === 'lessons') fetchLessons();
            if (li.dataset.target === 'notes') { updateActiveSessionUI(); fetchNotesLines(); }
            if (li.dataset.target === 'files') fetchJobs();
            document.querySelector('.sidebar').classList.remove('open');
            document.getElementById('nav-toggle').setAttribute('aria-expanded', 'false');
        };
        li.addEventListener('click', activate);
        li.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(); } });
    });

    const els = {
        engineStatus: document.getElementById('engine-status'),
        queueDepth: document.getElementById('queue-depth'),
        btnToggle: document.getElementById('btn-engine-toggle'),
        roomsList: document.getElementById('rooms-list'),
        modelsList: document.getElementById('models-list'),
        diagInfo: document.getElementById('diagnostic-info'),
        diagSummary: document.getElementById('diagnostic-summary'),
    };

    let engineReady = false;
    let engineRunning = false;
    let fullConfig = null;
    let activeSession = null;
    let publicURL = '';
    let statusPending = false;
    let capture = null;
    let captureGeneration = 0;
    const noteDrafts = new Map();
    let displayedSessionId = '';
    let notesSequence = 0;
    const notesCache = new Map();
    let notesPending = false;
    let textSubmitting = false, sessionStopping = false, diagnosticPending = false;
    let pinStatus = { listenerSet: false, speakerSet: false };
    function rememberConfig(config, flags) {
        // Older server replies may include PINs; retain presence only, never the PIN.
        pinStatus = flags || { listenerSet: Boolean(config?.listenerPin), speakerSet: Boolean(config?.speakerPin) };
        fullConfig = config ? { ...config, listenerPin: '', speakerPin: '', online: { ...config.online, apiKey: '' } } : null;
    }

    let setupPlan = null;
    let setupRunning = false;
    let setupQueryRunning = false;
    let setupLastState = '';
    const setupStart = document.getElementById('setup-start');
    const setupConsent = document.getElementById('setup-consent');
    const setupStatus = document.getElementById('setup-status');
    const setupCancel = document.getElementById('setup-cancel');
    function setupSourceLink(artifact) {
        try {
            const u = new URL(artifact.url);
            if (u.protocol !== 'https:' || !['huggingface.co', 'github.com'].includes(u.hostname)) return null;
            const parts = u.pathname.split('/').filter(Boolean);
            if (parts.length < 2) return null;
            return `${u.origin}/${parts.slice(0, 2).join('/')}`;
        } catch { return null; }
    }
    function updateSetupStart() {
        setupStart.disabled = setupRunning || !setupPlan?.compatible || !setupConsent.checked;
    }
    async function fetchSetupPlan() {
        try {
            const newPlan = await api('/setup/plan');
            if (setupPlan && setupPlan.fingerprint !== newPlan.fingerprint) setupConsent.checked = false;
            setupPlan = newPlan;
            const gigabytes = n => (n / 1000000000).toFixed(2);
            const systemDependencies = setupPlan.systemDependencies || [];
            const pendingDependencies = systemDependencies.filter(d => !d.status.ready);
            document.getElementById('setup-summary').textContent = `구성할 항목 ${setupPlan.missing.length + pendingDependencies.length}개 · 다운로드 ${gigabytes(setupPlan.downloadBytes)} GB · 설치 여유 공간 ${gigabytes(setupPlan.requiredDiskBytes)} GB · 여유 RAM ${setupPlan.requiredRAMGB} GB 필요. 이미 설치한 파일은 재사용합니다.`;
            const speech = setupPlan.speechLanguages;
            if (speech?.supported) {
                const voiceState = speech.error ? '언어별 음성 점검 미완료' : speech.missing.length ? `로컬 음성 모델 준비 필요: ${speech.missing.join(', ')}` : '선택 언어의 음성 모델 파일 확인';
                document.getElementById('setup-summary').textContent += ` ${voiceState}.`;
            }
            const list = document.getElementById('setup-assets'); list.replaceChildren();
            const missing = new Set(setupPlan.missing.map(a => a.id));
            setupPlan.artifacts.forEach(a => {
                const item = document.createElement('li');
                item.append(document.createTextNode(`${a.name} · ${gigabytes(a.bytes)} GB · ${a.license || '이용조건 확인 필요'} · ${missing.has(a.id) ? '받을 파일' : '저장된 파일'} `));
                const source = setupSourceLink(a);
                if (source) { const link = document.createElement('a'); link.href = source; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = '원본·이용조건'; item.append(link); }
                const notice = { 'tts-supertonic3': '/licenses/supertonic-3.txt', 'tts-kokoro-zh': '/licenses/tts-notices.txt', 'runtime-sherpa-tts': '/licenses/tts-notices.txt' }[a.id];
                if (notice) { const link = document.createElement('a'); link.href = notice; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = ' 포함 이용조건'; item.append(link); }
                list.append(item);
            });
            systemDependencies.forEach(d => {
                const item = document.createElement('li');
                item.append(document.createTextNode(`${d.profile.name} ${d.profile.version} · ${(d.profile.bytes / 1000000).toFixed(1)} MB · ${d.status.ready ? 'Windows 준비됨' : d.cached ? '저장된 설치 파일로 구성' : '다운로드 후 Windows 설치'} · 관리자 승인이 필요할 수 있습니다. `));
                const terms = new URL(d.profile.termsURL);
                if (terms.protocol === 'https:' && terms.hostname === 'visualstudio.microsoft.com') {
                    const link = document.createElement('a'); link.href = terms.href; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = 'Microsoft 이용조건'; item.append(link);
                }
                list.append(item);
            });
            document.getElementById('setup-warnings').textContent = (setupPlan.warnings || []).join(' ');
            setupStart.textContent = setupPlan.missing.length || pendingDependencies.length ? '필요 환경 받기·구성·구동 검증' : '저장된 환경 구동 검증';
        } catch (e) { setupPlan = null; setupConsent.checked = false; document.getElementById('setup-summary').textContent = e.message; }
        updateSetupStart();
    }
    async function fetchSetupStatus() {
        if (setupQueryRunning) return;
        setupQueryRunning = true;
        try {
            const data = await api('/setup/status');
            const op = data.operation || {};
            setupRunning = op.state === 'running';
            els.btnToggle.disabled = setupRunning;
            setupCancel.disabled = !setupRunning;
            setupConsent.disabled = setupRunning;
            const names = { running: '진행 중', verified: '기동·기능 연결 확인', failed: '구성 또는 구동 검증 실패', cancelled: '취소됨', interrupted: '중단됨' };
            const progress = (data.progress || []).find(p => p.id === op.currentID);
            const previousEnvironment = op.functionalVerified && setupPlan && (op.environmentFingerprint !== setupPlan.environmentFingerprint || setupPlan.missing.length > 0 || (setupPlan.systemDependencies || []).some(d => !d.status.ready) || (setupPlan.speechLanguages?.supported && (setupPlan.speechLanguages.error || setupPlan.speechLanguages.missing.length > 0)));
            setupStatus.textContent = op.id ? `${previousEnvironment ? '이전 환경의 시험 기록 · 현재 구성을 다시 확인하세요' : names[op.state] || op.state} · ${op.phase} · 설치 ${op.completed}/${op.artifactIDs.length}${op.currentID ? ` · ${op.currentID}` : ''}${progress ? ` · ${progress.received.toLocaleString()} / ${progress.total.toLocaleString()} 바이트` : ''}${op.error ? ` · ${op.error}` : ''}${op.functionalVerified ? ' · 마지막 시험에서 실제 엔진·PCM 생성을 확인했습니다. 정확도·7초 지연·강의 수용량 검증은 별도입니다.' : ''}` : '직접 선택하기 전에는 모델을 다운로드하지 않습니다. 엔진·세션·접속을 종료한 상태에서 구성하세요.';
            if (progress?.state === 'awaiting-approval') setupStatus.textContent += ' · Windows 관리자 승인 창에서 Microsoft 설치를 확인하세요.';
            if (op.restartRequired) setupStatus.textContent += ' · PC 재부팅 후 다시 검증하세요.';
            if (op.systemInstallationMayContinue || op.restartCheckRequired) setupStatus.textContent += ' · Microsoft 설치가 계속 진행될 수 있습니다. 설치 완료·재부팅 상태를 확인하고 다시 검증하세요.';
            const checks = document.getElementById('setup-checks'); checks.replaceChildren();
            (op.checks || []).forEach(c => { const li = document.createElement('li'); li.textContent = `${c.passed ? '확인' : '실패'} · ${c.stage} · ${c.language || ''} · ${c.millis} ms${c.error ? ` · ${c.error}` : ''}`; checks.append(li); });
            if (setupLastState !== op.state) { setupLastState = op.state; if (op.state && op.state !== 'running') { setupConsent.checked = false; await fetchSetupPlan(); } }
            updateSetupStart();
        } catch (e) { setupStatus.textContent = e.message; }
        finally { setupQueryRunning = false; }
    }
    setupConsent.addEventListener('change', updateSetupStart);
    setupStart.addEventListener('click', async () => {
        if (!setupPlan || setupStart.disabled) return;
        setupStart.disabled = true;
        try {
            await api('/setup/download', { method: 'POST', body: JSON.stringify({ fingerprint: setupPlan.fingerprint, consent: setupConsent.checked }) });
            await fetchSetupStatus();
        } catch (e) { setupStatus.textContent = e.message; await fetchSetupPlan(); }
    });
    setupCancel.addEventListener('click', async () => { try { const result = await api('/setup/cancel', { method: 'POST' }); setupStatus.textContent = result.state === 'cancel_requested' ? '취소 요청됨. 설치 완료 파일은 보존합니다.' : '이미 종료된 구성 작업입니다.'; await fetchSetupStatus(); } catch (e) { setupStatus.textContent = e.message; } });
    document.getElementById('setup-refresh').addEventListener('click', async () => { await fetchDiagnostics(); await fetchSetupPlan(); await fetchSetupStatus(); });
    document.getElementById('setup-offline').addEventListener('click', () => document.querySelector('.nav-menu li[data-target="portable"]').click());
    setInterval(fetchSetupStatus, 1500);
    fetchSetupPlan(); fetchSetupStatus();

    let inspectedPath = '';
    let inspectedFingerprint = '';
    const envStatus = document.getElementById('environment-status');
    const envResult = document.getElementById('environment-result');
    const envApply = document.getElementById('environment-apply');
    const envCancel = document.getElementById('environment-cancel');
    const envSource = document.getElementById('environment-source');
    envSource.addEventListener('input', () => { inspectedPath = ''; inspectedFingerprint = ''; envApply.disabled = true; });
    async function fetchEnvironment() {
        try {
            const data = await api('/environment/status');
            const op = data.operation || {};
            const names = { running: '진행 중', completed: '완료', failed: '실패', cancelled: '취소됨', interrupted: '중단됨' };
            envStatus.textContent = op.id ? `${names[op.state] || op.state} · ${op.phase || op.kind} · ${op.currentFile || ''}${op.totalBytes ? ` · 완료 파일 ${op.completedBytes.toLocaleString()} / ${op.totalBytes.toLocaleString()} 바이트` : ''}${op.error ? ` · ${op.error}` : ''}` : '환경 이전 작업을 선택하세요.';
            envCancel.disabled = op.state !== 'running';
            if (op.state === 'completed' && op.kind === 'inspect' && op.path === envSource.value.trim() && op.result?.compatible) {
                inspectedPath = op.path;
                inspectedFingerprint = op.result.fingerprint;
                envApply.disabled = false;
            } else envApply.disabled = true;
            if (op.result && op.state === 'completed') {
                const result = op.result;
                if (op.kind === 'inspect') {
                    const m = result.manifest;
                    envResult.textContent = `${m.appVersion} · ${m.targetOS}/${m.targetArch}\n패키지 매니페스트 SHA-256: ${result.fingerprint}\n필요 여유 공간 ${(result.requiredDiskBytes / 1073741824).toFixed(2)} GB\n${m.assets.map(a => `${a.profile.name} · ${a.profile.license} · ${a.profile.source}`).join('\n')}\n\n${m.files.slice(0, 12).map(f => `${f.path}\nSHA-256 ${f.sha256}`).join('\n')}${m.files.length > 12 ? `\n전체 ${m.files.length}개 중 12개 표시` : ''}\n\n${(result.warnings || []).join('\n')}\n파일 해시 확인 완료. 실제 모델 실행·지연·품질은 대상 PC에서 확인하세요.`;
                } else envResult.textContent = `${op.path}\n${(result.bytes || 0).toLocaleString()} 바이트 · CPU 대체 엔진 ${result.offlineComplete ? '포함' : '미완비'}\n${(result.warnings || []).join('\n')}${op.restartRequired ? '\n프로그램을 다시 실행하고 PC 진단 → 엔진 시작 → 통번역 성능 시험을 진행하세요.' : ''}`;
            }
        } catch (e) { envStatus.textContent = e.message; }
    }
    async function startEnvironment(kind, path, options = {}, fingerprint = '') {
        inspectedPath = ''; envApply.disabled = true; envResult.textContent = '';
        try {
            const op = await api(`/environment/${kind}`, { method: 'POST', body: JSON.stringify({ path, options, fingerprint }) });
            envStatus.textContent = `작업 접수 · ${op.id}`;
            await fetchEnvironment();
        } catch (e) { envStatus.textContent = e.message; }
    }
    document.getElementById('environment-export').addEventListener('submit', e => {
        e.preventDefault();
        startEnvironment('export', document.getElementById('environment-destination').value.trim(), {
            format: document.getElementById('environment-format').value,
            includeEXE: document.getElementById('environment-exe').checked,
            includeKnowledge: document.getElementById('environment-knowledge').checked
        });
    });
    document.getElementById('environment-import').addEventListener('submit', e => { e.preventDefault(); startEnvironment('inspect', envSource.value.trim()); });
    envApply.addEventListener('click', () => { if (inspectedPath && inspectedPath === envSource.value.trim()) startEnvironment('import', inspectedPath, {}, inspectedFingerprint); });
    envCancel.addEventListener('click', async () => { try { await api('/environment/cancel', { method: 'POST' }); envStatus.textContent = '취소 요청됨. 서버 정리 결과를 확인 중입니다.'; } catch (e) { envStatus.textContent = e.message; } });
    document.getElementById('environment-refresh').addEventListener('click', fetchEnvironment);
    setInterval(() => { if (document.getElementById('panel-portable').classList.contains('active')) fetchEnvironment(); }, 1500);

    async function fetchStatus() {
        if (statusPending) return;
        statusPending = true;
        try {
            const data = await api('/status');
            rememberConfig(data.config, data.pinStatus);
            engineReady = data.engine && data.engine.translationReady && data.engine.sttReady && data.engine.ttsReady;
            engineRunning = data.engine && (data.engine.translationReady || data.engine.sttReady);

            let statusText = '중지됨';
            els.engineStatus.className = 'status-badge';
            if (engineReady) {
                statusText = '기능 준비됨';
                els.engineStatus.classList.add('ready');
            } else if (data.engine && data.engine.error) {
                statusText = `엔진 안내: ${data.engine.error}`;
                els.engineStatus.classList.add('error');
            } else if (engineRunning) {
                statusText = '일부 기능만 준비됨';
                els.engineStatus.classList.add('error');
            }

            els.engineStatus.textContent = `엔진: ${statusText}`;
            els.queueDepth.textContent = `대기 작업: ${data.queueDepth}`;
            els.btnToggle.textContent = engineRunning ? '엔진 중지' : '엔진 시작';

            const nextSession = data.activeSession;
            if (capture && nextSession?.id !== capture.sessionId) stopMic('작업이 변경되어 마이크를 종료했습니다. 새 작업에서 직접 켜세요.');
            activeSession = nextSession;
            publicURL = data.publicURL || '';
            document.getElementById('connection-status').textContent = data.lastError ? `마지막 처리 안내: ${data.lastError}` : 'PC 서버 연결됨';
            if (document.getElementById('panel-notes').classList.contains('active')) {
                updateActiveSessionUI();
            }
        } catch (e) { document.getElementById('connection-status').textContent = `서버 연결 확인 필요: ${e.message}`; if (capture) stopMic('서버 연결이 끊겨 마이크를 종료했습니다. 재연결 후 직접 켜세요.'); }
        finally { statusPending = false; }
    }

    els.btnToggle.addEventListener('click', async () => {
        try {
            els.btnToggle.disabled = true;
            if (engineRunning) await stopMic();
            await api(engineRunning ? '/engine/stop' : '/engine/start', { method: 'POST' });
            await fetchStatus();
        } catch (e) { alert(e.message); }
        finally { els.btnToggle.disabled = setupRunning; }
    });

    function renderDiagnostic(d) {
        const gb = value => Number.isFinite(Number(value)) ? Number(value).toFixed(1) + 'GB' : '미확인';
        const summary = `운영체제 ${d.os}/${d.arch} · CPU ${d.cpu || '미확인'} (${d.cores || 0}코어) · RAM ${gb(d.ramGB)} / 사용 가능 ${gb(d.availableRamGB)} · 디스크 여유 ${gb(d.freeDiskGB)}`;
        els.diagSummary.textContent = summary + ' · ' + (d.measured ? '자원 진단값 확인' : '자원값 미측정') + ' · 모델 실행·품질·실시간 지연은 별도 시험합니다.';
        els.diagInfo.replaceChildren();
        const text = (tag, value, className = '') => { const el = document.createElement(tag); el.textContent = value; if (className) el.className = className; return el; };
        const overview = text('p', summary); overview.dataset.testid = 'diagnostic-resources'; els.diagInfo.append(overview);
        els.diagInfo.append(text('p', (d.measured ? 'PC 자원 진단을 수행했습니다.' : '실제 PC 자원 측정이 확인되지 않았습니다.') + ' 모바일급 후보 사양: ' + (d.mobileEquivalent ? '진단 조건 충족' : '진단 조건 미충족') + '. 장치 감지·파일 준비·엔진 구동·통역 품질/지연은 각각 확인해야 합니다.', 'card-meta'));
        const devices = document.createElement('ul'); devices.dataset.testid = 'diagnostic-devices';
        for (const device of d.devices || []) devices.append(text('li', `${device.kind === 'npu' ? 'NPU' : device.kind === 'gpu' ? 'GPU' : device.kind || '장치'} · ${device.name} · ${device.vendor || '제조사 미확인'} · ${Number(device.vramGB) > 0 ? 'VRAM ' + gb(device.vramGB) : 'VRAM 용량 미확인'} · ${device.verified ? '장치 검증 표기 있음' : '감지 정보·구동 미검증'} · 선택 모델의 실행·속도는 별도 시험`));
        if (!devices.children.length) devices.append(text('li', '가속 장치 목록을 확인하지 못했습니다. 장치가 없다고 단정하지 말고 아래 진단 경고와 드라이버를 확인하세요.'));
        els.diagInfo.append(text('h3', 'GPU · NPU 감지'), devices);
        const warnings = document.createElement('ul'); warnings.dataset.testid = 'diagnostic-warnings';
        for (const warning of d.warnings || []) warnings.append(text('li', warning, 'err-text'));
        if (warnings.children.length) els.diagInfo.append(text('h3', '진단 안내'), warnings);
        const recommendations = document.createElement('ul'); recommendations.dataset.testid = 'diagnostic-recommendations';
        for (const rec of d.recommendations || []) recommendations.append(text('li', recommendationText(rec)));
        if (!recommendations.children.length) recommendations.append(text('li', '현재 진단에서 모델 후보를 제시하지 못했습니다. 자원·드라이버·모델 요구량을 먼저 확인하세요.'));
        els.diagInfo.append(text('h3', '이 PC의 모델 후보'), recommendations);
    }
    function recommendationText(rec) {
        const status = { candidate: '실행 후보 · 실행/품질/속도 미검증', unavailable: '현재 진단 조건 미충족' }[rec.status] || rec.status;
        const reason = { 'Candidate system RAM': '사용 가능한 시스템 RAM 기준의 후보입니다.', 'Insufficient RAM': 'RAM·디스크·측정 상태가 후보 조건을 충족하는지 확인하세요.' }[rec.reason] || rec.reason;
        return `${rec.id} · ${rec.backend} · ${status} · ${reason}`;
    }
    async function fetchDiagnostics() {
        if (diagnosticPending) return;
        diagnosticPending = true;
        try {
            const d = await api('/diagnostics');
            renderDiagnostic(d);

            const cat = await api('/catalog');
            els.modelsList.replaceChildren();
            const installedMap = cat.installed || {};
            const progressArr = cat.progress || [];

            (cat.artifacts || []).forEach(m => {
                const isInstalled = !!installedMap[m.id];
                const prog = progressArr.find(p => p.id === m.id);

                const card = document.createElement('div');
                card.className = 'card';

                const h4 = document.createElement('h4');
                h4.textContent = m.name;
                card.appendChild(h4);

                const meta = document.createElement('div');
                meta.className = 'card-meta';
                const progressState = prog ? ({ downloading: '받는 중·검증 전', importing: '가져오는 중·검증 전', failed: '실패', cancelled: '취소', done: '파일 준비 완료' }[prog.state] || prog.state) : '파일 미준비';
                meta.textContent = `용도 ${m.task} · 계열 ${m.family} · 필요 RAM ${m.ramGB}GB\n이용조건 ${m.license}\n${isInstalled ? '파일 준비됨 · 실제 구동·품질 확인 필요' : progressState}${prog?.total ? ` · ${Math.round(prog.received / prog.total * 100)}%` : ''}${prog?.error ? `\n안내: ${prog.error}` : ''}`;
                card.appendChild(meta);
                for (const rec of (d.recommendations || []).filter(rec => rec.id === m.id)) { const p = document.createElement('p'); p.className = 'model-recommendation'; p.textContent = recommendationText(rec); card.append(p); }
                const source = setupSourceLink(m);
                if (source) { const link = document.createElement('a'); link.href = source; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = '모델 원본·이용조건 확인'; card.append(link); }

                const actions = document.createElement('div');
                actions.className = 'card-actions';

                if (!isInstalled && (!prog || ['failed', 'cancelled', 'done'].includes(prog.state))) {
                    const btn = document.createElement('button');
                    btn.textContent = '내려받기';
                    btn.onclick = async () => {
                        try { await api('/models/download', { method: 'POST', body: JSON.stringify({id: m.id, path:""})}); fetchDiagnostics(); }
                        catch (e) { alert(e.message); }
                    };
                    actions.appendChild(btn);
                    const importButton = document.createElement('button');
                    importButton.textContent = '내려받은 파일 가져오기';
                    importButton.onclick = async () => {
                        const path = prompt('이 PC에 있는 모델 또는 런타임 ZIP의 전체 경로를 입력하세요. 원본 파일은 보존됩니다.');
                        if (!path?.trim()) return;
                        importButton.disabled = true;
                        try {
                            await api('/models/import', { method: 'POST', body: JSON.stringify({ id: m.id, path: path.trim() }) });
                            document.getElementById('model-message').textContent = '파일 검증·가져오기를 접수했습니다. 새로고침으로 완료 상태를 확인하세요.';
                            fetchDiagnostics();
                        } catch (e) { document.getElementById('model-message').textContent = e.message; }
                        finally { importButton.disabled = false; }
                    };
                    actions.appendChild(importButton);
                } else if (prog && prog.state === 'downloading') {
                    const btn = document.createElement('button');
                    btn.textContent = '취소';
                    btn.onclick = async () => {
                        try { await api('/models/cancel', { method: 'POST', body: JSON.stringify({id: m.id, path:""})}); fetchDiagnostics(); }
                        catch(e) { alert(e.message); }
                    };
                    actions.appendChild(btn);
                }
                if (isInstalled && ['translation', 'stt'].includes(m.task)) {
                    const useButton = document.createElement('button');
                    useButton.textContent = '이 모델 선택';
                    useButton.onclick = async () => {
                        useButton.disabled = true;
                        try {
                            await fetchStatus();
                            const config = { ...fullConfig, [m.task === 'stt' ? 'sttModel' : 'translationModel']: m.id };
                            await api('/config', { method: 'POST', body: JSON.stringify(config) });
                            fullConfig = config;
                            setupConsent.checked = false;
                            await fetchSetupPlan();
                            await fetchSetupStatus();
                            document.getElementById('model-message').textContent = '모델을 선택했습니다. 엔진을 중지하고 다시 시작해 실제 구동을 확인하세요.';
                        } catch (e) { document.getElementById('model-message').textContent = e.message; }
                        finally { useButton.disabled = false; }
                    };
                    actions.appendChild(useButton);
                }
                card.appendChild(actions);
                els.modelsList.appendChild(card);
            });
            await fetchSetupPlan();
        } catch(e) { document.getElementById('model-message').textContent = `PC 진단 확인 필요: ${e.message}`; }
        finally { diagnosticPending = false; }
    }
    document.getElementById('btn-refresh-diag').addEventListener('click', fetchDiagnostics);

    document.getElementById('model-register-form').addEventListener('submit', async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const submit = form.querySelector('button[type="submit"]');
        submit.disabled = true;
        const value = id => document.getElementById(id).value.trim();
        const kind = value('model-profile');
        const speech = kind === 'whisper';
        const profile = {
            name: value('model-repo') + ' / ' + value('model-filename'),
            task: speech ? 'stt' : 'translation', family: speech ? 'whisper' : 'custom',
            format: speech ? 'ggml' : 'gguf', prompt: speech ? '' : kind,
            ramGB: Number(value('model-ram')), vramGB: 0, context: speech ? 0 : 4096,
            license: value('model-license'), backends: speech ? ['cpu', 'cuda'] : ['cpu', 'cuda', 'vulkan', 'openvino-npu']
        };
        try {
            const registered = await api('/models/register', { method: 'POST', body: JSON.stringify({
                repo: value('model-repo'), revision: value('model-revision'), filename: value('model-filename'), profile
            }) });
            document.getElementById('model-message').textContent = '실험 모델을 등록했습니다: ' + registered.id + '. 내려받기 또는 파일 가져오기 후 실제 시험이 필요합니다.';
            await fetchDiagnostics();
        } catch (e) { document.getElementById('model-message').textContent = e.message; }
        finally { submit.disabled = false; }
    });

    async function fetchConfig() {
        if (!fullConfig) {
            await fetchStatus();
        }
        if (fullConfig) {
            document.getElementById('cfg-backend').value = fullConfig.backend;
            document.getElementById('cfg-translation').value = fullConfig.translationModel;
            document.getElementById('cfg-stt').value = fullConfig.sttModel;
            document.getElementById('cfg-publicBind').value = fullConfig.publicBind;
            document.getElementById('cfg-publicURL').value = fullConfig.publicURL;
            document.getElementById('cfg-tlsCert').value = fullConfig.tlsCert || '';
            document.getElementById('cfg-tlsKey').value = fullConfig.tlsKey || '';
            document.getElementById('cfg-maxListeners').value = fullConfig.maxListeners;
            document.getElementById('cfg-access').value = fullConfig.access;
            document.getElementById('cfg-listener-pin').value = '';
            document.getElementById('cfg-speaker-pin').value = '';
            updatePinHints();
            document.getElementById('cfg-online-endpoint').value = fullConfig.online?.endpoint || '';
            document.getElementById('cfg-online-model').value = fullConfig.online?.model || '';
            document.getElementById('cfg-online-consent').checked = !!fullConfig.online?.consent;
            document.getElementById('cfg-online-key').value = '';
            document.getElementById('cfg-source').value = fullConfig.sourceLanguage || 'ko';
            document.getElementById('cfg-targets').value = (fullConfig.targetLanguages || []).join(',');
        }
    }

    function updatePinHints() {
        const pinMode = document.getElementById('cfg-access').value === 'pin';
        const listener = document.getElementById('cfg-listener-pin');
        const speaker = document.getElementById('cfg-speaker-pin');
        listener.required = pinMode && !pinStatus.listenerSet;
        speaker.required = !pinStatus.speakerSet;
        document.getElementById('cfg-listener-pin-status').textContent = pinStatus.listenerSet ? '청취 PIN 설정됨 · 비우면 기존 값 유지' : (pinMode ? '청취 PIN이 없습니다. PIN 접속을 사용하려면 새 값을 입력하세요.' : '청취 PIN 미설정 · PIN 접속을 선택할 때 입력하세요.');
        document.getElementById('cfg-speaker-pin-status').textContent = pinStatus.speakerSet ? '원격 마이크 PIN 설정됨 · 비우면 기존 값 유지' : '원격 마이크 PIN이 없습니다. 설정 저장 전 새 값을 입력하세요.';
    }
    document.getElementById('cfg-access').addEventListener('change', updatePinHints);
    document.getElementById('config-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!fullConfig) return;
        const newConfig = { ...fullConfig };
        newConfig.backend = document.getElementById('cfg-backend').value;
        newConfig.translationModel = document.getElementById('cfg-translation').value;
        newConfig.sttModel = document.getElementById('cfg-stt').value;
        newConfig.publicBind = document.getElementById('cfg-publicBind').value;
        newConfig.publicURL = document.getElementById('cfg-publicURL').value;
        newConfig.tlsCert = document.getElementById('cfg-tlsCert').value.trim();
        newConfig.tlsKey = document.getElementById('cfg-tlsKey').value.trim();
        newConfig.maxListeners = parseInt(document.getElementById('cfg-maxListeners').value, 10);
        newConfig.access = document.getElementById('cfg-access').value;
        const listenerInput = document.getElementById('cfg-listener-pin');
        const speakerInput = document.getElementById('cfg-speaker-pin');
        const listenerPin = listenerInput.value, speakerPin = speakerInput.value;
        const pinBytes = value => new TextEncoder().encode(value).length;
        const message = document.getElementById('config-msg');
        if ((listenerPin && (pinBytes(listenerPin) < 6 || pinBytes(listenerPin) > 32 || listenerPin !== listenerPin.trim())) || (newConfig.access === 'pin' && !listenerPin && !pinStatus.listenerSet)) { message.textContent = '청취 PIN은 앞뒤 공백 없이 UTF-8 기준 6~32바이트로 입력하세요.'; listenerInput.focus(); return; }
        if ((speakerPin && (pinBytes(speakerPin) < 6 || pinBytes(speakerPin) > 32 || speakerPin !== speakerPin.trim())) || (!speakerPin && !pinStatus.speakerSet)) { message.textContent = '원격 마이크 PIN은 앞뒤 공백 없이 UTF-8 기준 6~32바이트로 입력하세요.'; speakerInput.focus(); return; }
        newConfig.listenerPin = listenerPin;
        newConfig.speakerPin = speakerPin;
        newConfig.sourceLanguage = document.getElementById('cfg-source').value.trim();
        newConfig.targetLanguages = document.getElementById('cfg-targets').value.split(',').map(x => x.trim()).filter(Boolean);
        newConfig.online = { endpoint: document.getElementById('cfg-online-endpoint').value.trim(), model: document.getElementById('cfg-online-model').value.trim(), apiKey: document.getElementById('cfg-online-key').value, consent: document.getElementById('cfg-online-consent').checked };

        try {
            const saved = await api('/config', { method: 'POST', body: JSON.stringify(newConfig) });
            document.getElementById('config-msg').textContent = '설정을 저장했습니다.' +
                (saved.restartRequired ? ' 네트워크 변경 적용을 위해 프로그램을 다시 실행하세요.' : '') +
                (saved.engineRestartRequired ? ' 모델 변경 적용을 위해 엔진을 다시 시작하세요.' : '');
            rememberConfig(saved.config || newConfig, saved.pinStatus || { listenerSet: Boolean(listenerPin) || pinStatus.listenerSet, speakerSet: Boolean(speakerPin) || pinStatus.speakerSet });
            if (listenerInput.value === listenerPin) listenerInput.value = '';
            if (speakerInput.value === speakerPin) speakerInput.value = '';
            updatePinHints();
            document.getElementById('cfg-online-key').value = '';
            setupConsent.checked = false;
            await fetchSetupPlan();
            await fetchSetupStatus();
        } catch(e) { alert(e.message); }
    });

    async function fetchRooms() {
        try {
            const rooms = (await api('/rooms')) || [];
            els.roomsList.replaceChildren();
            for (const r of rooms) {
                const card = document.createElement('div');
                card.className = 'card';

                const title = document.createElement('h4');
                title.textContent = r.title;
                card.appendChild(title);

                const meta = document.createElement('div');
                meta.className = 'card-meta';
                meta.textContent = `방식 ${r.mode === 'lecture' ? '강의' : '자유 대화'} · 정원 ${r.capacity}명 (설정값·실측 아님) · 언어 ${r.languages.join(', ')}`;
                card.appendChild(meta);

                const actions = document.createElement('div');
                actions.className = 'card-actions';

                const tBtn = document.createElement('button');
                tBtn.textContent = '강사 초대 QR';
                tBtn.onclick = () => showQR('강사 초대', r.teacherURL);

                const sBtn = document.createElement('button');
                sBtn.textContent = '학생 초대 QR';
                sBtn.onclick = () => showQR('학생 초대', r.studentURL);

                actions.appendChild(tBtn);
                actions.appendChild(sBtn);
                card.appendChild(actions);

                els.roomsList.appendChild(card);
            }
        } catch (e) { document.getElementById('operation-status').textContent = `강의실 목록 확인 필요: ${e.message}`; }
    }

    const modalCreate = document.getElementById('modal-create');
    document.getElementById('btn-show-create').onclick = () => modalCreate.classList.remove('hidden');
    document.getElementById('btn-cancel-create').onclick = () => modalCreate.classList.add('hidden');
    document.getElementById('form-create-room').addEventListener('submit', async (e) => {
        e.preventDefault();
        try {
            await api('/rooms', { method: 'POST', body: JSON.stringify({
                title: document.getElementById('room-title').value,
                mode: document.getElementById('room-mode').value,
                capacity: parseInt(document.getElementById('room-capacity').value),
                languages: document.getElementById('room-languages').value.split(',').map(s=>s.trim())
            })});
            modalCreate.classList.add('hidden');
            fetchRooms();
        } catch(e) { alert(e.message); }
    });

    const modalQr = document.getElementById('modal-qr');
    const qrContainer = document.getElementById('qr-container');
    const qrUrl = document.getElementById('qr-url');
    function showQR(title, url) {
        document.getElementById('qr-title').textContent = title;
        qrUrl.value = url;
        qrContainer.replaceChildren();
        if (window.QRCode) {
            new QRCode(qrContainer, { text: url, width: 200, height: 200 });
        } else {
            qrContainer.textContent = 'QR 표시를 사용할 수 없습니다. 초대 주소를 복사하세요.';
        }
        modalQr.classList.remove('hidden');
    }
    document.getElementById('btn-close-qr').onclick = () => modalQr.classList.add('hidden');
    document.getElementById('btn-copy-url').onclick = () => navigator.clipboard.writeText(qrUrl.value);


    // An input remains attached to the session chosen before permission prompts.
    const noteStatus = document.getElementById('notes-status');
    const noteInput = document.getElementById('notes-text');
    const micButton = document.getElementById('btn-mic');
    const kinds = { note: '음성노트', broadcast: '통역 방송', file: '파일 전사', lecture: '강의실' };
    function updateActiveSessionUI() {
        const id = activeSession?.id || '';
        if (displayedSessionId !== id) {
            if (displayedSessionId) noteDrafts.set(displayedSessionId, noteInput.value);
            displayedSessionId = id;
            noteInput.value = noteDrafts.get(id) || '';
            notesSequence = 0; notesCache.clear();
            document.getElementById('notes-lines').replaceChildren();
        }
        document.getElementById('active-session-info').textContent = activeSession
            ? `${activeSession.title || '제목 없는 작업'} · ${kinds[activeSession.kind] || activeSession.kind} · ${activeSession.sourceLanguage} → ${(activeSession.targets || []).join(', ')}${activeSession.gapCount ? ` · 복구 기록 ${activeSession.gapCount}회. 녹음 공백을 확인하고 마이크를 직접 켜세요.` : ''}`
            : '진행 중인 작업이 없습니다. 방송 또는 음성노트를 시작하세요.';
        document.getElementById('btn-stop-session').disabled = !activeSession || sessionStopping;
        document.getElementById('btn-submit-text').disabled = !activeSession || textSubmitting;
        micButton.disabled = !capture && !activeSession;
        const invite = document.getElementById('broadcast-url');
        invite.value = activeSession?.kind === 'broadcast' ? publicURL : '';
        document.getElementById('broadcast-share').hidden = activeSession?.kind !== 'broadcast';
    }
    document.getElementById('broadcast-copy').onclick = async () => {
        try { await navigator.clipboard.writeText(document.getElementById('broadcast-url').value); noteStatus.textContent = '청취 주소를 복사했습니다.'; }
        catch { noteStatus.textContent = '청취 주소를 선택해 직접 복사하세요.'; }
    };
    document.getElementById('broadcast-qr').onclick = () => showQR('통역 방송 청취', publicURL);
    document.getElementById('form-create-notes').addEventListener('submit', async e => {
        e.preventDefault();
        const button = e.currentTarget.querySelector('button[type=submit]');
        button.disabled = true;
        try {
            if (capture) await stopMic();
            const targets = document.getElementById('notes-targets').value.trim();
            await api('/sessions', { method: 'POST', body: JSON.stringify({
                kind: document.getElementById('notes-kind').value,
                title: document.getElementById('notes-title').value.trim(),
                sourceLanguage: document.getElementById('notes-source').value.trim(),
                targets: targets ? targets.split(',').map(s => s.trim()).filter(Boolean) : undefined
            }) });
            await fetchStatus(); updateActiveSessionUI();
            noteStatus.textContent = '작업을 시작했습니다. 마이크는 별도로 켜세요.';
        } catch (err) { noteStatus.textContent = err.message; }
        finally { button.disabled = false; }
    });
    document.getElementById('btn-stop-session').addEventListener('click', async () => {
        if (sessionStopping) return;
        sessionStopping = true;
        const button = document.getElementById('btn-stop-session'); button.disabled = true;
        try { await stopMic(); await api('/sessions/stop', { method: 'POST' }); await fetchStatus(); updateActiveSessionUI(); noteStatus.textContent = '작업을 종료했습니다. 저장된 자료는 이력에서 확인하세요.'; }
        catch (err) { noteStatus.textContent = err.message; button.disabled = false; }
        finally { sessionStopping = false; updateActiveSessionUI(); }
    });
    document.getElementById('form-submit-text').addEventListener('submit', async e => {
        e.preventDefault();
        if (!activeSession) { noteStatus.textContent = '먼저 방송 또는 음성노트를 시작하세요.'; return; }
        if (textSubmitting) return;
        textSubmitting = true;
        const sessionId = activeSession.id, text = noteInput.value;
        const button = document.getElementById('btn-submit-text'); button.disabled = true;
        try {
            await api('/text', { method: 'POST', body: JSON.stringify({ sessionId, text }) });
            if (activeSession?.id === sessionId && noteInput.value === text) { noteInput.value = ''; noteDrafts.delete(sessionId); }
            noteStatus.textContent = '원문을 저장했습니다. 번역·음성은 처리 결과에서 확인하세요.';
            await fetchNotesLines();
        } catch (err) { noteStatus.textContent = `${err.message} · 입력은 보존했습니다. 이력에서 전송 여부를 확인한 뒤 다시 시도하세요.`; }
        finally { textSubmitting = false; button.disabled = !activeSession; }
    });
    document.getElementById('btn-refresh-notes').addEventListener('click', async () => { await fetchStatus(); updateActiveSessionUI(); await fetchNotesLines(); });
    async function fetchNotesLines() {
        if (!activeSession || notesPending) return;
        const sessionId = activeSession.id;
        notesPending = true;
        try {
            const lines = await api(`/sessions/${encodeURIComponent(sessionId)}/lines?after=${Math.max(0, notesSequence - 100)}&limit=200`);
            if (activeSession?.id !== sessionId) return;
            for (const line of lines) { notesCache.set(line.id, line); notesSequence = Math.max(notesSequence, line.sequence); }
            const recent = [...notesCache.values()].sort((a, b) => a.sequence - b.sequence).slice(-300);
            notesCache.clear(); for (const line of recent) notesCache.set(line.id, line);
            renderLines(recent, 'notes-lines');
        } catch (err) { noteStatus.textContent = err.message; }
        finally { notesPending = false; }
    }
    function renderLines(lines, containerId) {
        const container = document.getElementById(containerId); container.replaceChildren();
        if (!lines.length) { container.textContent = '아직 저장된 문장이 없습니다.'; return; }
        for (const line of lines) {
            const card = document.createElement('article'); card.className = 'line-item';
            const meta = document.createElement('p'); meta.className = 'card-meta';
            meta.textContent = `문장 ${line.sequence} · ${line.speakerName || 'PC 입력'} · ${line.sourceLanguage || ''} · ${line.capturedAt ? new Date(line.capturedAt).toLocaleString('ko-KR') : ''}`; card.append(meta);
            const source = document.createElement('p'); source.className = 'line-source'; source.textContent = line.sourceText; card.append(source);
            for (const [language, value] of Object.entries(line.translations || {})) { const p = document.createElement('p'); p.textContent = `[${language}] ${value}`; card.append(p); }
            for (const [language, value] of Object.entries(line.errors || {})) { const p = document.createElement('p'); p.className = 'err-text'; p.textContent = `처리 안내 [${language}]: ${value}`; card.append(p); }
            for (const [language, filename] of Object.entries(line.audio || {})) {
                if (!filename) continue;
                const button = document.createElement('button'); button.type = 'button'; button.textContent = language === 'source' ? '원음 듣기' : `${language} 음성 듣기`; button.onclick = () => playAudio(line.sessionId, filename); card.append(button);
            }
            container.append(card);
        }
    }
    const globalAudio = new Audio();
    let playbackGeneration = 0, playbackURL = '';
    async function playAudio(sessionId, filename) {
        const generation = ++playbackGeneration; globalAudio.pause();
        try {
            const blob = await authFetchBlob(`/admin/api/audio/${encodeURIComponent(sessionId)}/${encodeURIComponent(filename)}`);
            if (generation !== playbackGeneration) return;
            if (playbackURL) URL.revokeObjectURL(playbackURL);
            playbackURL = URL.createObjectURL(blob); globalAudio.src = playbackURL;
            await globalAudio.play();
        } catch (err) { noteStatus.textContent = `음성 재생: ${err.message}`; }
    }
    function captureCurrent(attempt) { return capture === attempt && attempt.generation === captureGeneration && activeSession?.id === attempt.sessionId; }
    async function stopMic(message = '') {
        const attempt = capture;
        if (!attempt) return;
        if (attempt.stopping) return attempt.stopping;
        attempt.phase = 'stopping';
        // Stop physical capture synchronously, including cancellation of permission setup.
        attempt.stream?.getTracks().forEach(track => track.stop());
        attempt.source?.disconnect();
        micButton.textContent = '마이크 종료 중';
        attempt.stopping = (async () => {
            if (attempt.node) {
                await new Promise(resolve => { attempt.flushDone = resolve; attempt.node.port.postMessage({ type: 'flush' }); setTimeout(resolve, 250); });
            }
            if (attempt.socket?.readyState === WebSocket.OPEN) {
                const until = performance.now() + 500;
                while (attempt.socket.bufferedAmount && performance.now() < until) await new Promise(resolve => setTimeout(resolve, 20));
            }
            if (attempt.socket) {
                await new Promise(resolve => { attempt.socket.addEventListener('close', resolve, { once: true }); attempt.socket.close(); setTimeout(resolve, 500); });
            }
            attempt.node?.disconnect(); attempt.gain?.disconnect();
            if (attempt.context) await attempt.context.close().catch(() => {});
            document.getElementById('mic-meter').value = 0;
            if (message) noteStatus.textContent = message;
            if (attempt.opened && activeSession?.id === attempt.sessionId && !attempt.cancelledPermission) {
                const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 5000);
                try { await api('/input/flush', { method: 'POST', signal: controller.signal, body: JSON.stringify({ sessionId: attempt.sessionId }) }); }
                catch (err) { noteStatus.textContent = `마이크는 꺼졌습니다. 마지막 문장 저장 확인 필요: ${err.message}`; }
                finally { clearTimeout(timeout); }
            }
            if (capture === attempt) { capture = null; captureGeneration++; }
            micButton.textContent = '마이크 켜기'; micButton.disabled = !activeSession;
        })();
        return attempt.stopping;
    }
    micButton.addEventListener('click', async () => {
        if (capture) { capture.cancelledPermission = capture.phase === 'permission'; await stopMic('마이크를 껐습니다. 작업은 계속 열려 있습니다.'); return; }
        if (!activeSession) return;
        const attempt = { sessionId: activeSession.id, generation: ++captureGeneration, phase: 'permission' }; capture = attempt;
        micButton.textContent = '마이크 준비 취소'; micButton.disabled = false;
        try {
            if (!navigator.mediaDevices?.getUserMedia) throw new Error('이 브라우저에서 마이크를 사용할 수 없습니다. PC의 localhost 또는 HTTPS 주소를 사용하세요.');
            const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true } });
            if (!captureCurrent(attempt) || attempt.phase === 'stopping') { stream.getTracks().forEach(track => track.stop()); return; }
            attempt.stream = stream;
            const AudioCtor = window.AudioContext || window.webkitAudioContext;
            attempt.context = new AudioCtor();
            await attempt.context.audioWorklet.addModule('/room/audio-worklet.js');
            const status = await api('/status');
            if (!captureCurrent(attempt) || attempt.phase === 'stopping' || status.activeSession?.id !== attempt.sessionId) { await stopMic('작업이 바뀌어 마이크 준비를 취소했습니다.'); return; }
            await attempt.context.resume();
            if (!captureCurrent(attempt) || attempt.phase === 'stopping') return;
            const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/admin/ws/input?token=${encodeURIComponent(token)}&sessionId=${encodeURIComponent(attempt.sessionId)}`); attempt.socket = socket;
            socket.binaryType = 'arraybuffer'; attempt.phase = 'connecting';
            socket.onopen = () => {
                if (!captureCurrent(attempt) || attempt.phase === 'stopping') { socket.close(); return; }
                attempt.opened = true;
                attempt.source = attempt.context.createMediaStreamSource(stream);
                attempt.node = new AudioWorkletNode(attempt.context, 'pcm-worklet');
                attempt.gain = attempt.context.createGain(); attempt.gain.gain.value = 0;
                attempt.node.port.onmessage = ({ data }) => {
                    if (data?.type === 'flushed') { attempt.flushDone?.(); return; }
                    if (data?.type !== 'pcm' || !captureCurrent(attempt) || socket.readyState !== WebSocket.OPEN) return;
                    if (socket.bufferedAmount > 65536) { stopMic('연결 혼잡으로 마이크를 중단했습니다. 마지막 음성의 저장 여부를 확인하고 다시 켜세요.'); return; }
                    socket.send(data.samples.buffer);
                    document.getElementById('mic-meter').value = Math.min(1, data.rms || 0);
                };
                attempt.source.connect(attempt.node); attempt.node.connect(attempt.gain); attempt.gain.connect(attempt.context.destination);
                attempt.phase = 'recording'; micButton.textContent = '마이크 끄기'; noteStatus.textContent = '마이크 입력 중 · 선택한 작업에 원음을 저장합니다.';
            };
            socket.onmessage = ({ data }) => { if (!captureCurrent(attempt) || attempt.phase === 'stopping') return; try { const event = JSON.parse(data); if (event.type === 'error') stopMic(`마이크 처리 안내: ${event.message}`); } catch {} };
            socket.onclose = () => { if (captureCurrent(attempt) && attempt.phase !== 'stopping') stopMic('입력 연결이 종료되어 마이크를 껐습니다. 다시 연결하려면 직접 켜세요.'); };
            socket.onerror = () => { if (captureCurrent(attempt)) stopMic('마이크 연결 실패. 작업과 엔진 상태를 확인하세요.'); };
            setTimeout(() => { if (captureCurrent(attempt) && attempt.phase === 'connecting') stopMic('마이크 연결 대기 시간이 지났습니다. 다시 켜세요.'); }, 7000);
        } catch (err) { if (capture === attempt) await stopMic(`마이크 준비 실패: ${err.message}`); }
    });
    window.addEventListener('pagehide', () => { stopMic(); playbackGeneration++; globalAudio.pause(); if (playbackURL) URL.revokeObjectURL(playbackURL); });
    setInterval(() => { if (document.getElementById('panel-notes').classList.contains('active')) fetchNotesLines(); }, 3000);

    // Files Workflow
    document.getElementById('form-upload-file').addEventListener('submit', async e => {
        e.preventDefault();
        const btn = document.getElementById('btn-upload-file');
        const status = document.getElementById('files-upload-status');
        btn.disabled = true;
        status.textContent = '파일 접수 중';
        try {
            const file = document.getElementById('files-audio').files[0];
            if (!file) throw new Error('전사할 WAV 파일을 선택하세요.');
            if (file.size > 240 * 1024 * 1024) throw new Error('WAV 파일은 240MB 이하로 나누어 올리세요.');
            let sId = document.getElementById('files-session').value.trim();
            if (!sId) {
                const targetsStr = document.getElementById('files-targets').value.trim();
                const s = await api('/sessions', { method: 'POST', body: JSON.stringify({
                    kind: 'file',
                    title: document.getElementById('files-title').value.trim(),
                    sourceLanguage: document.getElementById('files-source').value.trim(),
                    targets: targetsStr ? targetsStr.split(',').map(x=>x.trim()) : undefined
                })});
                sId = s.id; document.getElementById('files-session').value = sId;
            }
            const fd = new FormData();
            fd.append('audio', file);
            await api(`/files?sessionId=${encodeURIComponent(sId)}`, { method: 'POST', body: fd });
            status.textContent = '파일 작업을 접수했습니다. 전사·번역 완료 여부는 아래 작업 상태에서 확인하세요.';
            fetchJobs();
        } catch(err) {
            status.textContent = '접수 확인 필요: ' + err.message;
        } finally {
            btn.disabled = false;
        }
    });

    document.getElementById('btn-refresh-jobs').addEventListener('click', fetchJobs);
    let jobsPending = false;

    async function fetchJobs() {
        if (jobsPending) return;
        jobsPending = true;
        try {
            const jobs = (await api('/jobs')) || [];
            const container = document.getElementById('jobs-list');
            container.replaceChildren();
            const recentJobs = [...jobs].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).slice(0, 100);
            if (!recentJobs.length) container.textContent = '등록된 처리 작업이 없습니다.';
            recentJobs.forEach(j => {
                const div = document.createElement('div');
                div.className = 'card';
                const title = document.createElement('h4');
                title.textContent = `처리 작업 ${j.id} · 작업 ${j.sessionId}`;
                div.appendChild(title);
                const p = document.createElement('p');
                p.textContent = `상태: ${{ queued: '처리 대기', running: '처리 중', done: '완료', failed: '실패', collecting: '원음 수집 중' }[j.state] || j.state} · 시도 ${j.attempts}회${j.error ? ' · 안내: '+j.error : ''}`;
                div.appendChild(p);
                if (j.state === 'failed') {
                    const btn = document.createElement('button');
                    btn.textContent = '실패 작업 재시도';
                    btn.onclick = async () => {
                        btn.disabled = true;
                        try {
                            await api(`/jobs/${j.id}/retry`, { method: 'POST' });
                            fetchJobs();
                        } catch(err) { alert(err.message); btn.disabled = false; }
                    };
                    div.appendChild(btn);
                }
                container.appendChild(div);
            });
        } catch(err) { document.getElementById('operation-status').textContent = err.message; }
        finally { jobsPending = false; }
    }

    // History Workflow
    document.getElementById('btn-refresh-history').addEventListener('click', fetchHistory);
    let historyId = '', historyGeneration = 0, historyCursor = 0, historyCursors = [0], historyPage = 0;
    async function loadHistoryPage(id, page = 0) {
        const generation = ++historyGeneration;
        const after = historyCursors[page] || 0;
        document.getElementById('history-more').disabled = true;
        try {
            const lines = await api(`/sessions/${encodeURIComponent(id)}/lines?after=${after}&limit=100`);
            if (generation !== historyGeneration || historyId !== id) return;
            historyPage = page; historyCursor = lines.length ? lines[lines.length - 1].sequence : after;
            renderLines(lines, 'history-lines');
            document.getElementById('history-page-status').textContent = lines.length ? `작업 ${id} · 문장 ${lines[0].sequence}–${historyCursor} 표시. 전체 자료는 형식별 내려받기를 이용하세요.` : '이 페이지에 문장이 없습니다.';
            document.getElementById('history-more').disabled = lines.length < 100;
            document.getElementById('history-previous').disabled = page === 0;
        } catch (err) { document.getElementById('operation-status').textContent = err.message; }
    }
    document.getElementById('history-more').onclick = () => { historyCursors[historyPage + 1] = historyCursor; loadHistoryPage(historyId, historyPage + 1); };
    document.getElementById('history-previous').onclick = () => loadHistoryPage(historyId, Math.max(0, historyPage - 1));

    async function fetchHistory() {
        try {
            const sessions = (await api('/sessions')) || [];
            const container = document.getElementById('history-sessions-list');
            container.replaceChildren();
            sessions.forEach(s => {
                const div = document.createElement('div');
                div.className = 'card';
                const h = document.createElement('h4');
                h.textContent = `${s.title || '제목 없는 작업'} (${s.kind}) - ${s.id}`;
                div.appendChild(h);
                const p = document.createElement('p');
                p.textContent = `상태: ${{ active: '진행 중', stopped: '종료', interrupted: '중단' }[s.state] || s.state} · 입력 ${s.sourceLanguage} · 대상 ${(s.targets||[]).join(',')}${s.gapCount ? ` · 복구/녹음 공백 ${s.gapCount}회` : ''}`;
                div.appendChild(p);

                const actions = document.createElement('div');
                actions.className = 'card-actions';

                const btnView = document.createElement('button');
                btnView.textContent = '문장 보기';
                btnView.onclick = async () => {
                    historyId = s.id; historyCursors = [0];
                    await loadHistoryPage(s.id, 0);
                };
                actions.appendChild(btnView);

                ['json', 'txt', 'md', 'srt', 'zip'].forEach(fmt => {
                    const btnExp = document.createElement('button');
                    btnExp.textContent = `${fmt.toUpperCase()} 내려받기`;
                    btnExp.onclick = async () => {
                        try {
                            const blob = await authFetchBlob(`/admin/api/sessions/${encodeURIComponent(s.id)}/export?format=${encodeURIComponent(fmt)}`);
                            const url = URL.createObjectURL(blob);
                            const a = document.createElement('a');
                            a.href = url;
                            a.download = `MCastTalk-${s.id}.${fmt}`;
                            document.body.appendChild(a);
                            a.click();
                            document.body.removeChild(a);
                            setTimeout(() => URL.revokeObjectURL(url), 10000);
                        } catch(err) { alert(err.message); }
                    };
                    actions.appendChild(btnExp);
                });

                div.appendChild(actions);
                container.appendChild(div);
            });
        } catch(err) { document.getElementById('operation-status').textContent = err.message; }
    }

    // Glossary Workflow
    let glossaryTerms = [];
    let glossaryDirty = false;
    document.getElementById('btn-refresh-glossary').addEventListener('click', fetchGlossary);

    async function fetchGlossary() {
        try {
            if (glossaryDirty) { document.getElementById('glossary-status').textContent = '편집 중인 용어는 보존했습니다. 먼저 저장하세요.'; return; }
            glossaryTerms = (await api('/glossary')) || [];
            renderGlossary();
        } catch(err) { document.getElementById('glossary-status').textContent = err.message; }
    }

    function renderGlossary() {
        const tbody = document.getElementById('glossary-tbody');
        tbody.replaceChildren();
        glossaryTerms.forEach((t, i) => {
            const tr = document.createElement('tr');
            ['language', 'source', 'target'].forEach(k => {
                const td = document.createElement('td');
                const inp = document.createElement('input');
                inp.value = t[k];
                inp.setAttribute('aria-label', { language: '용어 언어', source: '원문 용어', target: '번역 용어' }[k]);
                inp.oninput = () => { glossaryTerms[i][k] = inp.value; glossaryDirty = true; };
                td.appendChild(inp);
                tr.appendChild(td);
            });
            const tdAct = document.createElement('td');
            const btn = document.createElement('button');
            btn.textContent = '삭제';
            btn.onclick = () => { glossaryTerms.splice(i, 1); glossaryDirty = true; renderGlossary(); };
            tdAct.appendChild(btn);
            tr.appendChild(tdAct);
            tbody.appendChild(tr);
        });
    }

    document.getElementById('form-add-term').addEventListener('submit', e => {
        e.preventDefault();
        glossaryDirty = true;
        glossaryTerms.push({
            language: document.getElementById('term-lang').value.trim(),
            source: document.getElementById('term-source').value.trim(),
            target: document.getElementById('term-target').value.trim()
        });
        document.getElementById('term-source').value = '';
        document.getElementById('term-target').value = '';
        renderGlossary();
    });

    document.getElementById('btn-save-glossary').addEventListener('click', async () => {
        const status = document.getElementById('glossary-status');
        const button = document.getElementById('btn-save-glossary');
        const snapshot = JSON.stringify(glossaryTerms);
        button.disabled = true;
        try {
            await api('/glossary', { method: 'POST', body: snapshot });
            glossaryDirty = JSON.stringify(glossaryTerms) !== snapshot;
            status.textContent = glossaryDirty ? '요청한 용어집을 저장했습니다. 이후 편집한 내용은 다시 저장하세요.' : '용어집을 저장했습니다.';
        } catch(err) { status.textContent = err.message; }
        finally { button.disabled = false; }
    });

    // Scripts Workflow
    document.getElementById('btn-refresh-scripts').addEventListener('click', fetchScripts);
    async function fetchScripts() {
        try {
            const scripts = (await api('/scripts')) || [];
            const container = document.getElementById('scripts-list');
            container.replaceChildren();
            scripts.forEach(s => {
                const div = document.createElement('div');
                div.className = 'card';
                const h = document.createElement('h4');
                h.textContent = s.title;
                div.appendChild(h);
                const p = document.createElement('p');
                p.textContent = s.text;
                div.appendChild(p);
                const btn = document.createElement('button');
                btn.textContent = '입력에 붙이기';
                btn.onclick = () => {
                    const input = document.getElementById('notes-text'); input.value += (input.value ? '\n\n' : '') + s.text;
                    document.querySelector('.nav-menu li[data-target="notes"]').click();
                };
                div.appendChild(btn);
                container.appendChild(div);
            });
        } catch(err) { document.getElementById('operation-status').textContent = err.message; }
    }

    document.getElementById('form-create-script').addEventListener('submit', async e => {
        e.preventDefault();
        const button = e.currentTarget.querySelector('button[type="submit"]');
        const titleInput = document.getElementById('script-title');
        const textInput = document.getElementById('script-text');
        const title = titleInput.value, text = textInput.value;
        button.disabled = true;
        try {
            await api('/scripts', { method: 'POST', body: JSON.stringify({
                title: title.trim(), text: text.trim()
            })});
            if (titleInput.value === title) titleInput.value = '';
            if (textInput.value === text) textInput.value = '';
            fetchScripts();
        } catch(err) { alert(err.message); }
        finally { button.disabled = false; }
    });

    // Lessons Workflow
    document.getElementById('btn-refresh-lessons').addEventListener('click', fetchLessons);
    async function fetchLessons() {
        try {
            const lessons = (await api('/lessons')) || [];
            const container = document.getElementById('lessons-list');
            container.replaceChildren();
            lessons.forEach(l => {
                const div = document.createElement('div');
                div.className = 'card';
                const h = document.createElement('h4');
                h.textContent = `교정 ${l.id} · ${l.language}`;
                div.appendChild(h);
                const p = document.createElement('p');
                p.textContent = `원문: ${l.source}
기존 번역: ${l.before || ''}
교정 제안: ${l.proposed}
판정: ${{ pending: '검토 대기', approved: '승인', held: '보류' }[l.status] || l.status}`; p.style.whiteSpace = 'pre-wrap';
                div.appendChild(p);

                const actions = document.createElement('div');
                actions.className = 'card-actions';
                ['approved', 'held'].forEach(st => {
                    const btn = document.createElement('button');
                    btn.textContent = st === 'approved' ? '교정 승인' : '보류'; btn.disabled = l.status === st;
                    btn.onclick = async () => {
                        try {
                            await api(`/lessons/${l.id}/decision`, { method: 'POST', body: JSON.stringify({ status: st }) });
                            fetchLessons();
                        } catch(err) { alert(err.message); }
                    };
                    actions.appendChild(btn);
                });
                div.appendChild(actions);
                container.appendChild(div);
            });
        } catch(err) { document.getElementById('operation-status').textContent = err.message; }
    }

    document.getElementById('form-create-lesson').addEventListener('submit', async e => {
        e.preventDefault();
        try {
            await api('/lessons', { method: 'POST', body: JSON.stringify({
                language: document.getElementById('lesson-lang').value.trim(),
                source: document.getElementById('lesson-source').value.trim(),
                before: document.getElementById('lesson-before').value.trim(),
                proposed: document.getElementById('lesson-proposed').value.trim()
            })});
            fetchLessons();
        } catch(err) { alert(err.message); }
    });

    document.getElementById('nav-toggle').onclick = () => { const open = document.querySelector('.sidebar').classList.toggle('open'); document.getElementById('nav-toggle').setAttribute('aria-expanded', String(open)); };
    document.querySelectorAll('[data-open-panel]').forEach(button => button.onclick = () => document.querySelector(`.nav-menu li[data-target="${button.dataset.openPanel}"]`).click());
    setInterval(() => { if (document.getElementById('panel-files').classList.contains('active')) fetchJobs(); }, 3000);
    setInterval(fetchStatus, 3000);
    fetchStatus(); fetchDiagnostics(); fetchRooms();
});
