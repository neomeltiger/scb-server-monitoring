-- =============================================================
--  [테스트 전용] 자동 테스트(H2)용 데모 계정 admin / admin - 운영 DB 에 쓰지 마세요
--  admin / admin (데모) : 운영 전 비밀번호를 반드시 변경하세요.
--  비밀번호는 BCrypt 해시({bcrypt}...)로만 저장합니다.
-- =============================================================
DELETE FROM tb_login_hist;
DELETE FROM tb_user;
INSERT INTO tb_user (user_id, user_nm, password_hash, role_cd, use_yn, lock_yn, appr_status, dept_nm, appr_user, appr_dt, fail_cnt, last_login_dt, last_login_ip, reg_dt, upd_dt) VALUES ('admin', '관리자', '{bcrypt}$2a$10$XNW6HNMwcRMJHjevD/mWa.WouR9XsR7hJbfhsV7Qfb57wFa65E0XC', 'ADMIN', 'Y', 'N', 'APPROVED', '시스템관리', 'admin', CURRENT_TIMESTAMP, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
