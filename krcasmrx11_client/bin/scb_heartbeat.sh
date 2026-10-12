#!/bin/sh
#=============================================================
# scb_heartbeat.sh - 하트비트(생존 신호) 전송 (krcasmrx11_client 1.4~)
# 대상 : Solaris 10 (SPARC) /bin/sh (Bourne shell) 호환
#
# scb_client.sh 데몬이 시작할 때 백그라운드로 함께 실행합니다. (직접 실행할 일은 없음)
#   Usage: scb_heartbeat.sh <데몬 PID>
#
# 동작
#   웹이 정한 주기(기본 10초, 네트워크 구성도 화면에서 3 / 5 / 10 / 15 / 30 / 60초)마다
#     GET /agent-api/v1/heartbeat?pid=<데몬 PID>
#   응답 hb|interval=N|timeout=T 의 N 으로 다음 주기를 바로 바꿉니다.
#   데몬 프로세스가 없어지면(비정상 종료 포함) 1초 안에 끝납니다.
#     → 웹은 T초(주기 x 2 + 3) 동안 하트비트를 못 받으면 "하트비트 끊김" 으로 보고
#       그 서버의 모니터링 대상 프로세스를 즉시 종료로 표시합니다. (서버 다운도 같은 방식으로 감지)
#   자료 수집·제어 명령과 따로 돌기 때문에 수집이 오래 걸려도 하트비트는 늦어지지 않습니다.
#   전송 실패는 처음 한 번과 다시 연결될 때만 로그에 남깁니다.
#
# 상태 파일 : run/heartbeat.state (pid, 데몬 pid, 주기, 마지막 성공, 연속 실패)
#=============================================================
BIN_DIR=`dirname $0`
CLIENT_HOME=`cd $BIN_DIR/.. && pwd`
[ -t 1 ] || SCB_QUIET=1
. $CLIENT_HOME/lib/scb_client_common.sh

DPID=$1
case "$DPID" in
    ''|*[!0-9]*) echo "Usage: scb_heartbeat.sh <daemon pid>"; exit 2 ;;
esac

HB_INTERVAL=10
HB_STATE=$RUN_DIR/heartbeat.state
HB_OUT=$RUN_DIR/heartbeat.resp
# 하트비트는 짧게 기다림 (늦게 도착한 하트비트는 의미 없음), 실패 로그는 직접 남김
SCB_HTTP_TIMEOUT=${SCB_HB_HTTP_TIMEOUT:-5}
HTTP_QUIET=1
FAIL=0
LAST_OK=""

write_hb_state() {
    {
        echo "pid=$$"
        echo "daemon_pid=$DPID"
        echo "interval=$HB_INTERVAL"
        echo "last_ok=$LAST_OK"
        echo "fail=$FAIL"
    } > $HB_STATE 2>/dev/null
}

trap 'rm -f $HB_OUT; exit 0' 1 2 15
clog "HEARTBEAT start pid=$$ daemon=$DPID"

while kill -0 $DPID 2>/dev/null; do
    if http_call GET "/agent-api/v1/heartbeat?pid=$DPID" $HB_OUT; then
        n=`sed -n 's/^hb|.*interval=\([0-9]*\).*/\1/p' $HB_OUT | head -1`
        case "$n" in
            ''|*[!0-9]*) ;;
            *)
                if [ "$n" -ge 1 ] && [ "$n" -ne $HB_INTERVAL ]; then
                    clog "HEARTBEAT 주기 ${HB_INTERVAL}s → ${n}s (웹)"
                    HB_INTERVAL=$n
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

    # 다음 전송까지 대기 (1초 단위 - 데몬이 없어지면 바로 끝냄)
    i=0
    while [ $i -lt $HB_INTERVAL ]; do
        kill -0 $DPID 2>/dev/null || break
        sleep 1
        i=`expr $i + 1`
    done
done

clog "HEARTBEAT stop pid=$$ (데몬 pid $DPID 없음)"
rm -f $HB_OUT
exit 0
