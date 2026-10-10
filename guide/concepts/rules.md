# Rules

A level decides how much a logger says. A **rule** acts on what it says: it looks at each event
from a logger and either discards it (`drop`) or keeps it with a shorter stack trace (`trim`). The
logger's level doesn't change, and every other event from it is logged as before.

```sh
logctl add rule drop com.acme.batch.Worker --message-contains "This happens a lot" --below ERROR
logctl add rule trim '*.AutoUpdateHelper' --throwable java.net.ConnectException --frames 3
```

!!! info "WildFly only in 1.0"
    Rules need `java.util.logging` or JBoss LogManager. On a plain JVM with Logback they aren't
    available yet.

## What a rule matches

A rule is attached to one logger and applies to that logger's events and its descendants'
events, including descendants created later, the same way a level is inherited. Within those, it
matches an event when **all** of its matchers match:

| Matcher | Matches when |
|---|---|
| `--message-contains <text>` | the message contains the text |
| `--message-contains-ignore-case <text>` | the same, ignoring case |
| `--throwable <class>` | the event carries an exception of exactly this class |
| `--throwable-message-contains <text>` | the exception's message contains the text |
| `--any-cause` | (with the two above) any exception in the cause chain may match, not just the top one |
| `--below <level>` | the event's level is below this one. Default `ERROR`; `FATAL` means every level |

The `--below` default means a rule leaves `ERROR` events alone unless you say otherwise. A `drop`
rule needs at least one matcher besides `--below`, so it can't silence a logger by accident. A
`trim` rule doesn't: trimming every `WARN` trace from a logger is a normal thing to want.

## `drop`

A matching event is discarded before any handler writes it. So the log never goes completely
dark, a `drop` rule lets the first matching event through, and then one every 5 minutes. Change the
interval with `--sample-full 30m`, or turn it off with `--no-sample-full`.

Every dropped event is counted. `logctl list rules` shows each rule's hit count, and every
10 minutes the log gets a summary line naming the busiest rules. On WildFly:

```
2026-10-10 00:52:16,630 INFO  [org.logaperture.drop] (logaperture-wildfly-sweep) drop summary: 59 events suppressed by 1 rule in the last 10m (r1 59); 1 sampled through
```

The summary can't be turned off; its interval can be changed with
[`-Dlogaperture.drop.summaryInterval`](../reference/agent-options.md).

## `trim`

A matching event is logged, but its stack trace is cut down:

- By default (`--frames 0`) the exception prints as one line, with each `Caused by:` as its own
  line.
- `--frames 3` keeps the top three frames.
- `--collapse-causes` folds the whole cause chain into the summary line.

Every trimmed trace ends with a marker saying how much was cut, such as
`[stack trace trimmed: 26 frames omitted]`, so a reader always knows something is missing. The
application's exception object is never changed: only what is written.

If several `trim` rules match one event, the one that keeps fewest frames wins. A dropped event is
never trimmed.

In 1.0, `trim` works with text log formats. JSON and XML formatters write the full trace.

## Ids and lifetimes

Rules you add get ids `r1`, `r2`, …; rules from a vendor defaults file have `vendor:<id>`. A rule
has a tier like any other change and lasts `for 4h` unless you give one. See
[Tiers and expiry](tiers-and-expiry.md).

- `logctl list rules` lists them; `--verbose` adds each rule's options.
- `logctl alter rule r3 --below WARN` changes one in place.
- `logctl reset rule r3` removes one; `reset rules` removes all of them; `reset logger <name>`
  also removes the rules attached directly to that logger.

## Rules and other agents

A rule only sees events that go through the logging framework after LogAperture has started. An
agent listed before LogAperture on the `-javaagent` line can log before that. See
[Agent order](agent-order.md).
