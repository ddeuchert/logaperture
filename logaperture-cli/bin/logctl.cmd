@echo off
rem
rem Copyright 2026 David Deuchert
rem
rem Licensed under the Apache License, Version 2.0 (the "License");
rem you may not use this file except in compliance with the License.
rem You may obtain a copy of the License at
rem
rem     http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.
rem
rem Thin launcher for the LogAperture control CLI. Requires a JDK, not just
rem a JRE (it needs com.sun.tools.attach). Build the jar with
rem `mvn -pl logaperture-cli package` first.
rem
rem An optional logctl.conf.cmd beside this script is run first, so it can set,
rem override or clear any variable (JAVA_HOME, JAVA_OPTS, ...) whatever the
rem caller's environment holds. %~dp0 (trailing backslash) is available to it
rem for relative paths.

setlocal

if exist "%~dp0logctl.conf.cmd" call "%~dp0logctl.conf.cmd"

rem LOGCTL_JAR_HOME, when set, is the only place looked in. Otherwise the
rem distribution zip puts the jar in ..\lib and a source-tree build leaves it
rem in ..\target. Try both. (Single-line ifs: a path containing parentheses
rem would break a block.)
if not defined LOGCTL_JAR_HOME goto defaultjar
set "JAR=%LOGCTL_JAR_HOME%\logaperture-cli.jar"
if exist "%JAR%" goto havejar
echo logctl: LOGCTL_JAR_HOME is set to %LOGCTL_JAR_HOME% but %JAR% was not found. 1>&2
exit /b 1

:defaultjar
set "JAR=%~dp0..\lib\logaperture-cli.jar"
if not exist "%JAR%" set "JAR=%~dp0..\target\logaperture-cli.jar"

:havejar
if not exist "%JAR%" (
  echo logctl: logaperture-cli.jar not found in ..\lib or ..\target - in a source tree, run "mvn -pl logaperture-cli package" first. 1>&2
  exit /b 1
)

rem JAVA_HOME, when set, is the only Java considered: no fallback to PATH.
rem (Single-line ifs: a path containing parentheses would break a block.)
set "JAVA_CMD=java"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_HOME goto run
if exist "%JAVA_CMD%" goto run
echo logctl: JAVA_HOME is set to %JAVA_HOME% but %JAVA_CMD% was not found. 1>&2
exit /b 1

:run
"%JAVA_CMD%" %JAVA_OPTS% -jar "%JAR%" %*
