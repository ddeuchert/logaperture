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

setlocal
set "JAR=%~dp0..\target\logaperture-cli.jar"

if not exist "%JAR%" (
  echo logctl: %JAR% not found - run "mvn -pl logaperture-cli package" first. 1>&2
  exit /b 1
)

java -jar "%JAR%" %*
