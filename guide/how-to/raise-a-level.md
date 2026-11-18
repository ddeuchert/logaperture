# Raise a log level

For more detail from part of an application for a while, then back to normal.

<div class="la-cast" data-cast="debug-for-a-while"></div>

Above: an application's checkout logger at `DEBUG` for 30 seconds on WildFly, with the console in
the top pane. The change reverts on its own, and the console's `AUTO` level goes back with it.

The output on this page is from WildFly 26.1.3.

## One logger

```sh
logctl set logger io.undertow.request DEBUG for 5m
```

```
io.undertow.request → DEBUG   (FOR, reverts 05:45:49 local — in 4m)
handler CONSOLE → AUTO, currently DEBUG   (follows io.undertow.request)
```

The second line is the console following the change: see [The console follows](#the-console-follows).

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

## The console follows

A logger's level decides what is logged. Each **handler** (the console, a file) has a level of its
own that decides what it writes. WildFly's console handler is usually at `INFO`, so on its own it
would write `DEBUG` lines to `server.log` but not to the console.

The console is the default handler (`DEFAULT_HANDLERS`), so `set logger` brings it along: a default
handler that would drop the new records goes to `AUTO`, where it tracks the most verbose change you
have active. It goes back to its own level when the changes it follows have all ended: expired,
reset, or set back to a quieter level. `logctl status` shows what it follows:

```
HANDLER  LEVEL  MODE  TIER  REVERTS          REASON
CONSOLE  DEBUG  AUTO  FOR   with the logger  follows io.undertow.request
```

A handler you've set yourself is left as you set it. If your setting lets the records through for
at least as long as the logger change, nothing changes. If it ends sooner, the handler keeps your
setting until then and follows the change after that. If it blocks them, `set logger` warns about
it.

To change only the logger, add `--keep-handlers`: it keeps the handlers at their own levels instead
of letting them follow the change. To make that the rule for a whole server, start
its JVM with `-Dlogaperture.followHandlers=false` (see [agent options](../reference/agent-options.md));
`set logger` then says why the handlers didn't follow.

```sh
logctl set logger io.undertow.request DEBUG for 5m --keep-handlers
```

```
io.undertow.request → DEBUG   (FOR, reverts 05:45:58 local — in 4m)
WARN: handler CONSOLE is at INFO and will drop DEBUG records from this logger.
      To see them: logctl set handler CONSOLE DEBUG
```

The same warning names any other handler that would drop the records, such as a file handler a
vendor or the application set stricter. Lower it for a while, as the warning says:

```sh
logctl set handler CONSOLE DEBUG for 30m
```

or put it in `AUTO` for good, so it follows every change without being brought along:

```sh
logctl set handler CONSOLE AUTO sticky
```

`logctl list handlers --show-all` lists the handlers. `ALL_HANDLERS` means all of them at once, and
`DEFAULT_HANDLERS` the ones that follow `set logger`.

## Let `logctl` ask

On a terminal, leave out what you don't know. `logctl set` alone asks what to set, which logger
(starting from part of its name, such as `Worker`), the level and the tier, then shows the full
command before applying it, so you learn the one-line form for next time.

Three things make that quicker when you do it often: a list of recent loggers, paging through long
lists, and one-letter levels. The sessions below are from a small Logback application on a plain
JVM, not WildFly.

### Pick from recent loggers

Type `?` at the logger question to pick from the loggers you set or added rules to recently. Enter
still cancels.

```
$ logctl set logger
Which logger? A name or pattern, e.g. Deployer or *.deployment.* (Enter to cancel, ? for recent)
> ?
Recent:
  1  org.hibernate.SQL
  2  com.shop.web.Router
Which? (e.g. 1,3 or 1-2 or all; Enter to cancel)
> 1
Level? (i=INFO, d=DEBUG, t=TRACE, w=WARN, e=ERROR, o=OFF)
> t
How long? session, for <duration> (e.g. for 30m), or sticky [for 4h]
> for 30m
Reason (optional)
> INC-4211 slow checkout

This is the command:
  logctl set logger org.hibernate.SQL TRACE for 30m --reason "INC-4211 slow checkout"
Apply? [Y/n]
>
org.hibernate.SQL → TRACE   (FOR, reverts 10:04:39 local — in 29m)
```

The level took one letter, `t`. The letters are `i` INFO, `d` DEBUG, `t` TRACE, `w` WARN, `e` ERROR
and `o` OFF. The full name works too.

If you have no recent loggers yet, `?` shows the top-level packages that have loggers, to give you
something to type:

```
$ logctl set logger
Which logger? A name or pattern, e.g. Deployer or *.deployment.* (Enter to cancel, ? for recent)
> ?
No history yet.
4 top-level packages (from first 144 loggers): com.shop, io.netty, org.apache, org.hibernate.
Which logger? A name or pattern, e.g. Deployer or *.deployment.* (Enter to cancel, ? for recent)
>
Not applied.
```

### A long list is paged

When more than 50 loggers match, `logctl` shows the first 50, and Enter shows the next page. Pick by
number (`1,3-5`, or `all`, which means every match on every page), or type a shorter name to search
again under it. A number that isn't in the list is explained and asked again. Rows 4 to 49 and 53 to
59 are left out here:

```
$ logctl set logger
Which logger? A name or pattern, e.g. Deployer or *.deployment.* (Enter to cancel, ? for recent)
> Worker
60 loggers match '*.Worker'. Page 1 of 2:
   1  com.shop.billing.audit.Worker    (INFO, native)
   2  com.shop.billing.batch.Worker    (INFO, native)
   3  com.shop.billing.cache.Worker    (INFO, native)
   ...
  50  com.shop.payments.sync.Worker    (INFO, native)
Which? (e.g. 1,3 or 1-2 or all, Enter for more, or type to narrow)
>
Page 2 of 2:
  51  com.shop.shipping.audit.Worker  (INFO, native)
  52  com.shop.shipping.batch.Worker  (INFO, native)
   ...
  60  com.shop.shipping.sync.Worker   (INFO, native)
Which? (e.g. 1,3 or 1-2 or all, Enter to cancel, or type to narrow)
> com.shop.orders
21 loggers match 'com.shop.orders.*':
   1  com.shop.orders               (INFO, native)
   2  com.shop.orders.audit         (INFO, native)
   3  com.shop.orders.audit.Worker  (INFO, native)
   ...
  21  com.shop.orders.sync.Worker   (INFO, native)
Which? (e.g. 1,3 or 1-2 or all, Enter to cancel, or type to narrow)
> 99
99 isn't in the list -- choose from 1 to 21.
Which? (e.g. 1,3 or 1-2 or all, Enter to cancel, or type to narrow)
> 1-2
Level? (i=INFO, d=DEBUG, t=TRACE, w=WARN, e=ERROR, o=OFF)
> d
How long? session, for <duration> (e.g. for 30m), or sticky [for 4h]
> for 10m
Reason (optional)
>

These are the commands:
  logctl set logger com.shop.orders DEBUG for 10m
  logctl set logger com.shop.orders.audit DEBUG for 10m
Apply? [Y/n]
>
com.shop.orders → DEBUG   (FOR, reverts 09:44:42 local — in 9m)
com.shop.orders.audit → DEBUG   (FOR, reverts 09:44:42 local — in 9m)
```

A name you type there is searched as a prefix: `com.shop.orders` became `com.shop.orders.*`.

### Where the history is kept

Your recent loggers are kept for you, not for the JVM, in `~/.logaperture/recent-loggers` (the last
50 names, one per line; under `-Dlogaperture.home` if you set it). Delete the file to clear them.

## Put it back

A timed change reverts by itself. To undo it sooner, `logctl reset logger io.undertow.request`.
See [Undo changes](undo-changes.md).
