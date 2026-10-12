#!/bin/sh
#=============================================================
# scb_client.sh - krcasmrx11_client 메인 (서버 → 웹 통합 모니터링 연동)
# 대상 : Solaris 10 (SPARC) /bin/sh (Bourne shell) 호환
# 실행 방식 : 데몬 (crontab 사용 안 함)
#
# Usage: scb_client.sh <command>
#   start      자료수집 데몬 + 하트비트 감시자 시작 (백그라운드)
#   stop       데몬 정지 (웹에 종료 보고 후 끝남) - 하트비트 감시자는 계속 실행 (서버 정상, 데몬 정지로 표시)
#   restart    데몬 다시 시작
#   stopall    데몬 + 하트비트 감시자 모두 정지 (서버 종료 / 점검 시, rc 스크립트 stop)
#   install-boot  서버 부팅 시 자동 시작 설치 (root, /etc/init.d/scb_client + rc 링크) - 서버가 되살아나면 자동 복구
#   status     데몬 실행 여부 / 수집 상태 / 수집 주기 / 마지막 확인·보고 / 하트비트
#   daemon     데몬을 이 터미널에서 실행 (start 가 내부적으로 사용, 점검용)
#   ping       웹 서버 연결 / 토큰 확인
#   heartbeat  하트비트 1회 전송 (점검용) - 응답 hb|interval=주기|timeout=끊김 기준
#   config     웹 서버 설정 받기 (수집 주기, 감시 프로세스, 동기화 대상)  → run/config.txt
#   collect    수집 결과를 화면에 출력 (전송하지 않음, 점검용)
#   report     수집 + 웹 서버로 1회 전송 (점검용, 데몬 실행 중에는 불가)
#   commands   웹 확인 1회 : 제어 명령 실행 + 요청된 서버 로그 전송 (점검용)
#
# 데몬 동작
#   10초(POLL_SEC)마다 웹 확인(heartbeat) : GET /agent-api/v1/commands?state=RUNNING|PAUSED&pid=N&ver=V
#     응답 첫 줄 ctl|collect=on|interval=60|shutdown=N 을 매번 적용
#       collect=off  → 자료 수집(보고·로그) 중지, 데몬은 계속 웹 확인 (웹 [시작] 으로 재개)
#       interval=N   → 수집 주기(초) 변경
#       shutdown=Y   → 데몬 종료 (웹 [종료])
#     cmd|...  서비스 제어 명령 실행 (수집 중지 중에도 처리)
#     log|...  서버 로그 요청 (수집 중일 때만)
#   수집 주기마다 자료 수집 → POST /agent-api/v1/report
#   하트비트 감시자 (1.5~) : bin/scb_heartbeat.sh 가 데몬과 따로 실행 → 웹이 정한 주기(기본 10초)마다
#     GET /agent-api/v1/heartbeat?pid=&daemon=RUNNING|PAUSED|STOPPED|DEAD
#     데몬이 죽어도 하트비트는 계속 → 웹은 서버는 정상, 데몬만 문제로 보고 재시작 알림 (웹 [데몬 재시작] → 감시자가 start)
#     하트비트가 3회 연속 안 오면(서버 전원·네트워크) 서버 다운. 감시자가 죽으면 데몬이 다시 띄웁니다.
#   서버 복구 (1.6~) : 서버가 되살아나 하트비트가 다시 가면 웹이 자동 감지
#     데몬이 안 떠 있으면 restart=Y 로 감시자가 자동 시작, 데몬이 떠 있으면 ctl|...|report=Y 로 바로 수집·보고
#     재부팅 후 자동 시작은 부팅 스크립트 필요 → bin/scb_client.sh install-boot (root, 한 번)
#
# 수집 스크립트 (종류별)
#   collect_sys.sh      [sys]      호스트명, OS
#   collect_metric.sh   [metric]   CPU / 메모리 / 디스크 / 세션
#   collect_proc.sh     [proc]     화면에 등록된 프로세스 상태
#   collect_service.sh  [service]  service.list 서비스 상태 (scb_main.sh status)
#   collect_net.sh      [net]      동기화 대상 서버 통신
# 제어 실행 / 로그
#   exec_command.sh     scb_main.sh <명령> <대상> 실행
#   collect_log.sh      요청받은 로그 파일의 마지막 N줄 (SCB_LOG_ALLOW 허용 경로만)
#
# 설정 : etc/client.env (SCB_WEB_URL, SCB_AGENT_TOKEN, SCB_AGENT_HOME, SCB_LOG_ALLOW)
# 로그 : log/scb_client.log    데몬 PID : run/daemon.pid    데몬 상태 : run/daemon.state
# 하트비트 : run/heartbeat.pid, run/heartbeat.state
# 부팅 시 자동 시작 : etc/scb_client.init → /etc/init.d/scb_client, /etc/rc3.d/S99scb_client
#=============================================================
BIN_DIR=`dirname $0`
CLIENT_HOME=`cd $BIN_DIR/.. && pwd`
BIN_DIR=$CLIENT_HOME/bin
# 터미널이 아니면(데몬) 화면 출력 없이 로그만
[ -t 1 ] || SCB_QUIET=1
. $CLIENT_HOME/lib/scb_client_common.sh

OIFS=$IFS
CMD=$1
POLL_SEC=10
PID_FILE=$RUN_DIR/daemon.pid
STATE_FILE=$RUN_DIR/daemon.state
HB_PID_FILE=$RUN_DIR/heartbeat.pid
HB_STATE_FILE=$RUN_DIR/heartbeat.state

usage() {
    sed -n '/^# Usage/,/^# 데몬 동작/p' $0 | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
}

# 수집 → $RUN_DIR/report.txt
build_report() {
    {
        echo "# scb_client report `date '+%Y-%m-%d %H:%M:%S'`"
        sh $BIN_DIR/collect_sys.sh
        sh $BIN_DIR/collect_metric.sh
        sh $BIN_DIR/collect_proc.sh
        sh $BIN_DIR/collect_service.sh
        sh $BIN_DIR/collect_net.sh
    } > $RUN_DIR/report.txt 2>> $LOG_FILE
}

do_report() {
    build_report
    if http_call POST /agent-api/v1/report $RUN_DIR/report.resp $RUN_DIR/report.txt; then
        clog "REPORT sent : `head -1 $RUN_DIR/report.resp`"
        LAST_REPORT=`date '+%Y-%m-%d %H:%M:%S'`
        return 0
    fi
    return 1
}

# 서버 로그 요청 : log|ID|줄수|경로 → collect_log.sh → POST /agent-api/v1/logs/ID?status=..
#   웹 서버가 화면에서 정한 주기(또는 [지금 요청])마다 명령 응답에 넣어 보냄
do_logs() {
    grep '^log|' $RUN_DIR/commands.txt > $RUN_DIR/logs.list 2>/dev/null
    [ -s $RUN_DIR/logs.list ] || return 0
    exec 5<&0 < $RUN_DIR/logs.list
    while IFS='|' read tag lid llines lpath; do
        IFS=$OIFS
        case "$lid" in
            ''|*[!0-9]*) clog "WARN: 잘못된 로그 요청 줄 무시"; continue ;;
        esac
        sh $BIN_DIR/collect_log.sh "$lpath" "$llines" > $RUN_DIR/log_$lid.out 2>&1 < /dev/null
        rc=$?
        case $rc in
            0) lst=OK ;;
            3) lst=NOT_FOUND ;;
            4) lst=DENIED ;;
            *) lst=ERROR ;;
        esac
        clog "LOG #$lid : $lpath ($llines줄) $lst"
        http_call POST "/agent-api/v1/logs/$lid?status=$lst" $RUN_DIR/log_$lid.resp $RUN_DIR/log_$lid.out
        rm -f $RUN_DIR/log_$lid.out $RUN_DIR/log_$lid.resp
    done
    IFS=$OIFS
    exec 0<&5 5<&-
    return 0
}

# 제어 명령 : cmd|ID|명령|대상 → exec_command.sh → 결과 전송. 실행한 건수를 CMD_RAN 에
run_cmds() {
    CMD_RAN=0
    grep '^cmd|' $RUN_DIR/commands.txt > $RUN_DIR/commands.list 2>/dev/null
    [ -s $RUN_DIR/commands.list ] || return 0
    exec 4<&0 < $RUN_DIR/commands.list
    while IFS='|' read tag cid ccmd ctarget; do
        IFS=$OIFS
        case "$cid" in
            ''|*[!0-9]*) clog "WARN: 잘못된 명령 줄 무시"; continue ;;
        esac
        clog "COMMAND #$cid : $ccmd $ctarget"
        sh $BIN_DIR/exec_command.sh "$ccmd" "$ctarget" > $RUN_DIR/cmd_$cid.out 2>&1 < /dev/null
        rc=$?
        clog "COMMAND #$cid : exit=$rc"
        http_call POST "/agent-api/v1/commands/$cid/result?exit=$rc" $RUN_DIR/cmd_$cid.resp $RUN_DIR/cmd_$cid.out
        rm -f $RUN_DIR/cmd_$cid.out $RUN_DIR/cmd_$cid.resp
        CMD_RAN=`expr $CMD_RAN + 1`
    done
    IFS=$OIFS
    exec 0<&4 4<&-
    return 0
}

# 웹 확인 1회 (점검용 commands 명령) : 데몬 정보 없이
do_commands() {
    http_call GET /agent-api/v1/commands $RUN_DIR/commands.txt || return 1
    do_logs
    run_cmds
    [ $CMD_RAN -gt 0 ] && do_report
    return 0
}

# 응답의 ctl|collect=on|interval=60|shutdown=N → CTL_COLLECT / CTL_INTERVAL / CTL_SHUTDOWN
read_ctl() {
    ctl=`sed -n 's/^ctl|//p' $RUN_DIR/commands.txt | head -1`
    CTL_COLLECT=`echo "$ctl" | sed -n 's/.*collect=\([a-z]*\).*/\1/p'`
    CTL_INTERVAL=`echo "$ctl" | sed -n 's/.*interval=\([0-9]*\).*/\1/p'`
    CTL_SHUTDOWN=`echo "$ctl" | sed -n 's/.*shutdown=\([YN]\).*/\1/p'`
    CTL_REPORT=`echo "$ctl" | sed -n 's/.*report=\([YN]\).*/\1/p'`
}

daemon_pid() {
    dpid=`cat $PID_FILE 2>/dev/null`
    if pid_is "$dpid" scb_client.sh; then
        echo $dpid
        return 0
    fi
    return 1
}

write_state() {
    {
        echo "pid=$$"
        echo "state=$DSTATE"
        echo "interval=$CFG_INTERVAL"
        echo "last_poll=$LAST_POLL"
        echo "last_report=$LAST_REPORT"
        echo "web=$SCB_WEB_URL"
    } > $STATE_FILE 2>/dev/null
}

# ------------------------------------------------------------
# 하트비트 감시자 (데몬과 따로 실행 - 데몬이 죽어도 계속 웹에 알림)
# ------------------------------------------------------------
hb_running() {
    hp=`cat $HB_PID_FILE 2>/dev/null`
    pid_is "$hp" scb_heartbeat.sh
}

# 감시자가 없으면 시작 (start, 데몬 루프에서 확인)
hb_ensure() {
    hb_running && return 0
    [ -n "$1" ] && clog "$1"
    nohup sh $BIN_DIR/scb_heartbeat.sh > /dev/null 2>&1 < /dev/null &
}

hb_stop() {
    hp=`cat $HB_PID_FILE 2>/dev/null`
    if [ -n "$hp" ] && kill -0 $hp 2>/dev/null; then
        kill $hp 2>/dev/null
        echo "하트비트 감시자 정지 (pid $hp)"
    fi
    rm -f $HB_PID_FILE
}

# ------------------------------------------------------------
# 자료수집 데몬 (포그라운드 루프 - start 가 nohup 으로 백그라운드 실행)
# ------------------------------------------------------------
daemon_main() {
    acquire_lock || { clog "ERROR: 데몬이 이미 실행 중입니다 (pid `cat $PID_FILE 2>/dev/null`)"; exit 1; }
    echo $$ > $PID_FILE
    STOP_REQ=0
    STOP_REASON="signal"
    trap 'STOP_REQ=1' 1 2 15
    LAST_POLL=""
    LAST_REPORT=""
    load_config
    DSTATE=RUNNING
    [ "$CFG_COLLECT" = "off" ] && DSTATE=PAUSED
    clog "DAEMON start pid=$$ ver=$CLIENT_VER interval=${CFG_INTERVAL}s collect=$DSTATE web=$SCB_WEB_URL"
    hb_ensure
    elapsed=$CFG_INTERVAL     # 시작하면 바로 1회 보고

    while [ $STOP_REQ -eq 0 ]; do
        # 1) 웹 확인 (heartbeat) - 웹 설정 적용, 명령/로그 처리
        if http_call GET "/agent-api/v1/commands?state=$DSTATE&pid=$$&ver=$CLIENT_VER" $RUN_DIR/commands.txt; then
            LAST_POLL=`date '+%Y-%m-%d %H:%M:%S'`
            read_ctl
            if [ "$CTL_SHUTDOWN" = "Y" ]; then
                clog "DAEMON 웹에서 종료 요청"
                STOP_REASON="web"
                break
            fi
            if [ "$CTL_COLLECT" = "off" ] && [ "$DSTATE" = "RUNNING" ]; then
                DSTATE=PAUSED
                clog "DAEMON 자료 수집 중지 (웹)"
            elif [ "$CTL_COLLECT" = "on" ] && [ "$DSTATE" = "PAUSED" ]; then
                DSTATE=RUNNING
                elapsed=$CFG_INTERVAL
                clog "DAEMON 자료 수집 시작 (웹)"
            fi
            case "$CTL_INTERVAL" in
                ''|*[!0-9]*) ;;
                *)
                    if [ "$CTL_INTERVAL" -ge $POLL_SEC ] && [ "$CTL_INTERVAL" -ne "$CFG_INTERVAL" ]; then
                        clog "DAEMON 수집 주기 ${CFG_INTERVAL}s → ${CTL_INTERVAL}s (웹)"
                        CFG_INTERVAL=$CTL_INTERVAL
                        [ $elapsed -gt $CFG_INTERVAL ] && elapsed=$CFG_INTERVAL
                    fi
                    ;;
            esac
            [ "$DSTATE" = "RUNNING" ] && do_logs
            run_cmds
            # 명령 실행 후 바뀐 상태를 바로 화면에 반영 / 서버 복구 직후(웹 report=Y) 바로 수집·보고
            if [ "$DSTATE" = "RUNNING" ] && { [ $CMD_RAN -gt 0 ] || [ "$CTL_REPORT" = "Y" ]; }; then
                [ "$CTL_REPORT" = "Y" ] && clog "DAEMON 서버 복구 - 웹 요청으로 바로 수집·보고"
                do_report
                elapsed=0
            fi
        fi

        # 2) 수집 주기가 되면 자료 수집 · 보고
        if [ "$DSTATE" = "RUNNING" ] && [ $elapsed -ge $CFG_INTERVAL ]; then
            load_config > /dev/null 2>&1
            do_report
            elapsed=0
        fi
        write_state
        hb_ensure "WARN: 하트비트 감시자가 없어 다시 시작합니다"

        # 3) 다음 확인까지 대기 (1초 단위 - 정지 신호에 바로 반응)
        i=0
        while [ $i -lt $POLL_SEC ] && [ $STOP_REQ -eq 0 ]; do
            sleep 1
            i=`expr $i + 1`
        done
        elapsed=`expr $elapsed + $POLL_SEC`
    done

    # 종료 : 웹에 알림 (하트비트 감시자는 계속 실행 - 웹은 서버 정상 / 데몬 정지로 표시)
    : > $RUN_DIR/empty.txt
    http_call POST "/agent-api/v1/daemon?state=STOPPED" $RUN_DIR/daemon.resp $RUN_DIR/empty.txt
    DSTATE=STOPPED
    write_state
    clog "DAEMON stop pid=$$ ($STOP_REASON)"
    rm -f $PID_FILE $RUN_DIR/empty.txt $RUN_DIR/daemon.resp
    release_lock
    exit 0
}

daemon_start() {
    if p=`daemon_pid`; then
        echo "이미 실행 중입니다 (pid $p)"
        hb_ensure
        return 0
    fi
    [ -n "$SCB_WEB_URL" ] && [ -n "$SCB_AGENT_TOKEN" ] || { echo "etc/client.env 의 SCB_WEB_URL / SCB_AGENT_TOKEN 을 먼저 설정하세요."; return 1; }
    nohup sh $BIN_DIR/scb_client.sh daemon > /dev/null 2>&1 < /dev/null &
    n=0
    while [ $n -lt 5 ]; do
        sleep 1
        if p=`daemon_pid`; then
            echo "자료수집 데몬 시작 (pid $p) - 로그 : $LOG_FILE"
            hb_ensure
            hb_running && echo "하트비트 감시자 실행 중 (pid `cat $HB_PID_FILE`)"
            return 0
        fi
        n=`expr $n + 1`
    done
    echo "데몬 시작 실패 - $LOG_FILE 를 확인하세요."
    return 1
}

daemon_stop() {
    if p=`daemon_pid`; then
        :
    else
        echo "실행 중인 데몬이 없습니다."
        rm -f $PID_FILE
        return 0
    fi
    kill $p 2>/dev/null
    n=0
    while [ $n -lt 20 ]; do
        kill -0 $p 2>/dev/null || { echo "데몬 정지 (pid $p)"; return 0; }
        sleep 1
        n=`expr $n + 1`
    done
    kill -9 $p 2>/dev/null
    rm -f $PID_FILE
    release_lock
    clog "DAEMON 강제 정지 (pid $p)"
    echo "응답이 없어 강제 정지했습니다 (pid $p)"
    return 0
}

# 서버 부팅 시 자동 시작 설치 (root) : 서버가 되살아나면 데몬·감시자가 자동으로 떠서 웹이 바로 복구 감지
install_boot() {
    case "`id`" in
        uid=0\(*) ;;
        *) echo "root 로 실행하세요."; return 1 ;;
    esac
    if [ ! -d /etc/init.d ] || [ ! -d /etc/rc3.d ]; then
        echo "/etc/init.d, /etc/rc3.d 가 없습니다. (Solaris 10 rc 스크립트 방식이 아님)"
        return 1
    fi
    sed "s,^CLIENT_HOME=.*,CLIENT_HOME=$CLIENT_HOME," $CLIENT_HOME/etc/scb_client.init > /etc/init.d/scb_client || return 1
    chmod 744 /etc/init.d/scb_client
    for l in /etc/rc3.d/S99scb_client /etc/rc0.d/K01scb_client /etc/rc1.d/K01scb_client /etc/rcS.d/K01scb_client; do
        [ -d `dirname $l` ] || continue
        rm -f $l
        ln -s /etc/init.d/scb_client $l
    done
    clog "부팅 자동 시작 설치 : /etc/init.d/scb_client (CLIENT_HOME=$CLIENT_HOME)"
    echo "부팅 자동 시작 설치 완료 : /etc/init.d/scb_client, /etc/rc3.d/S99scb_client (종료 : rc0/rc1/rcS K01)"
    echo "다음 하트비트부터 웹 화면에 '부팅 자동 시작 : 설치됨' 으로 표시됩니다."
    return 0
}

daemon_status() {
    if p=`daemon_pid`; then
        echo "자료수집 데몬 : 실행 중 (pid $p)"
        rc=0
    else
        echo "자료수집 데몬 : 정지"
        rc=3
    fi
    if [ -f $STATE_FILE ]; then
        sed -n 's/^state=/  수집 상태   : /p; s/^interval=\(.*\)/  수집 주기   : \1초/p; s/^last_poll=/  마지막 확인 : /p; s/^last_report=/  마지막 보고 : /p; s/^web=/  웹 서버     : /p' $STATE_FILE
    fi
    if hb_running; then
        echo "하트비트 감시자 : 실행 중 (pid $hp)"
    else
        echo "하트비트 감시자 : 정지  (시작 : bin/scb_client.sh start)"
    fi
    if [ -f $HB_STATE_FILE ]; then
        sed -n 's/^interval=\(.*\)/  하트비트 주기   : \1초/p; s/^daemon=/  알린 데몬 상태  : /p; s/^last_ok=/  마지막 전송 성공 : /p; s/^fail=/  연속 실패       : /p' $HB_STATE_FILE
    fi
    if [ "`boot_auto_installed`" = "Y" ]; then
        echo "부팅 자동 시작 : 설치됨 (/etc/init.d/scb_client)"
    else
        echo "부팅 자동 시작 : 미설치 - 서버 재부팅 후 자동 복구 안 됨 (root : bin/scb_client.sh install-boot)"
    fi
    bt=`boot_time`
    [ -n "$bt" ] && echo "서버 부팅 시각 : $bt"
    return $rc
}

case "$CMD" in
    start)   daemon_start; exit $? ;;
    stop)    daemon_stop; exit $? ;;
    stopall) daemon_stop; hb_stop; exit 0 ;;
    install-boot) install_boot; exit $? ;;
    restart) daemon_stop; sleep 1; daemon_start; exit $? ;;
    status)  daemon_status; exit $? ;;
    daemon|loop) daemon_main ;;
    heartbeat)
        if p=`daemon_pid`; then hd=RUNNING; else hd=STOPPED; fi
        bt=`boot_time | sed 's/ /+/g'`
        http_call GET "/agent-api/v1/heartbeat?pid=`cat $PID_FILE 2>/dev/null`&daemon=$hd&boot=`boot_auto_installed`&btime=$bt" $RUN_DIR/hb_test.txt || exit 1
        cat $RUN_DIR/hb_test.txt
        rm -f $RUN_DIR/hb_test.txt
        exit 0
        ;;
    ping)
        http_call GET /agent-api/v1/ping $RUN_DIR/ping.txt || exit 1
        cat $RUN_DIR/ping.txt
        echo "http tool : $HTTP_TOOL ($HTTP_BIN)"
        echo "agent home: $SCB_AGENT_HOME"
        exit 0
        ;;
    config)
        load_config
        cat $RUN_DIR/config.txt
        exit 0
        ;;
    collect)
        load_config
        build_report
        cat $RUN_DIR/report.txt
        exit 0
        ;;
    report|commands|run)
        acquire_lock || { echo "데몬이 실행 중입니다. (점검 명령은 데몬을 정지한 뒤 사용 : scb_client.sh stop)"; exit 1; }
        load_config
        case "$CMD" in
            report)   do_report ;;
            commands) do_commands ;;
            run)      do_report; do_commands ;;
        esac
        rc=$?
        release_lock
        exit $rc
        ;;
    *)
        usage
        ;;
esac
