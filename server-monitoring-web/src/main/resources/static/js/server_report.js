// ================= 서버 점검 리포트 (/admin/server-report, 관리자 전용) =================
// API : /api/admin/server-report/{serverId}[/{reportId}] (srvreport.controller.ServerReportController)
// 관리자가 [리포트 요청] 을 누를 때만 svr_collect_client 1.12 의 bin/collect_report.sh 가 만들어 보냄
// 리포트 본문 형식 : #SCBRPT|ver|host|os|time|sections, #SEC|code|title, #ITEM|code|title|source,
//                    #SUM|요약, #WARN|경고, #HL|강조(웹 관리 프로세스), #END|소요초, 그 밖의 줄 = 원문
// apiRequest / escapeHtml 는 script.js, scbConfirm / scbAlert 는 scb_dialog.js
(function () {
    const API = '../api/admin/server-report/';
    const POLL_MS = 5000;
    const SECTION_NAMES = { sec: '보안·접근', res: '시스템 자원', svc: '서비스·스케줄러', chg: '패키지·변경' };
    const STATUS = {
        REQUESTED: ['요청됨 · 에이전트 확인 대기', 'info'],
        SENT: ['서버에서 점검 중', 'info'],
        DONE: ['완료', 'success'],
        ERROR: ['오류', 'error']
    };
    const AGENT = { NONE: '미등록', WAITING: '보고 대기', OK: '연결', STALE: '보고 끊김', LOST: '하트비트 끊김', PAUSED: '수집 중지', STOPPED: '데몬 종료' };

    const $ = id => document.getElementById(id);
    let serverId = null;
    let data = null;          // 서버 + 리포트 목록
    let selected = null;      // 선택한 리포트 ID
    let report = null;        // 보고 있는 리포트 (내용 포함)
    let mode = 'all';
    let timer = null;

    function store(k, v) {
        try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { /* 무시 */ }
        return null;
    }

    function fmtBytes(n) {
        return n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
    }

    function secNames(s) {
        return (s || '').split(',').map(c => SECTION_NAMES[c] || c).join(', ');
    }

    function statusOf(r) {
        if ((r.status === 'REQUESTED' || r.status === 'SENT') && r.overdue) return ['응답 없음 (10분 초과)', 'warning'];
        return STATUS[r.status] || [r.status, 'info'];
    }

    // ------------------------------------------------------------ 불러오기

    async function load(keepSelection) {
        if (!serverId) return;
        try {
            data = await apiRequest('GET', API + serverId);
        } catch (e) {
            $('rpStatus').innerHTML = '<span class="lg-bad">' + escapeHtml(e.message) + '</span>';
            return;
        }
        renderStatus();
        renderList();
        const ids = data.reports.map(r => r.id);
        if (!keepSelection || !ids.includes(selected)) {
            const first = data.reports.find(r => r.status === 'DONE' || r.status === 'ERROR') || data.reports[0];
            selected = first ? first.id : null;
            report = null;
        }
        await loadReport();
        schedule();
    }

    async function loadReport() {
        renderListActive();
        if (!selected) { report = null; renderView(); return; }
        const meta = data.reports.find(r => r.id === selected);
        // 진행 중인 리포트는 내용이 없으므로 다시 받을 필요 없음 / 이미 받은 완료 리포트도 다시 받지 않음
        if (report && report.id === selected && report.status === (meta && meta.status)) { renderView(); return; }
        try {
            report = await apiRequest('GET', API + serverId + '/' + selected);
        } catch (e) {
            report = null;
            $('rpView').innerHTML = '<div class="lg-empty lg-bad">' + escapeHtml(e.message) + '</div>';
            return;
        }
        renderView();
    }

    // 진행 중인 요청이 있으면 5초마다 확인
    function schedule() {
        if (timer) { clearTimeout(timer); timer = null; }
        const active = data && data.reports.some(r => (r.status === 'REQUESTED' || r.status === 'SENT') && !r.overdue);
        if (active) timer = setTimeout(() => load(true), POLL_MS);
    }

    // ------------------------------------------------------------ 그리기

    function renderStatus() {
        const st = AGENT[data.agentState] || data.agentState || '-';
        const bad = ['NONE', 'LOST', 'STOPPED', 'STALE'].includes(data.agentState);
        let html = '<span>서버 <b>' + escapeHtml(data.serverName) + '</b> (' + escapeHtml(data.ip || '-') + ')</span>'
            + '<span>에이전트 <b class="' + (bad ? 'lg-bad' : '') + '">' + escapeHtml(st) + '</b></span>'
            + '<span>클라이언트 <b>' + escapeHtml(data.agentVer || '-') + '</b></span>'
            + '<span>마지막 웹 확인 <b>' + escapeHtml(data.lastPollDt || '-') + '</b></span>';
        if (!data.clientSupported) {
            html += '<span class="lg-bad">이 서버의 클라이언트는 리포트를 지원하지 않습니다 (1.12 이상 필요)</span>';
        }
        if (data.agentState === 'NONE') {
            html += '<span class="lg-bad">에이전트가 등록되지 않은 서버입니다</span>';
        }
        $('rpStatus').innerHTML = html;
        $('rpRequest').disabled = data.agentState === 'NONE' || !data.clientSupported;
    }

    function renderList() {
        const ul = $('rpList');
        if (!data.reports.length) {
            ul.innerHTML = '<li class="lg-empty">아직 리포트가 없습니다. [리포트 요청] 을 누르세요.</li>';
            return;
        }
        ul.innerHTML = data.reports.map(r => {
            const s = statusOf(r);
            const warn = r.status === 'DONE' || r.status === 'ERROR'
                ? (r.warnCnt > 0 ? '<span class="rp-warn-cnt">경고 ' + r.warnCnt + '</span>' : '<span class="rp-ok-cnt">경고 없음</span>') : '';
            return '<li class="rp-item" data-id="' + r.id + '" tabindex="0" role="button">'
                + '<div class="rp-item-top"><b>#' + r.id + '</b> <span class="rp-badge" data-status="' + s[1] + '">' + escapeHtml(s[0]) + '</span> ' + warn + '</div>'
                + '<div class="rp-item-sub">' + escapeHtml(r.reqDt || '') + ' · ' + escapeHtml(r.reqUser || '-') + '</div>'
                + '<div class="rp-item-sub">' + escapeHtml(secNames(r.sections)) + (r.byteCnt ? ' · ' + fmtBytes(r.byteCnt) : '') + '</div>'
                + '<button type="button" class="rp-del" data-del="' + r.id + '" title="삭제" aria-label="리포트 #' + r.id + ' 삭제">✕</button>'
                + '</li>';
        }).join('');
        renderListActive();
    }

    function renderListActive() {
        document.querySelectorAll('#rpList .rp-item').forEach(li => li.classList.toggle('active', Number(li.dataset.id) === selected));
    }

    // 리포트 본문 → 분야 / 항목 블록
    function parse(text) {
        const out = { head: null, warns: [], sections: [], end: null };
        let sec = null, item = null;
        const ensureItem = () => {
            if (!sec) { sec = { code: '', title: '기본 정보', items: [] }; out.sections.push(sec); }
            if (!item) { item = { code: '', title: '', source: '', lines: [] }; sec.items.push(item); }
        };
        (text || '').split('\n').forEach(line => {
            if (line.startsWith('#SCBRPT|')) {
                const p = line.split('|');
                out.head = { host: p[2], os: p[3], time: p[4], sections: p[5] };
            } else if (line.startsWith('#SEC|')) {
                const p = line.split('|');
                sec = { code: p[1], title: p.slice(2).join('|'), items: [] };
                item = null;
                out.sections.push(sec);
            } else if (line.startsWith('#ITEM|')) {
                const p = line.split('|');
                if (!sec) { sec = { code: '', title: '', items: [] }; out.sections.push(sec); }
                item = { code: p[1], title: p[2] || '', source: p.slice(3).join('|'), lines: [] };
                sec.items.push(item);
            } else if (line.startsWith('#END|')) {
                out.end = line.substring(5);
            } else if (line.startsWith('#WARN|')) {
                ensureItem();
                const t = line.substring(6);
                item.lines.push({ k: 'warn', t: t });
                out.warns.push({ t: t, sec: sec.title, item: item.title });
            } else if (line.startsWith('#SUM|')) {
                ensureItem(); item.lines.push({ k: 'sum', t: line.substring(5) });
            } else if (line.startsWith('#HL|')) {
                ensureItem(); item.lines.push({ k: 'hl', t: line.substring(4) });
            } else if (line !== '') {
                ensureItem(); item.lines.push({ k: 'raw', t: line });
            }
        });
        return out;
    }

    function renderView() {
        const v = $('rpView');
        if (!selected) {
            $('rpViewTitle').textContent = '리포트 내용';
            $('rpViewMeta').textContent = '';
            v.innerHTML = '<div class="lg-empty">왼쪽 목록에서 리포트를 고르거나 [리포트 요청] 을 누르세요.</div>';
            return;
        }
        const r = report;
        $('rpViewTitle').textContent = '리포트 #' + selected;
        if (!r) return;
        const s = statusOf(r);
        $('rpViewMeta').innerHTML = '<span class="rp-badge" data-status="' + s[1] + '">' + escapeHtml(s[0]) + '</span> '
            + escapeHtml(secNames(r.sections)) + ' · 요청 ' + escapeHtml(r.reqDt || '-')
            + (r.doneDt ? ' · 수신 ' + escapeHtml(r.doneDt) : r.sentDt ? ' · 전달 ' + escapeHtml(r.sentDt) : '');
        if (r.status === 'REQUESTED' || r.status === 'SENT') {
            v.innerHTML = '<div class="lg-empty">' + (r.overdue
                ? '10분이 지나도 결과가 없습니다. 서버의 svr_collect_client 데몬 상태(에이전트 관리)와 버전(1.12 이상)을 확인한 뒤 다시 요청하세요.'
                : (r.status === 'REQUESTED' ? '에이전트가 요청을 가져가기를 기다리는 중입니다 (최대 10초)...' : '서버에서 점검 중입니다 (10~30초)...')) + '</div>';
            return;
        }
        const doc = parse(r.content);
        const q = ($('rpFilter').value || '').trim().toLowerCase();
        const hit = t => !q || t.toLowerCase().includes(q);
        let html = '';
        if (doc.head) {
            html += '<div class="rp-head"><b>' + escapeHtml(doc.head.host || '') + '</b> · ' + escapeHtml(doc.head.os || '')
                + ' · 점검 ' + escapeHtml(doc.head.time || '') + (doc.end ? ' · 소요 ' + escapeHtml(doc.end) + '초' : '') + '</div>';
        }
        // 경고 요약
        html += '<div class="rp-summary" data-status="' + (doc.warns.length ? 'warning' : 'success') + '">'
            + '<div class="rp-summary-title">' + (doc.warns.length ? '경고 ' + doc.warns.length + '건' : '경고 없음') + '</div>';
        if (doc.warns.length) {
            html += '<ul>' + doc.warns.map(w => '<li><span class="rp-sum-where">' + escapeHtml(w.item || w.sec) + '</span> '
                + escapeHtml(w.t) + '</li>').join('') + '</ul>';
        }
        html += '</div>';
        doc.sections.forEach(sec => {
            let body = '';
            sec.items.forEach(it => {
                const lines = it.lines.filter(l => (mode === 'all' || l.k === 'warn' || l.k === 'hl' || l.k === 'sum') && hit(l.t));
                if (!lines.length && (mode !== 'all' || q)) return;
                const scb = it.code === 'scb';
                body += '<div class="rp-item-box' + (scb ? ' rp-scb' : '') + '">'
                    + '<div class="rp-item-title">' + escapeHtml(it.title) + (it.source ? '<span class="rp-src">' + escapeHtml(it.source) + '</span>' : '') + '</div>';
                let raw = [];
                const flush = () => {
                    if (raw.length) body += '<pre class="rp-raw">' + raw.map(escapeHtml).join('\n') + '</pre>';
                    raw = [];
                };
                lines.forEach(l => {
                    if (l.k === 'raw') { raw.push(l.t); return; }
                    flush();
                    body += '<div class="rp-line rp-' + l.k + '">' + escapeHtml(l.t) + '</div>';
                });
                flush();
                body += '</div>';
            });
            if (!body) return;
            html += '<section class="rp-sec"><h4>' + escapeHtml(sec.title) + '</h4>' + body + '</section>';
        });
        if (!doc.sections.length) {
            html += '<pre class="rp-raw">' + escapeHtml(r.content || '(내용 없음)') + '</pre>';
        }
        v.innerHTML = html;
    }

    // ------------------------------------------------------------ 동작

    async function requestReport() {
        const sections = Array.from(document.querySelectorAll('input[name="rpSec"]:checked')).map(c => c.value);
        if (!sections.length) { scbAlert('점검 분야를 하나 이상 고르세요.', 'warning'); return; }
        const btn = $('rpRequest');
        btn.disabled = true;
        try {
            data = await apiRequest('POST', API + serverId, { sections: sections });
            const r = data.reports[0];
            selected = r ? r.id : null;
            report = null;
            renderStatus();
            renderList();
            await loadReport();
            schedule();
        } catch (e) {
            scbAlert(e.message);
        } finally {
            if (data) renderStatus(); else btn.disabled = false;
        }
    }

    async function deleteReport(id) {
        if (!(await scbConfirm('리포트 #' + id + ' 를 삭제하시겠습니까?'))) return;
        try {
            await apiRequest('DELETE', API + serverId + '/' + id);
            if (selected === id) { selected = null; report = null; }
            await load(true);
        } catch (e) {
            scbAlert(e.message);
        }
    }

    function download() {
        if (!report || !report.content) { scbAlert('내려받을 리포트 내용이 없습니다.', 'warning'); return; }
        const name = 'server_report_' + (data ? data.serverName : serverId) + '_' + report.id + '.txt';
        const blob = new Blob([report.content], { type: 'text/plain;charset=utf-8' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = name.replace(/[\\/:*?"<>| ]+/g, '_');
        document.body.appendChild(a);
        a.click();
        setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
    }

    function init() {
        const sel = $('rpServer');
        if (!sel || !sel.options.length) {
            $('rpStatus').textContent = '등록된 서버가 없습니다.';
            $('rpRequest').disabled = true;
            return;
        }
        const saved = store('rpServer');
        if (saved && Array.from(sel.options).some(o => o.value === saved)) sel.value = saved;
        serverId = sel.value;
        sel.addEventListener('change', () => {
            serverId = sel.value; store('rpServer', serverId);
            selected = null; report = null;
            load(false);
        });
        $('rpRequest').addEventListener('click', requestReport);
        $('rpReload').addEventListener('click', () => load(true));
        $('rpList').addEventListener('click', e => {
            const del = e.target.closest('[data-del]');
            if (del) { e.stopPropagation(); deleteReport(Number(del.dataset.del)); return; }
            const li = e.target.closest('.rp-item');
            if (!li) return;
            selected = Number(li.dataset.id);
            loadReport();
        });
        $('rpList').addEventListener('keydown', e => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            const li = e.target.closest('.rp-item');
            if (!li) return;
            e.preventDefault();
            selected = Number(li.dataset.id);
            loadReport();
        });
        document.querySelectorAll('.rp-page .ua-tab[data-mode]').forEach(b => b.addEventListener('click', () => {
            document.querySelectorAll('.rp-page .ua-tab[data-mode]').forEach(x => x.classList.toggle('active', x === b));
            mode = b.dataset.mode;
            renderView();
        }));
        $('rpFilter').addEventListener('input', renderView);
        $('rpDownload').addEventListener('click', download);
        $('rpPrint').addEventListener('click', () => window.print());
        load(false);
    }

    document.addEventListener('DOMContentLoaded', init);
})();
