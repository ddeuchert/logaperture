# Test-driving LogAperture on WildFly

A hands-on walkthrough of the current feature set against a real, running
WildFly server — attach the agent, turn tracing on and off, feel the
difference between `for`, `session`, and `sticky`, and look at `top` and
`doctor`. It builds on [`INSTALL-wildfly.md`](../logaperture-dist/src/dist/INSTALL-wildfly.md)
(the bare install steps) and complements
[`doc/specs/wildfly-dev-environment.md`](specs/wildfly-dev-environment.md) (a
committed Docker environment for repo development) — this doc instead targets
*any* standalone WildFly you already have, 26.x or newer, no sample app or
Docker required.

Budget 15–20 minutes. Every step is read-only or self-reverting; nothing here
edits `standalone.xml` or any file WildFly owns, and step 11 leaves the server
exactly as it started.

**Prerequisites:** a standalone WildFly (26.x+) you can restart and don't mind
experimenting on, JDK 17+ on that host, and the LogAperture distribution
unzipped there (or built locally — see [DEVELOPMENT.md](../DEVELOPMENT.md)),
with its `bin/` directory on your `PATH` (e.g.
`export PATH="$PATH:/opt/logaperture/bin"`) — every command below is a bare
`logctl`. `logctl` must run as the same OS user, on the same host, as the WildFly
process.

Every command block below is real output — this was run start to finish
against the repo's own `dev/wildfly` environment (a real WildFly 26.1.3.Final
in Docker) to check it. Expect your own version numbers, timestamps, and
logger catalogue to differ.

---

## 1. Attach the agent

Add one line to `$JBOSS_HOME/bin/standalone.conf` (Linux/macOS):

```sh
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/logaperture/lib/logaperture-agent.jar"
```

Restart WildFly. Check the boot log for a clean start — no "The LogManager
was not properly installed" — and confirm the agent is live:

```sh
logctl env
```

```
LogAperture agent    0.1.0-alpha.2  (logctl 0.1.0-alpha.2)
Java                 17.0.5  Eclipse Adoptium
OS                   Linux 6.8.0  amd64
Logging backend      JBoss LogManager 2.1.18.Final
Framework/container  WildFly 26.1.3.Final
Diagnostics level    —
State file           /home/jboss/.logaperture/wildfly.state.yaml
```

`env` is the pasteable block for a bug report; you'll come back to it if
anything below looks wrong. If `Framework/container` doesn't say WildFly, the
agent didn't detect the container — stop here and check the boot log instead
of continuing.

## 2. See the baseline

```sh
logctl status                         # nothing yet — no overrides
logctl list loggers org.jboss --show-all
```

```
No active overrides.

LOGGER                     CONFIGURED  EFFECTIVE  OVERRIDE
org.jboss                  —           INFO       —
org.jboss.as               —           INFO       —
org.jboss.as.clustering    —           INFO       —
org.jboss.as.config        DEBUG       DEBUG      —
...
```

This is the read side working end to end before you change anything —
WildFly's own logger catalogue, its effective levels, and a `CONFIGURED`
column showing the handful WildFly itself preconfigures (like
`org.jboss.as.config`).

## 3. Turn on trace logging for a well-known noisy category

`io.undertow.request` is Undertow's per-request tracing — it exists on any
standalone WildFly whether or not you've deployed anything, since even a 404
still gets routed:

```sh
logctl set logger io.undertow.request DEBUG for 5m
```

```
io.undertow.request → DEBUG   (FOR, reverts 14:32:10 local — in 5m)
WARN: handler CONSOLE is at INFO and will drop DEBUG records from this logger.
      To see them: logctl set handler CONSOLE DEBUG
```

`for 5m` is a timer — the override reverts on its own, no second command
required. The warning is the **handler floor**: raising a *logger* only
controls what's generated. Whether it's actually written anywhere still
depends on each *handler*'s own level — WildFly's `CONSOLE` handler is
commonly pinned at `INFO` in `standalone.xml`. The warning names the exact
handler and gives you the copy-pasteable fix, resolved to WildFly's real
configured handler name (not an opaque token) because LogAperture reads it
from the server's own management model. Leave the handler alone for now —
you'll come back to it in step 6.

## 4. Generate some activity and watch it appear

From another terminal (or a browser), hit the server — any URL works, even
one that 404s:

```sh
curl http://localhost:8080/
```

Now look at `server.log`:

```sh
tail -f $JBOSS_HOME/standalone/log/server.log
```

```
14:28:07,427 DEBUG [io.undertow.request] (default I/O-6) Matched default handler path /
```

A `DEBUG` line that wasn't there a minute ago — the level change took effect
on the running server, with no restart, no redeploy, and (so far) nothing
reaching the console yet, because of the handler floor from step 3.

## 5. Confirm it, then let it expire

```sh
logctl status
```

```
LOGGER               LEVEL  TIER  REVERTS           REASON
io.undertow.request  DEBUG  FOR   14:32:10 (in 4m)  —
```

Wait out the timer (or jump to step 10 and reset it manually) — either way,
`io.undertow.request` reverts to `INFO` on its own, and the extra lines stop
without you touching anything.

## 6. Lower the handler, or let it follow automatically

Take the warning's suggested fix directly:

```sh
logctl set handler CONSOLE DEBUG for 5m
```

```
handler CONSOLE → DEBUG   (FOR, reverts 14:32:33 local — in 5m)
```

Hit the server again (step 4's `curl`) — this time the `DEBUG` line reaches
the console too, not just `server.log`.

...or instead put `CONSOLE` into `AUTO` mode, which tracks the lowest
currently active debug/trace override on its own and reverts the moment none
are left — no manual matching required:

```sh
logctl set handler CONSOLE AUTO
logctl list handlers --show-all
```

```
HANDLER           LEVEL  PERSISTS  TARGET                                        OVERRIDE
ALL_HANDLERS      —      —         —                                             —
FILE              ALL    file      /opt/jboss/wildfly/standalone/log/server.log  —
CONSOLE           DEBUG  no        —                                             AUTO → DEBUG (FOR, reverts in 3h 59m)
DEFAULT_HANDLERS  —      —         (auto: CONSOLE)                               —
```

(`AUTO`'s own tier defaults to `for 4h`, same as any bare `set` — it's the
*level* CONSOLE tracks that's automatic, not how long the `AUTO` assignment
itself lasts.) `ALL_HANDLERS` and `DEFAULT_HANDLERS` are always-addressable
names, not real handlers — see `logctl --help` for what each means.

## 7. Feel the difference: `for` vs. `session` vs. `sticky`

Three tiers, same command:

| Tier | Reverts when |
|---|---|
| *(omitted)* | `for 4h` — a working session, gone by morning |
| `for <n>s\|m\|h\|d` | its own timer expires — what you've used so far |
| `session` | the server JVM restarts (no timer at all otherwise) |
| `sticky` | *never* on its own — survives a restart too, until you `reset` it |

Set the same logger three ways and compare `status`:

```sh
logctl set logger com.example.demo DEBUG session
logctl set logger com.example.other DEBUG sticky
logctl status
```

```
LOGGER               LEVEL  TIER     REVERTS           REASON
io.undertow.request  DEBUG  FOR      14:32:10 (in 4m)  —
com.example.demo     DEBUG  SESSION  until restart     —
com.example.other    DEBUG  STICKY   until reset       —

HANDLER  LEVEL  MODE  TIER  REVERTS               REASON
CONSOLE  DEBUG  AUTO  FOR   18:32:33 (in 3h 59m)  —
```

`sticky` is the one worth restarting WildFly to see for real, if you have
the few minutes: restart the server, then `logctl status` again —
`com.example.other` is still there, reapplied on boot; `com.example.demo`
(session) is gone, because the JVM it was scoped to no longer exists.

## 8. `doctor` — read-only, no overrides required

```sh
logctl doctor
```

```
[WARN]  FILE has no size cap — writes are unbounded.
        no size-based rotation is configured
        suggested: configure a size-based rotation policy on FILE.
[WARN]  FILE has autoflush enabled — every record forces a flush.
[WARN]  CONSOLE has autoflush enabled — every record forces a flush.
[OK]    no excess verbosity found at root or on a known-chatty logger.

4 checks run — 0 critical, 3 warning, 0 info, 2 clean.
```

`doctor` never changes anything; it flags common logging-configuration
problems — unbounded file handlers, verbosity left on, duplicate output,
autoflush on a busy handler, low disk headroom — with a severity and, where
there's one unambiguous answer, the exact fix. What actually fires depends
entirely on your server's own `standalone.xml`, so expect a different set of
findings than shown here.

## 9. `top` — where the bytes are going

```sh
logctl top --limit 5
```

```
org.jboss.as.server.deployment          1 MB/h  (17 MB/day)  0% stack traces
org.jboss.as.config                     0 MB/h  (2 MB/day)   0% stack traces
org.wildfly.extension.undertow          0 MB/h  (1 MB/day)   0% stack traces
...

measured over 3m (since agent start, 2026-09-20T02:02:22Z) — 43 loggers tracked.
```

Also read-only: worst-first by bytes written since the agent started, with a
projected daily total and how much of that is stack-trace bytes. This ran
right after boot, on a server with nothing deployed — deployment-scanner and
management chatter dominate; run it again after step 3–6's DEBUG activity and
watch `io.undertow.request` show up.

## 10. Clean up

```sh
logctl reset loggers --include-sticky
logctl reset handlers
logctl status
```

```
Reverted 3 override(s).
Reverted 1 handler override(s).
No active overrides.
```

`--include-sticky` is required here only because step 7 set one on purpose —
day to day, a bulk `reset loggers`/`reset handlers` leaves a sticky override
in place and reports it, rather than silently dropping something you meant to
survive a restart. `standalone.xml` was never touched by any of this — you
can confirm with `md5sum` before step 1 and after step 10 if you'd like to see
it for yourself.

## 11. Uninstall (optional)

Delete the `-javaagent:` line you added in step 1, restart, and optionally
delete `~/.logaperture/` (the override store).

---

## Where to go from here

- [`doc/specs/pattern-level-targeting.md`](specs/pattern-level-targeting.md) —
  wildcard targets (`org.jboss.as.*`), and why a trailing star behaves
  differently for `set` than for `reset`.
- [`doc/specs/handler-floor-control.md`](specs/handler-floor-control.md) — the
  full design behind steps 3/6.
- [`doc/specs/wildfly-dev-environment.md`](specs/wildfly-dev-environment.md) —
  a committed Docker environment with a sample app, if you'd rather not
  experiment against a server you care about.
- `logctl --help` — every command and option, phone-test clean (dictatable,
  no `: = ( ) /` in any synopsis line).
