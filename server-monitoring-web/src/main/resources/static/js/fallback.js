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
        // 데몬이 다시 떠서 사라진 서버 안내
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

    function card(v) {
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
            '</div>' +
            '<div class="fb-tables"><div>' + procTable(v) + '</div><div>' + svcTable(v) + '</div></div>' +
            '<a class="hb-link" href="heartbeat">하트비트 모니터링 →</a>' +
            '</section>';
    }

    function render() {
        $('fbList').innerHTML = list.length ? list.map(card).join('')
            : '<div class="svc-empty fb-empty">데몬이 중단된 서버가 없습니다.<br><small>데몬(scb_client)이 멈추면 하트비트 감시자(클라이언트 1.7 이상)가 30초마다 프로세스 정보를 대신 수집해 여기에 표시합니다.</small></div>';
        const hash = location.hash.replace('#', '');
        if (hash && !render.scrolled) {
            const el = document.getElementById(hash);
            if (el) { el.scrollIntoView(); render.scrolled = true; }
        }
    }

    function setAuto(on) {
        clearInterval(timer);
        timer = on ? setInterval(() => { if (!document.hidden) load(); }, AUTO_MS) : null;
        store('fbAuto', on ? 'Y' : 'N');
    }

    window.addEventListener('DOMContentLoaded', function () {
        if (!$('fbList')) return;
        $('fbReload').addEventListener('click', load);
        $('fbAuto').checked = store('fbAuto') !== 'N';
        $('fbAuto').addEventListener('change', () => setAuto($('fbAuto').checked));
        $('fbList').addEventListener('click', async e => {
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
