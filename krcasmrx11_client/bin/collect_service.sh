#!/bin/sh
#=============================================================
# collect_service.sh - [service] service.list 서비스 상태 + CPU / MEM
#   krcasmrx11_agent 의 scb_main.sh status all 결과에 서비스 PID 의 CPU / MEM 을 붙임
#   출력 : 서비스명|RUNNING|PID|CPU|MEM   또는  서비스명|STOPPED|PID|0|0
#          (실행 중인데 ps 에 PID 가 없으면 CPU / MEM 칸은 비움)
#   프로세스 관리 화면의 상태/PID, 메인 홈의 서비스 CPU 그래프에 사용
#   ps 는 한 번만 실행 (ps -eo pid,pcpu,pmem)
# Solaris 10 /bin/sh 호환
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
. $CLIENT_HOME/lib/scb_client_common.sh

echo "[service]"
MAIN=$SCB_AGENT_HOME/bin/scb_main.sh
if [ ! -f $MAIN ]; then
    echo "# scb_main.sh 없음: $MAIN (client.env 의 SCB_AGENT_HOME 확인)"
    exit 0
fi
ST=$RUN_DIR/svc_status.$$
PS=$RUN_DIR/svc_ps.$$
# scb_main.sh status 출력 : NO GROUP LEVEL SERVICE ACCOUNT STATE PID
sh $MAIN status all < /dev/null 2>/dev/null | \
    $NAWK '$6 == "RUNNING" || $6 == "STOPPED" { print $4 "|" $6 "|" $7 }' > $ST
ps -eo pid,pcpu,pmem > $PS 2>/dev/null
$NAWK -v psf=$PS '
    FILENAME == psf {
        n = split($0, a, " ")
        if (a[1] ~ /^[0-9]+$/) { cpu[a[1]] = a[2]; mem[a[1]] = a[3] }
        next
    }
    {
        n = split($0, f, "|")
        if (f[2] == "RUNNING") {
            if (f[3] in cpu) print f[1] "|RUNNING|" f[3] "|" cpu[f[3]] "|" mem[f[3]]
            else             print f[1] "|RUNNING|" f[3] "||"
        } else {
            print f[1] "|STOPPED|" f[3] "|0|0"
        }
    }' $PS $ST
rm -f $ST $PS
exit 0
