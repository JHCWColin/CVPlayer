@echo off
set APP_HOME=%~dp0
if defined JAVA_HOME (set JAVACMD=%JAVA_HOME%in\java.exe) else (set JAVACMD=java.exe)
"%JAVACMD%" %JAVA_OPTS% -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
