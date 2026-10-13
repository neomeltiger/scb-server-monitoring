#!/bin/sh
#=============================================================
# collect_sys.sh - [sys] 서버 기본 정보 (호스트명, OS, 에이전트 버전)
#   네트워크 구성도 상세 패널의 "에이전트" 정보에 표시
# Solaris 10 /bin/sh 호환
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
. $CLIENT_HOME/lib/scb_client_common.sh

os=`uname -srvmp 2>/dev/null`
[ -n "$os" ] || os=`uname -a`

echo "[sys]"
echo "hostname=`hostname`"
echo "os=$os"
echo "agent_ver=$CLIENT_VER"
