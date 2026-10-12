krcasmrx11_client - 서버 → 통합 모니터링 웹 연동 에이전트 (Solaris 10 /bin/sh 호환)

웹 화면에 필요한 서버 정보를 종류별 스크립트로 수집해 웹 서버로 보내고,
웹 화면(프로세스 관리)에서 요청한 서비스 제어 명령을 받아 krcasmrx11_agent 의 scb_main.sh 로 실행합니다.

설치 (root)
  1) 디렉터리 : /shared/opt/agent/krcasmrx11_client   (root 로 생성, 권한 부여)
  2) 압축 해제 : cd /shared/opt/agent && tar xf krcasmrx11_client.tar
  3) 설정     : cd krcasmrx11_client
                cp etc/client.env.sample etc/client.env     (처음 한 번)
                vi etc/client.env       ← 웹 화면에서 발급한 SCB_WEB_URL, SCB_AGENT_TOKEN 입력
                chmod 600 etc/client.env
                (웹 화면 : 네트워크 구성도 → 서버 클릭 → 서버 에이전트 → [에이전트 토큰 발급])
                ※ SCB_WEB_URL 은 이 서버에서 접속 가능한 웹 서버 주소여야 합니다.
                   localhost / 127.0.0.1 은 이 서버 자신이므로 안 됩니다. (웹 서버 PC 의 IP 사용)
  4) 확인     : bin/scb_client.sh ping        → ok server_id=11
                연결 실패면 : ping 웹서버IP, 웹 서버 PC 방화벽의 포트 허용 확인
                bin/scb_client.sh collect     → 수집 결과 화면 출력 (전송 안 함)
  5) 데몬 시작 (crontab 사용 안 함)
                bin/scb_client.sh start       → 자료수집 데몬 + 하트비트 감시자 시작 (백그라운드)
                bin/scb_client.sh status      → 실행 여부, 수집 상태, 수집 주기, 마지막 확인·보고, 하트비트
                bin/scb_client.sh heartbeat   → 하트비트 1회 전송 (hb|interval=10|timeout=23)
                bin/scb_client.sh stop        → 데몬 정지 (웹에 종료 보고, 하트비트 감시자는 계속 → 웹은 서버 정상·데몬 정지로 표시)
                bin/scb_client.sh stopall     → 데몬 + 하트비트 감시자 모두 정지 (서버 점검·종료 시)
     ※ 이전 버전에서 crontab 에 scb_client.sh run 을 등록했다면 crontab -e 로 그 줄을 지우세요.
  6) 부팅 시 자동 시작 (root, 한 번) - 서버가 다운됐다 되살아나면 자동으로 데몬·감시자가 떠서 모니터링 재개
                bin/scb_client.sh install-boot
                (직접 하려면 : cp etc/scb_client.init /etc/init.d/scb_client ; chmod 744 /etc/init.d/scb_client
                 ln -s /etc/init.d/scb_client /etc/rc3.d/S99scb_client ; ln -s /etc/init.d/scb_client /etc/rc0.d/K01scb_client)
                설치 여부는 bin/scb_client.sh status 와 웹 하트비트 모니터링 화면에 표시됩니다.

  업그레이드 : bin/scb_client.sh stop → 같은 위치에 tar 를 다시 풀기 → bin/scb_client.sh start
               etc/client.env 는 tar 에 없으므로 그대로 유지됩니다.

구성
  bin/scb_client.sh       메인 (start | stop | restart | status | daemon | heartbeat | ping | config | collect | report | commands)
  bin/scb_heartbeat.sh    하트비트 감시자 - 데몬과 따로 실행, 데몬 상태를 함께 알리고 웹 재시작 요청 처리 (1.5~)
  etc/scb_client.init     부팅 시 자동 시작 rc 스크립트 (/etc/init.d/scb_client)
  bin/collect_sys.sh      [sys]      호스트명, OS                        → 구성도 상세 "서버 에이전트"
  bin/collect_metric.sh   [metric]   CPU / 메모리 / 디스크 / 세션         → 구성도 상세·상태 팝업, 메인 홈 그래프
  bin/collect_proc.sh     [proc]     화면에 등록된 프로세스 실행 상태     → 구성도 주요 프로세스, 메인 홈 프로세스 그래프
  bin/collect_service.sh  [service]  service.list 서비스 상태 + CPU/MEM  → 프로세스 관리 화면 상태/PID, 메인 홈 서비스 CPU 그래프 (1.3~)
                                     (scb_main.sh status all)
  bin/collect_net.sh      [net]      동기화 대상 서버 통신 (ping)         → 구성도 상세 "동기화 대상 통신"
  bin/exec_command.sh     제어 명령 1건 실행 : scb_main.sh <명령> <대상>
  bin/collect_log.sh      서버 로그 마지막 N줄 (웹 서버 요청, SCB_LOG_ALLOW 허용 경로만)
  bin/collect_ps.sh       [ps] 서버 전체 실행 프로세스 - 데몬 중단 중 감시자가 대신 수집할 때만 (하트비트 감시자 제외, 1.8~)
  lib/scb_client_common.sh  공통 (설정, HTTP: curl 또는 /usr/sfw/bin/wget, 로그, 잠금)
  etc/client.env.sample   설정 예시 (SCB_WEB_URL, SCB_AGENT_TOKEN, SCB_AGENT_HOME ...)
  run/                    웹 설정(config.txt), 마지막 보고(report.txt) 등 작업 파일
  log/scb_client.log      실행 로그

수집 기준 (Solaris 10)
  cpu      100 - idle  (vmstat 1 2 마지막 줄의 마지막 열)
  mem      (prtconf Memory size - vmstat free) / prtconf Memory size
  disk     df -k 의 실제 파일시스템 중 가장 높은 사용률 (swap, /proc, /devices 등 제외)
  sessions netstat -an -f inet -P tcp 의 ESTABLISHED 수
  proc     웹 설정의 proc|ID|이름|실행경로 → pgrep -f 실행경로, ps -o pcpu,pmem
  service  scb_main.sh status all 의 STATE / PID
  net      웹 설정의 peer=IP → /usr/sbin/ping IP 2

제어 명령
  웹 화면 요청 → (웹 DB 대기) → scb_client.sh 가 가져감 → exec_command.sh → scb_main.sh <명령> <대상>
  명령 : start | stop | restart | status | kill | clean
  대상 : all | group:N | level:N | 서비스명       (웹·클라이언트 양쪽에서 형식 검사, eval 사용 안 함)
  실행 계정 : scb_main.sh 와 같음 (root + scb.env 의 SCB_SU=1 이면 서비스 계정으로 su -)
  실행 결과(출력)와 종료코드는 웹 화면 "제어 명령 이력" 에서 확인

웹에서 데몬 제어 (웹 화면 : 서버자원모니터링 → 에이전트 관리)
  데몬은 10초마다 웹을 확인(heartbeat)하고, 응답의 ctl|collect=on|interval=60|shutdown=N 을 매번 적용합니다.
  [시작]     자료 수집 시작 (중지했던 수집 재개)
  [중지]     자료 수집 중지 - 데몬은 살아서 웹 확인만 계속 (서비스 제어 명령은 계속 처리)
  [종료]     데몬 프로세스 종료 - 이후에는 서버에서 bin/scb_client.sh start (또는 재부팅) 로 시작
  수집 주기  10초 / 30초 / 1분 / 2분 / 5분 / 10분 (서버별)
  상태 파일  run/daemon.pid, run/daemon.state

하트비트 감시자 (1.5~)
  bin/scb_client.sh start 가 데몬과 함께 bin/scb_heartbeat.sh 를 따로 띄웁니다. 감시자는 웹이 정한 주기마다
  GET /agent-api/v1/heartbeat?pid=데몬PID&daemon=데몬상태 를 보냅니다. (데몬이 죽어도 계속 보냄)
  주기       : 기본 10초, 웹 화면 네트워크 구성도 → 서버 → 서버 에이전트 → 하트비트 주기 (3 / 5 / 10 / 15 / 30 / 60초)
               응답 hb|interval=N 으로 다음 전송부터 바로 적용 (서버 작업 불필요)
  서버 다운  : 웹이 하트비트를 3회 연속(주기 x 3 + 2초) 못 받으면 → 서버 Down, 모니터링 대상 프로세스·서비스 즉시 종료, 알람
  데몬 문제  : 하트비트는 오는데 데몬이 멈춤(daemon=STOPPED / DEAD) → 서버는 정상(Down 아님), 프로세스·서비스는 종료 표시,
               "데몬 중단" 알람 + 웹 [데몬 재시작] → 다음 하트비트 응답 restart=Y → 감시자가 bin/scb_client.sh start
               (데몬 [종료] / scb_client.sh stop 처럼 일부러 멈춘 경우는 알람 없이 화면 안내만)
  서버 복구  : (1.6~) 서버가 되살아나 하트비트가 다시 오면 웹이 자동 감지 (부팅 시각이 바뀌면 재부팅으로 기록)
               - 데몬이 안 떠 있으면 자동 재시작 (restart=Y → 감시자가 scb_client.sh start, 일부러 멈춘 경우 제외)
               - 데몬이 떠 있으면 바로 수집·보고 (ctl|...|report=Y) → 서버 자원·프로세스 정보가 바로 복구
               - 재부팅 후 자동으로 뜨려면 부팅 스크립트 필요 (bin/scb_client.sh install-boot)
  데몬 중단 중 프로세스 현황 (1.7~)
               데몬이 죽거나 멈춰도 서버의 프로세스는 살아 있을 수 있으므로, 감시자가 30초마다(SCB_FALLBACK_SEC)
               collect_metric / collect_proc / collect_service / collect_ps 로 대신 수집해 POST /agent-api/v1/fallback
               (collect_ps : 서버에서 실행 중인 전체 프로세스, CPU 높은 순 최대 300건, 하트비트 감시자와 그 수집 작업은 제외)
               → 웹 화면 서버자원모니터링 → 데몬 중단 프로세스 현황 (기존 화면은 데몬 기준 그대로 "종료" 표시)
               데몬이 다시 시작되면 웹이 그 정보를 삭제합니다.
  확인       : 웹 화면 서버자원모니터링 → 하트비트 모니터링 (서버별 상태, 연속 미수신 횟수, 이벤트 이력)
  상태 파일  run/heartbeat.pid, run/heartbeat.state
  ※ 1.4 는 하트비트가 데몬과 함께 멈추므로 데몬이 죽으면 서버 다운으로 보입니다. 1.5 로 교체하세요.
  ※ 1.3 이하(하트비트 셸 없음)는 데몬의 웹 확인(10초)을 하트비트로 쓰며 끊김 기준은 45초입니다.

서버 로그 (웹 서버 주기 요청)
  웹 화면 "서버 로그" 에서 서버별 요청 주기(사용 안 함 / 1 / 5 / 10 / 30 / 60분)와 로그 파일을 정하면,
  웹 서버가 commands 응답에 log|ID|줄수|경로 를 넣어 보내고, 이 클라이언트가 마지막 N줄을 전송합니다.
  (데몬이 10초마다 웹을 확인하므로 10초 안에 처리)
  허용 경로 : etc/client.env 의 SCB_LOG_ALLOW (공백 구분 셸 패턴). 목록 밖 경로는 DENIED 로 응답하고 읽지 않음
  제한     : 최대 1000줄, 한 줄 2000자, 전체 256KB

보안
  - 토큰은 서버별로 발급되며 웹 DB 에는 해시만 저장됩니다. client.env 는 root 만 읽도록(600) 두세요.
  - 토큰을 재발급하면 이전 토큰은 즉시 무효가 됩니다.
  - 에이전트는 웹 서버로 나가는 연결(HTTP)만 사용하며, 서버에 포트를 열지 않습니다.
