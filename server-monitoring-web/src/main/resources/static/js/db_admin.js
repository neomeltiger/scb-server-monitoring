// =============================================================
//  DB 연결 관리 (ADMIN) : db_admin.html
//  API : GET /api/admin/db , POST /api/admin/db/{db}/test , PUT /api/admin/db/{db} ,
//        POST /api/admin/db/{db}/switch , PUT /api/admin/db/settings
//  apiRequest, escapeHtml 은 script.js
// =============================================================
(function () {
    const API = '../api/admin/db';
    const $ = id => document.getElementById(id);
    const esc = v => escapeHtml(v == null ? '' : String(v));
    let data = null;
    let editing = null;          // 연결 설정 중인 DB (상태 객체)
    let switching = null;        // 전환할 DB
    let busy = false;

    function status(msg, bad) {
        $('dbStatus').textContent = msg || '';
        $('dbStatus').classList.toggle('bad', !!bad);
    }

    function showError(msg) {
        $('dbError').textContent = msg || '';
        $('dbError').hidden = !msg;
    }

    const TYPE = { START: '시작', FAILOVER: '자동 교체', SWITCH: '관리자 전환', FAIL: '연결 끊김' };
    const label = key => {
        const d = data && data.databases.find(x => x.key === key);
        return d ? d.label : (key || '-');
    };

    async function load() {
        status('연결 확인 중... (연결되지 않는 DB 는 몇 초 걸릴 수 있습니다)');
        $('dbReload').disabled = true;
        try {
            data = await apiRequest('GET', API);
            showError('');
            render();
            status('');
        } catch (e) {
            showError(e.message);
            status('');
        } finally {
            $('dbReload').disabled = false;
        }
    }

    function render() {
        const c = data.current;
        $('dbCurrent').innerHTML =
            '<div class="db-cur-main"><span class="db-dot ok" aria-hidden="true"></span>지금 주 DB : <b>' + esc(c.label) + '</b>'
            + ' <span class="ua-sub">(' + esc(c.since.replace('T', ' ')) + ' 부터)</span></div>'
            + '<div class="ua-sub">' + esc(c.reason) + '</div>'
            + (c.health ? '<div class="ua-sub">최근 연결 확인 : ' + esc(c.health.replace('T', ' ')) + '</div>' : '');

        const curRank = (data.databases.find(d => d.current) || {}).rank || 99;
        $('dbList').innerHTML = data.databases.map(d => {
            const usable = d.current || (d.enabled && d.ok);
            const cls = d.current ? 'current' : (usable ? 'ok' : 'off');
            const higher = !d.current && usable && d.rank < curRank;
            const where = d.embedded ? '임베디드' + (d.url ? ' · ' + d.url : '') : (d.url || 'URL 없음');
            return '<div class="db-card ' + cls + '" data-key="' + esc(d.key) + '"' + (usable ? '' : ' aria-disabled="true"') + '>'
                + '<div class="db-rank" title="우선순위">' + d.rank + '</div>'
                + '<div class="db-body">'
                + '<div class="db-name"><span class="db-dot ' + (usable ? 'ok' : 'off') + '" aria-hidden="true"></span>' + esc(d.label)
                + (d.current ? ' <span class="badge badge-running">사용 중</span>' : '')
                + (higher ? ' <span class="badge badge-standby">위 순위 - 복귀 가능</span>' : '')
                + (!d.enabled ? ' <span class="badge db-badge-off">사용 안 함</span>' : '')
                + '</div>'
                + '<div class="db-url">' + esc(where) + (d.username ? ' · ' + esc(d.username) : '')
                + (d.hasPassword ? ' · 비밀번호 ' + (d.passwordInFile ? '저장됨' : '환경변수') : '') + '</div>'
                + '<div class="db-msg' + (usable ? '' : ' bad') + '">' + esc(d.message || '') + (d.ms ? ' (' + d.ms + 'ms)' : '') + '</div>'
                + '</div>'
                + '<div class="db-btns">'
                + '<button type="button" class="svc-btn" data-act="edit">연결 설정</button>'
                + '<button type="button" class="svc-btn start" data-act="switch"' + (usable && !d.current ? '' : ' disabled')
                + ' title="' + (d.current ? '지금 쓰는 DB' : usable ? '이 DB 를 주 DB 로' : '연결할 수 없어 선택 불가') + '">이 DB 로 전환</button>'
                + '</div></div>';
        }).join('');

        const s = data.settings;
        $('dbFailover').checked = !!s.failover;
        $('dbPrimary').value = s.primary || 'auto';
        $('dbFile').textContent = '설정 파일 : ' + s.file + ' · 연결 확인 주기 ' + s.healthIntervalSec + '초';

        const ev = data.events || [];
        $('dbEvents').innerHTML = ev.length ? ev.map(e =>
            '<tr><td data-m="key">' + esc(String(e.time).replace('T', ' ').substring(0, 19)) + '</td>'
            + '<td>' + esc(TYPE[e.type] || e.type) + '</td>'
            + '<td>' + esc(e.from ? label(e.from) + ' → ' : '') + esc(e.to ? label(e.to) : '-') + '</td>'
            + '<td>' + esc(e.message) + '</td><td>' + esc(e.user || '-') + '</td></tr>').join('')
            : '<tr><td colspan="5" class="svc-empty">기록 없음</td></tr>';
    }

    // ----------------------------------------------------------- 연결 설정
    function openEdit(d) {
        editing = d;
        $('connTitle').textContent = d.label + ' 연결 설정';
        $('cEnabled').checked = !!d.enabled;
        $('cEnabled').disabled = !!d.current;
        const maria = d.key === 'mariadb';
        $('cEmbeddedRow').hidden = !maria;
        $('cEmbedded').checked = maria && d.embedded;
        $('cUrl').value = d.embedded ? '' : (d.url || '');
        $('cUrl').placeholder = d.sampleUrl || (d.key === 'h2' ? '비우면 실행 폴더의 data/h2 파일 DB' : '');
        $('cUrlHint').textContent = d.key === 'h2' ? '비우면 내장 파일 DB' : (d.sampleUrl ? '예) ' + d.sampleUrl : '');
        $('cUser').value = d.username || '';
        $('cPw').value = '';
        $('cPwHint').textContent = d.hasPassword ? (d.passwordInFile ? '저장됨 - 바꿀 때만 입력' : '환경변수 값 사용 중 - 입력하면 파일에 저장') : '없음';
        $('cClearPw').checked = false;
        $('cClearPw').parentElement.hidden = !d.passwordInFile;
        $('connTest').textContent = '';
        $('connTest').className = 'db-test';
        $('connError').textContent = '';
        syncEmbedded();
        $('connModal').style.display = 'flex';
        $('cUrl').focus();
    }

    function syncEmbedded() {
        const emb = !$('cEmbeddedRow').hidden && $('cEmbedded').checked;
        ['cUrl', 'cUser', 'cPw'].forEach(id => { $(id).disabled = emb; });
    }

    function closeEdit() {
        $('connModal').style.display = 'none';
        editing = null;
    }

    function form() {
        const maria = editing.key === 'mariadb';
        return {
            enabled: $('cEnabled').checked,
            embedded: maria ? $('cEmbedded').checked : null,
            url: $('cUrl').value.trim(),
            username: $('cUser').value.trim(),
            password: $('cPw').value,
            clearPassword: $('cClearPw').checked
        };
    }

    async function testConn() {
        if (!editing || busy) return;
        busy = true;
        $('connTest').className = 'db-test';
        $('connTest').textContent = '연결 시험 중...';
        $('connError').textContent = '';
        try {
            const r = await apiRequest('POST', API + '/' + editing.key + '/test', form());
            $('connTest').textContent = (r.ok ? '✔ ' : '✖ ') + r.message + ' (' + r.ms + 'ms)';
            $('connTest').classList.add(r.ok ? 'ok' : 'bad');
        } catch (e) {
            $('connTest').textContent = '';
            $('connError').textContent = e.message;
        } finally {
            busy = false;
        }
    }

    async function saveConn() {
        if (!editing || busy) return;
        busy = true;
        $('connError').textContent = '';
        try {
            const r = await apiRequest('PUT', API + '/' + editing.key, form());
            closeEdit();
            status(r.message);
            await load();
            status(r.message);
        } catch (e) {
            $('connError').textContent = e.message;
        } finally {
            busy = false;
        }
    }

    // ----------------------------------------------------------- 전환
    function openSwitch(d) {
        switching = d;
        $('switchTitle').textContent = d.label + ' 로 전환';
        $('switchText').textContent = '지금 주 DB ' + data.current.label + ' 에서 ' + d.label + ' 로 바꿉니다. '
            + '화면과 수집은 바로 ' + d.label + ' 를 씁니다. 웹을 다시 시작하면 [웹을 시작할 때 쓸 DB] 설정을 따릅니다.';
        $('swCopy').checked = false;
        $('switchError').textContent = '';
        $('switchModal').style.display = 'flex';
        $('switchOk').focus();
    }

    function closeSwitch() {
        $('switchModal').style.display = 'none';
        switching = null;
    }

    async function doSwitch() {
        if (!switching || busy) return;
        busy = true;
        $('switchOk').disabled = true;
        $('switchError').textContent = '';
        try {
            const r = await apiRequest('POST', API + '/' + switching.key + '/switch', { copyData: $('swCopy').checked });
            closeSwitch();
            let msg = r.message;
            if (r.copy) msg += ' - 복사 ' + r.copy.tables + '개 테이블 ' + r.copy.rows + '건' + (r.copy.allOk ? '' : ' (건수 다름 : ' + (r.copyProblems || []).join(', ') + ')');
            await load();
            status(msg, r.copy && !r.copy.allOk);
        } catch (e) {
            $('switchError').textContent = e.message;
        } finally {
            busy = false;
            $('switchOk').disabled = false;
        }
    }

    async function saveSettings() {
        if (busy) return;
        busy = true;
        try {
            const r = await apiRequest('PUT', API + '/settings', { primary: $('dbPrimary').value, failover: $('dbFailover').checked });
            status(r.message);
        } catch (e) {
            status(e.message, true);
        } finally {
            busy = false;
        }
    }

    // ----------------------------------------------------------- 이벤트
    $('dbReload').addEventListener('click', load);
    $('dbSaveSettings').addEventListener('click', saveSettings);
    $('dbList').addEventListener('click', e => {
        const b = e.target.closest('button[data-act]');
        if (!b || b.disabled) return;
        const d = data.databases.find(x => x.key === b.closest('.db-card').dataset.key);
        if (!d) return;
        if (b.dataset.act === 'edit') openEdit(d);
        else openSwitch(d);
    });
    $('cEmbedded').addEventListener('change', syncEmbedded);
    $('connCancel').addEventListener('click', closeEdit);
    $('connTestBtn').addEventListener('click', testConn);
    $('connSave').addEventListener('click', saveConn);
    $('switchCancel').addEventListener('click', closeSwitch);
    $('switchOk').addEventListener('click', doSwitch);
    $('connModal').addEventListener('click', e => { if (e.target.id === 'connModal') closeEdit(); });
    $('switchModal').addEventListener('click', e => { if (e.target.id === 'switchModal') closeSwitch(); });
    document.addEventListener('keydown', e => {
        if (e.key !== 'Escape') return;
        if (editing) closeEdit();
        if (switching) closeSwitch();
    });

    load();
})();
