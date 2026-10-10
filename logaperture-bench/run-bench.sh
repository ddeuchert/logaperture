#!/usr/bin/env bash
# Copyright 2026 David Deuchert
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Runs LogAperture's overhead benchmarks on Linux or macOS and writes report.md plus a zip of
# everything - doc/specs/overhead-benchmarks.md Decision #19. Same steps and output as
# run-bench.ps1 on Windows.
#
#   logaperture-bench/run-bench.sh --quick     # setup check, about 10 minutes, indicative only
#   logaperture-bench/run-bench.sh             # the full run, about an hour
#
# Options:
#   --quick                  one fork, short iterations
#   --label <name>           names the results folder (default: run)
#   --allow-slow-clock       measure even if System.nanoTime() is slow (the report says so)
#   --async-profiler <lib>   also record flame graphs of the idle scenario (Linux; path to
#                            libasyncProfiler.so)
#
# It records the CPU frequency settings but doesn't change them (that needs root); it prints
# how to pin them instead.

set -euo pipefail

quick=false
label=run
allow_slow=false
async_lib=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --quick) quick=true ;;
        --label) label="$2"; shift ;;
        --allow-slow-clock) allow_slow=true ;;
        --async-profiler) async_lib="$2"; shift ;;
        -h|--help) sed -n '16,31p' "$0"; exit 0 ;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 1 ;;
    esac
    shift
done

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"

fail() { printf '\n\033[31mSTOPPED: %s\033[0m\n' "$1" >&2; exit 1; }
step() { printf '\n\033[36m== %s\033[0m\n' "$1"; }

# --- 1. Java -----------------------------------------------------------------------------------

step "Checking for Java 17 or newer"
command -v java >/dev/null || fail "Java isn't installed (or isn't on the PATH). Install a JDK 17 or newer, e.g. Temurin 21."
java_text="$(java -version 2>&1)"
java_major="$(sed -n 's/.*version "\([0-9]*\).*/\1/p' <<<"$java_text" | head -1)"
[[ "${java_major:-0}" -ge 17 ]] || fail "Found $(head -1 <<<"$java_text"), but Java 17 or newer is needed."
head -1 <<<"$java_text"

# --- 2. Build ----------------------------------------------------------------------------------

step "Building the benchmarks (the first build downloads Maven and libraries; a few minutes)"
./mvnw -B -q --no-transfer-progress -Pbench -pl logaperture-bench -am package -DskipTests \
    || fail "The build failed; see the output above."
jar="$repo/logaperture-bench/target/benchmarks.jar"
[[ -f "$jar" ]] || fail "The build finished but $jar is missing."

# --- 3. Results folder, clock check and machine record -----------------------------------------

name="$label-$(hostname -s 2>/dev/null || hostname)-$(date +%Y%m%d-%H%M)"
$quick && name="$name-quick"
out="$repo/logaperture-bench/target/results/$name"
mkdir -p "$out"

step "Checking the clock"
clock_args=()
$allow_slow && clock_args+=(--allow-slow-clock)
nano_time="$(java -cp "$jar" org.logaperture.bench.ClockCheck "${clock_args[@]}")" \
    || fail "System.nanoTime() is too slow on this machine; see above."
echo "One System.nanoTime() call: $nano_time ns"

step "Recording this machine"
read_file() { [[ -r "$1" ]] && cat "$1" || echo ""; }
if [[ "$(uname -s)" == Darwin ]]; then
    cpu="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || sysctl -n hw.model)"
    cores="$(sysctl -n hw.physicalcpu) / $(sysctl -n hw.logicalcpu)"
    memory="$(( $(sysctl -n hw.memsize) / 1073741824 ))"
    os="macOS $(sw_vers -productVersion) ($(uname -r))"
    clocksource="n/a"
    governor=""
    boost=""
else
    cpu="$(sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo | head -1)"
    cores="$(lscpu -p=CORE 2>/dev/null | grep -v '^#' | sort -u | wc -l) / $(nproc)"
    memory="$(awk '/MemTotal/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo)"
    os="$( (. /etc/os-release && echo "$PRETTY_NAME") 2>/dev/null || uname -s), kernel $(uname -r)"
    clocksource="$(read_file /sys/devices/system/clocksource/clocksource0/current_clocksource)"
    governor="$(read_file /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
    # acpi-cpufreq and amd-pstate expose boost as 1/0; intel_pstate as no_turbo, the other way round.
    boost="$(read_file /sys/devices/system/cpu/cpufreq/boost)"
    if [[ -z "$boost" && -r /sys/devices/system/cpu/intel_pstate/no_turbo ]]; then
        boost=$(( 1 - $(cat /sys/devices/system/cpu/intel_pstate/no_turbo) ))
    fi
fi
{
    echo "label:            $label"
    echo "quick:            $quick"
    echo "date:             $(date -Iseconds)"
    echo "computer:         $(hostname)"
    echo "cpu:              $cpu"
    echo "cores/threads:    $cores"
    echo "memory (GB):      $memory"
    echo "os:               $os"
    echo "clocksource:      $clocksource"
    echo "governor:         $governor"
    echo "boost:            $boost"
    echo "git commit:       $(git rev-parse HEAD 2>/dev/null)"
    echo "git branch:       $(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    echo "nanoTime (ns):    $nano_time"
    echo "java:"
    sed 's/^/    /' <<<"$java_text"
} | tee "$out/machine.txt"

if [[ -n "$governor" && "$governor" != performance ]] || [[ "$boost" == 1 ]]; then
    printf '\n\033[33mThe CPU frequency isn'"'"'t pinned, so expect wider error bars. To pin it for the run (needs root):\033[0m\n'
    echo "    sudo cpupower frequency-set -g performance"
    echo "    echo 0 | sudo tee /sys/devices/system/cpu/cpufreq/boost        # acpi-cpufreq, amd-pstate"
    echo "    echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo  # intel_pstate"
    echo "Continuing as it is."
fi

# --- 4. Benchmarks -----------------------------------------------------------------------------

step "Running the benchmarks (started $(date +%H:%M)); leave the machine alone until it's done"
suite_args=("$out")
$quick && suite_args+=(--quick)
[[ -n "$async_lib" ]] && suite_args+=(--async-profiler "$async_lib")
java -cp "$jar" org.logaperture.bench.Suite "${suite_args[@]}" 2>&1 | tee "$out/benchmarks.txt"
[[ "${PIPESTATUS[0]}" -eq 0 ]] || fail "A benchmark run failed; see $out/benchmarks.txt"

# --- 5. Report and zip -------------------------------------------------------------------------

step "Writing the report"
java -cp "$jar" org.logaperture.bench.Report "$out" >/dev/null || fail "The report step failed."
zip="$out.zip"
(cd "$out" && jar cfM "$zip" .)

printf '\n\033[32mDone.\033[0m\n'
echo "    report: $out/report.md"
echo "    zip:    $zip"
