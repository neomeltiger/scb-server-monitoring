#!/bin/sh
#=============================================================
# collect_log.sh - 웹 서버가 요청한 로그 파일의 마지막 N줄 출력
#   collect_log.sh <로그 파일 절대경로> <줄 수>
#   종료 코드 : 0 = OK, 3 = NOT_FOUND (파일 없음), 4 = DENIED (허용 목록 밖), 5 = ERROR (읽기 실패)
#
# 보안 : etc/client.env 의 SCB_LOG_ALLOW (공백 구분 셸 패턴) 에 맞는 경로만 읽습니다.
#        웹 서버가 어떤 경로를 요청하더라도 이 서버의 허용 목록이 최종 기준입니다.
#        경로는 절대 경로, 영문·숫자·_ . / - 만, ".." 금지, 줄 1000 / 한 줄 2000자 / 전체 256KB 까지
# Solaris 10 /bin/sh 호환 ($() / local 미사용)
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
SCB_QUIET=1
. $CLIENT_HOME/lib/scb_client_common.sh

path=$1
lines=$2

case "$lines" in
    ''|*[!0-9]*) lines=200 ;;
esac
[ $lines -gt 1000 ] && lines=1000
[ $lines -lt 1 ] && lines=10

case "$path" in
    /*) ;;
    *) echo "DENIED: 절대 경로가 아닙니다: '$path'"; exit 4 ;;
esac
case "$path" in
    *..*|*//*|*/) echo "DENIED: 허용되지 않는 경로 형식: '$path'"; exit 4 ;;
    *[!A-Za-z0-9_./-]*) echo "DENIED: 허용되지 않는 문자가 있습니다: '$path'"; exit 4 ;;
esac

# 허용 목록 검사 (패턴이 파일 이름으로 확장되지 않도록 set -f)
allowed=0
set -f
for pat in $SCB_LOG_ALLOW; do
    case "$path" in
        $pat) allowed=1; break ;;
    esac
done
set +f
if [ $allowed -ne 1 ]; then
    echo "DENIED: 이 서버의 허용 목록(SCB_LOG_ALLOW)에 없는 경로입니다: $path"
    echo "        허용하려면 $CLIENT_HOME/etc/client.env 의 SCB_LOG_ALLOW 에 추가하세요."
    exit 4
fi

if [ ! -f "$path" ]; then
    echo "NOT_FOUND: 파일이 없습니다: $path"
    exit 3
fi
if [ ! -r "$path" ]; then
    echo "ERROR: 읽기 권한이 없습니다: $path"
    exit 5
fi

# Solaris /usr/bin/tail 은 끝에서 읽는 양이 버퍼 크기로 제한되므로 xpg4 tail 우선
TAIL=tail
[ -x /usr/xpg4/bin/tail ] && TAIL=/usr/xpg4/bin/tail
if [ "$TAIL" = "tail" ] && [ "$OS_NAME" = "SunOS" ]; then
    tail -$lines "$path" 2>&1 | cut -c1-2000 | dd bs=1024 count=256 2>/dev/null
else
    $TAIL -n $lines "$path" 2>&1 | cut -c1-2000 | dd bs=1024 count=256 2>/dev/null
fi
exit 0
