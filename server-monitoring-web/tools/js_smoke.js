// 브라우저 없이 화면 스크립트의 최상위 코드를 실제로 실행 (TDZ / 초기화 순서 오류 검사)
const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = process.argv[2];
let bad = 0;
for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.js'))) {
    const el = new Proxy(function () {}, { get: (t, k) => k === Symbol.toPrimitive ? () => '' : el, apply: () => el, construct: () => el });
    const ctx = { console, setTimeout() {}, setInterval() {}, clearInterval() {}, URLSearchParams, location: { search: '', pathname: '/' },
                  localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} }, history: el, alert() {}, confirm() { return false; },
                  fetch: () => Promise.resolve(), navigator: {}, matchMedia: () => ({ matches: false }) };
    ctx.window = ctx; ctx.document = el; ctx.window.addEventListener = () => {};
    const files = f === 'script.js' ? ['script.js'] : ['script.js', f];   // 화면 스크립트는 script.js 다음에 로드됨
    try {
        const c = vm.createContext(ctx);
        for (const x of files) vm.runInContext(fs.readFileSync(path.join(dir, x), 'utf8'), c, { filename: x });
        console.log('OK  ', f);
    } catch (e) { bad++; console.log('FAIL', f, '-', e.message); }
}
process.exit(bad ? 1 : 0);
