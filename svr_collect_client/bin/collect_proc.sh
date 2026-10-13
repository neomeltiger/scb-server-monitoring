#!/bin/sh
#=============================================================
# collect_proc.sh - [proc] 화면에 등록된 프로세스의 실행 상태
#   감시 목록은 웹 서버 설정(run/config.txt 의 proc|ID|이름|실행경로)에서 받음
#   출력 : 프로세스ID|Y|PID|CPU%|MEM%   (중지면 프로세스ID|N|||)
#   네트워크 구성도 상세 패널의 주요 프로세스, 메인 홈 프로세스 그래프에 사용
# Solaris 10 /bin/sh 호환 (pgrep -f, ps -o pcpu,pmem)
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
. $CLIENT_HOME/lib/scb_client_common.sh

OIFS=$IFS
echo "[proc]"
[ -f $RUN_DIR/config.txt ] || exit 0
grep '^proc|' $RUN_DIR/config.txt > $RUN_DIR/proc.list 2>/dev/null
[ -s $RUN_DIR/proc.list ] || exit 0

# Bourne sh : 'while ... done < 파일' 은 서브셸이 되므로 exec 로 stdin 전환
exec 3<&0 < $RUN_DIR/proc.list
while IFS='|' read tag procid name path; do
    IFS=$OIFS
    case "$procid" in
        ''|*[!0-9]*) continue ;;
    esac
    [ -n "$path" ] || continue
    pid=`pgrep -f "$path" 2>/dev/null | head -1`
    if [ -n "$pid" ]; then
        usage=`ps -o pcpu,pmem -p $pid 2>/dev/null | tail -1 | $NAWK 'NF >= 2 {printf "%s|%s", $1, $2}'`
        [ -n "$usage" ] || usage="0.0|0.0"
        echo "$procid|Y|$pid|$usage"
    else
        echo "$procid|N|||"
    fi
done
IFS=$OIFS
exec 0<&3 3<&-
exit 0
