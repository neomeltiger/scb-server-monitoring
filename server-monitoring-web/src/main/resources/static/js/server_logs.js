// ================= 서버 로그 (/logs) =================
// API : /api/logs/{serverId} (serverlog.controller.ServerLogController)
// 웹 서버가 정한 주기마다 krcasmrx11_client 에 로그를 요청하고, 에이전트가 보낸 마지막 N줄을 보여줌
// apiRequest / escapeHtml / isAdminUser 는 script.js
(function () {
    const API = 'api/logs/';
    const AUTO_MS = 15000;
    const STATUS = {
        OK: ['정상', 'ok'], NOT_FOUND: ['파일 없음', 'warn'], DENIED: ['허용 안 됨', 'bad'], ERROR: ['오류', 'bad']
    };
    const AGENT = { NONE: '미등록', WAITING: '보고 대기', OK: '연결', STALE: '보고 끊김', LOST: '하트비트 끊김', PAUSED: '수집 중지', STOPPED: '데몬 종료' };
    const RE_ERR = /\b(ERROR|ERR|FATAL|CRIT(ICAL)?|PANIC|EMERG|ALERT|FAIL(ED|URE)?|EXCEPTION)\b|\.(err|crit|alert|emerg)\]/i;
    const RE_WARN = /\b(WARN(ING)?|NOTICE)\b|\.(warning|notice)\]/i;

    const $ = id => document.getElementById(id);
    let serverId = null;
    let data = null;           // 서버 로그 정보
    let selectedLog = null;    // 선택한 로그 ID
    let snaps = [];            // 수집 시각 목록
    let snap = null;           // 보고 있는 수집 내용
    let followLatest = true;   // 최신 수집을 따라감 (예전 시각을 고르면 해제)
    let level = 'all';
    let timer = null;

    function store(k, v) {
        try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { /* 무시 */ }
        return null;
    }

    function fmtBytes(n) {
        return n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
    }

    // ------------------------------------------------------------ 불러오기

    async function loadServer(keepSelection) {
        if (!serverId) return;
        try {
            data = await apiRequest('GET', API + serverId);
        } catch (e) {
            $('lgStatus').innerHTML = '<span class="lg-bad">' + escapeHtml(e.message) + '</span>';
            return;
        }
        renderStatus();
        renderSources();
        const ids = data.sources.map(s => s.id);
        if (!keepSelection || !ids.includes(selectedLog)) {
            const first = data.sources.find(s => s.lastDt) || data.sources[0];
            selectedLog = first ? first.id : null;
            followLatest = true;
        }
        await loadSnapshots();
    }

    async function loadSnapshots() {
        renderSourcesActive();
        if (!selectedLog) {
            snaps = []; snap = null;
            renderSnapSelect();
            renderViewer();
            return;
        }
        const prevLatest = snaps.length ? snaps[0].id : null;
        try {
            snaps = await apiRequest('GET', API + serverId + '/sources/' + selectedLog + '/snapshots');
        } catch (e) { snaps = []; }
        let target = null;
        if (snaps.length) {
            if (followLatest || !snap) target = snaps[0].id;
            else target = snaps.some(s => s.id === snap.id) ? snap.id : snaps[0].id;
        }
        renderSnapSelect(target);
        if (!target) { snap = null; renderViewer(); return; }
        if (!snap || snap.id !== target || prevLatest !== snaps[0].id) {
            try { snap = await apiRequest('GET', API + serverId + '/snapshots/' + target); } catch (e) { snap = null; }
        }
        renderViewer();
    }

    // ------------------------------------------------------------ 화면

    function renderStatus() {
        const p = data.policy, a = data.agent || {};
        const sel = $('lgInterval');
        sel.value = String(p.intervalMin);
        sel.disabled = !isAdminUser();
        const parts = [
            '<span class="lg-agent ' + escapeHtml(a.state || 'NONE') + '">에이전트 ' + escapeHtml(AGENT[a.state] || a.state || '-') + '</span>',
            '<span>마지막 요청 <b>' + escapeHtml(p.lastReqDt || '-') + '</b></span>',
            '<span>마지막 수신 <b>' + escapeHtml(p.lastRecvDt || '-') + '</b></span>',
            '<span>다음 요청 <b>' + escapeHtml(p.intervalMin === 0 ? '사용 안 함' : (p.nextReqDt || '에이전트 다음 확인 때')) + '</b></span>'
        ];
        if (p.requestPending) parts.push('<span class="lg-pending">⏳ 지금 요청 대기 중 (에이전트 확인 시 전달)</span>');
        if (a.state === 'NONE') parts.push('<span class="lg-bad">에이전트 토큰이 발급되지 않아 로그를 받을 수 없습니다. (네트워크 구성도 → 토큰 발급)</span>');
        else if (a.state === 'STALE') parts.push('<span class="lg-bad">에이전트 보고가 끊겨 요청이 전달되지 않을 수 있습니다.</span>');
        else if (a.state === 'PAUSED') parts.push('<span class="lg-bad">자료 수집이 중지되어 로그를 요청하지 않습니다. (에이전트 관리에서 [시작])</span>');
        else if (a.state === 'STOPPED') parts.push('<span class="lg-bad">에이전트 데몬이 종료되었습니다. 서버에서 시작해야 로그를 받을 수 있습니다.</span>');
        $('lgStatus').innerHTML = parts.join('');
    }

    function renderSources() {
        const admin = isAdminUser();
        $('lgSources').innerHTML = data.sources.length ? data.sources.map(s => {
            const st = STATUS[s.lastStatus];
            return '<li class="lg-source' + (s.use ? '' : ' off') + '" data-id="' + s.id + '">' +
                '<button type="button" class="lg-source-main" data-act="select" data-id="' + s.id + '">' +
                '<span class="lg-source-name">' + escapeHtml(s.name) + (s.use ? '' : ' <small>(수집 안 함)</small>') + '</span>' +
                '<code class="lg-source-path">' + escapeHtml(s.path) + '</code>' +
                '<span class="lg-source-meta">' + s.tailLines + '줄 · ' +
                (st ? '<span class="lg-chip ' + st[1] + '">' + st[0] + '</span> ' + escapeHtml(s.lastDt || '') : '<span class="lg-chip">수집 전</span>') +
                '</span></button>' +
                (admin ? '<div class="lg-source-btns"><button type="button" class="svc-btn status" data-act="edit" data-id="' + s.id + '">수정</button>' +
                    '<button type="button" class="svc-btn del" data-act="del" data-id="' + s.id + '">삭제</button></div>' : '') +
                '</li>';
        }).join('') : '<li class="lg-empty">등록된 로그 파일이 없습니다.' + (admin ? ' 아래에서 추가하세요.' : ' 관리자에게 등록을 요청하세요.') + '</li>';
        renderSourcesActive();
    }

    function renderSourcesActive() {
        document.querySelectorAll('#lgSources .lg-source').forEach(li =>
            li.classList.toggle('active', Number(li.getAttribute('data-id')) === selectedLog));
    }

    function renderSnapSelect(selected) {
        const sel = $('lgSnap');
        sel.innerHTML = snaps.length ? snaps.map((s, i) =>
            '<option value="' + s.id + '"' + (s.id === selected ? ' selected' : '') + '>' +
            escapeHtml(s.collectDt) + (i === 0 ? ' (최신)' : '') + ' · ' + (STATUS[s.status] ? STATUS[s.status][0] : s.status) + '</option>'
        ).join('') : '<option value="">수집된 내용 없음</option>';
        sel.disabled = !snaps.length;
    }

    function lineLevel(t) {
        if (RE_ERR.test(t)) return 'err';
        if (RE_WARN.test(t)) return 'warn';
        return '';
    }

    function highlight(text, q) {
        const e = escapeHtml(text);
        if (!q) return e;
        const qe = escapeHtml(q).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        return e.replace(new RegExp(qe, 'gi'), m => '<mark>' + m + '</mark>');
    }

    function renderViewer() {
        const src = data && data.sources.find(s => s.id === selectedLog);
        $('lgViewTitle').textContent = src ? src.name : '로그 내용';
        const box = $('lgViewer');
        box.classList.toggle('wrap', $('lgWrap').checked);
        if (!src) { $('lgViewMeta').textContent = ''; box.innerHTML = '<div class="lg-empty">왼쪽에서 로그를 선택하세요.</div>'; return; }
        if (!snap) {
            $('lgViewMeta').innerHTML = '<code>' + escapeHtml(src.path) + '</code>';
            box.innerHTML = '<div class="lg-empty">아직 수집된 내용이 없습니다. 요청 주기가 되면 에이전트가 보냅니다.' +
                (isAdminUser() ? ' (바로 받으려면 [지금 요청])' : '') + '</div>';
            return;
        }
        const st = STATUS[snap.status] || [snap.status, 'bad'];
        $('lgViewMeta').innerHTML = '<span class="lg-chip ' + st[1] + '">' + st[0] + '</span> ' +
            escapeHtml(snap.collectDt) + ' · ' + snap.lineCnt + '줄 · ' + fmtBytes(snap.byteCnt) + ' · <code>' + escapeHtml(src.path) + '</code>';
        const q = $('lgFilter').value.trim();
        const lines = (snap.content || '').replace(/\n$/, '').split('\n');
        const rows = [];
        let shown = 0;
        lines.forEach((t, i) => {
            const lv = lineLevel(t);
            if (level === 'err' && lv !== 'err') return;
            if (level === 'warn' && !lv) return;
            if (q && t.toLowerCase().indexOf(q.toLowerCase()) === -1) return;
            shown++;
            rows.push('<div class="lg-line ' + lv + '"><span class="lg-no">' + (i + 1) + '</span><span class="lg-text">' + highlight(t, q) + '</span></div>');
        });
        if (snap.status !== 'OK') rows.unshift('<div class="lg-line err lg-note"><span class="lg-no">!</span><span class="lg-text">에이전트 응답 : ' + st[0] + '</span></div>');
        box.innerHTML = rows.length ? rows.join('') : '<div class="lg-empty">조건에 맞는 줄이 없습니다.</div>';
        if (q || level !== 'all') $('lgViewMeta').insertAdjacentHTML('beforeend', ' · <b>' + shown + '줄 표시</b>');
        if (followLatest && !q && level === 'all') box.scrollTop = box.scrollHeight;
    }

    // ------------------------------------------------------------ 동작

    async function changeInterval() {
        const v = Number($('lgInterval').value);
        try {
            data = await apiRequest('PUT', API + serverId + '/policy', { intervalMin: v });
            renderStatus();
        } catch (e) {
            alert(e.message);
            if (data) $('lgInterval').value = String(data.policy.intervalMin);
        }
    }

    async function requestNow() {
        const btn = $('lgRequestNow');
        btn.disabled = true;
        try {
            data = await apiRequest('POST', API + serverId + '/request');
            renderStatus();
        } catch (e) { alert(e.message); }
        finally { btn.disabled = false; }
    }

    function resetForm() {
        $('lgFormId').value = '';
        $('lgName').value = '';
        $('lgPath').value = '';
        $('lgLines').value = '200';
        $('lgUse').checked = true;
        $('lgFormError').textContent = '';
        $('lgFormTitle').textContent = '로그 파일 추가';
        $('lgFormSave').textContent = '추가';
        $('lgFormCancel').hidden = true;
    }

    function editSource(id) {
        const s = data.sources.find(x => x.id === id);
        if (!s) return;
        $('lgFormId').value = s.id;
        $('lgName').value = s.name;
        $('lgPath').value = s.path;
        const opt = [...$('lgLines').options].find(o => Number(o.value) === s.tailLines);
        if (!opt) $('lgLines').insertAdjacentHTML('beforeend', '<option>' + s.tailLines + '</option>');
        $('lgLines').value = String(s.tailLines);
        $('lgUse').checked = s.use;
        $('lgFormTitle').textContent = '로그 파일 수정';
        $('lgFormSave').textContent = '저장';
        $('lgFormCancel').hidden = false;
        $('lgFormError').textContent = '';
        $('lgForm').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        $('lgName').focus();
    }

    async function saveSource(e) {
        e.preventDefault();
        const body = { name: $('lgName').value.trim(), path: $('lgPath').value.trim(), tailLines: Number($('lgLines').value), use: $('lgUse').checked };
        if (!body.name) { $('lgFormError').textContent = '이름을 입력하세요.'; return; }
        if (!/^\/[A-Za-z0-9_./-]+$/.test(body.path) || body.path.indexOf('..') !== -1) {
            $('lgFormError').textContent = '경로는 / 로 시작하는 절대 경로이며 영문, 숫자, _ . / - 만 쓸 수 있습니다. (.. 금지)';
            return;
        }
        const id = $('lgFormId').value;
        try {
            const s = id ? await apiRequest('PUT', API + serverId + '/sources/' + id, body)
                         : await apiRequest('POST', API + serverId + '/sources', body);
            resetForm();
            selectedLog = s.id;
            followLatest = true;
            await loadServer(true);
        } catch (err) {
            $('lgFormError').textContent = err.message;
        }
    }

    async function deleteSource(id) {
        const s = data.sources.find(x => x.id === id);
        if (!s || !confirm('[' + s.name + '] 로그를 삭제하시겠습니까?\n수집된 내용도 함께 삭제됩니다.')) return;
        try {
            await apiRequest('DELETE', API + serverId + '/sources/' + id);
            if (selectedLog === id) selectedLog = null;
            resetForm();
            await loadServer(true);
        } catch (e) { alert(e.message); }
    }

    function download() {
        if (!snap) return;
        const src = data.sources.find(s => s.id === selectedLog);
        const name = (data.systemName + '_' + (src ? src.path.split('/').pop() : 'log') + '_' + snap.collectDt)
            .replace(/[^A-Za-z0-9가-힣_.-]+/g, '_') + '.txt';
        const a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob([snap.content || ''], { type: 'text/plain;charset=utf-8' }));
        a.download = name;
        document.body.appendChild(a);
        a.click();
        setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
    }

    function setAuto(on) {
        if (timer) { clearInterval(timer); timer = null; }
        if (on) timer = setInterval(() => { if (!document.hidden) loadServer(true); }, AUTO_MS);
        store('lgAuto', on ? 'Y' : 'N');
    }

    // ------------------------------------------------------------ 시작

    window.addEventListener('DOMContentLoaded', function () {
        if (!$('lgServer')) return;
        if (!isAdminUser()) document.body.classList.add('role-user');
        const qs = new URLSearchParams(location.search).get('server') || store('lgServer');
        if (qs && [...$('lgServer').options].some(o => o.value === String(qs))) $('lgServer').value = String(qs);
        serverId = Number($('lgServer').value) || null;

        $('lgServer').addEventListener('change', () => {
            serverId = Number($('lgServer').value);
            store('lgServer', String(serverId));
            selectedLog = null; snap = null; snaps = [];
            resetForm();
            loadServer(false);
        });
        $('lgInterval').addEventListener('change', changeInterval);
        $('lgRequestNow').addEventListener('click', requestNow);
        $('lgReload').addEventListener('click', () => loadServer(true));
        $('lgAuto').checked = store('lgAuto') !== 'N';
        $('lgAuto').addEventListener('change', () => setAuto($('lgAuto').checked));
        $('lgSources').addEventListener('click', e => {
            const b = e.target.closest('[data-act]');
            if (!b) return;
            const id = Number(b.getAttribute('data-id'));
            if (b.getAttribute('data-act') === 'select') { selectedLog = id; followLatest = true; snap = null; loadSnapshots(); }
            else if (b.getAttribute('data-act') === 'edit') editSource(id);
            else if (b.getAttribute('data-act') === 'del') deleteSource(id);
        });
        $('lgSnap').addEventListener('change', async () => {
            const id = Number($('lgSnap').value);
            followLatest = snaps.length && snaps[0].id === id;
            try { snap = await apiRequest('GET', API + serverId + '/snapshots/' + id); } catch (e) { alert(e.message); }
            renderViewer();
        });
        $('lgFilter').addEventListener('input', renderViewer);
        document.querySelectorAll('.lg-levels .ua-tab').forEach(b => b.addEventListener('click', () => {
            level = b.getAttribute('data-level');
            document.querySelectorAll('.lg-levels .ua-tab').forEach(x => x.classList.toggle('active', x === b));
            renderViewer();
        }));
        $('lgWrap').checked = store('lgWrap') === 'Y';
        $('lgWrap').addEventListener('change', () => { store('lgWrap', $('lgWrap').checked ? 'Y' : 'N'); renderViewer(); });
        $('lgDownload').addEventListener('click', download);
        $('lgForm').addEventListener('submit', saveSource);
        $('lgFormCancel').addEventListener('click', resetForm);
        $('lgPresets').addEventListener('click', e => {
            const b = e.target.closest('button[data-path]');
            if (!b) return;
            if (!$('lgName').value) $('lgName').value = b.getAttribute('data-name');
            $('lgPath').value = b.getAttribute('data-path');
        });

        loadServer(false);
        setAuto($('lgAuto').checked);
    });
})();
