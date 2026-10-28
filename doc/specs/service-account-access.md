# `logctl` against a service running as another account

Status: **Draft, not signed off.** Targets `1.0.0`. The first open question has been answered by a
real test (below): attach from an elevated Administrator prompt reaches a `LocalSystem` JVM, so
no new access mechanism is needed and no feature-freeze exception (§17.1). The remaining work is
two corrections to how `logctl` finds and reports a JVM it cannot see, plus a guide page.

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

- Run `logctl status --pid <n>` (and every other command) from an Administrator prompt against an
  application server that runs as a Windows service under `LocalSystem`, the way they can already
  run `jboss-cli.bat`, without first becoming that account.
- Be told the real reason when `logctl` cannot reach a JVM, instead of "No process with PID" for a
  process that is running.
- Be pointed at `--pid` when `logctl` finds no JVM on a machine where a service may be running one.
- Read a guide page that shows how to find the JVM's PID behind a Windows service wrapper and how
  to run `logctl` against it.

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
   `AgentConnection.attachFailure`. The likely cause is that the JDK reports a process the caller
   may not open as absent on Windows. That cause is **not verified**; the false message is.
4. `jcmd 7100 VM.version` from the same prompt succeeded. **The Attach API reaches a `LocalSystem`
   JVM from an elevated Administrator prompt.** `logctl` never got as far as attaching.

## Proposed changes (to be signed off)

- **A. Do not report a live process as missing.** When `--pid` names a process that exists but
  that `logctl` cannot inspect, try the attach and report its outcome. "No process with PID" is
  kept for a PID that `tasklist` would not list either. Same change in the pre-attach check that
  `logctl attach` (#273) adds on `develop`.
- **B. Say what discovery cannot see.** When no JVM is found, say that JVMs started by other
  accounts, such as services, are not listed and name `--pid`. On Windows the message could also
  say how to find the JVM's PID behind a wrapper. Open: whether discovery can safely list more
  than the per-user directory (for example by also trying the `hsperfdata` directories of other
  accounts when elevated), or whether naming `--pid` is the whole fix. Deferred.
- **C. Guide page and corrected wording**, as listed above.
- **D. A `doctor` hint** is optional and deferred: `doctor` runs after a JVM is found, so it
  cannot help with the case that started this.

## Open design questions (deferred to when this is specced)

Nothing below is decided.

1. **Is the same true for a `LocalService` or `NetworkService` account, or a named service
   account?** Only `LocalSystem` has been tested.
2. **Is it the elevated token that matters?** A non-elevated administrator was not tested; the
   guide should say "elevated" only if that is what the test shows.
3. **Cause of the false "No process".** Verify on Windows whether `ProcessHandle.of` returns empty
   for an unopenable process, and whether the fix is to skip the check or to treat empty as
   "unknown".
4. **Other platforms.** Whether the same pre-attach check misreports on Linux for a process owned
   by another user (there the attach itself is refused, so it may not).
5. **Discovery beyond the current user** (change B above).

## Cost and dependencies

Layer 0, transport only: no new operation and no change to any MXBean signature, so the §11.1
compatibility promise is untouched. No new access path is added, so §8.2 and §9.8 are unchanged:
authorization is still the operating system's, and an elevated Administrator is already allowed by
it. A fix and a guide page fit the beta.1 freeze (§17.1) as a bug fix and documentation.

Needs a real Windows service run as `LocalSystem` to test; the CI matrix has none, and the dev
WildFly environment is Linux. The customer's machine produced the findings above; a repeat after
the fix is the acceptance test.
