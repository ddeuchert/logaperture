# Rule-pipeline spike — can `drop` and `trimStackTrace` be built on JBoss LogManager?

Status: **done (2026-09-21).** Stage A (plain JBoss LogManager) and Stage B (real WildFly 33.0.0 and
26.1.3) complete. Every question below is answered; two design refinements are recommended.

Parent spec: [`doc/specs/filtering-epic.md`](../specs/filtering-epic.md) (draft; not yet merged when this
spike ran) — the epic that pulls M2's `drop` (gate stage) and `trimStackTrace` (render stage) forward.
Also [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.2 (two-stage pipeline), §7.2 (rule model),
§15.5 (hooks get discarded), §17 (M2).
Builds on: [`m0-adapter-grid.md`](m0-adapter-grid.md), which already proved `Logger.setFilter` and
`Handler.setFormatter` work and are durable on WildFly. This spike tests only what M0 did not.

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this document, not the harness.
The harness is not committed — see "Harness".

## The scenario

The real-world case the epic is designed around, hard-coded into the prototype:

- **Trim:** logger `*.AutoUpdateHelper`, throwable `java.net.ConnectException`, log message contains
  `Failed to connect to URL`, level below FATAL → one-line summary plus a marker instead of the trace.
- **Drop:** same logger, message contains `This happens a lot`, level below ERROR → discarded.

## Results

| # | Question | Result |
|---|---|---|
| 1 | **Gate coverage.** Does a handler-level filter cover loggers created later, where `Logger.setFilter` cannot? | **Yes.** `Logger.setFilter` is *not* inherited (a child logger bypassed its parent's filter). A handler-level `Filter` saw a logger created after install. On WildFly, a filter on every handler dropped exactly the expected events for both the console and file handlers. |
| 2 | **Trim via a copied record.** Can the formatter be handed a copy with the exception replaced? | **Yes**, with a correction: `ExtLogRecord.copyAll()` is a `void` in-place snapshot, not a copy. The copy comes from the **copy constructor** `new ExtLogRecord(ExtLogRecord)`. Verified: sibling handlers still render the full trace (shared record untouched), the application's own `Throwable` is untouched, and caller info (`%C`, `%M`), formatted correctly, including when an `AsyncHandler` formats on another thread. |
| 3 | **One-line output and marker.** | **Yes for text formatters, with a caveat for structured ones.** Replacing the throwable with a frameless synthetic `Throwable` (whose `toString()` is the original's plus the marker) renders as one line and keeps a per-cause one-liner chain. The JSON formatter reports that synthetic class as `exceptionType` and drops the message — wrong. The alternative (null the throwable, append `: <toString> [marker]` to the message) is correct in JSON but loses the cause chain unless the suffix is extended to include causes. See recommendation R2. |
| 4 | **Reconfiguration.** | **Survives, via a re-arm loop.** See "Reconfiguration" below. No double-wrapping in any scenario. |
| 5 | **Chaining.** | **Yes.** Wrapping an existing handler filter preserved it (Stage A), and when WildFly's `filter-spec` replaced our filter, our re-arm wrapped WildFly's filter: both applied. |
| 6 | **Levels.** | **Plain int compare.** JBoss `FATAL` = 1100 > `ERROR` = `SEVERE` = 1000 > `WARN` = 900. A custom `java.util.logging.Level("FATAL", 1100)` logs as `FATAL` through WildFly's formatters. "Below FATAL" is `intValue < 1100`. |
| 7 | **Cost.** | **Idle/non-candidate logger: 3.4 ns/event, 0 bytes.** A candidate logger pays for `getFormattedMessage()` once (~360 ns, ~830 B, on a short message with one argument) — but the result is **cached on the record**, so the formatter's later use is free; the filter adds no formatting the event would not have paid anyway, and a dropped event skips the entire layout, date and stack-trace rendering. |

### Reconfiguration (Stage B, real WildFly)

| Event | What WildFly did | Outcome |
|---|---|---|
| Boot completes | Reset every handler's **filter** (`filterInstalls` 2 → 4) | Re-armed within the next 1s tick. |
| `pattern-formatter` attribute changed | Updated the formatter **in place**; our wrapper stayed | No re-install needed; trim continued under the new pattern. |
| `filter-spec` written on `CONSOLE` | Replaced our filter with its own | Re-armed by wrapping WildFly's filter (chain); both applied. |
| New `file-handler` added to root | New handler object | Covered within one tick; its first lines were already trimmed. |
| `:reload` | Reset all handler filters (`filterInstalls` 6 → 9), kept the handler objects and formatters | Re-armed; post-reload output correct. |

The same behaviour was seen on WildFly **26.1.3** (JBoss LogManager 2.1.18) and **33.0.0** (2.1.19).

## Design consequences

These change or sharpen what the epic spec says; fold them in.

1. **The gate is a handler-level `Filter` on every handler, not `Logger.setFilter`.** The epic's
   "drop applies to every handler" therefore means "installed on every handler", including ones
   added later.
2. **Coverage is maintained by a periodic re-arm, and it has a gap.** WildFly resets handler
   filters on its own schedule (boot completion, `filter-spec` writes, reload). Between a reset and
   the next tick (≤ the re-arm interval, 1s here) a matching event is not dropped or trimmed. The
   design is **fail-open** — the worst case is one interval of un-filtered output — which is
   consistent with §9's fail-open discipline, but it should be stated in the spec, and the
   interval belongs with the existing sweep/`reapplyOnReset` machinery. Closing the gap entirely
   would need a hook the container does not offer.
3. **Always chain, never replace.** Wrap the existing filter; re-detect on every tick whether our
   wrapper is still the one installed.
4. **Counters must be per event, not per handler evaluation.** With three handlers the prototype
   counted each dropped/trimmed event three times. Hit counts (which the epic promises in
   `status` and `top`) and the §9.6 suppression line need one count per event: record the decision
   on the event or count at a single designated point (§4.2's "carry the decision on the event").
5. **Trim uses the `ExtLogRecord` copy constructor, reached by reflection.** The real adapter has no
   compile-time dependency on JBoss LogManager; walking `record.getClass()` to
   `org.jboss.logmanager.ExtLogRecord` and calling its copy constructor worked from the agent's
   classloader on both WildFly versions. **Fail open** if the copy cannot be made: the prototype
   returns the untrimmed line.
6. **Message matching is effectively free** once the formatter would run anyway, because
   `getFormattedMessage()` is cached on the record. Only loggers that have a message-matching rule
   pay for it, and only on their first check per event.

## Recommendations for the epic spec

- **R1 — Fold design consequences 1–6 into the foundation section** and into Decision #6 (cost
  model). The cost model is now measured, not assumed.
- **R2 — Structured formatters need their own rule in the trim child spec.** Recommend: text
  formatters get the synthetic frameless throwable (keeps the cause chain as separate lines);
  structured formatters (JSON, XML — detected by class) get the message-suffix form with each
  cause summarised in the suffix. Cost: one more formatter branch. Alternative if that is too
  much for the first slice: support trim on text formatters only and document JSON as untrimmed.
- **R3 — Stage the release risk.** The riskiest assumptions are now retired. What remains is
  ordinary implementation work plus the re-arm gap of consequence 2.

## Not verified

- **Logback / Log4j 2.** M0 proved Logback's `TurboFilter` (gate) and `Encoder` swap (render) work;
  neither was re-tested for trim's record-copy or per-event counting. Out of scope for this epic's
  first slice, which is JBoss LogManager/JUL only.
- **Long-lived handler churn** (repeated `/subsystem=logging` remove/add cycles) — see #31 for the
  related handler-ref growth issue in the adapter.
- **Throughput under contention** with several rules and many handlers; Stage A measured a single
  rule on a single thread.
- **`AsyncHandler` on real WildFly.** Verified on plain JBoss LogManager only.

## Harness

Not committed. Two pieces, both dependency-free apart from the JBoss LogManager jars taken from the
`quay.io/wildfly/wildfly` images (the module directory of `org.jboss.logmanager`, plus
`wildfly-common` and the `javax.json` EE8 jar for the JSON formatter):

- **Stage A:** a single-class program run with `-Djava.util.logging.manager=org.jboss.logmanager.LogManager`.
  It attaches a capturing `ExtHandler` (optionally behind an `AsyncHandler`) under `PatternFormatter`
  or `JsonFormatter`, installs the drop filter and trim formatter, and asserts on the rendered lines
  for the epic's scenario (ERROR trimmed, FATAL full, non-matching message full, other logger full,
  cause chain, sibling handler full, caller info intact, application `Throwable` untouched), plus
  filter chaining, decide-before-format, late-logger coverage, and per-event cost with and without
  allocation.
- **Stage B:** a `-javaagent:` jar in the M0 style (a daemon thread that polls
  `java.util.logging.manager` until it is WildFly's, uses only the JUL API plus reflection, re-arms
  every second, and emits the scenario's probe events every three seconds), launched against the two
  WildFly images with the agent appended to `standalone.conf`. Reconfigurations were driven with
  `jboss-cli.sh` inside the container: `pattern-formatter` write, `filter-spec` write on `CONSOLE`,
  add a `file-handler` and attach it to `ROOT`, and `:reload`.
