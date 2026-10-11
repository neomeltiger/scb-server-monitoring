@echo off
rem ============================================================
rem  scb_mon_agent backup : DDL / DML / FULL  (mysqldump, consistent snapshot)
rem  Output : C:\scb\doc\데이터베이스\scb_mon_agent_{DDL|DML|FULL}_YYYYMMDD_HHMM.sql
rem  Usage  : backup-mysql.bat   (asks for the root password once; nothing is saved)
rem  Env    : MYSQL_HOST (127.0.0.1)  MYSQL_PORT (13306)  MYSQL_USER (root)  MYSQL_BIN  BACKUP_DIR
rem ============================================================
setlocal
chcp 65001 >nul
if not defined MYSQL_HOST set "MYSQL_HOST=127.0.0.1"
if not defined MYSQL_PORT set "MYSQL_PORT=13306"
if not defined MYSQL_USER set "MYSQL_USER=root"
if not defined MYSQL_BIN  set "MYSQL_BIN=C:\Program Files\MySQL\MySQL Server 8.0\bin"
if not defined BACKUP_DIR set "BACKUP_DIR=C:\scb\doc\데이터베이스"
if not exist "%MYSQL_BIN%\mysqldump.exe" (
    echo [ERROR] mysqldump.exe not found in "%MYSQL_BIN%". Set MYSQL_BIN.
    exit /b 1
)
if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmm"') do set "TS=%%i"
set /p "MYSQL_PWD=root password: "
set "OPT=-h %MYSQL_HOST% -P %MYSQL_PORT% -u %MYSQL_USER% --default-character-set=utf8mb4 --single-transaction --set-gtid-purged=OFF --no-tablespaces --hex-blob"

echo Backup to %BACKUP_DIR% (%TS%) ...
"%MYSQL_BIN%\mysqldump.exe" %OPT% --no-data --routines --triggers --events --skip-dump-date scb_mon_agent > "%BACKUP_DIR%\scb_mon_agent_DDL_%TS%.sql" || goto :fail
"%MYSQL_BIN%\mysqldump.exe" %OPT% --no-create-info --skip-triggers --complete-insert --skip-dump-date scb_mon_agent > "%BACKUP_DIR%\scb_mon_agent_DML_%TS%.sql" || goto :fail
"%MYSQL_BIN%\mysqldump.exe" %OPT% --routines --triggers --events --complete-insert --databases scb_mon_agent > "%BACKUP_DIR%\scb_mon_agent_FULL_%TS%.sql" || goto :fail
set "MYSQL_PWD="
dir /b "%BACKUP_DIR%\scb_mon_agent_*_%TS%.sql"
echo Done.
exit /b 0

:fail
set "MYSQL_PWD="
echo [ERROR] mysqldump failed. Check the password / MySQL service.
exit /b 1
