@echo off
REM 真正的构建逻辑在 build.sh（Git Bash），这样 .bat 里不用出现中文路径，绕开代码页问题
set "BASH=C:\Program Files\Git\bin\bash.exe"
if not exist "%BASH%" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not exist "%BASH%" (
    echo [ERROR] 找不到 Git Bash，请手动执行: bash build.sh
    pause
    exit /b 1
)
"%BASH%" "%~dp0build.sh"
pause
