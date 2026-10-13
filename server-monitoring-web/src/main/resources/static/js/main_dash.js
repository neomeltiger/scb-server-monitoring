// ================= 메인 홈 모니터링 대시보드 (Prometheus / Grafana 형) =================
// 위치 : index.html #mnDash (장애 알람 · 미등록 서버 아래, 서버 카드 위)
// 데이터 : GET api/topology/history?points=N (서버 CPU / MEM / 디스크 / 세션, 프로세스·서비스 CPU 이력, ts = 수집 시각 ms)
//          serversData (script.js loadServers) - 서버 이름·상태·위치
// 구성 : 요약 지표(stat) 한 줄 + 패널 여러 개 (선 / 영역 / 막대 / 게이지), 패널 추가·편집·삭제·확대
//        서버 선택(전체 / 여러 대 / 이 서버만) - 모든 패널·요약 지표에 적용, 보기 : 패널(서버 겹쳐 보기) / 서버별 나눠 보기
// 화면 설정(배경, 열 수, 조회 범위, 자동 갱신, 패널 목록)은 브라우저(localStorage)에 사용자별로 저장
// script.js 의 renderDashboard 가 서버 목록을 읽은 뒤 mnDashInit() 을 부름
(function () {
    const KEY = 'mnDash.v1';
    const COLORS = ['#7eb26d', '#eab839', '#6ed0e0', '#ef843c', '#e24d42', '#1f78c1', '#ba43a9', '#705da0', '#508642', '#cca300', '#447ebc', '#c15c17'];
    const METRICS = {
        cpu: { name: 'CPU 사용률', unit: '%', max: 100, q: 'scb_server_cpu_usage_percent' },
        mem: { name: '메모리 사용률', unit: '%', max: 100, q: 'scb_server_memory_usage_percent' },
        disk: { name: '디스크 사용률 (최고 파일시스템)', unit: '%', max: 100, q: 'scb_server_disk_usage_percent' },
        sessions: { name: 'TCP 세션 수', unit: '', max: null, q: 'scb_server_tcp_sessions' },
        proc: { name: '프로세스·서비스 CPU', unit: '%', max: null, q: 'scb_process_cpu_usage_percent' },
        // 서버별 나눠 보기 전용 : 서버 하나의 CPU · 메모리 · 디스크를 한 그래프에
        res: { name: '서버 자원 (CPU · 메모리 · 디스크)', unit: '%', max: 100, q: 'scb_server_resource_usage_percent' }
    };
    const VIEWS = [['site', '사이트별 (ITC / DRC)'], ['all', '전체 합쳐 보기'], ['split', '서버별 나눠 보기']];
    // 사이트 (서버 location : DRC 가 아니면 ITC)
    const SITES = [['itc', 'ITC · GASAN MTR-A'], ['drc', 'DRC']];
    const TYPES = [['line', '선'], ['area', '영역'], ['bar', '막대 (현재값)'], ['gauge', '게이지 (현재값)']];
    const BGS = [
        ['light', '기본 (밝게)'], ['dark', '다크 (Grafana)'], ['midnight', '미드나잇 블루'], ['graphite', '그라파이트'],
        ['aurora', '오로라 그라데이션'], ['grid', '모눈 격자'], ['sand', '샌드 베이지']
    ];
    // 조회 범위 (분) - Prometheus 처럼 "지금부터 거슬러" 시간 창
    const RANGES = [[15, '최근 15분'], [30, '최근 30분'], [60, '최근 1시간'], [180, '최근 3시간'], [360, '최근 6시간'], [720, '최근 12시간'], [1440, '최근 24시간']];
    const REFRESH = [[0, '끄기'], [10, '10초'], [30, '30초'], [60, '1분'], [300, '5분']];
    const DEFAULT_PANELS = [
        { id: 'p1', title: 'CPU 사용률', metric: 'cpu', type: 'line', servers: [] },
        { id: 'p2', title: '메모리 사용률', metric: 'mem', type: 'area', servers: [] },
        { id: 'p3', title: '디스크 사용률', metric: 'disk', type: 'bar', servers: [] },
        { id: 'p4', title: 'TCP 세션 수', metric: 'sessions', type: 'line', servers: [] }
    ];

    let st = null;          // 저장되는 화면 설정
    let hist = {};          // 서버 ID → HistoryView
    let timer = null;
    let loading = false;
    let lastLoad = null;
    const hidden = {};      // 패널 ID → { 계열 이름: true } (범례 클릭으로 숨김, 저장 안 함)
    let zoomId = null;      // 확대 중인 패널
    let editId = null;      // 편집 중인 패널 (null = 새 패널)

    const $ = id => document.getElementById(id);
    const esc = v => (typeof escapeHtml === 'function' ? escapeHtml(v) : String(v));

    // ------------------------------------------------------------ 설정 저장
    function load() {
        let s = null;
        try { s = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { s = null; }
        s = s && typeof s === 'object' ? s : {};
        return {
            bg: BGS.some(b => b[0] === s.bg) ? s.bg : 'light',
            cols: [1, 2, 3].includes(s.cols) ? s.cols : 2,
            range: RANGES.some(r => r[0] === s.range) ? s.range : 60,
            refresh: REFRESH.some(r => r[0] === s.refresh) ? s.refresh : 60,
            folded: !!s.folded,
            // 대시보드 전체 서버 선택 (빈 배열 = 전체) - Grafana 의 서버 변수처럼 모든 패널·요약 지표에 적용
            sel: Array.isArray(s.sel) ? s.sel.map(String) : [],
            // 보기 기본값 = 사이트별 (이전에 저장된 보기는 viewV 2 부터 사이트별로 한 번 바꿈)
            view: s.viewV === 2 && VIEWS.some(v => v[0] === s.view) ? s.view : 'site',
            viewV: 2,
            panels: Array.isArray(s.panels) && s.panels.length ? s.panels.filter(p => p && METRICS[p.metric] && p.metric !== 'res') : DEFAULT_PANELS.map(p => Object.assign({}, p))
        };
    }

    function save() {
        try { localStorage.setItem(KEY, JSON.stringify(st)); } catch (e) { /* 저장 못 해도 화면은 동작 */ }
    }

    // ------------------------------------------------------------ 데이터
    async function fetchHistory() {
        if (loading) return;
        loading = true;
        try {
            // 수집 주기 10초까지 고려해 분당 6건, 최대 1440건 (1분 수집이면 24시간)
            hist = await apiRequest('GET', 'api/topology/history?points=' + Math.min(1440, st.range * 6)) || {};
            lastLoad = new Date();
        } catch (e) {
            console.warn('대시보드 이력을 불러오지 못했습니다:', e);
        } finally {
            loading = false;
        }
    }

    function scopeOk(s) {
        const sc = typeof processScope === 'string' ? processScope : 'all';
        return sc === 'all' || (sc === 'drc' ? s.location === 'DRC' : s.location !== 'DRC');
    }

    function allServers() {
        return typeof serversData !== 'undefined' ? serversData : [];
    }

    function siteOf(s) { return s.location === 'DRC' ? 'drc' : 'itc'; }

    /** 위치(전체/ITC/DRC)와 대시보드 서버 선택을 모두 적용한 서버. site 를 주면 그 사이트만 */
    function servers(site) {
        const sel = st.sel.filter(id => allServers().some(s => String(s.id) === id));
        return allServers().filter(scopeOk).filter(s => !sel.length || sel.includes(String(s.id)))
            .filter(s => !site || siteOf(s) === site);
    }

    /** 패널 하나의 계열 : [{name, values[], ts[] (수집 시각 ms)}] */
    function seriesOf(p) {
        const out = [];
        if (p.metric === 'res') {
            const s = allServers().find(x => String(x.id) === String(p.serverId));
            const h = s ? hist[s.id] : null;
            if (!h || !h.ts || !h.ts.length) return out;
            [['cpu', 'CPU'], ['mem', '메모리'], ['disk', '디스크']].forEach(k => {
                const v = (h[k[0]] || []).map(x => (x === null || x === undefined ? null : Number(x)));
                const w = windowed({ name: k[1], values: v, ts: h.ts.slice() });
                if (w) out.push(w);
            });
            return out;
        }
        if (p.metric === 'proc') {
            const s = (typeof serversData !== 'undefined' ? serversData : []).find(x => String(x.id) === String(p.serverId));
            const h = s ? hist[s.id] : null;
            if (!s || !h) return out;
            (s.processes || []).forEach(pr => {
                const ps = h.processes && h.processes[pr.id];
                if (ps && ps.cpu && ps.cpu.length) out.push({ name: pr.name, values: ps.cpu.slice(), ts: tail(h.ts, ps.cpu.length) });
            });
            Object.keys(h.services || {}).forEach(n => {
                const ps = h.services[n];
                if (ps && ps.cpu && ps.cpu.length) out.push({ name: n, values: ps.cpu.slice(), ts: tail(h.ts, ps.cpu.length) });
            });
            const inWin = out.map(windowed).filter(Boolean);
            inWin.sort((a, b) => last(b.values) - last(a.values));
            return inWin.slice(0, 12);
        }
        servers(p.site).forEach(s => {
            if (p.servers && p.servers.length && !p.servers.map(String).includes(String(s.id))) return;
            const h = hist[s.id];
            if (!h || !h.ts || !h.ts.length) return;
            const v = (h[p.metric] || []).map(x => (x === null || x === undefined ? null : Number(x)));
            const w = windowed({ name: s.systemName, values: v, ts: h.ts.slice(), down: s.status === 'Down' });
            if (w) out.push(w);
        });
        return out;
    }

    /** 조회 범위(지금부터 st.range 분) 안의 점만. 하나도 없으면 null */
    function windowed(s) {
        const from = Date.now() - st.range * 60000;
        const keep = s.ts.map((t, i) => (t >= from ? i : -1)).filter(i => i >= 0);
        if (!keep.length) return null;
        return Object.assign({}, s, { ts: keep.map(i => s.ts[i]), values: keep.map(i => s.values[i]) });
    }

    function tail(a, n) { return (a || []).slice(Math.max(0, (a || []).length - n)); }
    function nums(a) { return a.filter(v => v !== null && !isNaN(v)); }
    function last(a) { const n = nums(a); return n.length ? n[n.length - 1] : 0; }
    function fmt(v, unit) {
        if (v === null || v === undefined || isNaN(v)) return '-';
        return (Math.abs(v) >= 100 || Number.isInteger(v) ? Math.round(v) : v.toFixed(1)) + (unit || '');
    }

    function query(p) {
        const m = METRICS[p.metric];
        if (p.metric === 'proc' || p.metric === 'res') {
            const s = allServers().find(x => String(x.id) === String(p.serverId));
            return m.q + '{server="' + (s ? s.systemName : '?') + '"}';
        }
        const scope = p.site || (typeof processScope === 'string' && processScope !== 'all' ? processScope : '');
        const sc = scope ? 'location="' + scope.toUpperCase() + '"' : '';
        const names = servers(p.site).filter(s => !(p.servers && p.servers.length) || p.servers.map(String).includes(String(s.id))).map(s => s.systemName);
        const sv = st.sel.length || (p.servers && p.servers.length)
            ? 'server=~"' + (names.length <= 2 ? names.join('|') : names.length + '대 선택') + '"' : '';
        const lab = [sc, sv].filter(Boolean).join(',');
        return m.q + (lab ? '{' + lab + '}' : '');
    }

    function color(i) { return COLORS[i % COLORS.length]; }

    function threshold(p, v) {
        if (METRICS[p.metric].unit !== '%') return 'ok';
        return v >= 90 ? 'crit' : v >= 80 ? 'warn' : 'ok';
    }

    // ------------------------------------------------------------ 그리기 : 시계열 (선 / 영역)
    function timeSvg(p, ser, w, h) {
        const m = METRICS[p.metric];
        const hid = hidden[p.id] || {};
        const vis = ser.filter(s => !hid[s.name]);
        // 시간축 = 지금부터 거슬러 조회 범위 (Prometheus 처럼 빈 구간은 비워 둠)
        const t1 = Date.now(), t0 = t1 - st.range * 60000;
        let max = m.max;
        if (!max) {
            const all = nums([].concat(...vis.map(s => s.values)));
            const raw = all.length ? Math.max(...all) : 1;
            max = raw <= 5 ? Math.max(1, Math.ceil(raw)) : Math.ceil(raw * 1.15 / 5) * 5;
        }
        const L = 44, R = 10, T = 10, B = 22;
        const pw = w - L - R, ph = h - T - B;
        const x = t => L + ((t - t0) / (t1 - t0)) * pw;
        const y = v => T + ph - (Math.min(v, max) / max) * ph;
        // 수집 간격 (점 사이가 이 간격의 3배를 넘으면 선을 끊음 - Prometheus 처럼 빈 구간을 그대로 보여 줌)
        const gaps = [];
        ser.forEach(s => { for (let i = 1; i < s.ts.length; i++) gaps.push(s.ts[i] - s.ts[i - 1]); });
        gaps.sort((a, b) => a - b);
        const step = gaps.length ? Math.max(1000, gaps[Math.floor(gaps.length / 2)]) : 60000;
        let g = '';
        for (let k = 0; k <= 4; k++) {
            const v = (max * k) / 4;
            const yy = y(v);
            g += '<line class="mn-gl" x1="' + L + '" x2="' + (w - R) + '" y1="' + yy + '" y2="' + yy + '"/>' +
                '<text class="mn-ax" x="' + (L - 6) + '" y="' + (yy + 4) + '" text-anchor="end">' + fmt(v, m.unit) + '</text>';
        }
        const ticks = Math.max(2, Math.min(8, Math.floor(pw / 80)));
        for (let k = 0; k <= ticks; k++) {
            const t = t0 + ((t1 - t0) * k) / ticks;
            g += '<text class="mn-ax" x="' + x(t) + '" y="' + (h - 6) + '" text-anchor="' + (k === 0 ? 'start' : k === ticks ? 'end' : 'middle') + '">' + hhmm(t) + '</text>';
        }
        if (METRICS[p.metric].unit === '%' && max >= 80) {
            g += '<line class="mn-th warn" x1="' + L + '" x2="' + (w - R) + '" y1="' + y(80) + '" y2="' + y(80) + '"/>' +
                '<line class="mn-th crit" x1="' + L + '" x2="' + (w - R) + '" y1="' + y(90) + '" y2="' + y(90) + '"/>';
        }
        ser.forEach((s, si) => {
            if (hid[s.name]) return;
            const segs = [];
            let cur = [];
            s.values.forEach((v, i) => {
                const t = s.ts[i];
                if (v === null || isNaN(v) || t === undefined) { if (cur.length) segs.push(cur); cur = []; return; }
                if (cur.length && t - cur[cur.length - 1][0] > step * 3) { segs.push(cur); cur = []; }
                cur.push([t, v]);
            });
            if (cur.length) segs.push(cur);
            segs.forEach(seg => {
                if (seg.length === 1) {
                    g += '<circle cx="' + x(seg[0][0]).toFixed(1) + '" cy="' + y(seg[0][1]).toFixed(1) + '" r="2" fill="' + color(si) + '"/>';
                    return;
                }
                const d = seg.map((pt, i) => (i ? 'L' : 'M') + x(pt[0]).toFixed(1) + ',' + y(pt[1]).toFixed(1)).join('');
                if (p.type === 'area') {
                    g += '<path d="' + d + 'L' + x(seg[seg.length - 1][0]).toFixed(1) + ',' + (T + ph) + 'L' + x(seg[0][0]).toFixed(1) + ',' + (T + ph) +
                        'Z" fill="' + color(si) + '" opacity="0.14"/>';
                }
                g += '<path d="' + d + '" fill="none" stroke="' + color(si) + '" stroke-width="1.8" stroke-linejoin="round"' + (s.down ? ' stroke-dasharray="4 3"' : '') + '/>';
            });
        });
        g += '<line class="mn-cross" x1="0" x2="0" y1="' + T + '" y2="' + (T + ph) + '" visibility="hidden"/>';
        g += '<rect class="mn-hit" x="' + L + '" y="' + T + '" width="' + pw + '" height="' + ph + '" fill="transparent"/>';
        return {
            svg: '<svg class="mn-svg" viewBox="0 0 ' + w + ' ' + h + '" width="' + w + '" height="' + h + '" role="img" aria-label="' + esc(p.title) + ' 그래프">' + g + '</svg>',
            geo: { L, pw, t0, t1, step }
        };
    }

    function hhmm(t) {
        const d = new Date(t);
        return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    }

    // 막대 / 게이지 : 계열마다 현재값
    function barHtml(p, ser) {
        const m = METRICS[p.metric];
        const vals = ser.map(s => last(s.values));
        const max = m.max || Math.max(1, ...vals) * 1.1;
        return '<div class="mn-bars">' + ser.map((s, i) => {
            const v = vals[i];
            const lv = threshold(p, v);
            return '<div class="mn-bar-row"><span class="mn-bar-name" title="' + esc(s.name) + '">' + esc(s.name) + '</span>' +
                '<span class="mn-bar-track"><i class="mn-bar-fill ' + lv + '" style="width:' + Math.min(100, (v / max) * 100).toFixed(1) + '%' +
                (lv === 'ok' && m.unit !== '%' ? ';background:' + color(i) : '') + '"></i></span>' +
                '<b class="mn-bar-val ' + lv + '">' + fmt(v, m.unit) + '</b></div>';
        }).join('') + '</div>';
    }

    function gaugeHtml(p, ser) {
        const m = METRICS[p.metric];
        const vals = ser.map(s => last(s.values));
        const max = m.max || Math.max(1, ...vals) * 1.1;
        return '<div class="mn-gauges">' + ser.map((s, i) => {
            const v = vals[i];
            const r = Math.max(0, Math.min(1, v / max));
            const lv = threshold(p, v);
            const a = Math.PI * (1 - r);
            const ex = 50 + 40 * Math.cos(a), ey = 50 - 40 * Math.sin(a);
            return '<div class="mn-gauge" title="' + esc(s.name) + '"><svg viewBox="0 0 100 58" width="120" height="70" aria-hidden="true">' +
                '<path d="M10,50 A40,40 0 0 1 90,50" class="mn-g-bg"/>' +
                (r > 0 ? '<path d="M10,50 A40,40 0 0 1 ' + ex.toFixed(1) + ',' + ey.toFixed(1) + '" class="mn-g-val ' + lv + '"' +
                    (lv === 'ok' && m.unit !== '%' ? ' style="stroke:' + color(i) + '"' : '') + '/>' : '') +
                '</svg><b class="mn-g-num ' + lv + '">' + fmt(v, m.unit) + '</b><span>' + esc(s.name) + '</span></div>';
        }).join('') + '</div>';
    }

    function legendHtml(p, ser) {
        const m = METRICS[p.metric];
        const hid = hidden[p.id] || {};
        return '<table class="mn-legend"><thead><tr><th>계열</th><th>현재</th><th>최대</th><th>평균</th></tr></thead><tbody>' +
            ser.map((s, i) => {
                const v = nums(s.values);
                const avg = v.length ? v.reduce((a, b) => a + b, 0) / v.length : null;
                return '<tr class="' + (hid[s.name] ? 'off' : '') + '" data-series="' + esc(s.name) + '" tabindex="0" title="눌러서 숨기기 / 보이기">' +
                    '<td><i style="background:' + color(i) + '"></i>' + esc(s.name) + (s.down ? ' <small class="mn-down">DOWN</small>' : '') + '</td>' +
                    '<td>' + fmt(v.length ? v[v.length - 1] : null, m.unit) + '</td><td>' + fmt(v.length ? Math.max(...v) : null, m.unit) + '</td>' +
                    '<td>' + fmt(avg, m.unit) + '</td></tr>';
            }).join('') + '</tbody></table>';
    }

    function panelBody(p, w, h) {
        const ser = seriesOf(p);
        if (!ser.length) {
            return { html: '<div class="mn-empty">' + (p.metric === 'proc' && !p.serverId ? '패널 편집에서 서버를 고르세요.'
                : '조회 범위(' + (RANGES.find(r => r[0] === st.range) || [0, ''])[1] + ') 안에 수집값이 없습니다. 에이전트(svr_collect_client)가 보고 중인 서버만 그려집니다. 범위를 늘려 보세요.') + '</div>', geo: null, ser };
        }
        if (p.type === 'bar') return { html: barHtml(p, ser), geo: null, ser };
        if (p.type === 'gauge') return { html: gaugeHtml(p, ser), geo: null, ser };
        const t = timeSvg(p, ser, w, h);
        return { html: '<div class="mn-plot">' + t.svg + '<div class="mn-tip" hidden></div></div>' + legendHtml(p, ser), geo: t.geo, ser };
    }

    // ------------------------------------------------------------ 화면
    function statHtml() {
        const list = servers();
        const conn = list.filter(s => typeof isServerConnected === 'function' && isServerConnected(s));
        const cnt = k => list.filter(s => s.status === k).length;
        const lastVals = k => conn.map(s => { const h = hist[s.id]; return h ? last((h[k] || []).map(Number)) : null; }).filter(v => v !== null);
        const cpus = lastVals('cpu');
        const disks = conn.map(s => { const h = hist[s.id]; return h ? { s, v: last((h.disk || []).map(Number)) } : null; }).filter(Boolean);
        const topDisk = disks.sort((a, b) => b.v - a.v)[0];
        const avgCpu = cpus.length ? cpus.reduce((a, b) => a + b, 0) / cpus.length : null;
        const scope = typeof processScope === 'string' && processScope !== 'all' ? processScope.toUpperCase() : '전체';
        const items = [
            ['서버', list.length + '대', 'info', st.sel.length ? '선택한 서버 (' + scope + ')' : '전체 (' + scope + ')'],
            ['Active', cnt('Active'), cnt('Active') ? 'ok' : 'info', '정상 운영'],
            ['Standby', cnt('Standby'), cnt('Standby') ? 'warn' : 'info', '대기'],
            ['Down', cnt('Down'), cnt('Down') ? 'crit' : 'ok', cnt('Down') ? '즉시 조치 필요' : '없음'],
            ['에이전트 연결', conn.length + ' / ' + list.length, conn.length === list.length ? 'ok' : 'warn', 'svr_collect_client'],
            ['평균 CPU', fmt(avgCpu, '%'), avgCpu === null ? 'info' : threshold({ metric: 'cpu' }, avgCpu), '연결 서버 현재값'],
            ['최고 디스크', topDisk ? fmt(topDisk.v, '%') : '-', topDisk ? threshold({ metric: 'disk' }, topDisk.v) : 'info', topDisk ? topDisk.s.systemName : '-']
        ];
        return items.map(i => '<div class="mn-stat ' + i[2] + '"><span class="mn-stat-k">' + esc(i[0]) + '</span>' +
            '<b class="mn-stat-v">' + esc(i[1]) + '</b><span class="mn-stat-s">' + esc(i[3]) + '</span></div>').join('');
    }

    /** 지금 보기의 패널 : 패널 보기 = 저장된 패널, 서버별 나눠 보기 = 선택 서버마다 자원 패널 1개 (Grafana 의 패널 반복) */
    function curPanels() {
        if (st.view === 'site') {
            // 사이트별 : 같은 패널 정의를 ITC / DRC 마다 한 번씩 (정의는 하나라 편집·삭제도 한 번에), 프로세스 패널은 그 서버의 사이트에만
            const out = [];
            const scope = typeof processScope === 'string' ? processScope : 'all';
            SITES.filter(si => scope === 'all' || scope === si[0]).forEach(si => {
                out.push({ head: true, id: 'site-' + si[0], site: si[0], title: si[1] });
                // 조회 범위 안 수집값이 없는 사이트는 빈 패널을 여러 개 그리지 않고 안내 한 줄로 합침
                const from = Date.now() - st.range * 60000;
                const hasData = servers(si[0]).some(s => { const h = hist[s.id]; return h && h.ts && h.ts.length && h.ts[h.ts.length - 1] >= from; });
                if (!hasData) {
                    out.push({ note: true, id: 'note-' + si[0], site: si[0], title: si[1] });
                } else {
                    st.panels.forEach(p => {
                        if (p.metric === 'proc') {
                            const s = allServers().find(x => String(x.id) === String(p.serverId));
                            if (!s || siteOf(s) !== si[0]) return;
                        }
                        out.push(Object.assign({}, p, { id: p.id + '@' + si[0], baseId: p.id, site: si[0] }));
                    });
                }
                // 그 사이트의 서버 카드 (script.js renderProcessSection 이 이 칸에 그림)
                out.push({ cards: true, id: 'cards-' + si[0], site: si[0] });
            });
            return out;
        }
        if (st.view !== 'split') return st.panels;
        return servers().map(s => ({
            id: 'srv-' + s.id, title: s.systemName, metric: 'res', type: st.splitType || 'line', serverId: String(s.id), split: true
        }));
    }

    function findPanel(id) {
        return curPanels().find(x => x.id === id) || st.panels.find(x => x.id === id);
    }

    /** 사이트별 보기의 복사 패널 → 저장된 원래 패널 */
    function basePanel(p) {
        return p && p.baseId ? st.panels.find(x => x.id === p.baseId) : p;
    }

    /** 사이트 머리 : 사이트 이름 + 서버 수 · 상태 · 연결 · 평균 CPU · 최고 디스크 */
    function siteHeadHtml(p) {
        const list = servers(p.site);
        const conn = list.filter(s => typeof isServerConnected === 'function' && isServerConnected(s));
        const curOf = (s, k) => {
            const h = hist[s.id];
            return h && h.ts && h.ts.length && h.ts[h.ts.length - 1] >= Date.now() - st.range * 60000 ? last((h[k] || []).map(Number)) : null;
        };
        const cpus = list.map(s => curOf(s, 'cpu')).filter(v => v !== null);
        const disks = list.map(s => ({ s, v: curOf(s, 'disk') })).filter(x => x.v !== null).sort((a, b) => b.v - a.v);
        const avg = cpus.length ? cpus.reduce((a, b) => a + b, 0) / cpus.length : null;
        const down = list.filter(s => s.status === 'Down').length;
        return '<div class="mn-site-head ' + esc(p.site) + '" role="heading" aria-level="4">' +
            '<b>' + esc(p.title) + '</b>' +
            '<span>서버 <b>' + list.length + '</b>대</span>' +
            '<span class="' + (down ? 'crit' : '') + '">Down <b>' + down + '</b></span>' +
            '<span>에이전트 연결 <b>' + conn.length + ' / ' + list.length + '</b></span>' +
            '<span class="' + (avg === null ? '' : threshold({ metric: 'cpu' }, avg)) + '">평균 CPU <b>' + fmt(avg, '%') + '</b></span>' +
            '<span class="' + (disks.length ? threshold({ metric: 'disk' }, disks[0].v) : '') + '">최고 디스크 <b>' +
            (disks.length ? fmt(disks[0].v, '%') + '</b> (' + esc(disks[0].s.systemName) + ')' : '-</b>') + '</span></div>';
    }

    /** 서버별 패널 머리 : 상태 · IP · 현재 자원 · 세션 */
    function splitHeadHtml(p) {
        const s = allServers().find(x => String(x.id) === p.serverId);
        if (!s) return '';
        const h = hist[s.id];
        const cur = k => (h && h.ts && h.ts.length && h.ts[h.ts.length - 1] >= Date.now() - st.range * 60000 ? last((h[k] || []).map(Number)) : null);
        const lv = (k, v) => (v === null ? '' : ' ' + threshold({ metric: k }, v));
        const cpu = cur('cpu'), mem = cur('mem'), disk = cur('disk'), ses = cur('sessions');
        return '<div class="mn-srv-meta"><span class="mn-srv-st ' + esc(s.status) + '">' + esc(s.status) + '</span>' +
            '<span>' + esc(s.ip) + (s.location === 'DRC' ? ' · DRC' : ' · ITC') + '</span>' +
            '<span class="mn-srv-v' + lv('cpu', cpu) + '">CPU <b>' + fmt(cpu, '%') + '</b></span>' +
            '<span class="mn-srv-v' + lv('mem', mem) + '">MEM <b>' + fmt(mem, '%') + '</b></span>' +
            '<span class="mn-srv-v' + lv('disk', disk) + '">DISK <b>' + fmt(disk, '%') + '</b></span>' +
            '<span class="mn-srv-v">세션 <b>' + fmt(ses, '') + '</b></span></div>';
    }

    function panelHtml(p) {
        if (p.head) return siteHeadHtml(p);
        if (p.cards) return '<div class="mn-site-cards" id="mnCards-' + esc(p.site) + '"></div>';
        if (p.note) {
            return '<div class="mn-site-empty" role="status">' + esc(p.title) + ' : 조회 범위(' + esc((RANGES.find(r => r[0] === st.range) || [0, ''])[1]) +
                ') 안에 수집값이 있는 서버가 없어 그래프 ' + st.panels.length + '개를 생략했습니다. 에이전트(svr_collect_client)가 보고를 시작하면 이 사이트의 그래프가 나타납니다.</div>';
        }
        if (p.split) {
            return '<article class="mn-panel mn-split" data-id="' + esc(p.id) + '">' +
                '<header class="mn-panel-head"><div class="mn-panel-title">' + esc(p.title) +
                '<code class="mn-q" title="지표 (Prometheus 표기)">' + esc(query(p)) + '</code></div>' +
                '<div class="mn-panel-tools">' +
                '<select class="mn-type" aria-label="' + esc(p.title) + ' 표시 방식">' + TYPES.map(t => '<option value="' + t[0] + '"' + (t[0] === p.type ? ' selected' : '') + '>' + t[1] + '</option>').join('') + '</select>' +
                '<button type="button" data-act="zoom" title="크게 보기" aria-label="크게 보기">⤢</button>' +
                '<button type="button" data-act="only" title="이 서버만 보기" aria-label="' + esc(p.title) + '만 보기">◎</button>' +
                '<button type="button" data-act="proc" title="이 서버의 프로세스·서비스 CPU 패널 추가" aria-label="프로세스 CPU 패널 추가">⚙</button></div></header>' +
                splitHeadHtml(p) + '<div class="mn-panel-body"></div></article>';
        }
        return '<article class="mn-panel" data-id="' + esc(p.id) + '">' +
            '<header class="mn-panel-head"><div class="mn-panel-title">' + esc(p.title) +
            '<code class="mn-q" title="지표 (Prometheus 표기)">' + esc(query(p)) + '</code></div>' +
            '<div class="mn-panel-tools">' +
            '<select class="mn-type" aria-label="' + esc(p.title) + ' 표시 방식">' + TYPES.map(t => '<option value="' + t[0] + '"' + (t[0] === p.type ? ' selected' : '') + '>' + t[1] + '</option>').join('') + '</select>' +
            '<button type="button" data-act="zoom" title="크게 보기" aria-label="크게 보기">⤢</button>' +
            '<button type="button" data-act="edit" title="편집" aria-label="편집">✎</button>' +
            '<button type="button" data-act="dup" title="복제" aria-label="복제">⧉</button>' +
            '<button type="button" data-act="del" title="삭제" aria-label="삭제">✕</button></div></header>' +
            '<div class="mn-panel-body"></div></article>';
    }

    function drawPanel(el, p, big) {
        const body = el.querySelector('.mn-panel-body') || el;
        const w = Math.max(260, Math.floor(body.clientWidth || 500));
        const h = big ? Math.max(320, Math.floor(window.innerHeight * 0.55)) : 210;
        const r = panelBody(p, w, h);
        body.innerHTML = r.html;
        if (r.geo) bindHover(body, p, r.ser, r.geo, w);
    }

    function render() {
        const root = $('mnDash');
        if (!root || !st) return;
        const dc = $('dashboard-container');
        if (dc) dc.setAttribute('data-mnbg', st.bg);
        root.classList.toggle('folded', st.folded);
        $('mnFold').textContent = st.folded ? '펼치기' : '접기';
        $('mnFold').setAttribute('aria-expanded', String(!st.folded));
        $('mnStats').innerHTML = statHtml();
        renderSel();
        const grid = $('mnGrid');
        grid.style.setProperty('--mn-cols', st.cols);
        const panels = curPanels().filter(p => !(p.cards && st.folded));
        grid.innerHTML = panels.map(panelHtml).join('') || (st.view === 'split'
            ? '<div class="mn-empty">선택한 서버가 없습니다. [서버] 에서 서버를 고르세요.</div>'
            : '<div class="mn-empty">패널이 없습니다. [+ 패널 추가] 를 누르세요.</div>');
        if (!st.folded) {
            grid.querySelectorAll('.mn-panel').forEach(el => {
                const p = panels.find(x => x.id === el.dataset.id);
                if (p) drawPanel(el, p, false);
            });
        }
        $('mnUpdated').textContent = lastLoad ? '갱신 ' + lastLoad.toTimeString().slice(0, 8) : '';
        // 서버 카드 : 사이트별 보기면 각 사이트 그래프 아래 칸으로, 아니면 원래 자리(#processGroups)로
        if (typeof renderProcessSection === 'function' && typeof serversData !== 'undefined') renderProcessSection();
        if (zoomId) drawZoom();
    }

    function bindHover(body, p, ser, geo, w) {
        const svg = body.querySelector('svg');
        const tip = body.querySelector('.mn-tip');
        const cross = svg.querySelector('.mn-cross');
        const hit = svg.querySelector('.mn-hit');
        const m = METRICS[p.metric];
        const move = ev => {
            const rect = svg.getBoundingClientRect();
            const sx = (ev.clientX - rect.left) * (w / rect.width);
            const t = geo.t0 + ((sx - geo.L) / geo.pw) * (geo.t1 - geo.t0);
            const hid = hidden[p.id] || {};
            // 계열마다 마우스 시각에 가장 가까운 점 (수집 간격의 1.5배 안에서만)
            const rows = [];
            ser.forEach((s, si) => {
                if (hid[s.name]) return;
                let best = -1, bd = Infinity;
                s.ts.forEach((tt, i) => { const d = Math.abs(tt - t); if (d < bd) { bd = d; best = i; } });
                if (best >= 0 && bd <= geo.step * 1.5 && s.values[best] !== null) rows.push({ s, si, v: s.values[best], t: s.ts[best] });
            });
            rows.sort((a, b) => b.v - a.v);
            const tt = rows.length ? rows[0].t : t;
            const px = geo.L + ((tt - geo.t0) / (geo.t1 - geo.t0)) * geo.pw;
            cross.setAttribute('x1', px); cross.setAttribute('x2', px); cross.setAttribute('visibility', 'visible');
            const dt = new Date(tt);
            tip.innerHTML = '<b>' + (dt.getMonth() + 1) + '/' + dt.getDate() + ' ' + dt.toTimeString().slice(0, 8) + '</b>' +
                (rows.length ? rows.map(r => '<div><i style="background:' + color(r.si) + '"></i>' + esc(r.s.name) + ' <span>' + fmt(r.v, m.unit) + '</span></div>').join('')
                    : '<div>이 시각의 수집값 없음</div>');
            tip.hidden = false;
            const left = (px / w) * rect.width;
            tip.style.left = (left > rect.width / 2 ? left - tip.offsetWidth - 12 : left + 12) + 'px';
        };
        hit.addEventListener('mousemove', move);
        hit.addEventListener('mouseleave', () => { tip.hidden = true; cross.setAttribute('visibility', 'hidden'); });
    }

    // ------------------------------------------------------------ 확대
    function drawZoom() {
        const p = findPanel(zoomId);
        if (!p) { closeZoom(); return; }
        $('mnZoomTitle').textContent = p.title + (p.site ? ' · ' + (SITES.find(x => x[0] === p.site) || ['', ''])[1] : '');
        $('mnZoomQuery').textContent = query(p);
        drawPanel($('mnZoomBody'), p, true);
    }

    function openZoom(id) {
        zoomId = id;
        $('mnZoom').classList.add('show');
        drawZoom();
    }

    function closeZoom() {
        zoomId = null;
        $('mnZoom').classList.remove('show');
    }

    // ------------------------------------------------------------ 패널 편집
    function openEdit(id) {
        editId = id;
        const p = id ? st.panels.find(x => x.id === id) : { title: '', metric: 'cpu', type: 'line', servers: [] };
        $('mnEditTitle').textContent = id ? '패널 편집' : '패널 추가';
        $('mnEdName').value = p.title || '';
        $('mnEdMetric').value = p.metric;
        $('mnEdType').value = p.type;
        const all = typeof serversData !== 'undefined' ? serversData : [];
        $('mnEdServer').innerHTML = all.map(s => '<option value="' + s.id + '"' + (String(s.id) === String(p.serverId) ? ' selected' : '') + '>' +
            esc(s.systemName) + ' (' + esc(s.ip) + ')</option>').join('');
        $('mnEdServers').innerHTML = all.map(s => '<label><input type="checkbox" value="' + s.id + '"' +
            ((p.servers || []).map(String).includes(String(s.id)) ? ' checked' : '') + '> ' + esc(s.systemName) +
            (hist[s.id] ? '' : ' <small>(이력 없음)</small>') + '</label>').join('');
        syncEdit();
        $('mnEdit').classList.add('show');
        $('mnEdName').focus();
    }

    function syncEdit() {
        const proc = $('mnEdMetric').value === 'proc';
        $('mnEdServerRow').hidden = !proc;
        $('mnEdServersRow').hidden = proc;
    }

    function closeEdit() { $('mnEdit').classList.remove('show'); }

    function saveEdit() {
        const metric = $('mnEdMetric').value;
        const title = $('mnEdName').value.trim() || METRICS[metric].name;
        const servers = Array.from($('mnEdServers').querySelectorAll('input:checked')).map(i => i.value);
        const data = { title: title.slice(0, 40), metric, type: $('mnEdType').value, servers, serverId: metric === 'proc' ? $('mnEdServer').value : undefined };
        if (editId) Object.assign(st.panels.find(x => x.id === editId), data);
        else st.panels.push(Object.assign({ id: 'p' + Date.now().toString(36) }, data));
        save();
        closeEdit();
        render();
    }

    // ------------------------------------------------------------ 서버 선택 (대시보드 전체)
    function serverState(s) {
        const h = hist[s.id];
        const recent = h && h.ts && h.ts.length && h.ts[h.ts.length - 1] >= Date.now() - st.range * 60000;
        return recent ? '' : ' <small>(범위 안 수집값 없음)</small>';
    }

    function renderSel() {
        const all = allServers();
        const sel = st.sel.filter(id => all.some(s => String(s.id) === id));
        const names = sel.map(id => all.find(s => String(s.id) === id).systemName);
        $('mnSrvBtn').innerHTML = '서버 : <b>' + (sel.length ? (sel.length === 1 ? esc(names[0]) : sel.length + '대 선택') : '전체') + '</b> ▾';
        $('mnSrvBtn').classList.toggle('on', sel.length > 0);
        // 고른 서버 칩 (✕ 로 빼기)
        $('mnChips').hidden = !sel.length;
        $('mnChips').innerHTML = sel.length ? '<span class="mn-chips-k">선택한 서버</span>' + sel.map((id, i) =>
            '<span class="mn-chip">' + esc(names[i]) + '<button type="button" data-unsel="' + esc(id) + '" aria-label="' + esc(names[i]) + ' 빼기">✕</button></span>').join('') +
            '<button type="button" class="mn-chip-all" data-unsel="*">전체 보기</button>' : '';
        if (!$('mnSrvPop').hidden) renderSelList();
    }

    function renderSelList() {
        const q = ($('mnSrvSearch').value || '').trim().toLowerCase();
        const groups = [['ITC · GASAN MTR-A', s => s.location !== 'DRC'], ['DRC', s => s.location === 'DRC']];
        $('mnSrvList').innerHTML = groups.map(g => {
            const list = allServers().filter(g[1]).filter(s => !q || (s.systemName + ' ' + s.ip).toLowerCase().includes(q));
            if (!list.length) return '';
            return '<li class="mn-srv-group">' + g[0] + '</li>' + list.map(s =>
                '<li><label><input type="checkbox" value="' + s.id + '"' + (st.sel.includes(String(s.id)) ? ' checked' : '') + '>' +
                '<i class="mn-dot ' + esc(s.status) + '" title="' + esc(s.status) + '"></i>' + esc(s.systemName) +
                ' <small>' + esc(s.ip) + '</small>' + serverState(s) + '</label>' +
                '<button type="button" class="mn-only" data-only="' + s.id + '">이 서버만</button></li>').join('');
        }).join('') || '<li class="mn-srv-group">찾는 서버가 없습니다.</li>';
    }

    function openSel(open) {
        $('mnSrvPop').hidden = !open;
        $('mnSrvBtn').setAttribute('aria-expanded', String(open));
        if (open) { $('mnSrvSearch').value = ''; renderSelList(); $('mnSrvSearch').focus(); }
    }

    function setSel(ids) {
        st.sel = ids.map(String);
        save();
        render();
    }

    function bindSel() {
        $('mnSrvBtn').addEventListener('click', e => { e.stopPropagation(); openSel($('mnSrvPop').hidden); });
        $('mnSrvSearch').addEventListener('input', renderSelList);
        $('mnSrvList').addEventListener('change', e => {
            const cb = e.target.closest('input[type=checkbox]');
            if (!cb) return;
            const set = new Set(st.sel);
            if (cb.checked) set.add(cb.value); else set.delete(cb.value);
            setSel(Array.from(set));
        });
        $('mnSrvList').addEventListener('click', e => {
            const b = e.target.closest('[data-only]');
            if (b) { setSel([b.dataset.only]); openSel(false); }
        });
        $('mnSrvAll').addEventListener('click', () => setSel([]));
        $('mnSrvConn').addEventListener('click', () => setSel(allServers().filter(s => {
            const h = hist[s.id];
            return h && h.ts && h.ts.length && h.ts[h.ts.length - 1] >= Date.now() - st.range * 60000;
        }).map(s => s.id)));
        $('mnSrvDone').addEventListener('click', () => openSel(false));
        $('mnChips').addEventListener('click', e => {
            const b = e.target.closest('[data-unsel]');
            if (!b) return;
            setSel(b.dataset.unsel === '*' ? [] : st.sel.filter(id => id !== b.dataset.unsel));
        });
        document.addEventListener('click', e => {
            if (!$('mnSrvPop').hidden && !e.target.closest('.mn-srv-wrap')) openSel(false);
        });
        document.addEventListener('keydown', e => {
            if (e.key === 'Escape' && !$('mnSrvPop').hidden) { openSel(false); $('mnSrvBtn').focus(); e.stopImmediatePropagation(); }
        });
        $('mnView').addEventListener('change', e => { st.view = e.target.value; save(); render(); });
    }

    // ------------------------------------------------------------ 자동 갱신
    function schedule() {
        if (timer) { clearInterval(timer); timer = null; }
        if (st.refresh > 0) {
            timer = setInterval(async () => {
                if (document.hidden) return;
                await fetchHistory();
                render();
            }, st.refresh * 1000);
        }
    }

    async function reload() {
        $('mnReload').disabled = true;
        await fetchHistory();
        render();
        $('mnReload').disabled = false;
    }

    // ------------------------------------------------------------ 시작
    function fillSelect(id, opts, val) {
        $(id).innerHTML = opts.map(o => '<option value="' + o[0] + '"' + (String(o[0]) === String(val) ? ' selected' : '') + '>' + o[1] + '</option>').join('');
    }

    async function init() {
        if (!$('mnDash') || st) return;
        st = load();
        fillSelect('mnBg', BGS, st.bg);
        fillSelect('mnRange', RANGES, st.range);
        fillSelect('mnRefresh', REFRESH, st.refresh);
        fillSelect('mnCols', [[1, '1열'], [2, '2열'], [3, '3열']], st.cols);
        fillSelect('mnEdMetric', Object.keys(METRICS).filter(k => k !== 'res').map(k => [k, METRICS[k].name]), 'cpu');
        fillSelect('mnView', VIEWS, st.view);
        fillSelect('mnEdType', TYPES, 'line');

        $('mnBg').addEventListener('change', e => { st.bg = e.target.value; save(); render(); });
        $('mnRange').addEventListener('change', async e => { st.range = Number(e.target.value); save(); await reload(); });
        $('mnRefresh').addEventListener('change', e => { st.refresh = Number(e.target.value); save(); schedule(); });
        $('mnCols').addEventListener('change', e => { st.cols = Number(e.target.value); save(); render(); });
        $('mnReload').addEventListener('click', reload);
        $('mnAdd').addEventListener('click', () => openEdit(null));
        $('mnFold').addEventListener('click', () => { st.folded = !st.folded; save(); render(); });
        $('mnResetPanels').addEventListener('click', async () => {
            if (!(await scbConfirm('패널 구성을 기본(CPU · 메모리 · 디스크 · 세션)으로 되돌리시겠습니까?', { type: 'warning' }))) return;
            st.panels = DEFAULT_PANELS.map(p => Object.assign({}, p));
            save();
            render();
        });
        bindSel();
        // [전체 / ITC / DRC] · [카드 순서 초기화] 는 대시보드 위 도구 줄로 옮김 (카드가 사이트 그래프 아래로 들어가므로)
        const scopeWrap = document.querySelector('#processSection .proc-scope-wrap');
        if (scopeWrap) { scopeWrap.classList.add('mn-scope'); $('mnDash').querySelector('.mn-bar').insertAdjacentElement('afterend', scopeWrap); }
        $('mnEdMetric').addEventListener('change', syncEdit);
        $('mnEdCancel').addEventListener('click', closeEdit);
        $('mnEdSave').addEventListener('click', saveEdit);
        $('mnZoomClose').addEventListener('click', closeZoom);
        document.addEventListener('keydown', e => {
            if (e.key !== 'Escape' || !$('mnSrvPop').hidden) return;
            if ($('mnEdit').classList.contains('show')) closeEdit();
            else if (zoomId) closeZoom();
        });

        $('mnGrid').addEventListener('change', e => {
            const sel = e.target.closest('.mn-type');
            if (!sel) return;
            const p = findPanel(sel.closest('.mn-panel').dataset.id);
            if (p.split) { st.splitType = sel.value; save(); render(); return; }   // 서버별 패널은 모두 같은 방식
            if (p.baseId) { basePanel(p).type = sel.value; save(); render(); return; }   // 사이트별 : ITC · DRC 함께
            p.type = sel.value;
            save();
            drawPanel(sel.closest('.mn-panel'), p, false);
        });
        $('mnGrid').addEventListener('click', async e => {
            const btn = e.target.closest('button[data-act]');
            const row = e.target.closest('tr[data-series]');
            const el = e.target.closest('.mn-panel');
            if (!el) return;
            const p = findPanel(el.dataset.id);
            if (row) { toggleSeries(p, row.dataset.series, el); return; }
            if (!btn) return;
            const act = btn.dataset.act;
            if (act === 'zoom') openZoom(p.id);
            else if (act === 'only') { st.sel = [p.serverId]; save(); render(); }
            else if (act === 'proc') {
                st.panels.push({ id: 'p' + Date.now().toString(36), title: p.title + ' 프로세스 CPU', metric: 'proc', type: 'line', servers: [], serverId: p.serverId });
                st.view = 'site';
                $('mnView').value = 'site';
                save(); render();
                scbAlert('"' + p.title + '" 의 프로세스·서비스 CPU 패널을 추가했습니다. (그 서버 사이트의 맨 아래)', 'success');
            }
            else if (act === 'edit') openEdit(basePanel(p).id);
            else if (act === 'dup') {
                const b = basePanel(p);
                st.panels.splice(st.panels.indexOf(b) + 1, 0, Object.assign({}, b, { id: 'p' + Date.now().toString(36), title: b.title + ' (복제)' }));
                save(); render();
            } else if (act === 'del') {
                const b = basePanel(p);
                if (!(await scbConfirm('"' + b.title + '" 패널을 삭제하시겠습니까?' + (p.baseId ? ' (ITC · DRC 모두에서 빠집니다)' : '')))) return;
                st.panels = st.panels.filter(x => x !== b);
                save(); render();
            }
        });
        $('mnGrid').addEventListener('keydown', e => {
            const row = e.target.closest('tr[data-series]');
            if (!row || (e.key !== 'Enter' && e.key !== ' ')) return;
            e.preventDefault();
            const el = row.closest('.mn-panel');
            toggleSeries(findPanel(el.dataset.id), row.dataset.series, el);
        });
        $('mnZoomBody').addEventListener('click', e => {
            const row = e.target.closest('tr[data-series]');
            if (!row || !zoomId) return;
            const p = findPanel(zoomId);
            hidden[p.id] = hidden[p.id] || {};
            hidden[p.id][row.dataset.series] = !hidden[p.id][row.dataset.series];
            render();
        });

        let rt = null;
        window.addEventListener('resize', () => { clearTimeout(rt); rt = setTimeout(render, 200); });

        // 상단 [전체 / ITC / DRC] 를 대시보드에도 적용
        if (typeof window.setProcessScope === 'function') {
            const orig = window.setProcessScope;
            window.setProcessScope = function (scope) { orig(scope); render(); };
        }
        // 서버 상태가 바뀌면(하트비트) 요약 지표 갱신
        if (typeof onHeartbeatChange === 'function') onHeartbeatChange(() => { $('mnStats').innerHTML = statHtml(); });

        render();
        await fetchHistory();
        render();
        schedule();
    }

    function toggleSeries(p, name, el) {
        hidden[p.id] = hidden[p.id] || {};
        hidden[p.id][name] = !hidden[p.id][name];
        drawPanel(el, p, false);
    }

    window.mnDashInit = init;
})();
