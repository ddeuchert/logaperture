# Installing LogAperture on WildFly

> ## ⚠️ PRE-PRODUCTION / EVALUATION USE ONLY
>
> Early experimental build. **Not for production.** No warranty, no support
> commitment. The agent's override-store format may change between builds. Use a
> disposable pre-production server.

LogAperture is a Java agent. You attach it to WildFly with one line in a config
file, restart once, and then use `logctl` to view and change log levels on the
running server. It **never edits `standalone.xml`** — overrides live in the
agent's own store and revert on a timer or on `logctl reset`.

## Prerequisites

- A **standalone** WildFly server. Tested against **26.1.3.Final**; other
  versions are unverified. Domain mode is not supported.
- A **JDK 17** running the server. `logctl` needs a JDK too (not just a JRE — it
  uses the JVM attach API).
- Run `logctl` **on the same host and as the same OS user** as the server
  process. The agent opens no network ports; `logctl` reaches it locally.

## Install

1. Unzip this bundle somewhere on the server, e.g. `/opt/logaperture`.

2. Add the agent to the server's JVM options — **one line**, appended:

   **Linux / macOS** — `$JBOSS_HOME/bin/standalone.conf`:
   ```sh
   JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/logaperture/lib/logaperture-agent.jar"
   ```

   **Windows** — `%JBOSS_HOME%\bin\standalone.conf.bat`:
   ```bat
   set "JAVA_OPTS=%JAVA_OPTS% -javaagent:C:\opt\logaperture\lib\logaperture-agent.jar"
   ```

3. Restart WildFly.

4. Verify — the server log shows a clean start (no "The LogManager was not
   properly installed"), and:
   ```sh
   /opt/logaperture/bin/logctl levels org.jboss
   ```
   lists the server's own loggers. `logctl status` shows no overrides yet.

## Using it

```sh
logctl levels [filter]                 # list loggers and their effective levels
logctl debug  <logger> [tier]          # raise <logger> to DEBUG   (also: trace|info|warn|error)
logctl set    <logger> <level> [tier]  # <level>: TRACE DEBUG INFO WARN ERROR OFF ALL
logctl status                          # what overrides are active
logctl reset  <logger>                 # drop one override   (or: reset --all)
```

**Tier** — how long the change lasts:

| tier | meaning |
|---|---|
| *(omitted)* | `for 4h` — a working session, gone by morning |
| `session` | until the server JVM restarts |
| `for <n>s\|m\|h\|d` | a timer, e.g. `for 30m` — reverts on its own |
| `sticky` | persists across a server restart |

Handy options: `--pid <n>` (if `logctl` can't pick the JVM automatically),
`--reason "<text>"` (kept in the audit trail), `--include-children`, `--json`.

Example:
```sh
logctl debug org.hibernate.SQL for 15m
logctl levels org.hibernate            # shows DEBUG, override active
logctl reset org.hibernate.SQL
```

### One thing to know

Raising a logger's level is necessary but not always sufficient: if a handler has
its own level floor (WildFly's `CONSOLE` handler is often pinned at `INFO` in
`standalone.xml`), `DEBUG` records still reach `server.log` but not the console.
`logctl` prints a warning when it detects this.

## Uninstall

Remove the `-javaagent:` line you added, and restart WildFly. Nothing else was
changed. The agent's override store (default: `~/.logaperture/`) can be deleted.

## Limits (this build)

- Standalone mode only — domain mode is declined with a diagnostic.
- JDK 17; WildFly 26.1.3.Final is the tested target.
- One server JVM per `logctl` invocation.
- Level control only. Log-volume / storm handling is not in this build.

## Feedback

https://github.com/ddeuchert/logaperture/issues
