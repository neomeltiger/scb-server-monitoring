// =============================================================
//  권한관리 (ADMIN) : 메뉴 권한 (menu_auth.html) / 메뉴 이름·순서 (menu_edit.html)
//  API : GET /api/admin/menus , PUT /api/admin/menus/access , PUT /api/admin/menus/layout , POST /api/admin/menus/reset
//  apiRequest, escapeHtml 은 script.js
// =============================================================
(function () {
    const API = '../api/admin/menus';
    const $ = id => document.getElementById(id);
    const esc = v => escapeHtml(v == null ? '' : String(v));
    /** 맨 위 단계 메뉴 아이콘 : 지정 아이콘, 없으면 기본 (그룹 📁 / 화면 📄 / 새 창 링크 🔗) - 서버 Menu.getDisplayIcon 과 같음 */
    const iconOf = m => m.icon || (m.type === 'GROUP' ? '📁' : m.type === 'LINK' ? '🔗' : '📄');

    function status(id, msg, bad) {
        const el = $(id);
        if (!el) return;
        el.textContent = msg || '';
        el.classList.toggle('bad', !!bad);
    }

    function showError(id, msg) {
        const el = $(id);
        el.textContent = msg || '';
        el.hidden = !msg;
    }

    /** 평면 목록 → 트리 [{...group, children:[]}, page, ...] (정렬 순서대로) */
    function toTree(list) {
        const top = list.filter(m => !m.parentId).sort((a, b) => a.sortOrd - b.sortOrd);
        return top.map(m => Object.assign({}, m, {
            children: m.type === 'GROUP' ? list.filter(c => c.parentId === m.id).sort((a, b) => a.sortOrd - b.sortOrd).map(c => Object.assign({}, c)) : null
        }));
    }

    // =========================================================== 메뉴 권한
    function initAuth() {
        let list = [];
        let dirty = false;
        let target = '';            // '' = 일반 사용자 기본 권한, 아니면 사용자 ID
        let access = null;          // 사용자별 : {custom, allowed}
        let users = [];

        async function loadUsers() {
            try {
                const d = await apiRequest('GET', '../api/admin/users?status=APPROVED');
                users = (d.users || []).filter(u => u.roleCd !== 'ADMIN');
            } catch (e) { users = []; }
            const sel = $('maTarget');
            sel.innerHTML = '<option value="">일반 사용자 기본 권한 (개별 지정이 없는 모든 일반 사용자)</option>' +
                users.map(u => '<option value="' + esc(u.userId) + '">' + esc(u.userId) + ' · ' + esc(u.userNm) + (u.menuCustom ? ' (개별 지정)' : '') +
                    (u.pwChangeRequired ? ' · 임시 비밀번호' : '') + '</option>').join('');
            const want = new URLSearchParams(location.search).get('user');
            if (want && users.some(u => u.userId === want)) target = want;
            else if (want) showError('maError', '[' + want + '] 는 승인된 일반 사용자가 아닙니다. (관리자는 항상 전체 메뉴)');
            sel.value = target;
        }

        async function load() {
            showError('maError', '');
            try {
                list = await apiRequest('GET', API);
                access = target ? await apiRequest('GET', API + '/access/' + encodeURIComponent(target)) : null;
            } catch (e) { showError('maError', '메뉴를 읽지 못했습니다 : ' + e.message); return; }
            dirty = false;
            const mode = $('maMode');
            mode.hidden = !target;
            if (target) mode.querySelector('input[value="' + (access.custom ? 'custom' : 'default') + '"]').checked = true;
            const u = users.find(x => x.userId === target);
            $('maTargetNote').textContent = !target ? '사용자별 지정이 없는 일반 사용자는 이 권한을 따릅니다. 관리자는 항상 전체 메뉴입니다.'
                : (access.custom ? '[' + target + '] 는 아래에서 체크한 메뉴만 봅니다.' : '[' + target + '] 는 지금 일반 사용자 기본 권한을 따릅니다 (아래는 기본 권한). "이 사용자만 따로 지정" 을 고르면 바꿀 수 있습니다.')
                + (u && u.pwChangeRequired ? ' · 이 사용자는 아직 임시 비밀번호를 바꾸지 않았습니다.' : '');
            render();
            status('maStatus', '');
        }

        function userAllowed(m) {
            if (!target) return m.userAllowed;
            return access.allowed.indexOf(m.id) !== -1;
        }

        function editable() {
            return !target || (document.querySelector('input[name="maMode"]:checked') || {}).value === 'custom';
        }

        function row(m, child) {
            const locked = m.adminOnly || m.fixed;
            const userCell = m.fixed ? '<span class="mn-fixed">항상 허용</span>'
                : m.adminOnly ? '<span class="mn-lock" title="권한관리·시스템관리 메뉴는 일반 사용자에게 줄 수 없습니다">🔒 관리자 전용</span>'
                : '<label class="mn-check"><input type="checkbox" data-id="' + esc(m.id) + '"' + (userAllowed(m) ? ' checked' : '') + (editable() ? '' : ' disabled') +
                  ' aria-label="' + esc(m.name) + ' 일반 사용자 허용"> 허용</label>';
            return '<tr class="' + (child ? 'mn-child' : 'mn-top') + (locked ? ' mn-locked' : '') + '">' +
                '<td class="ua-left">' + (child ? '<span class="mn-indent">└</span> ' : esc(iconOf(m)) + ' ') + '<b>' + esc(m.name) + '</b>' +
                (m.type === 'LINK' ? ' <span class="am-chip gray">새 창</span>' : '') + '</td>' +
                '<td class="svc-time"><code>' + esc(m.url || '') + '</code></td>' +
                '<td><span class="mn-ok">✔ 접근</span></td>' +
                '<td>' + userCell + '</td>' +
                '<td class="svc-time">' + (m.updDt ? esc(m.updUser || '') + '<span class="ua-sub">' + esc(m.updDt) + '</span>' : '-') + '</td></tr>';
        }

        function render() {
            let html = '';
            toTree(list).forEach(m => {
                if (m.type === 'GROUP') {
                    html += '<tr class="mn-group-row"><td colspan="5">' + esc(iconOf(m)) + ' <b>' + esc(m.name) + '</b>' +
                        (m.adminOnly ? ' <span class="mn-lock">🔒 관리자 전용 그룹</span>' : '') + '</td></tr>';
                    (m.children || []).forEach(c => { html += row(c, true); });
                } else {
                    html += row(m, false);
                }
            });
            $('maBody').innerHTML = html || '<tr><td colspan="5" class="svc-empty">메뉴가 없습니다.</td></tr>';
            count();
        }

        function count() {
            const boxes = Array.from(document.querySelectorAll('#maBody input[type=checkbox]'));
            const on = boxes.filter(b => b.checked).length;
            status('maStatus', (target ? '[' + target + '] ' : '일반 사용자 기본 ') + '허용 ' + on + ' / ' + boxes.length + '개 메뉴' + (dirty ? ' · 저장하지 않은 변경 있음' : ''));
        }

        $('maBody').addEventListener('change', e => {
            if (e.target.matches('input[type=checkbox]')) { dirty = true; count(); }
        });
        $('maAll').addEventListener('click', () => {
            document.querySelectorAll('#maBody input[type=checkbox]:not(:disabled)').forEach(b => { b.checked = true; });
            dirty = true; count();
        });
        $('maNone').addEventListener('click', () => {
            document.querySelectorAll('#maBody input[type=checkbox]:not(:disabled)').forEach(b => { b.checked = false; });
            dirty = true; count();
        });
        $('maReload').addEventListener('click', async () => { if (!dirty || (await scbConfirm('저장하지 않은 변경을 버리고 다시 읽을까요?'))) load(); });
        $('maSave').addEventListener('click', async () => {
            const allowed = Array.from(document.querySelectorAll('#maBody input[type=checkbox]:checked')).map(b => b.getAttribute('data-id'));
            try {
                if (!target) {
                    if (!(await scbConfirm('일반 사용자 기본 메뉴 권한을 저장하시겠습니까?\n허용 ' + allowed.length + '개 메뉴 - 개별 지정이 없는 일반 사용자의 좌측 메뉴에 바로 반영됩니다.'))) return;
                    list = await apiRequest('PUT', API + '/access', { allowed: allowed });
                    status('maStatus', '저장했습니다. (기본 권한 허용 ' + allowed.length + '개 메뉴)');
                } else {
                    const custom = editable();
                    if (!(await scbConfirm('[' + target + '] 의 메뉴 권한을 저장하시겠습니까?\n' + (custom ? '허용 ' + allowed.length + '개 메뉴 (이 사용자만)' : '일반 사용자 기본 권한을 따름')))) return;
                    access = await apiRequest('PUT', API + '/access/' + encodeURIComponent(target), { custom: custom, allowed: allowed });
                    status('maStatus', '저장했습니다. [' + target + '] ' + (custom ? '허용 ' + allowed.length + '개 메뉴' : '기본 권한 따름') + ' - 그 사용자가 다음 화면을 열 때 반영됩니다.');
                    await loadUsers();
                }
                dirty = false;
                render();
            } catch (e) { showError('maError', '저장 실패 : ' + e.message); }
        });
        $('maTarget').addEventListener('change', async e => {
            if (dirty && !(await scbConfirm('저장하지 않은 변경을 버리고 대상을 바꿀까요?'))) { e.target.value = target; return; }
            target = e.target.value;
            history.replaceState(null, '', target ? '?user=' + encodeURIComponent(target) : location.pathname);
            await load();
        });
        $('maMode').addEventListener('change', () => { dirty = true; render(); count(); });
        window.addEventListener('beforeunload', e => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
        loadUsers().then(load);
    }

    // =========================================================== 메뉴 이름·순서 (끌어서 놓기)
    function initEdit() {
        let tree = [];          // [{...group, children:[...]}, page, ...]
        let dirty = false;
        let dragId = null;

        async function load() {
            showError('meError', '');
            let list;
            try { list = await apiRequest('GET', API); } catch (e) { showError('meError', '메뉴를 읽지 못했습니다 : ' + e.message); return; }
            tree = toTree(list);
            dirty = false;
            render();
            status('meStatus', '');
        }

        function groups() { return tree.filter(m => m.type === 'GROUP'); }

        /** id 가 있는 목록과 위치 */
        function locate(id) {
            for (let i = 0; i < tree.length; i++) {
                if (tree[i].id === id) return { arr: tree, idx: i, parent: null };
                const ch = tree[i].children;
                if (ch) for (let j = 0; j < ch.length; j++) if (ch[j].id === id) return { arr: ch, idx: j, parent: tree[i].id };
            }
            return null;
        }

        function take(id) {
            const l = locate(id);
            return l ? l.arr.splice(l.idx, 1)[0] : null;
        }

        /** item 을 parentId(null = 맨 위) 목록의 index 위치에 */
        function put(item, parentId, index) {
            const arr = parentId ? (tree.find(g => g.id === parentId) || {}).children : tree;
            if (!arr) return;
            arr.splice(Math.max(0, Math.min(index, arr.length)), 0, item);
        }

        function changed(msg) {
            dirty = true;
            render();
            status('meStatus', (msg ? msg + ' · ' : '') + '저장하지 않은 변경 있음');
        }

        function itemHtml(m, parentId, idx, len) {
            const isGroup = m.type === 'GROUP';
            const opts = isGroup ? '' : '<select class="mn-parent" data-id="' + esc(m.id) + '" aria-label="' + esc(m.name) + ' 위치(그룹)">' +
                '<option value=""' + (!parentId ? ' selected' : '') + '>맨 위</option>' +
                groups().map(g => '<option value="' + esc(g.id) + '"' + (g.id === parentId ? ' selected' : '') + '>' + esc(g.name) + '</option>').join('') + '</select>';
            const renamed = m.name !== m.defaultName;
            let html = '<li class="mn-item' + (isGroup ? ' group' : '') + '" draggable="true" data-id="' + esc(m.id) + '">' +
                '<div class="mn-row">' +
                '<span class="mn-handle" title="끌어서 이동" aria-hidden="true">⠿</span>' +
                '<span class="mn-icon" aria-hidden="true">' + esc(isGroup || !parentId ? iconOf(m) : '▪') + '</span>' +
                '<input type="text" class="mn-name' + (renamed ? ' renamed' : '') + '" maxlength="50" value="' + esc(m.name) + '" data-id="' + esc(m.id) + '" aria-label="' + esc(m.defaultName) + ' 메뉴 이름">' +
                '<button type="button" class="mn-btn" data-act="default" data-id="' + esc(m.id) + '" title="처음 이름 : ' + esc(m.defaultName) + '"' + (renamed ? '' : ' disabled') + '>↺</button>' +
                '<span class="mn-meta">' + (isGroup ? '그룹' : esc(m.url || '')) + (m.adminOnly ? ' · 🔒 관리자 전용' : '') + (renamed ? ' · 처음 : ' + esc(m.defaultName) : '') + '</span>' +
                '<span class="mn-moves">' +
                '<button type="button" class="mn-btn" data-act="up" data-id="' + esc(m.id) + '" aria-label="' + esc(m.name) + ' 위로"' + (idx === 0 ? ' disabled' : '') + '>▲</button>' +
                '<button type="button" class="mn-btn" data-act="down" data-id="' + esc(m.id) + '" aria-label="' + esc(m.name) + ' 아래로"' + (idx === len - 1 ? ' disabled' : '') + '>▼</button>' +
                opts + '</span></div>';
            if (isGroup) {
                const ch = m.children || [];
                html += '<ul class="mn-children" data-parent="' + esc(m.id) + '">' +
                    ch.map((c, j) => itemHtml(c, m.id, j, ch.length)).join('') +
                    (ch.length ? '' : '<li class="mn-empty" data-parent="' + esc(m.id) + '">여기에 메뉴를 끌어 놓으세요 (비어 있는 그룹은 좌측 메뉴에 보이지 않음)</li>') + '</ul>';
            }
            return html + '</li>';
        }

        function render() {
            $('meTree').innerHTML = tree.map((m, i) => itemHtml(m, null, i, tree.length)).join('');
            renderPreview();
        }

        function renderPreview() {
            $('mePreview').innerHTML = tree.map(m => m.type === 'GROUP'
                ? '<li class="pv-group">' + esc(iconOf(m)) + ' ' + esc(m.name) + '<ul>' + (m.children || []).map(c => '<li>▪ ' + esc(c.name) + '</li>').join('') + '</ul></li>'
                : '<li class="pv-top">' + esc(iconOf(m)) + ' ' + esc(m.name) + '</li>').join('');
        }

        // ---- 이름
        $('meTree').addEventListener('input', e => {
            if (!e.target.matches('.mn-name')) return;
            const l = locate(e.target.getAttribute('data-id'));
            if (l) l.arr[l.idx].name = e.target.value;
            dirty = true;
            status('meStatus', '저장하지 않은 변경 있음');
            renderPreview();
        });
        $('meTree').addEventListener('change', e => {
            if (e.target.matches('.mn-name')) { render(); return; }
            if (!e.target.matches('.mn-parent')) return;
            const id = e.target.getAttribute('data-id');
            const item = take(id);
            const pid = e.target.value || null;
            put(item, pid, pid ? ((tree.find(g => g.id === pid) || {}).children || []).length : tree.length);
            changed('\'' + item.name + '\' 이동');
            focusItem(id, '.mn-parent');
        });

        // ---- 버튼 (▲ ▼ ↺)
        $('meTree').addEventListener('click', e => {
            const b = e.target.closest('.mn-btn');
            if (!b || b.disabled) return;
            const id = b.getAttribute('data-id');
            const l = locate(id);
            if (!l) return;
            const act = b.getAttribute('data-act');
            if (act === 'default') {
                l.arr[l.idx].name = l.arr[l.idx].defaultName;
                changed('처음 이름으로');
                focusItem(id, '.mn-name');
                return;
            }
            const to = act === 'up' ? l.idx - 1 : l.idx + 1;
            if (to < 0 || to >= l.arr.length) return;
            const [it] = l.arr.splice(l.idx, 1);
            l.arr.splice(to, 0, it);
            changed('\'' + it.name + '\' 순서 변경');
            focusItem(id, '[data-act="' + act + '"]');
        });

        function focusItem(id, sel) {
            const li = document.querySelector('#meTree .mn-item[data-id="' + CSS.escape(id) + '"]');
            const el = li && li.querySelector(':scope > .mn-row ' + sel);
            if (el && !el.disabled) el.focus(); else if (li) li.querySelector('.mn-name').focus();
        }

        // ---- 끌어서 놓기 (HTML5 drag and drop)
        function clearMarks() {
            document.querySelectorAll('#meTree .drop-before, #meTree .drop-after, #meTree .drop-into')
                .forEach(el => el.classList.remove('drop-before', 'drop-after', 'drop-into'));
        }

        /** 놓을 자리 : {parentId, index, el, mode} */
        function target(e) {
            const dragged = locate(dragId);
            if (!dragged) return null;
            const draggedIsGroup = dragged.arr[dragged.idx].type === 'GROUP';
            const empty = e.target.closest('.mn-empty');
            if (empty && !draggedIsGroup) return { parentId: empty.getAttribute('data-parent'), index: 0, el: empty, mode: 'into' };
            let li = e.target.closest('.mn-item');
            if (!li || li.getAttribute('data-id') === dragId) return null;
            let l = locate(li.getAttribute('data-id'));
            // 그룹은 맨 위 단계에만 : 하위 메뉴 위에 놓으면 그 그룹 기준
            if (draggedIsGroup && l.parent) {
                li = li.parentElement.closest('.mn-item');
                l = locate(li.getAttribute('data-id'));
            }
            const row = li.querySelector(':scope > .mn-row').getBoundingClientRect();
            const y = e.clientY - row.top;
            const isGroupRow = l.arr[l.idx].type === 'GROUP' && !l.parent;
            if (!draggedIsGroup && isGroupRow && y > row.height * 0.3 && y < row.height * 0.7) {
                return { parentId: l.arr[l.idx].id, index: (l.arr[l.idx].children || []).length, el: li.querySelector(':scope > .mn-row'), mode: 'into' };
            }
            const after = y > row.height / 2;
            return { parentId: l.parent, index: l.idx + (after ? 1 : 0), el: li.querySelector(':scope > .mn-row'), mode: after ? 'after' : 'before' };
        }

        $('meTree').addEventListener('dragstart', e => {
            const li = e.target.closest('.mn-item');
            if (!li || e.target.matches('input, select, button')) { e.preventDefault(); return; }
            dragId = li.getAttribute('data-id');
            e.dataTransfer.effectAllowed = 'move';
            try { e.dataTransfer.setData('text/plain', dragId); } catch (err) { /* IE */ }
            requestAnimationFrame(() => li.classList.add('dragging'));
            e.stopPropagation();
        });
        $('meTree').addEventListener('dragend', () => {
            dragId = null;
            clearMarks();
            document.querySelectorAll('#meTree .dragging').forEach(el => el.classList.remove('dragging'));
        });
        $('meTree').addEventListener('dragover', e => {
            if (!dragId) return;
            const t = target(e);
            clearMarks();
            if (!t) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = 'move';
            t.el.classList.add('drop-' + t.mode);
        });
        $('meTree').addEventListener('dragleave', e => { if (!$('meTree').contains(e.relatedTarget)) clearMarks(); });
        $('meTree').addEventListener('drop', e => {
            if (!dragId) return;
            e.preventDefault();
            const t = target(e);
            clearMarks();
            if (!t) return;
            const from = locate(dragId);
            let index = t.index;
            // 같은 목록 안에서 앞쪽 항목을 뒤로 옮기면 빼낸 만큼 한 칸 당김
            if (from.parent === t.parentId && from.idx < index) index--;
            const item = take(dragId);
            put(item, t.parentId, index);
            const id = dragId;
            dragId = null;
            changed('\'' + item.name + '\' 이동');
            focusItem(id, '.mn-name');
        });

        // ---- 저장 / 되돌리기
        $('meSave').addEventListener('click', async () => {
            const items = [];
            let blank = null;
            tree.forEach((m, i) => {
                items.push({ id: m.id, parentId: null, sortOrd: (i + 1) * 10, name: (m.name || '').trim() });
                if (!(m.name || '').trim()) blank = blank || m;
                (m.children || []).forEach((c, j) => {
                    items.push({ id: c.id, parentId: m.id, sortOrd: (j + 1) * 10, name: (c.name || '').trim() });
                    if (!(c.name || '').trim()) blank = blank || c;
                });
            });
            if (blank) { showError('meError', '이름이 빈 메뉴가 있습니다 (처음 이름 : ' + blank.defaultName + ')'); return; }
            showError('meError', '');
            try {
                tree = toTree(await apiRequest('PUT', API + '/layout', items));
                dirty = false;
                render();
                status('meStatus', '저장했습니다. 좌측 메뉴는 화면을 새로 열면 바뀝니다.');
                setTimeout(() => location.reload(), 800);
            } catch (e) { showError('meError', '저장 실패 : ' + e.message); }
        });
        $('meReset').addEventListener('click', async () => {
            if (!(await scbConfirm('모든 메뉴의 이름·순서·그룹과 일반 사용자 메뉴 권한을 처음 상태로 되돌리시겠습니까?'))) return;
            try {
                tree = toTree(await apiRequest('POST', API + '/reset'));
                dirty = false;
                render();
                status('meStatus', '처음 상태로 되돌렸습니다.');
                setTimeout(() => location.reload(), 800);
            } catch (e) { showError('meError', '되돌리기 실패 : ' + e.message); }
        });
        $('meReload').addEventListener('click', async () => { if (!dirty || (await scbConfirm('저장하지 않은 변경을 버리고 다시 읽을까요?'))) load(); });
        window.addEventListener('beforeunload', e => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
        load();
    }

    window.addEventListener('DOMContentLoaded', () => {
        if ($('maBody')) initAuth();
        if ($('meTree')) initEdit();
    });
})();
