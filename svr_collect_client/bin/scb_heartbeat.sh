#!/bin/sh
#=============================================================
# scb_heartbeat.sh - 하트비트 감시자 (svr_collect_client 1.5~, 서버 복구 감지 1.6~)
# 대상 : Solaris 10 (SPARC) /bin/sh (Bourne shell) 호환
#
# scb_client.sh start 가 함께 실행합니다. (직접 실행할 일은 없음, 하나만 실행됨)
#   Usage: scb_heartbeat.sh
#
# 동작
#   웹이 정한 주기(기본 10초, 네트워크 구성도 화면에서 3 / 5 / 10 / 15 / 30 / 60초)마다
#     GET /agent-api/v1/heartbeat?pid=<데몬 PID>&daemon=<데몬 상태>&boot=<Y|N>&btime=<부팅 시각>&hbver=<감시자 버전>&fb=<대신 수집 결과>
#       hbver/fb (1.9~) : 웹 "데몬 중단 프로세스 현황" 이 정보가 없을 때 원인(이전 감시자, 전송 실패 코드)을 보여 줌
#       데몬 상태 : RUNNING / PAUSED (실행 중) / STOPPED (정상 정지 - PID 파일 없음) / DEAD (PID 파일은 있는데 프로세스 없음)
#       boot      : 서버 부팅 시 자동 시작(rc 스크립트) 설치 여부, btime : 서버 부팅 시각 (바뀌면 웹이 재부팅 감지)
#   서버 복구 : 서버가 되살아나 하트비트가 다시 가면 웹이 감지 → 데몬이 멈춰 있으면 restart=Y (자동 재시작)
#   데몬 중단 중 프로세스 현황 (1.7~) : 데몬이 DEAD / STOPPED 이면 SCB_FALLBACK_SEC(기본 30초)마다
#     collect_metric / collect_proc / collect_service / collect_ps(1.8~ 서버 전체 실행 프로세스, 감시자 제외) 로
#     대신 수집해 POST /agent-api/v1/fallback (백그라운드 - 하트비트는 늦어지지 않음)
#     → 웹 "데몬 중단 프로세스 현황" 화면에 표시, 데몬이 다시 뜨면 웹이 삭제
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

# 이미 실행 중이면 끝냄 (감시자는 하나만, 재부팅 뒤 남은 PID 파일은 무시)
op=`cat $HB_PID_FILE 2>/dev/null`
if [ -n "$op" ] && [ "$op" != "$$" ] && pid_is "$op" scb_heartbeat.sh; then
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
BOOT_TIME=`boot_time | sed 's/ /+/g'`
FB_SEC=${SCB_FALLBACK_SEC:-30}
FB_ELAPSED=$FB_SEC
FB_PID_FILE=$RUN_DIR/fallback.pid
FB_STAT_FILE=$RUN_DIR/fallback.stat
FB_STAT=NONE
FB_BUSY=0
rm -f $FB_STAT_FILE

# 데몬 대신 프로세스 정보 수집 → 웹 (백그라운드, 이전 수집이 끝나지 않았으면 건너뜀)
fallback_collect() {
    fp=`cat $FB_PID_FILE 2>/dev/null`
    if pid_is "$fp" scb_heartbeat.sh; then
        # 이전 수집이 4번(약 2분) 넘게 끝나지 않으면 정리하고 다시 시도 (scb_main.sh status 응답 없음 등)
        FB_BUSY=`expr $FB_BUSY + 1`
        [ $FB_BUSY -lt 4 ] && return 0
        kill $fp 2>/dev/null
        clog "WARN: 대신 수집이 끝나지 않아 정리합니다 (pid $fp) - 수집 스크립트(scb_main.sh status 등) 응답을 확인하세요"
        echo FAIL_TIMEOUT > $FB_STAT_FILE
        rm -f $FB_PID_FILE
    fi
    FB_BUSY=0
    (
        {
            echo "# scb_client fallback `date '+%Y-%m-%d %H:%M:%S'` (daemon $DSTATE - collected by heartbeat watchdog)"
            sh $BIN_DIR/collect_metric.sh
            sh $BIN_DIR/collect_proc.sh
            sh $BIN_DIR/collect_service.sh
            sh $BIN_DIR/collect_ps.sh
        } > $RUN_DIR/fallback.txt 2>> $LOG_FILE
        if http_call POST /agent-api/v1/fallback $RUN_DIR/fallback.resp $RUN_DIR/fallback.txt; then
            echo OK > $FB_STAT_FILE
            if [ ! -f $RUN_DIR/fallback.sent ]; then
                clog "HEARTBEAT 데몬 중단 - 프로세스 정보를 대신 수집해 웹에 보냅니다 (${FB_SEC}초마다)"
                : > $RUN_DIR/fallback.sent
            fi
        else
            prev=`cat $FB_STAT_FILE 2>/dev/null`
            echo "FAIL_$HTTP_STATUS" > $FB_STAT_FILE
            [ "$prev" != "FAIL_$HTTP_STATUS" ] && clog "WARN: 대신 수집 전송 실패 (HTTP $HTTP_STATUS) `head -1 $RUN_DIR/fallback.resp 2>/dev/null`"
        fi
        rm -f $FB_PID_FILE $RUN_DIR/fallback.resp
    ) > /dev/null 2>&1 < /dev/null &
    echo $! > $FB_PID_FILE
}

# 데몬 상태 → DSTATE / DPID
check_daemon() {
    DPID=`cat $DAEMON_PID_FILE 2>/dev/null`
    if [ -z "$DPID" ]; then
        DSTATE=STOPPED
    elif pid_is "$DPID" scb_client.sh; then
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
        echo "ver=$CLIENT_VER"
        echo "env=$ENV_SIG"
        echo "fb=$FB_STAT"
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
ENV_SIG=`env_sig`

# client.env 가 바뀌었으면 다시 읽음 (토큰 재발급 / 웹 주소 변경을 감시자 재시작 없이 바로 적용)
reload_env() {
    new_sig=`env_sig`
    [ -n "$new_sig" ] && [ "$new_sig" != "$ENV_SIG" ] || return 0
    . $ENV_FILE
    ENV_SIG=$new_sig
    clog "HEARTBEAT client.env 변경 감지 - 토큰·웹 주소를 다시 읽음 (web=$SCB_WEB_URL)"
}

while :; do
    reload_env
    check_daemon
    if [ "$DSTATE" != "$PREV_DSTATE" ]; then
        case "$DSTATE" in
            DEAD) clog "HEARTBEAT 데몬 비정상 종료 감지 (pid $DPID 없음) - 웹에 알림" ;;
            STOPPED) [ -n "$PREV_DSTATE" ] && clog "HEARTBEAT 데몬 정지됨 - 웹에 알림" ;;
            RUNNING) [ -n "$PREV_DSTATE" ] && clog "HEARTBEAT 데몬 실행 확인 (pid $DPID)" ;;
        esac
        PREV_DSTATE=$DSTATE
    fi

    case "$DSTATE" in
        DEAD|STOPPED) FB_STAT=`cat $FB_STAT_FILE 2>/dev/null`; [ -n "$FB_STAT" ] || FB_STAT=WAIT ;;
        *) FB_STAT=NONE ;;
    esac
    if http_call GET "/agent-api/v1/heartbeat?pid=$DPID&daemon=$DSTATE&boot=`boot_auto_installed`&btime=$BOOT_TIME&hbver=$CLIENT_VER&fb=$FB_STAT" $HB_OUT; then
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
        if [ $FAIL -eq 1 ]; then
            if [ "$HTTP_STATUS" = "401" ]; then
                clog "HEARTBEAT 토큰 인증 실패 (HTTP 401) - etc/client.env 의 SCB_AGENT_TOKEN 을 웹에서 마지막으로 발급한 토큰으로 바꾸세요 (저장하면 자동으로 다시 읽음)"
            else
                clog "HEARTBEAT 전송 실패 (HTTP $HTTP_STATUS) - 웹 서버 연결 / 토큰을 확인하세요"
            fi
        fi
    fi
    write_hb_state

    # 데몬이 멈춰 있으면 프로세스 정보를 대신 수집 (멈춘 직후 바로, 이후 FB_SEC 마다)
    case "$DSTATE" in
        DEAD|STOPPED)
            if [ $FB_ELAPSED -ge $FB_SEC ]; then
                fallback_collect
                FB_ELAPSED=0
            fi
            FB_ELAPSED=`expr $FB_ELAPSED + $HB_INTERVAL`
            ;;
        *)
            FB_ELAPSED=$FB_SEC
            FB_BUSY=0
            rm -f $RUN_DIR/fallback.sent $FB_STAT_FILE
            ;;
    esac

    # 다음 전송까지 대기 (1초 단위 - 정지 신호에 바로 반응)
    i=0
    while [ $i -lt $HB_INTERVAL ]; do
        sleep 1
        i=`expr $i + 1`
    done
done
