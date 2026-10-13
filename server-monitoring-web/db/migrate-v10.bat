@echo off
rem Korean (UTF-8) output in the cmd window : code page 65001
chcp 65001 >nul
rem ============================================================
rem  scb_mon_agent v10 migration (existing data is kept)
rem    src\main\resources\db\migrate-v10-mysql.sql
rem      - tb_menu : left menu names / order / user menu access (permission management)
rem  Run once on a DB created before v9 (after migrate-v9.bat). A new install (init-mysql.bat) already has these.
rem
rem  Usage:  migrate-v10.bat                    (asks for the root password)
rem  Env  :  MYSQL_HOST (127.0.0.1)  MYSQL_PORT (13306)  MYSQL_USER (root)
rem          MYSQL_BIN  (folder of mysql.exe)
rem ============================================================
setlocal
if not defined MYSQL_HOST set "MYSQL_HOST=127.0.0.1"
if not defined MYSQL_PORT set "MYSQL_PORT=13306"
if not defined MYSQL_USER set "MYSQL_USER=root"
if not defined MYSQL_BIN  set "MYSQL_BIN=C:\Program Files\MySQL\MySQL Server 8.0\bin"
set "SQL_DIR=%~dp0..\src\main\resources\db"

if not exist "%MYSQL_BIN%\mysql.exe" (
    echo [ERROR] mysql.exe not found in "%MYSQL_BIN%". Set MYSQL_BIN.
    exit /b 1
)

echo Target : %MYSQL_USER%@%MYSQL_HOST%:%MYSQL_PORT%  schema scb_mon_agent
echo Applying migrate-v10-mysql.sql ...
"%MYSQL_BIN%\mysql.exe" -h %MYSQL_HOST% -P %MYSQL_PORT% -u %MYSQL_USER% -p --default-character-set=utf8mb4 < "%SQL_DIR%\migrate-v10-mysql.sql"
if errorlevel 1 (
    echo [ERROR] Migration failed. If it says "already exists" the DB is already v10.
    exit /b 1
)

echo Done.
exit /b 0
