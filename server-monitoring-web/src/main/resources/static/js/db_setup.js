// ================= DB 초기 구성 (/admin/db-setup, 관리자 전용) =================
// API : ../api/admin/db-setup/verify (비밀번호 재확인), /test, /schema, /tables, /data, /scripts
// 단계는 버튼을 눌러야만 실행 (자동 진행 없음). 입력 값이 바뀌면 [1. 연결 확인] 부터 다시
// apiRequest / escapeHtml 는 script.js, scbConfirm / scbAlert 는 scb_dialog.js
(function () {
    const API = '../api/admin/db-setup/';
    const $ = id => document.getElementById(id);
    const PORT = { oracle: 1521, sybase: 5000, mysql: 3306, mariadb: 3306 };
    const SCHEMA = { oracle: 'SCBMON', sybase: 'scbmon', mysql: 'scb_mon_agent', mariadb: 'scb_mon_agent', h2: 'scb_mon_agent' };
    const HINT = {
        oracle: 'Oracle : 스키마 = 사용자. 접속 아이디는 DBA (CREATE USER 권한). 스키마를 새로 만들 때는 "스키마 사용자 비밀번호" 가 필요합니다. 테이블은 DBA 로 그 스키마 안에 만듭니다.',
        sybase: 'Sybase ASE : 스키마 = database. 접속 아이디는 sa (CREATE DATABASE 권한, 기본 장치 사용). 웹 계정을 입력하면 로그인을 만들고 그 database 의 소유자로 지정합니다.',
        mysql: 'MySQL : 스키마 = database (utf8mb4). 접속 아이디는 root 등 CREATE DATABASE 권한. 웹 계정을 입력하면 그 database 의 모든 권한으로 만듭니다.',
        mariadb: 'MariaDB : MySQL 과 같은 DDL (utf8mb4). 접속 아이디는 root 등 CREATE DATABASE 권한.',
        h2: 'H2 : H2 폴더 아래 "스키마 이름.mv.db" 파일 DB 를 만듭니다. 처음 만들 때 입력한 아이디/비밀번호가 그 파일 DB 의 관리자 계정이 됩니다. 웹이 지금 쓰는 H2 파일은 고르지 마세요.'
    };
    let state = { tested: false, schemaExists: false, tablesReady: false };
    let leftTimer = null;

    function req() {
        const kind = $('dsKind').value;
        return {
            kind: kind,
            host: $('dsHost').value.trim(),
            port: $('dsPort').value ? Number($('dsPort').value) : null,
            service: $('dsService').value.trim(),
            h2Dir: $('dsH2Dir').value.trim(),
            url: $('dsUrl').value.trim(),
            user: $('dsUser').value.trim(),
            password: $('dsPassword').value,
            schema: $('dsSchema').value.trim(),
            appUser: $('dsAppUser').value.trim(),
            appPassword: $('dsAppPassword').value
        };
    }

    function log(lines, cls) {
        const box = $('dsLog');
        if (box.dataset.empty !== 'N') { box.textContent = ''; box.dataset.empty = 'N'; }
        const t = new Date().toTimeString().slice(0, 8);
        (Array.isArray(lines) ? lines : [lines]).forEach(l => {
            const div = document.createElement('div');
            div.className = cls || '';
            div.textContent = '[' + t + '] ' + l;
            box.appendChild(div);
        });
        box.scrollTop = box.scrollHeight;
    }

    function setButtons() {
        $('dsSchemaBtn').disabled = !state.tested || state.schemaExists;
        $('dsTablesBtn').disabled = !state.tested || !state.schemaExists;
        $('dsDataBtn').disabled = !state.tested || !state.schemaExists || !state.tablesReady;
    }

    function reset() {
        state = { tested: false, schemaExists: false, tablesReady: false };
        $('dsTestMsg').textContent = '입력한 계정으로 접속해 스키마 · 테이블이 있는지 확인합니다.';
        $('dsSchemaMsg').textContent = '스키마가 없을 때만 만듭니다.';
        $('dsTablesMsg').textContent = '없는 테이블만 만듭니다 (이미 있는 테이블은 건너뜀).';
        $('dsDataMsg').textContent = '기초 테이블이 비어 있을 때만 넣습니다.';
        setButtons();
    }

    function kindChanged() {
        const k = $('dsKind').value;
        document.querySelectorAll('.ds-net').forEach(e => { e.hidden = k === 'h2'; });
        document.querySelectorAll('.ds-ora').forEach(e => { e.hidden = k !== 'oracle'; });
        document.querySelectorAll('.ds-h2').forEach(e => { e.hidden = k !== 'h2'; });
        document.querySelectorAll('.ds-app').forEach(e => { e.hidden = k === 'oracle'; });
        $('dsPort').value = PORT[k] || '';
        $('dsSchema').value = SCHEMA[k];
        $('dsSchemaLabel').textContent = { oracle: '스키마(사용자) 이름', sybase: 'database 이름', h2: 'DB 파일 이름' }[k] || '스키마(database) 이름';
        $('dsAppPwLabel').textContent = k === 'oracle' ? '스키마 사용자 비밀번호 (새로 만들 때 필수)' : '웹 계정 비밀번호 (선택)';
        $('dsUserHint').textContent = k === 'h2' ? '(새 파일이면 그 관리자 계정)' : '(스키마를 만들 권한)';
        $('dsKindHint').textContent = HINT[k];
        reset();
    }

    async function call(path, button, msgId, okText) {
        const b = $(button);
        b.disabled = true;
        $(msgId).textContent = '실행 중...';
        try {
            return await apiRequest('POST', API + path, req());
        } catch (e) {
            $(msgId).innerHTML = '<span class="lg-bad">' + escapeHtml(e.message) + '</span>';
            log('실패 : ' + e.message, 'bad');
            if (/비밀번호 확인이 필요/.test(e.message)) lock();
            return null;
        } finally {
            setButtons();
            if (button === 'dsTest') b.disabled = false;
        }
    }

    async function test() {
        reset();
        const r = await call('test', 'dsTest', 'dsTestMsg');
        if (!r) return;
        state.tested = true;
        state.schemaExists = r.schemaExists;
        const ex = r.existingTables || [];
        state.tablesReady = ex.length === (r.targetTables || []).length;
        $('dsTestMsg').innerHTML = '<span class="lg-ok">접속 성공</span> ' + escapeHtml(r.product) + ' · 계정 ' + escapeHtml(r.user || '') + ' · ' + r.ms + 'ms';
        $('dsSchemaMsg').innerHTML = r.schemaExists
            ? '스키마 <b>' + escapeHtml(r.schema) + '</b> 가 이미 있습니다 → 만들지 않고 <b>3. 테이블 생성</b> 으로 진행하세요.'
            : '스키마 <b>' + escapeHtml(r.schema) + '</b> 가 없습니다 → [2. 스키마 생성]';
        $('dsTablesMsg').textContent = r.schemaExists
            ? '테이블 ' + (r.targetTables || []).length + '개 중 ' + ex.length + '개 있음' + (state.tablesReady ? ' - 모두 있음 (누르면 건너뜀)' : ' - 없는 ' + ((r.targetTables || []).length - ex.length) + '개를 만듭니다')
            : '스키마를 만든 뒤 실행합니다.';
        const rows = r.baseRows || {};
        const filled = Object.keys(rows).filter(k => rows[k] > 0);
        $('dsDataMsg').textContent = filled.length ? '기초 데이터가 이미 있습니다 (' + filled.map(k => k + ' ' + rows[k] + '행').join(', ') + ') - 넣지 않습니다.'
            : '기초 테이블이 비어 있을 때만 넣습니다.';
        log(['연결 확인 : ' + r.product + ' · 스키마 ' + r.schema + (r.schemaExists ? ' 있음' : ' 없음') + ' · 테이블 ' + ex.length + ' / ' + (r.targetTables || []).length]
            .concat(r.schemaNote ? [r.schemaNote] : []), 'ok');
        setButtons();
    }

    async function createSchema() {
        const p = req();
        if (!(await scbConfirm('[' + p.kind + '] ' + (p.url || p.host) + ' 에 스키마 "' + p.schema + '" 를 만듭니다. 계속할까요?', { type: 'warning' }))) return;
        const r = await call('schema', 'dsSchemaBtn', 'dsSchemaMsg');
        if (!r) return;
        state.schemaExists = true;
        $('dsSchemaMsg').innerHTML = r.created ? '<span class="lg-ok">생성 완료</span> ' + escapeHtml(r.schema) : '이미 있어서 건너뜀 - 3단계로 진행하세요.';
        log(r.log || [], 'ok');
        setButtons();
    }

    async function createTables() {
        const p = req();
        if (!(await scbConfirm('스키마 "' + p.schema + '" 에 테이블을 만듭니다 (없는 테이블만). 계속할까요?', { type: 'warning' }))) return;
        const r = await call('tables', 'dsTablesBtn', 'dsTablesMsg');
        if (!r) return;
        state.tablesReady = true;
        $('dsTablesMsg').innerHTML = '<span class="lg-ok">완료</span> 만듦 ' + r.created.length + '개 · 건너뜀 ' + r.skipped.length + '개 (' + escapeHtml(r.file) + ')';
        log((r.log || []).length ? r.log : ['만들 테이블 없음'], 'ok');
        setButtons();
    }

    async function insertData() {
        const p = req();
        if (!(await scbConfirm('스키마 "' + p.schema + '" 에 기초 데이터 (사용자 admin · 메뉴 · 서버 정보 · 설정) 를 넣습니다.\n수집 데이터와 이력은 넣지 않습니다. 계속할까요?', { type: 'warning' }))) return;
        const r = await call('data', 'dsDataBtn', 'dsDataMsg');
        if (!r) return;
        $('dsDataMsg').innerHTML = '<span class="lg-ok">완료</span> ' + escapeHtml((r.log || []).join(', '));
        log(['기초 데이터 입력 : ' + (r.log || []).join(', '), '처음 로그인 : admin / admin (데모) - 바로 비밀번호를 바꾸세요.',
            '다음 : DB 연결 관리에서 이 DB 를 등록 → [연결 시험] → [전환]'], 'ok');
        scbAlert('기초 데이터를 넣었습니다. DB 연결 관리에서 이 DB 를 등록하고 전환하면 웹이 새 DB 를 씁니다.', 'success');
    }

    // ---- 지금 웹이 쓰는 DB (읽기만)
    async function loadCurrent() {
        $('dsCur').textContent = '확인 중...';
        let r;
        try {
            r = await apiRequest('GET', API + 'current');
        } catch (e) {
            $('dsCur').innerHTML = '<span class="lg-bad">' + escapeHtml(e.message) + '</span>';
            if (/비밀번호 확인이 필요/.test(e.message)) lock();
            return;
        }
        if (!r.connected) {
            $('dsCur').innerHTML = '<span class="lg-bad">지금 DB 에 연결할 수 없습니다 : ' + escapeHtml(r.error || '') + '</span>';
            return;
        }
        const V = { OK: ['정상 - 테이블 모두 있음', 'green'], PARTIAL: ['일부 테이블 없음', 'yellow'], EMPTY: ['테이블 없음 (빈 스키마)', 'red'] };
        const v = V[r.verdict] || [r.verdict, 'gray'];
        const rows = r.baseRows || {};
        const empty = Object.keys(rows).filter(k => !rows[k]);
        $('dsCur').innerHTML = '<dl class="st-dl">' +
            '<dt>DB</dt><dd><b>' + escapeHtml(r.label) + '</b> · ' + escapeHtml(r.product) + '</dd>' +
            '<dt>접속</dt><dd><code>' + escapeHtml(r.url || '') + '</code> · 계정 ' + escapeHtml(r.user || '') + '</dd>' +
            '<dt>스키마</dt><dd>' + (r.schemaExists ? '<span class="am-chip green">있음</span> <b>' + escapeHtml(r.schema) + '</b>' : '<span class="am-chip red">없음</span>') + '</dd>' +
            '<dt>테이블</dt><dd><span class="am-chip ' + v[1] + '">' + v[0] + '</span> ' + r.present.length + ' / ' + r.targetCount + '개' +
            (r.missing.length ? '<div class="lg-bad">없는 테이블 : ' + r.missing.map(escapeHtml).join(', ') + '</div>' +
                '<div class="ua-sub">웹을 다시 시작하면 SchemaUpgrade 가 빠진 테이블을 자동으로 만듭니다. 또는 아래 단계로 이 DB 에 [3. 테이블 생성].</div>' : '') + '</dd>' +
            '<dt>기초 데이터</dt><dd>' + Object.keys(rows).map(k => escapeHtml(k) + ' ' + rows[k] + '행').join(' · ') +
            (empty.length ? '<div class="lg-bad">비어 있는 기초 테이블 : ' + empty.map(escapeHtml).join(', ') + '</div>' : '') + '</dd></dl>';
        // 관리자 화면 알림에서 [확인] 으로 왔으면 (?check=1) : 지금 DB 의 접속 정보를 단계 입력 칸에 맞추고 안내
        if (/[?&]check=1/.test(location.search) && r.verdict !== 'OK') {
            $('dsCur').closest('.spec-container').classList.add('ds-attn');
            $('dsCur').closest('.spec-container').scrollIntoView({ behavior: 'smooth', block: 'start' });
            if ($('dsKind').value !== r.kind) { $('dsKind').value = r.kind; kindChanged(); }
            if (r.schema) $('dsSchema').value = r.schema;
            log('현재 DB 에 ' + (r.missing.length ? '없는 테이블 ' + r.missing.length + '개' : '스키마 없음') +
                ' → 접속 아이디 · 비밀번호를 넣고 [1. 연결 확인] → (스키마가 있으면) [3. 테이블 생성] 을 누르세요.', 'bad');
        }
    }

    async function loadScripts() {
        try {
            const r = await apiRequest('GET', API + 'scripts');
            $('dsFiles').innerHTML = r.files.map(f => '<tr><td class="ua-left"><code>' + escapeHtml(f.name) + '</code></td><td class="ua-left"><code>' + escapeHtml(f.path) + '</code></td>' +
                '<td>' + (f.bytes / 1024).toFixed(1) + ' KB</td><td>' + f.statements + '</td>' +
                '<td><button type="button" class="svc-btn" data-view="' + escapeHtml(f.name) + '">보기</button> ' +
                '<a class="svc-btn" href="' + API + 'scripts/' + encodeURIComponent(f.name) + '" download="' + escapeHtml(f.name) + '">내려받기</a></td></tr>').join('');
            $('dsTablesInfo').innerHTML = '만드는 테이블 ' + r.tables.length + '개 : ' + r.tables.map(escapeHtml).join(', ') +
                '<br>기초 데이터 테이블 : ' + r.baseTables.map(escapeHtml).join(', ');
        } catch (e) {
            $('dsFiles').innerHTML = '<tr><td colspan="5" class="lg-bad">' + escapeHtml(e.message) + '</td></tr>';
        }
    }

    async function view(name) {
        const res = await fetch(API + 'scripts/' + encodeURIComponent(name), { credentials: 'same-origin' });
        const v = $('dsView');
        v.hidden = false;
        v.textContent = res.ok ? await res.text() : '불러오지 못했습니다 (' + res.status + ')';
        v.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    // ---- 비밀번호 재확인 (10분)
    function lock() {
        $('dsLock').hidden = false;
        $('dsPage').hidden = true;
        $('dsVerifyPw').value = '';
        $('dsVerifyPw').focus();
        if (leftTimer) { clearInterval(leftTimer); leftTimer = null; }
    }

    function unlock(leftSec) {
        $('dsLock').hidden = true;
        $('dsPage').hidden = false;
        let left = leftSec;
        const show = () => { $('dsLeft').textContent = '비밀번호 확인 남은 시간 ' + Math.floor(left / 60) + ':' + String(left % 60).padStart(2, '0'); };
        show();
        if (leftTimer) clearInterval(leftTimer);
        leftTimer = setInterval(() => {
            left--;
            if (left <= 0) { lock(); $('dsVerifyError').textContent = '10분이 지나 다시 확인이 필요합니다.'; return; }
            show();
        }, 1000);
        loadScripts();
        loadCurrent();
    }

    async function verify(e) {
        e.preventDefault();
        $('dsVerifyError').textContent = '';
        try {
            const r = await apiRequest('POST', API + 'verify', { password: $('dsVerifyPw').value });
            $('dsVerifyPw').value = '';
            unlock(r.leftSec);
        } catch (err) {
            $('dsVerifyError').textContent = err.message;
            $('dsVerifyPw').select();
        }
    }

    document.addEventListener('DOMContentLoaded', async () => {
        $('dsVerifyForm').addEventListener('submit', verify);
        $('dsKind').addEventListener('change', kindChanged);
        document.querySelectorAll('.ds-form input').forEach(i => i.addEventListener('input', reset));
        $('dsTest').addEventListener('click', test);
        $('dsCurBtn').addEventListener('click', loadCurrent);
        $('dsSchemaBtn').addEventListener('click', createSchema);
        $('dsTablesBtn').addEventListener('click', createTables);
        $('dsDataBtn').addEventListener('click', insertData);
        $('dsFiles').addEventListener('click', ev => { const b = ev.target.closest('[data-view]'); if (b) view(b.dataset.view); });
        kindChanged();
        try {
            const s = await apiRequest('GET', API + 'verify');
            if (s.verified) unlock(s.leftSec); else lock();
        } catch (e) {
            lock();
        }
    });
})();
