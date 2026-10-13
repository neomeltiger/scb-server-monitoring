// ================= 사용자 관리 (가입 승인) - ADMIN 전용 =================
// API : /api/admin/users (auth.controller.UserAdminController). apiRequest / escapeHtml 은 script.js
(function () {
    // 화면이 /admin/users 이므로 한 단계 위 (컨텍스트 경로가 있어도 동작)
    const API = '../api/admin/users';
    let status = 'PENDING';
    let users = [];
    let decision = null; // { type: 'approve' | 'reject', user }

    const $ = id => document.getElementById(id);
    const esc = v => v === null || v === undefined || v === '' ? '-' : escapeHtml(v);

    async function load() {
        $('userError').hidden = true;
        try {
            const data = await apiRequest('GET', API + '?status=' + encodeURIComponent(status));
            users = data.users || [];
            $('pendingCount').textContent = data.pendingCount;
            const nav = document.getElementById('navPendingCount');
            if (nav) { nav.textContent = data.pendingCount; nav.hidden = !data.pendingCount; }
            render();
        } catch (e) {
            $('userError').textContent = e.message;
            $('userError').hidden = false;
            $('userTableBody').innerHTML = '<tr><td colspan="10" class="svc-empty">조회 실패</td></tr>';
        }
    }

    function render() {
        const q = $('userSearch').value.trim().toLowerCase();
        const list = users.filter(u => !q ||
            [u.userId, u.userNm, u.deptNm, u.email].some(v => v && v.toLowerCase().indexOf(q) !== -1));
        if (!list.length) {
            $('userTableBody').innerHTML = '<tr><td colspan="10" class="svc-empty">' +
                (status === 'PENDING' ? '승인 대기 중인 가입 신청이 없습니다.' : '사용자가 없습니다.') + '</td></tr>';
            return;
        }
        $('userTableBody').innerHTML = list.map(rowHtml).join('');
    }

    function rowHtml(u) {
        const id = escapeHtml(u.userId);
        let state = '<span class="ua-state ' + u.apprStatus + '">' + escapeHtml(u.apprLabel) + '</span>';
        if (u.apprStatus !== 'PENDING' && u.apprUser) {
            state += '<span class="ua-sub">' + esc(u.apprUser) + ' · ' + esc(u.apprDt) + '</span>';
        }
        if (u.apprStatus === 'REJECTED' && u.rejectReason) state += '<span class="ua-sub">사유: ' + esc(u.rejectReason) + '</span>';

        let acct;
        if (u.lockYn === 'Y') acct = '<span class="ua-acct lock">잠김</span><span class="ua-sub">실패 ' + u.failCnt + '회</span>';
        else if (u.useYn !== 'Y') acct = '<span class="ua-acct off">사용 중지</span>';
        else acct = '사용';
        if (u.pwChangeRequired) acct += '<span class="ua-sub ua-temp" title="첫 로그인 때 비밀번호 변경 필요">임시 비밀번호</span>';

        const btn = (cls, act, label) => '<button type="button" class="svc-btn ' + cls + '" data-act="' + act + '" data-id="' + id + '">' + label + '</button>';
        const acts = [];
        if (u.apprStatus === 'PENDING') {
            acts.push(btn('approve', 'approve', '승인'), btn('reject', 'reject', '반려'));
        } else if (u.apprStatus === 'APPROVED') {
            if (u.lockYn === 'Y') acts.push(btn('start', 'unlock', '잠금 해제'));
            if (u.roleCd !== 'ADMIN') {
                acts.push('<a class="svc-btn start" href="menu-auth?user=' + encodeURIComponent(u.userId) + '" title="이 사용자가 볼 메뉴 지정">메뉴 권한' +
                    (u.menuCustom ? ' (개별)' : '') + '</a>');
            }
            if (!u.me) {
                acts.push(u.useYn === 'Y' ? btn('stop', 'disable', '사용 중지') : btn('start', 'enable', '사용'));
                acts.push(u.roleCd === 'ADMIN' ? btn('status', 'toUser', '일반 사용자로') : btn('restart', 'toAdmin', '관리자로'));
                acts.push(btn('status', 'tempPw', '임시 비밀번호'));
            }
        }
        if (!u.me) acts.push(btn('del', 'delete', '삭제'));

        return '<tr>' +
            '<td class="ua-left"><b>' + id + '</b>' + (u.me ? '<span class="ua-me">나</span>' : '') + '</td>' +
            '<td>' + esc(u.userNm) + '</td>' +
            '<td class="ua-left">' + esc(u.deptNm) + '<span class="ua-sub">' + esc(u.email) + '</span></td>' +
            '<td class="ua-reason">' + esc(u.reqReason) + '</td>' +
            '<td class="svc-time">' + esc(u.regDt) + '</td>' +
            '<td>' + state + '</td>' +
            '<td>' + escapeHtml(u.roleLabel) + '</td>' +
            '<td>' + acct + '</td>' +
            '<td class="svc-time">' + esc(u.lastLoginDt) + (u.lastLoginIp ? '<span class="ua-sub">' + escapeHtml(u.lastLoginIp) + '</span>' : '') + '</td>' +
            '<td class="ua-actions">' + (acts.join('') || '-') + '</td></tr>';
    }

    async function act(action, userId) {
        const u = users.find(x => x.userId === userId);
        if (!u) return;
        if (action === 'approve' || action === 'reject') return openDecision(action, u);
        if (action === 'tempPw') return issueTempPassword(u);
        const confirmMsg = {
            unlock: '[' + userId + '] 계정 잠금을 해제하시겠습니까?',
            disable: '[' + userId + '] 계정을 사용 중지하시겠습니까?\n(사용 중지된 계정은 로그인할 수 없습니다)',
            enable: '[' + userId + '] 계정을 다시 사용하도록 하시겠습니까?',
            toAdmin: '[' + userId + '] 에게 관리자(수정/삭제/제어) 권한을 주시겠습니까?',
            toUser: '[' + userId + '] 의 권한을 일반 사용자(조회 전용)로 내리시겠습니까?',
            delete: '[' + userId + '] 계정을 삭제하시겠습니까?\n삭제하면 되돌릴 수 없습니다.'
        }[action];
        if (!confirm(confirmMsg)) return;
        const url = API + '/' + encodeURIComponent(userId);
        try {
            let r;
            if (action === 'unlock') r = await apiRequest('POST', url + '/unlock');
            else if (action === 'disable' || action === 'enable') r = await apiRequest('PUT', url + '/use', { use: action === 'enable' });
            else if (action === 'toAdmin' || action === 'toUser') r = await apiRequest('PUT', url + '/role', { roleCd: action === 'toAdmin' ? 'ADMIN' : 'USER' });
            else if (action === 'delete') r = await apiRequest('DELETE', url);
            alert(r.message);
        } catch (e) {
            alert(e.message);
        }
        load();
    }

    function openDecision(type, u) {
        decision = { type: type, user: u };
        $('decisionTitle').textContent = type === 'approve' ? '가입 승인' : '가입 반려';
        $('decisionTarget').innerHTML = '<b>' + escapeHtml(u.userId) + '</b> (' + esc(u.userNm) + ', ' + esc(u.deptNm) + ')<br>신청 사유: ' + esc(u.reqReason);
        $('approveBox').hidden = type !== 'approve';
        $('rejectBox').hidden = type !== 'reject';
        $('approveRole').value = 'USER';
        $('rejectReason').value = '';
        $('decisionError').textContent = '';
        const ok = $('decisionOk');
        ok.textContent = type === 'approve' ? '승인' : '반려';
        ok.className = 'btn ' + (type === 'approve' ? 'btn-primary' : 'btn-danger');
        $('decisionModal').style.display = 'flex';
        (type === 'approve' ? $('approveRole') : $('rejectReason')).focus();
    }

    function closeDecision() {
        $('decisionModal').style.display = 'none';
        decision = null;
    }

    async function submitDecision() {
        if (!decision) return;
        const url = API + '/' + encodeURIComponent(decision.user.userId) + '/' + decision.type;
        let body;
        if (decision.type === 'approve') {
            body = { roleCd: $('approveRole').value };
            if (body.roleCd === 'ADMIN' && !confirm('관리자 권한으로 승인하면 수정/삭제/제어가 모두 가능합니다. 계속하시겠습니까?')) return;
        } else {
            body = { reason: $('rejectReason').value.trim() };
            if (!body.reason) { $('decisionError').textContent = '반려 사유를 입력하세요.'; return; }
        }
        $('decisionOk').disabled = true;
        try {
            const r = await apiRequest('POST', url, body);
            closeDecision();
            alert(r.message);
            load();
        } catch (e) {
            $('decisionError').textContent = e.message;
        } finally {
            $('decisionOk').disabled = false;
        }
    }

    // ---------------------------------------------------------------- 계정 생성 / 임시 비밀번호
    let createOpen = false;

    function openCreate() {
        createOpen = true;
        ['cUserId', 'cUserNm', 'cDeptNm', 'cEmail'].forEach(id => { $(id).value = ''; });
        $('cRole').value = 'USER';
        $('createTitle').textContent = '계정 생성';
        $('createForm').hidden = false;
        $('createResult').hidden = true;
        $('createError').textContent = '';
        $('createOk').hidden = false;
        $('createCancel').textContent = '취소';
        $('goMenuAuth').hidden = true;
        $('createModal').style.display = 'flex';
        $('cUserId').focus();
    }

    function showTemp(title, msg, userId, tempPw, roleCd) {
        createOpen = true;
        $('createTitle').textContent = title;
        $('createForm').hidden = true;
        $('createResult').hidden = false;
        $('createDone').innerHTML = msg;
        $('tempPw').value = tempPw;
        $('createError').textContent = '';
        $('createOk').hidden = true;
        $('createCancel').textContent = '닫기';
        const go = $('goMenuAuth');
        go.hidden = roleCd === 'ADMIN';
        go.href = 'menu-auth?user=' + encodeURIComponent(userId);
        $('createModal').style.display = 'flex';
        $('tempPw').select();
    }

    function closeCreate() {
        $('createModal').style.display = 'none';
        $('tempPw').value = '';
        createOpen = false;
    }

    async function submitCreate() {
        const body = {
            userId: $('cUserId').value.trim(), userNm: $('cUserNm').value.trim(),
            deptNm: $('cDeptNm').value.trim(), email: $('cEmail').value.trim(), roleCd: $('cRole').value
        };
        if (!/^[A-Za-z][A-Za-z0-9._-]{3,29}$/.test(body.userId)) { $('createError').textContent = '아이디는 영문으로 시작하는 4~30자 (영문·숫자·. _ -)로 입력하세요.'; $('cUserId').focus(); return; }
        if (!body.userNm) { $('createError').textContent = '이름을 입력하세요.'; $('cUserNm').focus(); return; }
        if (body.roleCd === 'ADMIN' && !confirm('관리자 계정은 모든 메뉴와 수정/삭제/제어가 가능합니다. 계속하시겠습니까?')) return;
        $('createOk').disabled = true;
        try {
            const r = await apiRequest('POST', API, body);
            showTemp('계정을 만들었습니다', '<b>' + escapeHtml(r.userId) + '</b> (' + escapeHtml(body.userNm) + ') · ' +
                (r.roleCd === 'ADMIN' ? '관리자' : '일반 사용자') + (r.roleCd === 'ADMIN' ? '' : ' - 이어서 <b>[메뉴 권한 부여]</b> 로 볼 메뉴를 정하세요.'),
                r.userId, r.tempPassword, r.roleCd);
            load();
        } catch (e) {
            $('createError').textContent = e.message;
        } finally {
            $('createOk').disabled = false;
        }
    }

    async function issueTempPassword(u) {
        if (!confirm('[' + u.userId + '] 에게 새 임시 비밀번호를 발급하시겠습니까?\n지금 비밀번호는 쓸 수 없게 되고, 잠금도 풀리며, 첫 로그인 때 비밀번호를 바꿔야 합니다.')) return;
        try {
            const r = await apiRequest('POST', API + '/' + encodeURIComponent(u.userId) + '/temp-password');
            showTemp('임시 비밀번호 발급', '<b>' + escapeHtml(u.userId) + '</b> (' + esc(u.userNm) + ') 의 새 임시 비밀번호입니다.', u.userId, r.tempPassword, u.roleCd);
            load();
        } catch (e) { alert(e.message); }
    }

    async function copyTemp() {
        const v = $('tempPw').value;
        try { await navigator.clipboard.writeText(v); $('copyPw').textContent = '복사됨'; }
        catch (e) { $('tempPw').select(); document.execCommand('copy'); $('copyPw').textContent = '복사됨'; }
        setTimeout(() => { $('copyPw').textContent = '복사'; }, 1500);
    }

    window.addEventListener('DOMContentLoaded', function () {
        $('createBtn').addEventListener('click', openCreate);
        $('createCancel').addEventListener('click', closeCreate);
        $('createOk').addEventListener('click', submitCreate);
        $('copyPw').addEventListener('click', copyTemp);
        $('createModal').addEventListener('click', e => { if (e.target.id === 'createModal' && $('createResult').hidden) closeCreate(); });
        $('createForm').addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.tagName === 'INPUT') submitCreate(); });
        document.addEventListener('keydown', e => { if (e.key === 'Escape' && createOpen) closeCreate(); });
        document.querySelectorAll('#statusTabs .ua-tab').forEach(b => b.addEventListener('click', function () {
            status = b.getAttribute('data-status');
            document.querySelectorAll('#statusTabs .ua-tab').forEach(x => x.classList.toggle('active', x === b));
            load();
        }));
        $('userSearch').addEventListener('input', render);
        $('reloadBtn').addEventListener('click', load);
        $('userTableBody').addEventListener('click', function (e) {
            const b = e.target.closest('button[data-act]');
            if (b) act(b.getAttribute('data-act'), b.getAttribute('data-id'));
        });
        $('decisionCancel').addEventListener('click', closeDecision);
        $('decisionOk').addEventListener('click', submitDecision);
        $('decisionModal').addEventListener('click', e => { if (e.target.id === 'decisionModal') closeDecision(); });
        document.addEventListener('keydown', e => { if (e.key === 'Escape' && decision) closeDecision(); });
        load();
    });
})();
