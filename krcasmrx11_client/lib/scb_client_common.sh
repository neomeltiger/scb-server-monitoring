#!/bin/sh
#=============================================================
# scb_client_common.sh - krcasmrx11_client 공통 함수
# Solaris 10 /bin/sh (Bourne shell) 호환: $() / local / $(( )) / grep -q 미사용
#
# 사용: CLIENT_HOME 을 정한 뒤 . $CLIENT_HOME/lib/scb_client_common.sh
#=============================================================
[ -n "$CLIENT_HOME" ] || { echo "CLIENT_HOME not set"; exit 1; }

[ -f $CLIENT_HOME/etc/client.env ] && . $CLIENT_HOME/etc/client.env
SCB_AGENT_HOME=${SCB_AGENT_HOME:-/shared/opt/agent/krcasmrx11_agent}
SCB_HTTP_TIMEOUT=${SCB_HTTP_TIMEOUT:-20}
SCB_HTTP_INSECURE=${SCB_HTTP_INSECURE:-0}
# 웹 서버가 요청할 수 있는 로그 경로 (공백 구분 셸 패턴, client.env 에서 바꿀 수 있음)
SCB_LOG_ALLOW=${SCB_LOG_ALLOW:-"/var/adm/messages /var/adm/messages.[0-9]* /var/log/*.log /var/log/syslog /shared/opt/agent/krcasmrx11_agent/log/* /shared/opt/agent/krcasmrx11_client/log/*"}
CLIENT_VER=1.3
RUN_DIR=$CLIENT_HOME/run
LOG_DIR=$CLIENT_HOME/log
LOG_FILE=$LOG_DIR/scb_client.log
mkdir -p $RUN_DIR $LOG_DIR 2>/dev/null

NAWK=/usr/bin/nawk
[ -x $NAWK ] || NAWK=awk
OS_NAME=`uname -s`

clog() {
    msg="`date '+%Y-%m-%d %H:%M:%S'` [CLIENT] $*"
    echo "$msg" >> $LOG_FILE 2>/dev/null
    [ -n "$SCB_QUIET" ] || echo "$msg"
}

# ------------------------------------------------------------
# HTTP (curl 우선, 없으면 wget - Solaris 10 은 /usr/sfw/bin/wget)
#   http_call <GET|POST> <경로(/agent-api/v1/..)> <응답파일> [본문파일]
#   반환: 0 = HTTP 200, 1 = 실패 (HTTP_STATUS 에 상태코드)
# ------------------------------------------------------------
find_http_tool() {
    HTTP_TOOL=""
    for t in curl /usr/bin/curl /usr/sfw/bin/curl /opt/csw/bin/curl; do
        if command_exists $t; then HTTP_TOOL=curl; HTTP_BIN=$t; return 0; fi
    done
    for t in wget /usr/bin/wget /usr/sfw/bin/wget /opt/csw/bin/wget; do
        if command_exists $t; then HTTP_TOOL=wget; HTTP_BIN=$t; return 0; fi
    done
    return 1
}

command_exists() {
    case "$1" in
        /*) [ -x "$1" ] ;;
        *)  type "$1" > /dev/null 2>&1 ;;
    esac
}

http_call() {
    h_method=$1; h_path=$2; h_out=$3; h_body=$4
    HTTP_STATUS=000
    [ -n "$SCB_WEB_URL" ] || { clog "ERROR: SCB_WEB_URL 미설정 (etc/client.env)"; return 1; }
    [ -n "$SCB_AGENT_TOKEN" ] || { clog "ERROR: SCB_AGENT_TOKEN 미설정 (etc/client.env)"; return 1; }
    [ -n "$HTTP_TOOL" ] || find_http_tool || { clog "ERROR: curl 또는 wget 이 없습니다"; return 1; }
    h_url=`echo "$SCB_WEB_URL" | sed 's,/*$,,'`$h_path
    : > $h_out
    if [ "$HTTP_TOOL" = "curl" ]; then
        h_k=""
        [ "$SCB_HTTP_INSECURE" = "1" ] && h_k="-k"
        if [ "$h_method" = "POST" ]; then
            HTTP_STATUS=`$HTTP_BIN -s $h_k -m $SCB_HTTP_TIMEOUT -o $h_out -w '%{http_code}' \
                -H "X-SCB-Agent-Token: $SCB_AGENT_TOKEN" -H "Content-Type: text/plain; charset=UTF-8" \
                --data-binary @$h_body "$h_url"`
        else
            HTTP_STATUS=`$HTTP_BIN -s $h_k -m $SCB_HTTP_TIMEOUT -o $h_out -w '%{http_code}' \
                -H "X-SCB-Agent-Token: $SCB_AGENT_TOKEN" "$h_url"`
        fi
    else
        h_k=""
        [ "$SCB_HTTP_INSECURE" = "1" ] && h_k="--no-check-certificate"
        h_err=$RUN_DIR/wget.err.$$
        if [ "$h_method" = "POST" ]; then
            $HTTP_BIN -S -q $h_k -T $SCB_HTTP_TIMEOUT -t 1 -O $h_out \
                --header="X-SCB-Agent-Token: $SCB_AGENT_TOKEN" --header="Content-Type: text/plain; charset=UTF-8" \
                --post-file=$h_body "$h_url" 2> $h_err
        else
            $HTTP_BIN -S -q $h_k -T $SCB_HTTP_TIMEOUT -t 1 -O $h_out \
                --header="X-SCB-Agent-Token: $SCB_AGENT_TOKEN" "$h_url" 2> $h_err
        fi
        HTTP_STATUS=`$NAWK '/HTTP\/[0-9.]+ [0-9][0-9][0-9]/ {s=$2} END {print (s=="" ? "000" : s)}' $h_err`
        rm -f $h_err
    fi
    [ "$HTTP_STATUS" = "200" ] && return 0
    case "$HTTP_STATUS" in
        401) clog "ERROR: 토큰 인증 실패 (HTTP 401) - 화면에서 토큰을 다시 발급해 client.env 에 넣으세요" ;;
        000) clog "ERROR: 웹 서버 연결 실패 ($h_url)"
             if [ -z "$HTTP_HINT_SHOWN" ]; then
                 HTTP_HINT_SHOWN=1
                 case "$SCB_WEB_URL" in
                     *://localhost|*://localhost[:/]*|*://127.*|*://\[::1\]*)
                         clog "HINT: SCB_WEB_URL 의 localhost / 127.0.0.1 은 이 서버 자신입니다. 웹 서버 PC 의 IP 주소로 바꾸세요 (etc/client.env)" ;;
                     *)
                         clog "HINT: 웹 서버 실행 여부, 주소/포트, 웹 서버 PC 방화벽(포트 허용), 서버→웹 서버 네트워크 경로를 확인하세요" ;;
                 esac
             fi ;;
        *)   clog "ERROR: HTTP $HTTP_STATUS $h_method $h_path : `head -1 $h_out 2>/dev/null`" ;;
    esac
    return 1
}

# ------------------------------------------------------------
# 웹 서버 설정 (수집 주기, 수집 여부, 감시 프로세스, 동기화 대상) → $RUN_DIR/config.txt
# 실패하면 이전에 받은 설정을 그대로 사용
# ------------------------------------------------------------
load_config() {
    if http_call GET /agent-api/v1/config $RUN_DIR/config.new; then
        mv $RUN_DIR/config.new $RUN_DIR/config.txt
    else
        rm -f $RUN_DIR/config.new
        [ -f $RUN_DIR/config.txt ] && clog "WARN: 이전 설정 사용 ($RUN_DIR/config.txt)"
    fi
    [ -f $RUN_DIR/config.txt ] || : > $RUN_DIR/config.txt
    CFG_INTERVAL=`sed -n 's/^interval=\([0-9][0-9]*\)$/\1/p' $RUN_DIR/config.txt | head -1`
    CFG_INTERVAL=${CFG_INTERVAL:-60}
    CFG_COLLECT=`sed -n 's/^collect=\([a-z]*\)$/\1/p' $RUN_DIR/config.txt | head -1`
    CFG_COLLECT=${CFG_COLLECT:-on}
    CFG_PEER=`sed -n 's/^peer=\([0-9.][0-9.]*\)$/\1/p' $RUN_DIR/config.txt | head -1`
}

# 동시 실행 방지 (cron 중복). 잠금을 잡은 프로세스가 이미 끝났으면 잠금을 지우고 다시 잡음
acquire_lock() {
    LOCK=$RUN_DIR/scb_client.lock
    if mkdir $LOCK 2>/dev/null; then
        echo $$ > $LOCK/pid
        return 0
    fi
    lpid=`cat $LOCK/pid 2>/dev/null`
    if [ -n "$lpid" ] && kill -0 $lpid 2>/dev/null; then
        return 1
    fi
    rm -rf $LOCK
    mkdir $LOCK 2>/dev/null || return 1
    echo $$ > $LOCK/pid
    return 0
}

release_lock() {
    rm -rf $RUN_DIR/scb_client.lock
}
