# Changelog

All notable changes to LogAperture are recorded here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims
to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it
reaches 1.0. Pre-1.0 alpha builds are numbered `0.1.0-alpha.N`.

## [Unreleased]

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
  (issue #62; `doc/specs/vendor-defaults-export.md`).
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
- `WildFlyContainerIT` runs against any WildFly image (#65).

### Known limitations

- The adapter's handler-ref maps are not pruned across repeated
  `/subsystem=logging` reconfiguration
  ([#31](https://github.com/ddeuchert/logaperture/issues/31)).
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

[Unreleased]: https://github.com/ddeuchert/logaperture/compare/v0.1.0-alpha.2...HEAD
[0.1.0-alpha.2]: https://github.com/ddeuchert/logaperture/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.1]: https://github.com/ddeuchert/logaperture/releases/tag/v0.1.0-alpha.1
