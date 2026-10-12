// =============================================================
//  API 가이드 (/admin/api-guide) - Postman API 문서 형태
//    왼쪽 : 태그별 API 목록 (검색 / 웹·조회·에이전트 필터)
//    오른쪽 : 설명 · 권한 · 처리 소스 · 파라미터 · 요청 편집 → [보내기] → 응답 · 언어별 예제 (복사)
//    시작하기 : 언어별 로그인 준비 코드 / 소스 : ScbApiClient.java · ScbApiExamples.java · Postman 컬렉션
//  데이터 : GET ../api/admin/docs/catalog (ApiDocsController)
// =============================================================
(function () {
    const CATALOG_URL = '../api/admin/docs/catalog';
    const LANGS = [
        { key: 'curl', label: 'cURL', note: 'bash · 시작하기의 로그인 다음에 실행 (쿠키 파일 cj.txt)' },
        { key: 'java', label: 'Java', note: 'ScbApiClient api (시작하기) 다음에 붙여넣기 · JDK 1.8+' },
        { key: 'javascript', label: 'JavaScript', note: '이 웹에 로그인한 브라우저 F12 Console · 시작하기의 BASE / xsrf() 먼저' },
        { key: 'python', label: 'Python', note: 'requests · 시작하기의 로그인 세션 s 다음에 실행' },
        { key: 'postman', label: 'Postman', note: 'Postman [Import] > [Raw text] 에 붙여넣기 · 컬렉션 변수 {{baseUrl}} {{xsrf}} {{agentToken}}' }
    ];
    const METHOD_CLASS = { GET: 'get', POST: 'post', PUT: 'put', DELETE: 'delete', PATCH: 'put' };

    let data = null;
    let current = null;
    let lang = 'curl';
    try { lang = localStorage.getItem('agLang') || 'curl'; } catch (e) { /* 저장소 사용 불가 */ }
    const srcCache = {};

    const $ = id => document.getElementById(id);
    const esc = v => escapeHtml(v == null ? '' : String(v));
    const base = () => new URL('..', location.href).href.replace(/\/$/, '');

    function showError(msg) {
        const el = $('agError');
        el.textContent = msg || '';
        el.hidden = !msg;
    }

    // ---------------------------------------------------------- 복사
    async function copyText(text, btn) {
        let ok = false;
        try {
            await navigator.clipboard.writeText(text);
            ok = true;
        } catch (e) {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            try { ok = document.execCommand('copy'); } catch (e2) { ok = false; }
            ta.remove();
        }
        if (btn) {
            const old = btn.textContent;
            btn.textContent = ok ? '복사됨' : '복사 실패';
            setTimeout(() => { btn.textContent = old; }, 1200);
        }
    }

    function codeBlock(text, title) {
        return '<div class="ag-code">' +
            '<div class="ag-code-head"><span>' + esc(title || '') + '</span>' +
            '<button type="button" class="btn svc-btn-light ag-copy">복사</button></div>' +
            '<pre><code>' + esc(text) + '</code></pre></div>';
    }

    document.addEventListener('click', ev => {
        const b = ev.target.closest('.ag-copy');
        if (!b) return;
        const code = b.closest('.ag-code').querySelector('code');
        copyText(code ? code.textContent : '', b);
    });

    // ---------------------------------------------------------- 목록
    function kindOk(e, kind) {
        if (kind === 'web') return !e.agent;
        if (kind === 'get') return !e.agent && e.method === 'GET';
        if (kind === 'agent') return e.agent;
        return true;
    }

    function renderList() {
        const q = $('agSearch').value.trim().toLowerCase();
        const kind = (document.querySelector('input[name="agKind"]:checked') || {}).value || '';
        const items = data.entries.filter(e => kindOk(e, kind) &&
            (!q || (e.summary + ' ' + e.path + ' ' + e.method + ' ' + e.tag + ' ' + e.handler).toLowerCase().indexOf(q) !== -1));
        $('agCount').textContent = 'API ' + items.length + ' / ' + data.entries.length + '건';
        if (!items.length) {
            $('agList').innerHTML = '<div class="svc-empty">검색 결과가 없습니다.</div>';
            return;
        }
        let html = '';
        let tag = null;
        items.forEach(e => {
            if (e.tag !== tag) {
                if (tag !== null) html += '</ul>';
                tag = e.tag;
                html += '<div class="ag-tag">' + esc(tag) + '</div><ul>';
            }
            html += '<li><a href="#' + encodeURIComponent(e.id) + '" class="ag-item' + (current && current.id === e.id ? ' active' : '') +
                '" data-id="' + esc(e.id) + '"><span class="ag-m ' + (METHOD_CLASS[e.method] || '') + '">' + esc(e.method) + '</span>' +
                '<span class="ag-item-txt"><b>' + esc(e.summary) + '</b><small>' + esc(e.path) + '</small></span></a></li>';
        });
        $('agList').innerHTML = html + '</ul>';
    }

    $('agList').addEventListener('click', ev => {
        const a = ev.target.closest('.ag-item');
        if (!a) return;
        ev.preventDefault();
        select(a.getAttribute('data-id'), true);
    });

    function select(id, push) {
        const e = data.entries.find(x => x.id === id);
        if (!e) return;
        current = e;
        document.querySelectorAll('.ag-item').forEach(a => a.classList.toggle('active', a.getAttribute('data-id') === id));
        renderDetail(e);
        if (push) history.replaceState(null, '', '#' + encodeURIComponent(id));
        if (window.innerWidth < 900) $('agDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    // ---------------------------------------------------------- 상세
    function paramRows(e) {
        const rows = [];
        Object.keys(e.pathVars || {}).forEach(k => rows.push(['경로', k, e.pathVars[k], '실제 ID 로 바꾸세요 (서버 ID 는 GET /api/topology/servers 의 id)']));
        Object.keys(e.query || {}).forEach(k => rows.push(['쿼리', k, e.query[k], '']));
        if (!rows.length) return '';
        return '<h4>파라미터</h4><table class="ag-params"><thead><tr><th>위치</th><th>이름</th><th>예시 값</th><th>설명</th></tr></thead><tbody>' +
            rows.map(r => '<tr><td>' + esc(r[0]) + '</td><td><code>' + esc(r[1]) + '</code></td><td><code>' + esc(r[2]) + '</code></td><td>' + esc(r[3]) + '</td></tr>').join('') +
            '</tbody></table>';
    }

    function renderDetail(e) {
        const sendNote = e.agent
            ? '에이전트 API 는 실제 서버 상태(하트비트·보고)가 바뀌므로 화면에서 보내지 않습니다. cURL / Postman 으로 호출하세요.'
            : e.write ? '보내면 실제 데이터가 변경됩니다 (ADMIN). 보내기 전에 확인 창이 뜹니다.'
            : '지금 로그인한 세션으로 호출합니다.';
        let html =
            '<div class="ag-head"><span class="ag-m big ' + (METHOD_CLASS[e.method] || '') + '">' + esc(e.method) + '</span>' +
            '<code class="ag-path">' + esc(e.path) + '</code></div>' +
            '<h3 class="ag-title">' + esc(e.summary) + '</h3>' +
            '<table class="ag-meta"><tbody>' +
            '<tr><th>분류</th><td>' + esc(e.tag) + '</td></tr>' +
            '<tr><th>인증</th><td>' + esc(e.auth) + '</td></tr>' +
            '<tr><th>응답</th><td>' + (e.textResponse ? 'text/plain' : 'application/json') + '</td></tr>' +
            '<tr><th>처리 소스</th><td><code>' + esc(e.handler) + '</code><br><small>' + esc(e.sourceFile) + '</small></td></tr>' +
            '</tbody></table>' +
            paramRows(e) +
            // 요청 편집 · 보내기
            '<h4>요청 보내기</h4>' +
            '<div class="ag-req"><span class="ag-m ' + (METHOD_CLASS[e.method] || '') + '">' + esc(e.method) + '</span>' +
            '<input type="text" id="agUrl" class="ag-url" aria-label="요청 경로" value="' + esc(e.samplePath) + '"' + (e.agent ? ' disabled' : '') + '>' +
            '<button type="button" class="btn ag-send" id="agSend"' + (e.agent ? ' disabled' : '') + '>보내기</button></div>' +
            '<p class="ag-note' + (e.write || e.agent ? ' warn' : '') + '">' + esc(sendNote) + '</p>' +
            (e.body != null ? '<label class="ag-lbl" for="agBody">Body (' + (e.textBody ? 'text/plain' : 'application/json') + ')</label>' +
                '<textarea id="agBody" class="ag-body" rows="' + Math.min(14, Math.max(4, String(e.body).split('\n').length + 1)) + '" spellcheck="false"' +
                (e.agent ? ' disabled' : '') + '>' + esc(e.body) + '</textarea>' : '') +
            '<div id="agResp" class="ag-resp" hidden></div>' +
            // 예제
            '<h4>예제 코드 <small class="ag-sub">복사해서 사용</small></h4>' +
            '<div class="ag-tabs" role="tablist" aria-label="예제 언어">' +
            LANGS.map(l => '<button type="button" role="tab" class="ag-tab' + (l.key === lang ? ' active' : '') + '" data-lang="' + l.key +
                '" aria-selected="' + (l.key === lang) + '">' + esc(l.label) + '</button>').join('') + '</div>' +
            '<div id="agCode"></div>' +
            '<details class="ag-more"><summary>Postman 요청 JSON (컬렉션 item)</summary>' + codeBlock(e.postmanItem, 'collection item v2.1') + '</details>';
        $('agDetail').innerHTML = html;
        renderCode(e);
        const send = $('agSend');
        if (send && !e.agent) send.addEventListener('click', () => sendRequest(e));
        const url = $('agUrl');
        if (url) url.addEventListener('keydown', ev => { if (ev.key === 'Enter' && !e.agent) sendRequest(e); });
    }

    function renderCode(e) {
        const l = LANGS.find(x => x.key === lang) || LANGS[0];
        $('agCode').innerHTML = '<p class="ag-note">' + esc(l.note) + '</p>' + codeBlock(e.code[l.key], l.label);
    }

    $('agDetail').addEventListener('click', ev => {
        const t = ev.target.closest('.ag-tab[data-lang]');
        if (!t || !current) return;
        lang = t.getAttribute('data-lang');
        try { localStorage.setItem('agLang', lang); } catch (e) { /* 무시 */ }
        document.querySelectorAll('.ag-tab[data-lang]').forEach(b => {
            const on = b === t;
            b.classList.toggle('active', on);
            b.setAttribute('aria-selected', String(on));
        });
        renderCode(current);
    });

    // ---------------------------------------------------------- 보내기 (Postman Send)
    async function sendRequest(e) {
        const path = $('agUrl').value.trim();
        if (path.indexOf('/api/') !== 0) {
            showResp('<div class="ag-resp-head bad">경로는 /api/ 로 시작해야 합니다.</div>');
            return;
        }
        if (e.write && !confirm(e.method + ' ' + path + '\n\n실제 데이터가 변경됩니다. 보낼까요?')) return;
        const opt = { method: e.method, credentials: 'same-origin', headers: Object.assign({ 'Accept': 'application/json, text/plain, */*' }, csrfHeaders()) };
        const bodyEl = $('agBody');
        if (bodyEl && e.method !== 'GET') {
            if (!e.textBody) {
                try { JSON.parse(bodyEl.value || 'null'); } catch (err) {
                    showResp('<div class="ag-resp-head bad">Body JSON 형식 오류 : ' + esc(err.message) + '</div>');
                    return;
                }
            }
            opt.headers['Content-Type'] = e.textBody ? 'text/plain; charset=UTF-8' : 'application/json';
            opt.body = bodyEl.value;
        }
        const btn = $('agSend');
        btn.disabled = true;
        btn.textContent = '보내는 중...';
        const t0 = performance.now();
        try {
            const res = await fetch(base() + path, opt);
            const ms = Math.round(performance.now() - t0);
            const text = await res.text();
            let shown = text;
            const type = res.headers.get('content-type') || '';
            if (type.indexOf('json') !== -1) {
                try { shown = JSON.stringify(JSON.parse(text), null, 2); } catch (err) { /* 그대로 */ }
            } else if (type.indexOf('text/html') !== -1) {
                shown = res.redirected ? '(로그인 화면으로 이동됨 - 세션이 만료되었으면 새로고침 후 다시 로그인하세요)' : text.slice(0, 2000);
            }
            const ok = res.ok && !(res.redirected && type.indexOf('text/html') !== -1);
            showResp('<div class="ag-resp-head ' + (ok ? 'ok' : 'bad') + '">' +
                '<b>' + res.status + ' ' + esc(res.statusText || '') + '</b> · ' + ms + ' ms · ' + text.length.toLocaleString() + ' bytes · ' + esc(type || '-') + '</div>' +
                codeBlock(shown || '(빈 응답)', '응답'));
        } catch (err) {
            showResp('<div class="ag-resp-head bad">요청 실패 : ' + esc(err.message) + '</div>');
        } finally {
            btn.disabled = false;
            btn.textContent = '보내기';
        }
    }

    function showResp(html) {
        const r = $('agResp');
        r.innerHTML = html;
        r.hidden = false;
    }

    // ---------------------------------------------------------- 시작하기
    function renderGuide() {
        const g = data.guide;
        $('agGuide').innerHTML =
            '<p class="ag-note">BASE 주소 : <code>' + esc(data.baseUrl) + '</code></p>' +
            codeBlock(g.curl, 'cURL (bash) - 로그인') +
            codeBlock(g.java, 'Java - 로그인') +
            codeBlock(g.javascript, 'JavaScript (브라우저 Console) - 준비') +
            codeBlock(g.python, 'Python (requests) - 로그인') +
            codeBlock(g.postman, 'Postman - 순서');
    }

    // ---------------------------------------------------------- 소스
    const SOURCES = {
        client: { url: '../samples/ScbApiClient.java', note: 'API 호출 클라이언트 (파일 하나, 외부 라이브러리 없음, JDK 1.8+) - 로그인 · CSRF · JSON 처리. 프로젝트에 그대로 복사해서 사용하세요.' },
        examples: { url: '../api/admin/docs/examples-java', note: '전체 API 예제 (자동 생성). ScbApiClient.java 와 같은 폴더에서 javac -encoding UTF-8 ScbApiClient.java ScbApiExamples.java → java ScbApiExamples. main 은 조회 API 만 실행합니다.' },
        postman: { url: '../api/docs/postman-collection', note: 'Postman [Import] 로 가져오는 컬렉션 (v2.1) - 태그별 폴더, 로그인 · CSRF 자동 처리 스크립트 포함.' }
    };

    async function showSource(key) {
        const s = SOURCES[key];
        $('agSrcNote').textContent = s.note;
        document.querySelectorAll('.ag-tab[data-src]').forEach(b => {
            const on = b.getAttribute('data-src') === key;
            b.classList.toggle('active', on);
            b.setAttribute('aria-selected', String(on));
        });
        const box = $('agSrcBox');
        if (!srcCache[key]) {
            box.innerHTML = '<pre><code>불러오는 중...</code></pre>';
            try {
                const res = await fetch(s.url, { credentials: 'same-origin' });
                if (!res.ok) throw new Error('HTTP ' + res.status);
                let text = await res.text();
                if (key === 'postman') text = JSON.stringify(JSON.parse(text), null, 2);
                srcCache[key] = text;
            } catch (err) {
                box.innerHTML = '<div class="data-error">소스를 불러오지 못했습니다 : ' + esc(err.message) + '</div>';
                return;
            }
        }
        box.outerHTML = '<div class="ag-code" id="agSrcBox">' +
            '<div class="ag-code-head"><span>' + esc(key === 'client' ? 'ScbApiClient.java' : key === 'examples' ? 'ScbApiExamples.java' : 'scb-monitoring.postman_collection.json') +
            ' · ' + srcCache[key].split('\n').length + '줄</span>' +
            '<button type="button" class="btn svc-btn-light ag-copy">복사</button></div>' +
            '<pre><code>' + esc(srcCache[key]) + '</code></pre></div>';
    }

    document.querySelectorAll('.ag-tab[data-src]').forEach(b => b.addEventListener('click', () => showSource(b.getAttribute('data-src'))));

    // ---------------------------------------------------------- 페이지 탭
    function showPage(p) {
        document.querySelectorAll('.ag-pagetab').forEach(b => {
            const on = b.getAttribute('data-page') === p;
            b.classList.toggle('active', on);
            b.setAttribute('aria-selected', String(on));
        });
        $('agPageApi').hidden = p !== 'api';
        $('agPageStart').hidden = p !== 'start';
        $('agPageSource').hidden = p !== 'source';
        if (p === 'source') {
            const active = document.querySelector('.ag-tab[data-src].active');
            showSource(active ? active.getAttribute('data-src') : 'client');
        }
    }
    document.querySelectorAll('.ag-pagetab').forEach(b => b.addEventListener('click', () => showPage(b.getAttribute('data-page'))));

    // ---------------------------------------------------------- 시작
    $('agSearch').addEventListener('input', renderList);
    document.querySelectorAll('input[name="agKind"]').forEach(r => r.addEventListener('change', renderList));

    async function load() {
        try {
            data = await apiRequest('GET', CATALOG_URL);
        } catch (err) {
            showError('API 목록을 불러오지 못했습니다 : ' + err.message);
            $('agList').innerHTML = '';
            return;
        }
        renderList();
        renderGuide();
        const want = decodeURIComponent(location.hash.replace(/^#/, ''));
        const first = data.entries.find(e => e.id === want) || data.entries[0];
        if (first) select(first.id, false);
    }
    load();
})();
