#!/bin/sh
#=============================================================
# collect_ps.sh - [ps] 서버에서 실행 중인 전체 프로세스 (krcasmrx11_client 1.8~)
#   데몬이 멈춘 동안 하트비트 감시자가 대신 수집할 때만 사용 (scb_heartbeat.sh → 웹 "데몬 중단 프로세스 현황")
#   출력 : PID|PPID|사용자|CPU%|MEM%|경과시간|명령행   (CPU 높은 순, 최대 SCB_PS_MAX 건 - 기본 300)
#   제외 : 하트비트 감시자(scb_heartbeat.sh)와 그 수집 작업 (collect_*.sh, ps, sleep 등 감시자·이 스크립트의 자식)
# Solaris 10 /bin/sh 호환 (ps -eo, nawk)
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
. $CLIENT_HOME/lib/scb_client_common.sh

PS_MAX=${SCB_PS_MAX:-300}
HB=`cat $RUN_DIR/heartbeat.pid 2>/dev/null`
OUT=$RUN_DIR/ps.out.$$

echo "[ps]"
ps -eo pid,ppid,user,pcpu,pmem,etime,args > $OUT 2>/dev/null || { rm -f $OUT; exit 0; }
# 1차 : 감시자·수집 작업 PID 모음 (PID 파일 + 명령행), 2차 : 그 프로세스와 자식을 빼고 출력
$NAWK -v me=$$ -v hb="$HB" '
    FNR == 1 { next }
    NR == FNR {
        args = $7
        for (i = 8; i <= NF; i++) args = args " " $i
        if ($1 == hb || $1 == me || args ~ /scb_heartbeat\.sh|collect_(metric|proc|service|ps)\.sh/) skip[$1] = 1
        next
    }
    NF >= 7 {
        pid = $1; ppid = $2
        args = $7
        for (i = 8; i <= NF; i++) args = args " " $i
        if (pid in skip || ppid in skip) next
        gsub(/\|/, "/", args)
        if (length(args) > 300) args = substr(args, 1, 300)
        printf "%s|%s|%s|%s|%s|%s|%s\n", pid, ppid, $3, $4, $5, $6, args
    }' $OUT $OUT | sort -t'|' -k4,4nr | head -$PS_MAX
rm -f $OUT
exit 0
