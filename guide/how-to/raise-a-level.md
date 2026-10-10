# Raise a log level

For more detail from part of an application for a while, then back to normal.

The output on this page is from WildFly 26.1.3.

## One logger

```sh
logctl set logger io.undertow.request DEBUG for 5m
```

```
io.undertow.request → DEBUG   (FOR, reverts 05:01:22 local — in 4m)
WARN: handler CONSOLE is at INFO and will drop DEBUG records from this logger.
      To see them: logctl set handler CONSOLE DEBUG
```

The level is one of `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF` or `ALL`. The tier at the
end says how long the change lasts: `for 30m`, `session` (until the JVM stops), or `sticky` (until
you reset it). Without a tier, `for 4h`. See [Tiers and expiry](../concepts/tiers-and-expiry.md).

Add `--reason` to say why; it shows in `status` and goes in the audit trail:

```sh
logctl set logger io.undertow.request DEBUG for 30m --reason "INC-4211 slow requests"
```

If you don't know the logger's full name, see [Find a logger](find-a-logger.md).

## A logger and everything under it

Setting a logger sets every logger under it too, now and later, because they inherit its level:

```sh
logctl set logger org.jboss.as TRACE for 5m
```

The exception is a logger under it that has a level of its own in the application's configuration.
It keeps that level, and `logctl` says so:

```
org.jboss.as → TRACE   (FOR, reverts 01:31:58 local — in 4m)
NOTE: 1 logger under org.jboss.as keeps its own level and won't follow TRACE: org.jboss.as.config (DEBUG).
  To set it too: logctl set logger org.jboss.as TRACE for 5m --force
```

`--force` sets those too:

```
org.jboss.as → TRACE   (FOR, reverts 01:31:58 local — in 4m)
org.jboss.as.config → TRACE   (FOR, reverts 01:31:58 local — in 4m, forced)
```

and resetting the parent puts them back with it:

```sh
logctl reset logger org.jboss.as
```

```
org.jboss.as → INFO (baseline)
org.jboss.as.config → DEBUG (baseline)   (forced by org.jboss.as)
```

A pattern ending in `*` (`'org.jboss.as.*'`) is refused for `set`: name the parent instead.

## Every logger matching a name

A log line often shows only the last part of a logger's name. A pattern starting with `*` sets every
logger whose name ends that way:

```sh
logctl set logger '*.Worker' DEBUG for 10m
```

On a terminal, `logctl` lists the matching loggers to pick from. In a script, add `--yes` to take
all of them:

```sh
logctl set logger '*.Worker' DEBUG for 10m --yes
```

```
org.logaperture.sample.work.Worker → DEBUG   (FOR, reverts 01:36:56 local — in 9m)
```

The pattern is applied once, to the loggers that exist now. A matching logger created later isn't
changed. Quote the pattern, so the shell doesn't expand the `*`.

## When raising a logger shows nothing

The warning after `set logger` is the usual reason:

```
WARN: handler CONSOLE is at INFO and will drop DEBUG records from this logger.
      To see them: logctl set handler CONSOLE DEBUG
```

A logger's level decides what is logged. Each **handler** (the console, a file) has a level of its
own that decides what it writes. WildFly's console handler is usually at `INFO`, so `DEBUG` lines go
to `server.log` but not to the console.

Either lower the handler for a while, as the warning says:

```sh
logctl set handler CONSOLE DEBUG for 30m
```

or put it in `AUTO`, where it follows the lowest `DEBUG` or `TRACE` change you have active and goes
back to its own level when there are none:

```sh
logctl set handler CONSOLE AUTO
```

`logctl list handlers --show-all` lists the handlers. `ALL_HANDLERS` means all of them at once.

## Let `logctl` ask

On a terminal, leave out what you don't know. `logctl set` alone asks what to set, which logger
(starting from part of its name, such as `Worker`), the level and the tier, then shows the full
command before applying it, so you learn the one-line form for next time.

## Put it back

A timed change reverts by itself. To undo it sooner, `logctl reset logger io.undertow.request`.
See [Undo changes](undo-changes.md).
