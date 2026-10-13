// ================= 메인 홈 : 서버 카드 위치 이동 (드래그 / 방향키) =================
// - 카드 머리의 ⠿ 손잡이를 끌어서(마우스·터치) 같은 구역(ITC / DRC) 안에서 좌우·상하로 이동
// - 손잡이에 초점을 두고 방향키 : ←/→ 한 칸, ↑/↓ 한 줄
// - 순서는 이 브라우저에 저장 (localStorage 'procCardOrder'), [카드 순서 초기화] 로 기본 순서 복원
// script.js 의 renderProcessSection / renderProcessCard 가 orderServers, cardDragHandle 을 사용
const CARD_ORDER_KEY = 'procCardOrder';
let cardDrag = null;              // 끌기 중인 카드 정보
let cardRenderPending = false;    // 끄는 동안 들어온 화면 갱신은 놓은 뒤 실행

function loadCardOrder() {
    try {
        const v = JSON.parse(localStorage.getItem(CARD_ORDER_KEY) || '{}');
        return v && typeof v === 'object' ? v : {};
    } catch (e) { return {}; }
}

function saveCardOrder(group, ids) {
    const all = loadCardOrder();
    all[group] = ids;
    try { localStorage.setItem(CARD_ORDER_KEY, JSON.stringify(all)); } catch (e) { /* 저장 불가 시 이번 화면만 유지 */ }
}

/** 저장된 순서대로 정렬 (저장에 없는 서버는 기본 순서로 뒤에) */
function orderServers(group, list) {
    const saved = loadCardOrder()[group];
    if (!Array.isArray(saved) || !saved.length) return list;
    const pos = {};
    saved.forEach((id, i) => { pos[id] = i; });
    return list
        .map((s, i) => ({ s: s, k: pos[s.id] !== undefined ? pos[s.id] : saved.length + i }))
        .sort((a, b) => a.k - b.k)
        .map(x => x.s);
}

function resetCardOrder() {
    try { localStorage.removeItem(CARD_ORDER_KEY); } catch (e) { /* 무시 */ }
    renderProcessSection();
}

function cardDragHandle(server) {
    return '<button type="button" class="proc-drag" data-drag-id="' + server.id + '" ' +
        'aria-label="' + escapeHtml(server.systemName) + ' 카드 이동 (끌기 또는 방향키)" title="끌어서 이동 · 방향키로 이동">⠿</button>';
}

function gridOf(card) { return card.closest('.proc-grid'); }

function groupKeyOf(grid) {
    const g = grid.closest('.proc-group');
    return g && g.classList.contains('drc') ? 'drc' : 'itc';
}

function saveGridOrder(grid) {
    const ids = [...grid.querySelectorAll(':scope > .proc-card')].map(c => Number(c.getAttribute('data-server-id')));
    saveCardOrder(groupKeyOf(grid), ids);
}

function gridColumns(grid) {
    const cols = getComputedStyle(grid).gridTemplateColumns.split(' ').filter(Boolean).length;
    return Math.max(1, cols);
}

// ---------------------------------------------------------------- 끌기 (Pointer Events : 마우스 + 터치)

function startCardDrag(e, handle) {
    if (e.button !== undefined && e.button !== 0) return;
    const card = handle.closest('.proc-card');
    const grid = card && gridOf(card);
    if (!grid) return;
    e.preventDefault();
    const rect = card.getBoundingClientRect();
    const ph = document.createElement('div');
    ph.className = 'proc-card-placeholder';
    ph.style.height = rect.height + 'px';
    card.parentNode.insertBefore(ph, card);

    card.classList.add('dragging');
    card.style.width = rect.width + 'px';
    card.style.height = rect.height + 'px';
    card.style.left = rect.left + 'px';
    card.style.top = rect.top + 'px';   // position: fixed 로 띄움 (자리는 placeholder 가 차지)

    cardDrag = { card: card, grid: grid, ph: ph, dx: e.clientX - rect.left, dy: e.clientY - rect.top,
                 pointerId: e.pointerId, handle: handle, lastX: e.clientX, lastY: e.clientY, scrollTimer: null };
    handle.setPointerCapture && handle.setPointerCapture(e.pointerId);
    document.body.classList.add('card-dragging');
    cardDrag.scrollTimer = setInterval(autoScrollWhileDrag, 30);
}

function moveCardDrag(e) {
    if (!cardDrag || e.pointerId !== cardDrag.pointerId) return;
    cardDrag.lastX = e.clientX;
    cardDrag.lastY = e.clientY;
    placeDraggedCard();
}

function placeDraggedCard() {
    const d = cardDrag;
    d.card.style.left = (d.lastX - d.dx) + 'px';
    d.card.style.top = (d.lastY - d.dy) + 'px';

    // 손가락/마우스 아래의 카드 (같은 구역) 와 자리 바꾸기
    const under = document.elementFromPoint(d.lastX, d.lastY);
    const target = under && under.closest('.proc-card');
    if (!target || target === d.card || gridOf(target) !== d.grid) return;
    const items = [...d.grid.children];
    const from = items.indexOf(d.ph);
    const to = items.indexOf(target);
    if (from < to) target.after(d.ph);
    else target.before(d.ph);
}

// 화면 위/아래 끝 가까이 끌면 자동 스크롤
function autoScrollWhileDrag() {
    if (!cardDrag) return;
    const edge = 70, y = cardDrag.lastY, h = window.innerHeight;
    let dy = 0;
    if (y < edge) dy = -Math.ceil((edge - y) / 4);
    else if (y > h - edge) dy = Math.ceil((y - (h - edge)) / 4);
    if (dy) {
        window.scrollBy(0, dy);
        placeDraggedCard();
    }
}

function endCardDrag(e) {
    if (!cardDrag || (e && e.pointerId !== cardDrag.pointerId)) return;
    const d = cardDrag;
    cardDrag = null;
    clearInterval(d.scrollTimer);
    d.ph.replaceWith(d.card);
    d.card.classList.remove('dragging');
    ['width', 'height', 'left', 'top'].forEach(p => { d.card.style[p] = ''; });
    document.body.classList.remove('card-dragging');
    saveGridOrder(d.grid);
    flashCard(d.card);
    if (cardRenderPending) {
        cardRenderPending = false;
        renderProcessSection();
    }
    const h = document.querySelector('.proc-drag[data-drag-id="' + d.card.getAttribute('data-server-id') + '"]');
    if (h) h.focus({ preventScroll: true });
}

// ---------------------------------------------------------------- 방향키

function moveCardByKey(handle, key) {
    const card = handle.closest('.proc-card');
    const grid = card && gridOf(card);
    if (!grid) return false;
    const cards = [...grid.querySelectorAll(':scope > .proc-card')];
    const i = cards.indexOf(card);
    const cols = gridColumns(grid);
    const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -cols, ArrowDown: cols }[key];
    if (step === undefined) return false;
    const j = Math.max(0, Math.min(cards.length - 1, i + step));
    if (j === i) return true;
    if (j > i) cards[j].after(card);
    else cards[j].before(card);
    saveGridOrder(grid);
    handle.focus({ preventScroll: true });
    card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    flashCard(card);
    return true;
}

function flashCard(card) {
    card.classList.remove('moved');
    void card.offsetWidth;
    card.classList.add('moved');
}

// ---------------------------------------------------------------- 연결

window.addEventListener('DOMContentLoaded', function () {
    const groups = document.getElementById('processGroups');
    if (!groups) return;
    // 카드는 #processGroups 또는 대시보드 사이트별 칸(#mnCards-itc / -drc)에 있으므로 문서 전체에서 받음
    document.addEventListener('pointerdown', e => {
        const h = e.target.closest('.proc-drag');
        if (h) startCardDrag(e, h);
    });
    document.addEventListener('pointermove', moveCardDrag);
    document.addEventListener('pointerup', endCardDrag);
    document.addEventListener('pointercancel', endCardDrag);
    document.addEventListener('keydown', e => {
        const h = e.target.closest && e.target.closest('.proc-drag');
        if (h && moveCardByKey(h, e.key)) e.preventDefault();
    });
    const reset = document.getElementById('cardOrderReset');
    if (reset) reset.addEventListener('click', resetCardOrder);
});
