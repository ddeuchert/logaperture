# `logctl` against a service running as another account

Status: **Agreed 2026-10-28** (decisions 1 to 4 below); implemented in PR #288. Targets `1.0.0`. The first open question has been answered by a
real test (below): attach from an elevated Administrator prompt reaches a `LocalSystem` JVM, so
no new access mechanism is needed. The remaining work is
making `logctl` find that JVM without `--pid` (Windows), plus a guide page; the false "No process
with PID" error is a separate bug fix. **Maintainer decision, 2026-10-28:** the whole fix goes into
1.0.0 as a freeze exception, because it is limited to `logctl`, adds no command, option or access
path, and touches nothing in the agent.

Tracking issue: [#287](https://github.com/ddeuchert/logaperture/issues/287) (milestone `1.0.0`;
branches from and targets `release/1.0`, see [`release-lines.md`](release-lines.md)). The false
"No process with PID" error is tracked separately as bug [#289](https://github.com/ddeuchert/logaperture/issues/289) (see Findings).

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §8.1 (the CLI over the Attach
API), §8.2 (the agent never listens), §9.8 (authentication comes from the operating system), §16
(constrained and customer-hosted environments).
Builds on: [`cli-transport.md`](cli-transport.md) (the attach transport and "Discovery"),
[`doctor.md`](doctor.md). Related, on `develop` for 1.1.0 and not yet on this line: `logctl attach`
(#273), which shares the same pre-attach process check.

## Functional summary

After this feature, the user will be able to:

- On a Windows server, run any `logctl` command from a command prompt opened as Administrator,
  with no `--pid`, and have it find the one JVM running LogAperture, including one that runs as a
  Windows service under `LocalSystem`, the way `jboss-cli.bat` already works from that prompt.
- Have other `java.exe` processes on the machine ignored: only a JVM running the agent counts.
- Be asked which JVM when several run the agent (in a terminal), or be told to pass `--pid` (in a
  script), exactly as on any other platform.
- Be told the real reason when `logctl` cannot reach a JVM, instead of "No process with PID" for a
  process that is running.
- Read a guide page that shows the above, and how to find the JVM's PID behind a Windows service
  wrapper when `--pid` is needed.

Linux and macOS behaviour does not change.

Guide pages this changes (when it lands): `troubleshooting.md` (the messages above);
`concepts/security.md` and `how-to/try-without-restart.md` ("same user" corrected for Windows
administrators, once the behaviour is confirmed there); a new `how-to/` page for services on
Windows; the changelog.

## Motivation

A customer runs WildFly as a Windows service under `LocalSystem`, through a native service wrapper.
Their administrators have an Administrator prompt, not a SYSTEM shell, and Sysinternals PsExec is
not installed. `jboss-cli.bat` works for them because it connects to the management port
(`localhost:9990`) and WildFly's local authentication trusts anyone who can read the token file
under `standalone\tmp\auth`, which Administrators can. `logctl` is meant to replace that path for
logging changes. The spec and guide say `logctl` needs the same account as the JVM (§9.8,
`how-to/try-without-restart.md`: "Same machine, same user").

## Findings (a real Windows service, 2026-10-28)

Customer machine, beta.3, application as a `LocalSystem` service behind a native wrapper, JDK
21.0.4, commands run from an elevated Administrator `cmd`:

1. `logctl status` printed "No LogAperture-enabled JVM found. Start the application with
   -javaagent:logaperture-agent.jar." with no "could not be inspected" line, so
   `VirtualMachine.list()` did not return the JVM at all. On Windows that list is built from the
   current user's own temporary directory, which is not where a `LocalSystem` process writes. This
   is a discovery gap, not a refusal.
2. `sc queryex` gives the wrapper's PID, not the JVM's. The JVM is a child `java.exe` in the
   Services session; its PID was found by process-tree or command-line lookup.
3. `logctl status --pid 7100` printed "No process with PID 7100." while `tasklist /fi "pid eq
   7100"` listed that `java.exe`. The message comes from a `ProcessHandle.of(pid).isEmpty()`
   check that runs before any attach is tried, in `Discovery.resolveTargetPid` and in
   `AgentConnection.attachFailure`. **Verified by probe:** `ProcessHandle.of(7100)` is empty for the
   service's JVM while `tasklist` lists it and attach succeeds, so the JDK reports a process the
   caller cannot open as absent.
4. `jcmd 7100 VM.version` from the same prompt succeeded, and a probe's
   `VirtualMachine.attach("7100")` read `logaperture.version=1.0.0-beta.3` and `user.name=SYSTEM`.
   **The Attach API reaches a `LocalSystem` JVM from an elevated Administrator prompt.** `logctl`
   never got as far as attaching.
5. Discovery probes, same machine:
   - `VirtualMachine.list()` and `ProcessHandle.allProcesses()` did not return the service JVM. The
     latter reports the command of other accounts' processes as unreadable (142 of them), so it
     cannot pick out `java.exe`.
   - `tasklist /fo csv /nh /fi "imagename eq java.exe"` listed it (PID 7100, Services session),
     whoever owns it.
   - Its `hsperfdata` file is at
     `C:\Windows\System32\config\systemprofile\AppData\Local\Temp\hsperfdata_SYSTEM\7100`, readable by
     the Administrator. Not under `C:\Windows\Temp`, and not under the JVM's own `java.io.tmpdir`,
     which is the relative path `../../jvmtemp/`.
   - Attaching only to PIDs that `tasklist` named: the Jenkins agent under the Administrator's own
     account attached (`logaperture.version` null, so correctly not a candidate), the service JVM
     attached (marker present), and the probe's own JVM was refused as expected.

## Proposed changes (to be signed off)

- **A. Do not report a live process as missing.** When `--pid` names a process that exists but
  that `logctl` cannot inspect, try the attach and report its outcome. "No process with PID" is
  kept for a PID that `tasklist` would not list either. Same change in the pre-attach check that
  `logctl attach` (#273) adds on `develop`.
- **B. Discovery finds a service's JVM on Windows.** With no `--pid`, from an elevated prompt,
  `logctl` collects candidate PIDs from three sources, probes each once the way it does today
  (attach, read the `logaperture.version` property), and then applies the existing rules unchanged:
  one match is used, several are asked about in a terminal (`pick-jvm.md`) or exit 4 with the table
  and `--pid` when it cannot ask, none is "No LogAperture-enabled JVM found". The sources:
  1. `VirtualMachine.list()`, as today.
  2. `tasklist` for `java.exe` and `javaw.exe`, which names them whoever owns them.
  3. Pid files in the `hsperfdata_*` directories under the system profile locations
     (`...\config\systemprofile\AppData\Local\Temp`, `ServiceProfiles\*\AppData\Local\Temp`,
     `C:\Windows\Temp`), which also finds a JVM hosted inside a service wrapper process such as
     `prunsrv.exe`, where the process image is not `java.exe`.
  Only PIDs from these sources are probed: attaching on Windows starts a thread in the target, so
  an arbitrary process is never probed. When nothing is found, the message says that JVMs owned by
  other accounts may not be listed and names `--pid`. Scope is Windows; the Linux and macOS path is
  not touched. **Proposed, not signed off; see open question 5.**
- **C. Guide page and corrected wording**, as listed above.
- **D. A `doctor` hint** is optional and deferred: `doctor` runs after a JVM is found, so it
  cannot help with the case that started this.

## Open design questions

Question 5 is decided (see it). The others are not, and none blocks the change.

1. **Is the same true for a `LocalService` or `NetworkService` account, or a named service
   account?** Only `LocalSystem` has been tested.
2. **Is it the elevated token that matters?** A non-elevated administrator was not tested; the
   guide should say "elevated" only if that is what the test shows.
3. **The fix for the false "No process"** (bug #289; the cause is verified above): skip the check
   and let the attach report its outcome, or treat an empty handle as "unknown" and say so.
4. **Other platforms.** Whether the same pre-attach check misreports on Linux for a process owned
   by another user (there the attach itself is refused, so it may not).
5. **Which discovery sources to keep.** *Agreed 2026-10-28:* all three of change B, with no cap
   or time limit on the probes (the candidate lists are short, they run one after another, and a
   refused PID is skipped), and no per-user profile scanning, so a named service account is found
   through `tasklist` when its image is `java.exe` or `javaw.exe`. Other service accounts are
   untested. A JVM started with `-XX:-UsePerfData` and hosted inside a wrapper process is not found;
   `--pid` still reaches it.
6. **Wrapper processes.** A native service wrapper is not a JVM and is never a candidate; only the
   JVM it launches is. Whether a launcher JVM that exits or holds no agent needs any special case.

## Cost and dependencies

Layer 0, transport only: no new operation and no change to any MXBean signature, so the §11.1
compatibility promise is untouched. No new access path is added, so §8.2 and §9.8 are unchanged:
authorization is still the operating system's, and an elevated Administrator is already allowed by
it. The false-error fix and the guide page fit the beta.1 freeze (§17.1) as a bug fix and
documentation. The discovery change (B) is the part that goes beyond a bug fix; Status records the decision to
include it in 1.0.0.

Needs a real Windows service run as `LocalSystem` to test; the CI matrix has none, and the dev
WildFly environment is Linux. The customer's machine produced the findings above; a repeat after
the fix is the acceptance test.

## What was tested

- Unit tests with fakes (`HostProcessesTest`, `DiscoveryWindowsTest`): the `tasklist` parser, the
  profile `hsperfdata` scan, the merge and de-duplication of the sources, the messages, and that
  nothing runs off Windows. They run on Linux in CI.
- Not tested in CI: anything against a real Windows service. The probe runs on the customer's
  machine (2026-10-28) were the evidence for the design.
- **Acceptance (2026-10-28):** on that machine (Windows Server 2019, JDK 21.0.4, application as a
  `LocalSystem` service, elevated Administrator prompt), the built `logctl` ran `status --pid
  <n>` and then `status` with no `--pid`, and both returned the service's status.
