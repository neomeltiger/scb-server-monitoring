// ================= 환경 설정 (/admin/settings, 관리자 전용) =================
// 데이터 : ../api/admin/settings (로그 주기 · 선택 값 · 부하 기준 · application.yml 현재 값 · 설정 파일)
//          ../api/topology/servers (서버), ../api/agents (에이전트 토큰·주기), ../api/admin/db (DB 연결 요약)
// 바꾸기 : 기존 API 그대로 - 하트비트 PUT ../api/agents/{id}/heartbeat-interval, 수집 PUT ../api/agents/{id}/collect-interval,
//          서버 로그 PUT ../api/logs/{id}/policy
// apiRequest / escapeHtml 는 script.js, scbAlert 는 scb_dialog.js
(function () {
    const API = '../api/';
    const $ = id => document.getElementById(id);
    const SEC = s => (s % 60 === 0 && s >= 60 ? (s / 60) + '분' : s + '초');
    const MIN = m => (m === 0 ? '사용 안 함' : m + '분');
    let data = null, servers = [], agents = {}, db = null;

    async function load() {
        $('stStatus').textContent = '불러오는 중...';
        try {
            const r = await Promise.all([
                apiRequest('GET', API + 'admin/settings'),
                apiRequest('GET', API + 'topology/servers'),
                apiRequest('GET', API + 'agents'),
                apiRequest('GET', API + 'admin/db').catch(() => null)
            ]);
            data = r[0];
            servers = r[1] || [];
            agents = {};
            (r[2] || []).forEach(v => { agents[String(v.serverId)] = v.agent || {}; });
            db = r[3];
        } catch (e) {
            $('stStatus').innerHTML = '<span class="lg-bad">' + escapeHtml(e.message) + '</span>';
            return;
        }
        render();
        const rt = data.runtime || {};
        $('stStatus').innerHTML = '서버 <b>' + servers.length + '</b>대 · 프로필 <b>' + escapeHtml((rt.profiles || []).join(', ') || 'default') +
            '</b> · 웹 시작 ' + escapeHtml(rt.startedAt || '-') + ' · 확인 ' + new Date().toTimeString().slice(0, 8);
    }

    function opt(list, cur, label) {
        return list.map(v => '<option value="' + v + '"' + (v === cur ? ' selected' : '') + '>' + label(v) + '</option>').join('');
    }

    function render() {
        // ---- 1. 서버별
        const o = data.options || {};
        $('stServerBody').innerHTML = servers.map(s => {
            const a = agents[String(s.id)] || {};
            const none = !a.state || a.state === 'NONE';
            const token = none
                ? '<span class="am-chip gray">미발급</span>'
                : '<span class="am-chip green">발급됨</span><span class="ua-sub">' + escapeHtml(a.issueDt || '') + (a.issueUser ? ' · ' + escapeHtml(a.issueUser) : '') +
                  (a.agentVer ? ' · client ' + escapeHtml(a.agentVer) : '') + '</span>';
            const dis = none ? ' disabled title="토큰을 먼저 발급하세요"' : '';
            const hb = '<select class="lg-select st-sel" data-kind="hb" data-id="' + s.id + '"' + dis + ' aria-label="' + escapeHtml(s.systemName) + ' 하트비트 주기">' +
                opt(o.heartbeat || [], a.hbIntervalSec, SEC) + '</select>';
            const throttle = a.loadThrottled
                ? '<span class="ua-sub ' + (a.effectiveIntervalSec !== a.collectIntervalSec ? 'am-warn' : '') + '">부하(' + escapeHtml(a.loadReason || '') + ') → ' +
                  SEC(a.effectiveIntervalSec) + ' 적용 중</span>' : '';
            const col = '<select class="lg-select st-sel" data-kind="collect" data-id="' + s.id + '"' + dis + ' aria-label="' + escapeHtml(s.systemName) + ' 수집 주기">' +
                opt(o.collect || [], a.collectIntervalSec, SEC) + '</select>' + throttle;
            const lmin = (data.logIntervalMin || {})[String(s.id)];
            const lg = '<select class="lg-select st-sel" data-kind="log" data-id="' + s.id + '" aria-label="' + escapeHtml(s.systemName) + ' 서버 로그 요청 주기">' +
                opt(o.log || [], lmin, MIN) + '</select>';
            const links = '<a class="svc-btn start" href="../network?server=' + s.id + '">' + (none ? '토큰 발급' : '구성도') + '</a>' +
                '<a class="svc-btn" href="../agents">에이전트</a><a class="svc-btn" href="../logs">로그</a>';
            return '<tr data-id="' + s.id + '"><td class="ua-left"><b>' + escapeHtml(s.systemName) + '</b><span class="ua-sub">' + escapeHtml(s.ip) +
                ' · ' + (s.location === 'DRC' ? 'DRC' : 'ITC') + ' · ' + escapeHtml(s.status) + '</span></td>' +
                '<td>' + token + '</td><td>' + hb + '</td><td>' + col + '</td><td>' + lg + '</td><td class="am-ctl">' + links + '</td></tr>';
        }).join('') || '<tr><td colspan="6">등록된 서버가 없습니다.</td></tr>';
        if (typeof mobileCards === 'function') mobileCards(document.getElementById('stServers'));

        // ---- 2. 부하
        const L = data.load || {};
        const now = servers.filter(s => (agents[String(s.id)] || {}).loadThrottled);
        $('stLoad').innerHTML = '<p>서버의 <b>CPU 또는 메모리 사용률이 ' + L.pct + '%를 넘으면</b> 수집 주기를 <b>최소 ' + SEC(L.minSec) +
            ' 이상</b>으로 늘려 서버 부담을 줄입니다. 사용자가 정한 주기가 이미 그보다 길면 그대로 두고, 부하가 내려가면 설정한 주기로 자동으로 돌아갑니다. ' +
            '(설정 값은 바뀌지 않음 · 데몬이 10초마다 받는 ctl|interval 로 적용)</p>' +
            '<p class="svc-hint">기준 바꾸기 : conf/scb_web.env 의 SCB_LOAD_THROTTLE_PCT (%) · SCB_LOAD_THROTTLE_MIN_SEC (초) → 웹 다시 시작</p>' +
            '<p>지금 부하로 늘어난 서버 : ' + (now.length ? now.map(s => {
                const a = agents[String(s.id)];
                return '<span class="am-chip yellow">' + escapeHtml(s.systemName) + ' · ' + escapeHtml(a.loadReason || '') + ' · ' + SEC(a.collectIntervalSec) + ' → ' + SEC(a.effectiveIntervalSec) + '</span>';
            }).join(' ') : '<b>없음</b>') + '</p>';

        // ---- 3. DB
        if (db && db.current) {
            const st = db.settings || {};
            $('stDb').innerHTML = kv([
                ['지금 주 DB', '<b>' + escapeHtml(db.current.label) + '</b> (' + escapeHtml(db.current.reason || '') + ', ' + escapeHtml(db.current.since || '') + ' 부터)'],
                ['우선순위', escapeHtml((st.order || []).join(' → '))],
                ['주 DB 설정', escapeHtml(st.primary || 'auto')],
                ['끊기면 자동 교체', st.failover ? '사용' : '사용 안 함'],
                ['연결 확인 주기', escapeHtml(String(st.healthIntervalSec || '-')) + '초'],
                ['설정 파일', '<code>' + escapeHtml(st.file || '') + '</code>']
            ]);
        } else {
            $('stDb').innerHTML = '<span class="lg-bad">DB 연결 정보를 불러오지 못했습니다.</span> <a href="../admin/db">DB 연결 관리</a> 화면에서 확인하세요.';
        }

        // ---- 4. 애플리케이션 설정
        const rt = data.runtime || {};
        $('stRuntime').innerHTML = kv([
            ['실행 폴더', '<code>' + escapeHtml(rt.workDir || '') + '</code>'],
            ['Java', escapeHtml(rt.java || '')],
            ['웹 시작', escapeHtml(rt.startedAt || '')]
        ]);
        let lastGroup = null;
        $('stAppBody').innerHTML = (data.app || []).map(r => {
            const g = r.group !== lastGroup ? r.group : '';
            lastGroup = r.group;
            return '<tr' + (g ? ' class="st-group-first"' : '') + '><td>' + escapeHtml(g) + '</td><td class="ua-left"><code>' + escapeHtml(r.key) + '</code></td>' +
                '<td class="ua-left"><b>' + escapeHtml(r.value) + '</b></td><td>' + (r.env ? '<code>' + escapeHtml(r.env) + '</code>' : '-') + '</td>' +
                '<td class="ua-left">' + escapeHtml(r.desc || '') + '</td></tr>';
        }).join('');

        // ---- 5. 설정 파일
        lastGroup = null;
        $('stFileBody').innerHTML = (data.files || []).map(f => {
            const g = f.where !== lastGroup ? f.where : '';
            lastGroup = f.where;
            const here = f.exists === undefined ? '-' : (f.exists ? '<span class="am-chip green">있음</span>' : '<span class="am-chip gray">없음</span>') +
                (f.path ? '<span class="ua-sub">' + escapeHtml(f.path) + '</span>' : '');
            return '<tr' + (g ? ' class="st-group-first"' : '') + '><td>' + escapeHtml(g) + '</td><td class="ua-left"><code>' + escapeHtml(f.name) + '</code></td>' +
                '<td class="ua-left">' + escapeHtml(f.desc) + '</td><td class="ua-left">' + escapeHtml(f.how) + '</td><td>' + here + '</td></tr>';
        }).join('');
    }

    function kv(rows) {
        return '<dl class="st-dl">' + rows.map(r => '<dt>' + escapeHtml(r[0]) + '</dt><dd>' + r[1] + '</dd>').join('') + '</dl>';
    }

    async function change(sel) {
        const id = sel.dataset.id, kind = sel.dataset.kind, v = Number(sel.value);
        const s = servers.find(x => String(x.id) === id);
        sel.disabled = true;
        try {
            if (kind === 'hb') agents[id] = await apiRequest('PUT', API + 'agents/' + id + '/heartbeat-interval', { sec: v });
            else if (kind === 'collect') agents[id] = await apiRequest('PUT', API + 'agents/' + id + '/collect-interval', { sec: v });
            else { await apiRequest('PUT', API + 'logs/' + id + '/policy', { intervalMin: v }); data.logIntervalMin[id] = v; }
            const what = { hb: '하트비트 주기', collect: '수집 주기', log: '서버 로그 요청 주기' }[kind];
            scbAlert('[' + (s ? s.systemName : id) + '] ' + what + '를 바꿨습니다 → ' + (kind === 'log' ? MIN(v) : SEC(v)), 'success');
            render();
        } catch (e) {
            scbAlert(e.message, 'error');
            render();
        }
    }

    // ---- 5. 로그 · 이력 보관 기간 (../api/admin/retention) ----------------------------------
    const DAY_LABEL = { 7: '1주일', 30: '1개월', 180: '6개월', 365: '1년' };
    const dayText = d => DAY_LABEL[d] || d + '일 (사용자 정의)';
    let ret = null;

    async function loadRet() {
        try {
            ret = await apiRequest('GET', API + 'admin/retention');
        } catch (e) {
            $('stRetStatus').innerHTML = '<span class="lg-bad">' + escapeHtml(e.message) + '</span>';
            return;
        }
        renderRet();
    }

    function retSelect(cls, cur, label) {
        const presets = ret.presets || [7, 30, 180, 365];
        const custom = cur !== null && presets.indexOf(cur) < 0;
        return '<select class="lg-select ' + cls + '" aria-label="' + escapeHtml(label) + '">' +
            presets.map(d => '<option value="' + d + '"' + (d === cur ? ' selected' : '') + '>' + DAY_LABEL[d] + ' (' + d + '일)</option>').join('') +
            '<option value="custom"' + (custom ? ' selected' : '') + '>사용자 정의</option></select>';
    }

    function renderRet() {
        if (!ret) return;
        const lp = ret.lastPurge;
        $('stRetStatus').innerHTML = '보관 대상 전체 <b>' + Number(ret.totalRows || 0).toLocaleString() + '</b>행 · 자동 정리 ' + ret.purgeIntervalMin + '분마다' +
            (lp ? ' · 마지막 정리 ' + escapeHtml(lp.at) + ' (' + escapeHtml(lp.by) + ', ' + Number(lp.total).toLocaleString() + '행 삭제)' : ' · 아직 정리 안 함 (웹 시작 2분 뒤 첫 정리)');
        $('stRetBody').innerHTML = (ret.categories || []).map(c => {
            const tables = c.tables.map(t => '<div class="st-ret-t"><code>' + escapeHtml(t.table) + '</code> ' + Number(t.rows).toLocaleString() + '행' +
                (t.oldest ? ' · ' + escapeHtml(t.oldest) + ' 부터' : '') + '</div>').join('');
            return '<tr data-code="' + escapeHtml(c.code) + '"><td class="ua-left"><b>' + escapeHtml(c.name) + '</b><span class="ua-sub">' + escapeHtml(c.desc) + '</span></td>' +
                '<td class="ua-left">' + tables + '</td>' +
                '<td>' + retSelect('st-ret-sel', c.keepDays, c.name + ' 보관 기간') +
                '<input type="number" class="st-days" min="' + ret.minDays + '" max="' + ret.maxDays + '" value="' + c.keepDays + '"' + (c.custom ? '' : ' hidden') +
                ' aria-label="' + escapeHtml(c.name) + ' 사용자 정의 일수"> <span class="st-days-unit"' + (c.custom ? '' : ' hidden') + '>일</span>' +
                '<span class="ua-sub">지금 ' + escapeHtml(dayText(c.keepDays)) + (c.updUser ? ' · ' + escapeHtml(c.updUser) + ' ' + escapeHtml(c.updDt || '') : ' · 기본값') + '</span></td>' +
                '<td class="am-ctl"><button type="button" class="svc-btn start st-ret-save">저장</button></td></tr>';
        }).join('');
        $('stRetAll').innerHTML = '<option value="">선택...</option>' + (ret.presets || []).map(d => '<option value="' + d + '">' + DAY_LABEL[d] + ' (' + d + '일)</option>').join('') +
            '<option value="custom">사용자 정의</option>';
        if (typeof mobileCards === 'function') mobileCards($('stRet'));
    }

    function readDays(sel, input) {
        const v = sel.value === 'custom' ? Number(input.value) : Number(sel.value);
        if (!Number.isInteger(v) || v < ret.minDays || v > ret.maxDays) {
            scbAlert('보관 기간은 ' + ret.minDays + ' ~ ' + ret.maxDays + '일 사이 정수로 입력하세요.', 'warning');
            return null;
        }
        return v;
    }

    async function saveRet(tr) {
        const c = (ret.categories || []).find(x => x.code === tr.dataset.code);
        const days = readDays(tr.querySelector('.st-ret-sel'), tr.querySelector('.st-days'));
        if (days === null) return;
        if (days < c.keepDays && !(await scbConfirm('[' + c.name + '] 보관 기간을 ' + dayText(c.keepDays) + ' → ' + dayText(days) +
            '로 줄입니다.\n다음 정리 때 ' + days + '일보다 오래된 기록이 삭제되며 되돌릴 수 없습니다. 계속할까요?', { type: 'warning' }))) return;
        try {
            ret = await apiRequest('PUT', API + 'admin/retention/' + encodeURIComponent(c.code), { days: days });
            renderRet();
            scbAlert('[' + c.name + '] 최장 보관 기간을 저장했습니다 → ' + dayText(days), 'success');
        } catch (e) {
            scbAlert(e.message, 'error');
        }
    }

    async function saveRetAll() {
        if (!$('stRetAll').value) { scbAlert('적용할 보관 기간을 고르세요.', 'warning'); return; }
        const days = readDays($('stRetAll'), $('stRetAllDays'));
        if (days === null) return;
        if (!(await scbConfirm('모든 분류의 최장 보관 기간을 ' + dayText(days) + '(으)로 바꿉니다.\n줄어드는 분류는 다음 정리 때 오래된 기록이 삭제됩니다. 계속할까요?', { type: 'warning' }))) return;
        try {
            ret = await apiRequest('PUT', API + 'admin/retention', { days: days });
            renderRet();
            scbAlert('모든 분류의 최장 보관 기간을 저장했습니다 → ' + dayText(days), 'success');
        } catch (e) {
            scbAlert(e.message, 'error');
        }
    }

    async function purgeRet() {
        if (!(await scbConfirm('보관 기간이 지난 로그 · 이력을 지금 삭제합니다. 삭제한 기록은 되돌릴 수 없습니다. 계속할까요?', { type: 'warning' }))) return;
        const btn = $('stRetPurge');
        btn.disabled = true;
        try {
            ret = await apiRequest('POST', API + 'admin/retention/purge');
            renderRet();
            const p = ret.purged || {};
            const parts = Object.keys(p.deleted || {}).filter(k => p.deleted[k] > 0).map(k => k + ' ' + p.deleted[k].toLocaleString() + '행');
            scbAlert('정리했습니다 : ' + Number(p.total || 0).toLocaleString() + '행 삭제' + (parts.length ? '\n' + parts.join(', ') : ' (지난 기록 없음)'), 'success');
        } catch (e) {
            scbAlert(e.message, 'error');
        } finally {
            btn.disabled = false;
        }
    }

    // ---- 요약본 (지금 값으로 텍스트 만들어 내려받기)
    function summary() {
        if (!data) return;
        const o = [], line = '-'.repeat(72);
        const rt = data.runtime || {};
        o.push('통합 모니터링 시스템 환경 설정 요약  (' + new Date().toLocaleString('ko-KR') + ')', '='.repeat(72), '');
        o.push('[실행] 폴더 ' + (rt.workDir || '') + ' · Java ' + (rt.java || '') + ' · 프로필 ' + ((rt.profiles || []).join(',') || 'default') + ' · 시작 ' + (rt.startedAt || ''), '');
        o.push('1. 서버별 에이전트 · 수집 설정', line);
        servers.forEach(s => {
            const a = agents[String(s.id)] || {};
            const none = !a.state || a.state === 'NONE';
            o.push('  ' + s.systemName + ' (' + s.ip + ', ' + (s.location === 'DRC' ? 'DRC' : 'ITC') + ')' +
                ' | 토큰 ' + (none ? '미발급' : '발급 ' + (a.issueDt || '')) +
                ' | 하트비트 ' + (none ? '-' : SEC(a.hbIntervalSec)) +
                ' | 수집 ' + (none ? '-' : SEC(a.collectIntervalSec) + (a.loadThrottled ? ' (부하 ' + a.loadReason + ' → ' + SEC(a.effectiveIntervalSec) + ')' : '')) +
                ' | 로그 요청 ' + MIN((data.logIntervalMin || {})[String(s.id)]));
        });
        const L = data.load || {};
        o.push('', '2. 부하 시 수집 주기 연장', line, '  CPU 또는 메모리 > ' + L.pct + '% → 수집 주기 최소 ' + SEC(L.minSec) + ' (SCB_LOAD_THROTTLE_PCT / SCB_LOAD_THROTTLE_MIN_SEC)');
        o.push('', '3. DB 연결', line);
        if (db && db.current) {
            const st = db.settings || {};
            o.push('  주 DB ' + db.current.label + ' (' + (db.current.reason || '') + ') · 우선순위 ' + (st.order || []).join(' → ') +
                ' · 자동 교체 ' + (st.failover ? '사용' : '안 함') + ' · 설정 파일 ' + (st.file || ''));
        }
        o.push('', '4. 애플리케이션 설정 (현재 값)', line);
        let g = null;
        (data.app || []).forEach(r => {
            if (r.group !== g) { o.push('  [' + r.group + ']'); g = r.group; }
            o.push('    ' + r.key + ' = ' + r.value + (r.env ? '   (' + r.env + ')' : '') + '   - ' + r.desc);
        });
        o.push('', '5. 로그 · 이력 최장 보관 기간', line);
        if (ret) {
            (ret.categories || []).forEach(c => o.push('  ' + c.name + ' : ' + dayText(c.keepDays) + ' (' + c.tables.map(t => t.table + ' ' + t.rows + '행').join(', ') + ')'));
            o.push('  자동 정리 ' + ret.purgeIntervalMin + '분마다' + (ret.lastPurge ? ' · 마지막 ' + ret.lastPurge.at + ' ' + ret.lastPurge.total + '행 삭제' : ''));
        }
        o.push('', '6. 설정 파일', line);
        g = null;
        (data.files || []).forEach(f => {
            if (f.where !== g) { o.push('  [' + f.where + ']'); g = f.where; }
            o.push('    ' + f.name + ' : ' + f.desc + ' / ' + f.how + (f.exists === undefined ? '' : ' / ' + (f.exists ? '있음' : '없음')));
        });
        const blob = new Blob(['﻿' + o.join('\r\n') + '\r\n'], { type: 'text/plain;charset=utf-8' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'scb_settings_summary_' + new Date().toISOString().slice(0, 10) + '.txt';
        document.body.appendChild(a);
        a.click();
        setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
    }

    document.addEventListener('DOMContentLoaded', () => {
        $('stReload').addEventListener('click', load);
        $('stSummary').addEventListener('click', summary);
        $('stServerBody').addEventListener('change', e => {
            const sel = e.target.closest('.st-sel');
            if (sel) change(sel);
        });
        // 보관 기간 : 사용자 정의를 고르면 일수 입력 칸 표시
        $('stRetBody').addEventListener('change', e => {
            const sel = e.target.closest('.st-ret-sel');
            if (!sel) return;
            const tr = sel.closest('tr');
            const custom = sel.value === 'custom';
            tr.querySelector('.st-days').hidden = !custom;
            tr.querySelector('.st-days-unit').hidden = !custom;
            if (custom) tr.querySelector('.st-days').focus();
        });
        $('stRetBody').addEventListener('click', e => {
            const b = e.target.closest('.st-ret-save');
            if (b) saveRet(b.closest('tr'));
        });
        $('stRetAll').addEventListener('change', () => {
            $('stRetAllDays').hidden = $('stRetAll').value !== 'custom';
            if ($('stRetAll').value === 'custom') $('stRetAllDays').focus();
        });
        $('stRetAllBtn').addEventListener('click', saveRetAll);
        $('stRetPurge').addEventListener('click', purgeRet);
        $('stReload').addEventListener('click', loadRet);
        load();
        loadRet();
    });
})();
