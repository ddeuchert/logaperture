# Silence noise

For a message you know is noise: one that repeats, or a stack trace you've seen a hundred times.
Lowering the logger's level would hide everything else it says too. A **rule** removes just that
message, or keeps it and shortens its stack trace. See [Rules](../concepts/rules.md) for how rules
work.

!!! info "WildFly only in 1.0"
    Rules need `java.util.logging` or JBoss LogManager. On a plain JVM with Logback they aren't
    available yet.

The output on this page is from WildFly 26.1.3.

## Drop a message

Name the logger and something in the message:

```sh
logctl add rule drop io.undertow.request --message-contains "Matched default" --below WARN --reason "health checks"
```

```
r2   io.undertow.request → drop   (FOR, reverts 05:27:09 local — in 3h 59m)
```

`--below WARN` limits the rule to events below `WARN`. Without it, the rule covers everything below
`ERROR`. A `drop` rule needs something to match besides the level; to quiet a whole logger, use
`set logger` with a higher level instead.

The rule lets the first matching event through, then one every 5 minutes, so the full message is
never far away in the log. Every dropped event is counted.

## Shorten a stack trace

To keep an event but not its trace:

```sh
logctl add rule trim '*.Worker' --throwable java.io.IOException --frames 3 --yes
```

```
r3   org.logaperture.sample.work.Worker → trim   (FOR, reverts 05:27:09 local — in 3h 59m)
```

Each matching `IOException` is still logged, with its top three frames and a marker saying how many
were cut. Without `--frames`, the exception is one line. A `trim` rule needs no matcher: `logctl add
rule trim com.acme.Worker --below WARN` shortens every trace from that logger below `WARN`.

The `'*.Worker'` pattern adds one rule to every logger now matching it. On a terminal, `logctl` lists
them to pick from; `--yes` takes all of them.

## Matching on the exception

| Option | Matches |
|---|---|
| `--message-contains <text>` | the log message contains the text |
| `--message-contains-ignore-case <text>` | the same, ignoring case |
| `--throwable <class>` | the exception is exactly this class |
| `--throwable-message-contains <text>` | the exception's message contains the text |
| `--any-cause` | let the two above match any exception in the cause chain |

Give several and all must match.

## See and change rules

```sh
logctl list rules --verbose
```

```
ID  LOGGER                              EXPRESSION                                                          ACTION  TIER  EXPIRES               HITS
r2  io.undertow.request                 --message-contains "Matched default" --below WARN --sample-full 5m  drop    FOR   07:19:12 (in 3h 59m)  0
r3  org.logaperture.sample.work.Worker  --throwable java.io.IOException --below ERROR --frames 3            trim    FOR   07:19:13 (in 3h 59m)  0
```

`HITS` counts the events each rule has acted on. `EXPRESSION` is the rule's options, in the form
`alter rule` takes. To change a rule without removing it, give only what changes:

```sh
logctl alter rule r2 --below ERROR
logctl alter rule r2 sticky
```

To remove one:

```sh
logctl reset rule r2
```

```
rule r2 → removed.
```

`reset rules` removes all of them, except `sticky` ones unless you add `--include-sticky`.

## Let `logctl` ask

On a terminal, `logctl add rule` alone walks through every part: which logger (starting from part of
its name, such as `Deployer`), drop or trim, what to match, and how long. It shows the full command
before adding the rule. `logctl alter rule r2` alone shows the rule's parts to pick what to change.
