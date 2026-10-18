@echo off
setlocal
set "ZERON_JAR=%~dp0..\lib\zeron.jar"
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -jar "%ZERON_JAR%" %*
) else (
    java -jar "%ZERON_JAR%" %*
)
exit /b %ERRORLEVEL%
