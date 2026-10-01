<!--
 - Copyright 2026 David Deuchert
 -
 - Licensed under the Apache License, Version 2.0 (the "License");
 - you may not use this file except in compliance with the License.
 - You may obtain a copy of the License at
 -
 -     http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
-->
# logaperture-bench

JMH benchmarks measuring how much time LogAperture adds to each log call. The design, the
scenarios and the budgets are in [`doc/specs/overhead-benchmarks.md`](../doc/specs/overhead-benchmarks.md).
This module is built only under the `bench` Maven profile and is never published.

## Running the benchmarks on Windows

This is for running the published measurements on a fast Windows PC. It takes about
**an hour**, most of it unattended, and doesn't change anything permanent on the machine.

### 1. Install Git and Java (once)

Open **PowerShell** (Start menu, type `powershell`) and run:

```powershell
winget install Git.Git
winget install EclipseAdoptium.Temurin.21.JDK
```

Then **close PowerShell and open a new one**, so it picks up both. Nothing else is needed: the
script downloads Maven and everything the build uses by itself.

### 2. Get the code

```powershell
cd $HOME
git clone https://github.com/ddeuchert/logaperture.git
cd logaperture
git checkout feature/128-overhead-benchmarks
```

If you already have the folder from an earlier run, instead:

```powershell
cd $HOME\logaperture
git pull
```

### 3. Check that it works (about 5 minutes)

```powershell
powershell -ExecutionPolicy Bypass -File logaperture-bench\run-baseline.ps1 -Quick
```

The first time, it spends a few minutes downloading and building. It should end with
**Done. Send David this file:** and a path. If it stops with a red **STOPPED:** message, send
David that message.

### 4. The real run (about 45 minutes)

1. Close everything else: browsers, games, launchers, Discord, anything with a tray icon you can
   quit. Plug in and leave the PC alone; mouse movement is fine, using it isn't.
2. Run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File logaperture-bench\run-baseline.ps1
   ```

3. When it says **Done**, send David the `.zip` file it names. It's in
   `logaperture-bench\target\results\`.

For the run, the script switches Windows to its High (or Ultimate) Performance power plan and
keeps the PC from sleeping. It puts the original plan back at the end, even if the run fails or
you press Ctrl+C.

### What's in the zip

- `machine.txt`: the CPU, memory, Windows version, Java version, power plan and the exact code
  version that ran. No personal files or account details, just the PC's name.
- One `.txt` (what scrolled past on screen) and one `.json` (the same numbers for analysis) per
  run.

## Running on Linux or macOS

```
./mvnw -Pbench -pl logaperture-bench -am package -DskipTests
java -jar logaperture-bench/target/benchmarks.jar -prof gc -rf json
```

For numbers worth comparing, pin the CPU frequency first (Linux: the `performance` governor,
with turbo off) and close everything else.
