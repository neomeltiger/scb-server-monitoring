// ================= 하트비트 모니터링 (/heartbeat) =================
// API : GET /api/agents/heartbeat (2초마다), GET /api/agents/heartbeat/events?serverId=&rows=
// 주기 변경은 네트워크 구성도 서버 상세에서 (ADMIN). apiRequest / escapeHtml 은 script.js
(function () {
    const API = 'api/agents/heartbeat';
    const AUTO_MS = 2000;
    const EVENT_ROWS = 200;
    // 카드 상태 : 수신 중 / 데몬 문제(서버 정상) / 서버 다운(3회 연속 미수신) / 데몬 종료(이전 클라이언트) / 없음
    const HB = {
        ALIVE: ['수신 중', 'ALIVE'], DAEMON: ['데몬 문제', 'DAEMON'], LOST: ['서버 다운', 'LOST'],
        STOPPED: ['데몬 종료', 'STOPPED'], NONE: ['없음', 'NONE']
    };
    const EVENT = {
        START: ['수신 시작', 'ALIVE'], RECOVER: ['복구', 'ALIVE'], LOST: ['서버 다운', 'LOST'],
        STOP: ['데몬 종료', 'STOPPED'], INTERVAL: ['주기 변경', 'NONE'],
        D_DOWN: ['데몬 중단', 'DAEMON'], D_UP: ['데몬 다시 실행', 'ALIVE'], RESTART: ['재시작 요청', 'NONE']
    };

    function cardState(v) {
        if (v.hbState === 'LOST') return 'LOST';
        if (v.daemonIssue) return 'DAEMON';
        return HB[v.hbState] ? v.hbState : 'NONE';
    }
    const AGENT = { NONE: '토큰 미발급', WAITING: '보고 대기', OK: '정상 보고', STALE: '보고 끊김', LOST: '하트비트 끊김', PAUSED: '수집 중지', STOPPED: '데몬 종료' };

    const $ = id => document.getElementById(id);
    let list = [];
    let fetchedAt = 0;
    let timer = null;
    let tickTimer = null;
    let lastStates = null;
    let events = [];

    function store(k, v) {
        try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { /* 무시 */ }
        return null;
    }

    function fmtGap(sec) {
        if (sec == null) return '-';
        if (sec < 60) return sec + '초';
        if (sec < 3600) return Math.floor(sec / 60) + '분 ' + (sec % 60) + '초';
        if (sec < 86400) return Math.floor(sec / 3600) + '시간 ' + Math.floor(sec % 3600 / 60) + '분';
        return Math.floor(sec / 86400) + '일 ' + Math.floor(sec % 86400 / 3600) + '시간';
    }

    // 받은 뒤 흐른 시간만큼 더해 매초 갱신 (2초 조회 사이에도 초가 움직이게)
    function agoNow(v) {
        if (v.lastHbAgoSec == null) return null;
        return v.lastHbAgoSec + Math.max(0, Math.floor((Date.now() - fetchedAt) / 1000));
    }

    function visible() {
        const q = $('hbSearch').value.trim().toLowerCase();
        const onlyAgent = $('hbOnlyAgent').checked;
        return list.filter(v => (!onlyAgent || v.agentState !== 'NONE') &&
            (!q || [v.systemNm, v.ipAddr, v.location].some(x => String(x || '').toLowerCase().indexOf(q) !== -1)));
    }

    async function load() {
        try {
            list = await apiRequest('GET', API);
            fetchedAt = Date.now();
            $('hbError').hidden = true;
        } catch (e) {
            $('hbError').hidden = false;
            $('hbError').textContent = '하트비트 상태를 불러오지 못했습니다: ' + e.message;
            return;
        }
        // 상태가 바뀐 서버가 있으면 이벤트 이력도 바로 다시 읽음
        const now = list.map(v => v.serverId + ':' + v.hbState).join(',');
        if (lastStates !== null && now !== lastStates) loadEvents();
        lastStates = now;
        fillServerSelect();
        render();
    }

    function summary() {
        const n = { ALIVE: 0, DAEMON: 0, LOST: 0, STOPPED: 0, NONE: 0 };
        list.forEach(v => { n[cardState(v)]++; });
        $('hbSummary').innerHTML = ['ALIVE', 'DAEMON', 'LOST', 'STOPPED', 'NONE'].map(k =>
            '<span class="hb-sum ' + k + '"><span class="hb-badge ' + k + '">' + HB[k][0] + '</span> <b>' + n[k] + '</b>대</span>').join('') +
            '<span class="hb-sum-note">전체 ' + list.length + '대 · 서버 다운 = 하트비트 3회 연속 미수신 · 데몬 문제 = 서버 정상, 데몬만 재시작</span>';
    }

    function missHtml(v) {
        if (v.hbMisses == null || v.hbState === 'NONE') return '-';
        const lim = v.hbMissLimit || 3;
        const n = Math.min(v.hbMisses, lim);
        let dots = '';
        for (let i = 1; i <= lim; i++) dots += '<i class="' + (i <= n ? (n >= lim ? 'bad' : 'warn') : '') + '"></i>';
        return '<span class="hb-miss-dots" aria-label="연속 미수신 ' + n + ' / ' + lim + '">' + dots + '</span> ' + n + ' / ' + lim + '회';
    }

    function card(v) {
        const st = cardState(v);
        const ago = agoNow(v);
        const limit = v.hbTimeoutSec || 23;
        const pct = ago == null ? 0 : Math.min(100, Math.round(ago * 100 / limit));
        const barCls = st === 'LOST' || pct >= 100 ? 'bad' : pct >= 34 ? 'warn' : 'ok';
        const legacy = v.agentState !== 'NONE' && !v.hbClient && v.lastHbDt;
        const lines = [
            ['마지막 수신', v.lastHbDt ? escapeHtml(v.lastHbDt) + ' <b class="hb-ago">(' + fmtGap(ago) + ' 전)</b>' : '-'],
            ['연속 미수신', missHtml(v)],
            ['주기 / 다운 기준', v.hbClient ? v.hbIntervalSec + '초 / ' + limit + '초' : (legacy ? '웹 확인 10초 / ' + limit + '초' : v.hbIntervalSec + '초 (설정)')],
            ['데몬 PID', v.hbPid != null ? escapeHtml(v.hbPid) : '-'],
            ['부팅 자동 시작', bootHtml(v)],
            ['서버 부팅', v.bootTime ? escapeHtml(v.bootTime) : '-'],
            ['에이전트', escapeHtml(AGENT[v.agentState] || v.agentState) + (v.agentVer ? ' · ' + escapeHtml(v.agentVer) : '') +
                (legacy ? ' <span class="hb-tag" title="하트비트 셸이 없는 1.3 이하 - 데몬의 웹 확인으로 판단">이전 방식</span>' : '')]
        ];
        return '<div class="hb-card ' + st + '" data-id="' + v.serverId + '">' +
            '<div class="hb-card-head"><span class="hb-dot ' + st + '" aria-hidden="true"></span>' +
            '<div class="hb-card-title"><b>' + escapeHtml(v.systemNm) + '</b><small>' + escapeHtml(v.ipAddr) + ' · ' + escapeHtml(v.location || '') + '</small></div>' +
            '<span class="hb-badge ' + st + '">' + HB[st][0] + '</span></div>' +
            '<div class="hb-bar" role="img" aria-label="끊김 기준 대비 경과 ' + pct + '%"><i class="' + barCls + '" style="width:' + (st === 'NONE' ? 0 : pct) + '%"></i></div>' +
            '<table class="hb-info">' + lines.map(r => '<tr><th>' + r[0] + '</th><td>' + r[1] + '</td></tr>').join('') + '</table>' +
            (st === 'LOST' ? '<div class="hb-alert">하트비트 ' + (v.hbMissLimit || 3) + '회 연속 미수신 - 서버 다운으로 보고 모니터링 대상 프로세스를 종료로 표시했습니다. 서버 전원·네트워크를 확인하세요.</div>' : '') +
            (st === 'DAEMON' || st === 'STOPPED' ? daemonNotice(v) : '') +
            '<a class="hb-link" href="network?server=' + v.serverId + '">네트워크 구성도에서 보기' + (isAdminUser() ? ' · 주기 변경' : '') + ' →</a>' +
            '</div>';
    }

    // 서버가 되살아났을 때 자동 복구되려면 부팅 스크립트가 있어야 함
    function bootHtml(v) {
        if (v.bootAuto === 'Y') return '<span class="hb-boot ok">설치됨</span>';
        if (v.bootAuto === 'N') return '<span class="hb-boot bad" title="서버 재부팅 후 데몬·감시자가 자동으로 뜨지 않아 모니터링이 복구되지 않습니다">미설치</span>' +
            '<span class="hb-boot-cmd">root : <code>bin/scb_client.sh install-boot</code></span>';
        return '<span class="hb-boot">알 수 없음</span>' + (v.agentState !== 'NONE' ? '<span class="hb-boot-cmd">클라이언트 1.6 이상에서 표시</span>' : '');
    }

    function daemonNotice(v) {
        const issue = v.daemonIssue || '데몬 종료';
        let act;
        if (v.restartPending) act = '<div class="hb-restart-wait">재시작 요청을 보냈습니다 - 다음 하트비트 때 서버에서 시작합니다.</div>';
        else if (isAdminUser() && v.canRestart) act = '<button type="button" class="btn btn-restart" data-restart="' + v.serverId + '">데몬 재시작</button>';
        else act = '<div>서버에서 <code>bin/scb_client.sh start</code>' + (v.watchdog ? '' : ' (웹 재시작은 클라이언트 1.5 이상)') + '</div>';
        return '<div class="hb-alert daemon">⚠ ' + escapeHtml(issue) + ' - 서버는 정상입니다(하트비트 수신 중). 데몬(scb_client)만 재시작하면 됩니다.' + act +
            '<a class="hb-link" href="fallback#fb-' + v.serverId + '">데몬 중단 중 프로세스 현황 보기 →</a></div>';
    }

    function render() {
        summary();
        const rows = visible();
        $('hbGrid').innerHTML = rows.length ? rows.map(card).join('')
            : '<div class="svc-empty">' + (list.length ? '조건에 맞는 서버가 없습니다.' : '등록된 서버가 없습니다.') + '</div>';
    }

    // 2초 조회 사이에도 경과 시간·막대만 매초 갱신
    function tick() {
        document.querySelectorAll('#hbGrid .hb-card').forEach(el => {
            const v = list.find(x => String(x.serverId) === el.getAttribute('data-id'));
            if (!v) return;
            const ago = agoNow(v);
            const a = el.querySelector('.hb-ago');
            if (a) a.textContent = '(' + fmtGap(ago) + ' 전)';
            const bar = el.querySelector('.hb-bar i');
            if (bar && v.hbState !== 'NONE' && ago != null) {
                const pct = Math.min(100, Math.round(ago * 100 / (v.hbTimeoutSec || 23)));
                bar.style.width = pct + '%';
                bar.className = v.hbState === 'LOST' || pct >= 100 ? 'bad' : pct >= 60 ? 'warn' : 'ok';
            }
        });
    }

    function fillServerSelect() {
        const sel = $('hbEventServer');
        if (sel.options.length > 1) return;
        list.forEach(v => {
            const o = document.createElement('option');
            o.value = v.serverId;
            o.textContent = v.systemNm + ' (' + v.ipAddr + ')';
            sel.appendChild(o);
        });
        const keep = store('hbEventServer');
        if (keep && [...sel.options].some(o => o.value === keep)) sel.value = keep;
    }

    async function loadEvents() {
        const sid = $('hbEventServer').value;
        try {
            events = await apiRequest('GET', API + '/events?rows=' + EVENT_ROWS + (sid ? '&serverId=' + encodeURIComponent(sid) : ''));
        } catch (e) {
            $('hbEventBody').innerHTML = '<tr><td colspan="6" class="svc-empty">이력을 불러오지 못했습니다: ' + escapeHtml(e.message) + '</td></tr>';
            return;
        }
        renderEvents();
    }

    function renderEvents() {
        const type = $('hbEventType').value;
        const rows = events.filter(e => !type || e.event === type);
        $('hbEventBody').innerHTML = rows.length ? rows.map(e => {
            const ev = EVENT[e.event] || [e.event, 'NONE'];
            return '<tr class="hb-ev-' + escapeHtml(e.event) + '"><td class="svc-time">' + escapeHtml(e.eventDt) + '</td>' +
                '<td>' + escapeHtml(e.systemNm) + '</td>' +
                '<td><span class="hb-badge ' + ev[1] + '">' + ev[0] + '</span></td>' +
                '<td>' + (e.pid != null ? escapeHtml(e.pid) : '-') + '</td>' +
                '<td>' + (e.gapSec != null ? fmtGap(e.gapSec) : '-') + '</td>' +
                '<td class="hb-ev-detail">' + escapeHtml(e.detail || '') + '</td></tr>';
        }).join('') : '<tr><td colspan="6" class="svc-empty">이벤트가 없습니다.</td></tr>';
    }

    function setAuto(on) {
        clearInterval(timer);
        timer = on ? setInterval(() => { if (!document.hidden) load(); }, AUTO_MS) : null;
        store('hbAuto', on ? 'Y' : 'N');
    }

    window.addEventListener('DOMContentLoaded', function () {
        if (!$('hbGrid')) return;
        $('hbSearch').addEventListener('input', render);
        $('hbOnlyAgent').checked = store('hbOnlyAgent') !== 'N';
        $('hbOnlyAgent').addEventListener('change', () => { store('hbOnlyAgent', $('hbOnlyAgent').checked ? 'Y' : 'N'); render(); });
        $('hbAuto').checked = store('hbAuto') !== 'N';
        $('hbAuto').addEventListener('change', () => setAuto($('hbAuto').checked));
        $('hbEventServer').addEventListener('change', () => { store('hbEventServer', $('hbEventServer').value); loadEvents(); });
        $('hbEventType').addEventListener('change', renderEvents);
        $('hbEventReload').addEventListener('click', loadEvents);
        $('hbGrid').addEventListener('click', async e => {
            const b = e.target.closest('button[data-restart]');
            if (!b) return;
            b.disabled = true;
            if (await restartDaemon(Number(b.getAttribute('data-restart')))) { await load(); loadEvents(); }
            else b.disabled = false;
        });
        load().then(loadEvents);
        setAuto($('hbAuto').checked);
        tickTimer = setInterval(tick, 1000);
    });
})();
