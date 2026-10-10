@echo off
cd /d "%~dp0"
echo Starting MyAI Backend with local SQL Server...
start "MyAI Backend" cmd /k "set JWT_SECRET=dev-jwt-secret-should-be-at-least-32-bytes&& set DB_PASSWORD=change-me&& set ANALYSIS_CUSTOM_DB_ENABLED=true&& .\mvnw.cmd spring-boot:run"
