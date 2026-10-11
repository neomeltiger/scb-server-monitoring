#!/bin/sh
#=============================================================
# exec_command.sh - 화면에서 요청한 서비스 제어 명령 1건 실행
#   exec_command.sh <명령> <대상>
#     명령 : start | stop | restart | status | kill | clean
#     대상 : all | group:N | level:N | 서비스명
#   krcasmrx11_agent 의 scb_main.sh 로 실행 (service.list 에 등록된 서비스만 실행됨)
#   웹 서버에서 검사한 값이지만 여기서도 다시 검사합니다. (eval / 셸 해석 없음)
# Solaris 10 /bin/sh 호환
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
. $CLIENT_HOME/lib/scb_client_common.sh

cmd=$1
target=$2

case "$cmd" in
    start|stop|restart|status|kill|clean) ;;
    *) echo "ERROR: 허용되지 않은 명령: '$cmd'"; exit 2 ;;
esac

case "$target" in
    all) ;;
    group:*|level:*)
        n=`echo "$target" | sed 's/^[a-z]*://'`
        case "$n" in
            ''|*[!0-9]*) echo "ERROR: 대상 형식 오류: '$target'"; exit 2 ;;
        esac
        ;;
    ''|*[!A-Za-z0-9_.-]*) echo "ERROR: 대상 형식 오류: '$target'"; exit 2 ;;
    *) ;;
esac

MAIN=$SCB_AGENT_HOME/bin/scb_main.sh
if [ ! -f $MAIN ]; then
    echo "ERROR: scb_main.sh 없음: $MAIN (client.env 의 SCB_AGENT_HOME 확인)"
    exit 3
fi

echo "# `date '+%Y-%m-%d %H:%M:%S'` `hostname` : scb_main.sh $cmd $target"
sh $MAIN $cmd "$target" < /dev/null 2>&1
