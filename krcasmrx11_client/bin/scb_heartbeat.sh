#!/bin/sh
#=============================================================
# scb_heartbeat.sh - 하트비트 감시자 (krcasmrx11_client 1.5~)
# 대상 : Solaris 10 (SPARC) /bin/sh (Bourne shell) 호환
#
# scb_client.sh start 가 함께 실행합니다. (직접 실행할 일은 없음, 하나만 실행됨)
#   Usage: scb_heartbeat.sh
#
# 동작
#   웹이 정한 주기(기본 10초, 네트워크 구성도 화면에서 3 / 5 / 10 / 15 / 30 / 60초)마다
#     GET /agent-api/v1/heartbeat?pid=<데몬 PID>&daemon=<데몬 상태>
#       데몬 상태 : RUNNING / PAUSED (실행 중) / STOPPED (정상 정지 - PID 파일 없음) / DEAD (PID 파일은 있는데 프로세스 없음)
#   데몬과 따로 돌기 때문에 데몬이 죽어도 하트비트는 계속 갑니다.
#     → 웹은 "서버는 정상, 데몬만 문제" 로 보고 서버를 Down 으로 표시하지 않고 데몬 재시작 알림을 띄움
#     → 하트비트 자체가 3회 연속 오지 않을 때만 서버 다운으로 판단 (서버 전원·네트워크 문제)
#   응답 hb|interval=N|timeout=T|restart=Y/N
#     interval  다음 전송 주기 (바로 적용)
#     restart=Y 웹 [데몬 재시작] - 이 감시자가 bin/scb_client.sh start 로 데몬을 시작
#   전송 실패는 처음 한 번과 다시 연결될 때만 로그에 남깁니다.
#   끝내려면 : bin/scb_client.sh stopall (데몬 + 하트비트) - 서버 종료 시 rc 스크립트가 실행
#
# 상태 파일 : run/heartbeat.pid, run/heartbeat.state (주기, 데몬 상태, 마지막 성공, 연속 실패)
#=============================================================
BIN_DIR=`dirname $0`
CLIENT_HOME=`cd $BIN_DIR/.. && pwd`
BIN_DIR=$CLIENT_HOME/bin
[ -t 1 ] || SCB_QUIET=1
. $CLIENT_HOME/lib/scb_client_common.sh

HB_PID_FILE=$RUN_DIR/heartbeat.pid
HB_STATE=$RUN_DIR/heartbeat.state
HB_OUT=$RUN_DIR/heartbeat.resp
DAEMON_PID_FILE=$RUN_DIR/daemon.pid
DAEMON_STATE_FILE=$RUN_DIR/daemon.state

# 이미 실행 중이면 끝냄 (감시자는 하나만)
op=`cat $HB_PID_FILE 2>/dev/null`
if [ -n "$op" ] && [ "$op" != "$$" ] && kill -0 $op 2>/dev/null; then
    echo "하트비트 감시자가 이미 실행 중입니다 (pid $op)"
    exit 0
fi
echo $$ > $HB_PID_FILE

HB_INTERVAL=10
# 하트비트는 짧게 기다림 (늦게 도착한 하트비트는 의미 없음), 실패 로그는 직접 남김
SCB_HTTP_TIMEOUT=${SCB_HB_HTTP_TIMEOUT:-5}
HTTP_QUIET=1
FAIL=0
LAST_OK=""
DSTATE=STOPPED
DPID=""
PREV_DSTATE=""

# 데몬 상태 → DSTATE / DPID
check_daemon() {
    DPID=`cat $DAEMON_PID_FILE 2>/dev/null`
    if [ -z "$DPID" ]; then
        DSTATE=STOPPED
    elif kill -0 $DPID 2>/dev/null; then
        DSTATE=RUNNING
        grep '^state=PAUSED' $DAEMON_STATE_FILE > /dev/null 2>&1 && DSTATE=PAUSED
    else
        DSTATE=DEAD
    fi
}

write_hb_state() {
    {
        echo "pid=$$"
        echo "interval=$HB_INTERVAL"
        echo "daemon=$DSTATE"
        echo "daemon_pid=$DPID"
        echo "last_ok=$LAST_OK"
        echo "fail=$FAIL"
    } > $HB_STATE 2>/dev/null
}

finish() {
    clog "HEARTBEAT stop pid=$$"
    [ "`cat $HB_PID_FILE 2>/dev/null`" = "$$" ] && rm -f $HB_PID_FILE
    rm -f $HB_OUT
    exit 0
}
trap finish 1 2 15
clog "HEARTBEAT start pid=$$ (감시자 - 데몬과 따로 실행)"

while :; do
    check_daemon
    if [ "$DSTATE" != "$PREV_DSTATE" ]; then
        case "$DSTATE" in
            DEAD) clog "HEARTBEAT 데몬 비정상 종료 감지 (pid $DPID 없음) - 웹에 알림" ;;
            STOPPED) [ -n "$PREV_DSTATE" ] && clog "HEARTBEAT 데몬 정지됨 - 웹에 알림" ;;
            RUNNING) [ -n "$PREV_DSTATE" ] && clog "HEARTBEAT 데몬 실행 확인 (pid $DPID)" ;;
        esac
        PREV_DSTATE=$DSTATE
    fi

    if http_call GET "/agent-api/v1/heartbeat?pid=$DPID&daemon=$DSTATE" $HB_OUT; then
        line=`sed -n 's/^hb|//p' $HB_OUT | head -1`
        n=`echo "$line" | sed -n 's/.*interval=\([0-9]*\).*/\1/p'`
        case "$n" in
            ''|*[!0-9]*) ;;
            *)
                if [ "$n" -ge 1 ] && [ "$n" -ne $HB_INTERVAL ]; then
                    clog "HEARTBEAT 주기 ${HB_INTERVAL}s → ${n}s (웹)"
                    HB_INTERVAL=$n
                fi
                ;;
        esac
        case "$line" in
            *restart=Y*)
                if [ "$DSTATE" = "DEAD" ] || [ "$DSTATE" = "STOPPED" ]; then
                    clog "HEARTBEAT 웹 재시작 요청 → bin/scb_client.sh start"
                    sh $BIN_DIR/scb_client.sh start > /dev/null 2>&1 < /dev/null &
                fi
                ;;
        esac
        [ $FAIL -gt 0 ] && clog "HEARTBEAT 다시 연결 (연속 실패 ${FAIL}회 후)"
        FAIL=0
        LAST_OK=`date '+%Y-%m-%d %H:%M:%S'`
    else
        FAIL=`expr $FAIL + 1`
        [ $FAIL -eq 1 ] && clog "HEARTBEAT 전송 실패 (HTTP $HTTP_STATUS) - 웹 서버 연결 / 토큰을 확인하세요"
    fi
    write_hb_state

    # 다음 전송까지 대기 (1초 단위 - 정지 신호에 바로 반응)
    i=0
    while [ $i -lt $HB_INTERVAL ]; do
        sleep 1
        i=`expr $i + 1`
    done
done
