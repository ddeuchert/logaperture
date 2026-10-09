# Quick start

Ten minutes on a standalone WildFly with the agent [installed](install-wildfly.md). You raise a
log level, watch it take effect, make the console show it too, compare how long changes last,
then put everything back. Nothing here edits `standalone.xml`, and the last step leaves the server
as it started.

The output below is from WildFly 26.1.3. Your versions, times and loggers will differ.

## 1. See where you start

```sh
logctl status
```

```
Storm detection: disabled

No active overrides.
```

`status` shows what LogAperture has changed. Nothing yet. Now look at some of WildFly's own
loggers:

```sh
logctl list loggers org.jboss --show-all
```

```
LOGGER                              CONFIGURED  EFFECTIVE  OVERRIDE
org.jboss                           —           INFO       —
org.jboss.as                        —           INFO       —
org.jboss.as.clustering             —           INFO       —
org.jboss.as.clustering.infinispan  —           INFO       —
org.jboss.as.config                 DEBUG       DEBUG      —
org.jboss.as.connector              —           INFO       —
...
```

`CONFIGURED` is what `standalone.xml` sets, `EFFECTIVE` is the level the logger runs at, and
`OVERRIDE` is a change made with `logctl`. Without `--show-all`, `list loggers` shows only loggers
with an override.

## 2. Raise a level for five minutes

`io.undertow.request` logs each HTTP request at `DEBUG`. It exists on every WildFly, with or
without an application deployed.

```sh
logctl set logger io.undertow.request DEBUG for 5m
```

```
io.undertow.request → DEBUG   (FOR, reverts 05:01:22 local — in 4m)
WARN: handler CONSOLE is at INFO and will drop DEBUG records from this logger.
      To see them: logctl set handler CONSOLE DEBUG
```

`for 5m` is the tier: the change reverts by itself after five minutes. Leave the warning for now;
step 4 is about it.

## 3. See it take effect

Send the server any request, even one that ends in a 404:

```sh
curl http://localhost:8080/
```

and look at the end of `server.log`:

```sh
tail $JBOSS_HOME/standalone/log/server.log
```

```
2026-10-09 04:56:29,448 DEBUG [io.undertow.request] (default I/O-9) Matched default handler path /
```

The running server logged at the new level: no restart, no redeploy. `status` shows the change and
when it reverts:

```sh
logctl status
```

```
Storm detection: disabled

LOGGER               LEVEL  TIER  REVERTS           REASON
io.undertow.request  DEBUG  FOR   05:01:22 (in 4m)  —
```

## 4. Make the console show it too

The `DEBUG` line went to `server.log` but not to the console. That's the warning from step 2: a
logger's level decides what gets logged, and each **handler** has a level of its own that decides
what it writes. WildFly's `CONSOLE` handler is usually at `INFO`.

You could lower it with the command the warning gave. Or put it in `AUTO`, where it follows the
lowest `DEBUG` or `TRACE` change you have active, and goes back to its own level when there are
none:

```sh
logctl set handler CONSOLE AUTO
```

```
handler CONSOLE → AUTO, currently DEBUG   (FOR, reverts 08:56:33 local — in 3h 59m)
```

```sh
logctl list handlers --show-all
```

```
HANDLER           LEVEL  PERSISTS  TARGET                                        OVERRIDE
ALL_HANDLERS      —      —         —                                             —
FILE              ALL    file      /opt/jboss/wildfly/standalone/log/server.log  —
CONSOLE           DEBUG  no        —                                             AUTO → DEBUG (FOR, reverts in 3h 59m)
DEFAULT_HANDLERS  —      —         (auto: CONSOLE)                               —
```

Run the `curl` again: this time the `DEBUG` line is on the console as well. `AUTO` itself lasts
`for 4h`, like any `set` without a tier.

## 5. Compare how long changes last

Every change has a tier:

| Tier | Lasts until |
|---|---|
| *(none)* | 4 hours: the same as `for 4h` |
| `for <duration>` | the time is up, for example `for 30m` |
| `session` | the server stops |
| `sticky` | you `reset` it. It survives restarts |

Make one of each kind:

```sh
logctl set logger com.example.demo DEBUG session
logctl set logger com.example.other DEBUG sticky
logctl status
```

```
Storm detection: disabled

LOGGER               LEVEL  TIER     REVERTS           REASON
io.undertow.request  DEBUG  FOR      05:01:22 (in 4m)  —
com.example.demo     DEBUG  SESSION  until restart     —
com.example.other    DEBUG  STICKY   until reset       —

HANDLER  LEVEL  MODE  TIER  REVERTS               REASON
CONSOLE  DEBUG  AUTO  FOR   08:56:33 (in 3h 59m)  —
```

If you have a minute, restart WildFly and run `logctl status` again. `com.example.other` is still
there; `com.example.demo` is gone with the JVM it belonged to.

## 6. Ask where the logging goes

Two read-only commands. `doctor` checks the logging configuration for common problems:

```sh
logctl doctor
```

```
[OK]    logaperture-agent.jar is the first -javaagent.
[WARN]  FILE has no size cap — writes are unbounded.
        no size-based rotation is configured
        suggested: configure a size-based rotation policy on FILE.
[OK]    no excess verbosity found at root or on a known-chatty logger.
[WARN]  FILE has autoflush enabled — every record forces a flush.
[WARN]  CONSOLE has autoflush enabled — every record forces a flush.
[OK]    /opt/jboss/wildfly/standalone/log/server.log is stable — no writes observed during the sample window.

5 checks run — 0 critical, 3 warning, 0 info, 3 clean.
```

`top` shows which loggers have written the most since the agent started:

```sh
logctl top --limit 5
```

```
org.logaperture.sample.work.Worker  0 MB/h  (3 MB/day)  0% stack traces
org.wildfly.extension.undertow      0 MB/h  (2 MB/day)  0% stack traces
org.jboss.as.ejb3                   0 MB/h  (1 MB/day)  0% stack traces
org.jboss.as                        0 MB/h  (1 MB/day)  0% stack traces
org.infinispan.CONTAINER            0 MB/h  (1 MB/day)  0% stack traces

measured over under a minute (since agent start, 2026-10-09T04:56:07.092712548Z) — 14 loggers tracked.
```

This server had a sample application deployed, so its worker leads. On yours, it will be whatever
is busiest.

## 7. Put everything back

```sh
logctl reset loggers
```

```
Reverted 2 override(s).
Left 1 sticky override(s) in place (pass --include-sticky to include them): com.example.other
```

A bulk reset leaves `sticky` changes alone, since you made them to last. Include them, and reset
the handler:

```sh
logctl reset loggers --include-sticky
logctl reset handlers
logctl status
```

```
Reverted 1 override(s).
Reverted 1 handler override(s).
Storm detection: disabled

No active overrides.
```

The server is back to its own configuration.

## Next

- [Raise a log level](../how-to/raise-a-level.md): patterns, `--force`, and handler levels in depth.
- [Silence noise](../how-to/silence-noise.md): `drop` and `trim` rules.
- [Configuration layers](../concepts/configuration-layers.md): what `reset` goes back to.
- `logctl help <command>`, or the [command reference](../reference/logctl.md).
