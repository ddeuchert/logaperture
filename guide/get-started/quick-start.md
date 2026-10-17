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
io.undertow.request → DEBUG   (FOR, reverts 05:45:49 local — in 4m)
handler CONSOLE → AUTO, currently DEBUG   (follows io.undertow.request)
```

`for 5m` is the tier: the change reverts by itself after five minutes. The second line is about the
console; step 4 explains it.

## 3. See it take effect

Send the server any request, even one that ends in a 404:

```sh
curl http://localhost:8080/
```

and look at the end of `server.log`, or at the console:

```sh
tail $JBOSS_HOME/standalone/log/server.log
```

```
2026-10-17 05:40:49,860 DEBUG [io.undertow.request] (default I/O-15) Matched default handler path /
```

The running server logged at the new level: no restart, no redeploy. `status` shows the change and
when it reverts:

```sh
logctl status
```

```
Storm detection: disabled

LOGGER               LEVEL  TIER  REVERTS           REASON
io.undertow.request  DEBUG  FOR   05:45:49 (in 4m)  —

HANDLER  LEVEL  MODE  TIER  REVERTS          REASON
CONSOLE  DEBUG  AUTO  FOR   with the logger  follows io.undertow.request
```

## 4. Why the console followed

A logger's level decides what gets logged, and each **handler** has a level of its own that decides
what it writes. WildFly's `CONSOLE` handler is at `INFO`, so on its own it would drop the `DEBUG`
line: it would reach `server.log` but not the console.

The console is the **default handler**, so it follows the change instead. It goes to `AUTO`, where
it tracks the most verbose change you have active, for as long as the change lasts, and then goes
back to `INFO` with it:

```sh
logctl list handlers --show-all
```

```
HANDLER           LEVEL  PERSISTS  TARGET                                        OVERRIDE
ALL_HANDLERS      —      —         —                                             —
FILE              ALL    file      /opt/jboss/wildfly/standalone/log/server.log  —
CONSOLE           DEBUG  no        —                                             AUTO → DEBUG (follows io.undertow.request)
DEFAULT_HANDLERS  —      —         (auto: CONSOLE)                               —
```

To change only the logger and leave the console as it is, add `--keep-handlers`. `set logger` then
warns that the console will drop the records and gives the command that would lower it.

The same, on a busier server: an application's payment logger raised to `TRACE`.

<div class="la-cast" data-cast="why-no-trace"></div>

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
io.undertow.request  DEBUG  FOR      05:45:49 (in 4m)  —
com.example.demo     DEBUG  SESSION  until restart     —
com.example.other    DEBUG  STICKY   until reset       —

HANDLER  LEVEL  MODE  TIER    REVERTS           REASON
CONSOLE  DEBUG  AUTO  STICKY  with the loggers  follows com.example.demo, com.example.other, io.undertow.request
```

The console follows all three changes, so it lasts as long as the longest of them.

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
Storm detection is disabled — see 'logctl enable storms'.
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

A bulk reset leaves `sticky` changes alone, since you made them to last. The console still follows
the one left:

```sh
logctl status
```

```
Storm detection: disabled

LOGGER             LEVEL  TIER    REVERTS      REASON
com.example.other  DEBUG  STICKY  until reset  —

HANDLER  LEVEL  MODE  TIER    REVERTS          REASON
CONSOLE  DEBUG  AUTO  STICKY  with the logger  follows com.example.other
```

Include it, and the console goes back with it:

```sh
logctl reset loggers --include-sticky
logctl status
```

```
Reverted 1 override(s).
Storm detection: disabled

No active overrides.
```

The server is back to its own configuration.

## Next

- [Raise a log level](../how-to/raise-a-level.md): patterns, `--force`, and handler levels in depth.
- [Silence noise](../how-to/silence-noise.md): `drop` and `trim` rules.
- [Configuration layers](../concepts/configuration-layers.md): what `reset` goes back to.
- `logctl help <command>`, or the [command reference](../reference/logctl.md).
