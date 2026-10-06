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

<#
.SYNOPSIS
    Runs LogAperture's overhead benchmarks on Windows and writes report.md plus a zip of everything.

.DESCRIPTION
    doc/specs/overhead-benchmarks.md Decision #19, the Windows twin of run-bench.sh: builds
    logaperture-bench, checks the clock, records the machine it runs on, switches to a
    high-performance power plan for the run (restoring the original afterwards), keeps the PC
    from sleeping, runs the published set of benchmarks, and writes report.md and a zip.

    Takes about an hour. Don't use the PC while it runs.

.PARAMETER Label
    Names the results folder (default: "run").

.PARAMETER Quick
    A setup check of about 10 minutes: one fork, short iterations, numbers indicative only.
    Use it first.

.PARAMETER AllowSlowClock
    Measure even if one System.nanoTime() call costs over 100 ns. The report then carries a warning.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File logaperture-bench\run-bench.ps1 -Quick
    powershell -ExecutionPolicy Bypass -File logaperture-bench\run-bench.ps1
#>
param(
    [string]$Label = "run",
    [switch]$Quick,
    [switch]$AllowSlowClock
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Fail([string]$message) {
    Write-Host ""
    Write-Host "STOPPED: $message" -ForegroundColor Red
    exit 1
}

function Step([string]$message) {
    Write-Host ""
    Write-Host "== $message" -ForegroundColor Cyan
}

# --- 1. Java -------------------------------------------------------------------------------------

Step "Checking for Java 17 or newer"
$javaText = & cmd /c "java -version 2>&1"
if ($LASTEXITCODE -ne 0 -or -not $javaText) {
    Fail ("Java isn't installed (or isn't on the PATH). Install it with:`n`n" +
          "    winget install EclipseAdoptium.Temurin.21.JDK`n`n" +
          "then close this window, open a new PowerShell, and run this script again.")
}
$javaFirst = ($javaText | Select-Object -First 1).ToString()
if ($javaFirst -match '"(\d+)') { $javaMajor = [int]$Matches[1] } else { $javaMajor = 0 }
if ($javaMajor -lt 17) {
    Fail ("Found $javaFirst, but Java 17 or newer is needed. Install it with:`n`n" +
          "    winget install EclipseAdoptium.Temurin.21.JDK`n`n" +
          "then open a new PowerShell and run this script again.")
}
Write-Host $javaFirst

# --- 2. Build ------------------------------------------------------------------------------------

Step "Building the benchmarks (the first build downloads Maven and libraries; a few minutes)"
& .\mvnw.cmd -B -q --no-transfer-progress -Pbench -pl logaperture-bench -am package -DskipTests
if ($LASTEXITCODE -ne 0) { Fail "The build failed. Send David the output above." }
$jar = Join-Path $repo "logaperture-bench\target\benchmarks.jar"
if (-not (Test-Path $jar)) { Fail "The build finished but $jar is missing." }

# --- 3. Results folder, clock check and machine record -------------------------------------------

$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$name = "$Label-$env:COMPUTERNAME-$stamp"
if ($Quick) { $name = "$name-quick" }
$out = Join-Path $repo "logaperture-bench\target\results\$name"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Step "Checking the clock"
$clockArgs = @("-cp", $jar, "org.logaperture.bench.ClockCheck")
if ($AllowSlowClock) { $clockArgs += "--allow-slow-clock" }
$nanoTime = (& java @clockArgs | Select-Object -First 1)
if ($LASTEXITCODE -ne 0) { Fail "System.nanoTime() is too slow on this PC; see above." }
Write-Host "One System.nanoTime() call: $nanoTime ns"

Step "Recording this machine"
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$os = Get-CimInstance Win32_OperatingSystem
$cs = Get-CimInstance Win32_ComputerSystem
$machine = @(
    "label:            $Label"
    "quick:            $Quick"
    "date:             $(Get-Date -Format o)"
    "computer:         $($cs.Manufacturer) $($cs.Model)"
    "cpu:              $($cpu.Name.Trim())"
    "cores/threads:    $($cpu.NumberOfCores) / $($cpu.NumberOfLogicalProcessors)"
    "max clock (MHz):  $($cpu.MaxClockSpeed)"
    "memory (GB):      $([math]::Round($cs.TotalPhysicalMemory / 1GB, 1))"
    "os:               $($os.Caption) $($os.Version) build $($os.BuildNumber)"
    "git commit:       $(& cmd /c "git rev-parse HEAD 2>nul")"
    "git branch:       $(& cmd /c "git rev-parse --abbrev-ref HEAD 2>nul")"
    "nanoTime (ns):    $nanoTime"
    "java:"
) + ($javaText | ForEach-Object { "    $_" })
$machine | Set-Content -Encoding UTF8 (Join-Path $out "machine.txt")
$machine | ForEach-Object { Write-Host $_ }

# --- 4. Power plan and sleep ---------------------------------------------------------------------

$plans = powercfg /list | Out-String
$original = $null
if ((powercfg /getactivescheme | Out-String) -match '([0-9a-fA-F-]{36})') { $original = $Matches[1] }
$ultimate = "e9a42b02-d5df-448d-aa00-03f14749eb61"
$high = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"
if ($plans -match $ultimate) { $target = $ultimate }
elseif ($plans -match $high) { $target = $high }
else { $target = $null }

Add-Type -Namespace LogAperture -Name Power -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
$ES_CONTINUOUS = [uint32]2147483648      # 0x80000000
$ES_SYSTEM_REQUIRED = [uint32]1

try {
    if ($target) {
        powercfg /setactive $target
        Write-Host "Power plan switched for the run: $((powercfg /getactivescheme | Out-String).Trim())"
    } else {
        Write-Host "No High/Ultimate Performance power plan found; running on the current plan." -ForegroundColor Yellow
    }
    "power plan:       $((powercfg /getactivescheme | Out-String).Trim())" |
        Add-Content -Encoding UTF8 (Join-Path $out "machine.txt")
    [LogAperture.Power]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED) | Out-Null

    # --- 5. Benchmarks ---------------------------------------------------------------------------

    # The published set of runs lives in one place, the Suite class, so this script and
    # run-bench.sh run exactly the same thing.
    Step "Running the benchmarks (started $(Get-Date -Format HH:mm)); leave the PC alone until it's done"
    $suiteArgs = @("-cp", $jar, "org.logaperture.bench.Suite", $out)
    if ($Quick) { $suiteArgs += "--quick" }
    & java @suiteArgs | Tee-Object -FilePath (Join-Path $out "benchmarks.txt")
    if ($LASTEXITCODE -ne 0) { Fail "A benchmark run failed; see $(Join-Path $out "benchmarks.txt")" }
}
finally {
    [LogAperture.Power]::SetThreadExecutionState($ES_CONTINUOUS) | Out-Null
    if ($original -and $target) {
        powercfg /setactive $original
        Write-Host "Power plan restored."
    }
}

# --- 6. Report and zip ---------------------------------------------------------------------------

Step "Writing the report"
& java -cp $jar org.logaperture.bench.Report $out | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "The report step failed." }
$zip = Join-Path $repo "logaperture-bench\target\results\$name.zip"
Compress-Archive -Path (Join-Path $out "*") -DestinationPath $zip -Force
Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "    report: $(Join-Path $out "report.md")" -ForegroundColor Green
Write-Host "    zip:    $zip" -ForegroundColor Green
