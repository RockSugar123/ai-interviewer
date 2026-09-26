@echo off
rem ============================================================
rem RocketMQ 5.3.4 local dev launcher (Stage 5), idempotent:
rem skips a component when its port is already LISTENING.
rem Logs: %USERPROFILE%\logs\rocketmqlogs\
rem NOTE: keep this file ASCII-only, cmd parses it as ANSI/GBK
rem ============================================================
set "ROCKETMQ_HOME=F:\rocketmq\rocketmq-all-5.3.4-bin-release"
if not defined JAVA_HOME set "JAVA_HOME=F:\Java\jdk-17.0.18"

rem --- namesrv (256m) ---
set "JAVA_OPT_EXT=-Xms256m -Xmx256m -Xmn128m"
netstat -ano | findstr ":9876" | findstr "LISTENING" >nul
if errorlevel 1 start "rocketmq-namesrv" cmd /c "call %ROCKETMQ_HOME%\bin\mqnamesrv.cmd"
if not errorlevel 1 echo namesrv already running on 9876

rem wait via ping (System32 timeout.exe is shadowed by Git Bash coreutils when launched from Git Bash)
ping -n 6 127.0.0.1 >nul

rem --- broker (512m) ---
set "JAVA_OPT_EXT=-Xms512m -Xmx512m -Xmn256m"
netstat -ano | findstr ":10911" | findstr "LISTENING" >nul
if errorlevel 1 start "rocketmq-broker" cmd /c "call %ROCKETMQ_HOME%\bin\mqbroker.cmd -c %~dp0rocketmq\broker-dev.conf"
if not errorlevel 1 echo broker already running on 10911

echo RocketMQ ready: namesrv 127.0.0.1:9876 / broker 127.0.0.1:10911
echo Verify: %ROCKETMQ_HOME%\bin\mqadmin.cmd clusterList -n 127.0.0.1:9876
