@echo off
setlocal
set "ZERON_JAR=%~dp0..\lib\zeron.jar"
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" --add-exports java.base/jdk.internal.vm=ALL-UNNAMED "-Dzeron.home=%~dp0.." -jar "%ZERON_JAR%" %*
) else (
    java --add-exports java.base/jdk.internal.vm=ALL-UNNAMED "-Dzeron.home=%~dp0.." -jar "%ZERON_JAR%" %*
)
exit /b %ERRORLEVEL%
