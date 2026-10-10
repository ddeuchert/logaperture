# Tiers and expiry

Every change `logctl` makes has a **tier**, which says how long it lasts. You give it at the end of
the command:

```sh
logctl set logger com.acme DEBUG for 30m
logctl set logger com.acme DEBUG session
logctl set logger com.acme DEBUG sticky
```

| Tier | Lasts until | Survives a restart |
|---|---|---|
| `for <duration>` | the time is up | yes, and keeps counting down |
| `session` | the JVM stops | no |
| `sticky` | you `reset` it | yes |

A duration is a number and a unit: `30s`, `30m`, `4h`, `2d`.

## The default is `for 4h`

`set`, `add rule` and `apply recipe` without a tier mean `for 4h`: a working session, gone by
morning. A change you forget about doesn't outlive the day you made it.

Two commands differ:

- `enable storms` and `disable storms` without a tier last until the JVM stops (`session`): they
  switch a diagnostic on or off rather than change what is logged.
- `alter rule` without a tier keeps the rule's current lifetime.

## How a timed change ends

LogAperture checks for changes that have run out every 30 seconds, so a `for 5m` change reverts
between 5 minutes and 5 minutes 30 seconds after you made it. The revert is written to the
[audit log](../reference/audit-log.md) with `source=expiry-sweep`.

## What survives a restart

`sticky` changes, and `for` changes that haven't run out, are saved in LogAperture's **state
file** and put back when the JVM starts again. A `for` change keeps counting from where it was: one
that ran out while the JVM was down is not put back, and its end is still audited. `session`
changes are never saved.

The state file is under `~/.logaperture/instances/` of the user running the JVM.
`logctl env` shows its exact path, and [Agent options](../reference/agent-options.md) says how to
move it. It belongs to LogAperture: don't edit it.

## When the application changes a level itself

Applications and servers change their own logging configuration too: a WildFly management
command, a Logback configuration reload. When that happens to a logger or handler that has an
override, LogAperture puts the override back within 30 seconds and audits it with
`source=verification-sweep`. The override wins until it ends. Then the logger goes back to the
application's configuration as it is at that moment, including the change.

## Seeing tiers

`logctl status` shows every active change with its tier and, for `for`, when it reverts:

```
LOGGER               LEVEL  TIER     REVERTS           REASON
io.undertow.request  DEBUG  FOR      05:01:22 (in 4m)  —
com.example.demo     DEBUG  SESSION  until restart     —
com.example.other    DEBUG  STICKY   until reset       —
```

`reset` skips `sticky` changes unless you add `--include-sticky`: you made them to last, so a bulk
reset doesn't remove them by accident. See [Undo changes](../how-to/undo-changes.md).
