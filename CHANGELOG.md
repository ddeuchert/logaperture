# Changelog

All notable changes to LogAperture are recorded here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims
to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it
reaches 1.0. Pre-1.0 alpha builds are numbered `0.1.0-alpha.N`.

## [Unreleased]

### Added

- **`logctl help <command>`**, and `--help` after any command (`logctl set logger --help`), explain
  one command: its forms, what it does, its options, and examples. `logctl help set` lists the
  `set` commands to pick from. The guide's command reference (`reference/logctl.md`) is generated
  from the same text (issue #159; `doc/specs/user-documentation.md` slice 2).

- **The user guide**, at https://logaperture.org/ and in the release zip's `docs/` folder (open
  `docs/index.html`; it works with no network access). Written so far: Home with what 1.0
  supports, installing on WildFly and on a plain JVM, a Quick start, Configuration layers, and the
  `logctl` command reference, and reference pages for agent options and `-Dlogaperture.*`
  properties, `doctor` checks, `--json` output, the vendor defaults and recipe file formats, and
  the audit log. The other pages are being written for 1.0 (issue #159;
  `doc/specs/user-documentation.md`).

- **`logctl enable storms` and `logctl disable storms`** turn storm detection on and off in a
  running JVM, in every context. With no tier that lasts until the JVM stops; `for 30m` switches it
  to the other position after 30 minutes, and `sticky` keeps it across restarts, ahead of the agent
  argument. Running one twice is the same as running it once.
  Each change is audited (`switch=storm-detection`) and needs the new `diagnostics` capability.
  `logctl storms`, `status` and `doctor` say when it is disabled; a report taken after disabling is
  kept as it was at that moment, and enabling again starts a new measurement window. The agent
  argument `--storm-detection=on` starts a JVM with it enabled (issue #151;
  `doc/specs/storm-detection-toggle.md`). The state file goes to schema 12.

- **`logctl set logger <name> <level> --force`** also sets the loggers under `<name>` that have a
  level of their own, which the logging framework never lets inherit. After `set logger com.acme
  TRACE`, `com.acme.other` configured at `INFO` stays at `INFO`. With `--force` it goes to `TRACE`
  too, and `reset logger com.acme` puts it back to `INFO`. A logger you set yourself is kept, and so
  is anything changed since the force. Without `--force`, `set logger` now ends with a note naming
  the loggers that didn't follow and the command that sets them too. Guided `set logger` asks.
  `list loggers` shows `forced by com.acme` on a forced override. The state file goes to schema 11
  (issue #142; `doc/specs/set-logger-force.md`).
- **`logctl list recipes`** — the logging recipes on offer in the running application: named,
  documented sets of logger levels for watching one thing, e.g. `io.undertow:sessions`. They come
  from the libraries it has loaded (`META-INF/logaperture/recipes.yaml` on a class path, including a
  war's `WEB-INF/classes` and `WEB-INF/lib` jars), from the vendor defaults file's new `recipes:`
  section, and from a recipes folder (`~/.logaperture/recipes`, or `-Dlogaperture.recipes=<dir>`),
  read afresh each time. A folder or vendor recipe with a library's id takes its place; `--verbose`
  shows full sources and any recipe file that couldn't be read, which `logctl doctor` also reports
  (issue #92; `doc/specs/recipes.md`).
- **`logctl show recipe <id>`** — what a recipe is for, where it came from, and each change it would
  make against the live level (`INFO -> DEBUG`). A library's recipe may only raise levels: an entry
  that would lower one is shown as skipped. `--from <source>` picks among recipes sharing an id
  (issue #92).
- **`logctl apply recipe <id> [session | for <duration> | sticky]`** — switch a recipe on: shows the
  changes, asks, then makes them (`--yes` to skip asking; `for 4h` unless a tier is given). Every
  change is checked first, and if any would be refused nothing changes. Each change remembers the
  recipe: `list loggers`, `list handlers` and `list rules` show a `RECIPE` column, `list recipes`
  an `APPLIED` column (`for, 22m left (2 of 3)`), and the audit log names the recipe and where it
  came from. Applying it again replaces its rules rather than adding them twice (issue #92).
- **`logctl reset recipe <id>`** — switch it off: puts back every change that still carries the
  recipe, leaving anything changed by hand since alone; sticky ones need `--include-sticky`. Works
  for a recipe whose library has since been undeployed. On a terminal, `logctl apply` and `logctl
  reset recipe` alone pick from a list (issue #92).
- **Vendor defaults file: `namespace:` and `recipes:`**, and multi-line text written as YAML's
  `key: |` block text (issue #92). `logctl export vendor-defaults` carries both sections over.

### Changed

- **The release zip's `docs/INSTALL-wildfly.md` is replaced by the user guide** in `docs/`
  (`docs/get-started/install-wildfly.html`). `doc/wildfly-test-drive.md` became the guide's
  Quick start (issue #159).

- **`logctl --help` is a one-screen overview**: every command form, the options every command
  takes, and how tiers work. The per-command options and explanations moved to `logctl help
  <command>`. A usage error now shows only the forms of the command you typed, not the whole help
  (issue #159).

- **Storm detection is disabled by default.** Through alpha.3 it was always on, and it added a
  measurable cost to every log call that reached a handler (#150). Start the agent with
  `-javaagent:logaperture-agent.jar=--storm-detection=on`, or run `logctl enable storms`, to turn it
  on (issue #151).
- **Audit lines label a handler `handler=`, not `logger=`** (issue #137). A handler change used to
  print as `logger=FILE`, which read like a logger called FILE. Handler levels and the
  `DEFAULT_HANDLERS` membership now print `handler=<name>`, and the records naming the vendor
  defaults file loaded or the state file resumed from print `file=<path>`. Logger and rule records
  still print `logger=`. If you grep the audit log for `logger=` to find handler changes, grep
  `handler=` instead.
- **Much quieter startup and drop summaries** (issue #123; `doc/specs/quieter-output.md`). A start
  prints one line, `[logaperture] LogAperture <version> active (WildFly): vendor defaults <file>
  (…); N sticky settings restored`, in place of several INFO lines. Loading a vendor defaults file
  is audited once, with the file's SHA-256, and a restart's resume once, with counts -- no longer
  one record per entry. Drop summaries are one line for every rule, every 10 minutes
  (`-Dlogaperture.drop.summaryInterval`), none during the first interval; on WildFly they go
  through the server's own logging at INFO under `org.logaperture.drop` instead of appearing as
  `ERROR [stderr]`. No rule can match or be attached to `org.logaperture.drop`. Every
  LogAperture message now honours `-Dlogaperture.diagnostics.level`, whose default is now `WARN`,
  in one format (`[logaperture] LEVEL message`); a failure repeated on every log event is written
  at most once a minute.
- **Guided `logctl list`** also offers `recipes`. A one-letter answer to a guided question now
  counts only when no other choice starts with that letter, so `r` asks again (rules or recipes).

### Fixed

- **A long-running server no longer ends up throwing `StackOverflowError` from every log call.**
  On java.util.logging and JBoss LogManager (WildFly), the agent's two filters on each handler, for
  storm detection and for rules, wrapped each other again on every 30-second check. So every log
  record went through a longer chain the longer the server ran. Logging got slower and storm counts
  were inflated. After a few days, any thread that logged ran out of stack, and a timer thread that
  died that way never came back. The filters now keep a single fixed layering however often the
  check runs. Affects 0.1.0-alpha.3 (issue #145).
- **The guided logger prompt accepts a trailing `.*` to pick from a subtree.** Typing
  `*.hibernate.*` or `org.hibernate.*` at `logctl set logger` or `logctl add rule` used to be refused
  ("A trailing '.*' isn't needed"), though the prompt's own example was `*.deployment.*`. It now
  lists the package and every logger under it to pick from, and sets only the names picked. A
  complete command such as `logctl set logger 'org.hibernate.*' DEBUG` is still refused, since there
  it would be a standing wildcard (issue #139).
- **A package name with loggers under it is no longer reported as missing.** Just after a server
  start, the guided prompt could say `No logger named org.wildfly exists yet; set it anyway? [y/N]`
  even though `list loggers org.wildfly --show-all` showed dozens of loggers under it: the framework
  hadn't created the `org.wildfly` logger itself yet. The prompt now uses the name and says what it
  reaches (`org.wildfly isn't a logger itself yet; 37 loggers under it inherit from it ...`). A name
  with nothing at or under it is still asked about (issue #140).
- **A handler set on its own under a group setting no longer flips every 30 seconds.** After
  `logctl handler ALL_HANDLERS TRACE` then `logctl handler FILE DEBUG`, the verification sweep used
  to put `FILE` back to `TRACE` and then to `DEBUG` on every tick, auditing both changes each time.
  Now the most specific setting wins and stays: a handler's own setting, then `DEFAULT_HANDLERS`,
  then `ALL_HANDLERS`. `logctl handler FILE reset` hands `FILE` back to the group's level rather
  than its original one, and `logctl status` notes which wins when a group is listed with a more
  specific setting. The sweep also no longer writes an audit row for a group member that was
  already at the right level (`previous=TRACE new=TRACE`) (issue #135).
- **`logaperture-agent.jar` listed twice on `-javaagent`** no longer starts LogAperture twice. The
  second copy used to fail to lock the state file and to register its control surface, logging
  both at startup; now LogAperture starts once, from the first entry, and every later entry writes
  one line, `LogAperture is already started in this JVM; ignoring the duplicate -javaagent entry.`
  Attaching the agent to a JVM that already runs it is ignored the same way. `logctl doctor` still
  flags the duplicate entry (issue #120).
- **Storm detection is much cheaper per log line.** Every message that reaches a handler is
  normalized for storm fingerprinting (`order 4821` and `order 9134` count as one message). That
  was four regular-expression passes; it's now one pass over the text with the same result, and
  a message seen recently, such as a log statement's template, is looked up rather than
  normalized again. `logctl storms` shows the same normalized messages as before, except that a
  non-ASCII letter touching a hex run now always counts as part of the word (`édeadbeef` stays
  as it is; on Java 19 and later it used to become `é<hex>`). The lookup
  keeps up to 1,024 recent messages per context; `-Dlogaperture.storm.normalizationCacheSize`
  changes that, and `0` turns it off (issue #129).
- **Storm detection is cheaper again, most of all for messages built by string concatenation.**
  The fingerprint is now a hash of the normalized message, computed in one pass without building
  the normalized text; the text is built only the first time a message is seen. A message that
  never repeats no longer takes a slot in the recent-message lookup, so it can't push a template
  out. On the benchmark machine, storm detection adds about 35 ns to a templated log call (was
  about 110 ns) and about 290 ns to a concatenated one (was about 760 ns). `logctl storms` shows
  the same fingerprints and messages as before (issue #147).
- **The rule check costs nothing when no rule applies.** Every log line that reaches a handler
  went through the `drop`/`trim` rule check, which took a lock shared by every logging thread and
  recorded the line in a table, even with no rules attached. Now a line from a logger no rule can
  reach is passed straight through, and lines that a rule does apply to share the remaining
  bookkeeping across 16 locks instead of one (issue #130).

## [0.1.0-alpha.3] — 2026-09-28

The first suppression features: `drop` and `trim` rules, report-only storm detection, a vendor
defaults file, and guided (ask-for-what's-missing) `logctl` commands.
**Evaluation only — not for production.** The override store's and vendor defaults file's on-disk
formats may still change between builds without a migration path.

### Added

- **`logctl storms`** — lists the log storms the agent has detected: a burst of near-identical
  events from one logger (1,000 within 10 s by default, `-Dlogaperture.storm.*` to tune). For each:
  the logger, exception type and normalized message, when it started, how many events, the current
  rate, and whether it is still going. The first occurrence is kept in full, stack trace included.
  `--limit N` shows the worst N; `--json` for scripts. Report-only: nothing is suppressed or
  delayed. `logctl doctor` adds a one-line pointer while a storm is ongoing (issue #26;
  `doc/specs/storm-detection.md`).
- **`logctl add rule drop <logger> …`** — stop a known-noisy message from being logged without
  touching its logger's level, e.g. `add rule drop com.acme.batch.Worker --message-contains "This
  happens a lot"`. Match on the log message (`--message-contains`, `--message-contains-ignore-case`),
  the exception (`--throwable`, `--throwable-message-contains`, `--any-cause`), or both; at least one
  is required. Only events below `--below` (default `ERROR`) are dropped, and FATAL never is. One
  full event is let through every 5 minutes (`--sample-full <duration>`, or `--no-sample-full`),
  and a periodic summary line on the JVM's stderr counts what was dropped (issue #72;
  `doc/specs/drop-rule.md`).
- **`logctl add rule trim <logger> …`** — keep logging a noisy exception but shorten its stack
  trace: a matching event below `--below` is written with a one-line trace and a marker, or the top
  `--frames N` frames; `--collapse-causes` also folds the `Caused by:` chain. Same matchers as
  `drop`, none required. Text formatters only; JSON/XML handlers are left untouched (issue #34;
  `doc/specs/trim-rule.md`).
- **Rules are managed like overrides** — each gets a short id (`r1`, `r2`, …) and the same
  `session` / `for <duration>` / `sticky` tiers as `set logger` (default `for 4h`). A rule reaches
  the logger's descendants, including ones created later. `logctl list rules` shows each rule with
  its tier, expiry and hit count; `logctl reset rule <id>` and `logctl reset rules` remove them, and
  `logctl reset logger X` also removes the rules attached directly to `X`. Rules and storm
  detection need the JUL / JBoss LogManager adapter (WildFly); Logback is not yet supported (issue
  #71; `doc/specs/rule-pipeline-foundation.md`).
- **Vendor defaults file** — start the agent with
  `-javaagent:logaperture-agent.jar=--vendor-defaults=/path/vendor-defaults.yaml` to ship baseline
  logger levels, handler levels, a default-handler list and `drop`/`trim` rules with a product.
  The file's settings apply from startup and become the baseline: `logctl reset` returns to them,
  not to the application's own logging configuration. `logctl list` shows them in a `VENDOR`
  column; `logctl status`, `env` and `doctor` report whether the file loaded. A file with errors
  is rejected as a whole, with every problem listed, and never stops the application from
  starting; `doctor` warns if the JVM's account can write the file. Vendor rules have `vendor:`
  ids and reset the way loggers do (see `logctl alter rule` below) (issues #60, #61;
  `doc/specs/vendor-defaults.md`).
- **`logctl reset … --to-native`** — on `reset logger`, `loggers`, `handler`, `handlers`,
  `default-handler`, `rule` and `rules`: return to the application's own logging configuration, ignoring the vendor
  defaults until restart; a plain `reset` puts the vendor default back (issue #94;
  `doc/specs/reset-to-native.md`). The layers and terms (native configuration, vendor defaults,
  baseline, override, effective level) are now defined in one place, spec §6.6.
- **`logctl alter rule <id>`** — change a rule in place, giving only what changes: `alter rule r3
  --below WARN`, `alter rule r3 --message-contains green`. It keeps its id; the `--no-` options
  (`--no-throwable`, `--no-message-contains`, …) remove an optional part, and a tier (`alter rule
  r3 sticky`) changes how long it lasts. The hit count starts over when what the rule matches
  changes. A vendor rule can be altered too: `reset rule vendor:<id>` puts the vendor's definition
  back, and `reset rule vendor:<id> --to-native` switches it off until restart (issue #96;
  `doc/specs/alter-rule.md`).
- **`logctl export vendor-defaults [--out <file>] [--force]`** — write the next vendor defaults
  file from a running application: the file it started with plus every `sticky` change made on top
  of it (`set … sticky`, `alter rule … sticky`, `add rule … sticky`). Anything reset with
  `--to-native` is left out, and `session`/`for` changes never reach the file. The agent checks the
  file loads before handing it over; `--out` never overwrites an existing file without `--force`
  (issue #62; `doc/specs/vendor-defaults-export.md`). Restarting the same application with the
  exported file hands those sticky settings over to it: each is active once, from the file (no
  `r7` running next to its exported copy), later edits to the file take effect, and the startup
  log lists what was handed over. Exported entries carry a `stateId:` line for this (issue #107;
  `doc/specs/export-round-trip.md`).
- **`logctl list rules --verbose`** — adds an `EXPRESSION` column: each rule's defining options,
  written as `add rule drop|trim` takes them (e.g. `--message-contains "Can't connect"
  --throwable java.net.ConnectException --below WARN --sample-full 5m`), with every default spelled
  out and shell-safe quoting. `list rules --json` always includes it as `expression` (issue #98;
  `doc/specs/list-rules-verbose.md`).
- **Guided `logctl add rule`** — on a terminal, leave out anything `add rule` needs and it asks.
  `logctl add rule '*.Deployer'` lists the matching loggers to pick from (`1,3-5`, `all`), then
  asks drop or trim, what to match, below which level, and how long the rule lasts, with the
  default shown for each. A bare name typed at the prompt, like `Deployer`, finds `*.Deployer`,
  the way a log line prints it. Before applying, it prints the equivalent one-line command to copy
  into a script. Complete commands, scripts without a terminal, and `--yes` never prompt (issue
  #104; `doc/specs/guided-add-rule.md`).
- **Pick the JVM from a list** — with several LogAperture JVMs running, any `logctl` command on a
  terminal lists them numbered and asks which one, then prints the `--pid` to skip the question
  next time. The list, and the "several candidates" table scripts still get (exit 4, unchanged),
  now show when each JVM started and its working directory, so two WildFly servers can be told
  apart (issue #106; `doc/specs/pick-jvm.md`).
- **Pattern targets for `add rule drop|trim`** — `add rule trim '*.Deployer' …` picks from the
  matching loggers on a terminal, or adds one rule to each with `--yes`; `--json` then prints an
  array (issue #104).
- **Guided `logctl list` and `logctl set`** — on a terminal, `logctl list` alone asks whether to
  list loggers, handlers or rules; for loggers, typing a name such as `Deployer` finds every
  matching logger, not only overridden ones. `logctl set` alone, `set logger Deployer`, `set
  handler` and `set default-handler` ask for whatever is missing. Loggers and handlers are picked
  from a numbered list that shows each one's current level, and the equivalent one-line command is
  printed before anything changes. Scripts, `--yes` and `--json` never prompt (issue #116;
  `doc/specs/guided-commands.md`).
- **Guided `logctl reset`** — on a terminal, `logctl reset` alone lists everything currently changed
  (overridden loggers and handlers, rules, an assigned default-handler membership) to pick from;
  `reset logger`, `reset handler` or `reset rule` with no name lists only that kind. Picking a
  sticky item asks once whether to reset it too, and with a vendor defaults file it asks whether
  to go back to the vendor defaults or the application's own configuration, so neither
  `--include-sticky` nor `--to-native` has to be remembered. One `logctl reset …` line per item is
  printed before anything changes (issue #116).
- **Guided `logctl alter rule`** — on a terminal, `logctl alter rule` alone lists the rules to pick
  one, and `logctl alter rule r3` with no changes lists the rule's parts with their current values
  (message, exception, level, sampling or stack frames, lifetime, reason); pick the ones to change,
  answer only those, and the `alter rule` command naming just those changes is printed before it's
  applied. `-` removes an optional part; answers that change nothing apply nothing (issue #116).

### Changed

- **`logctl set logger '<pattern>' <level>` on a terminal lists the matches to pick from** (`1,3-5`,
  `all`, Enter to cancel) rather than asking once to apply to all of them, the same as `add rule`. A
  pattern matching a single logger is applied without asking. More than 30 matches still get the
  all-or-nothing `[y/N]`. Without a terminal, and with `--yes`, nothing changes (issue #116).

### Fixed

- **Piped or scripted `logctl` could be treated as interactive on JDK 22–24**, whose
  `System.console()` returns a console even when input is redirected: `set logger`'s pattern
  confirmation could read its answer from the pipe, and an incomplete `add rule` asked questions
  instead of failing. `logctl` now also checks `Console.isTerminal()` where the JDK has it
  (issue #106).

- **WildFly could abort at startup** (`ModuleNotFoundException: org.jboss.as.standalone`) on
  launches where the JBoss LogManager is on the system class path
  (`jboss.modules.system.pkgs` naming `org.jboss.logmanager`) and the agent installs during
  `premain`. Cause: resolving WildFly's handler names asked JBoss Modules for its boot module
  loader before `org.jboss.modules.Main` had set `module.path`, which fixed that loader with no
  module roots. The resolver now waits for `module.path` (issues #86, #87). `drop`/`trim` rules
  keep applying from the moment the agent installs, including to other agents' startup logging
  that reaches the server's handlers. A new `-Dlogaperture.handlerInstallDelaySeconds=<n>`
  (default `0`) can hold back the handler-level installs (trim rendering, `top`'s byte counting,
  storm detection, the rule pipeline) if a launch ever needs it. See
  `doc/spikes/early-handler-install.md` and `doc/specs/wildfly-deferred-handler-install.md`.

### Known limitations

- `add rule drop|trim` attaches in the first logging context; multi-context attachment is
  [#79](https://github.com/ddeuchert/logaperture/issues/79).
- `trim` doesn't apply to structured (JSON/XML) formatters
  ([#83](https://github.com/ddeuchert/logaperture/issues/83)).
- Storm detection can merge two concurrent throw sites that share a fingerprint
  ([#77](https://github.com/ddeuchert/logaperture/issues/77)).

## [0.1.0-alpha.2] — 2026-09-20

Developer handler workflow, a reworked command surface, and WildFly fixes.
**Evaluation only — not for production.** The override store's on-disk format may
still change between builds without a migration path.

### Changed

- **`logctl` command surface refactor.** `set`, `reset` and `list` are now
  namespaced by target: `logctl set logger <logger> <level>`,
  `logctl set handler <name> <level>`, `logctl reset logger|loggers|handler|handlers`,
  `logctl list loggers|handlers`. The level-named verbs (`debug`, `trace`, ...) are
  retired. `list loggers` shows overrides only by default (`--show-all` for
  everything); `reset loggers --include-sticky` also clears sticky overrides.
  **Breaking:** commands and scripts written for alpha.1 (e.g. `logctl debug <logger>`)
  will stop working and must be updated to the new forms.
- **Pattern targeting is pure selection.** Glob patterns select the loggers a
  command applies to; standing rules are retired.

### Added

- **`AUTO` handler level** — `logctl set handler CONSOLE AUTO` makes a handler
  track the lowest active logger override.
- **`DEFAULT_HANDLERS`** — a deterministic default handler group for handler
  targeting.
- **Squelch warning** — warns when raising a handler's level would silence an
  active logger override.
- **`logctl env`** — read-only environment report for bug reports, including the
  fully-qualified state file path.
- **WildFly test-drive walkthrough** (`doc/wildfly-test-drive.md`).

### Fixed

- WildFly handler-name resolution on newer WildFly (#39) and the readiness gate
  that never passed (#64); boot-time resolver noise (#66); an intermittent
  JBoss LogManager install race.
- Handler override resume resilience across restarts: pending overrides and
  baseline-key migration (#29).
- `StateStore` batches removals in reset and expiry sweeps (#17).
- The adapter's handler-ref maps are pruned when a handler is detached, so repeated
  `/subsystem=logging` reconfiguration no longer grows them without bound
  ([#31](https://github.com/ddeuchert/logaperture/issues/31)).
- `WildFlyContainerIT` runs against any WildFly image (#65).

### Known limitations

- Still no log suppression; see the alpha.1 "Not yet in this build" list.

## [0.1.0-alpha.1] — 2026-09-07

First tagged build. **Evaluation only — not for production.** The override
store's on-disk format may still change between builds without a migration path.

### Added

- **Runtime log-level control.** `logctl set <logger> <level>` and the named
  forms (`debug` / `trace` / `info` / `warn` / `error`), across
  `java.util.logging` (incl. JBoss LogManager) and Logback, on plain
  `java -jar` and standalone WildFly. `--include-children` fans a change out
  over a subtree.
- **Enforced expiry and persistence tiers.** A change reverts on its own timer
  (`for 30m`), lasts the JVM's lifetime (`session`), or survives a restart and
  a WildFly redeploy (`sticky`). The agent re-applies persisted overrides on
  startup and re-asserts them after a `/subsystem=logging` change.
- **`logctl handler <name> <level>`** — set a handler's own level, up or down,
  with the same lifetime tokens; the fix when a raised logger still shows
  nothing because a handler is pinned stricter. A blocking handler on a level
  raise is named in a warning with the exact command to clear it.
- **`ALL_HANDLERS`** — a stable, restart-safe target that fans a handler-level
  change out over every real handler in a context.
- **Real WildFly handler names.** `CONSOLE`, `FILE`, and any dedicated handler
  resolve to their configured names, read in-VM from the running server's own
  `/subsystem=logging` model — no socket, no credentials. Degrades to
  `ALL_HANDLERS`-only where the model can't be read.
- **`logctl handlers`** — the addressable handler catalogue: name, level, sink,
  target file, and any active override.
- **`logctl doctor`** — read-only diagnosis of common logging-config problems:
  unbounded file-handler growth, verbosity left on, duplicate output to two
  persistent handlers, autoflush, and disk headroom vs. current write rate.
- **`logctl top`** — bytes written per logger over the agent's lifetime,
  worst-first, with a projected daily total and the stack-trace-byte fraction.
- **`logctl status`** and **`logctl reset` / `reset --all`** — inspect and undo
  what LogAperture has changed.
- **Governance.** Every mutation is capability-checked and written to a
  hash-chained, tamper-evident audit trail. The agent opens no network
  connections; `logctl` reaches it over the local attach API, UID-gated by the
  operating system.
- **`--json`** on every read command, for scripting and monitoring checks.
- **Evaluation bundle.** `logaperture-<version>.zip` — the agent jar, `logctl`,
  and a WildFly install guide, all marked pre-production.

### Not yet in this build

- Automatic storm collapse or any log suppression, per-rule squelching, budgets,
  the disk guard, capture profiles.
- The Log4j 2 adapter; Spring Boot, Tomcat, and Quarkus JVM mode at depth.
- Branch protection, Maven Central publishing, a signed release.

### Known limitations

- A persisted per-handler `sticky` override, and a `sticky ALL_HANDLERS`
  override, can be lost or mis-reverted across a WildFly restart if handler-name
  resolution loses the race with override resume
  ([#29](https://github.com/ddeuchert/logaperture/issues/29)).
- The adapter's handler-ref maps are not pruned across repeated
  `/subsystem=logging` reconfiguration
  ([#31](https://github.com/ddeuchert/logaperture/issues/31)).

[Unreleased]: https://github.com/ddeuchert/logaperture/compare/v0.1.0-alpha.3...HEAD
[0.1.0-alpha.3]: https://github.com/ddeuchert/logaperture/releases/tag/v0.1.0-alpha.3
[0.1.0-alpha.2]: https://github.com/ddeuchert/logaperture/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.1]: https://github.com/ddeuchert/logaperture/releases/tag/v0.1.0-alpha.1
