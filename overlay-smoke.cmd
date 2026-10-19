@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0overlay-smoke.ps1" %*
