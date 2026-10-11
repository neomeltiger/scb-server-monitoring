@echo off
rem ============================================================
rem  Create MySQL schema scb_mon_agent and load default data
rem    1) src\main\resources\db\schema-mysql.sql  (DROP + CREATE tables)
rem    2) src\main\resources\db\data.sql          (servers / processes / services / sample history)
rem    3) src\main\resources\db\users.sql         (login user admin / admin - change it)
rem  WARNING: existing tables in scb_mon_agent are dropped and recreated.
rem
rem  Usage:  init-mysql.bat                    (asks for the root password)
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
echo All tables in scb_mon_agent will be dropped and recreated.
set /p "YN=Continue? (y/N) "
if /i not "%YN%"=="y" (
    echo Cancelled.
    exit /b 1
)

rem schema-mysql.sql runs USE scb_mon_agent, so all files run in one session (one password prompt)
echo Running schema-mysql.sql + data.sql + users.sql ...
(type "%SQL_DIR%\schema-mysql.sql" & echo. & type "%SQL_DIR%\data.sql" & echo. & type "%SQL_DIR%\users.sql") | "%MYSQL_BIN%\mysql.exe" -h %MYSQL_HOST% -P %MYSQL_PORT% -u %MYSQL_USER% -p --default-character-set=utf8mb4
if errorlevel 1 (
    echo [ERROR] MySQL script failed.
    exit /b 1
)

echo Done.
exit /b 0
