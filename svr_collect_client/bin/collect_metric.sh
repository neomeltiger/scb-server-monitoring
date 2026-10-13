#!/bin/sh
#=============================================================
# collect_metric.sh - [metric] 서버 자원 (CPU / 메모리 / 디스크 / 세션)
#   네트워크 구성도 상세·상태 팝업, 메인 홈 서버별 그래프에 사용
#   cpu      = 100 - idle (vmstat 1초 표본)
#   mem      = (전체 - 여유) / 전체 (Solaris: prtconf Memory size, vmstat free)
#   disk     = 실제 파일시스템 중 가장 높은 사용률 (df -k)
#   sessions = TCP ESTABLISHED 연결 수
# Solaris 10 /bin/sh 호환 (Linux 는 테스트용 대체 경로)
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
. $CLIENT_HOME/lib/scb_client_common.sh

cpu=""; mem=""; disk=""; sessions=""

if [ "$OS_NAME" = "SunOS" ]; then
    # vmstat 마지막 줄 : ... free(5열) ... us sy id(마지막 열)
    vm=`vmstat 1 2 2>/dev/null | tail -1`
    idle=`echo "$vm" | $NAWK '{print $NF}'`
    free_kb=`echo "$vm" | $NAWK '{print $5}'`
    total_mb=`${PRTCONF:-/usr/sbin/prtconf} 2>/dev/null | $NAWK '/^Memory size/ {print $3}'`
    [ -n "$idle" ] && cpu=`echo "$idle" | $NAWK '{v = 100 - $1; if (v < 0) v = 0; printf "%.1f", v}'`
    if [ -n "$total_mb" ] && [ -n "$free_kb" ]; then
        mem=`echo "$total_mb $free_kb" | $NAWK '{t = $1 * 1024; if (t > 0) printf "%.1f", (t - $2) * 100 / t}'`
    fi
    # 가상 파일시스템(swap, proc, /devices ...) 제외. 백틱 안에서 \줄바꿈을 쓰지 않도록 awk 를 한 줄로 작성
    DF_AWK='NR > 1 && NF >= 6 && $1 !~ /^(swap|proc|mnttab|fd|objfs|sharefs|ctfs|\/devices)$/ && $6 !~ /^\/(proc|system|dev|devices|etc\/mnttab|etc\/dfs\/sharetab|lib\/libc)/ { c = $5; sub(/%/, "", c); if (c + 0 > m) m = c + 0 } END { printf "%.1f", m + 0 }'
    disk=`df -k 2>/dev/null | $NAWK "$DF_AWK"`
    sessions=`netstat -an -f inet -P tcp 2>/dev/null | grep ESTABLISHED | wc -l | $NAWK '{print $1}'`
else
    # Linux (개발/테스트용)
    s1=`head -1 /proc/stat`; sleep 1; s2=`head -1 /proc/stat`
    cpu=`echo "$s1 $s2" | $NAWK '{t1=0; for (i=2;i<=11;i++) t1+=$i; t2=0; for (i=13;i<=22;i++) t2+=$i;
          dt = t2 - t1; di = $16 - $5; if (dt > 0) printf "%.1f", (dt - di) * 100 / dt; else print "0.0"}'`
    mem=`$NAWK '/^MemTotal:/ {t=$2} /^MemAvailable:/ {a=$2} END {if (t > 0) printf "%.1f", (t - a) * 100 / t}' /proc/meminfo`
    disk=`df -kP 2>/dev/null | $NAWK 'NR > 1 && $1 ~ /^\// { c = $5; sub(/%/, "", c); if (c + 0 > m) m = c + 0 } END { printf "%.1f", m + 0 }'`
    sessions=`netstat -ant 2>/dev/null | grep ESTABLISHED | wc -l | $NAWK '{print $1}'`
fi

echo "[metric]"
[ -n "$cpu" ] && echo "cpu=$cpu"
[ -n "$mem" ] && echo "mem=$mem"
[ -n "$disk" ] && echo "disk=$disk"
[ -n "$sessions" ] && echo "sessions=$sessions"
exit 0
