# Log storms

A log storm is a burst of near-identical events from one logger: an error path stuck in a loop, a
retry that never stops. Storm detection notices them, groups them, and keeps one of the events in
full for you to read. In 1.0 it only reports: nothing is suppressed.

!!! info "WildFly only in 1.0"
    Storm detection needs `java.util.logging` or JBoss LogManager.

The output on this page is from WildFly 26.1.3.

## Turn it on

Storm detection starts off, because it costs a little on every log call. Turn it on for a while:

```sh
logctl enable storms for 30m
```

```
Storm detection is now enabled (was disabled) until 2026-10-10T03:11:21.486477057Z, then disabled. Measuring from 2026-10-10T02:41:21.486477057Z.
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
Storm detection is enabled until 2026-10-10T03:11:21.486477057Z, then disabled.

[ONGOING]  io.undertow.request  (no exception)  "Matched default handler path %s"
           started 2026-10-10T02:41:22.667701316Z (4s ago) — 1,500 events — ~21,598/min
           sample (event #1,000, when the storm was detected):
             2026-10-10T02:41:24.990134470Z DEBUG [io.undertow.request] Matched default handler path /x997

1 storm tracked — 1 ongoing, 0 ended. measured since 2026-10-10T02:41:21.497247862Z.
```

and after it, once a minute has passed quietly:

```
[ENDED]    io.undertow.request  (no exception)  "Matched default handler path %s"
           2026-10-10T02:41:22.667701316Z–2026-10-10T02:41:26.038228375Z (3s) — 1,500 events — ended 1m9s ago
```

Each storm shows its logger, its exception type if there is one, and the message with the parts
that vary replaced by `%s`, so 1,500 requests for different paths count as one storm. While it is
going on, you also see its rate and a sample: the event that made it a storm, in full, with its
message as logged and its stack trace if it has one. The sample is not the storm's first event; the
event number says how far into the storm it came (the 1,000th here, the threshold).

By default a storm is 1,000 near-identical events within 10 seconds. `--limit` shows only the worst
few.

## Then

- Find the cause with the sample, and fix it.
- Meanwhile, keep the storm out of the log with a `drop` rule matching its message, or shorten its
  traces with a `trim` rule. See [Silence noise](silence-noise.md).
- `logctl top` shows how much the storm wrote. See [Find where the disk goes](find-disk-usage.md).
