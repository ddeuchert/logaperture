# Changelog

All notable changes to LogAperture are recorded here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims
to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it
reaches 1.0. Pre-1.0 alpha builds are numbered `0.1.0-alpha.N`.

## [Unreleased]

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

[Unreleased]: https://github.com/ddeuchert/logaperture/compare/v0.1.0-alpha.1...HEAD
[0.1.0-alpha.1]: https://github.com/ddeuchert/logaperture/releases/tag/v0.1.0-alpha.1
