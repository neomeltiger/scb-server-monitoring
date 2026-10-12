// ================= 프로세스 관리 화면 (/processes, service.list 기준) =================
// 데이터 : GET  /api/services/{serverId}            서비스 목록 + 에이전트 상태 + 최근 제어 명령
// 제어   : POST /api/services/{serverId}/commands   {target, cmd}  (ADMIN)
// 가져오기: POST /api/services/{serverId}/import    {text, scriptHome} (ADMIN)
// 명령은 서버 에이전트(krcasmrx11_client)가 가져가 scb_main.sh 로 실행하고 결과를 보고합니다.
// apiRequest / escapeHtml 은 script.js 에 있습니다.
const SVC_API = 'api/services';
const SVC_POLL_ACTIVE_MS = 5000;     // 처리 중인 명령이 있을 때
const SVC_POLL_IDLE_MS = 30000;      // 평상시
const SVC_FOLLOWUP_MS = 30000;       // 명령 완료 후 상태 보고를 기다리는 시간
const CMD_LABEL = { start: '기동', stop: '종료', restart: '재기동', status: '상태', kill: '강제종료', clean: '정리' };
const AGENT_STATE_LABEL = {
    NONE: ['미설치', '에이전트 토큰이 발급되지 않았습니다. 네트워크 구성도 → 서버 선택 → [에이전트 토큰 발급] 후 서버에 krcasmrx11_client 를 설치하세요.'],
    WAITING: ['보고 대기', '토큰은 발급되었지만 아직 에이전트 보고가 없습니다. 서버의 client.env 확인 후 bin/scb_client.sh start 로 데몬을 시작하세요.'],
    OK: ['정상', ''],
    STALE: ['보고 끊김', '에이전트 보고가 오래되었습니다. 서버의 데몬(bin/scb_client.sh status) / 네트워크를 확인하세요. 제어 명령이 실행되지 않을 수 있습니다.'],
    LOST: ['하트비트 끊김', '서버 다운 또는 데몬 비정상 종료로 하트비트가 끊겼습니다. 서비스 상태는 종료로 표시되며 제어 명령이 실행되지 않습니다.'],
    PAUSED: ['수집 중지', '에이전트 관리 화면에서 자료 수집을 중지했습니다. 상태는 갱신되지 않지만 제어 명령은 실행됩니다.'],
    STOPPED: ['데몬 종료', '에이전트 데몬이 종료되어 제어 명령이 실행되지 않습니다. 서버에서 bin/scb_client.sh start 로 시작하세요.']
};

let svcData = null;
let svcTimer = null;
let svcLoading = false;
let svcLastActive = 0;

function isAdmin() {
    const bar = document.getElementById('userBar');
    return bar && bar.getAttribute('data-role') === 'ADMIN';
}

function currentServerId() {
    return document.getElementById('svcServer').value;
}

window.addEventListener('DOMContentLoaded', () => {
    const select = document.getElementById('svcServer');
    if (!select) return;
    if (!isAdmin()) document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');

    // 서버 전체목록 화면에서 행을 눌러 들어온 경우 그 서버를 선택
    try {
        const sel = localStorage.getItem('selectedServerId');
        if (sel && [...select.options].some(o => o.value === sel)) select.value = sel;
        localStorage.removeItem('selectedServerId');
    } catch (e) { /* 무시 */ }

    select.addEventListener('change', () => loadServices(true));
    // 하트비트 끊김 / 데몬 종료 → 선택한 서버면 서비스 상태(종료) 바로 다시 읽음
    if (typeof onHeartbeatChange === 'function') {
        onHeartbeatChange(changed => { if (changed.indexOf(Number(currentServerId())) !== -1) return loadServices(false); });
    }
    document.getElementById('svcRefreshBtn').addEventListener('click', () => loadServices(true));
    document.getElementById('bulkRunBtn').addEventListener('click', runBulk);
    document.getElementById('svcImportBtn').addEventListener('click', openImport);
    document.getElementById('importRunBtn').addEventListener('click', runImport);
    document.getElementById('importFile').addEventListener('change', readImportFile);
    document.getElementById('svcBody').addEventListener('click', onRowButton);
    document.getElementById('cmdBody').addEventListener('click', onRowButton);
    document.getElementById('histBody').addEventListener('click', onRowButton);
    document.getElementById('svcAddBtn').addEventListener('click', () => openSvcForm(null));
    document.getElementById('svcExportBtn').addEventListener('click', openExport);
    document.getElementById('svcDirtyExport').addEventListener('click', openExport);
    document.getElementById('svcHistBtn').addEventListener('click', () => openHist(null));
    document.getElementById('svcForm').addEventListener('submit', saveSvcForm);
    document.getElementById('svcForm').addEventListener('input', renderSfPreview);
    document.getElementById('svcForm').addEventListener('change', renderSfPreview);
    document.getElementById('exportRebuild').addEventListener('click', buildExport);
    document.getElementById('exportHome').addEventListener('change', buildExport);
    document.getElementById('exportCopy').addEventListener('click', copyExport);
    document.getElementById('exportDownload').addEventListener('click', downloadExport);
    document.getElementById('histState').addEventListener('change', renderHist);
    document.getElementById('histCmd').addEventListener('change', renderHist);
    document.addEventListener('keydown', e => {
        if (e.key !== 'Escape') return;
        // 위에 열린 팝업부터 닫음 (결과 → 이력 → 나머지)
        if (isOpen('resultModal')) closeResult();
        else { closeImport(); closeSvcForm(); closeExport(); closeHist(); }
    });
    if (select.value) loadServices(true);
    else document.getElementById('svcBody').innerHTML = '<tr><td colspan="11" class="svc-empty">등록된 서버가 없습니다.</td></tr>';
});

// ---------- 조회 ----------
async function loadServices(showError) {
    if (svcLoading) return;
    svcLoading = true;
    try {
        svcData = await apiRequest('GET', SVC_API + '/' + encodeURIComponent(currentServerId()));
        renderAgent();
        renderBulkTargets();
        renderServices();
        renderCommands();
    } catch (e) {
        if (showError) alert(e.message);
    } finally {
        svcLoading = false;
        schedulePoll();
    }
}

// 명령 처리 중이거나, 끝난 직후 SVC_FOLLOWUP_MS 동안은 빠르게 갱신
// (에이전트는 결과를 보낸 뒤 서비스 상태를 다시 수집해 보고하므로 상태 반영이 수 초 늦게 옴)
function schedulePoll() {
    clearTimeout(svcTimer);
    if (svcData && svcData.activeCommands > 0) svcLastActive = Date.now();
    const fast = Date.now() - svcLastActive < SVC_FOLLOWUP_MS;
    svcTimer = setTimeout(() => loadServices(false), fast ? SVC_POLL_ACTIVE_MS : SVC_POLL_IDLE_MS);
}

// ---------- 화면 ----------
function renderAgent() {
    const a = svcData.agent || { state: 'NONE' };
    const label = AGENT_STATE_LABEL[a.state] || [a.state, ''];
    let info = '';
    if (a.lastReportDt && a.state !== 'NONE' && a.state !== 'WAITING') {
        info = '마지막 보고 <b>' + escapeHtml(a.lastReportDt) + '</b> (' + agoText(a.lastReportAgoSec) + ')' +
            (a.agentHost ? ' · 호스트 ' + escapeHtml(a.agentHost) : '') +
            (a.agentVer ? ' · 에이전트 ' + escapeHtml(a.agentVer) : '');
    }
    document.getElementById('svcAgentBar').innerHTML =
        '<span class="svc-agent-state ' + escapeHtml(a.state) + '">에이전트 ' + escapeHtml(label[0]) + '</span>' +
        '<span>' + escapeHtml(svcData.systemName) + ' (' + escapeHtml(svcData.ip) + ')</span>' +
        (info ? '<span>' + info + '</span>' : '') +
        (label[1] ? '<span class="svc-agent-help">' + escapeHtml(label[1]) + '</span>' : '');
}

function agoText(sec) {
    if (sec == null) return '';
    if (sec < 60) return sec + '초 전';
    if (sec < 3600) return Math.floor(sec / 60) + '분 전';
    return Math.floor(sec / 3600) + '시간 전';
}

function renderBulkTargets() {
    const sel = document.getElementById('bulkTarget');
    const prev = sel.value;
    const groups = [...new Set(svcData.services.map(s => s.group))].sort((a, b) => a - b);
    const levels = [...new Set(svcData.services.map(s => s.level))].sort((a, b) => a - b);
    const cnt = (k, v) => svcData.services.filter(s => s[k] === v).length;
    sel.innerHTML = '<option value="all">전체 (' + svcData.services.length + ')</option>' +
        groups.map(g => '<option value="group:' + g + '">그룹 ' + g + ' (' + cnt('group', g) + ')</option>').join('') +
        levels.map(l => '<option value="level:' + l + '">Level ' + l + ' (' + cnt('level', l) + ')</option>').join('');
    if ([...sel.options].some(o => o.value === prev)) sel.value = prev;
}

function supports(svc, cmd) {
    const has = c => ('|' + svc.cmds + '|').indexOf('|' + c + '|') !== -1;
    if (cmd === 'status') return true;
    if (cmd === 'restart') return has('start') && has('stop');
    return has(cmd);
}

function renderServices() {
    const list = svcData.services;
    const n = st => list.filter(s => s.state === st).length;
    document.getElementById('svcSummary').innerHTML = list.length
        ? '<span class="svc-count RUNNING">실행 중 ' + n('RUNNING') + '</span><span class="svc-count STOPPED">종료 ' + n('STOPPED') + '</span>' +
          '<span class="svc-count UNKNOWN">미확인 ' + n('UNKNOWN') + '</span><span class="svc-sub">전체 ' + list.length + '건</span>'
        : '';
    const body = document.getElementById('svcBody');
    if (!list.length) {
        body.innerHTML = '<tr><td colspan="11" class="svc-empty">등록된 서비스가 없습니다.' +
            (isAdmin() ? ' [service.list 가져오기] 또는 [+ 프로세스 추가] 로 등록하세요.' : '') + '</td></tr>';
        return;
    }
    const admin = isAdmin();
    let prevGroup = null;
    body.innerHTML = list.map(s => {
        const groupStart = s.group !== prevGroup;
        prevGroup = s.group;
        const btns = admin ? ['start', 'stop', 'restart', 'status', 'kill', 'clean'].filter(c => supports(s, c)).map(c =>
            '<button type="button" class="svc-btn ' + c + '" data-act="cmd" data-target="' + escapeHtml(s.name) + '" data-cmd="' + c + '" title="' +
            escapeHtml(s.name) + ' ' + c + '">' + CMD_LABEL[c] + '</button>').join('') : '';
        return '<tr class="' + (groupStart ? 'svc-group-start' : '') + '">' +
            '<td>' + s.no + '</td><td>' + s.group + '</td><td>' + s.level + '</td>' +
            '<td class="svc-name">' + escapeHtml(s.name) + '</td><td>' + escapeHtml(s.account) + '</td>' +
            '<td class="svc-path" title="' + escapeHtml(s.script) + (s.arg ? ' ' + escapeHtml(s.arg) : '') + '">' + escapeHtml(s.script) +
            (s.arg ? ' <i>' + escapeHtml(s.arg) + '</i>' : '') + '</td>' +
            '<td><span class="svc-state ' + escapeHtml(s.state) + '">' + (s.state === 'UNKNOWN' ? '미확인' : s.state === 'STOPPED' ? '종료' : s.state === 'RUNNING' ? '실행 중' : escapeHtml(s.state)) + '</span></td>' +
            '<td><code>' + escapeHtml(s.pid) + '</code></td><td class="svc-time">' + escapeHtml(s.stateDt || '-') + '</td>' +
            '<td class="svc-ctrl">' + btns + '</td>' +
            '<td class="svc-manage"><button type="button" class="svc-btn status" data-act="hist" data-id="' + s.id + '">이력</button>' +
            (admin ? '<button type="button" class="svc-btn restart" data-act="edit" data-id="' + s.id + '">수정</button>' +
                     '<button type="button" class="svc-btn del" data-act="del" data-id="' + s.id + '">삭제</button>' : '') +
            '</td></tr>';
    }).join('');
    renderDirty();
}

function renderCommands() {
    const body = document.getElementById('cmdBody');
    const list = svcData.commands;
    if (!list.length) {
        body.innerHTML = '<tr><td colspan="9" class="svc-empty">제어 명령 이력이 없습니다.</td></tr>';
        return;
    }
    const stateLabel = { REQUESTED: '요청됨', SENT: '실행 중', DONE: '완료', FAILED: '실패', EXPIRED: '만료' };
    body.innerHTML = list.map(c =>
        '<tr><td>' + c.id + '</td><td class="svc-time">' + escapeHtml(c.reqDt || '') + '</td>' +
        '<td class="svc-name">' + escapeHtml(targetLabel(c.target)) + '</td><td>' + escapeHtml(c.cmd) + '</td>' +
        '<td><span class="svc-cmd-state ' + escapeHtml(c.state) + '">' + (stateLabel[c.state] || escapeHtml(c.state)) + '</span></td>' +
        '<td>' + escapeHtml(c.reqUser) + '</td><td>' + (c.exitCode == null ? '-' : c.exitCode) + '</td>' +
        '<td class="svc-time">' + escapeHtml(c.endDt || '-') + '</td>' +
        '<td>' + (c.result ? '<button type="button" class="svc-btn status" data-act="result" data-id="' + c.id + '">보기</button>' : '-') + '</td></tr>'
    ).join('');
}

function targetLabel(t) {
    if (t === 'all') return '전체';
    if (t.indexOf('group:') === 0) return '그룹 ' + t.substring(6);
    if (t.indexOf('level:') === 0) return 'Level ' + t.substring(6);
    return t;
}

// ---------- 제어 ----------
function onRowButton(e) {
    const b = e.target.closest('button[data-act]');
    if (!b) return;
    const act = b.getAttribute('data-act');
    const id = Number(b.getAttribute('data-id'));
    if (act === 'cmd') requestCommand(b.getAttribute('data-target'), b.getAttribute('data-cmd'));
    else if (act === 'result') showResult(id);
    else if (act === 'hist') openHist(id);
    else if (act === 'edit') openSvcForm(id);
    else if (act === 'del') deleteSvc(id);
}

function runBulk() {
    requestCommand(document.getElementById('bulkTarget').value, document.getElementById('bulkCmd').value);
}

async function requestCommand(target, cmd) {
    if (!svcData) return;
    const a = svcData.agent || { state: 'NONE' };
    let msg = '[' + svcData.systemName + '] ' + targetLabel(target) + ' 에 ' + cmd + ' (' + CMD_LABEL[cmd] + ') 를 요청합니다.';
    if (a.state !== 'OK') msg += '\n\n※ 에이전트 상태가 "' + (AGENT_STATE_LABEL[a.state] || [a.state])[0] + '" 입니다. 에이전트가 동작하지 않으면 실행되지 않고 만료됩니다.';
    if (cmd !== 'status' && !confirm(msg + '\n\n계속하시겠습니까?')) return;
    try {
        await apiRequest('POST', SVC_API + '/' + encodeURIComponent(currentServerId()) + '/commands', { target: target, cmd: cmd });
    } catch (e) { alert(e.message); return; }
    svcLastActive = Date.now();
    loadServices(true);
}

function showResult(id) {
    const c = (svcData && svcData.commands.find(x => x.id === id)) || histList.find(x => x.id === id);
    if (!c) return;
    document.getElementById('resultTitle').innerText = '#' + c.id + ' ' + targetLabel(c.target) + ' ' + c.cmd + ' 결과 (종료코드 ' + (c.exitCode == null ? '-' : c.exitCode) + ')';
    document.getElementById('resultBody').textContent = c.result || '';
    document.getElementById('resultModal').style.display = 'flex';
}

function closeResult() {
    const m = document.getElementById('resultModal');
    if (m) m.style.display = 'none';
}

// ---------- service.list 가져오기 ----------
function openImport() {
    document.getElementById('importMsg').textContent = '';
    document.getElementById('importModal').style.display = 'flex';
    document.getElementById('importText').focus();
}

function closeImport() {
    const m = document.getElementById('importModal');
    if (m) m.style.display = 'none';
}

function readImportFile(e) {
    const f = e.target.files && e.target.files[0];
    if (!f) return;
    if (f.size > 200000) { alert('파일이 너무 큽니다. (200KB 이하)'); return; }
    const r = new FileReader();
    r.onload = () => { document.getElementById('importText').value = r.result; };
    r.readAsText(f, 'UTF-8');
}

async function runImport() {
    const text = document.getElementById('importText').value;
    const home = document.getElementById('importHome').value.trim();
    const msg = document.getElementById('importMsg');
    if (!text.trim()) { msg.textContent = 'service.list 내용을 입력하세요.'; return; }
    if (!confirm('[' + (svcData ? svcData.systemName : '') + '] 의 서비스 목록을 이 내용으로 바꿉니다. 계속하시겠습니까?')) return;
    try {
        const r = await apiRequest('POST', SVC_API + '/' + encodeURIComponent(currentServerId()) + '/import', { text: text, scriptHome: home });
        msg.textContent = '완료: 추가 ' + r.added + ' / 갱신 ' + r.updated + ' / 삭제 ' + r.removed +
            (r.skipped.length ? ' / 건너뜀 ' + r.skipped.length + ' (' + r.skipped.join(', ') + ')' : '');
        loadServices(true);
    } catch (e) {
        msg.textContent = '실패: ' + e.message;
    }
}

function isOpen(id) {
    const m = document.getElementById(id);
    return !!m && m.style.display === 'flex';
}

// ---------- 웹에서 목록 변경 → 서버 반영 안내 (서버별, 이 브라우저 기준) ----------
const DIRTY_KEY = 'svcListChanged';

function dirtyMap() {
    try { return JSON.parse(localStorage.getItem(DIRTY_KEY) || '{}') || {}; } catch (e) { return {}; }
}

function setDirty(serverId, on) {
    const m = dirtyMap();
    if (on) m[serverId] = Date.now(); else delete m[serverId];
    try { localStorage.setItem(DIRTY_KEY, JSON.stringify(m)); } catch (e) { /* 무시 */ }
    renderDirty();
}

function renderDirty() {
    const el = document.getElementById('svcDirty');
    if (el) el.hidden = !dirtyMap()[currentServerId()];
}

// ---------- 프로세스(서비스) 추가 / 수정 / 삭제 ----------
function openSvcForm(id) {
    const s = id ? svcData.services.find(x => x.id === id) : null;
    const home = document.getElementById('sfHome').value || '/shared/opt/pos';
    document.getElementById('sfId').value = s ? s.id : '';
    document.getElementById('svcFormTitle').textContent = s ? '프로세스 수정 - ' + s.name : '프로세스 추가 - ' + (svcData ? svcData.systemName : '');
    document.getElementById('sfSave').textContent = s ? '저장' : '추가';
    const last = svcData && svcData.services.length ? svcData.services[svcData.services.length - 1] : null;
    document.getElementById('sfGroup').value = s ? s.group : (last ? last.group : 1);
    document.getElementById('sfLevel').value = s ? s.level : (last ? last.level + 1 : 1);
    document.getElementById('sfName').value = s ? s.name : '';
    document.getElementById('sfAccount').value = s ? s.account : (last ? last.account : '');
    document.getElementById('sfArg').value = s && s.arg ? s.arg : '';
    let script = s ? s.script : '';
    if (script && script.indexOf(home.replace(/\/+$/, '') + '/') === 0) script = script.substring(home.replace(/\/+$/, '').length + 1);
    document.getElementById('sfScript').value = script;
    const cmds = s ? s.cmds.split('|') : ['start', 'stop'];
    document.querySelectorAll('.sf-cmds input').forEach(c => { c.checked = cmds.indexOf(c.value) !== -1; });
    document.getElementById('sfError').textContent = '';
    renderSfPreview();
    document.getElementById('svcFormModal').style.display = 'flex';
    document.getElementById(s ? 'sfGroup' : 'sfName').focus();
}

function closeSvcForm() {
    const m = document.getElementById('svcFormModal');
    if (m) m.style.display = 'none';
}

function sfBody() {
    return {
        group: document.getElementById('sfGroup').value === '' ? null : Number(document.getElementById('sfGroup').value),
        level: document.getElementById('sfLevel').value === '' ? null : Number(document.getElementById('sfLevel').value),
        name: document.getElementById('sfName').value.trim(),
        account: document.getElementById('sfAccount').value.trim(),
        script: document.getElementById('sfScript').value.trim(),
        arg: document.getElementById('sfArg').value.trim(),
        cmds: [...document.querySelectorAll('.sf-cmds input:checked')].map(c => c.value),
        scriptHome: document.getElementById('sfHome').value.trim()
    };
}

function renderSfPreview() {
    const b = sfBody();
    document.getElementById('sfPreview').textContent =
        [b.group, b.level, b.name, b.account, b.script, b.arg].map(v => v == null ? '' : v).join('|') + '|' + b.cmds.join('|');
}

async function saveSvcForm(e) {
    e.preventDefault();
    const b = sfBody();
    const err = document.getElementById('sfError');
    if (b.group == null || b.level == null || !b.name || !b.account || !b.script) { err.textContent = '* 표시 항목을 입력하세요.'; return; }
    if (!b.cmds.length) { err.textContent = '지원 명령(CMDS)을 하나 이상 고르세요.'; return; }
    const id = document.getElementById('sfId').value;
    const url = SVC_API + '/' + encodeURIComponent(currentServerId()) + '/items' + (id ? '/' + id : '');
    const btn = document.getElementById('sfSave');
    btn.disabled = true;
    try {
        await apiRequest(id ? 'PUT' : 'POST', url, b);
        closeSvcForm();
        setDirty(currentServerId(), true);
        loadServices(true);
    } catch (ex) {
        err.textContent = ex.message;
    } finally {
        btn.disabled = false;
    }
}

async function deleteSvc(id) {
    const s = svcData.services.find(x => x.id === id);
    if (!s || !confirm('[' + s.name + '] 를 프로세스 목록에서 삭제하시겠습니까?\n뒤의 순번(NO)이 하나씩 당겨집니다.\n서버에 반영하려면 service.list 문서를 다시 만들어 적용하세요.')) return;
    try {
        await apiRequest('DELETE', SVC_API + '/' + encodeURIComponent(currentServerId()) + '/items/' + id);
        setDirty(currentServerId(), true);
        loadServices(true);
    } catch (e) { alert(e.message); }
}

// ---------- service.list 문서 만들기 ----------
function openExport() {
    document.getElementById('exportTitle').textContent = 'service.list 문서 - ' + (svcData ? svcData.systemName : '');
    document.getElementById('exportModal').style.display = 'flex';
    buildExport();
}

function closeExport() {
    const m = document.getElementById('exportModal');
    if (m) m.style.display = 'none';
}

async function buildExport() {
    const msg = document.getElementById('exportMsg');
    msg.textContent = '만드는 중...';
    try {
        const home = document.getElementById('exportHome').value.trim();
        const res = await fetch(SVC_API + '/' + encodeURIComponent(currentServerId()) + '/service-list?scriptHome=' + encodeURIComponent(home),
            { credentials: 'same-origin', headers: { 'Accept': 'text/plain' } });
        if (!res.ok) throw new Error('문서를 만들지 못했습니다. (HTTP ' + res.status + ')');
        const text = await res.text();
        document.getElementById('exportText').value = text;
        const n = text.split('\n').filter(l => l && l.charAt(0) !== '#').length;
        msg.textContent = '서비스 ' + n + '건';
    } catch (e) {
        msg.textContent = e.message;
    }
}

async function copyExport() {
    const t = document.getElementById('exportText');
    try {
        await navigator.clipboard.writeText(t.value);
    } catch (e) {
        t.select();
        document.execCommand('copy');
    }
    document.getElementById('exportMsg').textContent = '복사했습니다.';
    setDirty(currentServerId(), false);
}

function downloadExport() {
    const text = document.getElementById('exportText').value;
    if (!text) return;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    a.download = 'service.list';
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
    document.getElementById('exportMsg').textContent = 'service.list 를 내려받았습니다. 서버에 반영하세요.';
    setDirty(currentServerId(), false);
}

// ---------- 제어 이력 팝업 (전체 / 서비스별) ----------
let histList = [];

async function openHist(svcId) {
    const s = svcId ? svcData.services.find(x => x.id === svcId) : null;
    document.getElementById('histTitle').textContent = s ? '제어 이력 - ' + s.name : '제어 이력 - ' + (svcData ? svcData.systemName : '');
    document.getElementById('histSub').textContent = s
        ? '이 서비스에 대한 명령과, 이 서비스가 포함되는 그룹 ' + s.group + ' / Level ' + s.level + ' / 전체 명령 (최근 50건)'
        : '이 서버의 모든 제어 명령 (최근 100건)';
    document.getElementById('histState').value = '';
    document.getElementById('histCmd').value = '';
    document.getElementById('histBody').innerHTML = '<tr><td colspan="9" class="svc-empty">불러오는 중...</td></tr>';
    document.getElementById('histModal').style.display = 'flex';
    try {
        const base = SVC_API + '/' + encodeURIComponent(currentServerId());
        histList = await apiRequest('GET', s ? base + '/items/' + s.id + '/commands?rows=50' : base + '/commands?rows=100');
    } catch (e) {
        histList = [];
        document.getElementById('histBody').innerHTML = '<tr><td colspan="9" class="svc-empty">' + escapeHtml(e.message) + '</td></tr>';
        return;
    }
    renderHist();
}

function renderHist() {
    const st = document.getElementById('histState').value;
    const cmd = document.getElementById('histCmd').value;
    const list = histList.filter(c => (!st || c.state === st) && (!cmd || c.cmd === cmd));
    document.getElementById('histCount').textContent = list.length + ' / ' + histList.length + '건';
    const stateLabel = { REQUESTED: '요청됨', SENT: '실행 중', DONE: '완료', FAILED: '실패', EXPIRED: '만료' };
    document.getElementById('histBody').innerHTML = list.length ? list.map(c =>
        '<tr><td>' + c.id + '</td><td class="svc-time">' + escapeHtml(c.reqDt || '') + '</td>' +
        '<td class="svc-name">' + escapeHtml(targetLabel(c.target)) + '</td><td>' + escapeHtml(c.cmd) + '</td>' +
        '<td><span class="svc-cmd-state ' + escapeHtml(c.state) + '">' + (stateLabel[c.state] || escapeHtml(c.state)) + '</span></td>' +
        '<td>' + escapeHtml(c.reqUser) + '</td><td>' + (c.exitCode == null ? '-' : c.exitCode) + '</td>' +
        '<td class="svc-time">' + escapeHtml(c.endDt || '-') + '</td>' +
        '<td>' + (c.result ? '<button type="button" class="svc-btn status" data-act="result" data-id="' + c.id + '">보기</button>' : '-') + '</td></tr>'
    ).join('') : '<tr><td colspan="9" class="svc-empty">제어 이력이 없습니다.</td></tr>';
}

function closeHist() {
    const m = document.getElementById('histModal');
    if (m) m.style.display = 'none';
}
