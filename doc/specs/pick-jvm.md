# Pick the JVM when several are running (issue #106)

Status: **signed off 2026-09-26** (J1–J9 agreed).
Parent spec: [`cli-transport.md`](cli-transport.md) "Discovery" (step 3, "more than one") and
"Output and exit codes".
Related: [`guided-add-rule.md`](guided-add-rule.md) (#104), whose decision G11 split this out;
it shares that feature's terminal check and question reader.

## Functional summary

After this feature, the user will be able to:

- Run any `logctl` command on a terminal while several LogAperture JVMs are running, and pick
  the one they mean from a numbered list instead of re-running the command with `--pid`.
- Tell two similar JVMs apart (two WildFly servers, say) by when each started and which
  directory it runs from, in that list and in the existing "several candidates" table.
- See the `--pid` to use next time, so a script or a repeat command can skip the question.
- Keep scripts working as today: without a terminal, or with `--json`, several candidates are
  still exit 4 with the table.

## Why

Running a local WildFly and a test app side by side is ordinary development. Today every
command then stops:

```
PID    VERSION        COMMAND
41822  0.1.0-alpha.3  org.jboss.modules.Main -mp /opt/wildfly/modules org.jboss.as.standalone
52310  0.1.0-alpha.3  com.acme.App
Several candidates — pass --pid <n>.
```

The operator copies a PID and types the whole command again. With two WildFly servers the
table doesn't even say which is which: both rows show the same command.

## Behavior

### When it asks (J1)

Discovery asks which JVM to use when **all** of these hold:

- more than one candidate was found and no `--pid` was given;
- a terminal is attached (see J8 for how that is decided);
- neither `--json` nor `--yes` was given.

It applies to every command that connects to a JVM — read-only ones like `list loggers` and
`status` as much as `set` or `add rule`. `--help` and `--version` never connect, so never ask.

Otherwise the outcome is today's: exit 4 with the table (J3 adds two columns to it).

### The question (J2, J3, J4)

```
$ logctl status
2 LogAperture JVMs are running:
  #  PID    VERSION        STARTED           DIRECTORY             COMMAND
  1  41822  0.1.0-alpha.3  2026-09-26 08:14  /opt/wildfly-26       org.jboss.modules.Main -mp /opt/wildfly-26/modules …
  2  52310  0.1.0-alpha.3  2026-09-26 13:02  /home/dd/src/acme     com.acme.App
Which? (a number from the list, or a PID; Enter to cancel)
> 1
Using PID 41822 — pass --pid 41822 to skip this question.

No active overrides.
```

- The list and the question go to **stderr**, like the existing "several candidates" table
  and every other discovery message. Stdout carries only the command's own output, so
  `export vendor-defaults` and anything else that writes a document to stdout stay clean.
- **STARTED** is the process start time (local time, minutes). **DIRECTORY** is the JVM's
  working directory (`user.dir`), shortened with `~` for the home directory. Both come from
  what discovery already reads (the process handle and the system properties it attaches to
  check the marker), so nothing extra is asked of the JVM. COMMAND is truncated with `…` to
  fit an 120-column line; the full command is still in the non-interactive table.
- **Stderr redirected on a terminal** (`logctl status 2>/dev/null`): the terminal check looks
  only at stdin and stdout, so the question is still asked — but the list and question are
  hidden, and `logctl` waits for an answer with nothing on screen. Accepted and documented
  rather than detected: it's rare, Enter still cancels with exit 4, and Java has no portable
  way to ask whether stderr is a terminal. Pass `--pid` when redirecting stderr.
- Rows are in PID order, as today.
- The answer is a list number or one of the listed PIDs. Anything else is explained and asked
  again: `'3' isn't in the list -- answer 1 to 2, or one of the PIDs shown.`

### After the answer (J5, J6)

- A chosen JVM prints one line to stderr — `Using PID 41822 — pass --pid 41822 to skip this
  question.` — then the command runs against it exactly as if `--pid 41822` had been given.
- Enter or end of input (Ctrl-D) cancels: `No JVM chosen.` and **exit 4**, the same exit
  code as not being able to choose, since the command did not run.
- Nothing is remembered: the next command asks again (J7).

### With guided `add rule` (J9)

The JVM question comes first, then the command's own questions, all read from the same
input. `logctl add rule '*.Deployer'` with two JVMs running asks which JVM, then shows that
JVM's matching loggers.

## Terminal detection (J8)

`logctl` decides "a terminal is attached" with `System.console() != null` (`set logger`'s
confirmation and guided `add rule` both use it). JDK 22 changed that: its default console
became the JLine-based one, which `System.console()` returns even when stdin or stdout is
redirected, and it added `Console.isTerminal()` to say whether it really is a terminal. JDK 25
went back to the old default. `logctl` is built for release 17 and runs on whatever JDK the
operator has, so on JDK 22–24 a piped or scripted run can be treated as interactive:
`echo y | logctl set logger '*.X' INFO` reads the `y`, and a scripted incomplete `add rule`
asks questions and gets end of input instead of a usage error.

*Checked here:* on JDK 21 and JDK 25, piped stdin gives no console, as expected. JDK 22–24
aren't installed on this machine, so their behavior is from the JDK release notes, not
reproduced; the fix is harmless on every version either way.

The check becomes: a console exists **and**, where `Console.isTerminal()` exists (JDK 22+),
it returns `true`. `isTerminal` is called reflectively, since the build targets 17. This is
decided once in `Main`, as today, and every prompt — this one, `set logger`'s, guided
`add rule`'s — follows it.

## Out of scope

- **Remembering the choice** between commands (an environment variable such as
  `LOGCTL_PID`, or a per-shell default). Worth considering once this has been used; the
  printed `--pid` covers scripts and repeats for now.
- **Choosing by name** (`--pid wildfly`) or by directory.
- **Any change to the zero-candidate or `--pid` paths.**

## Implementation notes

- `Discovery.resolveTargetPid` splits into finding candidates (the attach loop, unchanged)
  and choosing one from a list, which is pure and takes the prompter, the stderr stream and
  whether it may ask. Tests drive the choosing half with a candidate list; the attach loop
  stays as it is.
- `Candidate` gains `startedAt` (`ProcessHandle.of(pid).info().startInstant()`, may be
  absent: shown as `?`) and `directory` (`user.dir` from the same `getSystemProperties()`
  call that reads the marker).
- `Connector` gains `connect(Long explicitPid, Prompter jvmQuestion)`, where a `null` prompter
  means "may not ask"; the existing one-argument form stays, as the never-ask case, so test
  connectors written as lambdas keep working. `Main` passes a prompter writing to stderr only
  when J1's conditions hold (`Invocation` carries whether `--json` or `--yes` was given).
- `Prompter` reads one line at a time straight from the input stream, with no read-ahead
  buffer, so discovery's question and the command's own questions (J9) can each use their own
  `Prompter` over the same stdin without one swallowing the other's input.
- The terminal check (J8) lives in one helper in `Main`.

## Testing

- The choosing half, with fake candidates: a number, a PID, an invalid answer re-asked,
  Enter and EOF each exit 4 with `No JVM chosen.`, the `Using PID …` line.
- Not asking: one candidate, `--pid`, no terminal, `--json`, `--yes` — each gives today's
  behavior, and the non-interactive table has the two new columns.
- Through `Main.run` with a fake connector: the JVM question followed by guided `add rule`'s
  questions from one input stream.
- J8: the terminal helper, with and without `isTerminal` present (a stand-in `Console`-like
  object for the reflective call).
- `CliEndToEndIT` already launches two fixtures for its `--pid` test: extend it to check the
  new columns in the non-interactive table (the IT has no terminal).

## Decisions

| # | Decision | Status |
|---|---|---|
| J1 | Ask only with several candidates, no `--pid`, a terminal, and no `--json`/`--yes`; every connecting command | **Agreed** |
| J2 | The list and question go to stderr, not stdout | **Agreed** |
| J3 | Add STARTED and DIRECTORY columns, to the question and to the non-interactive table | **Agreed** |
| J4 | Answer with a list number or a listed PID; invalid answers asked again | **Agreed** |
| J5 | Enter / EOF cancels with `No JVM chosen.` and exit 4 | **Agreed** |
| J6 | After choosing, print `Using PID n — pass --pid n to skip this question.` to stderr | **Agreed** |
| J7 | Nothing remembered between commands; an env var is a possible follow-up | **Agreed** |
| J8 | Fix terminal detection for JDK 22–24 (`Console.isTerminal()` where it exists, reflectively) as part of this | **Agreed** |
| J9 | The JVM question comes before a command's own questions, read from the same input | **Agreed** |
