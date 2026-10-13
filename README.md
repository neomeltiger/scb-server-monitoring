# scb-server-monitoring

통합 서버 관리 및 모니터링 시스템 - Murex(Solaris 10) 서버 모니터링 웹과 서버 에이전트 클라이언트

| 폴더 | 내용 |
|---|---|
| `server-monitoring-web/` | 웹 애플리케이션 - JDK 1.8, Spring Boot 2.7 (war), MyBatis(ANSI SQL), Thymeleaf, MySQL 8 |
| `svr_collect_client/` | 서버 수집 클라이언트 1.11 - Solaris 10 `/bin/sh` 데몬 (crontab 미사용) |

## 주요 기능

- 메인 홈 : 장애 알람(서버 다운 / 보고 끊김), 에이전트 미등록 서버, 연결된 서버 카드(이동 가능), 주요 프로세스 선택 그래프
- 네트워크 구성도, 서버 전체목록(주요 프로세스 관리), 웹 콘솔(SSH)
- 프로세스 관리 : service.list 서비스 상태·제어(start/stop/...), 추가·수정·삭제, service.list 문서 생성, 제어 이력
- 서버 로그 주기 요청, 에이전트 데몬 웹 제어(시작 / 중지 / 종료 / 수집 주기)
- 로그인·가입 승인·비밀번호 변경, ADMIN / USER 권한, 반응형 화면, 사용자 매뉴얼(`/manual/index.html`)

## 빌드 / 실행

```bash
cd server-monitoring-web
mvn clean package            # 자동 테스트(H2) 포함
java -jar target/server-monitoring-web.war
```

- DB : MySQL 8 스키마 `scb_mon_agent` - `server-monitoring-web/db/init-mysql.bat` (새로 만들기), `migrate-v2~v5.bat` (기존 DB 변경)
- DB 비밀번호는 저장소에 없습니다. 실행 전 환경변수 `MYSQL_PASSWORD` (필요하면 `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`)
- 관리자 계정 : 초기 데이터의 admin 은 비밀번호 없이(로그인 불가) 만들어집니다. 웹 [사용자 가입 신청] 후
  `UPDATE tb_user SET role_cd = 'ADMIN', appr_status = 'APPROVED' WHERE user_id = '가입한ID';`
- 에이전트 설치 : `svr_collect_client/README.txt` (tar 생성 : `python server-monitoring-web/tools/make_client_tar.py svr_collect_client svr_collect_client.tar`)
- 화면 스크립트 점검 : `node server-monitoring-web/tools/js_smoke.js server-monitoring-web/src/main/resources/static/js`

자세한 내용 : `server-monitoring-web/README.md`

## 저장소 공개용 정리

이 저장소는 내부 환경의 원본을 다음과 같이 바꾼 사본입니다.

- 서버 IP `10.0.x.x`, 도메인 `*.prod.example.com` 은 예시 값입니다. 실제 서버 정보로 바꿔 사용하세요. (`db/data.sql`, `config/allowed-hosts.json`)
- 비밀번호·토큰·DB 백업은 포함하지 않습니다. (`src/test/resources/db/users.sql` 의 admin/admin 은 자동 테스트 전용 데모 계정)
