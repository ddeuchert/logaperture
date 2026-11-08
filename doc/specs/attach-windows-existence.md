# `logctl attach` on Windows: a running process is not missing, and a service's JVM is listed

Status: **Signed off 2026-11-07** (decisions W1 to W4 below, every recommendation taken). Release `1.1.0`.

Parent specs: [`logctl-attach.md`](logctl-attach.md) (#1 command shape, #10 platforms),
[`service-account-access.md`](service-account-access.md) (findings 3 to 5; the fix that went into `1.0.0` as #289).
Tracking issue: [#290](https://github.com/ddeuchert/logaperture/issues/290), which is the part of #289 that
`logctl attach` (#273, `1.1.0` only) did not have on `release/1.0`.

## Functional summary

After this feature, the user will be able to:

- On Windows, from a command prompt opened as Administrator, run `logctl attach <pid>` against a JVM that
  runs as another account, such as a service under `LocalSystem`, and have it attach, instead of
  being told "No process with PID" for a process that is running.
- Run `logctl attach` with no PID on Windows and see that service's JVM in the list of JVMs to choose from.
- Be told the real reason when the agent cannot be loaded into a process that is running, as on Linux.

Guide pages this changes: `how-to/windows-service.md` (attach to the service's JVM), `how-to/try-without-restart.md`
(the "same user" line for Windows administrators, if it still says so), and the changelog.

Not part of this feature: any change on Linux or macOS; the agent; the other `logctl` commands, which already
had this fix in #289.

## The problem

`VmAttacher.loadFailure` reports "No process with PID n" when `ProcessHandle.of(pid).isEmpty()`. On Windows
that handle is empty for a process the caller may not open, though the process runs and attach works from an
elevated prompt (service-account-access.md, finding 3). Every other command asks
`HostProcesses.exists(pid)` instead, which believes `ProcessHandle` when it finds one, asks `tasklist`
when it does not, and says "exists" when `tasklist` cannot run so that the attach reports its own outcome.

`logctl attach` also builds its list of JVMs from `VirtualMachine.list()` alone, which reads only the
current user's `hsperfdata`, so a service's JVM is never offered.

## Findings from the code (2026-11-07)

- `VmAttacher.loadFailure` (the one false-absent check) and `AgentConnection.attachFailure`, which it falls
  back to and which already uses `HostProcesses.exists`.
- `VmAttacher.inspect` reads the process's command name with `ProcessHandle.of(pid)` after a successful
  attach, with `""` when it is empty; `targetOf` does the same for the start time (`null`). Both are display
  only and cannot make an attach fail, so they need no change; the name is blank in a table for a service's JVM.
- `Discovery.find` already unions `VirtualMachine.list()` with `HostProcesses.otherAccountJvmPids()` on Windows
  and probes each PID once. `VmAttacher.targets()` does not.

## Design

**W1. `loadFailure` asks `HostProcesses.exists`.** A new overload takes the `HostProcesses`; the existing
signature delegates with `HostProcesses.system()`, as `attachFailure` does, so the unit tests can pass a fake
`tasklist`. On Linux and macOS `exists` is exactly `ProcessHandle.of(pid).isPresent()`, so nothing changes there.

**W2. `targets()` adds the other accounts' JVMs on Windows.** The same sources as discovery
(`HostProcesses.otherAccountJvmPids`: the `java.exe` and `javaw.exe` rows of one `tasklist`, plus
`hsperfdata` PIDs `tasklist` still lists), each probed once with the attach the list already does, deduplicated
against the JDK's list, the CLI's own PID removed. Nothing but those PIDs is probed. A JVM that cannot be
inspected is skipped, as today. The table shows a blank name for a service's JVM (as discovery does for these).

**W3. The empty-list message names the Windows case.** "No JVM to attach to: none is running as this user ..."
gains, on Windows only, the sentence discovery already uses: a JVM running as another account that is not listed
can be named with its PID. Linux and macOS text unchanged.

## Decisions to sign off

| # | Decision | Recommendation |
|---|----------|----------------|
| W1 | Use `HostProcesses.exists` in `loadFailure` | Yes |
| W2 | List other accounts' JVMs in `logctl attach` with no PID (the issue left this undecided) | Yes: it is the same sources and probing discovery already uses, and the Administrator who needs `attach <pid>` for a service is the one who most needs the list |
| W3 | The Windows hint in the empty-list message | Yes |
| W4 | Tests: `loadFailure` with a fake `HostProcesses` (process invisible to `ProcessHandle` but listed by `tasklist`; `tasklist` unavailable; truly absent) and `targets()` against a fake source; then a hand check against a real service JVM from an elevated prompt before the `1.1.0` release (logctl-attach.md #10 already treats Windows as hand-checked) | Yes. Nothing here runs per call, so no benchmark |
