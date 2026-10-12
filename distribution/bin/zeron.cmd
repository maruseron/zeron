@echo off
setlocal
set "ZERON_JAR=%~dp0..\lib\zeron.jar"
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" --enable-preview -jar "%ZERON_JAR%" %*
) else (
    java --enable-preview -jar "%ZERON_JAR%" %*
)
exit /b %ERRORLEVEL%
