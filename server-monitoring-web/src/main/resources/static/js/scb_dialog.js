// =============================================================
//  공통 팝업 (상태별 색상·아이콘 표준) - 브라우저 기본 alert / confirm / prompt 대신 사용
//    success : 초록 ✓ (성공·완료)      info    : 파랑 ℹ (정보·안내)
//    warning : 주황 ⚠ (경고·주의)      error   : 빨강 ✕ (위험·오류)
//  scbAlert(메시지 [, 상태])                 → Promise (확인을 누르면)
//  scbConfirm(메시지 [, {type, title, okText, cancelText}]) → Promise<boolean>
//  scbPrompt(메시지 [, {type, title, placeholder}])         → Promise<string|null>
//  scbModalStatus(모달 카드 또는 오버레이, 상태)  : 화면 안의 팝업(.modal-card)에 상태 색 지정
//  상태를 주지 않으면 메시지 내용으로 짐작 (실패·오류 → error, 완료·했습니다 → success, ...)
//  <form data-confirm="메시지"> 는 제출 전에 scbConfirm
// =============================================================
(function () {
    const STATUS = {
        success: { icon: '✓', title: '완료' },
        info: { icon: 'ℹ', title: '안내' },
        warning: { icon: '⚠', title: '주의' },
        error: { icon: '✕', title: '오류' }
    };

    /** 메시지로 상태 짐작 */
    function guess(message, fallback) {
        const m = String(message || '');
        if (/실패|오류|에러|error|없습니다|않습니다|안 됨|거부|권한이 없|만료|차단|일치하지|너무 큽니다/i.test(m)) return 'error';
        if (/완료|했습니다|되었습니다|저장|보냈습니다|성공|추가되|수정되|제거되/.test(m)) return 'success';
        if (/주의|경고|만 사용할 수|삭제|제거|초기화|무효|되돌|버리고|덮어|재시작|중지/i.test(m)) return 'warning';
        return fallback || 'info';
    }

    let queue = Promise.resolve();

    function open(opts) {
        // 여러 팝업이 겹치지 않도록 차례로
        const run = () => new Promise(resolve => {
            const type = STATUS[opts.type] ? opts.type : 'info';
            const st = STATUS[type];
            const last = document.activeElement;
            const ov = document.createElement('div');
            ov.className = 'scb-dlg-overlay';
            ov.innerHTML =
                '<div class="scb-dlg" data-status="' + type + '" role="' + (opts.cancel ? 'alertdialog' : 'dialog') + '" aria-modal="true" aria-labelledby="scbDlgTitle" aria-describedby="scbDlgMsg">'
                + '<div class="scb-dlg-head"><span class="scb-dlg-icon" aria-hidden="true">' + st.icon + '</span>'
                + '<h3 id="scbDlgTitle" class="scb-dlg-title"></h3></div>'
                + '<div id="scbDlgMsg" class="scb-dlg-msg"></div>'
                + (opts.input ? '<input type="text" class="scb-dlg-input" autocomplete="off" spellcheck="false">' : '')
                + '<div class="scb-dlg-btns">'
                + (opts.cancel ? '<button type="button" class="btn scb-dlg-cancel"></button>' : '')
                + '<button type="button" class="btn scb-dlg-ok"></button></div></div>';
            ov.querySelector('.scb-dlg-title').textContent = opts.title || st.title;
            ov.querySelector('.scb-dlg-msg').textContent = String(opts.message == null ? '' : opts.message);
            const ok = ov.querySelector('.scb-dlg-ok');
            ok.textContent = opts.okText || '확인';
            const cancel = ov.querySelector('.scb-dlg-cancel');
            if (cancel) cancel.textContent = opts.cancelText || '취소';
            const input = ov.querySelector('.scb-dlg-input');
            if (input && opts.placeholder) input.placeholder = opts.placeholder;
            document.body.appendChild(ov);
            document.body.classList.add('scb-dlg-open');

            function done(v) {
                document.removeEventListener('keydown', key, true);
                ov.remove();
                if (!document.querySelector('.scb-dlg-overlay')) document.body.classList.remove('scb-dlg-open');
                if (last && last.focus) try { last.focus(); } catch (e) { /* 없어진 요소 */ }
                resolve(v);
            }
            const yes = () => done(input ? input.value : true);
            const no = () => done(input ? null : false);
            function key(e) {
                if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); opts.cancel ? no() : yes(); }
                else if (e.key === 'Enter' && (e.target === input || e.target === ok || !input)) {
                    if (e.target.tagName === 'BUTTON' && e.target !== ok) return;
                    e.preventDefault(); e.stopPropagation(); yes();
                } else if (e.key === 'Tab') {                                 // 팝업 안에서만 이동
                    const f = Array.from(ov.querySelectorAll('button, input'));
                    const i = f.indexOf(document.activeElement);
                    if (e.shiftKey && i <= 0) { e.preventDefault(); f[f.length - 1].focus(); }
                    else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); }
                }
            }
            document.addEventListener('keydown', key, true);
            ok.addEventListener('click', yes);
            if (cancel) cancel.addEventListener('click', no);
            ov.addEventListener('click', e => { if (e.target === ov && opts.cancel) no(); });
            // 위험한 작업 확인은 취소에 초점
            (input || (opts.cancel && type === 'error' ? cancel : ok)).focus();
        });
        const p = queue.then(run, run);
        queue = p.then(() => undefined, () => undefined);
        return p;
    }

    window.scbAlert = function (message, type) {
        return open({ message: message, type: type || guess(message) });
    };

    window.scbConfirm = function (message, o) {
        o = o || {};
        // 되돌릴 수 없는 작업(삭제·제거·초기화·무효화·덮어쓰기)은 위험(빨강), 그 밖은 주의(주황)
        const type = o.type || (/삭제|제거|초기화|무효|덮어|되돌리|kill/i.test(String(message)) ? 'error' : 'warning');
        return open({ message: message, type: type, title: o.title || (type === 'error' ? '위험 - 확인' : '확인'), cancel: true,
            okText: o.okText, cancelText: o.cancelText }).then(Boolean);
    };

    window.scbPrompt = function (message, o) {
        o = o || {};
        return open({ message: message, type: o.type || 'warning', title: o.title || '입력', cancel: true, input: true,
            placeholder: o.placeholder, okText: o.okText, cancelText: o.cancelText });
    };

    /** 화면 안의 팝업(.modal-card)에 상태 색 지정 : el = 카드 또는 오버레이 또는 id */
    window.scbModalStatus = function (el, type) {
        if (typeof el === 'string') el = document.getElementById(el);
        if (!el) return;
        const card = el.classList.contains('modal-card') ? el : el.querySelector('.modal-card');
        if (card) card.setAttribute('data-status', STATUS[type] ? type : 'info');
    };

    window.scbGuessStatus = guess;

    // <form data-confirm="..."> (로그아웃 등)
    document.addEventListener('submit', function (e) {
        const f = e.target;
        if (!f.dataset || !f.dataset.confirm || f.dataset.confirmed === 'Y') return;
        e.preventDefault();
        scbConfirm(f.dataset.confirm, { type: f.dataset.confirmType || 'info' }).then(yes => {
            if (!yes) return;
            f.dataset.confirmed = 'Y';
            f.submit();
        });
    }, true);
})();
