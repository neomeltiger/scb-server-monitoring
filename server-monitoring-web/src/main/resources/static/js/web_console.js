// ================= 웹 콘솔 (/console, templates/web_console.html) =================
// 네트워크 구성도에서 서버 노드를 클릭하면 열리는 원격 접속(SSH) 화면입니다.
//
// [접속 방식] - 서버의 /api/console-config (application.yml 의 app.console.mode) 로 결정됩니다.
//  - live : 실제 접속 모드 (같은 서버의 WebSocket /ssh → SSH, console/SshWebSocketHandler.java)
//  - demo : 데모 모드 (브라우저 안에서 서버 셸을 흉내냄, 실제 접속 없음)
//  터미널은 오픈소스 xterm.js(static/vendor/xterm)를 사용합니다.
//
// [접속 대상] (URL 파라미터)
//  - /console?id=<서버ID>&port=<포트>    : 등록된 서버 (네트워크 구성도에서 선택)
//  - /console?host=<주소>&port=<포트>   : 주소 직접 입력
//  - 파라미터가 없으면 주소/포트 입력 화면을 표시합니다.
//
// [게이트웨이 프로토콜]
//  접속  : ws(s)://<웹서버>/ssh?host=<IP>&port=<포트>&user=<계정>&cols=<열>&rows=<행>
//  송신  : {"type":"data","data":"<키 입력>"} / {"type":"resize","cols":N,"rows":N}  (JSON 텍스트)
//  수신  : 터미널 출력 (텍스트 또는 바이너리 UTF-8)
//  비밀번호는 URL에 넣지 않고, 터미널에서 입력받아 게이트웨이가 처리합니다.
let CONSOLE_GATEWAY_URL = '';     // 실제 접속 모드일 때 WebSocket 주소 (loadConsoleConfig 에서 설정, 비어 있으면 데모)
const CONSOLE_IDLE_TIMEOUT_MIN = 15; // 무입력 시 자동 접속 종료 (분)
const CONSOLE_FONT_KEY = 'webConsoleFontSize';

const C = { reset: '\x1b[0m', bold: '\x1b[1m', red: '\x1b[31m', green: '\x1b[32m', yellow: '\x1b[33m', blue: '\x1b[34m', cyan: '\x1b[36m', gray: '\x1b[90m' };

let wcServer = null;        // 접속 대상 (등록 서버 또는 직접 입력한 주소)
let wcPort = DEFAULT_SSH_PORT;
let term = null;
let fitAddon = null;
let ws = null;
let reader = null;          // 데모 모드 줄 입력기 { prompt, echo, history, buf, hIdx, resolve }
let inputMode = 'none';     // none | read | gateway | closed
let sessionGen = 0;         // 재접속 시 이전 세션의 비동기 흐름을 끊기 위한 세대 번호
let sessionStart = 0;
let lastInput = Date.now();
let cmdHistory = [];
let demoCwd = '';
let demoUser = '';

// ---------- 초기화 ----------
window.addEventListener('DOMContentLoaded', async () => {
    await loadConsoleConfig();
    // 등록 서버 목록 (DB) - 실패해도 주소 직접 입력 접속은 가능
    try { await loadServers(); } catch (e) { console.warn('서버 목록을 불러오지 못했습니다:', e); }

    const q = new URLSearchParams(location.search);
    const portParam = q.get('port');
    const hostParam = (q.get('host') || '').trim();
    let problem = '';
    if (q.has('id')) {
        wcServer = serversData.find(s => String(s.id) === q.get('id')) || null;
        if (!wcServer) problem = '등록된 서버를 찾을 수 없습니다. (id=' + q.get('id') + ') 주소를 직접 입력하세요.';
    } else if (hostParam) {
        if (isValidConsoleHost(hostParam)) wcServer = findServerByHost(hostParam) || manualTarget(hostParam);
        else problem = '접속 주소 형식이 올바르지 않습니다: ' + hostParam;
    }
    if (portParam !== null && !isValidConsolePort(portParam)) {
        problem = '포트는 1 ~ 65535 사이의 숫자여야 합니다. (입력값: ' + portParam + ')';
        wcServer = null;
    }
    wcPort = portParam !== null && !problem ? Number(portParam) : DEFAULT_SSH_PORT;

    initConnectForm();
    if (!wcServer) {
        document.getElementById('wcServerName').textContent = '원격 접속';
        document.getElementById('wcServerSub').textContent = '접속할 서버의 주소와 포트를 입력하세요.';
        renderMode();
        setState('', '접속 대상 입력 대기');
        showConnectForm(hostParam, portParam, problem);
        return;
    }
    if (typeof Terminal === 'undefined') {
        document.getElementById('wcTerminal').innerHTML = '<p style="color:#ffb3ba;font-size:13px;padding:12px;">터미널 라이브러리(xterm.js)를 불러오지 못했습니다. static/vendor/xterm 파일이 배포되었는지 확인하세요.</p>';
        return;
    }

    renderServerInfo();
    initTerminal();
    setInterval(tick, 1000);
    startSession();
});

window.addEventListener('beforeunload', e => {
    if (inputMode === 'gateway' || (inputMode === 'read' && demoUser)) {
        e.preventDefault();
        e.returnValue = '';
    }
});

// 등록되지 않은 주소로 접속할 때 사용하는 대상 정보
function manualTarget(host) {
    return { id: null, manual: true, systemName: host, fqdn: host, ip: host, os: 'Unknown', location: '직접 입력', status: '직접 입력',
        cpu: '5.0%', mem: '20.0%', disk: '30%', processes: [] };
}

function renderServerInfo() {
    const s = wcServer;
    document.title = s.systemName + ' - 웹 콘솔';
    document.getElementById('wcServerName').textContent = s.systemName;
    const badge = document.getElementById('wcServerStatus');
    badge.textContent = s.status;
    badge.className = 'badge ' + (s.manual ? 'badge-manual' : s.status === 'Active' ? 'badge-active' : s.status === 'Standby' ? 'badge-standby' : 'badge-stopped');
    document.getElementById('wcServerSub').textContent = s.manual
        ? s.ip + ':' + wcPort + '  ·  등록되지 않은 서버 (주소 직접 입력)'
        : s.fqdn + '  ·  ' + s.ip + '  ·  ' + s.os + '  ·  ' + s.location;
    document.getElementById('wcTarget').textContent = 'ssh ' + s.ip + ':' + wcPort;
    renderMode();
}

function renderMode() {
    const mode = document.getElementById('wcMode');
    mode.textContent = CONSOLE_GATEWAY_URL ? 'LIVE' : 'DEMO';
    mode.className = 'wc-mode ' + (CONSOLE_GATEWAY_URL ? 'live' : 'demo');
    mode.title = CONSOLE_GATEWAY_URL ? '실제 SSH 접속 (' + CONSOLE_GATEWAY_URL + ')' : '데모 모드: 실제 서버에 접속하지 않습니다';
}

// 웹 서버에서 접속 모드를 받아 옵니다. (실패 시 데모 모드)
async function loadConsoleConfig() {
    try {
        const res = await fetch('api/console-config', { cache: 'no-store' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const cfg = await res.json();
        if (cfg.mode === 'live') {
            const url = new URL(cfg.path || 'ssh', location.href); // 화면 기준 상대 경로 (컨텍스트 경로 대응)
            url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
            url.search = '';
            CONSOLE_GATEWAY_URL = url.href;
        }
    } catch (e) {
        console.warn('웹 콘솔 설정을 읽지 못해 데모 모드로 동작합니다:', e);
        CONSOLE_GATEWAY_URL = '';
    }
}

function initTerminal() {
    let fontSize = 14;
    try { fontSize = Number(localStorage.getItem(CONSOLE_FONT_KEY)) || 14; } catch (e) {}
    term = new Terminal({
        cursorBlink: true,
        fontSize: fontSize,
        fontFamily: "Consolas, 'D2Coding', 'Courier New', monospace",
        scrollback: 5000,
        theme: { background: '#1e1e1e', foreground: '#d4d4d4', cursor: '#d4d4d4' }
    });
    fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(document.getElementById('wcTerminal'));
    fitAddon.fit();
    term.focus();

    term.onData(onTermData);
    term.onResize(({ cols, rows }) => {
        updateSize();
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'resize', cols: cols, rows: rows }));
    });
    window.addEventListener('resize', () => fitAddon.fit());
    updateSize();
}

function updateSize() {
    document.getElementById('wcSize').textContent = term.cols + 'x' + term.rows;
}

function setState(kind, text) {
    document.getElementById('wcLed').className = 'wc-led ' + kind;
    document.getElementById('wcState').textContent = text;
}

function tick() {
    if (sessionStart) {
        const sec = Math.floor((Date.now() - sessionStart) / 1000);
        const p = n => String(n).padStart(2, '0');
        document.getElementById('wcElapsed').textContent = p(Math.floor(sec / 3600)) + ':' + p(Math.floor(sec / 60) % 60) + ':' + p(sec % 60);
    }
    const active = inputMode === 'gateway' || (inputMode === 'read' && demoUser);
    if (active && Date.now() - lastInput > CONSOLE_IDLE_TIMEOUT_MIN * 60000) {
        endSession(CONSOLE_IDLE_TIMEOUT_MIN + '분 동안 입력이 없어 자동 종료');
    }
}

// ---------- 키 입력 분배 ----------
function onTermData(data) {
    lastInput = Date.now();
    if (inputMode === 'gateway') {
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'data', data: data }));
    } else if (inputMode === 'read' && reader) {
        handleReaderInput(data);
    } else if (inputMode === 'closed' && (data === '\r' || data === '\n')) {
        startSession();
    }
}

// 줄 단위 입력 (로그인 계정, 비밀번호, 데모 셸 명령)
function readLine(opts) {
    return new Promise(resolve => {
        reader = { prompt: opts.prompt || '', echo: opts.echo !== false, history: !!opts.history, buf: '', hIdx: cmdHistory.length, resolve: resolve };
        inputMode = 'read';
        term.write(reader.prompt);
    });
}

function finishRead(value) {
    const r = reader;
    reader = null;
    if (r) r.resolve(value);
}

function charWidth(ch) {
    return /[ᄀ-ᅟ⺀-꓏가-힣豈-﫿︰-﹏＀-｠￠-￦]/.test(ch) ? 2 : 1;
}

function handleReaderInput(data) {
    const r = reader;
    if (data === '\x1b[A' || data === '\x1b[B') {
        if (!r.history || !cmdHistory.length) return;
        r.hIdx = Math.max(0, Math.min(cmdHistory.length, r.hIdx + (data === '\x1b[A' ? -1 : 1)));
        r.buf = cmdHistory[r.hIdx] || '';
        term.write('\r\x1b[K' + r.prompt + r.buf);
        return;
    }
    if (data.charAt(0) === '\x1b') return; // 기타 방향키/기능키 무시
    let prev = '';
    for (const ch of data) {
        if (reader !== r) break;
        if (ch === '\n' && prev === '\r') continue; // 붙여넣기의 \r\n
        prev = ch;
        if (ch === '\r' || ch === '\n') { term.write('\r\n'); finishRead(r.buf); break; }
        if (ch === '\x03') { term.write('^C\r\n'); finishRead(null); break; }
        if (ch === '\x7f' || ch === '\b') {
            if (!r.buf) continue;
            const chars = Array.from(r.buf);
            const last = chars.pop();
            r.buf = chars.join('');
            if (r.echo) { const w = charWidth(last); term.write('\b'.repeat(w) + ' '.repeat(w) + '\b'.repeat(w)); }
            continue;
        }
        if (ch === '\x0c') { term.write('\x1b[2J\x1b[H' + r.prompt + (r.echo ? r.buf : '')); continue; }
        if (ch < ' ') continue;
        r.buf += ch;
        if (r.echo) term.write(ch);
    }
}

// ---------- 세션 ----------
function sleep(ms) { return new Promise(res => setTimeout(res, ms)); }

async function startSession() {
    const gen = ++sessionGen;
    closeSocket();
    finishRead(null);
    inputMode = 'none';
    demoUser = '';
    sessionStart = Date.now();
    lastInput = Date.now();
    const s = wcServer;

    term.reset();
    term.writeln(C.cyan + C.bold + '■ 통합 서버 관리 및 모니터링 시스템 - 웹 콘솔' + C.reset);
    term.writeln(C.gray + '  대상: ' + s.systemName + ' (' + s.fqdn + ')' + C.reset);
    if (!CONSOLE_GATEWAY_URL) {
        term.writeln(C.yellow + '  [DEMO] 데모 모드로 실행 중입니다. 실제 서버에는 접속하지 않습니다. (실제 접속: app.console.mode=live)' + C.reset);
    }
    term.writeln(C.gray + '  모든 접속 및 명령 실행 내역은 감사 목적으로 기록될 수 있습니다.' + C.reset);
    term.writeln('');

    setState('connecting', '접속 중...');
    term.writeln('Connecting to ' + s.ip + ' port ' + wcPort + ' ...');

    if (!CONSOLE_GATEWAY_URL) {
        await sleep(700);
        if (gen !== sessionGen) return;
        if (s.status === 'Down' || s.status === 'Stopped') {
            term.writeln(C.red + 'ssh: connect to host ' + s.ip + ' port ' + wcPort + ': Connection timed out' + C.reset);
            endSession('접속 실패 (서버 Down)', true);
            return;
        }
        term.writeln('Connected to ' + s.fqdn + '.');
    }

    // 로그인 계정 입력
    let user = null;
    while (user === null) {
        const v = await readLine({ prompt: 'login as: ' });
        if (gen !== sessionGen) return;
        if (v === null) { endSession('사용자 취소'); return; }
        if (/^[A-Za-z_][A-Za-z0-9._-]{0,31}$/.test(v.trim())) user = v.trim();
        else term.writeln(C.red + '올바른 계정명을 입력하세요. (영문/숫자/._- 32자 이내)' + C.reset);
    }

    if (CONSOLE_GATEWAY_URL) connectGateway(user, gen);
    else runDemo(user, gen);
}

function endSession(reason, failed) {
    sessionGen++;
    closeSocket();
    finishRead(null);
    if (inputMode === 'closed') return;
    inputMode = 'closed';
    demoUser = '';
    sessionStart = 0;
    setState(failed ? 'failed' : '', '연결 종료');
    term.writeln('');
    term.writeln(C.yellow + '[세션 종료] ' + reason + C.reset);
    term.writeln(C.gray + 'Enter 키를 누르거나 [재접속] 버튼을 누르면 다시 접속합니다.' + C.reset);
}

function closeSocket() {
    if (!ws) return;
    const sock = ws;
    ws = null;
    sock.onclose = sock.onerror = sock.onmessage = null;
    try { sock.close(1000, 'client closed'); } catch (e) {}
}

// ---------- 실제 접속 (게이트웨이) ----------
function connectGateway(user, gen) {
    const params = new URLSearchParams({ host: wcServer.ip, port: String(wcPort), user: user, cols: String(term.cols), rows: String(term.rows) });
    let sock;
    try {
        sock = new WebSocket(CONSOLE_GATEWAY_URL + (CONSOLE_GATEWAY_URL.indexOf('?') < 0 ? '?' : '&') + params);
    } catch (e) {
        term.writeln(C.red + '게이트웨이 주소가 올바르지 않습니다: ' + e.message + C.reset);
        endSession('접속 실패', true);
        return;
    }
    ws = sock;
    sock.binaryType = 'arraybuffer';
    inputMode = 'gateway';
    sock.onopen = () => { if (gen === sessionGen) setState('connected', '접속됨 (' + user + ')'); };
    sock.onmessage = e => { if (gen === sessionGen) term.write(typeof e.data === 'string' ? e.data : new Uint8Array(e.data)); };
    sock.onerror = () => { if (gen === sessionGen) term.writeln('\r\n' + C.red + '웹 콘솔 연결 오류 (' + CONSOLE_GATEWAY_URL + ') - 로그인이 만료되었으면 새로고침 후 다시 로그인하세요.' + C.reset); };
    sock.onclose = e => {
        if (gen !== sessionGen) return;
        ws = null;
        endSession(e.reason || ('연결 종료 (code ' + e.code + ')'), e.code !== 1000);
    };
}

// ---------- 데모 모드 (시뮬레이션) ----------
function demoHost() { return String(wcServer.fqdn || wcServer.systemName).split('.')[0]; }
function demoHome() { return demoUser === 'root' ? '/root' : '/export/home/' + demoUser; }
function demoPrompt() {
    const cwd = demoCwd === demoHome() ? '~' : demoCwd;
    return C.green + C.bold + demoUser + '@' + demoHost() + C.reset + ':' + C.blue + C.bold + cwd + C.reset + (demoUser === 'root' ? '# ' : '$ ');
}

async function runDemo(user, gen) {
    for (let tries = 0; tries < 3; tries++) {
        const pw = await readLine({ prompt: user + '@' + wcServer.ip + "'s password: ", echo: false });
        if (gen !== sessionGen) return;
        if (pw === null) { endSession('사용자 취소'); return; }
        if (pw) {
            demoUser = user;
            break;
        }
        term.writeln('Permission denied, please try again.');
    }
    if (!demoUser) { endSession('인증 실패 (Permission denied)', true); return; }

    demoCwd = demoHome();
    cmdHistory = [];
    setState('connected', '접속됨 (' + user + ') · DEMO');
    const now = new Date();
    term.writeln('Last login: ' + now.toString().slice(0, 24) + ' from 10.0.1.25');
    term.writeln(isSolaris() ? 'Oracle Solaris 11.4.42.111.0                  Assembled March 2022' : 'Welcome to ' + demoHost());
    term.writeln(C.gray + "도움말은 'help' 를 입력하세요. (데모 모드)" + C.reset);

    while (gen === sessionGen) {
        const line = await readLine({ prompt: demoPrompt(), history: true });
        if (gen !== sessionGen) return;
        if (line === null) continue; // Ctrl+C
        const cmd = line.trim();
        if (!cmd) continue;
        if (cmdHistory[cmdHistory.length - 1] !== cmd) cmdHistory.push(cmd);
        if (cmdHistory.length > 200) cmdHistory.shift();
        if (cmd === 'exit' || cmd === 'logout') { term.writeln('logout'); endSession('로그아웃'); return; }
        demoExec(cmd);
    }
}

function isSolaris() { return /solaris|sunos/i.test(wcServer.os || ''); }

function pct(v) { return Math.max(0, Math.min(100, parseFloat(v) || 0)); }

function demoExec(line) {
    const s = wcServer;
    const args = line.split(/\s+/);
    const cmd = args[0];
    const out = text => text.split('\n').forEach(l => term.writeln(l));
    const procs = Array.isArray(s.processes) ? s.processes : [];
    const host = demoHost();

    switch (cmd) {
        case 'help':
            out(C.cyan + '사용 가능한 명령 (데모):' + C.reset + '\n' +
                '  hostname  uname [-a]  uptime  whoami  id  date  pwd  cd  ls  echo\n' +
                '  df -h  ps -ef  prstat | top  ifconfig -a  cat /etc/release  history  clear  exit');
            break;
        case 'hostname': out(host); break;
        case 'whoami': out(demoUser); break;
        case 'id': out(demoUser === 'root' ? 'uid=0(root) gid=0(root)' : 'uid=1001(' + demoUser + ') gid=100(staff)'); break;
        case 'date': out(new Date().toString().slice(0, 24)); break;
        case 'pwd': out(demoCwd); break;
        case 'echo': out(line.replace(/^echo\s*/, '')); break;
        case 'clear': term.clear(); break;
        case 'history': cmdHistory.forEach((h, i) => term.writeln(String(i + 1).padStart(5) + '  ' + h)); break;
        case 'uname':
            out(args[1] === '-a'
                ? (isSolaris() ? 'SunOS ' + host + ' 5.11 11.4.42.111.0 sun4v sparc sun4v' : 'Linux ' + host + ' 5.14.0-427.el9.x86_64 #1 SMP x86_64 GNU/Linux')
                : (isSolaris() ? 'SunOS' : 'Linux'));
            break;
        case 'uptime': {
            const l = (pct(s.cpu) / 25).toFixed(2);
            out('  ' + new Date().toTimeString().slice(0, 5) + '  up 132 day(s),  4:11,  2 users,  load average: ' + l + ', ' + (l * 0.9).toFixed(2) + ', ' + (l * 0.8).toFixed(2));
            break;
        }
        case 'cd': {
            const t = args[1];
            if (!t || t === '~') demoCwd = demoHome();
            else if (t === '..') demoCwd = demoCwd.replace(/\/[^/]+$/, '') || '/';
            else if (t.charAt(0) === '/') demoCwd = t.replace(/\/+$/, '') || '/';
            else demoCwd = (demoCwd === '/' ? '' : demoCwd) + '/' + t.replace(/\/+$/, '');
            break;
        }
        case 'ls':
            out(demoCwd === '/'
                ? C.blue + 'bin  dev  etc  export  home  opt  tmp  u01  usr  var' + C.reset
                : C.blue + 'logs  scripts  tmp' + C.reset + '  .profile  README.txt');
            break;
        case 'df':
            out('Filesystem             Size   Used  Available Capacity  Mounted on\n' +
                'rpool/ROOT/solaris      98G    21G        77G    22%    /\n' +
                'rpool/export            40G   4.1G        35G    11%    /export\n' +
                'app/murex              1.6T  ' + (pct(s.disk) * 16 / 1000).toFixed(1) + 'T   ' + ((100 - pct(s.disk)) * 16 / 1000).toFixed(1) + 'T    ' + (s.disk === 'N/A' ? '0%' : s.disk) + '    /opt/murex\n' +
                'swap                    32G   1.2G        31G     4%    /tmp');
            break;
        case 'ps': {
            const rows = ['     UID   PID  PPID   C    STIME TTY         TIME CMD',
                '    root     1     0   0   May 31 ?           0:42 /usr/sbin/init',
                '    root   245     1   0   May 31 ?           1:10 /usr/lib/ssh/sshd'];
            procs.filter(p => p.running).forEach(p => rows.push('   murex ' + String(p.pid).padStart(5) + '     1   0   May 31 ?          12:31 ' + p.path));
            out(rows.join('\n'));
            break;
        }
        case 'prstat':
        case 'top': {
            const rows = ['   PID USERNAME  SIZE   RSS STATE   PRI NICE      TIME  CPU PROCESS/NLWP'];
            procs.filter(p => p.running).forEach(p => rows.push(String(p.pid).padStart(6) + ' murex     ' + Math.round(pct(p.mem) * 400 + 200 + 0.5) + 'M  ' + Math.round(pct(p.mem) * 380) + 'M cpu1     59    0  12:31:04 ' + String(p.cpu).padStart(4) + ' ' + p.name + '/12'));
            rows.push('Total: ' + (procs.length + 86) + ' processes, ' + (procs.length * 12 + 412) + ' lwps, load averages: ' + (pct(s.cpu) / 25).toFixed(2));
            out(rows.join('\n'));
            break;
        }
        case 'ifconfig':
            out('lo0: flags=2001000849<UP,LOOPBACK,RUNNING,MULTICAST,IPv4,VIRTUAL> mtu 8232 index 1\n' +
                '        inet 127.0.0.1 netmask ff000000\n' +
                'net0: flags=1000843<UP,BROADCAST,RUNNING,MULTICAST,IPv4> mtu 1500 index 2\n' +
                '        inet ' + s.ip + ' netmask ffffff00 broadcast ' + s.ip.replace(/\.\d+$/, '.255'));
            break;
        case 'cat':
            if (args[1] === '/etc/release') out('                             Oracle Solaris 11.4 SPARC\n  Copyright (c) 1983, 2022, Oracle and/or its affiliates.\n                            Assembled 15 March 2022');
            else out('cat: ' + (args[1] || '') + ': No such file or directory');
            break;
        case 'sudo':
        case 'su':
        case 'rm':
        case 'shutdown':
        case 'reboot':
        case 'init':
            out(C.red + cmd + ': 데모 모드에서는 실행할 수 없는 명령입니다.' + C.reset);
            break;
        default:
            out('-bash: ' + cmd + ': command not found');
    }
}

// ---------- 주소/포트 입력 화면 ----------
function initConnectForm() {
    document.getElementById('wcConnectForm').addEventListener('submit', e => {
        e.preventDefault();
        const hostEl = document.getElementById('wcHostInput');
        const portEl = document.getElementById('wcPortInput');
        const host = hostEl.value.trim();
        const port = portEl.value.trim();
        const err = document.getElementById('wcConnectError');
        if (!isValidConsoleHost(host)) { err.textContent = '접속 주소를 올바르게 입력하세요. (예: 10.0.13.217 또는 host.example.com)'; hostEl.focus(); return; }
        if (!isValidConsolePort(port)) { err.textContent = '포트는 1 ~ 65535 사이의 숫자로 입력하세요.'; portEl.focus(); return; }
        const server = findServerByHost(host);
        location.href = 'console?' + (server
            ? 'id=' + encodeURIComponent(server.id)
            : 'host=' + encodeURIComponent(host)) + '&port=' + Number(port);
    });
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && wcServer) hideConnectForm();
    });
}

function showConnectForm(host, port, message) {
    document.getElementById('wcHostInput').value = host || '';
    document.getElementById('wcPortInput').value = port && isValidConsolePort(port) ? port : DEFAULT_SSH_PORT;
    document.getElementById('wcConnectError').textContent = message || '';
    document.getElementById('wcConnectCancel').style.display = wcServer ? '' : 'none';
    document.getElementById('wcConnect').hidden = false;
    document.getElementById('wcHostInput').focus();
}

function hideConnectForm() {
    document.getElementById('wcConnect').hidden = true;
    if (term) term.focus();
}

// ---------- 상단 버튼 ----------
function wcOtherHost() { showConnectForm('', '', ''); }
function wcReconnect() { if (!term) return; startSession(); term.focus(); }
function wcDisconnect() { if (!term) return; endSession('사용자 접속 종료'); term.focus(); }
function wcClear() { if (!term) return; term.clear(); term.focus(); }
function wcFont(delta) {
    if (!term) return;
    const size = Math.max(10, Math.min(24, term.options.fontSize + delta));
    term.options.fontSize = size;
    try { localStorage.setItem(CONSOLE_FONT_KEY, String(size)); } catch (e) {}
    fitAddon.fit();
    term.focus();
}
function wcFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else document.documentElement.requestFullscreen().catch(() => {});
    setTimeout(() => { if (term) { fitAddon.fit(); term.focus(); } }, 200);
}
