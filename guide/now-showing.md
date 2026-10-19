# Now Showing

*Short films of LogAperture fixing real problems, recorded with [asciinema](https://asciinema.org).*

Each film runs against a real WildFly server with a small demo web shop deployed, and takes less
than 90 seconds. The server's console is on top, and `logctl` runs below. Pause a film to copy a
command straight out of it. After each film, every command in it is taken apart, with links to
its reference and to the how-to that covers it.

**The programme**

1. [Why don't I see my TRACE?](#why-no-trace): a logger is raised, and nothing appears.
2. [Debug one package for a while](#debug-for-a-while): a change that cleans itself up.
3. [Something is flooding the log](#flood): find the source, and silence one exception.
4. [Stack traces are burying the log](#trim): keep every warning, but not its trace.

## Why don't I see my TRACE? {#why-no-trace}

A customer reports failed payments, so you turn on `TRACE` for the payment code, and the console
shows nothing new. The logger is now at `TRACE`, but the console's own handler is still at
`INFO`, and a handler drops anything below its own level. Reach for this whenever you raise a
logger and the output you expected doesn't appear: `logctl` tells you which handler is in the way,
and one more command lets it through.

<div class="la-cast" data-cast="why-no-trace"></div>

### Commands in this film {#why-no-trace-commands}

**`logctl set logger com.example.shop.payment TRACE for 15m`**: raise the payment code's level.

- `com.example.shop.payment`: the logger. A package name covers every logger under it, present
  and future.
- `TRACE`: the new level.
- `for 15m`: the change reverts by itself after 15 minutes. Without a duration it lasts `for 4h`.

`logctl` warns that the `CONSOLE` handler is at `INFO` and will drop the `TRACE` records.

Reference: [`set logger`](reference/logctl.md#set-logger) · How-to:
[When raising a logger shows nothing](how-to/raise-a-level.md#when-raising-a-logger-shows-nothing)

**`logctl set handler CONSOLE AUTO`**: let the console handler follow the logger.

- `CONSOLE`: the handler, by the name WildFly gives it. `logctl list handlers` lists the names.
- `AUTO`: instead of a fixed level, the handler follows the lowest `DEBUG` or `TRACE` logger
  override, and goes back to its own level once none are left.

Reference: [`set handler`](reference/logctl.md#set-handler) · How-to:
[When raising a logger shows nothing](how-to/raise-a-level.md#when-raising-a-logger-shows-nothing)

**`logctl status`**: show everything LogAperture has changed, and when each change reverts.
It changes nothing.

Reference: [`status`](reference/logctl.md#status) · How-to:
[See what is changed](how-to/undo-changes.md#see-what-is-changed)

## Debug one package for a while {#debug-for-a-while}

You need `DEBUG` from the checkout code for one investigation. Turning it on is easy; the risk is
forgetting to turn it off, and filling the disk with debug output for days. Reach for this
whenever a change should only last as long as the investigation: give it a duration, and it puts
itself back. The film uses 30 seconds so you can watch it happen; in real life it would be
`for 1h` or so.

<div class="la-cast" data-cast="debug-for-a-while"></div>

### Commands in this film {#debug-for-a-while-commands}

**`logctl set logger com.example.shop.checkout DEBUG for 30s`**: raise the checkout code's level
for a while.

- `com.example.shop.checkout`: the logger, and every logger under it.
- `DEBUG`: the new level.
- `for 30s`: the change reverts by itself after 30 seconds. Durations take `s`, `m`, `h` and `d`.

Reference: [`set logger`](reference/logctl.md#set-logger) · How-to:
[A logger and everything under it](how-to/raise-a-level.md#a-logger-and-everything-under-it)

**`logctl set handler CONSOLE AUTO`**: let the console handler follow the logger, as in the
[first film](#why-no-trace-commands). It goes back to its own level when the `DEBUG` override
ends, so nothing is left to clean up.

- `CONSOLE`: the handler.
- `AUTO`: follow the lowest `DEBUG` or `TRACE` logger override.

Reference: [`set handler`](reference/logctl.md#set-handler) · How-to:
[When raising a logger shows nothing](how-to/raise-a-level.md#when-raising-a-logger-shows-nothing)

**`logctl list loggers com.example`**: show the loggers under `com.example` that have an override,
with their levels and when each reverts.

- `com.example`: a filter, the start of the logger names to show. Add `--show-all` to see every
  logger, not just the changed ones.

Reference: [`list loggers`](reference/logctl.md#list-loggers) · How-to:
[From the start of the name](how-to/find-a-logger.md#from-the-start-of-the-name)

**`logctl status`**: after the 30 seconds, it shows nothing changed: the logger and the handler are
both back where they started.

Reference: [`status`](reference/logctl.md#status) · How-to:
[How a timed change ends](concepts/tiers-and-expiry.md#how-a-timed-change-ends)

## Something is flooding the log {#flood}

Something is writing the same failure many times a second, and the console scrolls too fast to
read. First you need to know which logger it is; then you want that one exception gone, without a
restart, and without losing everything else the component logs. Reach for this when one noisy
message drowns out the rest, or fills the disk.

<div class="la-cast" data-cast="flood"></div>

### Commands in this film {#flood-commands}

**`logctl top --limit 5`**: rank loggers by how much they have written, worst first, with each
one's rate and how much of it is stack traces. It changes nothing.

- `--limit 5`: show the five worst. The default is 10.

Reference: [`top`](reference/logctl.md#top) · How-to:
[Which loggers write the most](how-to/find-disk-usage.md#which-loggers-write-the-most)

**`logctl add rule drop com.example.shop.inventory.StockSync --throwable java.net.ConnectException --sample-full 1m`**:
discard that exception's events, and only those.

- `drop`: discard matching events. Every one is counted.
- `com.example.shop.inventory.StockSync`: the logger the rule is attached to.
- `--throwable java.net.ConnectException`: match only events carrying exactly this exception.
  Everything else `StockSync` logs is untouched.
- `--sample-full 1m`: let one full event through every minute, so the log never goes completely
  quiet about it.

The rule lasts `for 4h`, since no duration was given.

Reference: [`add rule`](reference/logctl.md#add-rule) · How-to:
[Drop a message](how-to/silence-noise.md#drop-a-message)

**`logctl list rules`**: show the rules, with their ids and when they expire. `HITS` counts the
events each rule has dropped.

Reference: [`list rules`](reference/logctl.md#list-rules) · How-to:
[See and change rules](how-to/silence-noise.md#see-and-change-rules)

## Stack traces are burying the log {#trim}

The shop's primary payment endpoint is down because of an environment problem, and every order
logs a warning with the same `ConnectException` and a 28-line stack trace. The warning matters:
you want to know each time it happens. The trace doesn't: it is the same root cause every time,
and it buries everything else on the console. Reach for this when a known failure keeps printing
a trace that tells you nothing new.

<div class="la-cast" data-cast="trim"></div>

### Commands in this film {#trim-commands}

**`logctl add rule trim com.example.shop.payment.PaymentGateway --throwable java.net.ConnectException --any-cause`**:
keep every matching warning, but print its exception without a stack trace.

- `trim`: keep the event, and shorten its stack trace. Each trimmed exception ends with a marker
  saying how many frames were cut.
- `com.example.shop.payment.PaymentGateway`: the logger the rule is attached to.
- `--throwable java.net.ConnectException`: match only events carrying this exception.
- `--any-cause`: match it anywhere in the cause chain. Here the `ConnectException` is the cause of
  the shop's own `PaymentException`, which is how client libraries usually wrap it.

Without `--frames`, each exception is printed as one line: 2 lines per warning instead of 28. The
rule lasts `for 4h`, since no duration was given.

Reference: [`add rule`](reference/logctl.md#add-rule) · How-to:
[Shorten a stack trace](how-to/silence-noise.md#shorten-a-stack-trace),
[Matching on the exception](how-to/silence-noise.md#matching-on-the-exception)

**`logctl list rules`**: show the rules, with their ids and when they expire. `HITS` counts the
events each rule has trimmed.

Reference: [`list rules`](reference/logctl.md#list-rules) · How-to:
[See and change rules](how-to/silence-noise.md#see-and-change-rules)
