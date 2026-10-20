# Launcher configuration file for `logctl` (issue #238)

Status: **signed off 2026-10-19** (L1–L6 agreed); L7 added and agreed 2026-10-19.
Parent spec: [`distribution-bundle.md`](distribution-bundle.md) "Launcher change" (the
`bin/logctl` and `bin/logctl.cmd` launchers this feature extends).

## Functional summary

After this feature, the user will be able to:

- Drop a `logctl.conf` (Linux, macOS) or `logctl.conf.cmd` (Windows) next to `logctl` and have it
  take effect on every run, whatever environment the caller has.
- Point `logctl` at a bundled JDK by a path relative to the launcher, for example
  `..\..\java`, so an application that ships its own Java runs `logctl` with no setup.
- Set or clear any environment variable for `logctl` (`JAVA_OPTS`, `JAVA_TOOL_OPTIONS`, …) from
  that file.
- Use `JAVA_HOME` to choose the Java for `logctl`, with or without the file.
- Use `LOGCTL_JAR_HOME` to say which directory holds `logaperture-cli.jar`, when the jar isn't in
  the usual place beside the launcher.
- Get a clear error naming the path it tried when the configured Java isn't there, instead of
  `logctl` quietly running some other `java`.

Guide pages changed: [Download](../../guide/get-started/download.md) (a pointer from the `bin/`
row) and a new vendor/integrator page, `guide/vendors/logctl-launcher.md`.

## Why

An application that ships LogAperture inside its own distribution is expected to run when dropped
into a folder, regardless of what is set in the caller's environment; the application's own
scripts override the environment from paths relative to themselves. Today `logctl` can't take part
in that: both launchers run bare `java` from `PATH` and ignore `JAVA_HOME`, so the Java found
depends entirely on the caller. WildFly solves the same problem with `standalone.conf`, a file the
launch script reads before it uses the environment; this is the same idea.

## Behavior

### L1. The file

Each launcher looks for a file named for it, in the launcher's own directory (`bin/`):

| Launcher | File | Language |
|---|---|---|
| `bin/logctl` | `bin/logctl.conf` | POSIX `sh`, sourced with `.` |
| `bin/logctl.cmd` | `bin/logctl.conf.cmd` | batch, run with `call` |

If the file exists it is read **first**, before the launcher reads any variable. If it doesn't
exist, nothing changes: the launcher uses the ambient environment. There is no search path and no
override variable; the file is found beside the launcher or not at all.

### L2. What the file can do

The file runs inside the launcher's own process, so it may set, override or clear any variable,
and whatever it leaves behind is what the launcher and the JVM it starts see. A plain assignment
replaces an inherited value; `unset` (sh) or `set "NAME="` (cmd) clears one. Because the file is
code it can also compute values.

The launcher's own directory is available to the file as `$here` (sh) and `%~dp0` (cmd, with its
trailing backslash), so a relative location resolves against the launcher and not against the
caller's working directory:

```sh
# bin/logctl.conf
JAVA_HOME="$here/../../java"
unset JAVA_TOOL_OPTIONS
```

```bat
rem bin\logctl.conf.cmd
set "JAVA_HOME=%~dp0..\..\java"
set "JAVA_TOOL_OPTIONS="
```

The spec promises these names (`here`, `%~dp0`) and the file's position (before everything); it
makes no other promise about launcher internals a file could reach.

### L3. Choosing the Java

After the file (if any) has run:

1. If `JAVA_HOME` is set and non-empty, the launcher runs `$JAVA_HOME/bin/java` and **nothing
   else**. If that file doesn't exist or isn't executable, the launcher exits 1 with
   `logctl: JAVA_HOME is set to <path> but <path>/bin/java was not found.` (the `<path>` printed
   is the one it tried, with `..` segments left as written). It does not fall back to `PATH`.
2. Otherwise it runs `java` from `PATH`, as today.

This applies with or without a file: an ambient `JAVA_HOME` is now honored. That is a behavior
change for anyone who has `JAVA_HOME` set to something other than the Java they want `logctl` to
use; the release notes call it out.

The existing requirement stands: it must be a JDK, not a JRE (`com.sun.tools.attach`).

### L7. `LOGCTL_JAR_HOME`

`LOGCTL_JAR_HOME` names the directory that holds `logaperture-cli.jar`. Like every other
variable it is read after the conf file has run, so the file can set it (for example
`LOGCTL_JAR_HOME="$here/../../lib/logaperture"`) as well as the caller.

- Set and non-empty: the launcher uses `$LOGCTL_JAR_HOME/logaperture-cli.jar` and nothing else. If
  that file doesn't exist it exits 1 with
  `logctl: LOGCTL_JAR_HOME is set to <dir> but <dir>/logaperture-cli.jar was not found.` It does
  not fall back to the lookup below, for the same reason `JAVA_HOME` doesn't fall back to `PATH`.
- Otherwise: the existing lookup, unchanged (`../lib/logaperture-cli.jar`, then
  `../target/logaperture-cli.jar`, then the existing "not found" error).

### L4. `JAVA_OPTS`

The launcher passes `$JAVA_OPTS` (sh: unquoted, so it splits on whitespace; cmd: `%JAVA_OPTS%`)
to the JVM ahead of `-jar`. It is empty if unset. Set it in the file to pass flags such as
`-Xmx` or `-Djdk.console=java.base`; clear it there to ignore a caller's value.

### L5. What this does not do

- It doesn't ship a conf file, an `.example`, or a template. The zip is unchanged apart from the
  launchers. Producing the file is the integrator's job; the guide page documents the contract
  (L1–L4) and carries the snippets above.
- It doesn't touch the agent, `-javaagent` arguments or `-Dlogaperture.*` properties; it is
  about starting `logctl` only.
- It doesn't validate the file. A syntax error in it is the shell's or `cmd`'s own error.

## Tests

- A launcher test per platform family that runs the real script in a temporary `bin/` directory
  with a stub `java` (a script that prints its arguments and environment), covering: no file and
  no `JAVA_HOME` uses `PATH`; ambient `JAVA_HOME` is used; a file's `JAVA_HOME` overrides the
  ambient one; a relative `JAVA_HOME` set from `$here` resolves against the launcher when run from
  another working directory; a missing `$JAVA_HOME/bin/java` exits 1 with the message and does not
  run the `PATH` `java`; the file can clear `JAVA_OPTS`; `JAVA_OPTS` reaches the JVM before `-jar`;
  `LOGCTL_JAR_HOME` selects the jar over `../lib`, a file-set one overrides the ambient one, and
  one pointing at a directory without the jar exits 1 with the message.
- The `.cmd` cases run only on Windows (skipped elsewhere); the sh cases run on Linux and macOS.
  The Windows case is also checked by hand on a real Windows machine before merge.

## Guide changes

- `guide/vendors/logctl-launcher.md` (new): the file names and location, order of evaluation, the
  `here` / `%~dp0` variable, the `JAVA_HOME` rule with the no-fallback behavior, `JAVA_OPTS`, and
  `LOGCTL_JAR_HOME`, the two snippets, with the error output captured from a real run.
- `guide/get-started/download.md`: the `bin/logctl` row links to that page.
- `guide/release-notes.md`: the ambient `JAVA_HOME` change.

## Decisions

| # | Decision | Status |
|---|---|---|
| L1 | Conf file beside the launcher, sourced first; absent means the ambient environment | Agreed (David, 2026-10-19) |
| L2 | File may set, override or clear any variable; `$here` / `%~dp0` available | Agreed in thread |
| L3 | `JAVA_HOME` wins when set, with no `PATH` fallback; ambient `JAVA_HOME` honored | Agreed (David, 2026-10-19) |
| L4 | `JAVA_OPTS` passed to the JVM | Agreed (David, 2026-10-19) |
| L5 | Nothing shipped; guide documents the contract | Agreed (David, 2026-10-19) |
| L6 | No `LOGCTL_CONF` redirect; beside the launcher only | Agreed (David, 2026-10-19) |
| L7 | `LOGCTL_JAR_HOME` = directory holding `logaperture-cli.jar`; wins when set, no fallback | Agreed (David, 2026-10-19) |
