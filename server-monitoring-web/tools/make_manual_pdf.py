"""사용자 매뉴얼 PDF 만들기 : static/manual/index.html → static/manual/pdf/manual.pdf (전체) + <화면>.pdf (화면별 절)
   화면 ↔ 절 목록은 templates/fragments/top.html 의 MAP (📖 이 화면 매뉴얼 과 같음)
   이 PC 의 Edge 또는 Chrome 을 headless 로 써서 인쇄 (인터넷 · 추가 라이브러리 없음)

   사용 : python tools/make_manual_pdf.py [--force]      (server-monitoring-web 폴더에서)
          매뉴얼(index.html · img) 또는 top.html 이 PDF 보다 새로울 때만 다시 만듦 (--force 는 항상)
   브라우저 위치 : 환경변수 SCB_PDF_BROWSER (없으면 Edge → Chrome 기본 위치)
"""
import os, re, shutil, subprocess, sys, tempfile, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MANUAL = os.path.join(ROOT, 'src', 'main', 'resources', 'static', 'manual')
TOP = os.path.join(ROOT, 'src', 'main', 'resources', 'templates', 'fragments', 'top.html')
OUT = os.path.join(MANUAL, 'pdf')
BROWSERS = [
    os.environ.get('SCB_PDF_BROWSER', ''),
    r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
    r'C:\Program Files\Google\Chrome\Application\chrome.exe',
    r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/microsoft-edge',
]


def screens():
    """top.html MAP : {menu: [절 목록, 화면 이름]}"""
    s = open(TOP, encoding='utf-8').read()
    m = re.search(r'var MAP = \{(.*?)\};', s, re.S)
    if not m:
        sys.exit('top.html 에서 MAP 을 찾지 못함')
    return {k: (sec, name) for k, sec, name in re.findall(r"(\w+): \['([^']+)', '([^']+)'\]", m.group(1))}


def newest(paths):
    t = 0
    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for f in files:
                    t = max(t, os.path.getmtime(os.path.join(root, f)))
        elif os.path.exists(p):
            t = max(t, os.path.getmtime(p))
    return t


def main():
    force = '--force' in sys.argv
    browser = next((b for b in BROWSERS if b and os.path.exists(b)), None)
    if not browser:
        sys.exit('Edge / Chrome 을 찾지 못함 - SCB_PDF_BROWSER 로 실행 파일 위치를 지정하세요')
    jobs = {'manual': ('', '')}
    jobs.update(screens())
    os.makedirs(OUT, exist_ok=True)
    src_time = newest([os.path.join(MANUAL, 'index.html'), os.path.join(MANUAL, 'img'), TOP])
    if not force and all(os.path.exists(os.path.join(OUT, k + '.pdf')) and os.path.getmtime(os.path.join(OUT, k + '.pdf')) >= src_time for k in jobs):
        print('PDF 최신 (건너뜀) :', OUT)
        return
    base = 'file:///' + os.path.join(MANUAL, 'index.html').replace('\\', '/')
    profile = tempfile.mkdtemp(prefix='scb_pdf_')
    try:
        for key, (sec, name) in jobs.items():
            q = 'pdf=1' + ('&section=' + urllib.parse.quote(sec) + '&title=' + urllib.parse.quote(name) if sec else '')
            out = os.path.join(OUT, key + '.pdf')
            tmp = out + '.tmp'
            cmd = [browser, '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
                   '--disable-extensions', '--user-data-dir=' + profile, '--no-pdf-header-footer', '--print-to-pdf-no-header',
                   '--virtual-time-budget=4000', '--run-all-compositor-stages-before-draw', '--print-to-pdf=' + tmp, base + '?' + q]
            subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
            if not os.path.exists(tmp) or open(tmp, 'rb').read(5) != b'%PDF-':
                sys.exit('PDF 만들기 실패 : ' + key)
            os.replace(tmp, out)
            print('%-10s %-24s %7d KB' % (key, name or '전체', os.path.getsize(out) // 1024))
    finally:
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == '__main__':
    main()
