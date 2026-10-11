-- =============================================================
--  scb_mon_agent 사용자 초기 데이터 (ANSI SQL) - db\init-mysql.bat 에서만 실행
--  admin 계정은 비밀번호 없이(로그인 불가) 만들어집니다. 저장소에는 비밀번호 해시를 두지 않습니다.
--  관리자 만들기 : 웹 [사용자 가입 신청]으로 계정을 만든 뒤 DB 에서 승인·권한 부여
--    UPDATE tb_user SET role_cd = 'ADMIN', appr_status = 'APPROVED' WHERE user_id = '가입한ID';
--  비밀번호는 BCrypt 해시({bcrypt}...)로만 저장합니다.
-- =============================================================
DELETE FROM tb_login_hist;
DELETE FROM tb_user;
INSERT INTO tb_user (user_id, user_nm, password_hash, role_cd, use_yn, lock_yn, appr_status, dept_nm, appr_user, appr_dt, fail_cnt, last_login_dt, last_login_ip, reg_dt, upd_dt) VALUES ('admin', '관리자', '{bcrypt}SET-BY-ADMIN', 'ADMIN', 'Y', 'N', 'APPROVED', '시스템관리', 'admin', CURRENT_TIMESTAMP, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
