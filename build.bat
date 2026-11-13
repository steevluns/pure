@echo off
setlocal
cd /d "%~dp0"
set "CSC="
if exist "%windir%\Microsoft.NET\Framework\v4.0.30319\csc.exe" set "CSC=%windir%\Microsoft.NET\Framework\v4.0.30319\csc.exe"
if not defined CSC if exist "%windir%\Microsoft.NET\Framework\v3.5\csc.exe" set "CSC=%windir%\Microsoft.NET\Framework\v3.5\csc.exe"
if not defined CSC if exist "%windir%\Microsoft.NET\Framework\v2.0.50727\csc.exe" set "CSC=%windir%\Microsoft.NET\Framework\v2.0.50727\csc.exe"
if not defined CSC (
  echo .NET Framework was not found on this PC.
  pause
  exit /b 1
)
"%CSC%" /nologo /target:winexe /optimize+ /out:Colors10.exe /r:System.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll Colors10.cs
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
echo Done. Run Colors10.exe
pause
