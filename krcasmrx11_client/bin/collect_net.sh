#!/bin/sh
#=============================================================
# collect_net.sh - [net] 동기화 대상 서버(ITC <-> DRC) 통신 상태
#   대상 IP 는 웹 서버 설정(run/config.txt 의 peer=)에서 받음
#   출력 : peer=IP|UP  또는  peer=IP|DOWN
#   네트워크 구성도 상세 패널의 "동기화 대상 통신" 에 사용
# Solaris 10 /bin/sh 호환 (/usr/sbin/ping 호스트 타임아웃초)
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
. $CLIENT_HOME/lib/scb_client_common.sh

echo "[net]"
[ -f $RUN_DIR/config.txt ] || exit 0
peer=`sed -n 's/^peer=\([0-9.][0-9.]*\)$/\1/p' $RUN_DIR/config.txt | head -1`
[ -n "$peer" ] || exit 0

if [ "$OS_NAME" = "SunOS" ]; then
    ${PING_BIN:-/usr/sbin/ping} $peer 2 > /dev/null 2>&1
else
    ping -c 1 -W 2 $peer > /dev/null 2>&1
fi
if [ $? -eq 0 ]; then
    echo "peer=$peer|UP"
else
    echo "peer=$peer|DOWN"
fi
exit 0
