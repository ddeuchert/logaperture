<!--
  Generated from logaperture-cli/src/main/java/org/logaperture/cli/HelpTopics.java.
  Don't edit by hand: edit HelpTopics.java, then regenerate with
    mvn -pl logaperture-cli test -Dtest=HelpReferenceTest -Dlogaperture.help.regenerate=true
-->

# logctl

`logctl` controls the logging of a running JVM through the LogAperture agent. This page has the same text as `logctl help <command>` on the command line.

## Options for every command {#options}

`--pid <n>`
:   Target this JVM instead of finding one. Without it, `logctl` uses the one JVM running the agent, or on a terminal lists several to pick from.

`--reason <text>`
:   Why you made the change. Shown in `status` and kept in the audit trail.

`--json`
:   Machine-readable output. Nothing is asked.

`--yes`
:   Never ask. A pattern target takes every match.

`--version`
:   Print the version and exit.

`-h, --help`
:   This overview. After a command, that command's help.

## How every command behaves {#behaviour}

A tier says how long a change lasts: `session` until the JVM stops, `for <duration>` and then it reverts by itself, or `sticky` across restarts. A duration is a number and s, m, h or d, for example `for 30m`. `set` without a tier means `for 4h`: a working session, gone by morning.

On a terminal, `list`, `set`, `reset`, `add rule` and `alter rule` ask for anything left out, picking loggers, handlers and rules from numbered lists, and show the full command before applying it.

`logctl` finds the JVM on its own when exactly one is running with the agent. It works only for a JVM you could attach a debugger to: authorization is the operating system's. It needs a JDK, not just a JRE.

## Commands {#commands}

| Command | What it does |
|---|---|
| [`list loggers`](#list-loggers) | Show loggers and their levels. |
| [`list handlers`](#list-handlers) | Show handlers and their levels. |
| [`status`](#status) | Show what LogAperture has changed. |
| [`set logger`](#set-logger) | Change a logger's level. |
| [`set handler`](#set-handler) | Change a handler's own level. |
| [`default-handler`](#default-handler) | Choose which handlers `DEFAULT_HANDLERS` means. |
| [`reset`](#reset) | Undo changes to loggers, handlers and rules. |
| [`add rule`](#add-rule) | Drop or trim a logger's events. |
| [`alter rule`](#alter-rule) | Change a rule in place. |
| [`list rules`](#list-rules) | Show the drop and trim rules. |
| [`recipes`](#recipes) | Find and apply ready-made sets of logger levels. |
| [`doctor`](#doctor) | Check the logging configuration for common problems. |
| [`top`](#top) | Show which loggers write the most. |
| [`storms`](#storms) | Detect log storms, and switch detection on and off. |
| [`env`](#env) | Print facts for a bug report. |
| [`export vendor-defaults`](#export-vendor-defaults) | Write a vendor defaults file from the current settings. |

## list loggers {#list-loggers}

Show loggers and their levels.

```text
logctl list loggers [filter] [--show-all]
```

Without `--show-all`, only loggers with an active override are shown. `--show-all` shows every logger the JVM knows. With a vendor defaults file, a VENDOR column shows the level it sets.

A filter is a logger-name prefix, or a pattern with a leading and/or trailing `*` segment. `logctl list loggers '*.infinispan' --show-all` finds a logger when the log line shows only the short category name.

**Options**

`--show-all`
:   Every known logger, not just overridden ones.

**Examples**

```sh
logctl list loggers
logctl list loggers org.jboss --show-all
logctl list loggers '*.infinispan' --show-all
```

## list handlers {#list-handlers}

Show handlers and their levels.

```text
logctl list handlers [--show-all]
```

Shows every handler you can name, its level, and any active override. Without `--show-all`, only handlers with an active override are shown.

On WildFly the individual names (`CONSOLE`, `FILE`, …) appear once the server is up. Before that, and always, `ALL_HANDLERS` means every handler at once.

**Options**

`--show-all`
:   Every known handler, not just overridden ones.

**Examples**

```sh
logctl list handlers --show-all
```

## status {#status}

Show what LogAperture has changed.

```text
logctl status
```

Shows the active overrides, plus a line naming the vendor defaults file when there is one. Read-only.

**Examples**

```sh
logctl status
logctl status --json
```

## set logger {#set-logger}

Change a logger's level.

```text
logctl set logger <target> <level> [session | for <duration> | sticky] [--force]
```

Changes the level until the tier says otherwise: `for 4h` unless you give one. Nothing in the application's own configuration files is touched.

A target is an exact logger name, or a leading-`*` pattern like `'*.Worker'`. A pattern is a one-time selection of every logger matching it now: on a terminal it lists them to pick from, and `--yes` takes all of them. A logger created later that would also match is not touched.

A trailing-`*` target (`'org.apache.*'`) is refused. Every descendant already inherits a set ancestor's level from the logging framework, so `logctl set logger org.apache DEBUG` covers `org.apache` and everything under it, present and future. The exception is a descendant with a level of its own, which keeps it: add `--force` to set those too. `reset logger org.apache` puts them back with it.

If a handler is set stricter than the new level, the extra output still won't appear. `set logger` then names the handler and the command that lowers it: see `logctl help set handler`.

**Options**

`--force`
:   Also set the loggers under the target that have a level of their own.

`--yes`
:   For a pattern target: take every match without asking.

**Examples**

```sh
logctl set logger com.example.demo DEBUG for 30m
logctl set logger '*.Worker' TRACE session --yes
logctl set logger com.acme TRACE --force
logctl set logger com.example.other DEBUG sticky --reason INC-42
```

## set handler {#set-handler}

Change a handler's own level.

```text
logctl set handler <name> <level> [session | for <duration> | sticky]
logctl set handler <name> AUTO [session | for <duration> | sticky]
```

Sets a handler's own level directly. It is the fix when raising a logger still shows nothing because a handler is set stricter. `reset handler <name>` reverts it.

`logctl list handlers` lists the names you can use. `ALL_HANDLERS` means every handler at once.

`AUTO` puts a handler into a self-adjusting mode instead of a fixed level. It follows the lowest active `DEBUG` or `TRACE` logger override by itself, and goes back to its native level the moment none are left. Setting a fixed level, or resetting it, takes it out of `AUTO`.

**Examples**

```sh
logctl set handler CONSOLE DEBUG for 30m
logctl set handler ALL_HANDLERS TRACE
logctl set handler CONSOLE AUTO
```

## default-handler {#default-handler}

Choose which handlers `DEFAULT_HANDLERS` means.

```text
logctl set default-handler <name> ...
logctl reset default-handler [--to-native]
```

`DEFAULT_HANDLERS` is a handler name usable anywhere `ALL_HANDLERS` is, but it means a chosen set of handlers rather than every one. Until it is set, a fixed rule picks the one obvious handler, usually the console.

`set default-handler FILE CONSOLE` sets an explicit membership, kept across restarts like a sticky change. `reset default-handler` clears it, going back to the rule.

**Options**

`--to-native`
:   Go back to the application's own logging configuration, ignoring the vendor defaults file until restart. A plain `reset` undoes it.

**Examples**

```sh
logctl set default-handler FILE CONSOLE
logctl reset default-handler
```

## reset {#reset}

Undo changes to loggers, handlers and rules.

```text
logctl reset logger <target> [--include-sticky] [--to-native]
logctl reset loggers [--include-sticky] [--to-native]
logctl reset handler <name> [--include-sticky] [--to-native]
logctl reset handlers [--include-sticky] [--to-native]
logctl reset rule <id> [--include-sticky] [--to-native]
logctl reset rules [--include-sticky] [--to-native]
```

`reset logger <target>` reverts whatever is overridden under that target, which may be an exact name or a pattern with either wildcard shape. `reset loggers` and `reset handlers` revert every overridden logger, or handler, at once. `reset rule <id>` removes one rule; `reset rules` removes them all.

Every form skips a `sticky` change unless you add `--include-sticky`. Naming one sticky target by its exact name is refused outright rather than doing nothing; a pattern or a bulk reset leaves it in place and says so.

A reset goes back to the baseline: the vendor defaults file, when the agent was started with one, otherwise the application's own logging configuration. `--to-native` goes back to the application's own configuration even when there is a vendor defaults file, until restart.

Rules from the vendor defaults file have `vendor:` ids. `reset rule` puts the vendor's definition back after an `alter rule`, and `reset rule ... --to-native` switches it off until the application restarts.

**Options**

`--include-sticky`
:   Also revert a `sticky` change. Without it, a sticky change is skipped.

`--to-native`
:   Go back to the application's own logging configuration, ignoring the vendor defaults file until restart. A plain `reset` undoes it.

**Examples**

```sh
logctl reset logger com.example.demo
logctl reset loggers
logctl reset loggers --include-sticky
logctl reset handler CONSOLE
logctl reset rule r3
logctl reset logger com.acme --to-native
```

## add rule {#add-rule}

Drop or trim a logger's events.

```text
logctl add rule drop <target> [matchers] [--below level] [--sample-full duration | --no-sample-full] [session | for <duration> | sticky] [--yes]
logctl add rule trim <target> [matchers] [--below level] [--frames n] [--collapse-causes] [session | for <duration> | sticky] [--yes]
```

Attaches a rule that acts on a logger's events before they are written, instead of changing its level. `drop` discards a matching event; `trim` keeps it but shortens its stack trace. The rule also covers the logger's descendants.

The matchers say which events: a message substring, an exception type, an exception message. `--below` bounds the levels the rule acts on. `drop` needs at least one matcher; `trim` doesn't, since a level-bounded trim is a normal case.

The target takes the same shapes as for `set logger`. Rules last `for 4h` unless you give a tier. `logctl list rules` shows them with their ids; `alter rule` changes one and `reset rule` removes one.

On a terminal, `logctl add rule` alone walks through every part, starting from a logger name such as `Deployer`.

**Options**

`--message-contains <text>`
:   Match events whose message contains the text.

`--message-contains-ignore-case <text>`
:   The same, ignoring case.

`--throwable <class>`
:   Match events carrying an exception of exactly this type.

`--throwable-message-contains <text>`
:   Match events whose exception message contains the text.

`--any-cause`
:   Let the exception matchers match any cause in the chain, not just the top exception.

`--below <level>`
:   Only act on events strictly below this level. Default `ERROR`.

`--sample-full <duration>`
:   For `drop`: let one full event through every so often, so the log never goes completely dark.

`--no-sample-full`
:   For `drop`: never let a full event through.

`--frames <n>`
:   For `trim`: stack frames to keep. Default 0.

`--collapse-causes`
:   For `trim`: fold the cause chain into one summary line.

`--yes`
:   For a pattern target: add the rule to every logger it matches without asking.

**Examples**

```sh
logctl add rule drop com.acme.batch.Worker --message-contains "This happens a lot" --below ERROR
logctl add rule trim com.acme.batch.Worker --below WARN
logctl add rule trim '*.AutoUpdateHelper' --throwable java.net.ConnectException --frames 3 sticky
```

## alter rule {#alter-rule}

Change a rule in place.

```text
logctl alter rule <id> [changes] [session | for <duration> | sticky]
```

Changes a rule and keeps its id. Give only what changes: `alter rule r3 --below WARN`. The `--no-` options remove an optional part.

The rule keeps its lifetime unless you give a tier (`alter rule r3 sticky`). The action and the logger can't change. `list rules --verbose` shows each rule's current options.

A vendor rule (a `vendor:` id) can be altered too; it then lasts `for 4h` unless you give a tier, and `reset rule` puts the vendor's definition back.

On a terminal, `logctl alter rule r3` alone shows the rule's parts to pick what to change.

**Options**

`--message-contains <text>`
:   Match events whose message contains the text.

`--message-contains-ignore-case <text>`
:   The same, ignoring case.

`--throwable <class>`
:   Match events carrying an exception of exactly this type.

`--throwable-message-contains <text>`
:   Match events whose exception message contains the text.

`--any-cause`
:   Let the exception matchers match any cause in the chain, not just the top exception.

`--below <level>`
:   Only act on events strictly below this level. Default `ERROR`.

`--sample-full <duration>`
:   For `drop`: let one full event through every so often, so the log never goes completely dark.

`--no-sample-full`
:   For `drop`: never let a full event through.

`--frames <n>`
:   For `trim`: stack frames to keep. Default 0.

`--collapse-causes`
:   For `trim`: fold the cause chain into one summary line.

`--no-message-contains`
:   Remove the message matcher.

`--no-throwable`
:   Remove the exception type matcher.

`--no-throwable-message-contains`
:   Remove the exception message matcher.

`--no-any-cause`
:   Match the top exception only.

`--no-collapse-causes`
:   Show the cause chain again.

**Examples**

```sh
logctl alter rule r3 --below WARN
logctl alter rule r3 --message-contains green
logctl alter rule r7 --no-throwable --throwable-message-contains "timed out"
logctl alter rule r3 sticky
```

## list rules {#list-rules}

Show the drop and trim rules.

```text
logctl list rules [--verbose]
```

Lists every attached rule and its id. Rules from the vendor defaults file have `vendor:` ids.

**Options**

`--verbose`
:   Add each rule's defining options, in an EXPRESSION column.

**Examples**

```sh
logctl list rules
logctl list rules --verbose
```

## recipes {#recipes}

Find and apply ready-made sets of logger levels.

```text
logctl list recipes [--verbose]
logctl show recipe <id> [--from <source>]
logctl apply recipe <id> [session | for <duration> | sticky] [--from <source>] [--yes]
logctl reset recipe <id> [--include-sticky]
```

A recipe is a named, documented set of logger levels for watching one thing. It comes from a library (`META-INF/logaperture/recipes.yaml` on its class path), from the vendor defaults file, or from a file in the recipes folder (`~/.logaperture/recipes`, or `-Dlogaperture.recipes=<dir>`).

`list recipes` shows what's on offer; a library's recipes appear once it has loaded. `show recipe` prints what one would change.

`apply recipe` shows the changes, asks, then makes them, `for 4h` unless you give a tier. Each change remembers the recipe. `reset recipe` puts back the ones you haven't changed by hand since. A library's recipe only ever raises levels.

**Options**

`--verbose`
:   For `list recipes`: full source paths, shadowed recipes and file errors.

`--from <source>`
:   Pick among recipes that share an id.

`--yes`
:   For `apply recipe`: apply without asking.

`--include-sticky`
:   Also revert a `sticky` change. Without it, a sticky change is skipped.

**Examples**

```sh
logctl list recipes
logctl show recipe io.undertow:sessions
logctl apply recipe io.undertow:sessions for 30m
logctl reset recipe io.undertow:sessions
```

## doctor {#doctor}

Check the logging configuration for common problems.

```text
logctl doctor
```

Flags common misconfigurations: unbounded file handlers, `DEBUG` or `TRACE` left on, the same output written twice, autoflush on a busy handler, low disk headroom. Each comes with a severity and, where there is an unambiguous one, the exact fix. Read-only: it never changes anything.

**Examples**

```sh
logctl doctor
```

## top {#top}

Show which loggers write the most.

```text
logctl top [--limit n]
```

Shows which loggers have written the most bytes since the agent started, worst first, with a rate, a projected daily total, and what share of that volume is stack traces. Read-only.

**Options**

`--limit <n>`
:   Show the worst n loggers; 0 for every one tracked. Default 10.

**Examples**

```sh
logctl top
logctl top --limit 5
```

## storms {#storms}

Detect log storms, and switch detection on and off.

```text
logctl storms [--limit n]
logctl enable storms [session | for <duration> | sticky]
logctl disable storms [session | for <duration> | sticky]
```

A log storm is a burst of near-identical events from one logger. `storms` lists the ones storm detection has seen, with the first occurrence kept in full. It only reports: nothing is suppressed.

Storm detection starts disabled. `enable storms` turns it on and `disable storms` turns it off, in every context. With no tier that lasts until the JVM stops, not `for 4h` as for `set`. `for 30m` switches it back after 30 minutes, and `sticky` keeps it across restarts.

To have it on from the start, start the agent with `-javaagent:logaperture-agent.jar=--storm-detection=on`.

**Options**

`--limit <n>`
:   Show the worst n storms. Default: every one.

**Examples**

```sh
logctl enable storms for 30m
logctl storms
logctl disable storms
```

## env {#env}

Print facts for a bug report.

```text
logctl env
```

Prints one block to paste into a bug report: agent and `logctl` versions, Java, the operating system, the detected logging backend, and the detected application framework or container. Read-only.

**Examples**

```sh
logctl env
```

## export vendor-defaults {#export-vendor-defaults}

Write a vendor defaults file from the current settings.

```text
logctl export vendor-defaults [--out <file>] [--force]
```

Writes the next vendor defaults file: the one this JVM started with, plus every `sticky` change made on top of it. `session` and `for` changes are left out, and so is anything reset `--to-native`.

Tune in a sandbox with `sticky`, export, then start the agent with `-javaagent:logaperture-agent.jar=--vendor-defaults=<file>`.

**Options**

`--out <file>`
:   Write the file there instead of to standard output.

`--force`
:   Overwrite the file if it exists.

**Examples**

```sh
logctl export vendor-defaults --out vendor-defaults.yaml
```
