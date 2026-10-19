@echo off
setlocal
set "ZERON_JAR=%~dp0..\lib\zeron.jar"
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" "-Dzeron.home=%~dp0.." -jar "%ZERON_JAR%" %*
) else (
    java "-Dzeron.home=%~dp0.." -jar "%ZERON_JAR%" %*
)
exit /b %ERRORLEVEL%
