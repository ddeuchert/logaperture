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

One command builds the benchmarks, checks the machine, runs the published set and writes
`report.md`: how much longer a log call takes with LogAperture than without it, as a percentage
and in nanoseconds, plus the budget checks and the machine it ran on. Everything lands in one
folder under `logaperture-bench/target/results/`, and a zip of it next to that folder.

You need Git and a JDK 17 or newer; the scripts download Maven themselves.

## Linux and macOS

```
logaperture-bench/run-bench.sh --quick    # setup check, about 10 minutes, numbers indicative only
logaperture-bench/run-bench.sh            # the full run, about an hour
```

Options: `--label <name>` names the results folder; `--allow-slow-clock` measures even when the
clock check fails; `--async-profiler <path to libasyncProfiler.so>` also records flame graphs of
the `idle` scenario (Linux).

For numbers worth comparing, close everything else and pin the CPU frequency first. The script
records the governor and boost settings and prints how to pin them, but doesn't change them,
since that needs root:

```
sudo cpupower frequency-set -g performance
echo 0 | sudo tee /sys/devices/system/cpu/cpufreq/boost          # acpi-cpufreq, amd-pstate
echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo  # intel_pstate
```

## Windows

Open **PowerShell** and, once, install Git and Java, then open a new PowerShell:

```powershell
winget install Git.Git
winget install EclipseAdoptium.Temurin.21.JDK
```

Get the code and run:

```powershell
git clone https://github.com/ddeuchert/logaperture.git
cd logaperture
powershell -ExecutionPolicy Bypass -File logaperture-bench\run-bench.ps1 -Quick   # about 10 minutes
powershell -ExecutionPolicy Bypass -File logaperture-bench\run-bench.ps1          # about an hour
```

`-Label <name>` and `-AllowSlowClock` work as on Linux. For the run, the script switches Windows
to its High (or Ultimate) Performance power plan and keeps the PC from sleeping; it puts the
original plan back at the end, even if the run fails or you press Ctrl+C. If it stops with a red
**STOPPED:** message, that message says why.

## The clock check

Every log record reads the clock, so before anything runs the scripts time one
`System.nanoTime()` call. About 25 ns is normal; over 100 ns, they stop. On Linux the usual cause
is the kernel rejecting the TSC at boot and falling back to `hpet` (check
`cat /sys/devices/system/clocksource/clocksource0/current_clocksource`, and `dmesg | grep -i tsc`);
booting with `tsc=reliable` usually fixes it.

## What's in the results folder

- `report.md`: the overhead table, the budget and decision-rule checks, the component
  benchmarks and the machine.
- `machine.txt`: CPU, memory, OS, Java, frequency settings or power plan, the clock check and
  the exact code version. No personal files or account details, just the machine's name.
- `benchmarks.txt`: everything that scrolled past; one `.json` per run with JMH's raw numbers.

## Running JMH directly

```
./mvnw -Pbench -pl logaperture-bench -am package -DskipTests
java -jar logaperture-bench/target/benchmarks.jar RulesBenchmark -prof gc
java -cp logaperture-bench/target/benchmarks.jar org.logaperture.bench.Report <folder with JMH JSON>
```
