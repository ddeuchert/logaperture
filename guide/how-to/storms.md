# Log storms

A log storm is a burst of near-identical events from one logger: an error path stuck in a loop, a
retry that never stops. Storm detection notices them, groups them, and keeps the first one for you
to read. In 1.0 it only reports: nothing is suppressed.

!!! info "WildFly only in 1.0"
    Storm detection needs `java.util.logging` or JBoss LogManager.

The output on this page is from WildFly 26.1.3.

## Turn it on

Storm detection starts off, because it costs a little on every log call. Turn it on for a while:

```sh
logctl enable storms for 30m
```

```
Storm detection is now enabled (was disabled) until 2026-10-10T01:57:29.218035696Z, then disabled. Measuring from 2026-10-10T01:27:29.218035696Z.
```

Without a tier it stays on until the JVM stops; `sticky` keeps it on across restarts. To have it on
from startup, start the agent with `--storm-detection=on` (see
[Agent options](../reference/agent-options.md)). `logctl disable storms` turns it off.

## See the storms

```sh
logctl storms
```

During a storm:

```
Storm detection is enabled until 2026-10-10T01:57:29.218035696Z, then disabled.

[ONGOING]  io.undertow.request  (no exception)  "Matched default handler path %s"
           started 2026-10-10T01:27:29.993963682Z (6s ago) — 1,500 events — ~13,028/min
           first occurrence:
             2026-10-10T01:27:33.014192045Z DEBUG [io.undertow.request] Matched default handler path %s

1 storm tracked — 1 ongoing, 0 ended. measured since 2026-10-10T01:27:29.226069794Z.
```

and after it, once a minute has passed quietly:

```
[ENDED]    io.undertow.request  (no exception)  "Matched default handler path %s"
           2026-10-10T01:27:29.993963682Z–2026-10-10T01:27:34.316925254Z (4s) — 1,500 events — ended 1m1s ago
```

Each storm shows its logger, its exception type if there is one, and the message with the parts
that vary replaced by `%s`, so 1,500 requests for different paths count as one storm. While it is
going on, you also see its rate and its first event.

By default a storm is 1,000 near-identical events within 10 seconds. `--limit` shows only the worst
few.

## Then

- Find the cause with the first occurrence, and fix it.
- Meanwhile, keep the storm out of the log with a `drop` rule matching its message, or shorten its
  traces with a `trim` rule. See [Silence noise](silence-noise.md).
- `logctl top` shows how much the storm wrote. See [Find where the disk goes](find-disk-usage.md).
