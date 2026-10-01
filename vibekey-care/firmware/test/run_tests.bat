@echo off
REM 펌웨어 단위 테스트 (윈도우 / MSVC) — 기기 없이 PC에서 바로 돌립니다.
REM   사용법: Visual Studio 개발자 명령 프롬프트에서  run_tests.bat
REM   또는 vcvars64.bat 을 먼저 실행한 아무 cmd 창에서.
setlocal
cd /d "%~dp0"
where cl >nul 2>&1
if errorlevel 1 (
  echo cl.exe 를 찾지 못했습니다. "x64 Native Tools Command Prompt" 에서 실행하거나,
  echo vcvars64.bat 을 먼저 실행하세요.
  exit /b 127
)
cl /nologo /std:c++14 /EHsc /O2 /utf-8 /Fe:"%TEMP%\vkp_test.exe" /Fo:"%TEMP%\\" test_vkp.cpp || exit /b 1
"%TEMP%\vkp_test.exe"
