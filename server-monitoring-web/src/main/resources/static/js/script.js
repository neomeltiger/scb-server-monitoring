// ================= 화면 초기화 =================
// 로그인 / 로그아웃 / 상단 사용자 정보는 서버(Spring Security + Thymeleaf fragments/top.html)가 처리합니다.

async function renderDashboard() {
    // 일반 사용자(USER)는 수정/삭제/제어 버튼(.admin-only)을 숨김 - 실제 권한 검사는 서버(SecurityConfig)에서 함
    if (!isAdminUser()) document.body.classList.add('role-user');
    if (/[?&]denied(=|&|$)/.test(location.search)) {
        history.replaceState(null, '', location.pathname);
        setTimeout(() => alert('관리자(ADMIN)만 사용할 수 있는 화면입니다. (사용자 관리 / 웹 콘솔)'), 0);
    }
    const dashboardContainer = document.getElementById('dashboard-container');
    if (!dashboardContainer) return; // 웹 콘솔 등 대시보드가 없는 화면
    dashboardContainer.style.display = 'flex';
    // 서버/프로세스 데이터 : MySQL (scb_mon_agent) ← /api/topology
    try {
        await loadServers();
        if (document.getElementById('network-container')) await loadTopoNodes();
    } catch (e) {
        showDataError(e);
        return;
    }
    if (document.getElementById('network-container')) {
        await loadAgentStates();
        initNetwork();
        startTopoRefresh();
        onHeartbeatChange(() => topoRefreshNow(true));
    }
    if (document.getElementById('specTableBody')) {
        renderSpecTable();
        onHeartbeatChange(async () => { await loadServers(); renderSpecTable(); });
    }
    if (document.getElementById('processSection')) {
        await Promise.all([loadHistory(), loadAgentStates(), loadServiceLists()]);
        renderProcessSection();
        startHistoryRefresh();
        onHeartbeatChange(async () => {
            await loadServers();
            await Promise.all([loadAgentStates(), loadServiceLists()]);
            renderProcessSection();
        });
    }
}

window.addEventListener('DOMContentLoaded', renderDashboard);

// 서버 목록 (DB: tb_server + tb_server_metric + tb_server_process) - loadServers() 로 채움
let serversData = [];
// 네트워크 노드 (DB: tb_net_node) - Central Hub / 사이트 스위치
let topoNodes = [];

// --- 서버 데이터 API (/api/topology, MySQL scb_mon_agent) ---
// 상대 경로를 쓰므로 컨텍스트 경로(/server-monitoring-web)가 있어도 동작합니다.
const TOPO_API = 'api/topology';

// 변경 요청(POST/PUT/DELETE)에 필요한 CSRF 토큰 (각 화면 head 의 meta 태그)
function csrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    const h = {};
    if (token && header) h[header.getAttribute('content')] = token.getAttribute('content');
    return h;
}

// JSON API 호출. 실패하면 서버가 준 message 로 Error 를 던진다.
async function apiRequest(method, url, body) {
    const opt = { method: method, credentials: 'same-origin', headers: Object.assign({ 'Accept': 'application/json' }, csrfHeaders()) };
    if (body !== undefined) {
        opt.headers['Content-Type'] = 'application/json';
        opt.body = JSON.stringify(body);
    }
    const res = await fetch(url, opt);
    if (res.status === 204) return null;
    const type = res.headers.get('content-type') || '';
    if (type.indexOf('application/json') === -1) {
        // 로그인 세션이 끝나 로그인 화면(HTML)이 온 경우
        if (res.redirected || res.status === 401) {
            window.location.reload();
            throw new Error('로그인이 만료되었습니다. 다시 로그인하세요.');
        }
        if (res.status === 403) {
            throw new Error('권한이 없습니다. 변경·제어는 관리자(ADMIN)만 가능합니다. (세션이 만료되었다면 새로고침 후 다시 시도하세요)');
        }
        throw new Error('요청 실패 (HTTP ' + res.status + ')');
    }
    const data = await res.json();
    if (!res.ok) throw new Error((data && data.message) || ('요청 실패 (HTTP ' + res.status + ')'));
    return data;
}

async function loadServers() {
    serversData = await apiRequest('GET', TOPO_API + '/servers');
}

async function loadTopoNodes() {
    topoNodes = await apiRequest('GET', TOPO_API + '/nodes');
}

// 데이터 조회 실패 안내 (MySQL 미기동 등)
function showDataError(e) {
    const wrap = document.querySelector('.main-content-wrapper');
    if (!wrap || document.getElementById('dataError')) return;
    const div = document.createElement('div');
    div.id = 'dataError';
    div.className = 'data-error';
    div.textContent = '서버 데이터를 불러오지 못했습니다: ' + e.message + '  (MySQL scb_mon_agent 연결 확인 후 새로고침)';
    const header = wrap.querySelector('.app-header');
    wrap.insertBefore(div, header ? header.nextSibling : wrap.firstChild);
}

function apiAlert(e) {
    alert(e.message || String(e));
}

async function resetServers() {
    if (!confirm('모든 서버 변경 내용을 지우고 기본 서버 목록으로 되돌립니다. 계속하시겠습니까?')) return;
    try {
        serversData = await apiRequest('POST', TOPO_API + '/reset');
    } catch (e) { apiAlert(e); return; }
    if (nodes) rebuildTopology();
    updateRemoveSelect();
    const detail = document.getElementById('detail-content');
    if (detail) detail.innerHTML = '<p style="color: #888; font-style: italic; font-size: 13px;">다이어그램에서 서버 노드를 클릭하세요.</p>';
    alert('기본 서버 목록으로 초기화했습니다.');
}

let network = null;
let nodes, edges;

// ================= 네트워크 구성도 (표준 도형/아이콘 디자인) =================
const TOPO_STATUS = {
    Active:  { fill: '#e7f1ff', stroke: '#007bff', led: '#28a745' },
    Standby: { fill: '#fff8e1', stroke: '#c79100', led: '#f0ad4e' },
    Down:    { fill: '#fde8ea', stroke: '#dc3545', led: '#dc3545' }
};
const TOPO_ROW = 105;      // 서버 행 간격
const TOPO_SERVER_X = 190; // 서버 열 x 좌표 (ITC는 -, DRC는 +)
const TOPO_SWITCH_X = 380; // 스위치 x 좌표
const TOPO_BUS_GAP = 95;   // 스위치-서버 버스 간격

// 구성도 표시 상태 : DB 상태가 Down 이거나, 에이전트로 서버 기본 정보를 받아오지 못하면(OK 외) Down 색으로 표시
//   (에이전트 상태를 못 읽었으면 DB 상태만 사용)
const TOPO_NODATA_LABEL = { NONE: '에이전트 미설치', WAITING: '보고 대기', STALE: '보고 끊김', LOST: '하트비트 끊김', PAUSED: '수집 중지', STOPPED: '데몬 종료' };

/**
 * 서버 다운 사유 (서버가 정상이면 null) - 화면에서 읽은 에이전트 상태, 없으면 서버 응답(noData).
 * 하트비트가 오는데 데몬만 멈춘 경우(serverDown=false)는 서버 다운이 아님 → topoDaemonIssue 로 재시작 알림
 */
function topoNoDataReason(server) {
    if (!agentStatesLoaded) return server.noData || null;
    const a = agentStates[server.id] || { state: 'NONE', serverDown: true };
    if (a.state === 'OK' || a.serverDown === false) return null;
    return TOPO_NODATA_LABEL[a.state] || a.state;
}

/** 데몬 문제 (서버 정상) : 데몬 종료 / 데몬 비정상 종료 / 데몬 응답 없음, 없으면 null */
function topoDaemonIssue(server) {
    if (!agentStatesLoaded) return server.daemonIssue || null;
    return (agentStates[server.id] || {}).daemonIssue || null;
}

function topoCanRestart(server) {
    return agentStatesLoaded ? !!(agentStates[server.id] || {}).canRestart : !!server.canRestart;
}

/** 데몬 재시작 요청 (ADMIN) - 하트비트 감시자(클라이언트 1.5~)가 다음 하트비트 때 서버에서 bin/scb_client.sh start */
async function restartDaemon(serverId) {
    const server = (typeof serversData !== 'undefined' ? serversData : []).find(s => s.id === serverId);
    const name = server ? server.systemName : serverId;
    if (!confirm('[' + name + '] 의 데몬(scb_client)을 재시작합니다.\n다음 하트비트 때 서버의 하트비트 감시자가 bin/scb_client.sh start 를 실행합니다.\n\n계속하시겠습니까?')) return null;
    let a;
    try {
        a = await apiRequest('POST', 'api/agents/' + encodeURIComponent(serverId) + '/restart');
    } catch (e) { alert(e.message); return null; }
    alert('재시작 요청을 보냈습니다. 다음 하트비트(최대 ' + (a.hbIntervalSec || 10) + '초) 때 서버에서 데몬을 시작합니다.');
    return a;
}

/** 등록된 상태 (서버 응답 status 는 정보 수신 안 되면 Down 으로 바뀌어 옴) */
function serverBaseStatus(server) {
    return server.baseStatus || server.status;
}

/** 등록 상태가 Down (서버 장애) */
function serverBaseDown(server) {
    const b = serverBaseStatus(server);
    return b === 'Down' || b === 'Stopped';
}

function topoIsDown(server) {
    return serverBaseDown(server) || topoNoDataReason(server) !== null;
}

function topoViewStatus(server) {
    return topoIsDown(server) ? 'Down' : serverBaseStatus(server);
}

/** Down 사유 짧은 표시 : 정보 수신 안 됨이면 " (보고 끊김)" */
function serverDownNote(server) {
    return !serverBaseDown(server) && server.noData ? ' (' + server.noData + ')' : '';
}

function topoStatusStyle(status) {
    return TOPO_STATUS[status === 'Stopped' ? 'Down' : status] || TOPO_STATUS.Active;
}

function getServerType(s) {
    const n = String(s.systemName || '');
    if (/DB/i.test(n)) return 'db';
    if (/NFS|NAS|STORAGE/i.test(n)) return 'nfs';
    return 'ap';
}

function svgToUri(svg) {
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}

// type: hub | switch-itc | switch-drc | ap | db | nfs
function topoIconUri(type, status) {
    const open = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">';
    let body = '';
    if (type === 'hub') {
        body = '<circle cx="32" cy="32" r="28" fill="#fff3bf" stroke="#d99a00" stroke-width="3"/>' +
            '<g stroke="#7a4f00" stroke-width="3" fill="none" stroke-linecap="round" stroke-linejoin="round">' +
            '<path d="M32 14v36M14 32h36"/><path d="M27 19l5-5 5 5M27 45l5 5 5-5M19 27l-5 5 5 5M45 27l5 5-5 5"/></g>';
    } else if (type === 'switch-itc' || type === 'switch-drc') {
        const itc = type === 'switch-itc';
        const f = itc ? '#d4edda' : '#f8d7da', s = itc ? '#28a745' : '#dc3545', d = itc ? '#155724' : '#721c24';
        body = '<rect x="4" y="18" width="56" height="28" rx="5" fill="' + f + '" stroke="' + s + '" stroke-width="3"/>' +
            '<g fill="' + d + '"><rect x="10" y="36" width="6" height="5" rx="1"/><rect x="19" y="36" width="6" height="5" rx="1"/>' +
            '<rect x="28" y="36" width="6" height="5" rx="1"/><rect x="37" y="36" width="6" height="5" rx="1"/><rect x="46" y="36" width="6" height="5" rx="1"/></g>' +
            '<g stroke="' + d + '" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round">' +
            '<path d="M12 27h40"/><path d="M17 23l-5 4 5 4"/><path d="M47 23l5 4-5 4"/></g>';
    } else {
        const st = topoStatusStyle(status);
        const F = st.fill, S = st.stroke, LED = st.led;
        if (type === 'db') {
            body = '<path d="M12 16v32c0 4.4 9 8 20 8s20-3.6 20-8V16" fill="' + F + '" stroke="' + S + '" stroke-width="3"/>' +
                '<ellipse cx="32" cy="16" rx="20" ry="8" fill="' + F + '" stroke="' + S + '" stroke-width="3"/>' +
                '<path d="M12 30c0 4.4 9 8 20 8s20-3.6 20-8" fill="none" stroke="' + S + '" stroke-width="2"/>' +
                '<circle cx="44" cy="47" r="3.5" fill="' + LED + '"/>';
        } else if (type === 'nfs') {
            body = '<rect x="6" y="12" width="52" height="40" rx="5" fill="' + F + '" stroke="' + S + '" stroke-width="3"/>' +
                '<g fill="#ffffff" stroke="' + S + '" stroke-width="1.5"><rect x="12" y="18" width="30" height="8" rx="2"/><rect x="12" y="29" width="30" height="8" rx="2"/><rect x="12" y="40" width="30" height="8" rx="2"/></g>' +
                '<g fill="' + LED + '"><circle cx="49" cy="22" r="2.8"/><circle cx="49" cy="33" r="2.8"/><circle cx="49" cy="44" r="2.8"/></g>';
        } else {
            body = '<rect x="14" y="5" width="36" height="54" rx="5" fill="' + F + '" stroke="' + S + '" stroke-width="3"/>' +
                '<g fill="#ffffff" stroke="' + S + '" stroke-width="1.5"><rect x="19" y="11" width="26" height="10" rx="2"/><rect x="19" y="24" width="26" height="10" rx="2"/><rect x="19" y="37" width="26" height="10" rx="2"/></g>' +
                '<circle cx="24" cy="53" r="3" fill="' + LED + '"/><path d="M31 53h14" stroke="' + S + '" stroke-width="2" stroke-linecap="round"/>';
        }
    }
    return svgToUri(open + body + '</svg>');
}

// DB 노드 → 화면 배치 : Hub(위), 첫 스위치(왼쪽, ITC), 두 번째 스위치(오른쪽, DRC)
function topoLayoutNodes() {
    const hub = topoNodes.find(n => n.type === 'HUB') || null;
    const sws = topoNodes.filter(n => n.type === 'SWITCH');
    const left = sws.find(n => n.siteCd === 'ITC') || sws[0] || null;
    const right = sws.find(n => n !== left) || null;
    return { hub: hub, left: left, right: right };
}

function serversOn(sw) {
    return sw ? serversData.filter(s => s.switchNodeId === sw.id) : [];
}

function topoNodeLabel(n) {
    return n.name + (n.desc ? '\n[' + n.desc + ']' : '');
}

function computeTopoLayout() {
    const t = topoLayoutNodes();
    const itc = serversOn(t.left);
    const drc = serversOn(t.right);
    const pos = {};
    const place = (list, sign) => list.forEach((s, i) => {
        pos[s.id] = { x: sign * TOPO_SERVER_X, y: (i - (list.length - 1) / 2) * TOPO_ROW };
    });
    place(itc, -1);
    place(drc, 1);
    const rows = Math.max(itc.length, drc.length, 1);
    if (t.hub) pos[t.hub.id] = { x: 0, y: -((rows - 1) / 2) * TOPO_ROW - 140 };
    if (t.left) pos[t.left.id] = { x: -TOPO_SWITCH_X, y: 0 };
    if (t.right) pos[t.right.id] = { x: TOPO_SWITCH_X, y: 0 };
    return pos;
}

function buildTopoNodes() {
    const pos = computeTopoLayout();
    const common = {
        shape: 'image', size: 26,
        font: { size: 12, face: 'Segoe UI', color: '#203a43' },
        shapeProperties: { useBorderWithImage: true },
        borderWidth: 2, borderWidthSelected: 3,
        color: { border: 'rgba(0,0,0,0)', background: 'rgba(0,0,0,0)', highlight: { border: '#007bff', background: 'rgba(0,123,255,0.08)' }, hover: { border: '#94a3b8', background: 'rgba(148,163,184,0.1)' } }
    };
    const t = topoLayoutNodes();
    const list = [];
    if (t.hub) list.push(Object.assign({ id: t.hub.id, label: topoNodeLabel(t.hub), image: topoIconUri('hub'), size: 28, x: pos[t.hub.id].x, y: pos[t.hub.id].y }, common));
    if (t.left) list.push(Object.assign({ id: t.left.id, label: topoNodeLabel(t.left), image: topoIconUri('switch-itc'), x: pos[t.left.id].x, y: pos[t.left.id].y }, common));
    if (t.right) list.push(Object.assign({ id: t.right.id, label: topoNodeLabel(t.right), image: topoIconUri('switch-drc'), x: pos[t.right.id].x, y: pos[t.right.id].y }, common));
    serversData.forEach(s => {
        const p = pos[s.id];
        if (!p) return;   // 스위치에 연결되지 않은 서버
        list.push(Object.assign({}, common, topoServerLook(s), { id: s.id, x: p.x, y: p.y }));
    });
    return list;
}

/** 서버 노드의 글자·아이콘 (상태가 바뀌면 이 값만 바꿔 위치는 유지) */
function topoServerLook(s) {
    const down = topoIsDown(s);
    const reason = serverBaseDown(s) ? null : topoNoDataReason(s);
    const issue = down ? null : topoDaemonIssue(s);
    return {
        label: s.systemName + '\n' + s.ip + '\n' + (down ? 'DOWN' + (reason ? ' (' + reason + ')' : '')
            : serverBaseStatus(s) + (issue ? ' · 데몬 재시작 필요' : '')),
        image: topoIconUri(getServerType(s), topoViewStatus(s)),
        font: { size: 12, face: 'Segoe UI', color: down ? '#dc3545' : issue ? '#b45309' : '#203a43' }
    };
}

// 에이전트 상태 주기 갱신 (60초) : 보고가 끊기거나 다시 연결되면 노드 색을 바꿈 (위치는 유지)
//   하트비트 끊김·데몬 종료는 onHeartbeatChange 로 몇 초 안에 바로 반영
let topoRefreshTimer = null;
function startTopoRefresh() {
    if (topoRefreshTimer) return;
    topoRefreshTimer = setInterval(() => { if (!document.hidden) topoRefreshNow(false); }, 60000);
}

/** 구성도 다시 그리기 (노드 위치 유지) - reloadServers 면 서버·프로세스 상태도 다시 읽음 */
async function topoRefreshNow(reloadServers) {
    if (!nodes) return;
    if (reloadServers) {
        try { await loadServers(); } catch (e) { return; }
    }
    await loadAgentStates();
    nodes.update(serversData.filter(s => nodes.get(s.id)).map(s => Object.assign({ id: s.id }, topoServerLook(s))));
    if (network) network.redraw();
    const box = document.getElementById('topoAgent');
    const sel = box ? serversData.find(s => String(s.id) === box.dataset.server) : null;
    if (sel) {
        document.getElementById('detail-content').innerHTML = topoDetailHtml(sel);
        loadTopoAgent(sel.id);
    }
}

// ================= 하트비트 즉시 반영 (모든 화면 공통) =================
// GET api/agents/heartbeat 를 HB_WATCH_MS 마다 확인 → 에이전트 상태가 바뀐 서버가 있으면 등록된 화면 갱신 함수를 바로 실행
// (웹 서버는 하트비트 끊김·데몬 종료를 2초 안에 감지해 그 서버의 프로세스·서비스를 종료로 바꿔 둠)
const HB_WATCH_MS = 3000;
let hbWatchTimer = null;
let hbLastStates = null;
const hbChangeHandlers = [];

function onHeartbeatChange(fn) {
    hbChangeHandlers.push(fn);
    startHeartbeatWatch();
}

function startHeartbeatWatch() {
    if (hbWatchTimer) return;
    // 기준 상태는 화면을 그린 직후 한 번 받아 둠 (숨겨진 탭이어도) → 숨겨진 동안은 조회하지 않고,
    // 다시 보이면 바로 확인해 그 사이 바뀐 서버를 반영
    const tick = async () => {
        if (document.hidden && hbLastStates) return;
        let list;
        try { list = await apiRequest('GET', 'api/agents/heartbeat'); } catch (e) { return; }
        const now = {};
        list.forEach(v => { now[v.serverId] = v.agentState; });
        if (hbLastStates) {
            const changed = Object.keys(now).filter(id => hbLastStates[id] !== now[id]).map(Number);
            if (changed.length) {
                for (const fn of hbChangeHandlers) {
                    try { await fn(changed, list); } catch (e) { console.warn('하트비트 화면 갱신 실패', e); }
                }
            }
        }
        hbLastStates = now;
    };
    tick();
    hbWatchTimer = setInterval(tick, HB_WATCH_MS);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) tick(); });
}

function topoRoundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
}

// 동기화 대상 서버 (tb_server.pair_server_id)
function topoPairOf(server) {
    if (server.pairId == null) return null;
    return serversData.find(s => s.id === server.pairId) || null;
}

function drawTopoZone(ctx, pos, switchId, servers, fill, stroke, title) {
    const pts = [];
    if (pos[switchId]) pts.push({ x: pos[switchId].x, y: pos[switchId].y, w: 60 });
    servers.forEach(s => { if (pos[s.id]) pts.push({ x: pos[s.id].x, y: pos[s.id].y, w: 70 }); });
    if (!pts.length) return;
    const minX = Math.min(...pts.map(p => p.x - p.w)) - 14;
    const maxX = Math.max(...pts.map(p => p.x + p.w)) + 14;
    const minY = Math.min(...pts.map(p => p.y)) - 66;
    const maxY = Math.max(...pts.map(p => p.y)) + 84;
    ctx.save();
    topoRoundRect(ctx, minX, minY, maxX - minX, maxY - minY, 14);
    ctx.fillStyle = fill;
    ctx.fill();
    ctx.setLineDash([8, 5]);
    ctx.lineWidth = 2;
    ctx.strokeStyle = stroke;
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = stroke;
    ctx.font = 'bold 14px Segoe UI, sans-serif';
    ctx.textBaseline = 'middle';
    ctx.fillText(title, minX + 14, minY + 20);
    ctx.restore();
}

function drawTopoBus(ctx, pos, switchId, servers, dir) {
    const sw = pos[switchId];
    if (!sw) return;
    const list = servers.filter(s => pos[s.id]);
    if (!list.length) return;
    const busX = sw.x + dir * TOPO_BUS_GAP;
    const ys = list.map(s => pos[s.id].y).concat([sw.y]);
    ctx.save();
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = '#64748b';
    ctx.beginPath();
    ctx.moveTo(sw.x + dir * 30, sw.y);
    ctx.lineTo(busX, sw.y);
    ctx.moveTo(busX, Math.min(...ys));
    ctx.lineTo(busX, Math.max(...ys));
    ctx.stroke();
    list.forEach(s => {
        const p = pos[s.id];
        const down = topoIsDown(s);
        ctx.beginPath();
        ctx.setLineDash(down ? [6, 4] : []);
        ctx.strokeStyle = down ? '#dc3545' : '#64748b';
        ctx.moveTo(busX, p.y);
        ctx.lineTo(p.x - dir * 27, p.y);
        ctx.stroke();
    });
    ctx.restore();
}

function drawTopoLinks(ctx) {
    if (!network) return;
    const pos = network.getPositions();
    const t = topoLayoutNodes();
    const itc = serversOn(t.left);
    const drc = serversOn(t.right);
    const zoneTitle = n => (n.siteCd || n.name) + ' · ' + (n.desc || n.location || '');

    if (t.left) drawTopoZone(ctx, pos, t.left.id, itc, '#f0fbf3', '#28a745', zoneTitle(t.left));
    if (t.right) drawTopoZone(ctx, pos, t.right.id, drc, '#fff5f5', '#dc3545', zoneTitle(t.right));

    // Central Hub -> 사이트 스위치
    const hub = t.hub ? pos[t.hub.id] : null;
    if (hub) {
        [t.left, t.right].filter(Boolean).map(n => [n.id, (n.siteCd || n.name) + ' Route']).forEach(r => {
            const sw = pos[r[0]];
            if (!sw) return;
            ctx.save();
            ctx.lineWidth = 3;
            ctx.strokeStyle = '#334155';
            ctx.beginPath();
            ctx.moveTo(hub.x, hub.y);
            ctx.lineTo(sw.x, hub.y);
            ctx.lineTo(sw.x, sw.y - 28);
            ctx.stroke();
            ctx.fillStyle = '#334155';
            ctx.font = 'bold 12px Segoe UI, sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText(r[1], (hub.x + sw.x) / 2, hub.y - 8);
            ctx.restore();
        });
    }

    if (t.left) drawTopoBus(ctx, pos, t.left.id, itc, 1);
    if (t.right) drawTopoBus(ctx, pos, t.right.id, drc, -1);

    // ITC <-> DRC 동기화(Sync) 링크
    itc.forEach(s => {
        const pair = topoPairOf(s);
        if (!pair || !pos[s.id] || !pos[pair.id]) return;
        const a = pos[s.id], b = pos[pair.id];
        const down = [s, pair].some(topoIsDown);
        ctx.save();
        ctx.lineWidth = 2;
        ctx.setLineDash([7, 5]);
        ctx.strokeStyle = down ? '#dc3545' : '#6c757d';
        ctx.beginPath();
        ctx.moveTo(a.x + 27, a.y);
        ctx.lineTo(b.x - 27, b.y);
        ctx.stroke();
        ctx.setLineDash([]);
        const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
        const text = down ? 'Sync ✕' : 'Sync';
        ctx.font = 'bold 11px Segoe UI, sans-serif';
        const tw = ctx.measureText(text).width + 12;
        topoRoundRect(ctx, mx - tw / 2, my - 10, tw, 20, 10);
        ctx.fillStyle = '#ffffff';
        ctx.fill();
        ctx.lineWidth = 1;
        ctx.strokeStyle = down ? '#dc3545' : '#adb5bd';
        ctx.stroke();
        ctx.fillStyle = down ? '#dc3545' : '#495057';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(text, mx, my + 1);
        ctx.restore();
    });
}

function topoBar(label, value, color) {
    const n = Math.max(0, Math.min(100, parseFloat(value) || 0));
    return '<div class="topo-bar"><span class="topo-bar-label">' + label + '</span>' +
        '<span class="topo-bar-track"><i style="width:' + n + '%;background:' + color + '"></i></span>' +
        '<span class="topo-bar-value">' + escapeHtml(value) + '</span></div>';
}

function topoDetailHtml(server) {
    const isDown = topoIsDown(server);
    const noData = serverBaseDown(server) ? null : topoNoDataReason(server);
    const lastReport = (agentStates[server.id] || {}).lastReportDt;
    const type = getServerType(server);
    const typeLabel = { ap: 'AP 서버', db: 'DB 서버', nfs: 'NFS 스토리지' }[type];
    const badgeClass = isDown ? 'badge-stopped' : (serverBaseStatus(server) === 'Active' ? 'badge-active' : 'badge-standby');
    const headColor = isDown ? '#dc3545' : '#203a43';
    const pair = topoPairOf(server);
    const procs = (Array.isArray(server.processes) ? server.processes : [])
        .map(p => noData ? Object.assign({}, p, { running: false, pid: '-' }) : p);   // 서버 정보 수신 안 됨 → 종료
    const procHtml = procs.length
        ? '<ul class="topo-proc">' + procs.map(p =>
            '<li><span class="' + (p.running ? 'on' : 'off') + '">' + (p.running ? '● 실행 중' : '○ 종료') + '</span> ' +
            escapeHtml(p.name) + '<small> PID ' + escapeHtml(p.pid) + '</small></li>').join('') + '</ul>'
        : '<div style="font-size:12px;color:#666;">등록된 프로세스가 없습니다.</div>';
    return '<div class="topo-detail-head" style="border-bottom:2px solid ' + headColor + ';">' +
        '<img src="' + topoIconUri(type, topoViewStatus(server)) + '" width="44" height="44" alt="' + typeLabel + ' 아이콘">' +
        '<div><div class="topo-detail-name" style="color:' + headColor + ';">' + escapeHtml(server.systemName) + '</div>' +
        '<div class="topo-detail-sub">' + typeLabel + ' · ' + escapeHtml(server.location) + '</div></div>' +
        '<span class="badge ' + badgeClass + '">' + escapeHtml(isDown ? 'Down' : serverBaseStatus(server)) + '</span></div>' +
        '<table class="detail-table">' +
        '<tr><th>FQDN</th><td>' + escapeHtml(server.fqdn) + '</td></tr>' +
        '<tr><th>IP 주소</th><td><code>' + escapeHtml(server.ip) + '</code></td></tr>' +
        '<tr><th>OS 종류</th><td>' + escapeHtml(server.os) + '</td></tr>' +
        '<tr><th>동기화 상태</th><td><span style="color:' + (isDown ? '#dc3545' : 'inherit') + ';font-weight:' + (isDown ? 'bold' : 'normal') + ';">' + escapeHtml(isDown ? 'DISCONNECTED (Link Down)' : server.sync) + '</span></td></tr>' +
        '<tr><th>동기화 대상</th><td>' + (pair ? escapeHtml(pair.systemName) + ' (' + escapeHtml(pair.ip) + ')' : '-') + '</td></tr>' +
        '<tr><th>활성 세션수</th><td>' + (isDown ? '0' : escapeHtml(server.sessions)) + ' 개</td></tr>' +
        '</table>' +
        '<div class="topo-bars">' +
        topoBar('CPU', isDown ? '0.0%' : server.cpu, '#d9534f') +
        topoBar('MEM', isDown ? '0.0%' : server.mem, '#0275d8') +
        topoBar('DISK', isDown ? 'N/A' : server.disk, '#6f42c1') + '</div>' +
        '<div class="topo-section-title">주요 프로세스 <span>' + procs.filter(p => p.running).length + ' / ' + procs.length + ' 실행 중</span></div>' + procHtml +
        (noData ? '<div class="topo-down-warn">경고: 원격 서버에서 기본 정보를 받아오지 못해 Down 으로 표시합니다. (' + escapeHtml(noData) +
            (lastReport ? ', 마지막 보고 ' + escapeHtml(lastReport) : '') + ') 서버 상태와 에이전트(krcasmrx11_client)를 확인하세요.</div>'
        : isDown ? '<div class="topo-down-warn">경고: 해당 서버가 다운(Down) 상태입니다. 네트워크 연결 및 전원 상태를 확인하세요.</div>' : '') +
        topoDaemonNoticeHtml(server, isDown) +
        '<div class="topo-actions admin-only"><button class="btn btn-primary" onclick="openServerEdit(' + server.id + ')">서버 정보 수정</button>' +
        '<button class="btn btn-danger" onclick="deleteServerFromDetail(' + server.id + ')">서버 삭제</button></div>' +
        '<button class="btn btn-primary" style="margin-top:8px;width:100%;" onclick="jumpToProcessTab(' + server.id + ')">이 서버의 프로세스 관리 화면으로 이동</button>' +
        '<button class="btn btn-console admin-only" onclick="openConsoleConnectById(' + server.id + ')">&gt;_ 웹 콘솔 원격 접속 (SSH)</button>' +
        '<p class="topo-hint admin-only">서버를 더블클릭해도 접속 화면이 열립니다.</p>' +
        '<p class="topo-hint user-only">서버 수정/삭제와 웹 콘솔은 관리자만 사용할 수 있습니다.</p>' +
        '<div class="topo-agent" id="topoAgent" data-server="' + server.id + '"><div class="topo-section-title">서버 에이전트</div>' +
        '<div class="topo-agent-body">상태 확인 중...</div></div>';
}

function topoNodeInfoHtml(id) {
    const n = topoNodes.find(x => x.id === id);
    if (!n) return '';
    if (n.type === 'HUB') {
        return '<div class="topo-detail-head" style="border-bottom:2px solid #d99a00;"><img src="' + topoIconUri('hub') + '" width="44" height="44" alt="라우터 아이콘">' +
            '<div><div class="topo-detail-name">' + escapeHtml(n.name) + '</div><div class="topo-detail-sub">중앙 라우팅 · 관리 노드</div></div></div>' +
            '<p style="font-size:13px;color:#444;line-height:1.6;">사이트 스위치를 연결하는 중앙 노드입니다. 연결 서버 총 ' + serversData.length + '대.</p>' + topoManualConsoleBtn();
    }
    const left = topoLayoutNodes().left;
    const isLeft = left && left.id === n.id;
    const list = serversOn(n);
    const cnt = st => list.filter(s => topoViewStatus(s) === st).length;
    return '<div class="topo-detail-head" style="border-bottom:2px solid ' + (isLeft ? '#28a745' : '#dc3545') + ';"><img src="' + topoIconUri(isLeft ? 'switch-itc' : 'switch-drc') + '" width="44" height="44" alt="스위치 아이콘">' +
        '<div><div class="topo-detail-name">' + escapeHtml(n.name) + '</div><div class="topo-detail-sub">' + escapeHtml(n.desc || n.location || '') + '</div></div></div>' +
        '<p style="font-size:13px;color:#444;line-height:1.6;">연결 서버 ' + list.length + '대 · Active ' + cnt('Active') + ' / Standby ' + cnt('Standby') + ' / Down ' + cnt('Down') + '</p>' + topoManualConsoleBtn();
}

// ---------- 서버 에이전트 (krcasmrx11_client) : 상태 / 토큰 발급 ----------
const AGENT_LABEL = { NONE: '미설치 (토큰 미발급)', WAITING: '보고 대기', OK: '정상', STALE: '보고 끊김', LOST: '하트비트 끊김', PAUSED: '수집 중지 (웹)', STOPPED: '데몬 종료' };

function isAdminUser() {
    const bar = document.getElementById('userBar');
    return !!bar && bar.getAttribute('data-role') === 'ADMIN';
}

async function loadTopoAgent(serverId) {
    const box = document.getElementById('topoAgent');
    if (!box || box.getAttribute('data-server') != serverId) return;
    let a;
    try {
        a = await apiRequest('GET', 'api/agents/' + encodeURIComponent(serverId));
    } catch (e) {
        box.querySelector('.topo-agent-body').textContent = '에이전트 상태를 불러오지 못했습니다: ' + e.message;
        return;
    }
    if (box.getAttribute('data-server') != serverId) return;   // 그 사이 다른 서버 선택
    const rows = [['상태', '<span class="topo-agent-state ' + escapeHtml(a.state) + '">' + escapeHtml(AGENT_LABEL[a.state] || a.state) + '</span>']];
    if (a.lastReportDt) rows.push(['마지막 보고', escapeHtml(a.lastReportDt)]);
    if (a.state !== 'NONE') rows.push(['하트비트', topoHeartbeatHtml(serverId, a)]);
    if (a.agentHost) rows.push(['호스트', escapeHtml(a.agentHost)]);
    if (a.agentOs) rows.push(['OS', escapeHtml(a.agentOs)]);
    if (a.peerStatus) rows.push(['동기화 대상 통신', escapeHtml(a.peerStatus.replace('|', ' : '))]);
    if (a.issueDt) rows.push(['토큰 발급', escapeHtml(a.issueDt) + (a.issueUser ? ' (' + escapeHtml(a.issueUser) + ')' : '')]);
    const btn = isAdminUser()
        ? '<button type="button" class="btn btn-console" onclick="issueAgentToken(' + serverId + ')">' + (a.state === 'NONE' ? '에이전트 토큰 발급' : '에이전트 토큰 재발급') + '</button>'
        : '';
    box.querySelector('.topo-agent-body').innerHTML =
        '<table class="detail-table">' + rows.map(r => '<tr><th>' + r[0] + '</th><td>' + r[1] + '</td></tr>').join('') + '</table>' + btn;
}

const HB_STATE_LABEL = { ALIVE: '수신 중', LOST: '끊김', STOPPED: '데몬 종료', NONE: '없음' };

/** 구성도 상세 : 데몬만 멈춘 경우 (서버 정상) 재시작 알림 */
function topoDaemonNoticeHtml(server, isDown) {
    const issue = isDown ? null : topoDaemonIssue(server);
    if (!issue) return '';
    const btn = isAdminUser() && topoCanRestart(server)
        ? '<button type="button" class="btn btn-restart" onclick="restartFromTopo(' + server.id + ')">데몬 재시작</button>'
        : '<div class="topo-daemon-cmd">서버에서 <code>bin/scb_client.sh start</code>' + (isAdminUser() ? ' (웹 재시작은 클라이언트 1.5 이상)' : '') + '</div>';
    return '<div class="topo-daemon-warn" role="alert">⚠ ' + escapeHtml(issue) + ' - 서버는 정상입니다 (하트비트 수신 중). 데몬(scb_client)만 재시작하면 됩니다.' + btn + '</div>';
}

async function restartFromTopo(serverId) {
    if (await restartDaemon(serverId)) topoRefreshNow(false);
}

/** 구성도 상세의 하트비트 행 : 상태·마지막 수신·주기, 관리자는 주기 변경 */
function topoHeartbeatHtml(serverId, a) {
    const st = HB_STATE_LABEL[a.hbState] ? a.hbState : 'NONE';
    let html = '<span class="hb-badge ' + st + '">' + HB_STATE_LABEL[st] + '</span>';
    if (a.lastHbDt) html += ' <small>' + escapeHtml(a.lastHbDt) + (a.lastHbAgoSec != null ? ' (' + a.lastHbAgoSec + '초 전)' : '') + '</small>';
    if (a.hbMisses != null && a.hbState !== 'NONE') {
        html += ' <span class="hb-miss' + (a.hbMisses >= (a.hbMissLimit || 3) ? ' bad' : a.hbMisses > 0 ? ' warn' : '') + '">연속 미수신 ' +
            Math.min(a.hbMisses, a.hbMissLimit || 3) + ' / ' + (a.hbMissLimit || 3) + '</span>';
    }
    if (a.daemonIssue) html += '<div class="topo-hb-daemon">⚠ ' + escapeHtml(a.daemonIssue) + ' (서버 정상)' +
        (a.restartPending ? ' · 재시작 요청 전달 중' : '') + '</div>';
    html += '<div class="topo-hb-sub">주기 <b>' + a.hbIntervalSec + '초</b> · 서버 다운 기준 ' +
        (a.hbClient ? a.hbTimeoutSec + '초 (3회 연속 미수신)' : a.hbTimeoutSec + '초') +
        (!a.hbClient && a.lastHbDt ? ' · <span class="hb-tag" title="하트비트 셸이 없는 1.3 이하 - 데몬의 웹 확인(10초)으로 판단">이전 방식</span>' : '') + '</div>';
    if (isAdminUser()) {
        const opts = (a.hbIntervals || [3, 5, 10, 15, 30, 60]).map(s =>
            '<option value="' + s + '"' + (s === a.hbIntervalSec ? ' selected' : '') + '>' + s + '초</option>').join('');
        html += '<div class="topo-hb-set"><label for="topoHbSel">하트비트 주기</label>' +
            '<select id="topoHbSel" class="am-interval">' + opts + '</select>' +
            '<button type="button" class="proc-btn" onclick="saveHeartbeatInterval(' + serverId + ')">적용</button>' +
            '<span class="topo-hb-msg" id="topoHbMsg" role="status"></span></div>';
    }
    return html;
}

async function saveHeartbeatInterval(serverId) {
    const sel = document.getElementById('topoHbSel');
    const msg = document.getElementById('topoHbMsg');
    if (!sel) return;
    const sec = Number(sel.value);
    try {
        await apiRequest('PUT', 'api/agents/' + encodeURIComponent(serverId) + '/heartbeat-interval', { sec: sec });
    } catch (e) {
        if (msg) msg.textContent = e.message;
        return;
    }
    await loadTopoAgent(serverId);
    const m = document.getElementById('topoHbMsg');
    if (m) m.textContent = sec + '초로 바꿨습니다. 다음 하트비트부터 적용됩니다.';
}

async function issueAgentToken(serverId) {
    const server = serversData.find(s => s.id === serverId);
    const name = server ? server.systemName : serverId;
    if (!confirm('[' + name + '] 의 에이전트 토큰을 발급합니다.\n이미 발급된 토큰이 있으면 즉시 무효가 되어, 서버의 client.env 도 새 토큰으로 바꿔야 합니다.\n\n계속하시겠습니까?')) return;
    let r;
    try {
        r = await apiRequest('POST', 'api/agents/' + encodeURIComponent(serverId) + '/token');
    } catch (e) { alert(e.message); return; }
    document.getElementById('agentTokenTitle').innerText = name + ' 에이전트 토큰';
    document.getElementById('agentTokenEnv').value = r.clientEnv;
    // 서버에서 접속할 웹 주소 확인 (localhost 는 서버 자신을 가리킴)
    const warn = document.getElementById('agentUrlWarn');
    if (warn) {
        const c = r.urlCandidates || [];
        warn.innerHTML = r.urlWarning
            ? '⚠ ' + escapeHtml(r.urlWarning) + (c.length > 1 ? '<br>이 PC 의 다른 주소 : ' + c.map(u => '<code>' + escapeHtml(u) + '</code>').join(', ') : '')
            : '';
        warn.hidden = !r.urlWarning;
    }
    document.getElementById('agentTokenModal').style.display = 'flex';
    loadTopoAgent(serverId);
}

function copyAgentEnv() {
    const t = document.getElementById('agentTokenEnv');
    t.select();
    const done = () => { document.getElementById('agentCopyMsg').textContent = '복사했습니다.'; };
    if (navigator.clipboard) navigator.clipboard.writeText(t.value).then(done, () => { document.execCommand('copy'); done(); });
    else { document.execCommand('copy'); done(); }
}

function closeAgentToken() {
    const m = document.getElementById('agentTokenModal');
    if (!m) return;
    document.getElementById('agentTokenEnv').value = '';   // 화면에 토큰을 남기지 않음
    document.getElementById('agentCopyMsg').textContent = '';
    m.style.display = 'none';
}

function topoManualConsoleBtn() {
    return '<button class="btn btn-console" onclick="openConsoleConnect(null)">&gt;_ 주소 입력하여 원격 접속</button>' +
        '<p class="topo-hint">서버가 아닌 노드를 더블클릭해도 주소 입력 화면이 열립니다.</p>';
}

function openConsoleConnectById(serverId) {
    openConsoleConnect(serversData.find(s => s.id === serverId) || null);
}

function openServerModal(server) {
    const isDown = server.status === 'Down' || server.status === 'Stopped';
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
    set('modalServerName', server.systemName + ' 서버 상태 정보');
    set('modalHealth', isDown ? 'Down (' + (server.noData && !serverBaseDown(server) ? '서버 정보 수신 안 됨 - ' + server.noData : 'Unreachable') + ')' : (server.status === 'Standby' ? 'Standby (Ready)' : 'Normal (Healthy)'));
    set('modalCpu', isDown ? '0.0% (DOWN)' : server.cpu);
    set('modalMemory', isDown ? '0.0% (DOWN)' : server.mem);
    set('modalDisk', isDown ? 'N/A (Unreachable)' : server.disk);
    set('modalSync', isDown ? 'DISCONNECTED (Link Down)' : server.sync);
    set('modalSessions', (isDown ? '0' : server.sessions) + ' 개');
    const dot = document.querySelector('#statusModal .health-indicator');
    if (dot) dot.style.backgroundColor = isDown ? '#dc3545' : (server.status === 'Standby' ? '#f0ad4e' : '#28a745');
    document.getElementById('statusModal').style.display = 'flex';
}

function closeModal() {
    const m = document.getElementById('statusModal');
    if (m) m.style.display = 'none';
}

function renderTopoLegend() {
    const el = document.getElementById('topoLegend');
    if (!el) return;
    const ico = (uri, label) => '<div class="legend-item"><img src="' + uri + '" width="24" height="24" alt="">' + label + '</div>';
    const dot = (c, label) => '<div class="legend-item"><span class="legend-dot" style="background:' + c + ';"></span>' + label + '</div>';
    el.innerHTML =
        ico(topoIconUri('hub'), '라우터 (Central Hub)') +
        ico(topoIconUri('switch-itc'), 'ITC 스위치') +
        ico(topoIconUri('switch-drc'), 'DRC 스위치') +
        ico(topoIconUri('ap', 'Active'), 'AP 서버') +
        ico(topoIconUri('db', 'Active'), 'DB 서버') +
        ico(topoIconUri('nfs', 'Active'), 'NFS 스토리지') +
        dot('#28a745', 'Active') + dot('#f0ad4e', 'Standby') + dot('#dc3545', 'Down (다운 / 서버 정보 수신 안 됨)') +
        '<div class="legend-item"><span class="legend-dash"></span>Sync 링크</div>';
}

function rebuildTopology() {
    if (!nodes) return;
    nodes.clear();
    nodes.add(buildTopoNodes());
    if (network) network.fit({ animation: false });
}

function initNetwork() {
    if (network) return;

    nodes = new vis.DataSet(buildTopoNodes());
    edges = new vis.DataSet([]); // 링크는 drawTopoLinks()에서 직접 그립니다.

    const container = document.getElementById('network-container');
    const options = {
        layout: { hierarchical: false },
        physics: false,
        interaction: { hover: true, dragNodes: true }
    };

    network = new vis.Network(container, { nodes: nodes, edges: edges }, options);
    network.on('beforeDrawing', drawTopoLinks);
    network.fit({ animation: false });

    network.on('click', function (params) {
        if (params.nodes.length === 0) return;
        const nodeId = params.nodes[0];
        const server = serversData.find(s => s.id === nodeId);
        document.getElementById('detail-content').innerHTML = server ? topoDetailHtml(server) : topoNodeInfoHtml(nodeId);
        if (server) loadTopoAgent(server.id);
    });

    // 더블클릭: 웹 콘솔(터미널) 접속 확인 화면
    //  - 서버 노드        → 해당 서버로 터미널을 띄울지 확인 (포트 변경 가능)
    //  - 서버가 아닌 노드 → 주소/포트를 직접 입력해 접속
    network.on('doubleClick', function (params) {
        if (params.nodes.length === 0) return;
        const server = serversData.find(s => s.id === params.nodes[0]);
        openConsoleConnect(server || null);
    });

    renderTopoLegend();
    updateRemoveSelect();

    // 화면 크기가 바뀌면(창 크기, 휴대폰 가로/세로) 구성도를 다시 맞춤
    let fitTimer = null;
    window.addEventListener('resize', () => {
        clearTimeout(fitTimer);
        fitTimer = setTimeout(() => network.fit({ animation: false }), 200);
    });

    const focusId = Number(new URLSearchParams(location.search).get('server'));
    const focus = focusId ? serversData.find(s => s.id === focusId) : null;
    if (focus) {
        network.selectNodes([focus.id]);
        document.getElementById('detail-content').innerHTML = topoDetailHtml(focus);
        loadTopoAgent(focus.id);
    }
}

// ================= 웹 콘솔 (원격 접속) =================
const DEFAULT_SSH_PORT = 22;

// IPv4 주소 또는 호스트명(FQDN)
function isValidConsoleHost(host) {
    const h = String(host || '').trim();
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) return h.split('.').every(n => Number(n) <= 255);
    return h.length <= 253 && /^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$/.test(h);
}

function isValidConsolePort(port) {
    const n = Number(port);
    return String(port).trim() !== '' && Number.isInteger(n) && n >= 1 && n <= 65535;
}

// 입력한 주소가 등록된 서버(IP/FQDN)이면 그 서버를 반환
function findServerByHost(host) {
    const h = String(host || '').trim().toLowerCase();
    return serversData.find(s => String(s.ip).toLowerCase() === h || String(s.fqdn).toLowerCase() === h) || null;
}

// 웹 콘솔 창 열기 - 접속 대상(서버+포트)마다 별도 창 하나를 사용합니다.
function openWebConsole(target) {
    const port = Number(target.port) || DEFAULT_SSH_PORT;
    const server = target.serverId != null ? serversData.find(s => s.id === target.serverId) : findServerByHost(target.host);
    const query = server
        ? 'id=' + encodeURIComponent(server.id) + '&port=' + port
        : 'host=' + encodeURIComponent(String(target.host).trim()) + '&port=' + port;
    const name = 'webconsole_' + (server ? server.id : String(target.host).replace(/[^A-Za-z0-9]/g, '_')) + '_' + port;
    const w = window.open('console?' + query, name,
        'width=1100,height=720,menubar=no,toolbar=no,location=no,status=no,resizable=yes');
    if (w) w.focus();
    else alert('팝업이 차단되어 웹 콘솔을 열 수 없습니다. 브라우저에서 이 사이트의 팝업을 허용해 주세요.');
    return !!w;
}

// 접속 확인 / 주소 입력 화면 (네트워크 구성도의 consoleConnectModal)
let consoleConnectServer = null;

function openConsoleConnect(server) {
    const modal = document.getElementById('consoleConnectModal');
    if (!modal) return;
    if (!isAdminUser()) {
        alert('웹 콘솔(원격 접속)은 관리자(ADMIN)만 사용할 수 있습니다.');
        return;
    }
    consoleConnectServer = server || null;
    const info = document.getElementById('ccInfo');
    const host = document.getElementById('ccHost');
    const port = document.getElementById('ccPort');
    document.getElementById('ccError').textContent = '';
    port.value = DEFAULT_SSH_PORT;

    if (server) {
        const isDown = server.status === 'Down' || server.status === 'Stopped';
        document.getElementById('ccTitle').textContent = '웹 콘솔 접속 확인';
        info.innerHTML =
            '<div class="cc-question"><b>' + escapeHtml(server.systemName) + '</b> 서버에 터미널(SSH) 화면을 띄우시겠습니까?</div>' +
            '<div class="cc-meta">' + escapeHtml(server.fqdn) + ' · ' + escapeHtml(server.os) + ' · ' + escapeHtml(server.location) +
            ' · <span class="badge ' + (isDown ? 'badge-stopped' : server.status === 'Active' ? 'badge-active' : 'badge-standby') + '">' + escapeHtml(server.status + serverDownNote(server)) + '</span></div>' +
            (isDown ? '<div class="cc-warn">이 서버는 Down 상태입니다' + (server.noData && !serverBaseDown(server) ? ' (서버 정보 수신 안 됨 - ' + escapeHtml(server.noData) + ')' : '') + '. 접속이 실패할 수 있습니다.</div>' : '');
        host.value = server.ip;
        host.readOnly = true;
        document.getElementById('ccStatusBtn').style.display = '';
    } else {
        document.getElementById('ccTitle').textContent = '원격 접속 (주소 직접 입력)';
        info.innerHTML = '<div class="cc-question">서버 노드가 아닙니다. 접속할 서버의 <b>주소</b>와 <b>포트</b>를 입력하세요.</div>' +
            '<div class="cc-meta">등록된 서버의 IP/FQDN을 입력하면 해당 서버 정보로 접속합니다.</div>';
        host.value = '';
        host.readOnly = false;
        document.getElementById('ccStatusBtn').style.display = 'none';
    }
    modal.style.display = 'flex';
    (server ? document.querySelector('#ccForm button[type="submit"]') : host).focus();
}

function closeConsoleConnect() {
    const modal = document.getElementById('consoleConnectModal');
    if (modal) modal.style.display = 'none';
}

function submitConsoleConnect(e) {
    e.preventDefault();
    const hostEl = document.getElementById('ccHost');
    const portEl = document.getElementById('ccPort');
    const err = document.getElementById('ccError');
    const host = hostEl.value.trim();
    const port = portEl.value.trim();
    if (!isValidConsoleHost(host)) { err.textContent = '접속 주소를 올바르게 입력하세요. (예: 10.0.13.217 또는 host.example.com)'; hostEl.focus(); return; }
    if (!isValidConsolePort(port)) { err.textContent = '포트는 1 ~ 65535 사이의 숫자로 입력하세요.'; portEl.focus(); return; }
    const ok = consoleConnectServer
        ? openWebConsole({ serverId: consoleConnectServer.id, port: Number(port) })
        : openWebConsole({ host: host, port: Number(port) });
    if (ok) closeConsoleConnect();
}

function showStatusFromConsoleConnect() {
    const server = consoleConnectServer;
    closeConsoleConnect();
    if (server) openServerModal(server);
}

document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeConsoleConnect();
});

function renderSpecTable() {
    const tbody = document.getElementById('specTableBody');
    if (!tbody) return;
    const box = document.getElementById('searchInput');
    const keyword = box ? box.value.trim().toLowerCase() : '';
    const list = serversData.filter(s => !keyword ||
        [s.systemName, s.fqdn, s.ip, s.location].some(v => String(v || '').toLowerCase().indexOf(keyword) !== -1));
    if (!list.length) {
        tbody.innerHTML = '<tr><td colspan="9" class="svc-empty">검색 결과가 없습니다.</td></tr>';
        return;
    }
    tbody.innerHTML = list.map((s, index) => {
        const isDown = s.status === 'Down' || s.status === 'Stopped';
        const badgeClass = isDown ? 'badge-stopped' : ((s.status === 'Active') ? 'badge-active' : 'badge-standby');
        const netStatusBadge = isDown
            ? '<span class="badge badge-stopped">DISCONNECTED (Down)</span>' + (s.noData && !serverBaseDown(s) ? '<div class="spec-nodata">서버 정보 수신 안 됨 · ' + escapeHtml(s.noData) + '</div>' : '')
            : '<span class="badge ' + badgeClass + '">' + escapeHtml(s.sync) + '</span>';
        const procs = s.processes || [];
        const run = procs.filter(p => p.running).length;
        return '<tr onclick="jumpToProcessTab(' + s.id + ')" title="클릭 시 해당 서버의 프로세스 관리 화면으로 이동합니다">' +
            '<td>' + (index + 1) + '</td><td><b>' + escapeHtml(s.systemName) + '</b></td><td>' + escapeHtml(s.fqdn) + '</td>' +
            '<td>' + escapeHtml(s.os) + '</td><td>' + escapeHtml(s.location) + '</td><td>' + escapeHtml(s.ip) + '</td>' +
            '<td><span class="badge ' + badgeClass + '">' + escapeHtml(s.status) + '</span></td><td>' + netStatusBadge + '</td>' +
            '<td class="spec-proc" onclick="event.stopPropagation()">' +
            '<span class="spec-proc-cnt">' + procs.length + '건' + (procs.length ? ' <small>(실행 ' + run + ')</small>' : '') + '</span>' +
            '<button type="button" class="svc-btn status" onclick="openSpecProc(' + s.id + ')">' + (isAdminUser() ? '관리' : '보기') + '</button></td>' +
            '</tr>';
    }).join('');
}

function jumpToProcessTab(serverId) {
    localStorage.setItem('selectedServerId', serverId);
    window.location.href = 'processes';
}

// 프로세스 관리 화면(/processes)은 service.list 기준으로 js/process_service.js 에서 처리합니다.

function filterSpecTable() {
    renderSpecTable();
}

function updateRemoveSelect() {
    const select = document.getElementById('removeNodeSelect');
    if (!select) return;
    select.innerHTML = '';
    serversData.forEach(s => {
        const opt = document.createElement('option');
        opt.value = s.id;
        opt.text = s.systemName;
        select.appendChild(opt);
    });
}

async function addServer() {
    const form = {
        systemName: document.getElementById('addName').value.trim(),
        fqdn: document.getElementById('addFqdn').value.trim(),
        os: document.getElementById('addOs').value.trim(),
        ip: document.getElementById('addIp').value.trim(),
        switchNodeId: parseInt(document.getElementById('addParent').value, 10),
        status: document.getElementById('addStatus').value
    };
    if (!form.systemName || !form.fqdn || !form.ip) {
        alert('시스템명, FQDN, IP 주소를 모두 입력해주세요.');
        return;
    }
    let created;
    try {
        created = await apiRequest('POST', TOPO_API + '/servers', form);
    } catch (e) { apiAlert(e); return; }

    serversData.push(created);
    if (nodes) rebuildTopology();
    updateRemoveSelect();
    document.getElementById('addName').value = '';
    document.getElementById('addFqdn').value = '';
    document.getElementById('addIp').value = '';
    alert('서버가 성공적으로 추가되었습니다.');
}

async function removeServerById(nodeId) {
    try {
        await apiRequest('DELETE', TOPO_API + '/servers/' + encodeURIComponent(nodeId));
    } catch (e) { apiAlert(e); return; }
    serversData = serversData.filter(s => s.id !== nodeId);
    serversData.forEach(s => { if (s.pairId === nodeId) s.pairId = null; });   // DB 와 동일하게 동기화 대상 해제
    if (nodes) rebuildTopology();

    updateRemoveSelect();
    const detailContent = document.getElementById('detail-content');
    if (detailContent) {
        detailContent.innerHTML = '<p style="color: #888; font-style: italic; font-size: 13px;">다이어그램에서 서버 노드를 클릭하세요.</p>';
    }
    alert('서버가 제거되었습니다.');
}

function removeServer() {
    const select = document.getElementById('removeNodeSelect');
    if (!select) return;
    const nodeId = parseInt(select.value);

    if (!nodeId) {
        alert('제거할 서버를 선택해주세요.');
        return;
    }
    removeServerById(nodeId);
}

function deleteServerFromDetail(serverId) {
    const server = serversData.find(s => s.id === serverId);
    if (!server) return;
    if (confirm('정말 "' + server.systemName + '" 서버를 삭제하시겠습니까?\n메인 홈의 서버 항목에서도 함께 삭제됩니다.')) {
        removeServerById(serverId);
    }
}

// --- 서버 정보 수정 ---
function openServerEdit(serverId) {
    const s = serversData.find(x => x.id === serverId);
    if (!s) return;
    document.getElementById('editServerId').value = s.id;
    document.getElementById('editName').value = s.systemName;
    document.getElementById('editFqdn').value = s.fqdn;
    document.getElementById('editOs').value = s.os;
    document.getElementById('editIp').value = s.ip;
    document.getElementById('editLocation').value = s.switchNodeId;
    const base = serverBaseStatus(s);   // 화면 Down(정보 수신 안 됨)이 아니라 등록된 상태
    document.getElementById('editStatus').value = (base === 'Stopped') ? 'Down' : base;
    document.getElementById('serverEditModal').style.display = 'flex';
}

function closeServerEdit() {
    document.getElementById('serverEditModal').style.display = 'none';
}

async function saveServerEdit() {
    const id = parseInt(document.getElementById('editServerId').value, 10);
    const s = serversData.find(x => x.id === id);
    if (!s) return;
    const form = {
        systemName: document.getElementById('editName').value.trim(),
        fqdn: document.getElementById('editFqdn').value.trim(),
        os: document.getElementById('editOs').value.trim() || s.os,
        ip: document.getElementById('editIp').value.trim(),
        switchNodeId: parseInt(document.getElementById('editLocation').value, 10),
        status: document.getElementById('editStatus').value
    };
    if (!form.systemName || !form.fqdn || !form.ip) {
        alert('시스템명, FQDN, IP 주소를 모두 입력해주세요.');
        return;
    }
    let updated;
    try {
        updated = await apiRequest('PUT', TOPO_API + '/servers/' + id, form);
    } catch (e) { apiAlert(e); return; }

    serversData[serversData.indexOf(s)] = updated;
    closeServerEdit();
    if (nodes) rebuildTopology();
    updateRemoveSelect();
    const detail = document.getElementById('detail-content');
    if (detail) { detail.innerHTML = topoDetailHtml(updated); loadTopoAgent(updated.id); }
    alert('서버 정보가 수정되었습니다. 메인 홈의 서버 항목에도 반영됩니다.');
}


// --- 서버별 주요 프로세스 (메인 홈) ---
// 서버 목록은 네트워크 구성도와 동일한 serversData를 사용합니다.
let processScope = 'all';
let procSearchServerId = null;

function escapeHtml(v) {
    return String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function setProcessScope(scope) {
    processScope = scope;
    document.querySelectorAll('#processScope button').forEach(b => {
        b.classList.toggle('active', b.getAttribute('data-scope') === scope);
    });
    renderProcessSection();
}

// --- 주요 프로세스 미등록 서버 : 프로세스 관리 화면(service.list)에 등록된 목록만 표시 ---
// 데이터 : GET /api/services/by-server (tb_service, 상태/PID 는 에이전트가 scb_main.sh status 로 보고)
let svcByServer = {};
const SVC_STATE = { RUNNING: ['● 실행 중', 'on'], STOPPED: ['○ 종료', 'off'], UNKNOWN: ['? 미확인', 'unk'] };

async function loadServiceLists() {
    try {
        svcByServer = await apiRequest('GET', 'api/services/by-server') || {};
    } catch (e) {
        console.warn('프로세스 관리 목록을 불러오지 못했습니다:', e);
    }
}

function serviceListOf(server) {
    return svcByServer[server.id] || svcByServer[String(server.id)] || [];
}

/** [실행 중, 전체] - 주요 프로세스가 없으면 서비스 기준 */
function procCounts(server) {
    const procs = server.processes || [];
    if (procs.length) return [procs.filter(p => p.running).length, procs.length];
    const svcs = serviceListOf(server);
    return [svcs.filter(s => s.state === 'RUNNING').length, svcs.length];
}

// --- 그래프에 표시할 주요 프로세스 선택 (카드 목록의 체크박스, 최대 GRAPH_MAX 개) ---
// 주요 프로세스(tb_server_process) 가 있으면 그것을, 없으면 프로세스 관리(service.list) 서비스를 선택 대상으로 함
// 선택은 브라우저에 서버별로 저장 (localStorage procGraphSel), 처음에는 주요 프로세스 전체 / 서비스는 CPU 높은 실행 중 5개
const GRAPH_MAX = 6;   // = PROC_COLORS 색 수 (PROC_COLORS 는 아래에서 선언되므로 여기서 참조하면 안 됨)
const GRAPH_SEL_KEY = 'procGraphSel';

function loadGraphSel() {
    try { return JSON.parse(localStorage.getItem(GRAPH_SEL_KEY) || '{}') || {}; } catch (e) { return {}; }
}

function saveGraphSel(serverId, keys) {
    const all = loadGraphSel();
    all[serverId] = keys;
    try { localStorage.setItem(GRAPH_SEL_KEY, JSON.stringify(all)); } catch (e) { /* 저장 불가 시 이번 화면만 */ }
}

/** 선택 대상 목록 : {mode: 'proc' | 'svc', items: [{key, name, running, cpu()}]} */
function graphItems(server) {
    const h = serverHistory(server);
    const procs = server.processes || [];
    if (procs.length) {
        return { mode: 'proc', items: procs.map(p => ({
            key: 'p' + p.id, name: p.name, running: p.running, cpu: () => buildProcessSeries(server, p) })) };
    }
    return { mode: 'svc', items: serviceListOf(server).map(s => ({
        key: 's:' + s.name, name: s.name, running: s.state === 'RUNNING',
        cpu: () => { const ss = h && h.services ? h.services[s.name] : null; return ss && ss.cpu ? ss.cpu.slice() : []; } })) };
}

/** 선택된 키 (목록 순서 = 그래프 색 순서) */
function graphSelectedKeys(server, gi) {
    const keys = gi.items.map(i => i.key);
    let saved = loadGraphSel()[server.id];
    if (Array.isArray(saved)) return keys.filter(k => saved.indexOf(k) !== -1).slice(0, GRAPH_MAX);
    if (gi.mode === 'proc') return keys.slice(0, GRAPH_MAX);
    const run = gi.items.filter(i => i.running).map(i => ({ k: i.key, v: lastOf(i.cpu()) }));
    run.sort((a, b) => b.v - a.v);
    const top = run.slice(0, 5).map(x => x.k);
    return keys.filter(k => top.indexOf(k) !== -1);
}

function graphSelection(server) {
    const gi = graphItems(server);
    const sel = graphSelectedKeys(server, gi);
    gi.selected = gi.items.filter(i => sel.indexOf(i.key) !== -1);
    gi.colorOf = key => { const k = sel.indexOf(key); return k === -1 ? null : PROC_COLORS[k % PROC_COLORS.length]; };
    return gi;
}

/** 목록 행 앞의 색 표시 (그래프에 선택된 항목만 색이 보임) */
function graphPickHtml(server, gi, item) {
    const color = item ? gi.colorOf(item.key) : null;
    return '<i class="proc-swatch' + (color ? ' on' : '') + '" style="background:' + (color || 'transparent') + '"' +
        (color ? ' title="그래프에 표시 중"' : '') + '></i>';
}

function graphPickHead(server, gi) {
    return '<button type="button" class="proc-btn proc-pick-open" onclick="openGraphPicker(' + server.id + ')">' +
        '📈 그래프 항목 선택 <b>' + gi.selected.length + '</b> / ' + Math.min(GRAPH_MAX, gi.items.length) + '</button>';
}

// --- 그래프 항목 선택 팝업 (index.html #graphPickModal) ---
let graphPick = null;   // { serverId, sel: [키], items, mode }

function openGraphPicker(serverId) {
    const server = serversData.find(s => s.id == serverId);
    const modal = document.getElementById('graphPickModal');
    if (!server || !modal) return;
    const gi = graphItems(server);
    graphPick = { serverId: server.id, sel: graphSelectedKeys(server, gi), items: gi.items, mode: gi.mode };
    document.getElementById('graphPickTitle').textContent = server.systemName + ' · 그래프 항목 선택';
    document.getElementById('graphPickSub').textContent = gi.mode === 'svc'
        ? '프로세스 관리(service.list) 서비스 ' + gi.items.length + '건 - 서비스 CPU 그래프'
        : '주요 프로세스 ' + gi.items.length + '건 - 프로세스 CPU 그래프';
    document.getElementById('graphPickSearch').value = '';
    renderGraphPicker();
    modal.style.display = 'flex';
    setTimeout(() => document.getElementById('graphPickSearch').focus(), 0);
}

function closeGraphPicker() {
    const modal = document.getElementById('graphPickModal');
    if (modal) modal.style.display = 'none';
    graphPick = null;
}

function renderGraphPicker() {
    if (!graphPick) return;
    const q = document.getElementById('graphPickSearch').value.trim().toLowerCase();
    const full = graphPick.sel.length >= GRAPH_MAX;
    const svcs = graphPick.mode === 'svc' ? serviceListOf({ id: graphPick.serverId }) : [];
    const server = serversData.find(s => s.id == graphPick.serverId);
    const rows = graphPick.items.filter(i => !q || i.name.toLowerCase().indexOf(q) !== -1).map(i => {
        const k = graphPick.sel.indexOf(i.key);
        const on = k !== -1;
        const series = i.cpu();
        const cur = series.length ? lastOf(series).toFixed(1) + '%' : '-';
        let sub = '';
        if (graphPick.mode === 'svc') {
            const s = svcs.find(x => 's:' + x.name === i.key) || {};
            sub = 'PID ' + escapeHtml(s.pid || '-') + ' · 그룹 ' + escapeHtml(s.group) + ' · Level ' + escapeHtml(s.level);
        } else {
            const p = (server.processes || []).find(x => 'p' + x.id === i.key) || {};
            sub = 'PID ' + escapeHtml(p.pid || '-') + ' · ' + escapeHtml(p.path || '');
        }
        return '<li class="gp-row' + (on ? ' on' : '') + (!on && full ? ' off' : '') + '">' +
            '<label><input type="checkbox" data-gkey="' + escapeHtml(i.key) + '"' + (on ? ' checked' : '') + (!on && full ? ' disabled' : '') + '>' +
            '<i class="proc-swatch' + (on ? ' on' : '') + '" style="background:' + (on ? PROC_COLORS[k % PROC_COLORS.length] : 'transparent') + '"></i>' +
            '<span class="gp-name"><b>' + escapeHtml(i.name) + '</b><small>' + sub + '</small></span>' +
            '<span class="proc-state ' + (i.running ? 'on' : 'off') + '">' + (i.running ? '● 실행 중' : '○ 종료') + '</span>' +
            '<span class="gp-cpu" title="최근 CPU">' + cur + '</span></label></li>';
    }).join('');
    document.getElementById('graphPickList').innerHTML = rows || '<li class="proc-empty">검색 결과가 없습니다.</li>';
    document.getElementById('graphPickCount').innerHTML = '선택 <b>' + graphPick.sel.length + '</b> / 최대 ' + GRAPH_MAX +
        (full ? ' <span class="gp-full">(최대 - 다른 항목을 해제하면 고를 수 있음)</span>' : '');
}

function graphPickToggle(box) {
    if (!graphPick) return;
    const key = box.getAttribute('data-gkey');
    if (box.checked) {
        if (graphPick.sel.length >= GRAPH_MAX) { box.checked = false; return; }
        // 목록 순서를 유지해야 색이 바뀌지 않음
        const order = graphPick.items.map(i => i.key);
        graphPick.sel = order.filter(k => k === key || graphPick.sel.indexOf(k) !== -1);
    } else {
        graphPick.sel = graphPick.sel.filter(k => k !== key);
    }
    updateGraphPickerState();
}

/** 체크 변경 시 목록을 다시 그리지 않고 상태만 갱신 (스크롤 위치 유지) */
function updateGraphPickerState() {
    if (!graphPick) return;
    const full = graphPick.sel.length >= GRAPH_MAX;
    document.querySelectorAll('#graphPickList input[data-gkey]').forEach(box => {
        const k = graphPick.sel.indexOf(box.getAttribute('data-gkey'));
        const on = k !== -1;
        const row = box.closest('.gp-row');
        box.checked = on;
        box.disabled = !on && full;
        row.classList.toggle('on', on);
        row.classList.toggle('off', !on && full);
        const sw = row.querySelector('.proc-swatch');
        sw.classList.toggle('on', on);
        sw.style.background = on ? PROC_COLORS[k % PROC_COLORS.length] : 'transparent';
    });
    document.getElementById('graphPickCount').innerHTML = '선택 <b>' + graphPick.sel.length + '</b> / 최대 ' + GRAPH_MAX +
        (full ? ' <span class="gp-full">(최대 - 다른 항목을 해제하면 고를 수 있음)</span>' : '');
}

function graphPickTop() {
    if (!graphPick) return;
    const run = graphPick.items.filter(i => i.running).map(i => ({ k: i.key, v: lastOf(i.cpu()) }));
    run.sort((a, b) => b.v - a.v);
    const top = run.slice(0, 5).map(x => x.k);
    graphPick.sel = graphPick.items.map(i => i.key).filter(k => top.indexOf(k) !== -1);
    updateGraphPickerState();
}

function graphPickClear() {
    if (!graphPick) return;
    graphPick.sel = [];
    updateGraphPickerState();
}

function graphPickApply() {
    if (!graphPick) return;
    saveGraphSel(graphPick.serverId, graphPick.sel.slice());
    const server = serversData.find(s => s.id == graphPick.serverId);
    closeGraphPicker();
    if (server) redrawGraphCard(server);
}

window.addEventListener('DOMContentLoaded', function () {
    const modal = document.getElementById('graphPickModal');
    if (!modal) return;
    document.getElementById('graphPickList').addEventListener('change', e => {
        if (e.target.matches('input[data-gkey]')) graphPickToggle(e.target);
    });
    document.getElementById('graphPickSearch').addEventListener('input', renderGraphPicker);
    modal.addEventListener('click', e => { if (e.target === modal) closeGraphPicker(); });
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && graphPick) closeGraphPicker(); });
});

/** 선택이 바뀌면 그래프와 목록(색 표시)만 다시 그림 - 목록 스크롤 위치 유지 */
function redrawGraphCard(server) {
    const block = document.getElementById('proc-' + server.id);
    const list = block ? block.querySelector('.proc-list') : null;
    const top = list ? list.scrollTop : 0;
    if (block) block.innerHTML = renderProcessBlock(server);
    const list2 = block ? block.querySelector('.proc-list') : null;
    if (list2) list2.scrollTop = top;
    refreshChart(server.id);
}

function renderServiceBlock(server) {
    const svcs = serviceListOf(server);
    const run = svcs.filter(s => s.state === 'RUNNING').length;
    const gi = graphSelection(server);
    const rows = svcs.map(s => {
        const st = SVC_STATE[s.state] || SVC_STATE.UNKNOWN;
        const item = gi.items.find(i => i.key === 's:' + s.name);
        return '<li class="proc-row">' + graphPickHtml(server, gi, item) +
            '<span class="proc-state ' + st[1] + '">' + st[0] + '</span>' +
            '<span class="proc-name" title="' + escapeHtml(s.script || '') + '">' + escapeHtml(s.name) +
            '<small>PID ' + escapeHtml(s.pid || '-') + ' · 그룹 ' + escapeHtml(s.group) + ' · Level ' + escapeHtml(s.level) +
            (s.stateDt ? ' · ' + escapeHtml(s.stateDt) : '') + '</small></span></li>';
    }).join('');
    return '<div class="proc-title">주요 프로세스 <span>프로세스 관리 등록 ' + svcs.length + '건 · 실행 중 ' + run + '</span></div>' +
        '<div class="proc-pick-bar">' + graphPickHead(server, gi) + '</div>' +
        '<ul class="proc-list proc-svc-list">' + rows + '</ul>' +
        '<button type="button" class="proc-btn" onclick="jumpToProcessTab(' + server.id + ')">프로세스 관리에서 보기 / 제어</button>';
}

/** 그래프 자리 : service.list 서비스 상태 요약 (서비스는 CPU 이력이 없어 상태만) */
function renderServiceSummary(server) {
    const svcs = serviceListOf(server);
    const n = { RUNNING: 0, STOPPED: 0, UNKNOWN: 0 };
    svcs.forEach(s => { n[SVC_STATE[s.state] ? s.state : 'UNKNOWN']++; });
    const pct = k => (svcs.length ? (n[k] / svcs.length * 100) : 0).toFixed(1);
    const last = svcs.map(s => s.stateDt).filter(Boolean).sort().pop();
    return '<div class="proc-chart-head"><div class="proc-chart-title">서비스 상태 <span>프로세스 관리(service.list) 기준</span>' +
        (last ? ' <span class="proc-src agent">확인 ' + escapeHtml(last) + '</span>' : '') + '</div></div>' +
        '<div class="svc-sum-bar" role="img" aria-label="실행 중 ' + n.RUNNING + ', 종료 ' + n.STOPPED + ', 미확인 ' + n.UNKNOWN + '">' +
        '<i class="run" style="width:' + pct('RUNNING') + '%"></i><i class="stop" style="width:' + pct('STOPPED') + '%"></i>' +
        '<i class="unk" style="width:' + pct('UNKNOWN') + '%"></i></div>' +
        '<div class="proc-legend"><span class="proc-legend-item"><i style="background:#28a745"></i>실행 중 <b>' + n.RUNNING + '</b></span>' +
        '<span class="proc-legend-item"><i style="background:#dc3545"></i>종료 <b>' + n.STOPPED + '</b></span>' +
        '<span class="proc-legend-item"><i style="background:#94a3b8"></i>미확인 <b>' + n.UNKNOWN + '</b></span></div>' +
        '<div class="proc-chart-note svc-sum-note">서버의 krcasmrx11_client 를 1.3 이상으로 올리면 서비스별 CPU 그래프가 표시됩니다.</div>';
}

function renderProcessBlock(server) {
    if (!Array.isArray(server.processes)) server.processes = [];
    const addBtn = '<button type="button" class="proc-btn add admin-only" onclick="openProcSearch(' + server.id + ')">+ 프로세스 찾아서 추가</button>';
    // krcasmrx11_client 가 연결(정상 보고)되기 전에는 실행 상태가 실제 값이 아니므로 목록을 보여주지 않음
    if (!isServerConnected(server)) {
        const cnt = server.processes.length;
        return '<div class="proc-title">주요 프로세스 <span>등록 ' + cnt + '건</span></div>' +
            '<div class="proc-hidden">' + (agentStatesLoaded
                ? '서버(krcasmrx11_client)가 연결되면 프로세스 실행 상태가 표시됩니다.'
                : '서버 연결 상태를 확인하고 있습니다.') + '</div>' + addBtn;
    }
    if (!server.processes.length && serviceListOf(server).length) return renderServiceBlock(server);
    const running = server.processes.filter(p => p.running).length;
    const gi = graphSelection(server);
    const rows = server.processes.map((p, i) =>
        '<li class="proc-row">' + graphPickHtml(server, gi, gi.items[i]) +
        '<span class="proc-state ' + (p.running ? 'on' : 'off') + '">' + (p.running ? '● 실행 중' : '○ 종료') + '</span>' +
        '<span class="proc-name" title="' + escapeHtml(p.path) + '">' + escapeHtml(p.name) +
        '<small>PID ' + escapeHtml(p.pid) + ' · CPU ' + escapeHtml(p.cpu) + ' · MEM ' + escapeHtml(p.mem) + '</small></span>' +
        '<button type="button" class="proc-btn admin-only" onclick="toggleServerProcess(' + server.id + ',' + i + ')">' + (p.running ? '중지' : '기동') + '</button>' +
        '<button type="button" class="proc-btn del admin-only" onclick="deleteServerProcess(' + server.id + ',' + i + ')" aria-label="' + escapeHtml(p.name) + ' 삭제">삭제</button>' +
        '</li>'
    ).join('');
    return '<div class="proc-title">주요 프로세스 <span>' + running + ' / ' + server.processes.length + ' 실행 중</span></div>' +
        (server.processes.length ? '<div class="proc-pick-bar">' + graphPickHead(server, gi) + '</div>' : '') +
        '<ul class="proc-list">' + (rows || '<li class="proc-empty">등록된 프로세스가 없습니다.</li>') + '</ul>' + addBtn;
}

function renderProcessCard(server) {
    const pairServer = topoPairOf(server);
    const pairText = pairServer ? ' · 동기화 대상 ' + escapeHtml(pairServer.systemName) : '';
    const isDown = server.status === 'Down';
    return '<div class="proc-card' + (isDown ? ' is-down' : '') + '" id="card-' + server.id + '" data-server-id="' + server.id + '">' +
        (isDown ? '<div class="proc-down-alert" role="alert">⚠ ' + (server.noData && !serverBaseDown(server)
            ? '서버 정보 수신 안 됨 (' + escapeHtml(server.noData) + ') - Down'
            : '서버 다운 - 즉시 조치가 필요합니다') + '</div>' : '') +
        '<div class="proc-card-top">' + (typeof cardDragHandle === 'function' ? cardDragHandle(server) : '') +
        '<div class="proc-card-title"><div class="proc-server-name">' + escapeHtml(server.systemName) + '</div>' +
        '<div class="proc-sub">' + escapeHtml(server.ip) + pairText + '</div></div>' +
        '<div class="proc-badge ' + escapeHtml(server.status) + '">' + escapeHtml(server.status) + '</div></div>' +
        '<div class="proc-chart" id="chart-' + server.id + '">' + renderChartArea(server) + '</div>' +
        '<div class="proc-block" id="proc-' + server.id + '">' + renderProcessBlock(server) + '</div>' +
        '</div>';
}

// --- 서버별 그래프 (선 / 막대 / 원 / 자원, 팝업 확대) ---
// 데이터 : DB 수집 이력 (GET /api/topology/history)
//   tb_process_metric_hist → 프로세스 CPU, tb_server_metric_hist → 서버 CPU/MEM ("자원")
//   서버 에이전트(krcasmrx11_client)가 보고하면 AGENT 이력이 쌓이고, 그 전에는 초기 샘플(SAMPLE)을 표시합니다.
const PROC_COLORS = ['#007bff', '#0e7c8c', '#d9480f', '#7048e8', '#2f9e44', '#c2255c'];
const CHART_TYPES = [['line', '선'], ['bar', '막대'], ['pie', '원'], ['res', '자원']];
const HISTORY_POINTS = 30;
const HISTORY_REFRESH_MS = 60000;
const procChartTypes = {};
let zoomServerId = null;
let historyData = {};
let historyTimer = null;

function getChartType(serverId) { return procChartTypes[serverId] || 'line'; }

async function loadHistory() {
    try {
        historyData = await apiRequest('GET', TOPO_API + '/history?points=' + HISTORY_POINTS) || {};
    } catch (e) {
        console.warn('수집 이력을 불러오지 못했습니다:', e);
        historyData = {};
    }
}

// 1분마다 이력/서버 상태를 다시 읽어 그래프 갱신
function startHistoryRefresh() {
    if (historyTimer) return;
    historyTimer = setInterval(async () => {
        try { await loadServers(); } catch (e) { return; }
        const before = serversData.map(s => s.id + ':' + isServerConnected(s)).join(',');
        await Promise.all([loadHistory(), loadAgentStates(), loadServiceLists()]);
        if (serversData.map(s => s.id + ':' + isServerConnected(s)).join(',') !== before) {
            renderProcessSection();
            return;
        }
        serversData.forEach(s => {
            const el = document.getElementById('proc-' + s.id);
            if (el) el.innerHTML = renderProcessBlock(s);
            refreshChart(s.id);
        });
    }, HISTORY_REFRESH_MS);
}

function serverHistory(server) {
    return historyData[server.id] || null;
}

// 프로세스 CPU 이력 (없으면 빈 배열)
function buildProcessSeries(server, p) {
    const h = serverHistory(server);
    const ps = h && h.processes ? h.processes[p.id] : null;
    return ps && ps.cpu ? ps.cpu.slice() : [];
}

function lastOf(a) { return a.length ? a[a.length - 1] : 0; }

function niceMax(raw) {
    const step = raw <= 5 ? 1 : (raw <= 10 ? 2 : 5);
    return Math.ceil(raw / step) * step;
}

function fmtNum(v) { return Number.isInteger(v) ? String(v) : v.toFixed(1); }

function shortName(name, max) { return name.length > max ? name.slice(0, max - 1) + '…' : name; }

function chartLine(procs, series, large, times, fixedMax) {
    const W = large ? 640 : 320, H = large ? 300 : 130, f = large ? 1.4 : 1;
    const L = 30 * f, R = 8 * f, T = 8 * f, B = 20 * f;
    const n = Math.max(2, ...series.map(a => a.length));
    const yMax = fixedMax || niceMax(Math.max(1, ...series.map(a => a.length ? Math.max(...a) : 0)));
    const x = i => L + i * (W - L - R) / (n - 1);
    const y = v => T + (H - T - B) * (1 - v / yMax);
    let grid = '';
    for (let g = 0; g <= 4; g++) {
        const val = yMax * g / 4, yy = y(val);
        grid += '<line x1="' + L + '" y1="' + yy + '" x2="' + (W - R) + '" y2="' + yy + '" stroke="#e2e8f0" stroke-width="1"/>' +
            '<text x="' + (L - 4) + '" y="' + (yy + 3 * f) + '" text-anchor="end" font-size="' + 9 * f + '" fill="#475569">' + fmtNum(val) + '</text>';
    }
    const tl = times && times.length ? times : [];
    const xl = [[tl[0] || '', 0, 'start'], [tl[Math.floor((tl.length - 1) / 2)] || '', (n - 1) / 2, 'middle'], [tl[tl.length - 1] || '현재', n - 1, 'end']]
        .map(t => '<text x="' + x(t[1]) + '" y="' + (H - 5 * f) + '" text-anchor="' + t[2] + '" font-size="' + 9 * f + '" fill="#475569">' + t[0] + '</text>').join('');
    const lines = series.map((a, k) => {
        const dash = k >= PROC_COLORS.length / 2 ? ' stroke-dasharray="5 3"' : '';
        const off = n - a.length;   // 이력이 짧은 계열은 오른쪽(최근)에 맞춤
        const pts = a.map((v, i) => x(i + off).toFixed(1) + ',' + y(v).toFixed(1)).join(' ');
        return '<polyline points="' + pts + '" fill="none" stroke="' + PROC_COLORS[k % PROC_COLORS.length] + '" stroke-width="' + 2 * (large ? 1.2 : 1) + '" stroke-linejoin="round"' + dash + '/>';
    }).join('');
    return { W: W, H: H, body: grid + xl + lines };
}

function chartBar(procs, series, large) {
    const f = large ? 1.4 : 1, W = large ? 640 : 320;
    const labelW = large ? 190 : 110, valW = 44 * f, rh = 22 * f, T = 6 * f;
    const H = Math.max(large ? 300 : 130, T * 2 + procs.length * rh);
    const cur = series.map(lastOf);
    const yMax = niceMax(Math.max(1, ...cur));
    const barMax = W - labelW - valW - 8;
    const rows = procs.map((p, k) => {
        const yy = T + k * rh, v = cur[k];
        const w = Math.max(1, v / yMax * barMax);
        return '<text x="' + (labelW - 6) + '" y="' + (yy + rh * 0.65) + '" text-anchor="end" font-size="' + 10 * f + '" fill="#334155">' + escapeHtml(shortName(p.name, large ? 26 : 15)) + '</text>' +
            '<rect x="' + labelW + '" y="' + (yy + rh * 0.18) + '" width="' + w.toFixed(1) + '" height="' + rh * 0.64 + '" rx="2" fill="' + PROC_COLORS[k % PROC_COLORS.length] + '"/>' +
            '<text x="' + (labelW + w + 5) + '" y="' + (yy + rh * 0.65) + '" font-size="' + 10 * f + '" font-weight="bold" fill="#203a43">' + (v > 0 ? v.toFixed(1) + '%' : '종료') + '</text>';
    }).join('');
    return { W: W, H: H, body: '<line x1="' + labelW + '" y1="' + T + '" x2="' + labelW + '" y2="' + (H - T) + '" stroke="#94a3b8"/>' + rows };
}

function chartPie(procs, series, large) {
    const W = large ? 640 : 320, H = large ? 300 : 130, f = large ? 1.4 : 1;
    const cur = series.map(lastOf);
    const total = cur.reduce((s, v) => s + v, 0);
    if (total <= 0) {
        return { W: W, H: H, body: '<text x="' + W / 2 + '" y="' + H / 2 + '" text-anchor="middle" font-size="' + 11 * f + '" fill="#64748b">실행 중인 프로세스 없음</text>' };
    }
    const cx = W / 2, cy = H / 2, r = Math.min(H / 2 - 6 * f, 120);
    let ang = -Math.PI / 2, out = '';
    const slices = cur.map((v, k) => ({ v: v, k: k })).filter(s => s.v > 0);
    slices.forEach(s => {
        const frac = s.v / total, a2 = ang + frac * 2 * Math.PI;
        const color = PROC_COLORS[s.k % PROC_COLORS.length];
        if (slices.length === 1) {
            out += '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="' + color + '"/>';
        } else {
            const x1 = cx + r * Math.cos(ang), y1 = cy + r * Math.sin(ang);
            const x2 = cx + r * Math.cos(a2), y2 = cy + r * Math.sin(a2);
            out += '<path d="M' + cx + ',' + cy + ' L' + x1.toFixed(1) + ',' + y1.toFixed(1) + ' A' + r + ',' + r + ' 0 ' + (frac > 0.5 ? 1 : 0) + ' 1 ' + x2.toFixed(1) + ',' + y2.toFixed(1) + ' Z" fill="' + color + '" stroke="#fff" stroke-width="1.5"/>';
        }
        if (frac >= 0.08) {
            const mid = ang + frac * Math.PI;
            out += '<text x="' + (cx + r * 0.62 * Math.cos(mid)).toFixed(1) + '" y="' + (cy + r * 0.62 * Math.sin(mid) + 4 * f).toFixed(1) + '" text-anchor="middle" font-size="' + 10 * f + '" font-weight="bold" fill="#ffffff">' + Math.round(frac * 100) + '%</text>';
        }
        ang = a2;
    });
    return { W: W, H: H, body: out };
}

// --- 그래프 표시 조건 ---
// 1) 주요 프로세스가 등록되어 있고  2) 서버 에이전트(krcasmrx11_client)가 연결(정상 보고)된 서버만 그래프를 그림
// 연결 상태 : GET /api/agents (NONE 미등록 / WAITING 보고 대기 / STALE 보고 끊김 / OK 연결)
let agentStates = {};
let agentStatesLoaded = false;

async function loadAgentStates() {
    try {
        const list = await apiRequest('GET', 'api/agents');
        const map = {};
        list.forEach(v => { map[v.serverId] = v.agent || { state: 'NONE' }; });
        agentStates = map;
        agentStatesLoaded = true;
    } catch (e) {
        console.warn('서버 연결 상태를 불러오지 못했습니다:', e);
    }
}

function isServerConnected(server) {
    return agentStatesLoaded && (agentStates[server.id] || {}).state === 'OK';
}

/** 그래프를 그리면 안 되는 이유 (그려도 되면 null) */
function chartBlockReason(server) {
    if ((!server.processes || !server.processes.length) && !serviceListOf(server).length) return 'NO_PROC';
    if (!agentStatesLoaded) return 'LOADING';
    const a = agentStates[server.id] || { state: 'NONE' };
    return a.state === 'OK' ? null : a.state;
}

function renderChartArea(server) {
    const reason = chartBlockReason(server);
    if (!reason) return renderProcessChart(server);
    const a = agentStates[server.id] || {};
    const admin = isAdminUser();
    const MSG = {
        NO_PROC: ['주요 프로세스 미등록', '이 서버에 등록된 주요 프로세스도, 프로세스 관리(service.list)에 등록된 서비스도 없습니다.' +
            (admin ? ' 아래 [+ 프로세스 찾아서 추가] 로 등록하세요.' : ' 관리자에게 등록을 요청하세요.')],
        LOADING: ['연결 확인 중', '서버 연결 상태를 확인하고 있습니다.'],
        NONE: ['서버 미연결', '에이전트(krcasmrx11_client)가 등록되지 않았습니다. 연결되면 그래프가 표시됩니다.'],
        WAITING: ['연결 대기', '에이전트 토큰은 발급되었지만 아직 보고가 없습니다. 서버에서 클라이언트를 기동하세요.'],
        STALE: ['연결 끊김', '에이전트 보고가 끊겼습니다' + (a.lastReportDt ? ' (마지막 보고 ' + escapeHtml(a.lastReportDt) + ')' : '') + '. 다시 연결되면 그래프가 표시됩니다.'],
        LOST: ['하트비트 끊김', '서버 다운 또는 데몬 비정상 종료로 하트비트가 끊겼습니다. 모니터링 대상 프로세스는 종료로 표시됩니다.'],
        PAUSED: ['수집 중지', '에이전트 관리 화면에서 자료 수집을 중지했습니다. [시작] 하면 그래프가 표시됩니다.'],
        STOPPED: ['데몬 종료', '에이전트 데몬이 종료되었습니다. 서버에서 bin/scb_client.sh start 로 시작하세요.']
    };
    const m = MSG[reason] || MSG.NONE;
    const btn = (reason === 'NO_PROC' || reason === 'LOADING') ? '' :
        '<div class="proc-wait-btns"><button type="button" class="proc-btn" onclick="checkServerConnection(' + server.id + ')">연결 확인</button>' +
        (admin ? '<a class="proc-btn" href="network?server=' + server.id + '">' + (reason === 'NONE' ? '토큰 발급' : '구성도') + '</a>' : '') + '</div>';
    return '<div class="proc-wait ' + reason + '" role="status">' +
        '<div class="proc-wait-icon" aria-hidden="true">' + (reason === 'NO_PROC' ? '📋' : reason === 'LOADING' ? '⏳' : '🔌') + '</div>' +
        '<div class="proc-wait-body"><b>' + m[0] + '</b><span>' + m[1] + '</span>' + btn +
        '<span class="proc-wait-result" id="connResult-' + server.id + '"></span></div></div>';
}

/** 카드의 [연결 확인] : 해당 서버만 다시 확인해 연결되면 바로 그래프 표시 */
async function checkServerConnection(serverId) {
    const out = document.getElementById('connResult-' + serverId);
    if (out) out.textContent = '확인 중...';
    try {
        const a = await apiRequest('GET', 'api/agents/' + serverId);
        agentStates[serverId] = a;
        if (a.state === 'OK') {
            await reloadForConnection();
        } else if (out) {
            out.textContent = '아직 연결되지 않았습니다. (' + ({ NONE: '미등록', WAITING: '보고 대기', STALE: '보고 끊김', LOST: '하트비트 끊김', PAUSED: '수집 중지', STOPPED: '데몬 종료' }[a.state] || a.state) + ')';
        }
    } catch (e) {
        if (out) out.textContent = e.message;
    }
}

/** 메인 홈 "미등록 서버" 패널에서 연결이 확인되면 그래프도 바로 갱신 (main_alarm.js) */
function onAgentStateChanged(serverId, agent) {
    const before = (agentStates[serverId] || {}).state;
    agentStates[serverId] = agent;
    if (before !== agent.state) {
        if (agent.state === 'OK') reloadForConnection();
        else renderProcessSection();
    }
}

/** 서버 연결 상태가 바뀌면 프로세스 실행 상태·이력을 다시 읽고 카드(그래프 + 주요 프로세스)와 합계를 다시 그림 */
async function reloadForConnection() {
    try { await loadServers(); } catch (e) { /* 이전 값으로 표시 */ }
    await loadHistory();
    renderProcessSection();
}

function renderProcessChart(server, large) {
    const gi = graphSelection(server);
    const procs = gi.selected;
    const type = getChartType(server.id);
    const h = serverHistory(server);
    const down = server.status === 'Down';
    const subtitle = { line: '최근 ' + HISTORY_POINTS + '회 수집 (%)', bar: '최근 값 (%)', pie: '최근 CPU 점유 비율', res: '서버 CPU · 메모리 (%)' }[type];
    const btns = CHART_TYPES.map(t =>
        '<button type="button" class="proc-type-btn' + (t[0] === type ? ' active' : '') + '" aria-pressed="' + (t[0] === type) + '" onclick="setProcChartType(' + server.id + ',\'' + t[0] + '\')">' + t[1] + '</button>'
    ).join('');
    const zoomBtn = large ? '' : '<button type="button" class="proc-btn" onclick="openChartZoom(' + server.id + ')" title="그래프 확대">확대</button>';
    const src = !h ? '<span class="proc-src none">수집 이력 없음</span>'
        : (h.source === 'AGENT' ? '<span class="proc-src agent" title="에이전트 수집">수집 ' + escapeHtml(h.lastTime || '') + '</span>'
            : '<span class="proc-src sample" title="에이전트 설치 전 초기 샘플 데이터">샘플</span>');
    const title = type === 'res' ? '서버 자원 사용률' : (gi.mode === 'svc' ? '서비스 CPU 사용률' : '프로세스 CPU 사용률');
    const head = '<div class="proc-chart-head"><div class="proc-chart-title">' + title + ' <span>' + subtitle + '</span> ' + src + '</div>' +
        '<div class="proc-chart-ctrl"><div class="proc-type-group" role="group" aria-label="그래프 종류">' + btns + '</div>' + zoomBtn + '</div></div>';
    const svg = g => '<svg viewBox="0 0 ' + g.W + ' ' + g.H + '" role="img" aria-label="' + escapeHtml(server.systemName) + ' ' + title + ' 그래프" style="width:100%;height:auto;display:block">' + g.body + '</svg>';

    if (type === 'res') {
        if (!h || !h.cpu.length) return head + '<div class="proc-empty">수집된 서버 자원 이력이 없습니다.</div>';
        const series = [h.cpu, h.mem];
        const g = chartLine([{ name: 'CPU' }, { name: 'MEM' }], series, !!large, h.times, 100);
        const legend = ['CPU', 'MEM'].map((n, k) => '<span class="proc-legend-item"><i style="background:' + PROC_COLORS[k] + '"></i>' + n + ' <b>' + lastOf(series[k]).toFixed(1) + '%</b></span>').join('');
        return head + svg(g) + '<div class="proc-legend">' + legend + '</div>';
    }

    if (!gi.items.length) return head + '<div class="proc-empty">그래프로 표시할 프로세스가 없습니다.</div>';
    if (!procs.length) return head + '<div class="proc-empty proc-pick-hint">그래프로 볼 주요 프로세스를 선택하세요. (최대 ' + GRAPH_MAX + '개) ' +
        (large ? '' : '<button type="button" class="proc-btn proc-pick-open" onclick="openGraphPicker(' + server.id + ')">📈 그래프 항목 선택</button>') + '</div>';
    const series = procs.map(p => p.cpu());
    if (series.every(a => !a.length)) {
        if (gi.mode === 'svc') {
            // 이전 클라이언트(1.2 이하)는 서비스 CPU 를 보내지 않음 → 상태 요약
            return head + '<div class="proc-empty">선택한 서비스의 CPU 수집 이력이 없습니다. (krcasmrx11_client 1.3 이상에서 수집)</div>' +
                renderServiceSummary(server).replace(/<div class="proc-chart-head">[\s\S]*?<\/div><\/div>/, '');
        }
        return head + '<div class="proc-empty">수집된 프로세스 이력이 없습니다. (에이전트 보고 후 표시)</div>';
    }
    const g = type === 'bar' ? chartBar(procs, series, !!large) : (type === 'pie' ? chartPie(procs, series, !!large) : chartLine(procs, series, !!large, h ? h.times : []));
    const total = series.reduce((s, a) => s + lastOf(a), 0);
    const legend = procs.map((p, k) => {
        const cur = lastOf(series[k]);
        const val = (p.running && !down) ? (type === 'pie' && total > 0 ? Math.round(cur / total * 100) + '%' : cur.toFixed(1) + '%') : '종료';
        return '<span class="proc-legend-item"><i style="background:' + PROC_COLORS[k % PROC_COLORS.length] + '"></i>' + escapeHtml(p.name) + ' <b>' + val + '</b></span>';
    }).join('');
    const note = down ? '<div class="proc-chart-note">서버 연결 끊김 · 수집 데이터 없음</div>' : '';
    return head + svg(g) + '<div class="proc-legend">' + legend + '</div>' + note;
}

function refreshChart(serverId) {
    const server = serversData.find(s => s.id == serverId);
    if (!server) return;
    const el = document.getElementById('chart-' + serverId);
    if (el) el.innerHTML = renderChartArea(server);
    if (zoomServerId == serverId) {
        if (chartBlockReason(server)) closeChartZoom();
        else document.getElementById('chartZoomBody').innerHTML = renderProcessChart(server, true);
    }
}

function setProcChartType(serverId, type) {
    procChartTypes[serverId] = type;
    refreshChart(serverId);
}

function openChartZoom(serverId) {
    const server = serversData.find(s => s.id == serverId);
    if (!server || chartBlockReason(server)) return;
    zoomServerId = serverId;
    document.getElementById('chartZoomTitle').innerText = server.systemName + ' · 프로세스 모니터링';
    document.getElementById('chartZoomBody').innerHTML = renderProcessChart(server, true);
    document.getElementById('chartZoomModal').style.display = 'flex';
}

function closeChartZoom() {
    document.getElementById('chartZoomModal').style.display = 'none';
    zoomServerId = null;
}

window.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    const zoom = document.getElementById('chartZoomModal');
    if (zoom && zoom.style.display === 'flex') closeChartZoom();
    const search = document.getElementById('procSearchModal');
    if (search && search.style.display === 'flex') closeProcSearch();
});

function renderProcessSection() {
    const container = document.getElementById('processGroups');
    if (!container) return;
    if (typeof cardDrag !== 'undefined' && cardDrag) { cardRenderPending = true; return; }
    const groups = [
        { key: 'itc', title: 'ITC · GASAN MTR-A', test: s => s.location !== 'DRC' },
        { key: 'drc', title: 'DRC', test: s => s.location === 'DRC' }
    ];
    container.innerHTML = groups
        .filter(g => processScope === 'all' || processScope === g.key)
        .map(g => {
            const list = serversData.filter(g.test);
            let conn = list.filter(isServerConnected);
            if (typeof orderServers === 'function') conn = orderServers(g.key, conn);
            const all = conn.reduce((n, s) => n + procCounts(s)[1], 0);
            const on = conn.reduce((n, s) => n + procCounts(s)[0], 0);
            // 서버 그래프 카드는 에이전트(krcasmrx11_client)가 연결된 서버만 표시
            //  (연결되지 않은 서버는 위쪽 "에이전트 미등록 서버" 목록에서 확인)
            const body = conn.length
                ? '<div class="proc-grid">' + conn.map(renderProcessCard).join('') + '</div>'
                : '<div class="proc-none" role="status">' + (agentStatesLoaded
                    ? '🔌 이 구역에 에이전트가 연결된 서버가 없습니다. (서버 ' + list.length + '대 모두 미연결)' +
                      '<button type="button" class="proc-btn" onclick="scrollToAgentReg()">미등록 서버 목록 보기</button>'
                    : '서버 연결 상태를 불러오지 못했습니다. 잠시 후 자동으로 다시 확인합니다.') + '</div>';
            return '<div class="proc-group ' + g.key + '">' +
                '<div class="proc-group-head"><h3>' + g.title + '</h3>' +
                '<span>연결 서버 ' + conn.length + ' / ' + list.length + '대 · 프로세스 실행 중 ' + on + ' / ' + all + '</span></div>' +
                body + '</div>';
        }).join('');
    // 알람이 있는 서버 카드 표시 (main_alarm.js)
    if (typeof applyAlarmMarks === 'function') applyAlarmMarks();
}

function scrollToAgentReg() {
    const panel = document.getElementById('agentRegPanel');
    if (panel && !panel.hidden) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function refreshServerProcesses(serverId) {
    const server = serversData.find(s => s.id == serverId);
    const el = document.getElementById('proc-' + serverId);
    if (server && el) el.innerHTML = renderProcessBlock(server);
    // 그룹 헤더의 합계 갱신
    renderProcessSection();
    if (zoomServerId == serverId && server) {
        document.getElementById('chartZoomBody').innerHTML = renderProcessChart(server, true);
    }
}


async function toggleServerProcess(serverId, idx) {
    const server = serversData.find(s => s.id == serverId);
    if (!server || !server.processes[idx]) return;
    try {
        server.processes[idx] = await apiRequest('POST',
            TOPO_API + '/servers/' + server.id + '/processes/' + server.processes[idx].id + '/toggle');
    } catch (e) { apiAlert(e); return; }
    refreshServerProcesses(serverId);
}

async function deleteServerProcess(serverId, idx) {
    const server = serversData.find(s => s.id == serverId);
    if (!server || !server.processes[idx]) return;
    if (confirm('정말 "' + server.processes[idx].name + '" 프로세스를 삭제하시겠습니까?')) {
        try {
            await apiRequest('DELETE', TOPO_API + '/servers/' + server.id + '/processes/' + server.processes[idx].id);
        } catch (e) { apiAlert(e); return; }
        server.processes.splice(idx, 1);
        refreshServerProcesses(serverId);
    }
}

// --- 프로세스 찾기 팝업 ---
function getProcessCatalog() {
    const map = {};
    serversData.forEach(s => (s.processes || []).forEach(p => {
        const key = p.name + '|' + p.path;
        if (!map[key]) map[key] = { name: p.name, path: p.path, servers: [] };
        map[key].servers.push(s.systemName);
    }));
    return Object.keys(map).map(k => map[k]);
}

function openProcSearch(serverId) {
    const server = serversData.find(s => s.id == serverId);
    if (!server) return;
    procSearchServerId = serverId;
    document.getElementById('procSearchTitle').innerText = '프로세스 찾기 - ' + server.systemName;
    document.getElementById('procSearchInput').value = '';
    document.getElementById('procManualName').value = '';
    document.getElementById('procManualPath').value = '';
    document.getElementById('procSearchModal').style.display = 'flex';
    renderProcSearchResults();
    document.getElementById('procSearchInput').focus();
}

function closeProcSearch() {
    document.getElementById('procSearchModal').style.display = 'none';
    procSearchServerId = null;
}

function renderProcSearchResults() {
    const server = serversData.find(s => s.id == procSearchServerId);
    const list = document.getElementById('procSearchResults');
    if (!server || !list) return;
    const q = document.getElementById('procSearchInput').value.trim().toLowerCase();
    const have = (server.processes || []).map(p => p.name + '|' + p.path);
    const items = getProcessCatalog().filter(c =>
        !q || (c.name + ' ' + c.path + ' ' + c.servers.join(' ')).toLowerCase().indexOf(q) !== -1);
    if (!items.length) {
        list.innerHTML = '<li class="proc-search-row"><span class="proc-empty">검색 결과가 없습니다. 아래에서 직접 등록할 수 있습니다.</span></li>';
        return;
    }
    list.innerHTML = items.map(c => {
        const key = c.name + '|' + c.path;
        const added = have.indexOf(key) !== -1;
        return '<li class="proc-search-row"><span class="proc-search-info"><b>' + escapeHtml(c.name) + '</b>' +
            '<small>' + escapeHtml(c.path) + ' · ' + c.servers.length + '개 서버에서 사용</small></span>' +
            '<button type="button" class="proc-btn add" ' + (added ? 'disabled' : '') +
            ' data-name="' + escapeHtml(c.name) + '" data-path="' + escapeHtml(c.path) + '" onclick="addSearchedProcess(this)">' +
            (added ? '등록됨' : '추가') + '</button></li>';
    }).join('');
}

async function addProcessToServer(serverId, name, path) {
    const server = serversData.find(s => s.id == serverId);
    if (!server) return;
    if (!Array.isArray(server.processes)) server.processes = [];
    try {
        server.processes.push(await apiRequest('POST', TOPO_API + '/servers/' + server.id + '/processes',
            { name: name, path: path, running: true }));
    } catch (e) { apiAlert(e); return; }
    refreshServerProcesses(serverId);
}

async function addSearchedProcess(btn) {
    await addProcessToServer(procSearchServerId, btn.getAttribute('data-name'), btn.getAttribute('data-path'));
    renderProcSearchResults();
}

async function addManualProcess() {
    const name = document.getElementById('procManualName').value.trim();
    const path = document.getElementById('procManualPath').value.trim();
    if (!name || !path) {
        alert('프로세스명과 실행 파일 경로를 모두 입력해주세요.');
        return;
    }
    await addProcessToServer(procSearchServerId, name, path);
    document.getElementById('procManualName').value = '';
    document.getElementById('procManualPath').value = '';
    renderProcSearchResults();
}
