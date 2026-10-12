# 통합 서버 관리 및 모니터링 시스템

JDK 1.8 + Spring Boot 2.7 + Maven + Thymeleaf 기반 웹 애플리케이션입니다.
웹 콘솔(SSH 원격 접속)까지 하나의 Spring Boot 애플리케이션에서 동작합니다.

| 구분 | 사용 기술 |
|---|---|
| 언어 / 빌드 | JDK 1.8, Maven 3.6 이상 |
| 웹 | Spring Boot 2.7.18 (Spring MVC) - war 패키징: 외부 Tomcat 9 배포 / `java -jar` 실행 모두 가능 |
| 화면 | Thymeleaf (공통 상단·좌측: `templates/fragments/top.html`, `left.html`) |
| 로그인 | Spring Security (폼 로그인, CSRF) |
| 웹 콘솔 | Spring WebSocket + JSch(`com.github.mwiede:jsch`) + Bouncy Castle(JDK 1.8 에서 ssh-ed25519 키 지원) + xterm.js |
| 네트워크 구성도 | vis-network |
| DB (주) | MySQL 8.x `scb_mon_agent` - MyBatis 2.3 + mysql-connector-j 8.0.33 (ANSI SQL Mapper) |
| DB (보조) | Sybase ASE - jTDS 1.3.1 + HikariCP (`sybase` 프로필) |

xterm.js, vis-network 는 `src/main/resources/static/vendor/` 에 포함되어 있어 인터넷(CDN) 없이 내부망에서 동작합니다. (라이선스 파일 동봉)

## 빌드 / 실행

SCB 개발환경(`C:\scb`)에서는 루트의 스크립트를 사용합니다. (`C:\scb\README.md`)

| 방법 | 명령 | 주소 |
|---|---|---|
| Tomcat 배포 | `C:\scb\deploy-tomcat.bat` → `C:\scb\tomcat-start.bat` | http://localhost:8080/server-monitoring-web/ |
| 바로 실행 | `C:\scb\build.bat` → `C:\scb\run-boot.bat` | http://localhost:8081/ |

Maven 으로 직접 실행할 때:

```bash
mvn -s C:/scb/tools/maven/conf/settings.xml clean package
java -Dfile.encoding=UTF-8 -jar target/server-monitoring-web.war
```

- 데모 계정 `admin` / `admin`
- 프로필: `demo` (웹 콘솔 시뮬레이션), `sybase` (Sybase 연결) - 예: `--spring.profiles.active=sybase,demo`
- `config/allowed-hosts.json` 은 실행 위치 기준 경로입니다. 다른 위치에서 실행하면 `--app.console.allowed-hosts-file=<경로>` 로 지정하세요. (Tomcat 은 `setenv.bat` 이 지정)
- 화면·JS 는 상대 경로를 쓰므로 컨텍스트 경로(`/server-monitoring-web`)가 있어도 그대로 동작합니다.

## 데이터베이스 (MySQL scb_mon_agent)

네트워크 구성도 화면 구성을 기준으로 만든 테이블입니다. 메인 홈 / 서버 전체목록 / 프로세스 관리 화면도 같은 데이터를 사용합니다.

| 테이블 | 화면 요소 |
|---|---|
| `tb_net_node` | Central Hub, ITC / DRC Switch 노드 (라벨, 설치장소) |
| `tb_server` | 서버 노드 (시스템명, FQDN, OS, IP, 설치장소=스위치, 상태, 동기화 상태, 동기화 대상) |
| `tb_server_metric` | 상세 패널 / 상태 팝업의 CPU, MEM, DISK, 활성 세션수 |
| `tb_server_process` | 서버별 주요 프로세스 (PID, CPU, MEM, 실행 여부) |
| `tb_user` / `tb_login_hist` | 로그인 사용자 (BCrypt, 잠금, 가입 승인 상태) / 로그인·비밀번호 변경 이력 |
| `tb_alarm` | 서버 장애 알람 (서버 다운 / 에이전트 보고 끊김, 확인·조치 내용) |
| `tb_log_policy` / `tb_log_source` / `tb_log_snapshot` | 서버 로그 : 요청 주기 / 수집 대상 / 수집 결과 (최근 20회) |
| `tb_agent` | 서버 에이전트(krcasmrx11_client) 토큰 해시, 마지막 보고, 데몬 제어(수집 시작/중지·주기·종료)와 상태(heartbeat) |
| `tb_server_metric_hist` / `tb_process_metric_hist` | 메인 홈 서버별 그래프 (수집 이력, 출처 AGENT / SAMPLE) |
| `tb_service` / `tb_service_cmd` | 프로세스 관리 화면 : service.list 서비스 목록 / 제어 명령 |

- 생성: `db\init-mysql.bat` (root 비밀번호 입력, **기존 테이블을 지우고 다시 만듦**)
- v2 변경(가입 승인 컬럼, `tb_alarm`)만 적용: `db\migrate-v2.bat` (`db/migrate-v2-mysql.sql`, 기존 데이터 유지)
  - DDL `src/main/resources/db/schema-mysql.sql`, 초기 데이터 `src/main/resources/db/data.sql` (서버 10대 / 프로세스 27건)
- 접속: `application.yml` 의 `spring.datasource` (기본 `127.0.0.1:13306`, root). 환경변수 `MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD` 로 변경
- 구조 (Spring MVC): `TopologyPageController`(화면) / `TopologyApiController`(JSON) → `TopologyService`(업무 규칙·트랜잭션) → `TopologyMapper` + `mapper/topology/TopologyMapper.xml`(ANSI SQL) → MySQL
- Mapper SQL 은 ANSI SQL 만 사용합니다 (`JOIN ... ON`, `LEFT OUTER JOIN`, `COALESCE`, `CURRENT_TIMESTAMP`, 키는 JDBC getGeneratedKeys). 테스트(`TopologyServiceTest`)가 같은 Mapper·초기 데이터를 H2 메모리 DB 에서 실행해 확인합니다.
- 화면의 "서버 목록 기본값으로 초기화" 는 `data.sql` 을 다시 실행합니다.

### API 문서 (Swagger UI)

- 주소 : `/swagger-ui.html` (ADMIN 로그인 필요, 좌측 메뉴 시스템관리 › API 문서), 문서 JSON : `/v3/api-docs/1-web-api`, `/v3/api-docs/2-agent-api`
- springdoc-openapi-ui 1.8.0 (`config/OpenApiConfig`), 컨트롤러별 `@Tag` / `@Operation(summary)` 한글 설명
- [Try it out] 은 로그인한 세션으로 호출. CSRF 는 쿠키 방식(`XSRF-TOKEN` → `X-XSRF-TOKEN` 헤더)이라 Swagger UI 가 자동 전달 (화면은 meta 태그 그대로)
- 끄기 : 환경변수 `SWAGGER_ENABLED=false`

### 데이터 API (로그인 필요, 변경 요청은 CSRF 토큰 필요)

| 메서드 / 주소 | 내용 |
|---|---|
| `GET /api/topology/nodes` | Hub / Switch 노드 |
| `GET /api/topology/servers` | 서버 목록 (자원 현황, 프로세스 포함) |
| `POST / PUT / DELETE /api/topology/servers[/{id}]` | 서버 추가 / 수정 / 삭제 |
| `POST /api/topology/reset` | 기본 서버 목록으로 초기화 |
| `POST /api/topology/servers/{id}/processes` | 프로세스 등록 |
| `PUT / DELETE /api/topology/servers/{id}/processes/{procId}` | 프로세스 수정 / 삭제 |
| `POST /api/topology/servers/{id}/processes/{procId}/toggle` | 프로세스 기동 / 중지 (화면용 시뮬레이션) |

## 로그인 (tb_user)

- 사용자는 DB `tb_user` 에서 관리하며 비밀번호는 BCrypt 해시(`{bcrypt}...`)로만 저장합니다. 초기 계정 `admin` / `admin` (`db/users.sql`) - **운영 전 반드시 변경**.
- 연속 5회 실패하면 잠금(`lock_yn = 'Y'`), 모든 시도는 `tb_login_hist` 에 남습니다. 잠금 해제: `UPDATE tb_user SET lock_yn = 'N', fail_cnt = 0 WHERE user_id = '...'`
- 권한 : `ADMIN` = 조회 + 변경/삭제/제어 + 웹 콘솔 + 사용자 관리, `USER` = 조회만
  - 서버 : `/api/**` 의 POST·PUT·DELETE, `/admin/**`, `/api/admin/**`, `/console`, `/ssh` 는 ADMIN 만 (SecurityConfig)
  - 화면 : USER 는 `body.role-user` 로 `.admin-only` 버튼 숨김. 관리자 화면 주소로 들어오면 `/?denied` 로 이동
- 가입 신청 `/signup` (로그인 없이) → `tb_user.appr_status = PENDING`, 항상 USER → 관리자 승인 전 로그인 불가
  - 로그인 실패 사유 : `bad | locked | disabled | pending | rejected`. 계정 상태는 비밀번호가 맞을 때만 알려줌 (DaoAuthenticationProvider 사후 검사)
- 사용자 관리 `/admin/users` (ADMIN) : 승인(권한 선택) / 반려(사유 필수) / 잠금 해제 / 사용 중지 / 권한 변경 / 삭제. 자기 자신·마지막 관리자 보호
- 비밀번호 변경 `/password` (로그인 화면·상단 바) : 현재 비밀번호 확인, 틀리면 실패 횟수 증가, 변경 후 로그아웃. 이력 `PW_CHANGE`
- 비밀번호 규칙 (가입·변경) : 8자 이상, 영문·숫자·특수문자 포함, 아이디 포함 불가

## 서버 에이전트 연동 (krcasmrx11_client)

서버(`/shared/opt/agent/krcasmrx11_client`)의 셸 스크립트가 수집 결과를 보내고 제어 명령을 가져갑니다. (설치: `C:\scb\workspace\krcasmrx11_client.tar`, `README.txt`)

```
서버 krcasmrx11_client ──(HTTPS/HTTP, 토큰)──▶ /agent-api/v1  ──▶ MySQL scb_mon_agent ◀── 화면
   collect_*.sh 수집 → report                       tb_server_metric(_hist), tb_server_process, tb_service, tb_agent
   commands → exec_command.sh → scb_main.sh   ◀──   tb_service_cmd (화면 요청 → 에이전트 실행 → 결과)
```

- 토큰 : 네트워크 구성도 → 서버 클릭 → 서버 에이전트 → [에이전트 토큰 발급] (ADMIN). 원문은 그때 한 번만 표시, DB 에는 SHA-256 만 저장.
- `SCB_WEB_URL` : `app.agent.public-url`(환경변수 `SCB_PUBLIC_URL`) 이 있으면 그 값, 없으면 화면을 연 주소. 화면을 `localhost` 로 열었으면 이 PC 의 네트워크 IPv4 로 바꾸고 경고 표시 (`AgentWebUrl`) - 서버에서 localhost 는 서버 자신이기 때문
- 에이전트 API (`X-SCB-Agent-Token` 헤더, 응답 text/plain) : `GET /agent-api/v1/ping`, `GET /config`, `POST /report`, `GET /commands`, `POST /commands/{id}/result?exit=N`
- 설정 `app.agent.*` : 보고 주기, 이력 보관 일수(7), 명령 만료(요청 10분 / 결과 30분), 보고 끊김 기준(300초)

## 서버 정보 수신 안 됨 → 서버 Down / 프로세스 종료 표시

- 서버 : `ServerView.of(server, processes, agentState)` - 에이전트 상태 OK 아니면 `status = "Down"`, `baseStatus` = 등록 상태(tb_server.status_cd, 수정 화면 기본값), `noData` = 사유. `ServerServicesView.status`, `AgentStatusView.statusCd` 도 같은 기준 (DB 의 status_cd 와 SERVER_DOWN 알람은 바꾸지 않음)

- `agent/service/ProcessStatusService`
  - 서버 단위 : 에이전트 상태가 OK 가 아니면(NONE/WAITING/STALE/PAUSED/STOPPED) 실행 중 프로세스(tb_server_process)·서비스(tb_service) → 종료 (AlarmService 60초 점검)
  - 프로세스 단위 : 에이전트 보고에 빠진 프로세스·서비스 → 종료 (AgentService.ingestReport, 서비스 상태를 보내지 않는 이전 클라이언트는 서비스 제외)
- 실행 중 → 종료로 바뀔 때만 기록 : 로거 `process-status` → `logs/process-status.log` (`logback-spring.xml`, 일·10MB 단위, 30일) + 프로세스 수집 이력 running_yn=N
- 화면 표시 문구 "중지" → "종료" (메인 홈, 구성도, 서버 전체목록, 프로세스 관리)

## 서버 장애 알람 (tb_alarm)

- `AlarmService` 가 `app.alarm.check-interval-ms`(60초)마다 점검 + 서버 생성/수정·에이전트 보고·초기화 커밋 직후 점검
  - `SERVER_DOWN` (CRITICAL) : 서버 상태 Down / `AGENT_STALE` (WARNING) : 보고하던 에이전트가 `stale-after-sec` 이상 끊김 (Down 이면 제외)
  - 조건이 풀리면 자동 CLOSED. 같은 서버·종류의 진행 중 알람은 1건 (점검은 lock 으로 직렬화)
- 메인 홈 : 빨간 배너(긴급) / 주황(경고), 미확인 시 탭 제목 깜빡임, 알림음(선택), 서버 카드 경고 색, 바로가기(구성도 `network?server=ID`, 프로세스 관리, 웹 콘솔, 확인/조치)
- API : `GET /api/alarms` (active + 최근 해소), `POST /api/alarms/{id}/ack {"memo"}` (ADMIN), `POST /api/alarms/check` (ADMIN)

## 에이전트 미등록 서버 (메인 홈)

- `GET /api/agents` : 전체 서버의 krcasmrx11_client 등록 현황 (`registered`, `reason`, 상태 NONE / WAITING / STALE / OK)
- 화면은 정상(OK)이 아닌 서버만 표시, [등록 확인] = `GET /api/agents/{id}` 재조회

## 서버 복구 자동 감지 (스키마 v8, 클라이언트 1.6)

- 감시자 하트비트에 `boot=Y|N`(부팅 자동 시작 rc 스크립트 설치 여부), `btime=부팅시각`(who -b) 추가 → `tb_agent.boot_auto_yn / boot_time` (`db\migrate-v8.bat`)
- `HeartbeatService.beat` : LOST 였던 서버의 하트비트가 다시 오거나 부팅 시각이 바뀌면 복구(RECOVER, 재부팅 표시)
  - 데몬 DEAD / STOPPED (일부러 멈춘 경우 제외) → 같은 응답에 `restart=Y` (자동 재시작, RESTART 이벤트 "데몬 자동 재시작")
  - 데몬 실행 중 → 다음 웹 확인 응답 `ctl|...|report=Y` (한 번, 메모리) → 데몬이 바로 수집·보고
  - 1.3 이하 데몬도 웹 확인이 다시 오면 report=Y (무시해도 무해)
- 클라이언트 : `pid_is` 로 PID 파일의 프로세스가 실제 scb 스크립트인지 확인 (재부팅 뒤 PID 재사용 대비), `install-boot` (root, rc 스크립트 설치), `status` 에 부팅 자동 시작·부팅 시각

## 하트비트 v7 : 서버 다운 / 데몬 문제 구분 (스키마 v7, 클라이언트 1.5)

- 클라이언트 1.5 : `bin/scb_heartbeat.sh` 가 데몬과 **따로** 실행되는 감시자 (`start` 가 함께 띄움, `stop` 은 데몬만, `stopall` 은 둘 다). `heartbeat?pid=&daemon=RUNNING|PAUSED|STOPPED|DEAD` 로 데몬 상태를 알리고, 응답 `restart=Y` 면 `scb_client.sh start` 실행
- 서버 다운 = 하트비트 **3회 연속 미수신** (`주기 x 3 + 2초`, `Agent.HB_MISS_LIMIT`) → LOST, 프로세스·서비스 즉시 종료, 서버 Down, AGENT_STALE 알람
- 데몬 문제 = 하트비트는 오는데 데몬 STOPPED / DEAD (또는 보고만 끊김) → `AgentView.serverDown=false`, `daemonIssue`, 서버 Down 아님, 프로세스는 종료 표시, **DAEMON_DOWN** 알람 (일부러 멈춘 경우 제외)
- 웹 재시작 : `POST /api/agents/{id}/restart` (ADMIN) → `restart_req_yn` Y → 다음 하트비트에 전달(S) → 데몬이 웹 확인을 보내면 바로 D_UP·해제 (재시작 직후 첫 보고가 종료로 덮이지 않게)
- DB v7 (`db\migrate-v7.bat`) : `tb_agent` + `hb_daemon_state`, `restart_req_yn/user/dt`, 이벤트 `D_DOWN / D_UP / RESTART`, 알람 `DAEMON_DOWN`

## 하트비트 (원격 서버 구동 유무, 스키마 v6, 클라이언트 1.4)

- 클라이언트 : 데몬이 `bin/scb_heartbeat.sh <데몬PID>` 를 함께 실행 → `GET /agent-api/v1/heartbeat?pid=N` 를 주기마다, 응답 `hb|interval=N|timeout=T` 로 주기 갱신. 데몬이 죽으면 하트비트도 1초 안에 끝남. 종료 때는 하트비트를 먼저 멈춘 뒤 `POST /daemon?state=STOPPED`
- 웹 : `agent/service/HeartbeatService` - 수신(START / RECOVER), 감시 `app.agent.heartbeat-check-ms`(2초)마다 `주기 x 2 + 3초` 초과 → LOST → `ProcessStatusService.stopAllNoData`(프로세스·서비스 즉시 종료) + 알람(AGENT_STALE, "하트비트 끊김"), 데몬 종료 보고 → STOP 같은 처리
- 에이전트 상태 `LOST`(하트비트 끊김) 추가 : 화면 상태 Down + 사유. 1.3 이하 데몬은 웹 확인(/commands)을 생존 신호로 쓰고 기준 45초
- DB : `tb_agent` + `hb_interval_sec`(3/5/10/15/30/60) `hb_state` `last_hb_dt` `hb_pid`, `tb_heartbeat_hist` (START / LOST / RECOVER / STOP / INTERVAL) - 기존 DB 는 `db\migrate-v6.bat`
- API : `GET /api/agents/heartbeat` (화면 즉시 갱신용 - script.js `onHeartbeatChange` 가 3초마다 확인해 상태가 바뀌면 화면 갱신), `GET /api/agents/heartbeat/events`, `PUT /api/agents/{id}/heartbeat-interval {"sec":5}` (ADMIN)
- 화면 : `/heartbeat` 하트비트 모니터링, 네트워크 구성도 서버 상세의 하트비트 행·주기 변경 / 로그 `logs/heartbeat.log`

## 에이전트 데몬 (crontab 미사용) / 웹 제어

- 클라이언트 1.2 : `bin/scb_client.sh start | stop | restart | status` (데몬), 부팅 자동 시작 `etc/scb_client.init` → `/etc/init.d/scb_client`
- 데몬은 10초마다 `GET /agent-api/v1/commands?state=RUNNING|PAUSED&pid=N&ver=V` (heartbeat). 응답 첫 줄 `ctl|collect=on|interval=60|shutdown=N` 을 매번 적용
  - collect=off : 보고·로그 중지 (데몬·서비스 제어 명령은 유지), interval : 수집 주기(초), shutdown=Y : 데몬 종료 (1회 전달 후 해제, 그 응답에는 명령을 넣지 않음)
  - 데몬 종료 시 `POST /agent-api/v1/daemon?state=STOPPED`
- 화면 `/agents` (서버자원모니터링 › 에이전트 관리) : 데몬 상태(실행 중 / 수집 중지 / 종료됨 / 응답 없음(90초) / 구버전), PID·시작, 마지막 확인·보고, 수집 주기(10/30/60/120/300/600초), [시작] [중지] [종료] - 변경은 ADMIN
  - API : `POST /api/agents/{id}/daemon {"action":"start|stop|shutdown"}`, `PUT /api/agents/{id}/collect-interval {"sec":60}`
- tb_agent 추가 열 : collect_yn, collect_interval_sec, shutdown_req_yn, daemon_state, daemon_pid, daemon_start_dt, last_poll_dt, ctl_user, ctl_dt (`db\migrate-v4.bat`)
- 알람 : 웹에서 중지/종료한 서버는 보고 끊김 알람 없음, 보고 끊김 기준 = max(`stale-after-sec`, 수집 주기 x3)

## 프로세스 관리 : 서비스 추가·수정·삭제 / service.list 문서 / 제어 이력 팝업

- `/processes` : [+ 프로세스 추가], 줄별 [수정]·[삭제] (ADMIN) - `ServiceForm` 을 service.list 1줄로 만들어 `ServiceListParser` 로 같은 규칙 검사, 삭제 시 list_no 1부터 다시 매김
- [service.list 문서 만들기] : `GET /api/services/{id}/service-list?scriptHome=` (text/plain) → 복사 / 내려받기. 웹 변경은 이 문서를 서버에 반영해야 scb_main.sh 에 적용
- [제어 이력] `GET /api/services/{id}/commands?rows=100`, 줄별 [이력] `GET /api/services/{id}/items/{svcId}/commands` (서비스명 + group:N + level:N + all)
- `/servers` : 서버별 주요 프로세스 열 + [관리] 팝업 (기존 `/api/topology/servers/{id}/processes` 등록·수정·삭제)

## 메인 홈 : 그래프에 볼 프로세스 선택

- 카드의 [📈 그래프 항목 선택] 팝업(index.html `#graphPickModal`, script.js `openGraphPicker`)으로 그래프 항목 선택 (최대 6개 = 색 수, 검색 / CPU 상위 5개 / 모두 해제 / 적용·취소), 브라우저 localStorage `procGraphSel` 에 서버별 저장
- 주요 프로세스가 없으면 프로세스 관리(service.list) 서비스가 선택 대상 → 서비스 CPU 이력 `tb_service_metric_hist` (클라이언트 1.3 `collect_service.sh` 가 `서비스|상태|PID|CPU|MEM` 보고, `db\migrate-v5.bat`)
- 이력 API : `GET /api/topology/history` 응답의 서버별 `services` (서비스명 → cpu/mem)
- 화면 스크립트 로드 점검 : `node tools/js_smoke.js src/main/resources/static/js` (최상위 실행 순서 오류 - 예: 선언 전 const 참조 - 검사)

## 서버 로그 (웹 서버 주기 요청)

- 화면 `/logs` (서버자원모니터링 › 서버 로그) : 서버별 요청 주기(0=사용 안 함 / 1 / 5 / 10 / 30 / 60분, 기본 5), [지금 요청], 수집 대상 로그 관리 (변경은 ADMIN)
- 흐름 : 에이전트가 `GET /agent-api/v1/commands` 할 때 요청 시각이 되었으면 `log|로그ID|줄수|경로` 를 함께 내려줌
  → 클라이언트 `bin/collect_log.sh` 가 마지막 N줄 → `POST /agent-api/v1/logs/{logId}?status=OK|NOT_FOUND|DENIED|ERROR`
- 테이블 : `tb_log_policy`(주기, 마지막/다음 요청, 즉시 요청), `tb_log_source`(로그 파일), `tb_log_snapshot`(로그별 최근 20회)
- 보안 : 경로는 절대 경로·영문/숫자/`_./-` 만, `..` 금지 (웹·클라이언트 양쪽 검사). 클라이언트는 `client.env` 의 `SCB_LOG_ALLOW` 패턴에 맞는 경로만 읽음
- API : `GET /api/logs/{serverId}`, `PUT .../policy`, `POST .../request`, `POST|PUT|DELETE .../sources[/{logId}]`, `GET .../sources/{logId}/snapshots`, `GET .../snapshots/{snapId}`
- 기존 DB : `db\migrate-v3.bat` (`db/migrate-v3-mysql.sql`)

## 사용자 매뉴얼

- `src/main/resources/static/manual/index.html` (`/manual/index.html`, 로그인 없이 열림) - 같은 파일을 `C:\scb\사용자매뉴얼.html` 로 복사해 둠

## 프로세스 관리 화면 (service.list 기준)

- 서버의 `krcasmrx11_agent/etc/service.list` 를 `tb_service` 에 등록 (초기 데이터 : krcasmrx11 27건, 화면의 [service.list 가져오기] 로 갱신)
- 상태/PID 는 에이전트가 `scb_main.sh status all` 로 수집해 보고
- 개별 서비스 / 전체 / 그룹 N / Level N 에 start, stop, restart, status, kill, clean 요청 → 에이전트가 `scb_main.sh <명령> <대상>` 실행 → 결과(출력, 종료코드)를 "제어 명령 이력" 에서 확인
- 대상·명령은 화면, 웹 서버, 에이전트 세 곳에서 형식을 검사합니다. (CMDS 에 없는 명령은 요청 불가)

## 화면 주소

| 주소 | 화면 | 템플릿 |
|---|---|---|
| `/login` | 로그인 | `login.html` |
| `/signup` | 사용자 가입 신청 | `signup.html` |
| `/password` | 비밀번호 변경 | `password.html` |
| `/logs` | 서버 로그 | `server_logs.html` |
| `/heartbeat` | 하트비트 모니터링 | `heartbeat.html` |
| `/agents` | 에이전트 관리 (데몬 제어) | `agent_manage.html` |
| `/admin/users` | 사용자 관리 (ADMIN) | `admin_users.html` |
| `/manual/index.html` | 사용자 매뉴얼 | `static/manual/index.html` |
| `/` | 메인 홈 | `index.html` |
| `/network` | 네트워크 구성도 | `network_topology.html` |
| `/servers` | 서버 전체 목록 | `server_spec.html` |
| `/processes` | 프로세스 관리 | `process_monitoring.html` |
| `/console?id=서버ID&port=22` | 웹 콘솔 (별도 창) | `web_console.html` |
| `/console?host=주소&port=22` | 웹 콘솔 (주소 직접 입력) | `web_console.html` |
| `/api/db/status` | Sybase 연결 상태 (JSON) | - |
| `/api/topology/history?points=30` | 메인 홈 서버별 그래프 이력 (JSON) | - |
| `/api/services/{serverId}` | 프로세스 관리 : 서비스·에이전트·제어 이력 (JSON) | - |
| `/api/agents/{serverId}` | 에이전트 상태 / `POST .../token` 토큰 발급 | - |

## 폴더 구조

```
pom.xml
config/allowed-hosts.json                  웹 콘솔 접속 허용 서버 목록
src/main/java/com/scb/monitoring/
  MonitoringApplication.java               시작 클래스
  config/SecurityConfig.java               로그인 / 접근 제어
  config/AppSecurityProperties.java        로그인 계정 설정 (app.security.users)
  config/SybaseDataSourceConfig.java       Sybase 연결 (sybase 프로필, 보조 DB)
  topology/controller/                     네트워크 구성도 화면(TopologyPageController) / API(TopologyApiController)
  topology/service/TopologyService.java    서버·프로세스 업무 규칙, 트랜잭션
  topology/mapper/TopologyMapper.java      MyBatis Mapper (SQL: resources/mapper/topology/TopologyMapper.xml)
  topology/domain/, topology/dto/          테이블 매핑 객체 / 요청·응답 객체
  web/DbStatusController.java              /api/db/status
  web/PageController.java                  화면 라우팅
  console/WebSocketConfig.java             웹 콘솔 WebSocket(/ssh) 등록
  console/SshWebSocketHandler.java         WebSocket ↔ SSH 중계
  console/SshBridge.java                   SSH 세션 1개 처리 (JSch)
  console/AllowedHosts.java                접속 허용 서버 검사 (IP / CIDR / 호스트명)
  console/ConsoleProperties.java           웹 콘솔 설정 (app.console.*)
  console/ConsoleApiController.java        /api/console-config
src/main/resources/
  application.yml
  templates/                               Thymeleaf 화면
  templates/fragments/top.html, left.html  공통 상단 / 좌측 메뉴
  static/css, js, images                   정적 리소스
  static/vendor/xterm, vis-network         오픈소스 라이브러리
```

## 설정 (application.yml)

```yaml
app:
  security:
    users:
      - username: admin
        password: "{noop}admin"     # 운영 시 {bcrypt}해시 로 변경
        roles: [ADMIN]
  console:
    mode: live                      # live | demo
    allowed-hosts-file: config/allowed-hosts.json
    allowed-origins: []             # 비우면 같은 주소에서 연 화면만 허용
    ssh:
      strict-host-key-checking: "no"  # 운영 권장: "yes" + known-hosts-file
      known-hosts-file: ""
      legacy-algorithms: true         # 구형 SSH 서버 호환 (아래 참고)
      term-type: xterm                # TERM (Solaris 10 은 xterm-256color 미지원)
```

모든 설정은 실행 시 덮어쓸 수 있습니다. 예: `java -jar ... --server.port=9090 --app.console.mode=demo`

## 구형 SSH 서버 (Solaris 10 등)

Solaris 10 의 `Sun_SSH_1.1.5` 같은 구형 서버는 SHA-1 키 교환(`diffie-hellman-group14-sha1` 등), `ssh-rsa`/`ssh-dss` 호스트 키, CBC 암호, `hmac-sha1` 만 지원합니다.
JSch 는 이 알고리즘들을 기본으로 끄므로 `Algorithm negotiation fail` 오류가 납니다.

`app.console.ssh.legacy-algorithms: true` (기본값)이면 이 알고리즘들을 **목록 맨 끝에** 추가합니다.
SSH 는 클라이언트가 먼저 적은 알고리즘부터 고르므로 최신 서버는 계속 최신 알고리즘(curve25519, ed25519 등)으로 접속하고, 구형 서버만 SHA-1 계열을 씁니다.
웹 콘솔은 PuTTY 처럼 pty 요청에 **터미널 모드**(Enter→줄바꿈 변환 ICRNL, ECHO, Backspace=DEL, Ctrl+C 등)를 함께 보냅니다.
보내지 않으면 Sun_SSH 는 Enter(CR)를 줄 끝으로 처리하지 않아 `read` 로 입력받는 메뉴(예: scb_menu.sh)가 멈춥니다. (`console/SshBridge.java` 의 `TERMINAL_MODES`)

구형 서버가 없다면 `false` 로 끄세요. 접속한 서버의 SSH 버전은 `console-audit` 로그의 `AUTH OK (SSH-2.0-...)` 에 남습니다.

## 웹 콘솔 접속 허용 서버

`config/allowed-hosts.json` 에 있는 주소로만 SSH 접속합니다. 파일을 수정하면 재시작 없이 다음 접속부터 반영됩니다.

```json
[ "10.0.13.217", "10.0.0.0/16", "krcasmrx11.prod.example.com" ]
```

## 보안 참고

- 모든 화면과 웹 콘솔(WebSocket)은 로그인이 필요합니다. 접속 기록은 `console-audit` 로그에 웹 사용자·접속 대상·결과가 남습니다.
- `admin` / `admin` 은 데모 계정입니다. 운영 전 `app.security.users` 를 변경하거나 사내 인증(LDAP/SSO)으로 교체하세요.
- 서버 목록·프로세스 데이터는 아직 브라우저(localStorage)에 저장됩니다. 여러 사용자가 공유하려면 DB 연동이 필요합니다.
- 운영 환경에서는 HTTPS(`server.ssl.*`)와 SSH 호스트 키 검사(`strict-host-key-checking: "yes"`)를 사용하세요.
