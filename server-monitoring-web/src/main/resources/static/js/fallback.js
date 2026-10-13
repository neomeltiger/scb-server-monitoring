// ================= 데몬 중단 프로세스 현황 (/fallback) =================
// API : GET /api/agents/fallback - 데몬이 멈춘 동안 하트비트 감시자가 대신 수집한 서버별 프로세스·서비스
// 데몬이 다시 시작되면 서버에서 삭제 → 목록에서 사라짐. apiRequest / escapeHtml / isAdminUser / restartDaemon 은 script.js
(function () {
    const API = 'api/agents/fallback';
    const AUTO_MS = 5000;
    const $ = id => document.getElementById(id);
    let list = [];
    let timer = null;
    let known = null;
    // 서버별 실행 프로세스 표 검색·정렬·전체 보기 (5초 갱신해도 유지)
    const psState = {};
    const PS_SHOW = 30;
    let lastTyping = 0;

    function store(k, v) {
        try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { /* 무시 */ }
        return null;
    }

    function fmtGap(sec) {
        if (sec == null) return '-';
        if (sec < 60) return sec + '초';
        if (sec < 3600) return Math.floor(sec / 60) + '분 ' + (sec % 60) + '초';
        return Math.floor(sec / 3600) + '시간 ' + Math.floor(sec % 3600 / 60) + '분';
    }

    function pct(v) { return v == null || v === '' ? '-' : escapeHtml(v) + '%'; }

    async function load() {
        try {
            list = await apiRequest('GET', API);
            $('fbError').hidden = true;
        } catch (e) {
            $('fbError').hidden = false;
            $('fbError').textContent = '현황을 불러오지 못했습니다: ' + e.message;
            return;
        }
        // 데몬이 다시 떠서 사라진 서버 안내 (정보 없음 카드는 제외)
        const ids = list.map(v => v.serverId);
        if (known) {
            const gone = known.filter(x => ids.indexOf(x.id) === -1);
            if (gone.length) showGone(gone);
        }
        known = list.map(v => ({ id: v.serverId, name: v.systemNm }));
        render();
    }

    function showGone(gone) {
        const box = document.createElement('div');
        box.className = 'fb-gone';
        box.setAttribute('role', 'status');
        box.textContent = '✔ ' + gone.map(g => g.name).join(', ') + ' - 데몬이 다시 시작되어 대신 수집한 정보를 삭제했습니다. 다른 화면에서 정상 정보를 확인하세요.';
        $('fbList').parentNode.insertBefore(box, $('fbList'));
        setTimeout(() => box.remove(), 15000);
    }

    function procTable(v) {
        if (!v.processes.length) return '<div class="fb-none">등록된 주요 프로세스가 없습니다.</div>';
        return '<table class="full-spec-table fb-table"><thead><tr><th>주요 프로세스</th><th>상태</th><th>PID</th><th>CPU</th><th>MEM</th></tr></thead><tbody>' +
            v.processes.map(p => '<tr><td class="svc-name" title="' + escapeHtml(p.path || '') + '">' + escapeHtml(p.name) + '</td>' +
                '<td><span class="svc-state ' + (p.running ? 'RUNNING' : 'STOPPED') + '">' + (p.running ? '실행 중' : '종료') + '</span></td>' +
                '<td><code>' + (p.pid != null ? escapeHtml(p.pid) : '-') + '</code></td><td>' + pct(p.cpu) + '</td><td>' + pct(p.mem) + '</td></tr>').join('') +
            '</tbody></table>';
    }

    function svcTable(v) {
        if (!v.services.length) return '<div class="fb-none">service.list 서비스 정보가 없습니다.</div>';
        return '<table class="full-spec-table fb-table"><thead><tr><th>그룹</th><th>Level</th><th>서비스 (service.list)</th><th>상태</th><th>PID</th><th>CPU</th><th>MEM</th></tr></thead><tbody>' +
            v.services.map(s => '<tr><td>' + (s.group != null ? s.group : '-') + '</td><td>' + (s.level != null ? s.level : '-') + '</td>' +
                '<td class="svc-name">' + escapeHtml(s.name) + '</td>' +
                '<td><span class="svc-state ' + escapeHtml(s.state) + '">' + (s.state === 'RUNNING' ? '실행 중' : '종료') + '</span></td>' +
                '<td><code>' + (s.pid ? escapeHtml(s.pid) : '-') + '</code></td><td>' + pct(s.cpu) + '</td><td>' + pct(s.mem) + '</td></tr>').join('') +
            '</tbody></table>';
    }

    function psNum(x) { const n = parseFloat(x); return isNaN(n) ? -1 : n; }

    function psRowsOf(v) {
        const st = psState[v.serverId] || (psState[v.serverId] = { q: '', sort: 'cpu', all: false });
        const q = st.q.trim().toLowerCase();
        let rows = v.psList.filter(p => !q || [p.pid, p.user, p.args, p.tag].some(x => String(x || '').toLowerCase().indexOf(q) !== -1));
        const key = st.sort;
        rows = rows.slice().sort((a, b) => key === 'user' ? String(a.user).localeCompare(String(b.user))
            : key === 'pid' ? psNum(a.pid) - psNum(b.pid)
            : key === 'tag' ? (b.tag ? 1 : 0) - (a.tag ? 1 : 0) || psNum(b.cpu) - psNum(a.cpu)
            : psNum(b[key]) - psNum(a[key]));
        return { st: st, rows: rows };
    }

    function psTableHtml(v) {
        const r = psRowsOf(v);
        const shown = r.st.all ? r.rows : r.rows.slice(0, PS_SHOW);
        const body = shown.length ? shown.map(p => '<tr' + (p.tag ? ' class="fb-ps-main"' : '') + '>' +
                '<td><code>' + escapeHtml(p.pid) + '</code></td><td>' + escapeHtml(p.ppid) + '</td><td>' + escapeHtml(p.user) + '</td>' +
                '<td>' + pct(p.cpu) + '</td><td>' + pct(p.mem) + '</td><td class="svc-time">' + escapeHtml(p.etime) + '</td>' +
                '<td class="fb-ps-args">' + (p.tag ? '<span class="fb-ps-tag">' + escapeHtml(p.tag) + '</span> ' : '') + escapeHtml(p.args) + '</td></tr>').join('')
            : '<tr><td colspan="7" class="svc-empty">' + (r.st.q ? '검색 결과가 없습니다.' : '프로세스 정보가 없습니다.') + '</td></tr>';
        const more = r.rows.length > PS_SHOW
            ? '<button type="button" class="btn svc-btn-light fb-ps-more" data-ps-more="' + v.serverId + '">' +
              (r.st.all ? '처음 ' + PS_SHOW + '건만 보기' : '전체 ' + r.rows.length + '건 보기') + '</button>' : '';
        return '<table class="full-spec-table fb-table fb-ps-table"><thead><tr><th>PID</th><th>PPID</th><th>사용자</th><th>CPU</th><th>MEM</th><th>경과</th><th>명령</th></tr></thead>' +
            '<tbody>' + body + '</tbody></table>' + more;
    }

    function psSection(v) {
        if (!v.psCollected) {
            return '<div class="fb-ps"><h4>서버 실행 중 프로세스</h4><div class="fb-none">클라이언트 1.8 이상에서 표시됩니다. (서버의 svr_collect_client 를 업그레이드하세요)</div></div>';
        }
        const st = psState[v.serverId] || (psState[v.serverId] = { q: '', sort: 'cpu', all: false });
        const tagged = v.psList.filter(p => p.tag).length;
        return '<div class="fb-ps" data-id="' + v.serverId + '">' +
            '<div class="fb-ps-head"><h4>서버 실행 중 프로세스 <span class="svc-sub">' + v.psList.length + '개 · CPU 높은 순 · 하트비트 감시자 제외' +
            (tagged ? ' · 주요 프로세스/서비스 ' + tagged + '개 강조' : '') + '</span></h4>' +
            '<div class="svc-toolbar"><input type="search" class="search-box fb-ps-q" data-ps-q="' + v.serverId + '" value="' + escapeHtml(st.q) +
            '" placeholder="PID, 사용자, 명령 검색..." aria-label="' + escapeHtml(v.systemNm) + ' 프로세스 검색">' +
            '<select class="am-interval" data-ps-sort="' + v.serverId + '" aria-label="정렬">' +
            [['cpu', 'CPU 순'], ['mem', 'MEM 순'], ['pid', 'PID 순'], ['user', '사용자 순'], ['tag', '주요 프로세스 먼저']].map(o =>
                '<option value="' + o[0] + '"' + (st.sort === o[0] ? ' selected' : '') + '>' + o[1] + '</option>').join('') + '</select></div></div>' +
            '<div class="fb-ps-body" id="fbPs-' + v.serverId + '">' + psTableHtml(v) + '</div></div>';
    }

    function refreshPs(serverId) {
        const v = list.find(x => x.serverId === serverId);
        const box = document.getElementById('fbPs-' + serverId);
        if (v && box) box.innerHTML = psTableHtml(v);
    }

    // 데몬이 멈췄는데 아직 대신 수집한 정보가 없는 서버 : 원인과 조치
    function pendingCard(v) {
        const waiting = !v.pendingAction;
        let act = '';
        if (v.restartPending) act = '<span class="hb-restart-wait">재시작 요청 전달 중</span>';
        else if (isAdminUser() && v.canRestart) act = '<button type="button" class="btn btn-restart" data-restart="' + v.serverId + '">데몬 재시작</button>';
        return '<section class="fb-card ' + (waiting ? 'waiting' : 'blocked') + '" id="fb-' + v.serverId + '">' +
            '<div class="fb-head"><span class="hb-dot DAEMON" aria-hidden="true"></span>' +
            '<div class="fb-title"><b>' + escapeHtml(v.systemNm) + '</b><small>' + escapeHtml(v.ipAddr) + ' · ' + escapeHtml(v.location || '') + '</small></div>' +
            '<span class="hb-badge DAEMON">' + escapeHtml(v.daemonIssue || '데몬 중단') + '</span>' +
            '<span class="hb-badge ' + (waiting ? 'NONE' : 'STOPPED') + '">' + (waiting ? '정보 수집 중' : '정보 없음') + '</span>' + act + '</div>' +
            '<div class="fb-pending-msg">' + (waiting ? '⏳ ' : '⚠ ') + escapeHtml(v.pendingReason) + '</div>' +
            (v.pendingAction ? '<div class="fb-pending-act">조치 : <code>' + escapeHtml(v.pendingAction) + '</code></div>' : '') +
            '<div class="fb-meta"><span>감시자 버전 ' + escapeHtml(v.watchdogVer || '알 수 없음 (1.8 이하)') + '</span>' +
            (v.fbStat ? '<span>대신 수집 결과 ' + escapeHtml(v.fbStat) + '</span>' : '') + '</div>' +
            '</section>';
    }

    function card(v) {
        if (v.pending) return pendingCard(v);
        const issue = v.daemonIssue || (v.daemonState === 'DEAD' ? '데몬 비정상 종료' : '데몬 정지');
        let act = '';
        if (v.restartPending) act = '<span class="hb-restart-wait">재시작 요청 전달 중 - 데몬이 뜨면 이 정보는 삭제됩니다.</span>';
        else if (isAdminUser() && v.canRestart) act = '<button type="button" class="btn btn-restart" data-restart="' + v.serverId + '">데몬 재시작</button>';
        else act = '<span class="fb-cmd">서버에서 <code>bin/scb_client.sh start</code></span>';
        const staleMsg = v.serverDown
            ? '<div class="hb-alert">하트비트도 끊겼습니다 (서버 다운). 아래는 마지막으로 받은 정보입니다.</div>'
            : v.stale ? '<div class="hb-alert">새 정보가 ' + fmtGap(v.collectAgoSec) + ' 동안 없습니다. 감시자 수집이 지연되고 있습니다.</div>' : '';
        return '<section class="fb-card' + (v.stale ? ' stale' : '') + '" id="fb-' + v.serverId + '">' +
            '<div class="fb-head"><span class="hb-dot DAEMON" aria-hidden="true"></span>' +
            '<div class="fb-title"><b>' + escapeHtml(v.systemNm) + '</b><small>' + escapeHtml(v.ipAddr) + ' · ' + escapeHtml(v.location || '') + '</small></div>' +
            '<span class="hb-badge DAEMON">' + escapeHtml(issue) + '</span>' + act + '</div>' +
            staleMsg +
            '<div class="fb-meta">' +
            '<span>수집 <b>' + escapeHtml(v.collectDt || '-') + '</b> (' + fmtGap(v.collectAgoSec) + ' 전)</span>' +
            '<span>데몬 중단 후 처음 수집 ' + escapeHtml(v.firstDt || '-') + ' · ' + v.recvCnt + '회</span>' +
            '<span>CPU ' + pct(v.cpu) + ' · MEM ' + pct(v.mem) + ' · DISK ' + pct(v.disk) + '</span>' +
            '<span>주요 프로세스 실행 <b>' + v.procRunning + '</b> / ' + v.processes.length + ' · 서비스 실행 <b>' + v.svcRunning + '</b> / ' + v.services.length + '</span>' +
            (v.watchdogVer ? '<span>감시자 ' + escapeHtml(v.watchdogVer) + '</span>' : '') +
            '</div>' +
            '<div class="fb-tables"><div>' + procTable(v) + '</div><div>' + svcTable(v) + '</div></div>' +
            psSection(v) +
            '<a class="hb-link" href="heartbeat">하트비트 모니터링 →</a>' +
            '</section>';
    }

    function render() {
        // 다시 그려도 검색창 입력 위치 유지
        const ae = document.activeElement;
        const focusId = ae && ae.getAttribute && ae.getAttribute('data-ps-q');
        const caret = focusId ? ae.selectionStart : null;
        $('fbList').innerHTML = list.length ? list.map(card).join('')
            : '<div class="svc-empty fb-empty">데몬이 중단된 서버가 없습니다.<br><small>데몬(scb_client)이 멈추면 하트비트 감시자(클라이언트 1.7 이상)가 30초마다 프로세스 정보를 대신 수집해 여기에 표시합니다.</small></div>';
        if (focusId) {
            const el = document.querySelector('[data-ps-q="' + focusId + '"]');
            if (el) { el.focus(); try { el.setSelectionRange(caret, caret); } catch (e) { /* 무시 */ } }
        }
        const hash = location.hash.replace('#', '');
        if (hash && !render.scrolled) {
            const el = document.getElementById(hash);
            if (el) { el.scrollIntoView(); render.scrolled = true; }
        }
    }

    function setAuto(on) {
        clearInterval(timer);
        // 검색어를 입력하는 중(마지막 입력 후 3초)에는 다시 그리지 않음 (입력 위치 유지)
        timer = on ? setInterval(() => {
            if (document.hidden || Date.now() - lastTyping < 3000) return;
            load();
        }, AUTO_MS) : null;
        store('fbAuto', on ? 'Y' : 'N');
    }

    window.addEventListener('DOMContentLoaded', function () {
        if (!$('fbList')) return;
        $('fbReload').addEventListener('click', load);
        $('fbAuto').checked = store('fbAuto') !== 'N';
        $('fbAuto').addEventListener('change', () => setAuto($('fbAuto').checked));
        $('fbList').addEventListener('input', e => {
            const q = e.target.closest('[data-ps-q]');
            if (!q) return;
            const id = Number(q.getAttribute('data-ps-q'));
            lastTyping = Date.now();
            (psState[id] = psState[id] || { q: '', sort: 'cpu', all: false }).q = q.value;
            refreshPs(id);
        });
        $('fbList').addEventListener('change', e => {
            const s = e.target.closest('[data-ps-sort]');
            if (!s) return;
            const id = Number(s.getAttribute('data-ps-sort'));
            (psState[id] = psState[id] || { q: '', sort: 'cpu', all: false }).sort = s.value;
            refreshPs(id);
        });
        $('fbList').addEventListener('click', async e => {
            const m = e.target.closest('button[data-ps-more]');
            if (m) {
                const id = Number(m.getAttribute('data-ps-more'));
                psState[id].all = !psState[id].all;
                refreshPs(id);
                return;
            }
            const b = e.target.closest('button[data-restart]');
            if (!b) return;
            b.disabled = true;
            if (await restartDaemon(Number(b.getAttribute('data-restart')))) load();
            else b.disabled = false;
        });
        load();
        setAuto($('fbAuto').checked);
    });
})();
