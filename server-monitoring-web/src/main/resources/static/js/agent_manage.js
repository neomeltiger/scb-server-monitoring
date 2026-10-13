// ================= 에이전트 관리 (/agents) =================
// API : GET /api/agents, POST /api/agents/{id}/daemon {action}, PUT /api/agents/{id}/collect-interval {sec}
// apiRequest / escapeHtml / isAdminUser 는 script.js
(function () {
    const API = 'api/agents';
    const AUTO_MS = 5000;
    const DAEMON = {
        NONE: ['미실행', 'gray', '서버에서 bin/scb_client.sh start'],
        RUNNING: ['실행 중', 'green', ''],
        PAUSED: ['실행 중 · 수집 중지', 'yellow', ''],
        STOPPING: ['종료 중', 'yellow', '종료 요청을 전달했습니다.'],
        STOPPED: ['종료됨', 'red', '서버에서 bin/scb_client.sh start 로 다시 시작 (클라이언트 1.5 는 [재시작])'],
        DEAD: ['비정상 종료', 'red', '하트비트 감시자가 감지 - 서버는 정상, [재시작] 으로 다시 시작'],
        NO_RESPONSE: ['응답 없음', 'red', '서버에서 bin/scb_client.sh status 로 확인']
    };
    const INTERVAL_LABEL = { 10: '10초', 30: '30초', 60: '1분', 120: '2분', 300: '5분', 600: '10분' };

    const $ = id => document.getElementById(id);
    let list = [];
    let timer = null;
    let busy = false;

    function store(k, v) {
        try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { /* 무시 */ }
        return null;
    }

    function ago(sec) {
        if (sec == null) return '';
        if (sec < 60) return sec + '초 전';
        if (sec < 3600) return Math.floor(sec / 60) + '분 전';
        if (sec < 86400) return Math.floor(sec / 3600) + '시간 전';
        return Math.floor(sec / 86400) + '일 전';
    }

    async function load() {
        if (busy) return;
        try {
            list = await apiRequest('GET', API);
            $('amError').hidden = true;
        } catch (e) {
            $('amError').textContent = e.message;
            $('amError').hidden = false;
            return;
        }
        render();
    }

    function render() {
        const q = $('amSearch').value.trim().toLowerCase();
        const rows = list.filter(v => !q || (v.systemNm + ' ' + v.ipAddr).toLowerCase().indexOf(q) !== -1);
        const reg = list.filter(v => v.agent && v.agent.state !== 'NONE');
        $('amRegCount').textContent = reg.length;
        $('amRunCount').textContent = list.filter(v => v.agent && ['RUNNING', 'PAUSED'].indexOf(v.agent.daemonState) !== -1).length;
        $('amBody').innerHTML = rows.length ? rows.map(rowHtml).join('') : '<tr><td colspan="8" class="svc-empty">서버가 없습니다.</td></tr>';
    }

    // 1.2 미만 = cron 방식 이전 클라이언트 : 웹 제어(ctl)를 모름
    function isOldClient(ver) {
        if (!ver) return false;
        const p = String(ver).split('.').map(Number);
        return (p[0] || 0) < 1 || ((p[0] || 0) === 1 && (p[1] || 0) < 2);
    }

    function rowHtml(v) {
        const a = v.agent || {};
        const admin = isAdminUser();
        const sid = Number(v.serverId);
        const server = '<b>' + escapeHtml(v.systemNm) + '</b><span class="ua-sub">' + escapeHtml(v.ipAddr) +
            (a.agentVer ? ' · client ' + escapeHtml(a.agentVer) : '') + '</span>';
        if (a.state === 'NONE') {
            return '<tr class="am-none"><td class="ua-left">' + server + '</td>' +
                '<td colspan="6"><span class="am-chip gray">토큰 미발급</span> <span class="ua-sub">네트워크 구성도 → 서버 선택 → [에이전트 토큰 발급] 후 서버에 설치</span></td>' +
                '<td class="am-ctl">' + (admin ? '<a class="svc-btn start" href="network?server=' + sid + '">토큰 발급</a>' +
                    '<button type="button" class="svc-btn kill" data-act="reset" data-id="' + sid + '" title="수집 이력·알람 삭제, 등록 상태 Active">데이터 초기화</button>' : '-') + '</td></tr>';
        }
        const d = DAEMON[a.daemonState] || [a.daemonState, 'gray', ''];
        const old = isOldClient(a.agentVer);
        const daemon = (old ? '<span class="am-chip yellow" title="crontab 방식 이전 버전 - 웹 제어(시작/중지/종료, 수집 주기)를 지원하지 않습니다">구버전 ' + escapeHtml(a.agentVer) + '</span>' +
                '<span class="ua-sub am-warn">1.2 로 업그레이드 후 bin/scb_client.sh start</span>' :
            '<span class="am-chip ' + d[1] + '">' + d[0] + '</span>') +
            (a.shutdownPending ? '<span class="ua-sub am-warn">종료 요청 대기 (다음 확인 때 전달)</span>' : '') +
            (!old && d[2] ? '<span class="ua-sub">' + escapeHtml(d[2]) + '</span>' : '');
        const collect = a.collectOn
            ? '<span class="am-chip green">수집 중</span>'
            : '<span class="am-chip yellow">중지</span>';
        const collectSub = a.ctlUser ? '<span class="ua-sub">' + escapeHtml(a.ctlUser) + ' · ' + escapeHtml(a.ctlDt || '') + '</span>' : '';
        const interval = admin
            ? '<select class="am-interval" data-id="' + sid + '" aria-label="' + escapeHtml(v.systemNm) + ' 수집 주기">' +
              (a.collectIntervals || [10, 30, 60, 120, 300, 600]).map(s =>
                  '<option value="' + s + '"' + (s === a.collectIntervalSec ? ' selected' : '') + '>' + (INTERVAL_LABEL[s] || s + '초') + '</option>').join('') + '</select>'
            : escapeHtml(INTERVAL_LABEL[a.collectIntervalSec] || a.collectIntervalSec + '초');
        const pid = a.daemonPid ? 'PID ' + a.daemonPid + '<span class="ua-sub">' + escapeHtml(a.daemonStartDt || '') + '</span>' : '-';
        const poll = a.lastPollDt ? escapeHtml(a.lastPollDt) + '<span class="ua-sub">' + ago(a.lastPollAgoSec) + '</span>' : '-';
        const reportCls = a.state === 'STALE' ? ' am-warn' : '';
        const report = a.lastReportDt ? '<span class="' + reportCls + '">' + escapeHtml(a.lastReportDt) + '</span><span class="ua-sub">' + ago(a.lastReportAgoSec) +
            (a.state === 'STALE' ? ' · 보고 끊김' : '') + '</span>' : '-';
        const alive = ['RUNNING', 'PAUSED'].indexOf(a.daemonState) !== -1;
        const btn = (act, cls, label, disabled, title) =>
            '<button type="button" class="svc-btn ' + cls + '" data-act="' + act + '" data-id="' + sid + '"' +
            (disabled ? ' disabled' : '') + (title ? ' title="' + escapeHtml(title) + '"' : '') + '>' + label + '</button>';
        const ctl = admin
            ? btn('start', 'start', '시작', a.collectOn && !a.shutdownPending, a.collectOn ? '이미 수집 중입니다' : '자료 수집 시작') +
              btn('stop', 'stop', '중지', !a.collectOn, a.collectOn ? '자료 수집 중지 (데몬은 유지)' : '이미 중지됨') +
              btn('shutdown', 'kill', '종료', !alive || a.shutdownPending, alive ? '데몬 프로세스 종료' : '실행 중인 데몬이 없습니다') +
              (a.canRestart && a.daemonIssue ? btn('restart', 'daemon-restart', '재시작', a.restartPending,
                  a.restartPending ? '재시작 요청 전달 중' : '하트비트 감시자가 서버에서 bin/scb_client.sh start') : '') +
              btn('revoke', 'kill', '토큰 제거', false, '이 서버 클라이언트에 발급한 토큰 제거 (수집 이력은 유지)') +
              btn('reset', 'kill', '데이터 초기화', false, '토큰·수집 이력·하트비트·알람 삭제, 등록 상태 Active')
            : '-';
        return '<tr data-id="' + sid + '"><td class="ua-left">' + server + '</td><td>' + daemon + '</td><td>' + collect + collectSub + '</td>' +
            '<td>' + interval + '</td><td class="svc-time">' + pid + '</td><td class="svc-time">' + poll + '</td>' +
            '<td class="svc-time">' + report + '</td><td class="am-ctl">' + ctl + '</td></tr>';
    }

    async function control(id, action) {
        const v = list.find(x => Number(x.serverId) === id);
        const name = v ? v.systemNm : id;
        const msg = {
            start: '[' + name + '] 자료 수집을 시작하시겠습니까?',
            stop: '[' + name + '] 자료 수집을 중지하시겠습니까?\n데몬은 계속 실행되며 언제든 다시 시작할 수 있습니다.',
            shutdown: '[' + name + '] 서버의 데몬 프로세스를 종료하시겠습니까?\n\n종료 후에는 웹에서 다시 켤 수 없습니다.\n서버에서 bin/scb_client.sh start 로 시작해야 합니다.'
        }[action];
        if (action === 'restart') {
            const a = await restartDaemon(id);
            if (a && v) { v.agent = a; render(); }
            return;
        }
        if (action === 'revoke') {
            if (!confirm('[' + name + '] 클라이언트에 발급한 에이전트 토큰을 제거하시겠습니까?\n\n'
                + '제거하면 그 서버의 krcasmrx11_client 는 더 이상 보고할 수 없습니다 (수집 이력은 남음).\n다시 연결하려면 토큰을 새로 발급하세요.')) return;
            try {
                const r = await apiRequest('DELETE', API + '/' + id + '/token');
                alert(r.message);
            } catch (e) { alert(e.message); }
            load();
            return;
        }
        if (action === 'reset') {
            const typed = prompt('[' + name + '] 서버 데이터를 초기화합니다.\n\n'
                + '삭제 : 에이전트 토큰, 자원·프로세스·서비스 수집 이력, 하트비트 이력, 제어 명령, 수집 로그, 알람\n'
                + '유지 : 서버 등록 정보, 주요 프로세스·service.list·로그 수집 설정\n'
                + '등록 상태가 Down 이면 Active 로 바꿉니다.\n\n계속하려면 서버 이름을 그대로 입력하세요 : ' + name);
            if (typed === null) return;
            if (typed.trim() !== String(name)) { alert('서버 이름이 일치하지 않아 취소했습니다.'); return; }
            try {
                await apiRequest('POST', API + '/' + id + '/reset?activate=true');
                alert('[' + name + '] 서버 데이터를 초기화했습니다.');
            } catch (e) { alert(e.message); }
            load();
            return;
        }
        if (!confirm(msg)) return;
        busy = true;
        try {
            const a = await apiRequest('POST', API + '/' + id + '/daemon', { action: action });
            if (v) v.agent = a;
            render();
        } catch (e) { alert(e.message); }
        finally { busy = false; }
    }

    async function changeInterval(sel) {
        const id = Number(sel.getAttribute('data-id'));
        busy = true;
        try {
            const a = await apiRequest('PUT', API + '/' + id + '/collect-interval', { sec: Number(sel.value) });
            const v = list.find(x => Number(x.serverId) === id);
            if (v) v.agent = a;
            render();
        } catch (e) { alert(e.message); load(); }
        finally { busy = false; }
    }

    function setAuto(on) {
        if (timer) { clearInterval(timer); timer = null; }
        if (on) timer = setInterval(() => { if (!document.hidden && !document.activeElement.matches('select.am-interval')) load(); }, AUTO_MS);
        store('amAuto', on ? 'Y' : 'N');
    }

    window.addEventListener('DOMContentLoaded', function () {
        if (!$('amBody')) return;
        if (!isAdminUser()) document.body.classList.add('role-user');
        $('amBody').addEventListener('click', e => {
            const b = e.target.closest('button[data-act]');
            if (b && !b.disabled) control(Number(b.getAttribute('data-id')), b.getAttribute('data-act'));
        });
        $('amBody').addEventListener('change', e => {
            if (e.target.matches('select.am-interval')) changeInterval(e.target);
        });
        $('amSearch').addEventListener('input', render);
        $('amReload').addEventListener('click', load);
        $('amAuto').checked = store('amAuto') !== 'N';
        $('amAuto').addEventListener('change', () => setAuto($('amAuto').checked));
        load();
        setAuto($('amAuto').checked);
    });
})();
