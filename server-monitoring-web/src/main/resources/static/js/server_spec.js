// ================= 서버 전체목록 (/servers) : 서버별 주요 프로세스 등록 / 수정 / 삭제 =================
// API : POST /api/topology/servers/{id}/processes, PUT|DELETE .../processes/{procId}  (변경은 ADMIN)
// serversData / apiRequest / escapeHtml / isAdminUser / renderSpecTable / TOPO_API 는 script.js
let specProcServerId = null;

function specServer() {
    return serversData.find(s => s.id === specProcServerId) || null;
}

function openSpecProc(serverId) {
    specProcServerId = serverId;
    const s = specServer();
    if (!s) return;
    document.getElementById('specProcTitle').textContent = s.systemName + ' (' + s.ip + ') · 주요 프로세스';
    resetSpecForm();
    renderSpecProc();
    document.getElementById('specProcModal').style.display = 'flex';
    if (isAdminUser()) document.getElementById('spName').focus();
}

function closeSpecProc() {
    const m = document.getElementById('specProcModal');
    if (m) m.style.display = 'none';
    specProcServerId = null;
}

function renderSpecProc() {
    const s = specServer();
    if (!s) return;
    const admin = isAdminUser();
    const procs = s.processes || [];
    document.getElementById('specProcBody').innerHTML = procs.length ? procs.map((p, i) =>
        '<tr><td>' + (i + 1) + '</td><td class="svc-name">' + escapeHtml(p.name) + '</td>' +
        '<td class="svc-path" title="' + escapeHtml(p.path) + '">' + escapeHtml(p.path) + '</td>' +
        '<td><span class="svc-state ' + (p.running ? 'RUNNING' : 'STOPPED') + '">' + (p.running ? '실행 중' : '종료') + '</span></td>' +
        '<td><code>' + escapeHtml(p.pid) + '</code></td><td>' + escapeHtml(p.cpu) + ' / ' + escapeHtml(p.mem) + '</td>' +
        (admin ? '<td class="svc-manage"><button type="button" class="svc-btn restart" data-act="edit" data-id="' + p.id + '">수정</button>' +
                 '<button type="button" class="svc-btn del" data-act="del" data-id="' + p.id + '">삭제</button></td>' : '') + '</tr>'
    ).join('') : '<tr><td colspan="' + (admin ? 7 : 6) + '" class="svc-empty">등록된 주요 프로세스가 없습니다.' +
        (admin ? ' 아래에서 등록하세요.' : '') + '</td></tr>';
}

function resetSpecForm() {
    document.getElementById('spId').value = '';
    document.getElementById('spName').value = '';
    document.getElementById('spPath').value = '';
    document.getElementById('spError').textContent = '';
    document.getElementById('spFormTitle').textContent = '프로세스 등록';
    document.getElementById('spSave').textContent = '등록';
    document.getElementById('spCancel').hidden = true;
}

function editSpecProc(id) {
    const p = (specServer().processes || []).find(x => x.id === id);
    if (!p) return;
    document.getElementById('spId').value = p.id;
    document.getElementById('spName').value = p.name;
    document.getElementById('spPath').value = p.path;
    document.getElementById('spError').textContent = '';
    document.getElementById('spFormTitle').textContent = '프로세스 수정 - ' + p.name;
    document.getElementById('spSave').textContent = '저장';
    document.getElementById('spCancel').hidden = false;
    document.getElementById('spName').focus();
}

async function saveSpecProc(e) {
    e.preventDefault();
    const s = specServer();
    const name = document.getElementById('spName').value.trim();
    const path = document.getElementById('spPath').value.trim();
    const err = document.getElementById('spError');
    if (!name || !path) { err.textContent = '프로세스명과 실행 파일 경로를 입력하세요.'; return; }
    const id = document.getElementById('spId').value;
    const old = id ? s.processes.find(x => x.id === Number(id)) : null;
    const url = TOPO_API + '/servers/' + s.id + '/processes' + (id ? '/' + id : '');
    try {
        // 수정 시 실행 상태는 그대로 (상태는 에이전트 보고 / 메인 홈 기동·중지로 바뀜)
        const saved = await apiRequest(id ? 'PUT' : 'POST', url, { name: name, path: path, running: old ? old.running : true });
        if (old) s.processes[s.processes.indexOf(old)] = saved;
        else (s.processes = s.processes || []).push(saved);
        resetSpecForm();
        renderSpecProc();
        renderSpecTable();
    } catch (ex) {
        err.textContent = ex.message;
    }
}

async function deleteSpecProc(id) {
    const s = specServer();
    const p = (s.processes || []).find(x => x.id === id);
    if (!p || !confirm('[' + s.systemName + '] 의 주요 프로세스 "' + p.name + '" 를 삭제하시겠습니까?\n수집 이력도 함께 삭제됩니다.')) return;
    try {
        await apiRequest('DELETE', TOPO_API + '/servers/' + s.id + '/processes/' + id);
        s.processes = s.processes.filter(x => x.id !== id);
        if (Number(document.getElementById('spId').value) === id) resetSpecForm();
        renderSpecProc();
        renderSpecTable();
    } catch (ex) {
        alert(ex.message);
    }
}

window.addEventListener('DOMContentLoaded', function () {
    const modal = document.getElementById('specProcModal');
    if (!modal) return;
    if (!isAdminUser()) document.body.classList.add('role-user');
    document.getElementById('specProcForm').addEventListener('submit', saveSpecProc);
    document.getElementById('spCancel').addEventListener('click', resetSpecForm);
    document.getElementById('specProcBody').addEventListener('click', e => {
        const b = e.target.closest('button[data-act]');
        if (!b) return;
        const id = Number(b.getAttribute('data-id'));
        if (b.getAttribute('data-act') === 'edit') editSpecProc(id);
        else deleteSpecProc(id);
    });
    modal.addEventListener('click', e => { if (e.target === modal) closeSpecProc(); });
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && specProcServerId) closeSpecProc(); });
});
