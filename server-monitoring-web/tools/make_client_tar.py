import io
import os
import sys
import tarfile
import time

src = sys.argv[1]          # C:\scb\workspace\krcasmrx11_client
out = sys.argv[2]          # C:\scb\workspace\krcasmrx11_client.tar
top = 'krcasmrx11_client'
now = int(time.time())


def info(name, mode, size=0, is_dir=False):
    ti = tarfile.TarInfo(name)
    ti.mode = mode
    ti.mtime = now
    ti.uid = ti.gid = 0
    ti.uname = ti.gname = 'root'
    if is_dir:
        ti.type = tarfile.DIRTYPE
    else:
        ti.size = size
    return ti


with tarfile.open(out, 'w', format=tarfile.USTAR_FORMAT) as tar:
    # 최상위 디렉터리 항목은 넣지 않음 : 서버에 미리 만든 디렉터리(777)의 권한을 tar 가 바꾸지 않도록
    for d in ['bin', 'lib', 'etc', 'run', 'log']:
        tar.addfile(info(top + '/' + d + '/', 0o755, is_dir=True))
    for rel in sorted(['README.txt', 'etc/client.env.sample', 'etc/scb_client.init'] +
                      ['bin/' + f for f in os.listdir(os.path.join(src, 'bin'))] +
                      ['lib/' + f for f in os.listdir(os.path.join(src, 'lib'))]):
        data = open(os.path.join(src, rel), 'rb').read()
        assert b'\r\n' not in data, 'CRLF in ' + rel
        mode = 0o755 if rel.endswith('.sh') or rel.endswith('.init') else 0o644
        tar.addfile(info(top + '/' + rel, mode, len(data)), io.BytesIO(data))
    for keep in ['run/.keep', 'log/.keep']:
        tar.addfile(info(top + '/' + keep, 0o644, 0), io.BytesIO(b''))

with tarfile.open(out) as tar:
    for m in tar.getmembers():
        print('%s %s %6d %s' % ('d' if m.isdir() else '-', oct(m.mode)[-3:], m.size, m.name))
