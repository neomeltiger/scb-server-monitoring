#!/bin/sh
#=============================================================
# collect_report.sh - 서버 점검 리포트 (웹 관리자 화면에서 요청할 때만 실행, 1.12~)
#   collect_report.sh [분야]      분야 : all(기본) 또는 sec,res,svc,chg 중 콤마 목록
#     sec  보안 및 접근 제어   : SSH 로그인 실패·IP, sudo / su 이력, 방화벽 차단 기록
#     res  시스템 자원         : 디스크 사용률, 커널 메시지(장애·OOM), 자원 사용량 추이 (load, vmstat, iostat, sar)
#     svc  서비스 및 스케줄러  : ★ 통합 모니터링 웹이 관리하는 프로세스 (service.list 서비스, 화면 등록 프로세스),
#                                cron 작업 실행 결과, 웹(Apache/Nginx 4xx·5xx) / 메일(큐·지연) 상태
#     chg  패키지 및 환경 변화 : 최근 설치 패키지·패치, 최근 7일 /etc 변경 파일, 계정 파일 변경
#
# 출력 형식 (웹 서버가 화면으로 그림)
#   #SCBRPT|1|호스트|OS|생성시각|분야     머리
#   #SEC|분야코드|제목                    분야 시작
#   #ITEM|항목코드|제목|확인한 로그/명령  항목 시작
#   #SUM|요약       #WARN|경고       #HL|강조 (웹 관리 프로세스)       그 밖의 줄 = 원문
#   #END|소요초
#
# Solaris 10 /bin/sh 호환 ($() / local 미사용). Linux 경로(/var/log/secure, journalctl, dpkg/dnf 로그)는 보조로 확인.
# 로그는 마지막 SCB_RPT_SCAN 줄(기본 20000)만 검사, 항목별 출력 줄 수 제한, 전체 SCB_RPT_MAXKB(기본 512KB) 까지.
# root 가 아니면 읽을 수 없는 로그는 "읽기 권한 없음" 으로 표시합니다.
#=============================================================
CLIENT_HOME=`cd \`dirname $0\`/.. && pwd`
SCB_QUIET=1
. $CLIENT_HOME/lib/scb_client_common.sh
BIN_DIR=$CLIENT_HOME/bin

SECTIONS=${1:-all}
case "$SECTIONS" in
    all|ALL|"") SECTIONS="sec,res,svc,chg" ;;
    *[!a-z,]*)  echo "#SCBRPT|1|`hostname`|$OS_NAME|`date '+%Y-%m-%d %H:%M:%S'`|$SECTIONS"
                echo "#WARN|잘못된 분야 값입니다: $SECTIONS (sec,res,svc,chg)"
                exit 2 ;;
esac

SCAN=${SCB_RPT_SCAN:-20000}
MAXKB=${SCB_RPT_MAXKB:-512}
SSH_WARN=${SCB_RPT_SSH_WARN:-10}
DISK_WARN=${SCB_RPT_DISK_WARN:-80}
DISK_CRIT=${SCB_RPT_DISK_CRIT:-90}
T=$RUN_DIR/rpt.$$
# 점검용 : 로그·설정 파일 경로 앞에 붙일 루트 (기본 없음 = 실제 경로)
R=${SCB_RPT_ROOT:-}
OIFS=$IFS
PATH=$PATH:/usr/sbin:/usr/bin:/bin:/sbin:/usr/sfw/bin:/opt/sfw/bin
export PATH

TAIL=tail
[ -x /usr/xpg4/bin/tail ] && TAIL=/usr/xpg4/bin/tail

sec()  { echo "#SEC|$1|$2"; }
item() { echo "#ITEM|$1|$2|$3"; }
sum()  { echo "#SUM|$*"; }
warn() { echo "#WARN|$*"; }
hl()   { echo "#HL|$*"; }
have() { type "$1" > /dev/null 2>&1; }

# 마지막 N줄 (Solaris 기본 tail 은 버퍼 제한 → xpg4 tail 우선)
lastn() {
    if [ "$TAIL" = "tail" ] && [ "$OS_NAME" = "SunOS" ]; then
        tail -$1 "$2" 2>/dev/null
    else
        $TAIL -n $1 "$2" 2>/dev/null
    fi
}

# 있는 로그 파일만 골라 $T.files 에 (읽기 권한 없는 파일은 $T.noread)
pick_logs() {
    : > $T.files
    : > $T.noread
    for f in "$@"; do
        f=$R$f
        [ -f "$f" ] || continue
        if [ -r "$f" ]; then echo "$f" >> $T.files; else echo "$f" >> $T.noread; fi
    done
}

# pick_logs 결과 안내 (확인한 파일 목록 문자열 → LOGS_USED)
logs_note() {
    LOGS_USED=`tr '\n' ' ' < $T.files`
    if [ -s $T.noread ]; then
        echo "  (읽기 권한 없음: `tr '\n' ' ' < $T.noread`- root 로 실행 필요)"
    fi
    [ -s $T.files ] || [ -s $T.noread ] || echo "  (해당 로그 파일 없음)"
}

# 고른 로그들의 마지막 SCAN 줄에서 egrep 패턴(대소문자 무시) 일치 줄 → $1
scan_logs() {
    : > $1
    exec 6<&0 < $T.files
    while read f; do
        lastn $SCAN "$f" | egrep -i "$2" >> $1 2>/dev/null
    done
    exec 0<&6 6<&-
}

cnt() {
    n=`wc -l < $1 2>/dev/null`
    expr ${n:-0} + 0
}

# 출력 줄 수 제한 + 들여쓰기
show() {
    head -${2:-20} $1 | cut -c1-300 | sed 's/^/  /'
}

#------------------------------------------------------------
# 1. 보안 및 접근 제어 (Security & Access)
#------------------------------------------------------------
report_sec() {
    sec sec "보안 및 접근 제어 (Security & Access)"

    # SSH 로그인 실패
    pick_logs /var/adm/authlog /var/log/authlog /var/adm/messages /var/log/secure /var/log/auth.log
    item ssh "SSHD 로그인 실패 (Failed logins)" "/var/adm/authlog /var/adm/messages /var/log/secure /var/log/auth.log"
    logs_note
    scan_logs $T.ssh 'sshd.*(failed|invalid user|illegal user|authentication failure|did not receive identification|maximum authentication attempts)'
    n=`cnt $T.ssh`
    sum "로그인 실패 ${n}건 (검사: ${LOGS_USED:-없음}, 파일별 마지막 ${SCAN}줄)"
    if [ $n -gt 0 ]; then
        echo "  [실패 IP 상위 10]  건수  IP"
        sed -n 's/.* from \([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' $T.ssh | sort | uniq -c | sort -rn | head -10 | \
            $NAWK '{printf "    %6d  %s\n", $1, $2}'
        echo "  [최근 실패 기록]"
        $TAIL -20 $T.ssh 2>/dev/null | cut -c1-300 | sed 's/^/  /'
        [ $n -ge $SSH_WARN ] && warn "SSH 로그인 실패 ${n}건 (기준 ${SSH_WARN}건 이상) - 무단 접근 시도 확인 필요"
    fi

    # sudo / su 사용 이력
    item sudo "Sudo / su 사용 이력" "journalctl _COMM=sudo, /var/adm/sulog, auth 로그의 sudo:"
    : > $T.sudo
    if have journalctl; then
        journalctl _COMM=sudo --no-pager -n 200 2>/dev/null | grep -v '^-- ' >> $T.sudo
    fi
    if [ ! -s $T.sudo ]; then
        pick_logs /var/adm/authlog /var/log/authlog /var/adm/messages /var/log/secure /var/log/auth.log
        scan_logs $T.sudo 'sudo(\[[0-9]+\])?:'
    fi
    n=`cnt $T.sudo`
    egrep -i 'incorrect password|not in sudoers|authentication failure|not allowed' $T.sudo > $T.sudof 2>/dev/null
    nf=`cnt $T.sudof`
    sum "sudo 기록 ${n}건, 실패·거부 ${nf}건"
    if [ $n -gt 0 ]; then
        echo "  [최근 sudo 기록]"
        $TAIL -15 $T.sudo 2>/dev/null | cut -c1-300 | sed 's/^/  /'
    fi
    [ $nf -gt 0 ] && warn "sudo 실패·거부 ${nf}건 - 권한 남용 시도 확인 필요"
    if [ -f $R/var/adm/sulog ]; then
        if [ -r $R/var/adm/sulog ]; then
            # Solaris sulog : SU 월/일 시:분 +|- tty 사용자-대상  ('-' = 실패)
            lastn 2000 $R/var/adm/sulog > $T.su
            ns=`cnt $T.su`
            $NAWK '$4 == "-"' $T.su > $T.suf
            nsf=`cnt $T.suf`
            sum "su 기록 ${ns}건 (/var/adm/sulog 마지막 2000줄), 실패 ${nsf}건"
            echo "  [최근 su 기록]"
            $TAIL -10 $T.su 2>/dev/null | sed 's/^/  /'
            [ $nsf -gt 0 ] && warn "su 실패 ${nsf}건 (/var/adm/sulog)"
        else
            echo "  (읽기 권한 없음: /var/adm/sulog - root 로 실행 필요)"
        fi
    fi

    # 방화벽 차단 기록
    item fw "Firewall 차단 기록" "ipfilter(ipfstat, ipmon), iptables/ufw 로그 (/var/adm/messages, /var/log/messages)"
    if have ipfstat; then
        ipfstat 2>/dev/null | egrep -i 'blocked|passed' | head -6 | sed 's/^/  ipfstat: /'
        nr=`ipfstat -io 2>/dev/null | grep -c '^[bp]'`
        echo "  ipfilter 규칙 ${nr}개 (ipfstat -io)"
    fi
    pick_logs /var/adm/messages /var/log/messages /var/log/kern.log /var/log/syslog /var/log/ufw.log /var/log/ipflog
    logs_note
    scan_logs $T.fw 'ipmon\[|UFW BLOCK|IPTABLES|iptables.*(DROP|REJECT)|kernel:.*SRC=.*DST=| b [0-9.]+,[0-9]+ -> '
    n=`cnt $T.fw`
    sum "차단 기록 ${n}건 (검사: ${LOGS_USED:-없음})"
    if [ $n -gt 0 ]; then
        echo "  [차단 출발지 IP 상위 10]  건수  IP"
        sed -n -e 's/.*SRC=\([0-9.]*\).*/\1/p' -e 's/.* b \([0-9.]*\),[0-9]* -> .*/\1/p' $T.fw | sort | uniq -c | sort -rn | head -10 | \
            $NAWK '{printf "    %6d  %s\n", $1, $2}'
        echo "  [차단 대상 포트 상위 10]  건수  포트"
        sed -n -e 's/.*DPT=\([0-9]*\).*/\1/p' -e 's/.* -> [0-9.]*,\([0-9]*\) .*/\1/p' $T.fw | sort | uniq -c | sort -rn | head -10 | \
            $NAWK '{printf "    %6d  %s\n", $1, $2}'
        echo "  [최근 차단 기록]"
        $TAIL -10 $T.fw 2>/dev/null | cut -c1-300 | sed 's/^/  /'
    else
        echo "  (차단 기록 없음 - 방화벽 로그를 남기지 않는 설정일 수 있음)"
    fi
}

#------------------------------------------------------------
# 2. 시스템 자원 및 하드웨어 (System Resources)
#------------------------------------------------------------
report_res() {
    sec res "시스템 자원 및 하드웨어 (System Resources)"

    # 디스크 사용률
    item disk "Disk Space (파티션별 사용률)" "df -h / df -k"
    df -h 2>/dev/null | $NAWK 'NR == 1 || ($2 != "0" && $2 != "0K" && $6 !~ /\.so/) {print "  " $0}' | head -40
    df -k 2>/dev/null | $NAWK -v W=$DISK_WARN -v C=$DISK_CRIT '
        NR == 1 { next }
        NF == 1 { fs = $1; next }
        { if (fs != "") { $0 = fs " " $0; fs = "" } }
        $2 + 0 == 0 || $6 ~ /\.so/ { next }
        { p = $5; sub(/%/, "", p); p += 0
          if (p >= C)      printf "#WARN|디스크 위험 %s %d%% (기준 %d%%)\n", $6, p, C
          else if (p >= W) printf "#WARN|디스크 경고 %s %d%% (기준 %d%%)\n", $6, p, W
          if (p > max) { max = p; mm = $6 } }
        END { printf "#SUM|가장 높은 사용률 %s %d%% (경고 %d%% / 위험 %d%%)\n", mm, max, W, C }'

    # 커널 메시지
    item kernel "Kernel Message (하드웨어 결함·메모리 고갈·OOM)" "dmesg, /var/adm/messages, /var/log/boot.log, fmadm faulty"
    : > $T.kern
    if have dmesg; then
        dmesg 2>/dev/null | $TAIL -500 > $T.dmesg 2>/dev/null
    else
        : > $T.dmesg
    fi
    pick_logs /var/adm/messages /var/log/messages /var/log/kern.log /var/log/boot.log
    logs_note
    scan_logs $T.kern 'panic|fatal|out of memory|oom|killed process|hardware error|machine check|i/o error|retryable|offline|fault|disk.*error|scsi.*error|ecc|uncorrectable|segfault|warning: '
    egrep -i 'panic|fatal|out of memory|oom|killed process|hardware error|machine check|i/o error|fault|uncorrectable|segfault' $T.dmesg >> $T.kern 2>/dev/null
    n=`cnt $T.kern`
    egrep -i 'panic|fatal|out of memory|oom|killed process|hardware error|machine check|uncorrectable' $T.kern > $T.kcrit 2>/dev/null
    nc=`cnt $T.kcrit`
    sum "주의 메시지 ${n}건, 심각(패닉·OOM·하드웨어) ${nc}건"
    if [ $n -gt 0 ]; then
        echo "  [최근 주의 메시지]"
        $TAIL -20 $T.kern 2>/dev/null | cut -c1-300 | sed 's/^/  /'
    fi
    [ $nc -gt 0 ] && warn "커널 심각 메시지 ${nc}건 (패닉·OOM·하드웨어 오류) - 확인 필요"
    if have fmadm; then
        fmadm faulty 2>/dev/null > $T.fma
        if [ -s $T.fma ]; then
            echo "  [fmadm faulty]"
            show $T.fma 20
            warn "Solaris FMA 장애 부품 보고 있음 (fmadm faulty)"
        else
            echo "  fmadm faulty : 보고된 장애 없음 (또는 권한 없음)"
        fi
    fi

    # 자원 사용량 추이
    item trend "자원 사용량 추이 (Load / 메모리 / 디스크 I/O)" "uptime, vmstat, iostat, sar, swap"
    up=`uptime 2>/dev/null`
    echo "  $up"
    l1=`echo "$up" | sed -n 's/.*load average[s]*: *\([0-9.]*\).*/\1/p'`
    ncpu=""
    have psrinfo && ncpu=`psrinfo 2>/dev/null | wc -l`
    [ -z "$ncpu" ] && have nproc && ncpu=`nproc 2>/dev/null`
    ncpu=`expr ${ncpu:-0} + 0`
    if [ -n "$l1" ] && [ $ncpu -gt 0 ]; then
        over=`echo "$l1 $ncpu" | $NAWK '{print ($1 > $2) ? 1 : 0}'`
        sum "Load(1분) $l1 / CPU ${ncpu}개"
        [ "$over" = "1" ] && warn "Load Average(1분) $l1 가 CPU 수(${ncpu}) 보다 큼 - CPU 포화"
    fi
    if have prtconf && [ "$OS_NAME" = "SunOS" ]; then
        prtconf 2>/dev/null | grep '^Memory size' | sed 's/^/  /'
    elif have free; then
        free -m 2>/dev/null | sed 's/^/  /'
    fi
    if have swap; then
        swap -s 2>/dev/null | sed 's/^/  swap -s : /'
    fi
    echo "  [vmstat 1 3]"
    vmstat 1 3 2>/dev/null | sed 's/^/  /'
    echo "  [iostat - 디스크 I/O]"
    if [ "$OS_NAME" = "SunOS" ]; then
        iostat -xn 1 2 2>/dev/null > $T.io
    else
        iostat -x 1 2 2>/dev/null > $T.io
    fi
    if [ -s $T.io ]; then
        # 두 번째 측정(현재 1초 구간)만
        $NAWK 'NR == 1 { h = $0 } { a[NR] = $0 } END { s = 0; for (i = 1; i <= NR; i++) if (a[i] ~ /extended|Device|r\/s/) last = i;
               for (i = last; i <= NR; i++) print "  " a[i] }' $T.io | head -40
    else
        echo "  (iostat 없음)"
    fi
    echo "  [sar - 오늘 CPU / 메모리 추이]"
    if have sar && sar -u 2>/dev/null | grep '[0-9]' > $T.sar && [ -s $T.sar ]; then
        $TAIL -12 $T.sar 2>/dev/null | sed 's/^/  /'
        sar -r 2>/dev/null | $TAIL -6 | sed 's/^/  /'
    else
        echo "  (sar 기록 없음 - sys 계정 crontab 의 sa1 / sysstat 미사용)"
    fi
}

#------------------------------------------------------------
# 3. 애플리케이션 및 스케줄러 (Services & Automation)
#------------------------------------------------------------
report_svc() {
    sec svc "애플리케이션 및 스케줄러 (Services & Automation)"

    # ★ 통합 모니터링 웹이 관리하는 프로세스 (강조)
    item scb "★ 통합 모니터링 웹 관리 프로세스" "svr_opt_agent scb_main.sh status all, 웹 등록 프로세스(run/config.txt)"
    MAIN=$SCB_AGENT_HOME/bin/scb_main.sh
    if [ -f "$MAIN" ]; then
        sh $MAIN status all < /dev/null 2>/dev/null | $NAWK '$6 == "RUNNING" || $6 == "STOPPED"' > $T.svc
        nr=`grep -c ' RUNNING ' $T.svc`
        nst=`grep -c ' STOPPED ' $T.svc`
        sum "service.list 서비스 : 실행 ${nr} / 중지 ${nst} ($SCB_AGENT_HOME)"
        stopped=""
        exec 6<&0 < $T.svc
        while read no grp lvl name acc st pid; do
            if [ "$st" = "RUNNING" ]; then
                hl "[서비스] $name  RUNNING  pid=$pid  (그룹 $grp / Level $lvl / 계정 $acc)"
            else
                hl "[서비스] $name  STOPPED  (그룹 $grp / Level $lvl / 계정 $acc)"
                stopped="$stopped $name"
            fi
        done
        exec 0<&6 6<&-
        # 경고는 한 줄로 (이름은 10개까지)
        [ $nst -gt 0 ] && warn "웹 관리 서비스 중지 ${nst}개 :`echo $stopped | $NAWK '{ for (i = 1; i <= NF && i <= 10; i++) printf " %s", $i; if (NF > 10) printf " 외 %d개", NF - 10 }'`"
    else
        echo "  (svr_opt_agent 없음: $MAIN - etc/client.env 의 SCB_AGENT_HOME 확인)"
    fi
    if [ -f $RUN_DIR/config.txt ] && grep '^proc|' $RUN_DIR/config.txt > $T.plist 2>/dev/null && [ -s $T.plist ]; then
        sh $BIN_DIR/collect_proc.sh 2>/dev/null | grep '|' > $T.pst
        np=`cnt $T.plist`
        sum "화면 등록 프로세스 ${np}개 (네트워크 구성도 주요 프로세스)"
        exec 6<&0 < $T.plist
        while IFS='|' read tag procid name path; do
            IFS=$OIFS
            st=`grep "^$procid|" $T.pst | head -1`
            run=`echo "$st" | $NAWK -F'|' '{print $2}'`
            if [ "$run" = "Y" ]; then
                ppid=`echo "$st" | $NAWK -F'|' '{print $3}'`
                pc=`echo "$st" | $NAWK -F'|' '{print $4}'`
                pm=`echo "$st" | $NAWK -F'|' '{print $5}'`
                hl "[프로세스] $name  실행 중  pid=$ppid  CPU ${pc}%  MEM ${pm}%  ($path)"
            else
                hl "[프로세스] $name  중지  ($path)"
                warn "웹 등록 프로세스 중지 : $name ($path)"
            fi
        done
        IFS=$OIFS
        exec 0<&6 6<&-
    else
        echo "  (화면 등록 프로세스 없음 - 웹 설정을 아직 받지 않았으면 bin/scb_client.sh config)"
    fi

    # Cron Job
    item cron "Cron Job (예약 작업 실행 결과)" "/var/cron/log, /var/log/cron, crontab -l"
    pick_logs /var/cron/log /var/log/cron /var/log/cron.log
    logs_note
    : > $T.cron
    exec 6<&0 < $T.files
    while read f; do lastn $SCAN "$f" >> $T.cron; done
    exec 0<&6 6<&-
    # Solaris : '>' 시작 줄 'CMD:' , '<' 끝 줄 'rc=N' (0 아니면 실패) / Linux : CMD (...) , error / ORPHAN
    nrun=`egrep -c 'CMD[:( ]' $T.cron`
    egrep -i 'rc=[1-9]|error|fail|orphan' $T.cron > $T.cronf 2>/dev/null
    nf=`cnt $T.cronf`
    sum "실행 기록 ${nrun}건, 실패·오류 ${nf}건 (검사: ${LOGS_USED:-없음})"
    if [ $nf -gt 0 ]; then
        echo "  [최근 실패·오류]"
        $TAIL -15 $T.cronf 2>/dev/null | cut -c1-300 | sed 's/^/  /'
        warn "cron 작업 실패·오류 ${nf}건"
    fi
    crontab -l 2>/dev/null | grep -v '^#' | grep -v '^ *$' > $T.tab
    echo "  [crontab -l (`id | sed 's/^uid=[0-9]*(\([^)]*\)).*/\1/'`) - `cnt $T.tab`개]"
    show $T.tab 20

    # Web / Mail Server
    item web "Web Server (Apache / Nginx 4xx·5xx)" "access_log, error_log"
    found=0
    for f in /usr/local/apache/logs/access_log /usr/local/apache2/logs/access_log /var/apache2/2.2/logs/access_log \
             /var/apache2/logs/access_log /var/apache/logs/access_log /var/log/httpd/access_log /var/log/apache2/access.log \
             /var/log/nginx/access.log; do
        f=$R$f
        [ -f $f ] || continue
        found=1
        if [ ! -r $f ]; then echo "  (읽기 권한 없음: $f)"; continue; fi
        lastn 5000 $f | $NAWK -v F=$f '
            { t++; c = $9; if (c ~ /^[45][0-9][0-9]$/) { n[c]++; if (c ~ /^5/) e5++; else e4++ } }
            END { printf "#SUM|%s : 최근 %d건 중 4xx %d건, 5xx %d건\n", F, t, e4, e5
                  for (k in n) printf "    %s  %d건\n", k, n[k]
                  if (e5 > 0) printf "#WARN|웹 서버 5xx 오류 %d건 (%s)\n", e5, F }'
        el=`echo $f | sed -e 's/access_log$/error_log/' -e 's/access\.log$/error.log/'`
        if [ -r "$el" ]; then
            lastn 2000 $el | egrep -i 'error|crit|alert|emerg' > $T.weberr
            echo "  [$el 최근 오류 `cnt $T.weberr`건 중 10줄]"
            $TAIL -10 $T.weberr 2>/dev/null | cut -c1-300 | sed 's/^/  /'
        fi
    done
    [ $found -eq 0 ] && echo "  (웹 서버 로그 없음 - Apache / Nginx 미사용 또는 다른 경로)"

    item mail "Mail Server (발송 큐 / 지연·반송)" "mailq, postqueue -p, /var/log/syslog, /var/log/maillog"
    if have postqueue; then
        postqueue -p 2>/dev/null | $TAIL -1 | sed 's/^/  postqueue: /'
    elif have mailq; then
        mailq 2>/dev/null > $T.mq
        q=`egrep -c '^[A-Za-z0-9]+[ *]' $T.mq`
        head -3 $T.mq | sed 's/^/  mailq: /'
        grep -i 'total requests' $T.mq | sed 's/^/  mailq: /'
    fi
    pick_logs /var/log/maillog /var/log/mail.log /var/log/syslog
    logs_note
    scan_logs $T.mail 'stat=deferred|status=deferred|status=bounced|dsn=5|reject|relay denied|stat=service unavailable'
    n=`cnt $T.mail`
    sum "메일 지연·반송·거부 ${n}건 (검사: ${LOGS_USED:-없음})"
    if [ $n -gt 0 ]; then
        $TAIL -10 $T.mail 2>/dev/null | cut -c1-300 | sed 's/^/  /'
        warn "메일 지연·반송·거부 ${n}건"
    fi
}

#------------------------------------------------------------
# 4. 패키지 및 환경 변화 (System Changes)
#------------------------------------------------------------
report_chg() {
    sec chg "패키지 및 환경 변화 (System Changes)"

    item pkg "패키지·패치 변경 (최근 설치·업데이트·삭제)" "/var/sadm/pkg, /var/sadm/patch, /var/log/dpkg.log, /var/log/dnf.log, /var/log/yum.log, rpm -qa --last"
    if [ -d $R/var/sadm/pkg ]; then
        echo "  [최근 설치 패키지 15 - 설치일 / 패키지 / 이름] (Solaris pkginfo)"
        ls -t $R/var/sadm/pkg 2>/dev/null | head -15 > $T.pkgs
        exec 6<&0 < $T.pkgs
        while read p; do
            pf=$R/var/sadm/pkg/$p/pkginfo
            [ -r $pf ] || continue
            idt=`sed -n 's/^INSTDATE=//p' $pf | head -1`
            pnm=`sed -n 's/^NAME=//p' $pf | head -1`
            echo "    $idt  $p  $pnm" | cut -c1-200
        done
        exec 0<&6 6<&-
        sum "설치된 패키지 `ls $R/var/sadm/pkg 2>/dev/null | wc -l | sed 's/ //g'`개"
    fi
    if [ -d $R/var/sadm/patch ]; then
        echo "  [최근 적용 패치 10] (/var/sadm/patch)"
        ls -lt $R/var/sadm/patch 2>/dev/null | grep '^d' | head -10 | $NAWK '{print "    " $6 " " $7 " " $8 "  " $NF}'
    fi
    shown=0
    for f in /var/log/dpkg.log /var/log/dnf.log /var/log/dnf.rpm.log /var/log/yum.log; do
        f=$R$f
        [ -r $f ] || continue
        shown=1
        lastn $SCAN $f | egrep -i ' install | upgrade | remove | purge |Installed|Updated|Erased|Upgraded|Removed' > $T.pk
        echo "  [$f - 최근 변경 `cnt $T.pk`건 중 20줄]"
        $TAIL -20 $T.pk 2>/dev/null | cut -c1-200 | sed 's/^/  /'
    done
    if have rpm && [ $shown -eq 0 ]; then
        echo "  [rpm -qa --last 15]"
        rpm -qa --last 2>/dev/null | head -15 | sed 's/^/  /'
    fi

    item etc "시스템 설정 변경 (최근 7일 /etc)" "find /etc -type f -mtime -7"
    find $R/etc -type f -mtime -7 2>/dev/null > $T.etc
    n=`cnt $T.etc`
    sum "최근 7일 변경된 /etc 파일 ${n}개"
    show $T.etc 30
    for f in /etc/passwd /etc/shadow /etc/group /etc/sudoers /etc/ssh/sshd_config /etc/inet/hosts /etc/hosts; do
        grep -x "$R$f" $T.etc > /dev/null 2>&1 && warn "중요 설정 파일 변경 (7일 이내) : $f"
    done
}

#------------------------------------------------------------
main() {
    START=`date '+%Y-%m-%d %H:%M:%S'`
    echo "#SCBRPT|1|`hostname`|`uname -s -r -v -m 2>/dev/null`|$START|$SECTIONS"
    echo "  svr_collect_client $CLIENT_VER  실행 계정 `id | sed 's/^uid=[0-9]*(\([^)]*\)).*/\1/'`  `uptime 2>/dev/null | sed 's/^ *//'`"
    s0=`$NAWK 'BEGIN { srand(); print srand() }'`
    for s in `echo $SECTIONS | tr ',' ' '`; do
        case "$s" in
            sec) report_sec ;;
            res) report_res ;;
            svc) report_svc ;;
            chg) report_chg ;;
            *)   warn "알 수 없는 분야 무시: $s" ;;
        esac
    done
    s1=`$NAWK 'BEGIN { srand(); print srand() }'`
    echo "#END|`expr $s1 - $s0`"
}

main 2>&1 | cut -c1-400 | dd bs=1024 count=$MAXKB 2>/dev/null
rm -f $T.*
exit 0
