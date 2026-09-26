@echo off
rem ============================================================
rem Monitoring launcher (Stage 6): Prometheus 9090 + Grafana 3000
rem Grafana default login admin/admin (change on first visit)
rem NOTE: keep this file ASCII-only
rem ============================================================
netstat -ano | findstr ":9090" | findstr "LISTENING" >nul
if errorlevel 1 (
  cd /d F:\monitoring\prometheus-3.1.0.windows-amd64
  start "prometheus" cmd /c "prometheus.exe --config.file=prometheus-local.yml --web.listen-address=:9090 --storage.tsdb.path=F:/monitoring/prom-data"
) else (
  echo prometheus already running on 9090
)

netstat -ano | findstr ":3000" | findstr "LISTENING" >nul
if errorlevel 1 (
  cd /d F:\monitoring\grafana-v11.4.0
  start "grafana" cmd /c "bin\grafana-server.exe --config conf\defaults.ini --homepath F:\monitoring\grafana-v11.4.0"
) else (
  echo grafana already running on 3000
)

echo Monitoring ready: Prometheus http://localhost:9090 / Grafana http://localhost:3000 (dashboard: AI Interviewer Stage 6)
