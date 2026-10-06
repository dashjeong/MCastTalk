// app.js - Refactored for safe rendering and realistic mock state

const SCHEMA_VERSION = "1.0";
const MAX_HISTORY = 500;
const MAX_FEEDBACK = 100;

document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initQR();
    initStorage();
    initRoleRouting();
    initInteractions();
    initFailureControls();
});

function safeText(text) {
    return text == null ? "" : String(text);
}

// Navigation state
function initNavigation() {
    const navBtns = document.querySelectorAll('.nav-btn');
    const sections = document.querySelectorAll('.view-section');

    navBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            navBtns.forEach(b => b.classList.remove('active'));
            sections.forEach(s => s.classList.remove('active'));
            btn.classList.add('active');
            const viewId = `view-${btn.dataset.view}`;
            const target = document.getElementById(viewId);
            if(target) {
                target.classList.add('active');
                document.querySelector('.content').scrollTop=0;
            }
        });
    });
}

function initQR() {
    // Detect localhost safely
    const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
    const baseUrl = window.location.origin + window.location.pathname;

    if (typeof QRCode !== 'undefined') {
        const createQR = (id, role) => {
            const el = document.getElementById(id);
            if(el) {
                el.innerHTML = ''; // reset
                new QRCode(el, {
                    text: `${baseUrl}?role=${role}`,
                    width: 128, height: 128
                });
            }
        };
        createQR('qr-teacher', 'teacher');
        createQR('qr-student', 'student');
    }
}

function initRoleRouting() {
    const params = new URLSearchParams(window.location.search);
    const role = params.get('role');
    if (role === 'teacher' || role === 'student') {
        document.querySelector('[data-testid="nav-participant"]')?.click();
        const roleSelect = document.getElementById('role-select');
        if(roleSelect) roleSelect.value = role;
    }
}

// Storage and State
let state = {
    transcriptId: 0,
    history: [],
    feedbacks: [],
    isConnected: true,
    gapDetected: false,
    room: {
        mode: 'lecture', // lecture | conversation
        queue: []
    },
    rooms: [{id:'room-1',title:'지속 가능한 도시'},{id:'room-2',title:'일반 대화'}],
    preferences: {},
    models: [],
    gapCount: 0
};

function initStorage() {
    try {
        const raw = localStorage.getItem('mcast_mock_state');
        if (raw) {
            const parsed = JSON.parse(raw);
            if (parsed.version === SCHEMA_VERSION) {
                state.transcriptId = parsed.transcriptId || 0;
                state.history = Array.isArray(parsed.history) ? parsed.history.slice(-MAX_HISTORY).filter(h=>Number.isSafeInteger(h.id)&&h.id>0&&typeof h.sourceText==='string'&&typeof h.sourceLang==='string'&&h.translations&&typeof h.translations==='object') : [];
                state.history = state.history.filter((h,i,a)=>a.findIndex(v=>v.id===h.id)===i);
                state.transcriptId = Math.max(0,...state.history.map(h=>h.id),Number.isSafeInteger(parsed.transcriptId)?parsed.transcriptId:0);
                state.feedbacks = Array.isArray(parsed.feedbacks) ? parsed.feedbacks.slice(-MAX_FEEDBACK) : [];
                state.preferences = parsed.preferences && typeof parsed.preferences==='object' ? parsed.preferences : {};
                state.models = Array.isArray(parsed.models) ? parsed.models.slice(-30).filter(p=>['repo','revision','file','family','prompt','license','sha'].every(k=>typeof p[k]==='string')) : [];
                const rooms=Array.isArray(parsed.rooms)?parsed.rooms.filter(r=>typeof r.id==='string'&&typeof r.title==='string').slice(-30):[];
                if(rooms.length)state.rooms=rooms;
                state.gapCount = Number.isSafeInteger(parsed.gapCount) ? parsed.gapCount : 0;
                state.room.mode = parsed.roomMode==='conversation'?'conversation':'lecture';
                Object.entries(state.preferences).forEach(([id,value])=>{const el=document.getElementById(id); if(el) { if(el.type==='checkbox')el.checked=!!value;else if(typeof value==='string')el.value=value; }});
                document.getElementById('room-mode-'+state.room.mode).checked=true;
            }
        }
    } catch(e) {
        console.warn('Storage read failed', e);
    }

    // Re-render everything from state
    renderAllHistory();
    renderFeedbacks();

    // Bind room mode
    document.getElementsByName('roomMode').forEach(radio => {
        radio.addEventListener('change', (e) => {
            state.room.mode = e.target.value;
            saveState();
        });
    });
}

function saveState() {
    try {
        // Enforce limits
        if(state.history.length > MAX_HISTORY) state.history = state.history.slice(-MAX_HISTORY);
        if(state.feedbacks.length > MAX_FEEDBACK) state.feedbacks = state.feedbacks.slice(-MAX_FEEDBACK);

        localStorage.setItem('mcast_mock_state', JSON.stringify({
            version: SCHEMA_VERSION,
            transcriptId: state.transcriptId,
            history: state.history,
            feedbacks: state.feedbacks,
            preferences: state.preferences,
            rooms: state.rooms,
            models: state.models,
            gapCount: state.gapCount,
            roomMode: state.room.mode
        }));
        return true;
    } catch(e) {
        console.error('Storage write failed', e);
        const fbStatus = document.getElementById('fb-status');
        if(fbStatus) fbStatus.textContent = "저장소 오류: 변경사항이 임시로만 유지됩니다.";
        return false;
    }
}

// Mocks
const SAMPLES = {
    teacherSpeech: {
        role: 'teacher',
        ko: "우리가 살고 있는 도시를 어떻게 더 지속 가능하게 만들 수 있을까요? 에너지 효율을 높이는 방안이 핵심입니다.",
        en: "How can we make the cities we live in more sustainable? Improving energy efficiency is key.",
        ja: "私たちが住んでいる都市をどのようにより持続可能にできるでしょうか？エネルギー効率を高めることが重要です。",
        zh: "我们如何才能让我们居住的城市更具可持续性？提高能源效率是关键。",
        es: "¿Cómo podemos hacer que las ciudades en las que vivimos sean más sostenibles? Mejorar la eficiencia energética es clave."
    },
    studentQuestion: {
        role: 'student',
        ko: "건축에서 재생 에너지를 사용하는 비율을 50%까지 올리는 데 얼마나 걸릴까요?",
        en: "How long will it take to increase the proportion of renewable energy in construction to 50%?",
        ja: "建築における再生可能エネルギーの使用割合を50％まで引き上げるのにどれくらいかかりますか？",
        zh: "在建筑中将可再生能源的使用比例提高到50%需要多长时间？",
        es: "¿Cuánto tiempo llevará aumentar la proporción de uso de energía renovable en la construcción al 50%?"
    },
    teacherAnswer: {
        role: 'teacher',
        ko: "좋은 질문입니다. 규제와 비용 문제를 해결한다면 약 10년이 걸릴 것으로 예상합니다.",
        en: "That's a good question. If regulatory and cost issues are resolved, it is expected to take about 10 years.",
        ja: "良い質問ですね。規制とコストの問題を解決すれば、約10年かかると予想されます。",
        zh: "好问题。如果解决了监管和成本问题，预计需要大约10年时间。",
        es: "Buena pregunta. Si se resuelven los problemas normativos y de costes, se espera que tarde unos 10 años."
    }
};

function initInteractions() {
    // Actions Panel
    document.getElementById('btn-teacher-speak')?.addEventListener('click', () => triggerSample(SAMPLES.teacherSpeech, '강사 (시연)'));
    document.getElementById('btn-student-speak')?.addEventListener('click', () => triggerSample(SAMPLES.studentQuestion, '학생 (시연)'));
    document.getElementById('btn-custom-speak')?.addEventListener('click', () => {
        const input = document.getElementById('custom-speech');
        if(input && input.value.trim()) {
            triggerCustom(input.value.trim(), '강사 (직접입력)', 'teacher');
            input.value = '';
        }
    });

    // Participant Room lifecycle
    const inRoomSection = document.getElementById('participant-room');
    const setupSection = document.getElementById('participant-setup');
    const btnJoin = document.getElementById('btn-join-room');
    const btnLeave = document.getElementById('btn-leave-room');

    if(btnJoin) {
        btnJoin.addEventListener('click', () => {
            setupSection.classList.add('hidden');
            inRoomSection.classList.remove('hidden');
            const name=document.getElementById('participant-name').value.trim().slice(0,40);
            document.getElementById('participant-name').value=name||'참가자';
            document.getElementById('p-room-name').textContent=(document.getElementById('room-select').selectedOptions[0]?.textContent||'대화방')+' · '+(name||'참가자')+' · '+document.getElementById('role-select').selectedOptions[0].textContent;

            const rSelect = document.getElementById('role-select');
            const role = rSelect ? rSelect.value : 'student';

            // Show role-specific buttons
            document.getElementById('btn-p-question-speak').classList.toggle('hidden', role !== 'student');
            document.getElementById('btn-p-teacher-answer').classList.toggle('hidden', role !== 'teacher');

            renderParticipantView(); // Update view with current preferences
        });
    }

    if(btnLeave) {
        btnLeave.addEventListener('click', () => {
            inRoomSection.classList.add('hidden');
            setupSection.classList.remove('hidden');
            // Release floor if held
            const reqBtn = document.getElementById('btn-p-request-speak');
            if(reqBtn && (reqBtn.classList.contains('active')||reqBtn.classList.contains('queued'))) {
                reqBtn.click(); // trigger release
            }
        });
    }

    // Participant In-Room Sample buttons
    document.getElementById('btn-p-question-speak')?.addEventListener('click', () => {
        const name = document.getElementById('participant-name')?.value || 'Student';
        triggerSample(SAMPLES.studentQuestion, name, document.getElementById('language-select')?.value);
    });

    document.getElementById('btn-p-teacher-answer')?.addEventListener('click', () => {
        const name = document.getElementById('participant-name')?.value || 'Teacher';
        triggerSample(SAMPLES.teacherAnswer, name, document.getElementById('language-select')?.value);
    });

    // Checkboxes and preferences
    document.getElementById('language-select')?.addEventListener('change', renderAllHistory);
    document.getElementById('show-original')?.addEventListener('change', renderParticipantView);
    document.getElementById('caption-only')?.addEventListener('change', (e) => {
        const activeStr = e.target.checked ? "자막 모드 (음성 출력 끄기)" : "자막 + 모의 음성 활성";
        document.getElementById('p-speak-status').textContent = activeStr;
    });

    document.getElementById('history-language')?.addEventListener('change', renderHistoryView);

    // Floor request toggle
    const reqBtn = document.getElementById('btn-p-request-speak');
    if(reqBtn) {
        reqBtn.addEventListener('click', () => {
            if(!state.isConnected&&!reqBtn.classList.contains('active')&&!reqBtn.classList.contains('queued')) return;
            const statusLabel = document.getElementById('p-speak-status');

            if (reqBtn.classList.contains('active') || reqBtn.classList.contains('queued')) {
                // Release
                reqBtn.classList.remove('active', 'queued');
                reqBtn.textContent = '발언권 요청 / 해제 (토글)';
                statusLabel.textContent = '대기 중...';
            } else {
                // Request
                const isContention = document.getElementById('sim-voice-contention')?.checked || (state.room.mode === 'lecture' && document.getElementById('role-select')?.value === 'student');

                if (isContention) {
                    reqBtn.classList.add('queued');
                    reqBtn.textContent = '발언권 대기열 취소';
                    statusLabel.textContent = state.room.mode === 'lecture' ? '강사 발언 중, 발언권 대기열에 추가됨' : '발언 경합 유발됨, 대기열 진입';
                } else {
                    reqBtn.classList.add('active');
                    reqBtn.textContent = '발언 종료 (마이크 모의 끄기)';
                    statusLabel.textContent = '발언 시연 중 · 실제 녹음 없음 (마이크 권한 요청안함)';
                }
            }
        });
    }

    // Export Feedback
    document.getElementById('btn-submit-feedback')?.addEventListener('click', () => {
        const screenEl = document.getElementById('fb-screen');
        const probEl = document.getElementById('fb-problem');
        const reqEl = document.getElementById('fb-request');
        const prioEl = document.getElementById('fb-priority');
        const statEl = document.getElementById('fb-status');

        if(!screenEl.value || !reqEl.value) {
            statEl.textContent = "화면과 요구사항을 입력하세요.";
            return;
        }

        state.feedbacks.push({
            id: Date.now(),
            timestamp: new Date().toISOString(),
            screen: screenEl.value,
            problem: probEl.value,
            request: reqEl.value,
            priority: prioEl.value
        });
        const stored=saveState();
        if(stored) {screenEl.value = ''; probEl.value = ''; reqEl.value = '';}
        statEl.textContent = stored ? "시연 피드백을 이 브라우저에 저장했습니다." : "저장 실패 · 현재 화면에서만 유지됩니다. JSON으로 내보내세요.";
        renderFeedbacks();
    });

    document.getElementById('btn-export-feedback')?.addEventListener('click', () => {
        exportJSON('feedbacks.json', state.feedbacks, { mode: 'simulation' });
    });

    // Export History
    document.getElementById('btn-export-json')?.addEventListener('click', () => {
        exportJSON('history.json', state.history, { mode: 'simulation' });
    });

    document.getElementById('btn-export-txt')?.addEventListener('click', () => {
        const language=document.getElementById('history-language').value;
        const lines = state.history.map(h => `[${h.timestamp}] ${h.speaker}: ${h.sourceText} -> [${language}] ${h.translations[language] || '실제 번역 미제공'}`);
        exportText('history.txt', 'MCastTalk MVP · simulation · 실제 통번역 결과 아님\n'+lines.join('\n'));
    });

    // HF registration and profile lifecycle are bound by workflows.js.

    // Model load/unload simulation
    document.querySelectorAll('.mock-load, .mock-unload').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const target = e.target.dataset.target;
            const statusEl = document.getElementById(`status-${target}`);
            if(e.target.classList.contains('mock-load')) {
                if(document.getElementById('ram-selector').value==='8'&&['12b','27b'].includes(target)) {
                    statusEl.textContent='예시 RAM8GB로는 부족 · 작은 모델 후보를 선택하세요 (실측 전)';return;
                }
                statusEl.textContent = "모의 로드됨";
                statusEl.className = "status-label loaded";
                e.target.textContent = "언로드";
                e.target.classList.replace('mock-load', 'mock-unload');
            } else {
                statusEl.textContent = "대기";
                statusEl.className = "status-label waiting";
                e.target.textContent = "모의 로드";
                e.target.classList.replace('mock-unload', 'mock-load');
            }
        });
    });
}

function triggerSample(sampleObj, speakerName, sourceLangCode = null) {
    if (!state.isConnected) {
        appendSystemMessage("네트워크 끊김: 발언을 전송할 수 없습니다.");
        return;
    }

    const tId = ++state.transcriptId;
    const timestamp = new Date().toLocaleTimeString();

    // Determine source
    // If a sourceLangCode is provided (from participant selector), use that specific translation as the "source"
    const actualSourceLang = sourceLangCode || (sampleObj.role === 'student' ? 'en' : 'ko');
    const sourceText = sampleObj[actualSourceLang] || sampleObj.ko;

    const data = {
        id: tId,
        timestamp,
        speaker: speakerName,
        role: sampleObj.role,
        sourceLang: actualSourceLang,
        sourceText: sourceText,
        translations: { ...sampleObj },
        isCustom: false,
        correction: null
    };
    data.roomId=document.getElementById('room-select').value;

    simulatePipelineAndSave(data);
}

function triggerCustom(text, speakerName, role, sourceLang='ko') {
    if (!state.isConnected) return;
    const tId = ++state.transcriptId;
    const timestamp = new Date().toLocaleTimeString();

    const data = {
        id: tId,
        timestamp,
        speaker: speakerName,
        role: role,
        sourceLang,
        sourceText: text,
        translations: {},
        isCustom: true,
        correction: null
    };
    data.roomId=document.getElementById('room-select').value;

    simulatePipelineAndSave(data);
}

function simulatePipelineAndSave(data) {
    const pStatus = document.getElementById('pipeline-status');
    if(pStatus) pStatus.textContent = '인식 중 (STT 모의)...';

    setTimeout(() => {
        if(pStatus) pStatus.textContent = '문맥 번역 중...';

        setTimeout(() => {
            const delayJa = document.getElementById('sim-delay-ja')?.checked;
            if (delayJa && !data.isCustom) {
                data.translations[document.getElementById('sim-language').value] = '[시연 지연/오류] 번역을 받지 못함';
            }

            if(pStatus) pStatus.textContent = '전송 중...';

            state.history.push(data);
            saveState();
            renderAllHistory(); // Rerender

            if(pStatus) pStatus.textContent = '대기 중';

        }, 600);
    }, 400);
}

// Rendering Logic (Safe Text Content)
function renderAllHistory() {
    renderConsoleView();
    renderParticipantView();
    renderHistoryView();
    renderRecoveryView();
}

function renderRecoveryView() {
    const status = document.getElementById('recovery-status');
    if (!status) return;
    const lastId = state.history.reduce((last, item) => Math.max(last, item.id), 0);
    const summary = `브라우저에 저장한 확정 시연 문장 ${state.history.length}개 · 마지막 ID ${lastId}`;
    status.textContent = state.gapCount
        ? `${summary} · 공백 ${state.gapCount}회 · 실제 Windows 재부팅 시험 전. 전원이 꺼진 동안의 발언은 생성되지 않으며 실제 마이크는 다시 켜야 합니다.`
        : `${summary} · 실제 원음/서버 복구는 POC 시험 예정`;
}

function renderConsoleView() {
    const cBox = document.getElementById('console-transcript');
    if(!cBox) return;
    cBox.innerHTML = '';

    state.history.filter(h=>!h.roomId||h.roomId===document.getElementById('room-select').value).forEach(item => {
        const div = document.createElement('div');
        div.className = `t-item ${item.role}`;

        const metaDiv = document.createElement('div');
        metaDiv.className = 't-meta';
        const spSpan = document.createElement('span');
        spSpan.textContent = `${item.speaker} (ID: ${item.id})`;
        const tsSpan = document.createElement('span');
        tsSpan.textContent = item.timestamp;
        metaDiv.appendChild(spSpan); metaDiv.appendChild(tsSpan);

        const srcDiv = document.createElement('div');
        srcDiv.className = 't-source text-muted';
        srcDiv.textContent = `[${item.sourceLang.toUpperCase()}] ${item.sourceText}`;

        div.appendChild(metaDiv);
        div.appendChild(srcDiv);

        if (item.isCustom) {
            const transDiv = document.createElement('div');
            transDiv.className = 't-trans untranslated';
            transDiv.textContent = '[실제 번역은 POC 모델 연결 후 제공]';
            div.appendChild(transDiv);
        } else {
            const koDiv = document.createElement('div');
            koDiv.className = 't-trans';
            koDiv.textContent = item.translations.ko;
            const subDiv = document.createElement('div');
            subDiv.className = 't-trans text-muted';
            subDiv.style.fontSize = '0.85rem';
            subDiv.textContent = `${item.translations.en} / ${item.translations.ja}`;
            div.appendChild(koDiv);
            div.appendChild(subDiv);
        }

        cBox.appendChild(div);
    });
    cBox.scrollTop = cBox.scrollHeight;
}

function renderParticipantView() {
    const pBox = document.getElementById('p-transcript');
    if(!pBox) return;
    pBox.innerHTML = '';

    const myLang = document.getElementById('language-select')?.value || 'en';
    const showOrig = document.getElementById('show-original')?.checked;

    state.history.filter(h=>!h.roomId||h.roomId===document.getElementById('room-select').value).forEach(item => {
        const div = document.createElement('div');
        div.className = `t-item ${item.role}`;

        const metaDiv = document.createElement('div');
        metaDiv.className = 't-meta';
        const spSpan = document.createElement('span');
        spSpan.textContent = item.speaker;
        const tsSpan = document.createElement('span');
        tsSpan.textContent = item.timestamp;
        metaDiv.appendChild(spSpan); metaDiv.appendChild(tsSpan);

        div.appendChild(metaDiv);

        if (showOrig) {
            const srcDiv = document.createElement('div');
            srcDiv.className = 't-source text-muted';
            srcDiv.style.fontSize = '0.8rem';
            srcDiv.textContent = item.sourceText;
            div.appendChild(srcDiv);
        }

        const transDiv = document.createElement('div');
        transDiv.className = 't-trans';
        if (item.isCustom) {
            transDiv.className += ' untranslated';
            transDiv.textContent = '번역 미제공 (모의 텍스트)';
        } else {
            transDiv.textContent = item.translations[myLang] || item.translations.ko;
        }
        div.appendChild(transDiv);

        pBox.appendChild(div);
    });
    pBox.scrollTop = pBox.scrollHeight;
}

function renderHistoryView() {
    const hBox = document.getElementById('history-list');
    if(!hBox) return;
    hBox.innerHTML = '';

    const selectedLang = document.getElementById('history-language')?.value || 'en';

    // Render in reverse chronological order
    const reversed = [...state.history].reverse();

    reversed.forEach(item => {
        const div = document.createElement('div');
        div.className = 'h-item';

        const metaDiv = document.createElement('div');
        metaDiv.className = 't-meta';
        metaDiv.textContent = `${item.speaker} | ${item.timestamp}`;

        const srcDiv = document.createElement('div');
        srcDiv.textContent = `원문(${item.sourceLang}): ${item.sourceText}`;

        const transDiv = document.createElement('div');
        transDiv.style.color = 'var(--accent-color)';
        const targetStr = item.isCustom ? '미제공' : (item.translations[selectedLang] || item.translations.ko);
        transDiv.textContent = `번역(${selectedLang.toUpperCase()}): ${targetStr}`;

        div.appendChild(metaDiv);
        div.appendChild(srcDiv);
        div.appendChild(transDiv);

        // Correction block
        const corrDiv = document.createElement('div');
        corrDiv.className = 'h-correction';

        const corrInput = document.createElement('input');
        corrInput.type = 'text';
        corrInput.placeholder = '더 나은 번역 제안 (모의)';
        corrInput.style.flex = '1';
        const correction=item.corrections?.[selectedLang];
        corrInput.value = correction?.text || '';
        corrInput.setAttribute('aria-label','문장 '+item.id+' '+selectedLang+' 교정 제안');

        const corrBtn = document.createElement('button');
        corrBtn.className = 'secondary-btn small';
        corrBtn.textContent = '제안 저장';
        const decision=document.createElement('select');
        decision.setAttribute('aria-label','교정 검토 상태');
        [['pending','검토 대기'],['approved','승인 (시연)'],['held','보류'],['revoked','승인 취소']].forEach(([value,text])=>{const opt=document.createElement('option');opt.value=value;opt.textContent=text;decision.append(opt);});
        decision.value=correction?.status||'pending';
        decision.addEventListener('change',()=>{if(item.corrections?.[selectedLang]) {item.corrections[selectedLang].status=decision.value;saveState();}});

        corrBtn.addEventListener('click', () => {
            if(corrInput.value.trim()) {
                item.corrections=item.corrections||{};
                item.corrections[selectedLang]={text:corrInput.value.trim().slice(0,2000),status:'pending'};
                corrBtn.textContent = saveState()?'제안 저장됨 · 검토 대기':'저장 실패';
                decision.value='pending';
            }
        });

        corrDiv.appendChild(corrInput);
        corrDiv.appendChild(corrBtn);
        corrDiv.appendChild(decision);
        div.appendChild(corrDiv);

        hBox.appendChild(div);
    });
}

function renderFeedbacks() {
    const list = document.getElementById('fb-list');
    if(!list) return;
    list.innerHTML = '';

    state.feedbacks.forEach(fb => {
        const div = document.createElement('div');
        div.style.marginBottom = '0.5rem';
        div.textContent = `[${fb.priority}] ${fb.screen} - ${fb.problem} (요구: ${fb.request})`;
        list.appendChild(div);
    });
}

function appendSystemMessage(msgStr) {
    const cBox = document.getElementById('console-transcript');
    if(cBox) {
        const div = document.createElement('div');
        div.className = 't-item system';
        div.textContent = msgStr;
        cBox.appendChild(div);
        cBox.scrollTop = cBox.scrollHeight;
    }
}

// Failure Controls
function initFailureControls() {
    const btnToggle = document.getElementById('btn-toggle-failure');
    const panel = document.querySelector('.failure-panel');

    if(btnToggle) {
        btnToggle.addEventListener('click', () => {
            panel.classList.toggle('minimized');
            btnToggle.textContent = panel.classList.contains('minimized') ? '펼치기' : '최소화';
        });
    }

    const msg = document.getElementById('sim-status-msg');

    document.getElementById('btn-sim-disconnect')?.addEventListener('click', () => {
        state.isConnected = false;
        msg.textContent = '네트워크 끊김! 수신/발신 불가 상태.';
        msg.style.color = '#dc2626';
        appendSystemMessage("--- 경고: 네트워크 연결이 끊어졌습니다 ---");
    });

    document.getElementById('btn-sim-reconnect')?.addEventListener('click', () => {
        state.isConnected = true;
        renderAllHistory();
        msg.textContent = '시연 재연결 · 저장된 문장을 ID별로 다시 표시합니다. 실제 서버 누락분 회수는 POC 시험 예정.';
        msg.style.color = '#166534';
        appendSystemMessage("--- 시스템: 재연결되었습니다. 안전한 ID 기반 복원 확인 ---");
        setTimeout(() => { msg.textContent = ''; }, 3000);
    });

    document.getElementById('btn-sim-reboot')?.addEventListener('click', () => {
        state.gapCount++;
        saveState();
        msg.textContent = 'PC 강제 종료 발생... 새로고침합니다.';
        msg.style.color = '#dc2626';
        setTimeout(() => {
            window.location.reload();
        }, 1000);
    });
}

// Helpers
function exportJSON(filename, data, meta) {
    const blob = new Blob([JSON.stringify({ meta, data }, null, 2)], { type: 'application/json' });
    downloadBlob(blob, filename);
}
function exportText(filename, text) {
    const blob = new Blob([text], { type: 'text/plain' });
    downloadBlob(blob, filename);
}
function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}
