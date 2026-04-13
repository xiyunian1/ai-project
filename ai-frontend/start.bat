@echo off
cd /d "%~dp0"
echo Starting MyAI Frontend...
start "MyAI Frontend" cmd /k "npm run dev -- --host 127.0.0.1 --port 5173"
