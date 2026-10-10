@echo off
cd /d "%~dp0"
if "%DB_PASSWORD%"=="" (
  echo Please set DB_PASSWORD first, e.g. set DB_PASSWORD=your-password
  exit /b 1
)
echo Starting MyAI Backend with local SQL Server...
start "MyAI Backend" cmd /k "set JWT_SECRET=dev-jwt-secret-should-be-at-least-32-bytes&& set ANALYSIS_CUSTOM_DB_ENABLED=true&& .\mvnw.cmd spring-boot:run"
