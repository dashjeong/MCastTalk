'use strict';

document.addEventListener('DOMContentLoaded', () => {
    const $ = id => document.getElementById(id);
    const els = {
        join: $('join-view'), chat: $('chat-view'), form: $('join-form'), joinError: $('join-error'),
        joinRetry: $('join-retry'), sidebar: $('sidebar'), overlay: $('sidebar-overlay'),
        menu: $('btn-toggle-sidebar'), roomInfo: $('room-info'), publicList: $('public-channels-list'),
        privateList: $('private-channels-list'), members: $('member-list'), title: $('current-channel-title'),
        badge: $('current-scope-badge'), history: $('chat-history'), input: $('chat-input'), send: $('btn-send'),
        mic: $('btn-mic'), meter: $('mic-level'), micStatus: $('mic-status'), owner: $('floor-owner'),
        timer: $('floor-timer'), queue: $('queue-status'), request: $('btn-request-floor'),
        release: $('btn-release-floor'), privacy: $('privacy-scope-text'), gap: $('connection-gap'),
        retry: $('btn-reconnect'), leave: $('btn-leave'), settings: $('btn-settings'),
        settingsMenu: $('settings-menu'), language: $('active-language'), export: $('btn-export'),
        exportFormat: $('export-format'), notes: $('context-notes'), saveNotes: $('btn-save-context'),
        notesCount: $('context-count'), autoplay: $('voice-autoplay'), stopVoice: $('btn-stop-voice'),
        voiceStatus: $('voice-status'), status: $('operation-status'), pending: $('pending-list'),
        pendingSummary: $('pending-summary'), mode: $('room-mode'), modeWrap: $('room-mode-control')
    };
    const names = { ko: '한국어', en: '영어 · English', ja: '일본어 · 日本語', zh: '중국어 · 中文', es: '스페인어 · Español' };
    const id = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    const languageName = value => names[value] || value;
    const node = (tag, text, className) => {
        const element = document.createElement(tag);
        if (text !== undefined) element.textContent = text;
        if (className) element.className = className;
        return element;
    };
    const button = (label, action, className = 'secondary') => {
        const element = node('button', label, className);
        element.type = 'button';
        element.addEventListener('click', action);
        return element;
    };
    const session = {
        token: '', room: null, participant: null, channelId: null, channels: [], lines: new Map(),
        ws: null, wsGen: 0, connected: false, closing: false, mutation: false,
        reconnectTimer: null, reconnectAttempts: 0, engine: null
    };
    const drafts = new Map();
    const draftVersions = new Map();
    const textPending = new Map();
    const noteDrafts = new Map();
    const voiceUploads = []; // At most eight unaccepted WAVs, including in-flight/failed.
    let uploadPump = null;
    let activeUploadController = null;
    let capture = null;
    let captureGen = 0;
    let restoredAuth = null;
    let historyFrame = null;
    let channelFetchGen = 0;
    const playback = { gen: 0, busy: false, queue: [], audio: null, url: null, abort: null, finish: null };
    const heard = new Set();
    let invite = new URLSearchParams(location.hash.slice(1)).get('invite') || '';
    if (location.hash) history.replaceState(null, '', location.pathname + location.search);

    function message(text, bad = false) {
        els.status.textContent = text;
        els.status.classList.toggle('error', bad);
    }
    function scope() {
        return Object.freeze({
            token: session.token, roomId: session.room.id, channelId: session.channelId,
            language: session.participant.language, memberId: session.participant.id, wsGen: session.wsGen
        });
    }
    function sameOwner(sc) {
        return !session.closing && sc.token === session.token && sc.memberId === session.participant?.id &&
            sc.roomId === session.room?.id;
    }
    function selected(sc) {
        return sameOwner(sc) && sc.channelId === session.channelId && sc.language === session.participant.language;
    }
    function channel() { return session.channels.find(c => c.id === session.channelId); }
    function activeRoom() { return session.room?.state === 'active' && !session.closing; }
    function ownsFloor() {
        const c = channel();
        return c?.floor?.participantId === session.participant?.id && Date.parse(c.floor.expiresAt) > Date.now();
    }
    function canInput() { return session.connected && activeRoom() && !session.mutation && ownsFloor(); }
    function authKey() { return 'mcast-room-data:' + session.participant.id + ':' + session.room.id; }

    async function request(path, options = {}, sc = null, timeout = 12000) {
        const controller = new AbortController();
        const relay = () => controller.abort();
        const upstream = options.signal;
        if (upstream?.aborted) controller.abort();
        else upstream?.addEventListener('abort', relay, { once: true });
        const timer = setTimeout(() => controller.abort(), timeout);
        const headers = new Headers(options.headers || {});
        const token = sc ? sc.token : session.token;
        if (token) headers.set('Authorization', 'Bearer ' + token);
        try {
            const response = await fetch(path, { ...options, headers, signal: controller.signal });
            if (!response.ok) {
                const error = new Error((await response.text()).slice(0, 400) || '요청을 처리하지 못했습니다.');
                error.httpStatus = response.status;
                throw error;
            }
            return response;
        } finally {
            clearTimeout(timer);
            upstream?.removeEventListener('abort', relay);
        }
    }
    async function jsonRequest(path, data, sc = null, signal = null) {
        const response = await request(path, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data), signal
        }, sc);
        return response.json();
    }
    const roomPath = (roomId, action) => '/api/classroom/' + encodeURIComponent(roomId) + '/' + action;

    function persist() {
        if (!session.participant || !session.room) return;
        const pending = Array.from(textPending.values()).map(p => ({
            id: p.id, text: p.text, original: p.original, version: p.version,
            channelId: p.sc.channelId, language: p.sc.language, state: p.state
        }));
        try {
            sessionStorage.setItem('roomAuth', JSON.stringify({
                token: session.token, roomId: session.room.id, channelId: session.channelId
            }));
            sessionStorage.setItem(authKey(), JSON.stringify({
                drafts: Object.fromEntries(drafts), versions: Object.fromEntries(draftVersions),
                pending, autoplay: els.autoplay.checked, audioCount: voiceUploads.length
            }));
        } catch (_) {
            message('이 창에는 보관됐지만 브라우저 저장 공간이 부족해 새로고침 복구를 보장할 수 없습니다.', true);
        }
    }
    function restoreDrafts() {
        try {
            const saved = JSON.parse(sessionStorage.getItem(authKey()) || '{}');
            for (const [cid, text] of Object.entries(saved.drafts || {})) {
                if (typeof text === 'string' && text.length <= 8192 && session.channels.some(c => c.id === cid)) drafts.set(cid, text);
            }
            for (const [cid, version] of Object.entries(saved.versions || {})) draftVersions.set(cid, Number(version) || 0);
            for (const p of (saved.pending || []).slice(0, 8)) {
                if (!session.channels.some(c => c.id === p.channelId) || !/^[0-9a-f]{32}$/.test(p.id) || typeof p.text !== 'string') continue;
                const sc = Object.freeze({ ...scope(), channelId: p.channelId, language: p.language });
                textPending.set(p.channelId, { ...p, sc, state: 'failed', error: '서버 접수 여부를 같은 요청 ID로 다시 확인하세요.' });
            }
            els.autoplay.checked = !!saved.autoplay;
            if (saved.audioCount > 0) message('이전 창의 미접수 음성 ' + saved.audioCount + '구간은 새로고침으로 복구되지 않습니다. 서버에 접수된 음성과 기록은 유지됩니다.', true);
        } catch (_) { message('저장된 초안을 읽지 못했습니다. 서버 기록은 다시 연결하면 조회할 수 있습니다.', true); }
    }
    function storeDraft() {
        if (!session.channelId) return;
        drafts.set(session.channelId, els.input.value);
        draftVersions.set(session.channelId, (draftVersions.get(session.channelId) || 0) + 1);
        persist();
    }
    function showJoin(text) {
        els.join.classList.remove('hidden');
        els.chat.classList.add('hidden');
        els.joinError.textContent = text || '';
        els.joinRetry.classList.toggle('hidden', !restoredAuth || !!invite);
    }
    function setSidebar(open) {
        els.sidebar.classList.toggle('open', open);
        els.overlay.classList.toggle('hidden', !open);
        els.menu.setAttribute('aria-expanded', String(open));
        if (!open) els.menu.focus();
    }
    function updateGating() {
        const allowed = canInput();
        const pendingText = textPending.get(session.channelId);
        const sending = pendingText?.state === 'sending';
        els.input.disabled = !allowed || sending;
        els.send.disabled = !allowed || sending;
        els.mic.disabled = !allowed || voiceUploads.length >= 7 || !!capture?.state.match(/starting|stopping/);
        els.request.disabled = !session.connected || !activeRoom() || session.mutation;
        els.release.disabled = !session.connected || !activeRoom() || session.mutation;
        els.language.disabled = !session.connected || !activeRoom() || session.mutation;
        const c = channel();
        const mayEdit = c && (c.kind === 'private' || session.participant?.role === 'teacher');
        els.notes.disabled = !session.connected || !activeRoom() || !mayEdit;
        els.saveNotes.disabled = els.notes.disabled;
        els.mode.disabled = !session.connected || !activeRoom() || session.mutation;
        if (capture && (!session.connected || !activeRoom() || !ownsFloor() || !selected(capture.sc))) {
            cancelCapture('발언권 또는 연결이 바뀌어 녹음을 중단했습니다.');
        }
    }
    function updateRoom() {
        if (!session.room) return;
        els.roomInfo.replaceChildren(node('strong', session.room.title),
            node('small', (session.room.mode === 'lecture' ? '강의 모드' : '자유대화 모드') + ' · ' +
                (session.room.members || []).filter(m => m.online).length + '명 연결 · 설정 정원 ' + session.room.capacity));
        els.modeWrap.classList.toggle('hidden', session.participant.role !== 'teacher');
        els.mode.value = session.room.mode;
        els.language.replaceChildren();
        for (const value of session.room.languages) {
            const option = node('option', languageName(value));
            option.value = value;
            els.language.append(option);
        }
        els.language.value = session.participant.language;
    }
    function updateChannel() {
        const c = channel();
        if (!c) {
            els.title.textContent = '대화를 선택하세요';
            els.badge.textContent = '';
            els.privacy.textContent = '수신 범위를 확인한 뒤 입력할 수 있습니다.';
            updateGating();
            return;
        }
        els.title.textContent = c.title;
        els.badge.className = 'badge ' + c.kind;
        els.badge.textContent = c.kind === 'private' ? '개인대화 · 지정 2명' : '전체 강의·공지';
        if (c.kind === 'private') {
            const recipients = (c.memberIds || []).map(memberId => session.room.members.find(m => m.id === memberId)?.name || '참여자');
            els.privacy.textContent = '수신자: ' + recipients.join(' ↔ ') + '. 운영 PC가 내용을 처리합니다. 종단 간 암호화가 아닙니다.';
        } else {
            els.privacy.textContent = '수신자: 이 방에 인증된 전체 참여자. 인터넷 무인증 공개 게시가 아닙니다.';
        }
        if (document.activeElement !== els.notes) els.notes.value = noteDrafts.get(c.id) ?? c.contextNotes ?? '';
        els.notesCount.textContent = Array.from(els.notes.value).length + ' / 512자';
        updateFloor();
    }
    function updateFloor() {
        const c = channel();
        if (!c) return updateGating();
        const left = Math.max(0, Math.ceil((Date.parse(c.floor?.expiresAt) - Date.now()) / 1000)) || 0;
        const holder = left ? session.room.members.find(m => m.id === c.floor?.participantId) : null;
        els.owner.textContent = holder ? '발언 중: ' + holder.name : '발언권 대기';
        els.timer.textContent = left ? left + '초' : '';
        const queue = c.queue || [];
        const position = queue.indexOf(session.participant.id);
        els.queue.textContent = position >= 0 ? '내 대기 순서 ' + (position + 1) + ' / ' + queue.length : queue.length ? '발언 대기 ' + queue.length + '명' : '';
        els.request.classList.toggle('hidden', ownsFloor() || position >= 0);
        els.release.classList.toggle('hidden', !ownsFloor() && position < 0);
        els.release.textContent = position >= 0 ? '대기 취소' : '발언권 놓기';
        updateGating();
    }
    function renderChannels() {
        els.publicList.replaceChildren();
        els.privateList.replaceChildren();
        for (const c of session.channels) {
            const item = node('li');
            const control = button(c.title, () => selectChannel(c.id), 'channel-button');
            control.setAttribute('aria-current', c.id === session.channelId ? 'true' : 'false');
            control.disabled = session.mutation;
            if (c.id === session.channelId) item.classList.add('active');
            item.append(control);
            (c.kind === 'private' ? els.privateList : els.publicList).append(item);
        }
        if (!els.privateList.childElementCount) els.privateList.append(node('li', '참여자를 선택해 개인대화를 시작하세요.', 'empty'));
    }
    function renderMembers() {
        els.members.replaceChildren();
        for (const m of session.room?.members || []) {
            const item = node('li', m.name + ' · ' + (m.role === 'teacher' ? '강사' : '학생') + ' · ' + languageName(m.language));
            item.append(node('span', m.online ? ' 연결됨' : ' 연결 대기', 'presence'));
            if (m.id !== session.participant.id) {
                const dm = button('개인대화', async () => {
                    try {
                        const sc = scope();
                        const c = await jsonRequest(roomPath(sc.roomId, 'channels'), { participantIds: [m.id] }, sc);
                        if (!sameOwner(sc)) return;
                        await fetchChannels();
                        selectChannel(c.id);
                    } catch (error) { message('개인대화를 열지 못했습니다: ' + error.message, true); }
                });
                dm.disabled = !session.connected || !activeRoom() || session.mutation;
                item.append(dm);
            }
            if (session.participant.role === 'teacher' && channel()?.kind === 'broadcast') {
                const grant = button('발언 승인', () => requestFloor('grant', m.id));
                grant.disabled = !session.connected || !activeRoom() || session.mutation;
                item.append(grant);
            }
            els.members.append(item);
        }
    }
    async function fetchChannels() {
        const sc = scope();
        const fetchGen = ++channelFetchGen;
        const response = await request(roomPath(sc.roomId, 'channels'), {}, sc);
        const result = await response.json();
        if (!sameOwner(sc) || fetchGen !== channelFetchGen) return;
        session.channels = result;
        renderChannels();
        updateChannel();
    }
    function selectChannel(cid) {
        if (cid === session.channelId || session.mutation || !session.channels.some(c => c.id === cid)) return;
        storeDraft();
        cancelCapture('대화가 바뀌어 미접수 녹음을 중단했습니다.');
        stopPlayback();
        session.channelId = cid;
        session.lines.clear();
        els.history.replaceChildren();
        els.input.value = drafts.get(cid) || '';
        updateChannel();
        renderChannels();
        renderMembers();
        renderPending();
        persist();
        setSidebar(false);
        connectWS();
    }

    async function bootstrap(auth) {
        session.closing = false;
        const sc = { token: auth.token };
        const response = await request(roomPath(auth.roomId, 'status'), {}, sc);
        const data = await response.json();
        session.token = auth.token;
        session.room = data.room;
        session.participant = data.participant;
        session.engine = data.engine;
        session.channelId = data.room.id;
        await fetchChannels();
        const desired = auth.channelId;
        if (desired && session.channels.some(c => c.id === desired)) session.channelId = desired;
        else if (desired && desired !== data.room.id) {
            session.channelId = null;
            message('이전 개인대화에 접근할 수 없습니다. 수신 범위를 다시 선택하세요.', true);
        }
        restoreDrafts();
        els.input.value = drafts.get(session.channelId) || '';
        els.join.classList.add('hidden');
        els.chat.classList.remove('hidden');
        updateRoom(); updateChannel(); renderChannels(); renderMembers(); renderPending();
        persist();
        if (session.channelId) connectWS();
    }
    function disconnectSocket() {
        clearTimeout(session.reconnectTimer);
        session.reconnectTimer = null;
        session.wsGen++;
        session.connected = false;
        const old = session.ws;
        session.ws = null;
        if (old) old.close();
    }
    function cleanupAll() {
        session.closing = true;
        disconnectSocket();
        cancelCapture();
        stopPlayback();
        activeUploadController?.abort();
        persist();
        updateGating();
    }
    function scheduleReconnect(gen) {
        if (session.closing || session.mutation || !activeRoom() || gen !== session.wsGen) return;
        clearTimeout(session.reconnectTimer);
        if (++session.reconnectAttempts > 6) {
            els.gap.textContent = '재접속할 수 없습니다. 연결을 확인한 뒤 다시 연결해주세요.';
            els.retry.classList.remove('hidden');
            return;
        }
        const delay = Math.min(30000, 1000 * 2 ** (session.reconnectAttempts - 1));
        session.reconnectTimer = setTimeout(async () => {
            if (gen !== session.wsGen || session.closing || session.mutation) return;
            try {
                const response = await request(roomPath(session.room.id, 'status'));
                const data = await response.json();
                if (gen !== session.wsGen || session.closing) return;
                session.room = data.room; session.participant = data.participant; session.engine = data.engine;
                if (!activeRoom()) return roomClosed();
                connectWS();
            } catch (error) {
                if (gen !== session.wsGen || session.closing) return;
                if (error.httpStatus === 401 || error.httpStatus === 403) {
                    els.gap.textContent = '참여 인증이 만료됐습니다. 초대 링크로 다시 참여하세요.';
                    els.retry.classList.add('hidden');
                    return;
                }
                scheduleReconnect(gen);
            }
        }, delay);
    }
    function roomClosed() {
        disconnectSocket();
        cancelCapture('방이 종료되어 녹음을 중단했습니다.');
        stopPlayback();
        els.gap.classList.remove('hidden');
        els.gap.textContent = '이 방은 종료됐습니다. 접수된 기록은 내보내기로 조회할 수 있습니다.';
        els.retry.classList.add('hidden');
        updateRoom(); updateChannel(); renderMembers();
    }
    function connectWS() {
        if (session.closing || !session.channelId || !session.room || session.mutation) return;
        disconnectSocket();
        cancelCapture();
        stopPlayback();
        const gen = session.wsGen;
        const sc = scope();
        els.gap.classList.remove('hidden');
        els.gap.textContent = '선택한 대화의 기록을 다시 연결하고 있습니다. 마이크는 직접 켜야 합니다.';
        els.retry.classList.remove('hidden');
        updateGating();
        if (!activeRoom()) return roomClosed();
        const socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/classroom/events');
        session.ws = socket;
        socket.onopen = () => {
            if (gen !== session.wsGen || !selected(sc) || session.closing) return socket.close();
            socket.send(JSON.stringify({ token: sc.token, channelId: sc.channelId }));
        };
        socket.onmessage = event => {
            if (gen !== session.wsGen || !selected(sc) || session.closing) return;
            let msg;
            try { msg = JSON.parse(event.data); } catch (_) { return message('수신 데이터를 읽지 못했습니다.', true); }
            if (msg.type === 'snapshot') {
                if (msg.channel?.id !== sc.channelId || msg.room?.id !== sc.roomId || msg.participant?.id !== sc.memberId) return socket.close();
                session.room = msg.room;
                session.participant = msg.participant;
                updateStoredChannel(msg.channel);
                session.lines.clear();
                for (const line of msg.lines || []) {
                    if (line.sessionId !== sc.channelId) continue;
                    upsert(line, false);
                    if (line.audio?.[sc.language]) rememberHeard(audioKey(line));
                }
                session.connected = true;
                session.reconnectAttempts = 0;
                els.gap.classList.add('hidden'); els.retry.classList.add('hidden');
                updateRoom(); updateChannel(); renderChannels(); renderMembers(); renderLines();
                if (!activeRoom()) return roomClosed();
                if (session.room.gapCount) message('운영 PC 복구 이력 ' + session.room.gapCount + '회. 접수된 최신 기록을 복원했습니다. 마이크는 직접 켜주세요.');
                renderPending();
                persist();
                pumpUploads();
            } else if (msg.type === 'line' && msg.line?.sessionId === sc.channelId) {
                upsert(msg.line, true);
                scheduleRender();
            } else if (msg.type === 'channel' && msg.channel?.id === sc.channelId) {
                updateStoredChannel(msg.channel);
                updateChannel(); renderMembers();
            } else if (msg.type === 'room' && msg.room?.id === sc.roomId) {
                session.room = msg.room;
                if (!activeRoom()) return roomClosed();
                updateRoom(); updateChannel(); renderMembers();
            } else if (msg.type === 'channelsChanged') {
                fetchChannels().catch(error => message('대화 목록 갱신 실패: ' + error.message, true));
            } else if (msg.type === 'error') message(msg.error || msg.message || '대화 처리 오류가 발생했습니다.', true);
        };
        socket.onclose = () => {
            if (gen !== session.wsGen || session.closing) return;
            session.connected = false;
            cancelCapture('연결이 끊겨 현재 녹음을 중단했습니다. 접수 대기 자료는 기존 대화에 보관됩니다.');
            stopPlayback();
            updateGating();
            els.gap.classList.remove('hidden');
            els.gap.textContent = '연결이 끊겼습니다. 기존 대화로 재접속 중이며 마이크는 꺼져 있습니다.';
            scheduleReconnect(gen);
        };
        socket.onerror = () => { /* onclose owns reconnection and cleanup. */ };
    }
    function updateStoredChannel(c) {
        const index = session.channels.findIndex(existing => existing.id === c.id);
        if (index < 0) session.channels.push(c);
        else session.channels[index] = c;
    }
    function upsert(line, autoplay) {
        const previous = session.lines.get(line.id);
        if (previous && Number(previous.revision) > Number(line.revision)) return;
        session.lines.set(line.id, line);
        if (session.lines.size > 200) {
            const oldest = Array.from(session.lines.values()).sort((a, b) => a.sequence - b.sequence)[0];
            session.lines.delete(oldest.id);
        }
        const key = audioKey(line);
        if (autoplay && els.autoplay.checked && line.audio?.[session.participant.language] && !heard.has(key)) {
            rememberHeard(key);
            queueAudio(line);
        }
    }
    function audioKey(line) { return session.channelId + ':' + session.participant.language + ':' + line.id + ':' + (line.audio?.[session.participant.language] || ''); }
    function rememberHeard(key) {
        heard.add(key);
        if (heard.size > 512) heard.delete(heard.values().next().value);
    }
    function scheduleRender() {
        if (historyFrame !== null) return;
        historyFrame = requestAnimationFrame(() => { historyFrame = null; renderLines(); });
    }
    function renderLines() {
        const nearBottom = els.history.scrollHeight - els.history.scrollTop - els.history.clientHeight < 120;
        els.history.replaceChildren();
        const lines = Array.from(session.lines.values()).filter(l => l.sessionId === session.channelId).sort((a, b) => a.sequence - b.sequence);
        if (!lines.length) els.history.append(node('div', '선택한 대화에 아직 문장이 없습니다. 발언권을 요청한 뒤 말하거나 입력하세요.', 'empty-state'));
        for (const line of lines) {
            const card = node('article', undefined, 'line' + (line.speakerId === session.participant.id ? ' own' : ''));
            const when = line.capturedAt ? new Date(line.capturedAt).toLocaleTimeString() : '';
            card.append(node('div', (line.speakerName || '참여자') + ' · ' + (line.role === 'teacher' ? '강사' : '학생') + ' · ' + languageName(line.sourceLanguage) + ' ' + when, 'line-meta'));
            const lang = session.participant.language;
            card.append(node('div', line.translations?.[lang] || (line.sourceLanguage === lang && line.sourceText) ||
                (line.sourceText ? '번역 처리 중…' : '음성 인식 처리 중…'), 'line-text'));
            if (line.sourceText && line.sourceLanguage !== lang) card.append(node('div', '원문: ' + line.sourceText, 'line-source'));
            if (line.errors?.[lang]) card.append(node('div', '처리 상태: ' + line.errors[lang], 'error'));
            if (line.audio?.[lang]) card.append(button('내 언어 음성 듣기', () => queueAudio(line), 'btn-play'));
            else card.append(node('small', session.engine?.ttsReady ? '음성 준비 중 또는 생성되지 않은 문장' : '서버 음성이 준비되지 않았습니다. 자막을 확인하세요.', 'voice-unavailable'));
            els.history.append(card);
        }
        $('history-summary').textContent = '화면의 최근 ' + lines.length + '문장 · 전체 접수 기록은 내보내기';
        if (nearBottom || lines.length < 5) els.history.scrollTop = els.history.scrollHeight;
    }

    async function requestFloor(action, targetId = null) {
        if (!session.connected || !activeRoom() || session.mutation) return;
        const sc = scope();
        try {
            if (action === 'release') {
                await endCapture();
                await pumpUploads();
                if (voiceUploads.some(p => p.sc.channelId === sc.channelId)) return message('이 대화의 미접수 음성을 다시 전송하거나 폐기한 뒤 발언권을 놓을 수 있습니다.', true);
            }
            const c = await jsonRequest(roomPath(sc.roomId, 'floor'), { action, channelId: sc.channelId, participantId: targetId }, sc);
            if (!sameOwner(sc)) return;
            updateStoredChannel(c);
            if (c.id === session.channelId) updateChannel();
            message(action === 'release' ? '발언권 또는 대기 요청을 취소했습니다.' : c.floor?.participantId === session.participant.id ? '발언권을 받았습니다. 마이크는 누르고 있는 동안만 켜집니다.' : '발언 요청을 대기열에 등록했습니다.');
        } catch (error) { message('발언권 요청 실패: ' + error.message, true); }
    }
    async function retryNetwork(operation, sc) {
        for (let attempt = 0; attempt < 3; attempt++) {
            if (!sameOwner(sc) || sc.language !== session.participant.language) throw new Error('참여 정보 또는 입력 언어가 바뀌어 전송을 보류했습니다.');
            try { return await operation(); }
            catch (error) {
                if (error.httpStatus || attempt === 2 || session.closing) throw error;
            }
        }
    }
    async function sendText() {
        if (!canInput()) return;
        storeDraft();
        const original = els.input.value;
        const text = original.trim();
        if (!text) return;
        if (new TextEncoder().encode(text).length > 8192) return message('문자는 UTF-8 기준 8192바이트 이하로 입력하세요.', true);
        if (textPending.has(session.channelId)) return message('이 대화의 이전 문자가 미접수 상태입니다. 같은 요청을 재시도하거나 폐기하세요.', true);
        if (textPending.size >= 8) return message('미접수 문자 8개를 먼저 처리하세요.', true);
        const item = { id: id(), text, original, version: draftVersions.get(session.channelId), sc: scope(), state: 'queued', error: '' };
        textPending.set(item.sc.channelId, item);
        persist(); renderPending();
        await deliverText(item);
    }
    async function deliverText(item) {
        if (item.state === 'sending' || session.mutation || !sameOwner(item.sc)) return;
        item.state = 'sending'; item.error = '';
        persist(); renderPending(); updateGating();
        try {
            const line = await retryNetwork(() => jsonRequest(roomPath(item.sc.roomId, 'text'),
                { text: item.text, requestId: item.id, channelId: item.sc.channelId }, item.sc), item.sc);
            if (!sameOwner(item.sc)) return;
            textPending.delete(item.sc.channelId);
            if ((draftVersions.get(item.sc.channelId) || 0) === item.version && drafts.get(item.sc.channelId) === item.original) {
                drafts.set(item.sc.channelId, '');
                if (selected(item.sc)) els.input.value = '';
            }
            if (selected(item.sc) && line.sessionId === item.sc.channelId) { upsert(line, false); scheduleRender(); }
            fetchChannels().catch(() => {}); // Accepted input renews the server floor lease.
            message('문자를 서버에 접수했습니다.');
        } catch (error) {
            item.state = 'failed'; item.error = error.message;
            message('문자 접수 확인 실패. 원문과 같은 요청 ID를 보관했습니다.', true);
        } finally { persist(); renderPending(); updateGating(); }
    }

    function encodeWAV(samples) {
        const buffer = new ArrayBuffer(44 + samples.length * 2);
        const view = new DataView(buffer);
        const text = (offset, value) => { for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i)); };
        text(0, 'RIFF'); view.setUint32(4, 36 + samples.length * 2, true); text(8, 'WAVE'); text(12, 'fmt ');
        view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
        view.setUint32(24, 16000, true); view.setUint32(28, 32000, true); view.setUint16(32, 2, true);
        view.setUint16(34, 16, true); text(36, 'data'); view.setUint32(40, samples.length * 2, true);
        for (let i = 0; i < samples.length; i++) view.setInt16(44 + i * 2, samples[i], true);
        return new Blob([buffer], { type: 'audio/wav' });
    }
    function enqueueVoice(a) {
        if (!a.used) return true;
        if (!a.voiced) { a.used = 0; a.voiced = false; return true; }
        if (voiceUploads.length >= 8) {
            message('음성 대기열 8구간이 가득 차 추가 수음을 중단했습니다. 보관된 구간을 먼저 처리하세요.', true);
            return false;
        }
        const pcm = a.samples.slice(0, a.used);
        voiceUploads.push({ id: id(), sc: a.sc, blob: encodeWAV(pcm), samples: pcm.length, state: 'queued', error: '' });
        a.used = 0; a.voiced = false;
        persist(); renderPending(); pumpUploads();
        return true;
    }
    async function pumpUploads() {
        if (uploadPump) return uploadPump;
        if (session.closing) return;
        uploadPump = (async () => {
            while (!session.closing) {
                const item = voiceUploads.find(p => p.state === 'queued' && sameOwner(p.sc));
                if (!item) break;
                item.state = 'sending'; renderPending(); updateGating();
                activeUploadController = new AbortController();
                const controller = activeUploadController;
                try {
                    const receipt = await retryNetwork(async () => {
                        const response = await request(roomPath(item.sc.roomId, 'audio'), {
                            method: 'POST', headers: { 'Content-Type': 'audio/wav', 'X-Channel-ID': item.sc.channelId, 'X-Request-ID': item.id },
                            body: item.blob, signal: controller.signal
                        }, item.sc, 8000);
                        return response.json();
                    }, item.sc);
                    if (!sameOwner(item.sc)) throw new Error('현재 참여에서 벗어나 접수 확인을 보류했습니다.');
                    const index = voiceUploads.indexOf(item);
                    if (index >= 0) voiceUploads.splice(index, 1);
                    if (receipt.sourceLanguage && receipt.sourceLanguage !== item.sc.language) message('서버 음성 입력 언어가 예상과 다릅니다. 관리자에게 확인하세요.', true);
                    else if (selected(item.sc)) message('음성 ' + (item.samples / 16000).toFixed(1) + '초를 서버에 접수했습니다. 자막 처리는 별도로 진행됩니다.');
                    fetchChannels().catch(() => {});
                } catch (error) {
                    item.state = 'failed'; item.error = error.message;
                    message('음성 접수 확인 실패. 이 창에 원음과 같은 요청 ID를 보관했습니다. 대기 목록에서 재시도하세요.', true);
                } finally {
                    if (activeUploadController === controller) activeUploadController = null;
                    persist(); renderPending(); updateGating();
                }
            }
        })();
        try { await uploadPump; }
        finally { uploadPump = null; }
    }
    function renderPending() {
        els.pending.replaceChildren();
        const currentVoice = voiceUploads.filter(p => p.sc.channelId === session.channelId && sameOwner(p.sc));
        const currentText = textPending.get(session.channelId);
        const total = voiceUploads.length + textPending.size;
        els.pendingSummary.textContent = total ? '서버 접수 대기 ' + total + '개 · 다른 대화 자료는 해당 대화에서 확인' : '미접수 자료 없음';
        const renderItem = (item, isVoice) => {
            const row = node('li', (isVoice ? '음성 ' + (item.samples / 16000).toFixed(1) + '초' : '문자') + ' · ' +
                (item.state === 'sending' ? '접수 확인 중' : item.state === 'queued' ? '전송 대기' : '재시도 필요'), 'pending-item');
            if (item.error) row.append(node('small', item.error, 'error'));
            if (!isVoice) row.append(node('small', item.text.slice(0, 120), 'pending-text'));
            if (item.state === 'failed') {
                row.append(button('같은 요청 재시도', () => {
                    if (session.mutation || !sameOwner(item.sc)) return;
                    if (item.sc.language !== session.participant.language) return message('이 자료의 입력 언어로 돌아간 뒤 재시도할 수 있습니다.', true);
                    if (isVoice) { item.state = 'queued'; item.error = ''; pumpUploads(); }
                    else deliverText(item);
                    renderPending(); persist();
                }));
                row.append(button('미접수 자료 폐기', () => {
                    if (isVoice) voiceUploads.splice(voiceUploads.indexOf(item), 1);
                    else textPending.delete(item.sc.channelId);
                    persist(); renderPending(); updateGating();
                    message('이 창의 미접수 자료를 폐기했습니다. 이미 서버에 접수된 기록은 삭제되지 않습니다.');
                }));
            }
            els.pending.append(row);
        };
        if (currentText) renderItem(currentText, false);
        currentVoice.forEach(item => renderItem(item, true));
    }

    function stopTracks(a) {
        if (!a) return;
        a.stream?.getTracks().forEach(track => track.stop());
        try { a.source?.disconnect(); } catch (_) {}
    }
    function disposeCapture(a) {
        stopTracks(a);
        if (!a) return;
        const wasCurrent = capture === a;
        clearTimeout(a.flushTimer);
        a.flushDone?.();
        try { a.worklet?.disconnect(); a.worklet?.port.close(); } catch (_) {}
        if (a.context && a.context.state !== 'closed') a.context.close().catch(() => {});
        if (wasCurrent) {
            capture = null;
            els.mic.classList.remove('recording');
            els.mic.setAttribute('aria-pressed', 'false');
            els.meter.value = 0;
        }
    }
    function cancelCapture(reason) {
        captureGen++;
        const a = capture;
        if (!a) return;
        a.state = 'cancelled';
        disposeCapture(a);
        els.micStatus.textContent = '마이크 꺼짐';
        if (reason && a.used) message(reason + ' 아직 접수되지 않은 마지막 수음 구간은 전송하지 않았습니다.', true);
    }
    function validAttempt(a) {
        return capture === a && a.gen === captureGen && sameOwner(a.sc) && selected(a.sc) &&
            a.sc.wsGen === session.wsGen && session.connected && activeRoom();
    }
    async function startCapture(event) {
        if (event?.type === 'keydown' && (event.repeat || document.activeElement !== els.mic)) return;
        if (event?.type === 'pointerdown' && (event.button !== 0 || !event.isPrimary)) return;
        event?.preventDefault();
        if (!canInput() || capture || voiceUploads.length >= 7) return;
        if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) return message('마이크는 HTTPS 또는 이 PC의 localhost에서 사용할 수 있습니다.', true);
        const a = {
            gen: ++captureGen, sc: scope(), state: 'starting', samples: new Int16Array(64000),
            used: 0, voiced: false, pointerId: event?.pointerId, key: event?.type === 'keydown' ? event.code : null
        };
        capture = a;
        stopPlayback();
        updateGating();
        els.micStatus.textContent = '마이크 권한 확인 중… 놓으면 취소됩니다.';
        if (a.pointerId !== undefined) { try { els.mic.setPointerCapture(a.pointerId); } catch (_) {} }
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
            a.stream = stream;
            if (!validAttempt(a) || !ownsFloor()) return disposeCapture(a);
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            a.context = new AudioContextClass(); // Worklet resamples actual 44.1/48 kHz source rate.
            await a.context.audioWorklet.addModule('audio-worklet.js');
            if (!validAttempt(a) || !ownsFloor()) return disposeCapture(a);
            await a.context.resume();
            if (!validAttempt(a) || !ownsFloor()) return disposeCapture(a);
            a.source = a.context.createMediaStreamSource(stream);
            a.worklet = new AudioWorkletNode(a.context, 'pcm-worklet', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
            a.worklet.port.onmessage = ({ data }) => {
                if (capture !== a || a.gen !== captureGen || !['recording', 'stopping'].includes(a.state)) return;
                if (data.type === 'flushed' && data.id === a.flushId) return a.flushDone?.();
                if (data.type !== 'pcm' || !(data.samples instanceof Int16Array)) return;
                els.meter.value = Math.min(1, data.rms * 8);
                let offset = 0;
                while (offset < data.samples.length) {
                    const count = Math.min(64000 - a.used, data.samples.length - offset);
                    a.samples.set(data.samples.subarray(offset, offset + count), a.used);
                    a.used += count; offset += count;
                    if (data.rms >= 0.004) a.voiced = true;
                    if (a.used === 64000) {
                        if (!enqueueVoice(a)) return cancelCapture('음성 대기열 한도에 도달했습니다.');
                        if (voiceUploads.length >= 7 && a.state === 'recording') {
                            message('미접수 음성이 쌓여 마이크를 중단합니다. 대기 목록을 먼저 처리하세요.', true);
                            endCapture();
                        }
                    }
                }
            };
            a.state = 'recording';
            a.source.connect(a.worklet); a.worklet.connect(a.context.destination);
            els.mic.classList.add('recording');
            els.mic.setAttribute('aria-pressed', 'true');
            els.micStatus.textContent = '수음 중 · 최대 4초 구간으로 서버에 접수';
            updateGating();
        } catch (error) {
            const wasCurrent = capture === a;
            disposeCapture(a);
            if (wasCurrent) { els.micStatus.textContent = '마이크 꺼짐'; message('마이크를 준비하지 못했습니다: ' + error.message, true); }
        }
    }
    async function endCapture(event) {
        const a = capture;
        if (!a) return;
        if (event?.type === 'pointerup' && a.pointerId !== event.pointerId) return;
        if (event?.type === 'keyup' && a.key !== event.code) return;
        event?.preventDefault();
        if (a.state === 'starting') return cancelCapture('권한 대기 중 마이크 요청을 취소했습니다.');
        if (a.state === 'stopping') return a.finishPromise;
        if (a.state !== 'recording') return;
        a.state = 'stopping';
        stopTracks(a); // Synchronous: no network wait can keep the microphone active.
        els.mic.classList.remove('recording'); els.mic.setAttribute('aria-pressed', 'false');
        els.micStatus.textContent = '마이크 꺼짐 · 마지막 구간 접수 준비';
        a.finishPromise = (async () => {
            a.flushId = id();
            let flushed = false;
            await new Promise(resolve => {
                a.flushDone = () => { flushed = true; clearTimeout(a.flushTimer); resolve(); };
                a.flushTimer = setTimeout(resolve, 1200);
                a.worklet.port.postMessage({ type: 'flush', id: a.flushId });
            });
            if (capture === a && a.gen === captureGen && sameOwner(a.sc)) {
                enqueueVoice(a);
                if (!flushed) message('수음은 중단됐지만 마지막 오디오 프레임 확인이 지연됐습니다. 접수 기록을 확인하세요.', true);
                disposeCapture(a);
            }
            if (!capture) els.micStatus.textContent = '마이크 꺼짐';
            updateGating();
            playNextAudio();
        })();
        return a.finishPromise;
    }

    function stopPlayback() {
        playback.gen++;
        playback.abort?.abort();
        playback.abort = null;
        if (playback.audio) {
            playback.audio.onended = null; playback.audio.onerror = null;
            playback.audio.pause(); playback.audio.removeAttribute('src'); playback.audio.load();
        }
        playback.finish?.();
        if (playback.url) URL.revokeObjectURL(playback.url);
        playback.audio = null; playback.url = null; playback.finish = null;
        playback.queue = []; playback.busy = false;
    }
    function queueAudio(line) {
        if (line.sessionId !== session.channelId || !session.connected) return;
        const file = line.audio?.[session.participant.language];
        if (!file) return;
        if (playback.queue.length + Number(playback.busy) >= 8) {
            els.voiceStatus.textContent = '음성 대기 8개로 자동재생을 보류합니다. 필요한 문장의 듣기를 눌러주세요.';
            return;
        }
        playback.queue.push({
            sc: scope(), gen: playback.gen,
            url: roomPath(session.room.id, 'audio/' + encodeURIComponent(file)) + '?channelId=' + encodeURIComponent(session.channelId)
        });
        playNextAudio();
    }
    async function playNextAudio() {
        if (playback.busy || capture || !playback.queue.length || session.closing) return;
        const item = playback.queue.shift();
        if (item.gen !== playback.gen || !selected(item.sc)) return playNextAudio();
        playback.busy = true; // Guard while fetch is pending, before an Audio exists.
        const controller = new AbortController();
        playback.abort = controller;
        let objectURL = null;
        let audio = null;
        try {
            const response = await request(item.url, { signal: controller.signal }, item.sc);
            const blob = await response.blob();
            if (item.gen !== playback.gen || !selected(item.sc) || session.closing) return;
            objectURL = URL.createObjectURL(blob);
            audio = new Audio(objectURL);
            playback.audio = audio; playback.url = objectURL;
            els.voiceStatus.textContent = '서버가 생성한 ' + languageName(item.sc.language) + ' 음성 재생 중';
            await new Promise((resolve, reject) => {
                playback.finish = resolve;
                audio.onended = resolve;
                audio.onerror = () => reject(new Error('음성 파일을 재생하지 못했습니다.'));
                audio.play().catch(reject);
            });
        } catch (error) {
            if (item.gen === playback.gen) els.voiceStatus.textContent = '음성 재생을 시작하지 못했습니다. 문장의 듣기 버튼을 눌러주세요.';
        } finally {
            if (audio) { audio.onended = null; audio.onerror = null; audio.pause(); }
            if (objectURL) URL.revokeObjectURL(objectURL);
            if (item.gen === playback.gen) {
                playback.busy = false; playback.audio = null; playback.url = null; playback.abort = null; playback.finish = null;
                playNextAudio();
            }
        }
    }

    async function changeLanguage() {
        const next = els.language.value;
        const old = session.participant.language;
        if (next === old || session.mutation) return;
        session.mutation = true; updateGating(); renderChannels();
        try {
            await endCapture();
            stopPlayback();
            await pumpUploads();
            if (voiceUploads.length || textPending.size) throw new Error('이전 언어의 미접수 문자·음성을 재시도하거나 폐기한 뒤 언어를 변경하세요.');
            const sc = scope();
            disconnectSocket();
            const participant = await jsonRequest(roomPath(sc.roomId, 'language'), { language: next }, sc);
            if (!sameOwner(sc)) return;
            session.participant = participant;
            session.lines.clear(); els.history.replaceChildren();
            persist();
            message('입력·수신 언어를 ' + languageName(participant.language) + '로 변경했습니다. 마이크는 다시 눌러주세요.');
        } catch (error) {
            els.language.value = old;
            message('언어를 바꾸지 못했습니다: ' + error.message, true);
        } finally {
            session.mutation = false;
            updateRoom(); renderChannels(); updateGating();
            connectWS();
        }
    }
    async function exportHistory() {
        if (!session.channelId || !session.participant) return;
        const sc = scope();
        const format = els.exportFormat.value === 'json' ? 'json' : 'txt';
        els.export.disabled = true;
        let url;
        try {
            const response = await request(roomPath(sc.roomId, 'export') + '?channelId=' + encodeURIComponent(sc.channelId) + '&format=' + format, {}, sc, 60000);
            const blob = await response.blob();
            if (!selected(sc)) return message('대화가 바뀌어 내보내기를 취소했습니다.');
            url = URL.createObjectURL(blob);
            const link = node('a'); link.href = url; link.download = 'MCastTalk-' + sc.channelId + '.' + format;
            document.body.append(link); link.click(); link.remove();
            message('지정된 대화의 서버 접수 기록을 내보냈습니다. 다른 개인대화는 포함하지 않습니다.');
        } catch (error) { message('내보내기 실패: ' + error.message, true); }
        finally { if (url) setTimeout(() => URL.revokeObjectURL(url), 1000); els.export.disabled = false; }
    }
    async function saveContext() {
        if (els.saveNotes.disabled) return;
        const sc = scope(); const notes = els.notes.value;
        if (Array.from(notes).length > 512) return message('문맥 메모는 512자 이하로 입력하세요.', true);
        try {
            const result = await jsonRequest(roomPath(sc.roomId, 'context'), { channelId: sc.channelId, notes }, sc);
            if (!sameOwner(sc)) return;
            updateStoredChannel(result);
            noteDrafts.delete(sc.channelId);
            if (selected(sc)) updateChannel();
            message('선택한 대화에만 문맥 메모를 저장했습니다.');
        } catch (error) { message('문맥 메모 저장 실패: ' + error.message, true); }
    }

    // Handlers are registered before asynchronous bootstrap, including restored sessions.
    els.menu.addEventListener('click', () => setSidebar(!els.sidebar.classList.contains('open')));
    els.overlay.addEventListener('click', () => setSidebar(false));
    els.settings.addEventListener('click', () => {
        const open = els.settingsMenu.classList.toggle('hidden') === false;
        els.settings.setAttribute('aria-expanded', String(open));
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape') { setSidebar(false); els.settingsMenu.classList.add('hidden'); els.settings.setAttribute('aria-expanded', 'false'); }
    });
    els.input.addEventListener('input', storeDraft);
    els.input.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); sendText(); }
    });
    els.send.addEventListener('click', sendText);
    els.request.addEventListener('click', () => requestFloor('request'));
    els.release.addEventListener('click', () => requestFloor('release'));
    els.mic.addEventListener('pointerdown', startCapture);
    window.addEventListener('pointerup', endCapture);
    els.mic.addEventListener('pointercancel', () => cancelCapture('마이크 동작이 취소됐습니다.'));
    els.mic.addEventListener('lostpointercapture', () => { if (capture?.state !== 'stopping') cancelCapture(); });
    els.mic.addEventListener('keydown', event => { if (event.code === 'Space' || event.code === 'Enter') startCapture(event); });
    window.addEventListener('keyup', event => { if (capture?.key === event.code) endCapture(event); });
    window.addEventListener('blur', () => { cancelCapture(); stopPlayback(); });
    document.addEventListener('visibilitychange', () => { if (document.hidden) { cancelCapture(); stopPlayback(); } });
    window.addEventListener('pagehide', cleanupAll);
    window.addEventListener('pageshow', event => {
        if (event.persisted && session.participant) { session.closing = false; session.reconnectAttempts = 0; connectWS(); }
    });
    window.addEventListener('beforeunload', event => {
        if (!voiceUploads.length) return;
        event.preventDefault(); event.returnValue = '';
    });
    els.language.addEventListener('change', changeLanguage);
    els.export.addEventListener('click', exportHistory);
    els.notes.addEventListener('input', () => {
        noteDrafts.set(session.channelId, els.notes.value);
        els.notesCount.textContent = Array.from(els.notes.value).length + ' / 512자';
    });
    els.saveNotes.addEventListener('click', saveContext);
    els.autoplay.addEventListener('change', () => {
        persist();
        if (!els.autoplay.checked) stopPlayback();
        els.voiceStatus.textContent = els.autoplay.checked ? '새로 도착한 내 언어 서버 음성을 재생합니다. 브라우저가 차단하면 문장의 듣기를 눌러주세요.' : '자동재생 꺼짐 · 문장별 듣기는 사용할 수 있습니다.';
    });
    els.stopVoice.addEventListener('click', () => { stopPlayback(); els.voiceStatus.textContent = '현재 음성과 재생 대기열을 중지했습니다.'; });
    els.retry.addEventListener('click', () => { session.reconnectAttempts = 0; connectWS(); });
    els.joinRetry.addEventListener('click', () => {
        if (restoredAuth) bootstrap(restoredAuth).catch(error => showJoin('다시 연결하지 못했습니다: ' + error.message));
    });
    els.mode.addEventListener('change', async () => {
        if (!session.connected || !activeRoom() || session.participant.role !== 'teacher') return;
        const sc = scope();
        try {
            const room = await jsonRequest(roomPath(sc.roomId, 'mode'), { mode: els.mode.value }, sc);
            if (sameOwner(sc)) { session.room = room; updateRoom(); updateChannel(); message('방의 강의·자유대화 모드를 변경했습니다.'); }
        } catch (error) { updateRoom(); message('모드 변경 실패: ' + error.message, true); }
    });
    els.leave.addEventListener('click', async () => {
        cancelCapture(); stopPlayback();
        if (voiceUploads.length || textPending.size) return message('미접수 자료를 다시 전송하거나 폐기한 뒤 나가세요. 자료는 기존 대화에 보관 중입니다.', true);
        const sc = scope();
        try {
            await jsonRequest(roomPath(sc.roomId, 'leave'), {}, sc);
            cleanupAll();
            sessionStorage.removeItem('roomAuth');
            sessionStorage.removeItem(authKey());
            restoredAuth = null;
            showJoin('참여를 종료했습니다. 다시 참여하려면 새 초대 링크를 여세요.');
        } catch (error) { message('나가기 실패: ' + error.message, true); }
    });
    els.form.addEventListener('submit', async event => {
        event.preventDefault();
        if (!invite) return showJoin('교사 또는 학생 초대 링크를 먼저 여세요.');
        els.joinError.textContent = '';
        const submit = els.form.querySelector('button[type="submit"]');
        submit.disabled = true;
        try {
            const response = await request('/api/classroom/join', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ invite, name: $('join-name').value.trim(), language: $('join-language').value })
            }, { token: '' });
            const result = await response.json();
            invite = '';
            restoredAuth = { token: result.token, roomId: result.room.id, channelId: result.room.id };
            sessionStorage.setItem('roomAuth', JSON.stringify(restoredAuth));
            await bootstrap(restoredAuth);
        } catch (error) { showJoin('참여하지 못했습니다: ' + error.message); }
        finally { submit.disabled = false; }
    });
    const fitViewport = () => document.documentElement.style.setProperty('--app-height', (window.visualViewport?.height || innerHeight) + 'px');
    window.visualViewport?.addEventListener('resize', fitViewport);
    window.addEventListener('resize', fitViewport);
    fitViewport();
    setInterval(() => { if (session.participant && session.channelId) updateFloor(); }, 500);
    try { restoredAuth = JSON.parse(sessionStorage.getItem('roomAuth') || 'null'); } catch (_) { sessionStorage.removeItem('roomAuth'); }
    if (invite) showJoin('새 초대 링크로 참여합니다. 역할은 서버가 확인합니다.');
    else if (restoredAuth?.token && restoredAuth.roomId) {
        bootstrap(restoredAuth).catch(error => showJoin('이전 참여로 연결하지 못했습니다: ' + error.message + ' 다시 연결하거나 초대 링크를 여세요.'));
    } else showJoin('교사 또는 학생 초대 QR·링크를 열어 참여하세요.');
});
