// ================= 메인 홈 : 서버 장애 알람 / 에이전트 미등록 서버 =================
// 알람   : GET /api/alarms (서버 다운 = CRITICAL, 에이전트 보고 끊김 = WARNING)
//          서버가 1분마다 점검해 알람을 열고/해소함 (alarm.service.AlarmService). 화면은 30초마다 다시 읽음.
// 미등록 : GET /api/agents (krcasmrx11_client 토큰 미발급 / 보고 없음 / 보고 끊김)
// apiRequest, escapeHtml, isAdminUser, jumpToProcessTab, openWebConsole 은 script.js
const ALARM_API = 'api/alarms';
const AGENT_API = 'api/agents';
const ALARM_REFRESH_MS = 30000;
const AGENT_REFRESH_MS = 60000;

let activeAlarms = [];
let seenAlarmIds = null;     // 처음 읽은 뒤부터 "새 알람" 판단
let ackAlarm = null;
let titleTimer = null;
const baseTitle = document.title;

function alarmStore(key, value) {
    try {
        if (value === undefined) return localStorage.getItem(key);
        localStorage.setItem(key, value);
    } catch (e) { /* 저장소를 쓸 수 없으면 기본값 */ }
    return null;
}

function fmtElapsed(sec) {
    if (sec == null) return '-';
    if (sec < 60) return sec + '초';
    if (sec < 3600) return Math.floor(sec / 60) + '분';
    if (sec < 86400) return Math.floor(sec / 3600) + '시간 ' + Math.floor((sec % 3600) / 60) + '분';
    return Math.floor(sec / 86400) + '일 ' + Math.floor((sec % 86400) / 3600) + '시간';
}

// ---------------------------------------------------------------- 알람

async function loadAlarms() {
    let data;
    try {
        data = await apiRequest('GET', ALARM_API);
    } catch (e) {
        console.warn('알람을 불러오지 못했습니다:', e);
        return;
    }
    activeAlarms = data.active || [];
    const ids = new Set(activeAlarms.filter(a => a.stateCd === 'OPEN').map(a => a.alarmId));
    const fresh = seenAlarmIds ? [...ids].filter(id => !seenAlarmIds.has(id)) : [...ids];
    seenAlarmIds = ids;
    renderAlarmBanner(data);
    applyAlarmMarks();
    updateAlarmTitle(data.openCount);
    if (fresh.length) playAlarmSound();
    window._recentClosedAlarms = data.recentClosed || [];
}

function renderAlarmBanner(data) {
    const banner = document.getElementById('alarmBanner');
    const ok = document.getElementById('alarmOk');
    if (!banner) return;
    if (!activeAlarms.length) {
        banner.hidden = true;
        ok.hidden = false;
        document.getElementById('alarmOkTime').textContent = '(확인 ' + new Date().toLocaleTimeString('ko-KR') + ')';
        return;
    }
    ok.hidden = true;
    banner.hidden = false;
    const crit = data.criticalCount || 0;
    const warn = activeAlarms.length - crit;
    banner.classList.toggle('critical', crit > 0);
    document.getElementById('alarmTitle').textContent = crit > 0 ? '서버 장애 발생' : '주의 알람';
    document.getElementById('alarmSummary').textContent =
        '서버 다운 ' + crit + '건 · 에이전트 보고 끊김 ' + warn + '건 · 미확인 ' + (data.openCount || 0) + '건';

    const admin = isAdminUser();
    document.getElementById('alarmList').innerHTML = activeAlarms.map(a => {
        const sid = Number(a.serverId);
        const ackInfo = a.stateCd === 'ACK'
            ? '<div class="alarm-ack">✔ ' + escapeHtml(a.ackUser || '') + ' 확인 (' + escapeHtml(a.ackDt || '') + ') : ' + escapeHtml(a.actionMemo || '') + '</div>'
            : '';
        return '<li class="alarm-item ' + a.alarmLevel + ' ' + a.stateCd + '">' +
            '<div class="alarm-main">' +
            '<span class="alarm-level">' + (a.alarmLevel === 'CRITICAL' ? '긴급' : '경고') + '</span>' +
            '<span class="alarm-state">' + escapeHtml(a.stateLabel) + '</span>' +
            '<b class="alarm-server">' + escapeHtml(a.systemNm) + '</b> <code>' + escapeHtml(a.ipAddr) + '</code>' +
            '<span class="alarm-type">' + escapeHtml(a.typeLabel) + '</span>' +
            '<div class="alarm-msg">' + escapeHtml(a.message) + '</div>' +
            '<div class="alarm-time">발생 ' + escapeHtml(a.occurDt) + ' · 경과 ' + fmtElapsed(a.elapsedSec) +
            (a.location ? ' · ' + escapeHtml(a.location) : '') + '</div>' + ackInfo +
            '</div>' +
            '<div class="alarm-actions">' +
            '<a class="alarm-act" href="network?server=' + sid + '">구성도</a>' +
            '<button type="button" class="alarm-act" onclick="jumpToProcessTab(' + sid + ')">프로세스 관리</button>' +
            (admin ? '<button type="button" class="alarm-act" onclick="openWebConsole({ serverId: ' + sid + ' })">웹 콘솔</button>' +
                '<button type="button" class="alarm-act primary" onclick="openAlarmAck(' + Number(a.alarmId) + ')">' +
                (a.stateCd === 'ACK' ? '조치 수정' : '확인 / 조치') + '</button>' : '') +
            '</div></li>';
    }).join('');
}

// 메인 홈 서버 카드에 알람 표시 (script.js renderProcessSection 뒤에도 호출됨)
function applyAlarmMarks() {
    document.querySelectorAll('.proc-card[data-server-id]').forEach(card => {
        card.classList.remove('alarm-critical', 'alarm-warning');
        const old = card.querySelector('.proc-alarm-tag');
        if (old) old.remove();
        // 서버 상태가 바뀌었으면 (1분 갱신 serversData) 다운 표시도 맞춤
        const id = Number(card.getAttribute('data-server-id'));
        const server = serversData.find(s => s.id === id);
        const down = (server && server.status === 'Down') ||
            activeAlarms.some(a => a.serverId === id && a.alarmType === 'SERVER_DOWN');
        card.classList.toggle('is-down', down);
        const tag = card.querySelector('.proc-down-alert');
        if (down && !tag) {
            const d = document.createElement('div');
            d.className = 'proc-down-alert';
            d.setAttribute('role', 'alert');
            d.textContent = '⚠ 서버 다운 - 즉시 조치가 필요합니다';
            card.insertBefore(d, card.firstChild);
        } else if (!down && tag) {
            tag.remove();
        }
    });
    activeAlarms.forEach(a => {
        const card = document.getElementById('card-' + a.serverId);
        if (!card) return;
        card.classList.add(a.alarmLevel === 'CRITICAL' ? 'alarm-critical' : 'alarm-warning');
        if (a.alarmLevel !== 'CRITICAL') {
            const tag = document.createElement('div');
            tag.className = 'proc-alarm-tag';
            tag.textContent = '⚠ ' + a.typeLabel + (a.stateCd === 'ACK' ? ' (조치 중)' : '');
            card.insertBefore(tag, card.firstChild);
        }
    });
}

function updateAlarmTitle(openCount) {
    if (titleTimer) { clearInterval(titleTimer); titleTimer = null; }
    document.title = baseTitle;
    if (!openCount) return;
    let on = false;
    titleTimer = setInterval(() => {
        on = !on;
        document.title = on ? '🚨 [장애 ' + openCount + '건] ' + baseTitle : baseTitle;
    }, 1000);
}

function playAlarmSound() {
    const box = document.getElementById('alarmSound');
    if (!box || !box.checked) return;
    try {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        const ctx = new Ctx();
        [0, 0.35, 0.7].forEach(t => {
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'square';
            osc.frequency.value = 880;
            gain.gain.value = 0.08;
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start(ctx.currentTime + t);
            osc.stop(ctx.currentTime + t + 0.2);
        });
        setTimeout(() => ctx.close(), 1500);
    } catch (e) { /* 소리를 낼 수 없는 브라우저 */ }
}

function openAlarmAck(alarmId) {
    ackAlarm = activeAlarms.find(a => a.alarmId === alarmId) || null;
    if (!ackAlarm) return;
    document.getElementById('alarmAckTarget').innerHTML =
        '<b>' + escapeHtml(ackAlarm.systemNm) + '</b> (' + escapeHtml(ackAlarm.ipAddr) + ') · ' + escapeHtml(ackAlarm.typeLabel) +
        '<br>' + escapeHtml(ackAlarm.message);
    document.getElementById('alarmAckMemo').value = ackAlarm.actionMemo || '';
    document.getElementById('alarmAckError').textContent = '';
    document.getElementById('alarmAckModal').style.display = 'flex';
    document.getElementById('alarmAckMemo').focus();
}

function closeAlarmAck() {
    document.getElementById('alarmAckModal').style.display = 'none';
    ackAlarm = null;
}

async function submitAlarmAck() {
    if (!ackAlarm) return;
    const memo = document.getElementById('alarmAckMemo').value.trim();
    if (!memo) { document.getElementById('alarmAckError').textContent = '조치 내용을 입력하세요.'; return; }
    const btn = document.getElementById('alarmAckOk');
    btn.disabled = true;
    try {
        await apiRequest('POST', ALARM_API + '/' + ackAlarm.alarmId + '/ack', { memo: memo });
        closeAlarmAck();
        await loadAlarms();
    } catch (e) {
        document.getElementById('alarmAckError').textContent = e.message;
    } finally {
        btn.disabled = false;
    }
}

function openAlarmHistory() {
    const list = window._recentClosedAlarms || [];
    document.getElementById('alarmHistoryBody').innerHTML = list.length ? list.map(a =>
        '<tr><td><b>' + escapeHtml(a.systemNm) + '</b><span class="ua-sub">' + escapeHtml(a.ipAddr) + '</span></td>' +
        '<td>' + escapeHtml(a.typeLabel) + '</td><td class="svc-time">' + escapeHtml(a.occurDt) + '</td>' +
        '<td class="svc-time">' + escapeHtml(a.closeDt) + '</td><td>' + fmtElapsed(a.elapsedSec) + '</td>' +
        '<td class="ua-reason">' + (a.actionMemo ? escapeHtml(a.actionMemo) + '<span class="ua-sub">' + escapeHtml(a.ackUser || '') + '</span>' : '-') + '</td></tr>'
    ).join('') : '<tr><td colspan="6" class="svc-empty">최근 24시간 동안 해소된 알람이 없습니다.</td></tr>';
    document.getElementById('alarmHistoryModal').style.display = 'flex';
}

// ---------------------------------------------------------------- 에이전트 미등록 서버

const AGENT_STATE_LABEL = { NONE: '미등록', WAITING: '보고 대기', OK: '정상', STALE: '보고 끊김', LOST: '하트비트 끊김', PAUSED: '수집 중지', STOPPED: '데몬 종료' };

async function loadAgentRegistrations() {
    let list;
    try {
        list = await apiRequest('GET', AGENT_API);
    } catch (e) {
        console.warn('에이전트 등록 현황을 불러오지 못했습니다:', e);
        return;
    }
    const panel = document.getElementById('agentRegPanel');
    if (!panel) return;
    const missing = list.filter(v => !v.registered);
    document.getElementById('agentRegCount').textContent = missing.length;
    document.getElementById('agentRegSummary').textContent =
        '전체 ' + list.length + '대 중 등록(정상 보고) ' + (list.length - missing.length) + '대 · ' + new Date().toLocaleTimeString('ko-KR') + ' 기준';
    panel.hidden = missing.length === 0;
    document.getElementById('agentRegBody').innerHTML = missing.map(agentRegRow).join('');
}

function agentRegRow(v) {
    const a = v.agent || {};
    const sid = Number(v.serverId);
    return '<tr id="agentReg-' + sid + '">' +
        '<td><b>' + escapeHtml(v.systemNm) + '</b></td>' +
        '<td><code>' + escapeHtml(v.ipAddr) + '</code></td>' +
        '<td>' + escapeHtml(v.location || '-') + '</td>' +
        '<td><span class="badge ' + (v.statusCd === 'Down' ? 'badge-stopped' : v.statusCd === 'Standby' ? 'badge-standby' : 'badge-running') + '">' + escapeHtml(v.statusCd) + '</span></td>' +
        '<td><span class="svc-agent-state ' + escapeHtml(a.state) + '">' + escapeHtml(AGENT_STATE_LABEL[a.state] || a.state) + '</span></td>' +
        '<td class="ua-reason">' + escapeHtml(v.reason || '-') + '</td>' +
        '<td class="svc-time">' + escapeHtml(a.lastReportDt || '-') + '</td>' +
        '<td class="agent-reg-act"><button type="button" class="svc-btn status" onclick="recheckAgent(' + sid + ')">등록 확인</button>' +
        (isAdminUser() ? '<a class="svc-btn start" href="network?server=' + sid + '">' + (a.state === 'NONE' ? '토큰 발급' : '구성도') + '</a>' : '') +
        '<div class="agent-reg-result" id="agentRegResult-' + sid + '"></div></td></tr>';
}

async function recheckAgent(serverId) {
    const out = document.getElementById('agentRegResult-' + serverId);
    if (out) { out.className = 'agent-reg-result'; out.textContent = '확인 중...'; }
    try {
        const a = await apiRequest('GET', AGENT_API + '/' + serverId);
        if (typeof onAgentStateChanged === 'function') onAgentStateChanged(serverId, a);
        if (!out) return;
        if (a.state === 'OK') {
            out.className = 'agent-reg-result ok';
            out.textContent = '✔ 등록 확인됨 (마지막 보고 ' + (a.lastReportDt || '-') + ')';
            setTimeout(loadAgentRegistrations, 2500);
        } else {
            out.className = 'agent-reg-result fail';
            out.textContent = '✖ 아직 미등록 : ' + (AGENT_STATE_LABEL[a.state] || a.state) +
                (a.state === 'NONE' ? ' - 관리자가 토큰을 발급해야 합니다.'
                    : a.state === 'WAITING' ? ' - 서버에서 bin/scb_client.sh start 로 데몬을 시작하세요.'
                    : a.state === 'PAUSED' ? ' - 에이전트 관리 화면에서 [시작] 하세요.'
                    : a.state === 'STOPPED' ? ' - 데몬이 종료되었습니다. 서버에서 bin/scb_client.sh start 로 시작하세요.'
                    : ' - 클라이언트 프로세스와 네트워크를 확인하세요.');
        }
    } catch (e) {
        if (out) { out.className = 'agent-reg-result fail'; out.textContent = e.message; }
    }
}

// ---------------------------------------------------------------- 시작

window.addEventListener('DOMContentLoaded', function () {
    if (!document.getElementById('alarmBanner')) return;
    // 하트비트 끊김 / 데몬 종료 → 알람·미등록 서버 패널 바로 갱신
    if (typeof onHeartbeatChange === 'function') {
        onHeartbeatChange(() => Promise.all([loadAlarms(), loadAgentRegistrations()]));
    }

    const sound = document.getElementById('alarmSound');
    sound.checked = alarmStore('alarmSound') === 'Y';
    sound.addEventListener('change', () => {
        alarmStore('alarmSound', sound.checked ? 'Y' : 'N');
        if (sound.checked) playAlarmSound();
    });

    const fold = document.getElementById('alarmFoldBtn');
    const setFold = folded => {
        document.getElementById('alarmList').hidden = folded;
        fold.textContent = folded ? '펼치기' : '접기';
        fold.setAttribute('aria-expanded', String(!folded));
        alarmStore('alarmFolded', folded ? 'Y' : 'N');
    };
    setFold(alarmStore('alarmFolded') === 'Y');
    fold.addEventListener('click', () => setFold(!document.getElementById('alarmList').hidden));

    document.getElementById('alarmCheckBtn').addEventListener('click', async () => {
        try {
            await apiRequest('POST', ALARM_API + '/check');
            await loadAlarms();
        } catch (e) { alert(e.message); }
    });
    document.getElementById('alarmAckCancel').addEventListener('click', closeAlarmAck);
    document.getElementById('alarmAckOk').addEventListener('click', submitAlarmAck);
    document.getElementById('alarmHistoryBtn').addEventListener('click', openAlarmHistory);
    document.getElementById('alarmHistoryClose').addEventListener('click', () => {
        document.getElementById('alarmHistoryModal').style.display = 'none';
    });
    document.getElementById('agentRegAllBtn').addEventListener('click', loadAgentRegistrations);
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && ackAlarm) closeAlarmAck(); });

    loadAlarms();
    loadAgentRegistrations();
    setInterval(loadAlarms, ALARM_REFRESH_MS);
    setInterval(loadAgentRegistrations, AGENT_REFRESH_MS);
});
