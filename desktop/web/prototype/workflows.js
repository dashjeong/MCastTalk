// MVP integration: these controls only manipulate labelled local simulation data.
document.addEventListener('DOMContentLoaded', () => {
    const byId=id=>document.getElementById(id);
    const prefIds=['participant-name','role-select','language-select','device-select','room-select','show-original','caption-only','history-language','ram-selector','npu-selector'];
    function remember() {
        prefIds.forEach(id=>{const el=byId(id); if(el)state.preferences[id]=el.type==='checkbox'?el.checked:el.value;});
        saveState();
    }
    function updateRoom() {
        const select=byId('room-select');
        select.replaceChildren();
        state.rooms.forEach(room=>{const option=document.createElement('option');option.value=room.id;option.textContent=room.title+' (시연)';select.append(option);});
        select.value=state.preferences['room-select']||'room-1';
        if(!select.value)select.value=state.rooms[0].id;
        renderAllHistory();
    }
    function updateParticipant() {
        byId('participant-frame').classList.toggle('pc-view',byId('device-select').value==='pc');
        byId('in-room-language').value=byId('language-select').value;
        byId('p-room-name').textContent=(byId('room-select').selectedOptions[0]?.textContent||'대화방')+' · '+byId('participant-name').value+' · '+byId('role-select').selectedOptions[0].textContent;
    }
    updateRoom(); updateParticipant();
    prefIds.forEach(id=>{
        byId(id)?.addEventListener('change',()=>{remember();updateParticipant();renderAllHistory();});
    });
    byId('participant-name').addEventListener('input',remember);
    byId('btn-join-room').addEventListener('click',()=>{remember();updateParticipant();});
    byId('in-room-language').addEventListener('change',()=>{
        byId('language-select').value=byId('in-room-language').value;
        byId('language-select').dispatchEvent(new Event('change'));
    });
    byId('participant-send').addEventListener('click',()=>{
        const input=byId('participant-text');
        const text=input.value.trim();
        if(!text)return;
        triggerCustom(text.slice(0,2000),byId('participant-name').value,byId('role-select').value,byId('language-select').value);
        input.value='';
    });
    byId('create-room').addEventListener('click',()=>{
        const title=byId('new-room-title').value.trim();
        if(!title) {byId('room-create-status').textContent='방 이름을 입력하세요.';return;}
        if(state.rooms.length>=30) {byId('room-create-status').textContent='목업 방 상한30개입니다.';return;}
        const room={id:'room-'+crypto.randomUUID(),title:title.slice(0,80)};
        state.rooms.push(room);state.preferences['room-select']=room.id;
        const saved=saveState();updateRoom();updateParticipant();
        byId('room-create-status').textContent=saved?'시연 방을 만들고 이 브라우저에 저장했습니다.':'임시 시연 방 · 저장 실패';
        byId('new-room-title').value='';
    });
    renderRecoveryView();

    function modelControls(card,id) {
        const output=document.createElement('p');output.className='note';output.setAttribute('role','status');
        const group=document.createElement('div');group.className='mock-model-actions';
        function action(text,handler) {const button=document.createElement('button');button.className='secondary-btn small';button.textContent=text;button.addEventListener('click',handler);group.append(button);}
        action('다운로드 시연',()=>{output.textContent='다운로드/해시 확인 단계 시연 중 · 실제 파일 전송 없음';setTimeout(()=>{output.textContent='모의 다운로드 완료 · 실제 파일/해시는 검증하지 않았습니다.';},500);});
        const file=document.createElement('input');file.type='file';file.accept='.gguf,.bin';file.hidden=true;file.setAttribute('aria-label',id+' 모의 모델 파일');
        file.addEventListener('change',()=>{output.textContent=file.files[0]?'선택 파일 '+file.files[0].name+' · 가져오기 시연. 파일 내용/해시/실행 검증 전.':'파일 선택 취소';});
        action('가져오기 시연',()=>file.click());group.append(file);
        action('성능 측정 준비',()=>{output.textContent='시험 계획: 실제 로드 후 STT/번역/음성/사용자 재생 지연 측정. 현재 미측정.';});
        action('이전 프로필 복원 시연',()=>{output.textContent='이전 모델 프로필 선택 시연 · 실제 추론 모델 전환은 POC에서 시험.';});
        card.append(group,output);
    }
    document.querySelectorAll('.models-grid .model-card').forEach((card,i)=>modelControls(card,'기본 모델 '+i));
    function renderRegistered() {
        const list=byId('registered-models');list.replaceChildren();
        state.models.forEach(profile=>{
            const card=document.createElement('div');card.className='h-item';
            const title=document.createElement('h4');title.textContent=profile.repo+' · 새 프로필 시연';
            const detail=document.createElement('p');detail.className='note';
            detail.textContent='GGUF / '+profile.family+' / 프롬프트 '+profile.prompt+' / '+profile.license+' / commit '+profile.revision.slice(0,12)+' / SHA256 '+profile.sha.slice(0,12)+' · 실제 모델 검증 전';
            card.append(title,detail);modelControls(card,profile.repo);list.append(card);
        });
    }
    renderRegistered();
    byId('btn-register-mock').addEventListener('click',()=>{
        const profile={};
        ['repo','revision','file','family','prompt','license','sha'].forEach(key=>{profile[key]=byId('hf-'+key).value.trim();});
        const status=byId('register-mock-status');
        if(!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(profile.repo)||!/^[0-9a-f]{40}$/i.test(profile.revision)||!/^[A-Za-z0-9_.-]+\.gguf$/i.test(profile.file)||!/^[0-9a-f]{64}$/i.test(profile.sha)||!profile.family||!profile.prompt||!profile.license) {
            status.textContent='등록 불가: repo, 고정40자리 commit, GGUF 파일명, family/프롬프트/라이선스,64자리 SHA256을 모두 입력하세요. 형식 확인만 시연합니다.';return;
        }
        if(state.models.length>=30) {status.textContent='시연 프로필 상한30개입니다.';return;}
        state.models.push(profile);
        status.textContent=saveState()?'새 프로필을 브라우저에 모의 등록했습니다. 실제 HF 조회·다운로드·해시 확인·모델 실행은 하지 않았습니다.':'프로필 저장 실패 · 임시 상태';
        renderRegistered();
    });
});
