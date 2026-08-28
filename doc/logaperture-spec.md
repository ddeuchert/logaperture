# LogAperture — High-Level Specification

**Runtime logging control for the JVM.** A Java agent that lets you see, tune, and bound what your application logs — without restarting it, editing its configuration, or knowing in advance what will go wrong.

*Draft v0.3. Project name **LogAperture**; command `logctl`; artifacts `logaperture-*`. Three personas (§2), security-governed (§9), container-neutral (§15).*

---

## 1. Problem statement

Logging configuration in the JVM is effectively static. Changing a level usually means editing a config file and restarting, or relying on whatever partial control the framework happens to expose (Log4j2 JMX, Logback `JMXConfigurator`, Spring Boot Actuator `/loggers`). None of these are portable across frameworks, none survive a restart, and none of them address the more common operational pain: **a small number of loggers producing enormous volumes of low-value output**, typically repeated stack traces from third-party libraries retrying a failing connection.

This project provides a Java agent that attaches to any JVM (statically or dynamically), discovers whichever logging framework is present, and exposes a uniform control plane for:

1. **Level control** — inspect and mutate logger levels at runtime, with expiry.
2. **Persistence** — changes survive JVM restart and framework reconfiguration.
3. **Squelch rules** — declarative, keyword-and-category-based suppression, trimming, rate-limiting and deduplication of log events, with the ability to trim stack traces without dropping the event entirely.

Requirement 3 is the differentiator; 1 and 2 are table stakes that make it deployable.

---

## 2. Goals and non-goals

### Personas

Three, with different wants and — more importantly — different failure modes. The right structure is **one engine with thin persona-specific layers**, not three tools.

| | Wants | Failure mode |
|---|---|---|
| **Developer** | To find the one line that matters in a scrolling console, and to raise a level without editing config | Writes `System.out.println`; occasionally ships it |
| **Support engineer** | To collect the right diagnostics from a machine they don't own, without breaking it | Enables DEBUG on a customer site and never turns it off |
| **Customer / sysadmin** | Logs that stay bounded, transmittable, and openable | Disk exhaustion takes the application down |

The shared core — adapters, matchers, level control with *enforced* expiry, gate-stage actions, persistence — serves all three. Highlighting and baselining are a developer layer. Budgets, guards, and capture bundles are an operations layer. Neither layer should be able to hold the core hostage.

### Goals

- **Be faster and easier than `System.out.println`.** The developer is in a redeploy loop; if raising a log level takes longer than typing a println, the tool has failed regardless of how good its rule engine is.
- **Make the interesting line findable in a scrolling console.** Suppression is only half of this; promotion is the other half (§14.2).
- **Keep log volume bounded on machines you do not control.** A multi-GB file that fills a customer's disk is an outage, not an inconvenience (§16).
- **Make every diagnostic change self-reverting.** Expiry is not a convenience feature; it is the mechanism that makes it safe to hand this tool to someone on a customer site.
- Zero application code change; zero build change. Agent only.
- Never mutate any configuration file the container or application owns (§15.10).
- **`premain` (`-javaagent:`) is the primary entry point**, and the one every guarantee in
  this document is validated against. `agentmain` (dynamic attach to an already-running
  JVM) is a secondary, best-effort entry point — worth supporting where it costs nothing
  extra, not a design driver, and not assumed to have equal fidelity. §4.1 explains why:
  much of what the agent needs to do reliably depends on hooking a class *as it first
  loads*, which `agentmain` typically cannot offer, since by the time you attach to a
  running JVM the target framework classes are usually already loaded. Nothing in the
  architecture should foreclose improving `agentmain` support later — it just isn't where
  v1's effort goes. (JDK 21+ also warns on dynamic agent loading by default, JEP 451 —
  see §8.1 — an independent reason not to lean on it as primary.)
- Support Logback, Log4j 2.x, `java.util.logging`, and Log4j 1.x, behind one uniform model.
- Fail open. A malfunctioning agent must degrade to "normal logging", never to "no logging" or "crashed JVM".
- Negligible overhead on the logging hot path (see §10 for a budget).
- No transitive dependencies leaked into the application classloader.

### Non-goals (at least for v1)

- Log shipping, aggregation, parsing, or storage. This is a control plane, not a pipeline.
- Replacing the frameworks' own configuration files as the source of truth for *initial* config.
- Structured-logging transformation, log correlation, or trace injection (APM agents already do this well).
- Windows/Unix service management, container orchestration integration beyond a documented pattern.

---

## 3. Prior art and honest differentiation

Worth auditing carefully before writing code, and worth stating plainly in the README — much of this exists in pieces:

| Capability | Existing solution | Gap |
|---|---|---|
| Runtime level change | Log4j2 JMX, Logback `JMXConfigurator`, Actuator `/loggers` | Framework-specific; not persistent; Actuator needs Spring |
| Rate limiting | Log4j2 `BurstFilter` | Log4j2 only; config-file driven; not runtime-mutable |
| Duplicate suppression | Logback `DuplicateMessageFilter` | Logback only; crude matching; not runtime-mutable |
| Conditional deny | Logback `TurboFilter`, Log4j2 `Filter` | Requires code or config changes; boolean only — cannot trim |
| Event rewriting | Log4j2 `RewriteAppender` + `RewritePolicy` | Log4j2 only; requires config change |
| Runtime level change (WildFly), management path | `jboss-cli.sh` logging subsystem ops | Goes through the management model and **writes the change back into `standalone.xml`** — sticky whether you wanted it or not, and can end up committed or baked into an image |
| Runtime level change (WildFly), MBean path | Pushing attributes to the LogManager MBean | Changes the running JVM only and is **lost on restart** — if you want it to stick you must separately edit `standalone.xml`, so you end up making the same change twice in two places |

These two rows are the whole problem in miniature. WildFly offers a mechanism that persists when you don't want it to and a mechanism that doesn't persist when you do, with no indication of which is which, and the user is expected to know that changing a log level involves choosing between them. **What is missing is the third option**: sticky, but recorded outside the server's own configuration, revocable in one command, and optionally self-expiring (§6.1).
| Cross-framework level change from a CLI | **Arthas** `logger --name X --level debug` | Requires naming the classloader by hashcode in war/fat-jar deployments; heavyweight interactive console; no persistence, no rules |
| Runtime method tracing (the real println replacement) | **Arthas** `trace`/`watch`, **Byteman**, **BTrace** | Mature and out of this project's scope — integrate or defer to them rather than rebuild |

### 3.1 Surveyed landscape (as of mid-2026)

A search across the obvious categories turns up plenty of adjacent work and nothing that occupies this position. Grouped by which of your features it touches:

**Runtime level control — crowded.** Arthas (`logger --name X --level debug`, cross-framework, attach-based), Spring Boot Actuator `/loggers`, Log4j 2's JMX support, Logback's `JMXConfigurator`, the WildFly management CLI and MBean paths. All of them are ephemeral, none expire, none persist outside the app's own config. This is the least differentiated part of the project and should not lead the pitch.

**Dynamic tracing — mature, and someone else's problem.** Byteman (JBoss), BTrace, Arthas `trace`/`watch`, and smaller efforts like `d-akara/java-tracing-agent`, which injects logging into a running application from a JSON config. This is the real `println` replacement (§14.1) and there is no case for building a fourth one.

**Duplicate and burst suppression — exists, but crude and config-bound.** Logback ships `DuplicateMessageFilter`; Log4j 2 ships `BurstFilter`. The Logback one keys on the raw message pattern, so parameterised messages collapse together regardless of their arguments — `Getting customer {}` counts eight different customers as repetitions of one message. The common workaround is a hand-rolled `TurboFilter` backed by a Caffeine cache, which people re-invent locally, article by article. All of it requires editing the application's logging configuration and knowing in advance that you want it.

**Rate limiting in the pipeline — solves a different problem.** rsyslog's repeated-message reduction, journald rate limits, Fluent Bit's throttle filter, Vector, Logstash. Every one of these acts *after* the bytes have been formatted and written on the originating host. They do not save the customer's disk, do not reclaim the CPU spent rendering stack traces, and mostly do not exist at all in a single-box on-premises install.

**Error fingerprinting — right idea, wrong deployment model.** Sentry, Rollbar and similar do exception fingerprinting, grouping and rate limiting well. They require a server and network egress, and they never touch the log file that is filling the disk.

**General-purpose agents.** The OpenTelemetry Java agent, Glowroot, and the APM vendors instrument and export; none of them control logging configuration or reduce log volume at source.

The closest thing found to the storm-collapse design in §7.1 is `log-rate-limit` — a Python logging filter that groups messages into streams, suppresses repeats, and emits a "skipped N logs" summary. Same instinct, different language, and in-application rather than agent-side.

### 3.2 Where the genuine gaps are

Four, in descending order of confidence:

1. **Persistence with expiry.** Nothing found offers "set this level, have it survive a restart, and revert itself in thirty minutes". Every tool is either ephemeral or permanent, which is the §3 problem restated.
2. **Zero-configuration storm collapse.** The building blocks exist inside the frameworks but require editing configuration in advance for a problem that is unknown by definition. Nothing applies it agent-side, cross-framework, without prior knowledge.
3. **The on-premises, no-egress deployment model.** The entire observability industry assumes a pipeline and a backend. A constrained single box owned by a customer, with a support engineer on the phone and no network path out, is not a scenario anyone is building for.
4. **Governance.** No comparable tool ships a capability model, a suppression floor, a verbosity ceiling, or a tamper-evident audit trail (§9).

**Caveat: absence of evidence.** A search cannot prove nothing exists, and this space is full of badly-named single-maintainer repositories and capabilities buried inside commercial APM products. Before committing, do the specific due diligence: browse GitHub by topic (`javaagent`, `logging`, `log4j`), check the Awesome Java lists, and ask directly on the Logback and Log4j 2 mailing lists — the maintainers will know immediately whether this has been tried.

That last conversation is worth having for a second reason. If storm collapse belongs in Log4j 2 core rather than in an agent, contributing it upstream reaches vastly more users than a new project ever will, and the parts that genuinely cannot live upstream — cross-framework control, persistence, governance, the on-box support workflow — become a sharper and more defensible project as a result.

**The differentiation is the combination**: cross-framework, runtime-mutable, persistent, attachable to a running JVM, and — critically — supporting *partial* suppression (trim the trace, keep the line) rather than binary allow/deny. No existing tool covers that intersection.

---

## 4. Architecture

### 4.1 The key design decision: extension points first, instrumentation second

The instinct with a javaagent is to instrument everything. Resist it. Both major frameworks expose supported extension points that cover most of what's needed, and code built on those will survive version bumps that bytecode instrumentation would not.

**Instrumentation should be reserved for:**

- Detecting *when* the logging framework has been loaded, across arbitrary classloaders, so adapters can install at the right moment.
- Frameworks or versions with no adequate extension point — this also covers re-establishing hooks after a reconfiguration event, for the frameworks that expose no reconfiguration listener at all (JUL, Log4j 1 — §4.3, §15.5). Logback and Log4j 2 both ship a public listener for exactly this case, and that's public-API territory, not instrumentation — see below.
- Optional interception of `Throwable.printStackTrace()` and similar direct-to-stderr paths.

Everything else — filters, appender wrapping, encoder/layout delegation — uses public API. Budget the maintenance cost accordingly: an instrumentation-heavy design means a per-version test matrix that will dominate the project's effort.

**Two different things both get called "instrumentation," and they have very different reliability profiles.** Hooking a class *as it first loads* — a `ClassFileTransformer` registered before the target class is ever defined — is reliable and well-trodden; it's how "detect when a framework has loaded" above actually works. **Retransforming a class that's already loaded is a different, much less reliable operation in practice**, independent of what the JVMTI spec formally permits: it's restricted to method-body-only changes (no new fields or methods, no signature or hierarchy changes), and even within that narrow lane, lambdas, anonymous/synthetic classes, and JIT-deoptimization interactions are long-documented sources of failure across JVM vendors and versions — the reason dedicated hot-reload tools (JRebel, HotswapAgent) build their own reload machinery rather than trust raw class redefinition for anything beyond the trivial case. **Treat class-load-time hooks as reliable and load-bearing; treat retransformation of an already-loaded class as a last resort, not a design pillar.**

This is the concrete reason `agentmain` is a secondary entry point in §2's goals rather than an equal partner to `premain`: `premain` runs before the target frameworks load, so its hooks are the reliable, class-load-time kind. `agentmain` attaches to a JVM where those classes are typically already loaded, which pushes anything instrumentation-dependent into the fragile retransform case. That's a reason to make `premain` the one every guarantee is validated against — not a reason `agentmain` can never be supported.

### 4.2 Two-stage pipeline

Squelch actions divide cleanly into two stages, and the distinction drives the whole implementation:

**Gate stage** — evaluated before or during event construction, boolean outcome. Portable across every framework via native filter APIs. Covers `drop`, `rateLimit`, `dedupe`, `sample`.

**Render stage** — evaluated at serialization time, transforms output. Requires wrapping the encoder/layout or the appender. Covers `trimStackTrace`, `redact`, `summarize`, `collapseCauses`.

A third category, **event mutation** (`downgrade` level, `reroute`), is the hardest — it requires rebuilding the event and, in some frameworks, re-entering the dispatch path. Defer to a later milestone.

Some actions are *decided* at gate stage but *applied* at render stage. Carry the decision on the event itself (a reserved MDC/ThreadContext key, or a wrapper event object) rather than a `ThreadLocal` — async appenders and Log4j2's disruptor-backed async loggers will hand the event to a different thread.

### 4.3 Per-framework mechanism map

| | Logback | Log4j 2.x | JUL | Log4j 1.x |
|---|---|---|---|---|
| Level read/write | `LoggerContext.getLogger().setLevel()` | `Configurator.setLevel()` | `Logger.setLevel()` | `Logger.setLevel()` |
| Gate stage | `TurboFilter` (context-wide) | context-wide `Filter` | `Logger.setFilter()` / handler filter | appender `Filter` |
| Render stage | wrap `Encoder` on each appender; wrap `ILoggingEvent` to return trimmed `IThrowableProxy` | wrap `Layout`, or `RewriteAppender`/`RewritePolicy`; rebuild via `Log4jLogEvent.Builder` | wrap `Formatter` on each handler | wrap `Layout` |
| Reconfiguration hook | `LoggerContextListener` | `ConfigurationListener` / `PropertyChangeListener` | none — poll (see §4.1: `LogManager` is typically already loaded before any agent attaches, so a reliable class-load-time hook usually isn't even available) | none — poll |
| Multiple contexts | one `LoggerContext` per classloader via `ContextSelector` | same | single global `LogManager` | one hierarchy per classloader |

Note that `ILoggingEvent` and `LogEvent` are both interfaces, which makes render-stage wrapping tractable without bytecode work.

### 4.4 Classloader model

This is where agents of this kind usually go wrong. Proposed structure:

- **Bootstrap classloader**: a tiny bridge package — interfaces plus a static registry — visible to everything. No third-party types.
- **Agent classloader** (isolated, child of bootstrap): agent core, rule engine, config parsing, state store, control plane. Shaded/relocated dependencies.
- **Per-context adapter instances**: an adapter is instantiated *per detected framework context*, holding reflective or direct references into the classloader that owns the framework classes. Keyed by `(frameworkId, classloader identity)` in a weak map so redeployed webapps don't leak.

The agent must never load framework classes into its own classloader, and must never assume there is exactly one logging context. Application servers, OSGi containers, and Spring Boot's `LaunchedURLClassLoader` all break that assumption.

### 4.5 Self-diagnostics

The agent cannot use the application's logging framework — that's a re-entrancy loop waiting to happen. It needs its own minimal, dependency-free diagnostic writer to stderr or a dedicated file, with its own level, controlled by `-Dlogaperture.diagnostics.level=`.

### 4.6 Module layout

One constraint the layout must enforce: **`core` never depends on `agent`.** The agent is one delivery mechanism for the engine, not the engine itself. Keeping that direction clean is what makes in-process integrations — a Quarkus extension, a Spring Boot starter, anything embedded — cheap to add later (§18.2), and it is the kind of dependency that gets introduced accidentally by a single convenience import. Assert it in the build.

```
logaperture-bridge          bootstrap-visible interfaces, no deps
logaperture-api             public rule/level model (also usable standalone)
logaperture-core            rule engine, matcher compilation, state store, config parsing
logaperture-agent           premain/agentmain, classloader plumbing, framework detection
logaperture-adapter-logback
logaperture-adapter-jboss-logmanager   JBoss LogManager — covers WildFly and Quarkus-JVM (§15.6)
logaperture-adapter-log4j2
logaperture-adapter-jul
logaperture-adapter-log4j1
logaperture-container-none      plain java -jar; the baseline, built first
logaperture-container-wildfly
logaperture-container-springboot
logaperture-container-tomcat
logaperture-container-quarkus
logaperture-control-jmx     MBean surface
logaperture-control-http    optional embedded endpoint (com.sun.net.httpserver, zero deps)
logaperture-cli             attach-API client
logaperture-dev             OPTIONAL ADD-ON — highlight/banner, baselining, runtime rule authoring.
                       Never packaged into a customer-facing distribution (§9.11)
logaperture-it              integration test matrix
```

---

## 5. Feature 1 — Level control

> Implementation spec for the first buildable slice: [`doc/specs/level-control.md`](specs/level-control.md).

### Operations

- `listLoggers(filter)` → name, configured level, effective level, whether an override is active, override source and expiry.
- `setLevel(name, level, options)` where options include `includeChildren`, `expiresIn`, `reason`.
- `resetLevel(name)` — revert to the framework's configured value.
- `resetAll()` — remove every override, restore baseline.
- `snapshot()` / `restore(snapshot)`.

### Semantics to pin down early

- **Baseline capture.** On adapter install, capture the framework's configured levels. Without this, `reset` is undefined and persistence can't distinguish "user set INFO" from "app was already INFO".
- **Expiry is a first-class feature, not a nicety.** `setLevel("com.acme", DEBUG, expiresIn=15m)` is the operationally correct primitive; a forgotten DEBUG override on a busy logger is the exact failure mode this tool would otherwise create. Expiry should be enforced by a scheduled sweep *and* persisted, so it survives restart.
- **`reason` field** on every mutation, propagated to the audit log. Cheap to implement, disproportionately valuable at 3am.
- Hierarchy semantics follow the underlying framework's (a level on `org.apache` affects `org.apache.http` unless that logger has its own explicit level). Document the difference where frameworks disagree.

---

## 6. Feature 2 — Persistence and resume

> Implementation spec: [`doc/specs/persistence.md`](specs/persistence.md).

### 6.1 Three tiers, chosen explicitly

The gap identified in §3 is that WildFly forces a choice between "dies at restart" and "edited into my configuration file forever". Offer three tiers instead, named at the point of use so the choice is conscious:

| Tier | Behaviour | Typical use |
|---|---|---|
| `--session` | Lives until the JVM stops | Quick check during a redeploy loop |
| `--for 30m` | Auto-reverts on a timer; survives restart within the window | **The default.** Support work, triage, most debugging |
| `--sticky` | Survives restart until explicitly revoked | A known-noisy category you have decided about |

All three are recorded in the agent's own state file. **None of them touch `standalone.xml`**, in any tier, ever. `logctl status` lists what is active in each tier and when it reverts; `logctl reset <logger>` removes one; `logctl reset --all` restores the baseline. (One override per logger, so the target is the logger name, not a separate id — see [`doc/specs/cli-transport.md`](specs/cli-transport.md) "Naming reconciliation".)

Making `--for` the default is the important choice. It means the careless path — a support engineer who forgets, a developer who moves on — resolves itself, and the durable options require someone to have thought about it.

### 6.2 The phone test

The other half of the WildFly problem is that `/subsystem=logging/logger=com.acme:add(level=DEBUG)` is close to impossible to dictate to a non-developer. Slashes, colons, parentheses, equals signs, and an ordering nobody can guess.

Adopt a concrete design criterion: **can a support engineer read the command down a phone line to a customer without them mistyping it?** `logctl debug com.acme for 30m` passes. Anything with punctuation-heavy syntax does not. Apply the test to every command in the CLI, and prefer named profiles (§16.5) over logger names wherever the customer would otherwise be transcribing a package path.

### 6.3 State store

- Default: single file, human-readable and hand-editable (YAML), under `${logaperture.home}/`,
  defaulting `logaperture.home` to `${user.home}/.logaperture/` (`%USERPROFILE%` on Windows) — a
  fixed, discoverable per-user location, not the JVM's own working directory. The specific file
  within it is named from the JVM's canonical working directory rather than being one shared file,
  so multiple JVMs on one box get one discoverable home without colliding by default; see
  [`doc/specs/persistence.md`](specs/persistence.md) for the derivation.
- Written atomically (temp file + rename), with a schema version field.
- Pluggable `StateStore` SPI from day one — containers are ephemeral and the interesting deployments will want a shared or external store. Ship file-based only in v1; the SPI is what matters.

### 6.4 Identity problem

"Resume on restart" requires knowing *which JVM's* state to resume. Default: derive it from the
JVM's own canonical working directory, so the common case needs no operator configuration at all.
Provide an explicit `logaperture.instanceId` as the escape hatch for the case that default can't
disambiguate on its own — two instances launched from the identical working directory — rather
than trying to be clever about auto-detecting identity from hostname/main-class/args.

### 6.5 Reconfiguration re-application

Both Logback and Log4j2 will happily reload their config from disk and throw away levels and filters we installed. The agent must:

1. Register for reconfiguration events where the framework supports it (§4.3), poll or instrument where it doesn't.
2. On reconfiguration: re-capture baseline, re-install filters and wrapped encoders, re-apply live overrides.
3. Be idempotent — double-wrapping an encoder on every reload is a real and easy bug. Tag wrappers with a marker interface and check before wrapping.

### 6.6 Precedence

Define and document, highest to lowest:

1. Runtime mutations made via the control plane in the current session
2. Persisted state from previous sessions (unless expired, or unless `logaperture.resume=false`)
3. Static rules file supplied at agent start (`-javaagent:logaperture-agent.jar=config=/etc/logaperture.yaml`)
4. The application's own logging configuration (the baseline)

---

## 7. Feature 3 — The squelch engine

### 7.1 Automatic storm collapse — the case declarative rules cannot cover

**The primary source of runaway log volume is a bug you have not seen yet.** Unexpected errors from unexpected usage patterns; a defect that drops into a tight loop throwing exceptions. By definition nobody wrote a squelch rule for it in advance, and nobody will be watching when it starts.

This is a significant qualification on everything else in this section. The declarative rule engine is the right tool for *known* noise — the Apache connection-retry chatter you have lived with for two years. It is structurally the wrong tool for the failure mode that actually fills customers' disks, because authoring a rule requires already knowing the shape of the problem.

So the engine needs a content-agnostic, zero-configuration layer that runs underneath the rules:

**Behaviour.** Any event whose fingerprint repeats beyond a threshold within a window automatically collapses. The first occurrence is emitted in full, always — it is the diagnostically valuable one. Subsequent identical occurrences are counted rather than written, with a periodic summary line, and a closing line when the storm subsides.

```
03:14:02 ERROR [com.acme.batch.Worker] Unable to reserve slot
    org.acme.SlotException: no capacity
        at com.acme.batch.Worker.reserve(Worker.java:88)
        ... full trace ...
03:14:07 WARN  [logaperture] storm collapse engaged: com.acme.batch.Worker / SlotException
                        (Worker.java:88) — 4,118 in 5s, suppressing duplicates
03:19:07 WARN  [logaperture] storm continuing: 2,431,900 suppressed since 03:14:07
03:41:22 INFO  [logaperture] storm ended: com.acme.batch.Worker / SlotException —
                        3,104,772 suppressed over 27m15s
```

**Fingerprinting, in two tiers.** Discriminating storms requires knowing the throw site, but `Throwable.getStackTrace()` clones the entire array and is far too expensive to call on every event in a storm. Use a cheap key always — `(logger name, level, throwable class, normalized message)` — and escalate to a frame-based key (top three to five frames) only by *sampling*, once the cheap key already indicates a storm. That keeps the hot path allocation-free while still separating two different bugs that happen to throw the same exception type.

**Suppress before formatting, not before writing.** In a storm the disk write is not the only cost — rendering a sixty-frame trace with its cause chain is significant CPU, repeated tens of thousands of times per second. Gate-stage denial runs before the layout or formatter executes (Logback's `TurboFilter`, a Log4j2 context-wide filter, JBoss LogManager's `Logger.setFilter`), so collapsing a storm reclaims CPU as well as bytes, and may keep the application responsive enough to be diagnosed.

Be honest about the limit: `fillInStackTrace` has already run at construction time, inside the application's own loop. The agent cannot recover that cost, only the logging-side rendering.

**Self-relaxing and self-announcing.** Collapse engages and disengages automatically on rate, and says so in the log at both ends. This is the §9.6 detectability requirement doing real work — a customer must never find a silent hole where three million events used to be.

**Detection is valuable even without suppression.** A storm *detector* that only reports — "logger X emitted 40,000 near-identical exceptions in five minutes starting 03:14, first occurrence below" — is precisely the diagnostic that identifies a tight-loop bug, and it changes no behaviour at all. That puts it in the read-only Layer 1 release, and it is probably the most compelling single thing in that release.

**Defaults.** On, with conservative thresholds. Content-agnostic, so it needs no knowledge of the application. Subject to the suppression floor (§9.5) like everything else.

### 7.2 Rule model

A rule is `{ id, description, enabled, when, then, terminal, dryRun }`.

**Matchers (`when`)** — all specified matchers must hold (AND); each matcher may express its own disjunction.

| Matcher | Forms |
|---|---|
| `logger` | `equals`, `startsWith` (with `includeChildren`), `glob`, `regex` |
| `level` | `equals`, `atLeast`, `atMost`, `in [...]` |
| `message` | `contains`, `containsIgnoreCase`, `startsWith`, `regex` |
| `throwable` | `type` (with `includeSubclasses`), `message` matchers, `anyCause` to walk the cause chain, `present: true/false` |
| `thread` | name matchers |
| `context` | MDC / ThreadContext key-value matchers |
| `marker` | name matchers (where supported) |
| `frame` | a stack frame matching a class/package pattern is present — expensive, opt-in only |

**Actions (`then`)** — ordered list, applied in sequence.

| Action | Stage | Notes |
|---|---|---|
| `drop` | gate | Terminal by definition |
| `rateLimit` | gate | `max`, `per`, `onExceed: DROP\|TRIM`, `emitSummary` |
| `dedupe` | gate | Key derived from logger + normalized message + throwable type; `window`, `emitSummary` |
| `sample` | gate | `oneIn: N` or `every: duration` |
| `trimStackTrace` | render | `frames: N` (0 = message line only), `collapseCauses`, `appendSummary` (e.g. `... 47 frames omitted by rule apache-connect-noise`) |
| `redact` | render | `pattern` → `replacement`; applies to message and throwable messages |
| `summarize` | render | Replace multi-line output with a single line |
| `tag` | gate | Add an MDC key — useful for downstream routing |
| `downgrade` | mutation | Deferred to a later milestone |

### 7.3 Evaluation semantics

- Rules evaluate in declaration order.
- `terminal: true` stops evaluation after a match.
- A `drop` always terminates.
- Render-stage actions accumulate; conflicting parameters resolve last-write-wins, logged as a diagnostic warning at config load.
- Rules are compiled once into an immutable plan and published via a `volatile` reference — evaluation is lock-free, updates swap the whole plan.

### 7.4 The keep-one-in-N escape hatch

The obvious failure mode of squelching is going blind. Every suppressing rule should support `sampleFull: { every: <duration> }` — one completely untouched event per interval per rule, so the full diagnostic is still in the log when someone needs it. This should be **on by default** with a sensible interval, and require an explicit opt-out. It is the single feature that makes this tool safe to recommend.

### 7.5 Worked example — the original use case

```yaml
version: 1

levels:
  - logger: com.acme.noisy
    level: WARN
    expiresIn: 30m
    reason: "INC-4412 investigation"

rules:
  - id: apache-connect-noise
    description: "Connection failures from Apache libs: keep the line, drop the trace"
    when:
      logger:
        startsWith: org.apache
        includeChildren: true
      level: { atLeast: WARN }
      throwable:
        present: true
        anyCause: true
        message: { containsIgnoreCase: "unable to connect" }
    then:
      - trimStackTrace:
          frames: 0
          appendSummary: true
      - sampleFull: { every: 10m }
    terminal: true

  - id: batch-storm-guard
    when:
      logger: { startsWith: com.acme.batch }
    then:
      - rateLimit:
          max: 100
          per: 1m
          onExceed: DROP
          emitSummary: true
```

### 7.6 Observability of the engine

Per-rule counters, exposed via JMX and the HTTP endpoint: `evaluations`, `matches`, `dropped`, `trimmed`, `bytesSaved`, `lastMatchAt`, `errors`. Two reasons this matters more than it looks: it's how an operator verifies a rule does what they meant, and "this rule saved 4.2 GB today" is the number that gets the tool adopted.

### 7.7 Dry-run mode

`dryRun: true` on a rule (or globally) increments counters and records sample matches without altering output. Non-negotiable for adoption — nobody enables log suppression in production without first seeing what it would have suppressed.

---

## 8. Control plane and user interfaces

### 8.1 One command model, several surfaces

> Implementation spec for the `logctl` CLI transport: [`doc/specs/cli-transport.md`](specs/cli-transport.md).

Every surface is a client of the same command model, subject to the same capability checks (§9.3), the same expiry defaults (§6.1), and the same audit trail (§9.7). **No surface is a privileged path.** A UI that can do something the CLI cannot is a security hole with a friendly icon.

**JMX (v1, required).** Zero dependencies, works everywhere, usable from JConsole, VisualVM, and `jcmd`. The reference implementation; everything else is a convenience over it.

**CLI over the Attach API (v1).** `logctl levels`, `logctl debug com.acme for 30m --reason INC-123`, `logctl top`, `logctl doctor`, `logctl status`, `logctl undo`. UID-gated by the JVM, so authentication comes free from the OS (§9.8). Note that JDK 21+ warns on dynamic agent loading (JEP 451) and may need `-XX:+EnableDynamicAgentLoading` — document this prominently.

### 8.2 The agent never listens

A user interface is worth building. A **listening socket inside the customer's JVM** is not, and the two are separable — that is the whole design here.

Recall §9.11.1: the Quarkus Dev UI vulnerability worked because browser JavaScript can reach localhost without a preflight, so "it only binds to loopback" is not a boundary when a browser runs on the same machine. Keeping the agent free of any listener preserves the claim that matters most in a security review — *this agent opens no network connections, inbound or outbound, ever* — and it is a claim a customer can verify by inspection rather than by trusting configuration.

So the rule is: **UIs are clients of the attach transport, exactly like the CLI. Nothing is added to the agent.**

### 8.3 Terminal UI — the recommended first interface

A full-screen interactive TUI over the same attach transport (JLine or Lanterna). No network, no browser, no new attack surface, no packaging problem, and it costs nothing security-wise because it is the CLI with a different renderer.

It also fits the deployment reality better than a browser would: a support engineer on a customer site is usually inside an SSH session, where a TUI works and a browser does not.

### 8.4 Browser UI — hosted by the CLI, not the agent

If a browser interface is wanted later, `logctl ui` starts a short-lived local web server **inside the CLI process**, which talks to the JVM over the attach API. The agent stays inert. The surface exists only while the user is looking at it, is never present on a customer machine, and cannot be misdeployed because it is not part of the agent distribution at all.

It still needs the §9.11.1 protections — unguessable path, required token, strict `Origin` checking, rejection of simple cross-origin requests — because localhost remains browser-reachable. The difference is that the blast radius is a process the user explicitly started and will close, rather than a permanent surface inside a production application server.

Ship it in `logaperture-dev` (§9.11) regardless.

### 8.5 What the interface is actually for

The motivating task — find a logger, see its level, change it — is harder than it sounds, and getting it right is most of the value.

**"Discovered" is the correct framing, and it needs to be visible.** Loggers exist only once something instantiates them, so any catalogue is partial. The UI must say so rather than presenting an incomplete list as complete. Show four states distinctly:

- **Live** — instantiated in this run
- **Known** — observed in a previous run, not yet this run. Still a valid target: both Logback and Log4j 2 accept a level on a logger that does not exist yet, so pre-setting works
- **Inferred** — derived from loaded class packages (§8.6), never yet observed logging
- **Referenced but never seen** — an override or rule names it, but it has never appeared. Usually a typo, occasionally dead code. **Surface this as a warning**; it is a free diagnostic and catches the most common configuration mistake there is

**Persist the catalogue and merge across runs.** The list should get richer every time the application runs, and should survive restarts along with everything else in §6.

**Present a tree, not a list.** A large application has tens of thousands of logger names; a flat searchable list is unusable. Package tree, collapsed by default, with fuzzy search, and both *configured* and *effective* level shown side by side — the difference between those two is the single most common source of confusion in logging, and showing both silently answers most support questions.

**Sort by volume.** You already compute bytes per logger for `top` (§16.1). Putting that number in the tree turns "browse the loggers" into "show me what is noisy", which is the question the user actually has. No comparable tool does this.

**Casual users need the safety rails most.** Default to a duration with a visible countdown, make `undo` prominent, preview rules against recent events before applying, and show the active-overrides panel permanently rather than behind a menu. The person least likely to remember they enabled DEBUG is exactly the person this interface is for.

### 8.6 Enriching discovery from loaded classes

Runtime observation can be augmented cheaply: most loggers are named after their declaring class, so walking `Instrumentation.getAllLoadedClasses()` and deriving the package tree gives useful autocomplete long before those classes ever log. Treat the results as **inferred** rather than real, and never let an inferred name silently become an override target without the user seeing which state it came from.

---

## 9. Safety, failure modes, and security

### Fail-open discipline — and its one deliberate exception

**Two mechanisms with opposite defaults.** This is a real tension and it should be explicit in the design rather than discovered later:

- **Rule evaluation** fails *open*. A broken matcher, a throwing action, an adapter that won't install — all of these result in unmodified log output. A bug in this project must never silence an application's logs.
- **The disk and volume guards** (§16.3, §16.4) fail *closed*. When the log volume is about to exhaust the disk, dropping log lines is strictly better than taking the application down. This is the one place where the tool deliberately chooses less logging under uncertainty.

Both are safe only because every guard action announces itself (§16.4). "There is no log line here" and "the log line was suppressed at 03:14 by the disk guard" must be distinguishable during an investigation.

### Ordinary failure handling

- Any exception inside a matcher or action is caught, counted, and the event passes through unmodified.
- A rule exceeding an error threshold auto-disables and raises a diagnostic.
- Global kill switch: `-Dlogaperture.disabled=true`, honoured without requiring the control plane to be reachable.
- If adapter installation fails, the agent logs a diagnostic and does nothing further. The application must start normally.

### 9.1 Security is the load-bearing design constraint, not a hardening pass

Two capabilities make this a security-relevant component regardless of intent. **Suppressing log output is the ability to hide activity.** **Raising log verbosity is the ability to create a data exposure that did not previously exist** — DEBUG logging routinely dumps request bodies, bearer tokens, SQL with bound parameters, and personal data into a file with weaker protections than the database it came from. The second risk is the less obvious one and the more likely to bite on a customer's machine.

Both must be governable by whoever owns the machine, and neither can be retrofitted onto a design that assumed a trusted operator.

### 9.2 The governing principle: restrictions compose, permissions do not

There are two legitimate authorities here and they will conflict. The vendor ships rule packs and needs support workflows to function. The customer's system administrator owns the machine and may have regulatory obligations the vendor knows nothing about.

Resolve it by never asking who wins:

> A capability is available only if **every** layer in the chain permits it. Each layer may restrict further. No layer can restore what a layer above it denied.

The chain, outermost first:

1. **Agent build** — what the code can do at all. Absent code paths are the strongest control.
2. **Vendor policy** — what you ship enabled, in a signed rule pack.
3. **Customer administrator policy** — a root-owned file on their machine. Can deny anything.
4. **Runtime grant** — what a given operator is actually exercising, within all of the above.

This is a pure intersection, which makes it analysable, testable, and explicable to a security reviewer in one sentence. It also means the customer can always say no, which is the property that gets the tool approved.

### 9.3 Capabilities

Authorization is per-capability, not a single on/off. The split that matters most is between operations that look similar but carry opposite risks:

| Capability | Risk it carries |
|---|---|
| `view` | Logger names and metrics can be mildly informative to an attacker; otherwise low |
| `level.raise` | **Data exposure.** Verbose logging writes secrets and PII to disk. Also disk exhaustion |
| `level.lower` | **Evidence loss.** Silencing a category that mattered |
| `rules.apply` | Applying a pre-authored, signed rule pack |
| `rules.author` | Writing arbitrary new rules at runtime — a much larger grant than applying one |
| `suppress` | Drop/trim actions specifically, separable from rate limiting |
| `capture` | **Data egress.** Producing a bundle of log content that leaves the machine |
| `persist` | Making a change outlive the process, rather than expiring |
| `guard.override` | Disabling or loosening the disk and volume guards |

A typical support workflow needs only `view`, `level.raise`, `rules.apply`, and `capture` — a considerably smaller grant than "administer logging", and one a security reviewer can approve. Design the CLI so that the common path exercises the minimum set.

### 9.4 Sealed at boot

Some settings must be fixed when the JVM starts and have **no runtime mutation path in the code at all**. Not an authorization check that could be bypassed — an absent method.

- The policy file location
- The capability grants themselves
- The protected-category lists (§9.5)
- Whether a network control plane binds at all
- Whether `rules.author` is permitted
- The audit sink destination
- Maximum permitted override duration

These come from agent arguments and a policy file whose path is given at agent start. The agent verifies at load that the policy file is not writable by the account the JVM runs as, and refuses to enable anything beyond `view` if it is. **On a locally hosted deployment the filesystem is the real trust boundary** — an attacker who can rewrite a root-owned policy file already owns the machine, and no amount of cryptography inside the JVM changes that.

Signing matters for a different reason: rule packs and diagnostic profiles *travel*, emailed to a customer during a support call. Sign those, verify against a key pinned at agent start, and reject unsigned packs when policy requires it.

### 9.5 Two symmetric protected lists

- **Suppression floor** — categories no rule may drop, trim, or downgrade. Security, authentication, audit, transaction, and licensing loggers.
- **Verbosity ceiling** — categories no operator may raise above a stated level, because they are known to emit sensitive data at DEBUG. Persistence layers, HTTP wire logging, security token handling.

Both sealed at boot, both settable by the vendor and further tightenable by the customer, both refusing-and-auditing on violation rather than silently ignoring. The verbosity ceiling is the one most projects omit and the one most likely to prevent an actual incident.

### 9.6 Suppression must never be silent

More important than any of the above, because it protects the categories nobody thought to protect:

**Every suppressed event is counted, and the counts are visible in the log itself.** A periodic line — `logctl: 1,247 events suppressed in the last hour (rule apache-connect-noise: 1,203; disk-guard: 44)` — means the log never contains an undetectable hole. An investigator can always distinguish "nothing happened" from "something was suppressed, here is what and by which rule".

Detectability is a stronger and cheaper guarantee than prevention, and it degrades gracefully: even a misconfigured or malicious rule leaves a trace of its own operation.

### 9.7 The audit trail

- Separate sink, which squelch rules cannot match and the runtime cannot redirect.
- Records the **revert** as well as the change — an override that expired unnoticed is exactly what an audit needs to show.
- Fields: principal, source (CLI / JMX / HTTP / file), what changed, previous value, expiry, `reason`.
- **Hash-chained entries** — each record includes a digest of its predecessor. Cheap to implement, makes deletion or alteration detectable, and is the sort of thing that turns a security review from an argument into a checkbox.
- Optionally mirrored to syslog or the Windows Event Log so the record leaves the process entirely.

### 9.8 Authentication comes from the operating system

The local attach path is UID-gated by the JVM: only the account running the JVM, or root, can attach. That set is exactly the set that should be able to control logging, it requires no credential management, and it is already audited by the OS. **Make the local CLI the primary and best-supported surface.**

Every other surface is strictly worse and should be treated accordingly. JMX inherits the JVM's JMX authentication and is only as good as that configuration. The HTTP plane is the one that converts a local tool into a remote attack surface: off by default, refuses to start without authentication configured, never binds a wildcard address by default, TLS or loopback only.

### 9.9 Capture bundles are a data egress channel

`logctl capture` collects log content and packages it to leave the customer's premises, with vendor-supplied rules deciding what goes in. Treat it as the egress path it is:

- **The agent never transmits anything.** No outbound network connections, ever, for any feature. The bundle is written to local disk and a human moves it. Document this as an absolute property — it is a claim a customer's security team can verify by inspection.
- **A manifest** listing exactly what was included, so an administrator can review before sending.
- **Customer redaction is additive.** The customer can add patterns; they cannot remove vendor ones, and neither can remove those covering credentials.
- `capture` is separately deniable, so a customer can permit diagnostics while forbidding extraction.

### 9.10 The agent's own supply chain

You are asking a customer to install a new binary that runs with full JVM privileges inside their application server. That deserves the same rigour you are asking of them:

- Signed artifacts, reproducible builds, published SBOM, minimal dependencies. The zero-dependency goal in §2 is now a security requirement, not an aesthetic one.
- **No expression interpolation, lookups, JNDI, or scripting anywhere in rule evaluation.** The lesson of CVE-2021-44228 is that a logging component sits on an attacker-reachable data path; rules must be pure pattern matching over strings, and attacker-controlled log content must never be interpreted.
- **Regex is an attack surface.** A catastrophically backtracking pattern in a rule pack, evaluated on every log event, hangs the application. Validate patterns at load, enforce a step budget at evaluation, and prefer literal matchers where the semantics are equivalent (§10 already wants this for performance — it is also a DoS control).
- All state size-bounded (§16.7), so a crafted log stream cannot exhaust the heap via the dedupe map.

### 9.11 Modes, on the Quarkus dev-mode pattern

Secure defaults conflict with the "faster than println" goal. Rather than splitting the difference, bundle the capability grants into named modes — and take the deeper lesson from Quarkus, which is not that dev tooling is *flagged off* in production but that it **is not present in the production artifact at all**.

**Absence beats configuration.** A capability that isn't in the deployed jar cannot be misconfigured, cannot be escalated, and cannot have a vulnerability that matters. A flag can be wrong; a missing file cannot. This argues for splitting the distribution:

| Artifact | Contents |
|---|---|
| `logaperture-agent.jar` | Adapters, rule engine, level control, expiry, `top`, `doctor`, budgets, disk guard, capture, audit, capability model. Safe to ship to a customer. |
| `logaperture-agent-dev.jar` | Optional add-on: highlight and banner actions, baseline recording, runtime `rules.author`, any interactive UI, tracing integrations. **Never included in a customer-facing distribution.** |

Mode selection then becomes partly a question of *which files are on disk*, verifiable by a directory listing rather than by auditing configuration. That is a far easier claim to make to a customer's security team, and a far easier one for them to check.

**The four modes:**

| Mode | Grants | Defaults |
|---|---|---|
| `dev` | Everything, including `rules.author`. Requires the dev add-on present | Ephemeral only, guards generous, verbose self-diagnostics |
| `preprod` | Mutating capabilities granted, `rules.author` optional | Guards on, audit on, verbosity ceiling enforced |
| `prod` | **Default.** Deny beyond `view` unless the policy file grants more | Everything on, deny by default, no network plane |
| `off` | Agent loaded but inert | Enabling requires a JVM restart |

`off` is worth having as distinct from "not installed": a customer can keep the agent in place, inert, with the property that turning it on requires a restart with changed arguments. Restart-to-enable is itself a meaningful safety guarantee, and for the strictest deployments you may want `prod` to permit `view` and `capture` live while requiring a restart for anything mutating.

**Mode is sealed at boot and composes downward.** It comes from agent arguments and the policy file, never from an environment variable the application could set, and there is no runtime path to change it. It behaves as another layer in the §9.2 chain: if the policy file says `prod`, the presence of the dev add-on grants nothing. The more restrictive setting always wins, so a stray dev jar on a customer machine is inert rather than dangerous.

**Default to `prod`.** Quarkus can default to dev because you explicitly type `quarkus:dev`. An agent attached through `JAVA_OPTS` has no such signal, and the failure mode is a dev-configured agent reaching a customer. Requiring a developer to add one flag to `standalone.conf` once is acceptable friction.

**Announce the mode, unsuppressibly.** Anything other than `prod` emits a prominent, protected line at startup — `LogAperture 1.0 — DEV MODE: permissive authorization, not for production use`. It cannot be squelched, including by the agent's own rules. A misdeployed dev agent then shows up in the first twenty lines of any customer log, which is how you would actually catch it. `doctor` should report the mode as a finding, and the capture bundle manifest should record it, so a bundle that arrives from a customer tells you immediately if their agent is misconfigured.

### 9.11.1 The cautionary half of the Quarkus analogy

Quarkus dev tooling is also the source of the best argument against building an interactive UI for this at all. <cite index="34-1">CVE-2022-4116 was a vulnerability in the Quarkus Dev UI allowing remote code execution on the machine running it, triggered simply by visiting a crafted webpage while the Dev UI was running.</cite> <cite index="31-1">It was rated 9.8 on CVSS v3</cite>, and <cite index="30-1">while it affected only dev mode and not production services, the impact was severe because it gave an attacker local access to a developer's machine.</cite> <cite index="29-1">The proof of concept opened a calculator, but the same technique could plant a keylogger to capture production credentials, or use the developer's tokens to modify source code.</cite>

The mechanism matters: <cite index="28-1">JavaScript on a malicious page can issue requests to localhost without a preflight, so localhost-bound services are reachable from the outside.</cite> "It only binds to localhost" is not a security boundary when a browser is running on the same machine.

Three consequences for this design:

1. **Dev mode being lower-risk is an illusion when the surface is browser-reachable.** A developer's workstation holds production credentials and source-signing keys; it is not a low-value target.
2. **Prefer the attach-API CLI over any HTTP surface**, including in dev. The attach path is UID-gated and has no browser-reachable endpoint, which eliminates this entire class. This considerably strengthens the case for cutting the HTTP control plane from 1.0.
3. If an HTTP surface is ever built, it needs an unguessable path, a required token, strict `Origin` checking, and rejection of simple cross-origin requests — not merely a localhost bind. <cite index="34-1">Quarkus's own interim workaround was to move the endpoint to a random path</cite>, which tells you how thin the default protection was.

### 9.12 Deliverable: a written threat model and control mapping

Customers in regulated environments will put this through security review, and "it can turn off logging" is a finding waiting to happen. Get ahead of it with two short documents in the repository:

1. A threat model naming the principals, the abuse cases (evidence destruction, data exposure through verbosity, egress through capture, DoS through regex), and the control for each.
2. A mapping of those controls to the requirements customers are actually audited against — log integrity and retention obligations under PCI-DSS, HIPAA, SOX and similar.

This is unglamorous and it is the difference between a tool that gets approved and one that gets banned. It is also a genuine differentiator: no comparable tool ships one.

---

## 10. Performance

Every gate-stage evaluation happens on the caller's thread inside the logging call. The budget should be explicit and enforced by JMH benchmarks in CI:

- **Target: < 200 ns added per evaluated event** for a typical rule set (~20 rules), and near-zero for loggers no rule can match.
- **Per-logger resolution cache.** Most matchers are decidable from the logger name alone. Precompute, per logger name, the subset of rules that could possibly match, cached in a bounded map. Loggers matching nothing get a fast-path no-op.
- **Cheapest matcher first.** Compile rules so name/level checks run before message checks, and message checks before throwable-chain walks. Reject regex where a literal `contains` is equivalent — do that rewriting at compile time.
- **Never call `Throwable.getStackTrace()` during matching.** It clones the whole array. Match on throwable type and message; if frame matching is requested, use `StackWalker` on JDK 9+ with a frame limit, and mark the matcher as expensive in documentation.
- **Zero allocation on the non-matching path.** No iterators, no boxing, no string concatenation.
- **Async-safe.** Log4j2's `MutableLogEvent` is reused across events — never retain a reference to an event or its fields beyond the call.

---

## 11. Compatibility matrix

| Axis | v1 target |
|---|---|
| JDK | 8 through current LTS+ (compile to 8 bytecode for the agent core, multi-release jar for `StackWalker` and JPMS handling) |
| Logback | 1.2.x, 1.3.x, 1.4.x, 1.5.x (note the jakarta split at 1.3) |
| Log4j 2 | 2.17+ (below that is CVE territory; document rather than support) |
| Log4j 1 | 1.2.17, plus reload4j |
| JBoss LogManager | Current WildFly line, the EAP versions in your estate, and Quarkus JVM mode |
| JUL | all |
| SLF4J | 1.7.x and 2.x, as a facade over the above |
| Containers | plain `java -jar`, WildFly, Spring Boot fat jar, Tomcat, Quarkus JVM mode, Jetty (see §15.3) |
| Not supported | Quarkus/GraalVM native image — no JVM, no `-javaagent`. OSGi and Karaf are out of scope for 1.0 |

JPMS on 9+ requires the correct manifest attributes (`Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`, `Can-Redefine-Classes`) and possibly `Add-Opens`. Test on a modular application early — it is a common late surprise.

---

## 12. Testing strategy

This will be the majority of the work, and the project's credibility rests on it.

- **Decompose the matrix, or it becomes unaffordable.** Container × framework × version is combinatorial and you cannot run it. Use the asymmetry of §15.1: *behaviour* is a property of the adapter, *discovery and lifecycle* are properties of the container.
  - **Adapter behaviour tests** — exhaustive, in-process, against a plain JVM with the framework on the classpath. Every framework, every supported version, every rule and action. Fast enough to run on every commit.
  - **Container integration tests** — few and deliberately shallow. Does the agent install? Does it discover every context? Does state survive a redeploy or reload? Does it leak on context discard? Nothing about rule semantics belongs here.
  This keeps the expensive axis small and the exhaustive axis cheap. Automate the split before writing the second adapter, not after the fourth.
- **Golden-output tests**: given an app that logs a known sequence, assert exact rendered output with and without rules.
- **Classloader isolation tests**: instantiate frameworks in child classloaders, verify per-context state, verify no leaks after classloader discard (weak-reference assertions).
- **Reconfiguration tests**: trigger a framework config reload mid-test, assert overrides and filters survive and are not double-installed.
- **Container smoke tests** for each `ContainerIntegration`, on real servers via Testcontainers, plus a modular JPMS app.
- **Leak tests on Tomcat specifically** — its classloader-leak detection turns the weak-reference discipline of §4.4 into a pass/fail signal rather than a code-review opinion (§15.8).
- **JMH benchmarks** in CI with a regression threshold.
- **Chaos test**: a rule that throws on every evaluation must produce unmodified log output and a disabled rule.
- **Distribution assertion**: a build-failing test that the customer-facing distribution contains no `logaperture-dev` artifact and no class from it. This is the check that makes the §9.11 guarantee real rather than aspirational, and it belongs in the build rather than on a release checklist.
- **Mode escalation tests**: assert that no sequence of runtime operations can raise the effective mode, that a `prod` policy file renders a present dev add-on inert, and that the mode banner cannot be suppressed by any rule including the agent's own.

---

## 13. Open questions and risks

1. **Is render-stage wrapping durable?** The whole architecture assumes encoder/layout wrapping is stable across framework versions. M0 exists to test that assumption. Fallback is instrumentation of `ThrowableProxyConverter` / `ThrowablePatternConverter`, with a much higher maintenance cost.
2. **Async appenders.** Logback's `AsyncAppender` and Log4j2's async loggers change when and on which thread rendering happens. Verify gate→render decision propagation in both, early.
3. **Loggers created before install.** Dynamic attach means loggers already exist. Context-wide filters handle this in both major frameworks, but appender wrapping needs a sweep of existing appenders plus a hook for appenders added later.
4. **Scope creep toward "log router".** Once events can be matched and rewritten, rerouting and sinks look tempting. Hold the line at the control plane boundary for 1.0.
5. **Naming — settled, with the reasoning recorded.** The project is **LogAperture**; the command is `logctl`; artifacts are `logaperture-*`. An aperture controls how much light passes in *both* directions, which is the only metaphor that covers a tool doing suppression and promotion at once. The bare word "Aperture" was rejected: FluxNinja Aperture is an Apache-2.0 flow-control platform publishing `com.fluxninja.aperture:aperture-java` to Maven Central, whose vocabulary (rate limiting, prioritization) overlaps this project's directly. That project appears to be winding down following its 2024 acquisition, but Maven coordinates are permanent. The compound disambiguates without losing the metaphor. Still to verify before the repository is public: GitHub org and repo availability, Maven Central groupId, no existing Apache or Eclipse project, basic trademark search, and whether `logctl` collides with an existing binary on common distributions — if it does, ship `logaperture` as the canonical name with `logctl` as an installed alias.
6. **License and governance.** Apache 2.0 is the ecosystem norm and compatible with Logback's EPL/LGPL dual license for the dependency direction involved — worth confirming with the actual license texts. DCO sign-off is lighter weight than a CLA for a project this size.
7. **Adapter count vs. depth.** Four adapters × N versions is a large matrix for a new project. Consider shipping v1 with Logback and Log4j2 only, with a documented, stable adapter SPI, and let JUL/Log4j1 follow. Depth on the two frameworks that cover the large majority of real deployments beats breadth.

---

## 14. Developer workflow — the primary use case

Two distinct problems, with different solutions. Conflating them is the main risk to the design.

**Problem A — signal loss.** A known set of warnings and errors scrolls past on every boot. The interesting event (did the deployment succeed? did *my* change take effect?) is buried in it.

**Problem B — friction.** Adjusting a level in WildFly means finding the logging subsystem nested in `standalone.xml`, or knowing CLI syntax most developers have never seen. So they write `System.out.println("!!!*** in doSomething ***!!!")`, because it takes four seconds and is guaranteed visible. Occasionally one ships.

### 14.1 The uncomfortable fact about Problem B

**Level control alone does not replace `println`.** The developer reaches for `println` precisely at the point where *there is no logging statement in the code*. Turning `com.acme.MyClass` up to DEBUG does nothing if `MyClass` never calls `log.debug()`. Any pitch built on "just use log levels instead" will be quietly ignored by the people it's aimed at, because they've already noticed this.

So Problem B decomposes into four separate interventions, and the project should be explicit about which it takes on:

1. **Make level changes trivially cheap** — one command, no PID, no config file, auto-expiring. Solves the case where a log statement exists but is below threshold. This is the project's job.
2. **Make the raised output visible** — a DEBUG line in a noisy console is still invisible. Highlighting is what actually replaces the `!!!***` markers. This is the project's job, and it's the piece that makes intervention 1 credible.
3. **Provide output where no statement exists** — dynamic method entry/exit tracing. Arthas, Byteman, and BTrace already do this well. *Recommend integration, not reimplementation.* A `logctl trace com.acme.Foo#bar` that shells out to or embeds an existing engine is defensible; building a fourth one is not.
4. **Prevent shipping** — see §14.4. Runtime detection helps you clean up; only static analysis actually stops it.

### 14.2 Promotion: the missing action family

The rule engine in §7 is really "match and transform". Suppression is one direction; promotion is the other, and it uses the same matchers.

| Action | Stage | Notes |
|---|---|---|
| `highlight` | render | Inject ANSI styling. WildFly's `COLOR-PATTERN` formatter already emits ANSI, so this composes naturally. Must degrade to a no-op when output is not a TTY. |
| `banner` | render | Surround the event with separator lines so it is impossible to scroll past. This is the sanctioned replacement for `!!!****`. |
| `pin` | side effect | Maintain a small set of "latest matching event per rule", queryable via CLI — e.g. "what was the last deployment result?" |
| `notify` | side effect | Terminal bell or desktop notification. Opt-in, rate-limited, off by default. |

A shipped WildFly rule pack should banner deployment success and failure messages out of the box, so the very first thing a new user sees is their `did it start?` question answered without configuration.

### 14.3 Known-noise baselining

This is the strongest available answer to Problem A, and it does not require anyone to hand-write squelch rules.

**Mechanism.** Fingerprint each event as `(logger name, level, normalized message template, throwable type)`. Normalization replaces the variable parts — digits, UUIDs, hex, timestamps, file paths, thread names, durations — with placeholders, yielding a stable message *shape*. Record the set of fingerprints observed during a known-good run.

**Modes.**
- `hide` — suppress everything in the baseline. Aggressive; good for a boot you've seen a thousand times.
- `dim` — render baseline events in a muted style, everything else normally.
- `highlight-new` — render normally, but banner anything whose fingerprint is not in the baseline.

**Why this is the high-value feature.** It inverts the problem. Instead of "which noise should I suppress?" — which requires knowing the noise in advance and writing a rule for each — it asks "what is new?", which is the actual question the developer has. `logctl baseline record` on a clean boot, commit `.logaperture/baseline.yaml` to the repo, and the whole team gets it.

**Details to get right.**
- Staleness: warn when the baseline is old, or when the match rate drops below a threshold — that indicates drift, and a stale baseline silently hides real regressions.
- Never baseline-hide anything on the protected-logger list (§9).
- Fingerprint stability across framework versions and message-catalog changes (WildFly's `WFLYxxxxxx` codes are actually a gift here — the code alone is a good fingerprint component).
- A CI application worth noting: fail an integration-test build when a new ERROR-level fingerprint appears that isn't in the baseline. That turns the baseline file into a reviewed artifact with a real gate behind it, which is what keeps it maintained.

### 14.4 The stray-`println` problem

**Runtime detection.** `System.setOut()` with a wrapping `PrintStream` — no bytecode instrumentation required — that attributes each write to its calling class via `StackWalker` and either (a) routes it through the logging framework tagged with its origin, or (b) accumulates a `class → write count` report retrievable from the CLI. Note that WildFly already redirects `System.out` to a logger, so the value here is *attribution*, not capture; the wrapper must compose with WildFly's redirection rather than fight it. A package allowlist is needed, since third-party libraries write to stdout legitimately.

**Prevention.** Be honest in the docs: a runtime agent cannot stop a `println` from shipping. Error Prone, SpotBugs, Checkstyle, and Sonar all have rules for this, and a pre-commit hook or a build-failing check is what actually closes the gap. The right framing for the project is:

> The agent removes the *motive*. A lint rule removes the *possibility*. Use both.

Shipping a small Checkstyle/Error Prone rule module alongside the agent is cheap, makes the story complete, and is the kind of thing that gets a project adopted at the org level rather than by individuals.

### 14.5 CLI ergonomics are the product

For this audience the CLI is not a convenience wrapper over JMX — it is the entire user experience, and it competes directly against four seconds of typing a println. Requirements:

- **No PID argument** when exactly one candidate JVM is running. Discover it.
- `logctl debug com.acme.Foo` — sets DEBUG, default 4-hour expiry (a working session, not a phone call — see [`doc/specs/cli-transport.md`](specs/cli-transport.md)), prints the expiry time in the confirmation.
- `logctl status` — what is overridden right now, by whom, why, and when it reverts.
- `logctl undo` — revert the last change. People experiment; make experimenting safe.
- `logctl quiet` / `logctl loud` — apply or drop the noise-suppression pack.
- `logctl new` — enable `highlight-new` baseline mode.
- **Shell completion over live logger names**, pulled from the running JVM. Developers frequently do not know the exact logger name, and this single feature removes a large part of the friction that sends them back to `println`.
- Default agent behaviour with no arguments: do nothing except expose the control plane. Zero-config must mean zero behaviour change.

An IntelliJ or VS Code plugin is the obvious later multiplier, but it should follow a CLI that already works.

---

## 15. Container and application-server environments

### 15.1 Two orthogonal axes

Earlier drafts of this document let WildFly colonise the architecture. It is worth stating plainly that there are two independent axes, and that conflating them is the main way this design goes wrong.

**Axis 1 — the logging backend.** Logback, Log4j 2, JBoss LogManager, JULI/JUL, Log4j 1. Determines *how* levels are read and written, how filters install, how formatters wrap. All the mechanism in §4.3.

**Axis 2 — the container.** Plain `java -jar`, Spring Boot, Tomcat, WildFly, Quarkus, Jetty. Determines *where* the logging contexts are, *when* they appear and disappear, *how* the agent is installed, and *what identity survives a redeploy*.

They multiply, but not evenly. Adapters carry almost all of the behaviour; containers carry almost all of the discovery and lifecycle. That asymmetry is what keeps the matrix affordable (§12), and it means two SPIs rather than one.

The axes also overlap in your favour: **JBoss LogManager covers WildFly *and* Quarkus JVM mode. Logback covers Spring Boot *and* most modern Tomcat WARs.** Two adapters reach four of the five target containers.

### 15.2 The `ContainerIntegration` SPI

```java
public interface ContainerIntegration {
  String id();
  boolean detect();                                 // is this container present?
  AggregateLevelControl activate(Instrumentation inst, CapabilityPolicy policy, AuditLog audit,
                                 Consumer<AggregateLevelControl> onFirstContextReady);
  default InstallGuidance guidance();               // where the -javaagent flag goes
}
```

> Implemented shape, as of `doc/specs/wildfly-support.md` Slice 1. The original sketch had
> `discoverContexts` plus `onContextAdded` / `onContextRemoved` callbacks and a
> `stableKey(ContextHandle)` method. Implementation moved the per-container composition
> root *into* each integration (it legitimately needs `logaperture-bridge` and class-load
> instrumentation, neither of which belongs in `core`), so the SPI collapsed to one
> `activate` call that returns the finished `AggregateLevelControl`. `stableKey` moved onto
> `ContextHandle` itself; "a context appeared / went away" became `AggregateLevelControl`'s
> `addContext` / `removeContext`, driven by each integration internally.

`stableKey` (now `ContextHandle.stableKey()`) is the one that earns its place. Overrides keyed on classloader identity evaporate on redeploy; keyed on a logical name — deployment name, context path, application name — they survive, which is what lets the aggregate re-broadcast still-active overrides onto a context after it is re-registered (`doc/specs/wildfly-support.md`, "The redeploy loop"). In the current release overrides are broadcast to every context rather than keyed per context in the store; `stableKey` is a redeploy-recognition identity, not yet a persistence key.

`detect()` must be non-invasive: resource and class-presence probing only, never speculative class loading, for the reasons in §15.6.

**A `none` integration is mandatory and comes first.** Plain `java -jar`: one classloader, one context, no lifecycle events. Build it before any real container, because it runs in milliseconds and it forces the SPI to be honest about which methods are genuinely optional.

### 15.3 Coverage

| Container | Usual backend | Container-specific work |
|---|---|---|
| Plain `java -jar` | any | None. The baseline and the fast test target |
| Spring Boot fat jar | Logback (default), Log4j 2 | Nested-jar classloader; `LoggingSystem` re-initialisation; devtools restart |
| Tomcat | JULI, plus whatever the WAR ships | Per-webapp classloader config; webapp reload; mixed frameworks in one JVM |
| WildFly | JBoss LogManager | Per-deployment `LogContext`; LogManager install timing; redeploy |
| Quarkus (JVM) | JBoss LogManager | `QuarkusClassLoader`; dev-mode live reload; build-time config baking |
| Quarkus (native) | — | **Not supported** — no JVM, no `-javaagent`, no `Instrumentation` |
| Jetty | varies | Minimal; largely the `none` path |

### 15.4 One JVM, several frameworks, at once

Tomcat makes this unavoidable: the container logs through JULI while the deployed WAR logs through its own Logback in `WEB-INF/lib`. Both are live, both need control, and a user raising a level neither knows nor cares which is which.

Consequences, which §4.4 treated as a classloader concern but which is really a first-class user-facing scenario:

- Adapters are instantiated per `(framework, context)` pair, never globally.
- `top`, `doctor`, and `status` aggregate across every adapter into a single view. A user must never have to ask the same question twice for two frameworks.
- A level change resolves to whichever context owns that logger name, with explicit disambiguation syntax only when more than one does.

### 15.5 Assume the hooks will be discarded

Every environment on the list throws away installed filters and wrapped formatters at some point:

- **WildFly** — logging subsystem reconfiguration; redeploy
- **Spring Boot** — `LoggingSystem` re-initialisation during startup; context refresh; devtools restart
- **Tomcat** — webapp reload
- **Quarkus** — dev-mode live reload
- **Every framework** — its own config auto-reload (Logback scanning, Log4j 2 `monitorInterval`)

Rather than enumerate hooks per container, make it an invariant of the core: **the agent must be able to re-establish its entire installed state, idempotently, at any moment, driven by either an event or a periodic verification sweep.**

Idempotence is the hard half. Double-wrapping a formatter on every reload is an easy bug, compounds silently, and is expensive on the hot path — tag wrappers with a marker interface and check before wrapping. The periodic sweep is the backstop for containers that offer no usable event, and it means adding a new container starts out working correctly, if slightly late, before anyone writes a single hook.

### 15.6 WildFly

> Implementation spec: [`doc/specs/wildfly-support.md`](specs/wildfly-support.md) — the JBoss LogManager adapter, the `ContainerIntegration` SPI, per-deployment contexts, and the redeploy loop, delivered in three slices.

WildFly installs **JBoss LogManager** as the `java.util.logging.LogManager` and routes deployment logging — SLF4J, Log4j, commons-logging, JUL — into it through the logging subsystem. That is a significant simplification: **one backend adapter covers most of a WildFly estate**, rather than four. The exception is a deployment using `use-deployment-logging-config` with its own bundled configuration, which gets its own isolated setup.

The relevant API surface is `org.jboss.logmanager.LogContext` and `LogContextSelector`. <cite index="4-1">A `LogContext` is a logging context that produces an isolated logging environment</cite>, and <cite index="1-1">WildFly creates a new `LogContext` per deployment, selected by the deployment's classloader</cite> — <cite index="3-1">`WildFlyLogContextSelector` registers and unregisters log contexts against the deployment module's classloader as deployments come and go</cite>. <cite index="8-1">`ContextClassLoaderLogContextSelector` chooses a context based on the thread context classloader, falling back to the system context when no match is found.</cite>

Practical consequences:

- Enumerate and hook **every** registered `LogContext`, not just the system one.
- `ExtLogRecord` (JBoss LogManager's `LogRecord` subclass) carries MDC, NDC, source class, and thread name — a richer matcher surface than plain JUL, and worth exposing in the rule model.
- Gate stage: `Logger.setFilter` and handler filters. Render stage: wrap the `java.util.logging.Formatter` on each handler — WildFly's `PatternFormatter` / `COLOR-PATTERN` are the ones that matter.

#### The premain gotcha that will cost you a day

WildFly sets `java.util.logging.manager=org.jboss.logmanager.LogManager`. If **anything** causes `java.util.logging` to initialize before that property takes effect, the default `LogManager` is installed instead and the server fails or logs incorrectly. An agent's `premain` runs early enough to trigger exactly this.

The agent must therefore touch no logging class during `premain`. Registration must be lazy — triggered by observing the JBoss LogManager class being defined — never eager. Make this an explicit invariant with a test that boots WildFly with the agent attached and asserts the correct `LogManager` is installed.

#### The redeploy loop

Developers redeploy constantly, and each redeploy discards and re-registers the deployment's `LogContext`. Overrides keyed on classloader identity evaporate. Key them on **deployment name / logging-profile name** instead, and re-apply on context registration. This makes the §6 persistence machinery load-bearing for the dev use case, not just the restart case — arguably it should be validated against redeploy before it's validated against restart.

#### Ephemerality

<cite index="9-1">A logging level change made through the WildFly CLI is written back into the XML configuration.</cite> That is the correct behaviour for a management tool and exactly the wrong behaviour for debugging: a developer bumps a level, forgets, and the change surfaces in a diff, a commit, or a built image.

The agent's overrides live in memory, expire on a timer, and never touch `standalone.xml`. State this in the first paragraph of the README — for a WildFly audience it is a sharper differentiator than the squelch engine.

#### Installation mechanics

- Attach via `JAVA_OPTS` in `standalone.conf` / `standalone.conf.bat`.
- JBoss Modules isolates aggressively; if any agent class must be visible to deployment classloaders, its package prefix has to be appended to `jboss.modules.system.pkgs`. Verify empirically in the M0 spike and document the exact line, because a wrong answer here presents as baffling `ClassNotFoundException`s.
- Domain mode: out of scope for v1. Say so explicitly.

### 15.7 Spring Boot

- Backend is Logback by default, Log4j 2 optionally. The fat jar uses a nested-jar classloader whose URLs do not resolve to real files — the agent must not assume they do.
- **Spring Boot owns Logback**, through its `LoggingSystem` abstraction, and initialises it in phases, re-initialising once the environment is prepared. Hooks installed during `premain` will be discarded. Re-apply on the environment-prepared or context-refresh events, or fall back to the §15.5 sweep.
- Actuator's `/loggers` endpoint already provides level control, so the overlap here is the largest of any container. The differentiators are the persistence tiers, expiry, storm collapse, rules, and not requiring Actuator to be present and exposed. Say so honestly rather than pretending the gap is bigger than it is.
- Devtools' restart classloader is another discard event, and a good cheap test of the sweep.
- An executable WAR deployed into Tomcat or WildFly is a hybrid: detect on the *container*, never on the packaging.

### 15.8 Tomcat

- JULI (`org.apache.juli.ClassLoaderLogManager`) keys logging configuration by classloader, giving per-webapp contexts structurally similar to WildFly's `LogContext`. Much of the WildFly discovery logic generalises.
- Expect mixed frameworks (§15.4) as the normal case, not the exception.
- Install via `CATALINA_OPTS` in `setenv.sh`.
- Webapp reload discards the context. Usefully, Tomcat's own classloader-leak detection will complain loudly if the agent retains references — which makes the weak-reference discipline of §4.4 empirically testable rather than theoretical. **Tomcat is therefore the best environment in which to catch agent memory leaks**, and worth adding to CI early for that reason alone, independent of how many customers run it.

### 15.9 Quarkus

- JVM mode is built on JBoss LogManager, so the WildFly adapter largely applies. This is the cheapest second container you will ever add, and worth doing early purely to prove the axes of §15.1 are genuinely separable.
- `QuarkusClassLoader` and the fast-jar layout; dev mode restarts the runtime classloader on live reload.
- Quarkus bakes much of its configuration at build time, so runtime mutability is more constrained than WildFly's. Set expectations in the documentation rather than letting users find the edges.
- **Native image is not supported and cannot be.** A GraalVM native executable has no JVM, no `-javaagent`, and no `Instrumentation`. State this in the README instead of letting people discover it. If it ever matters, the only viable route is a build-time Quarkus extension — a different project with a different architecture.

### 15.10 Never mutate the container's configuration

Stated for WildFly above, and it generalises to every target: no tier of §6.1 writes to `standalone.xml`, `logging.properties`, `logback-spring.xml`, `application.properties`, or any other file the container or the application owns. Agent state lives in the agent's own store.

This is a promise none of the built-in mechanisms can make (§3), and on a customer deployment it is the whole difference between a reversible action and a support incident.

---

## 16. Constrained and customer-hosted environments

The developer runs on a workstation with a terminal and disposable state. The customer runs on a box that may have 40 GB of disk, a small heap, no shell tooling, and no network path to your support team. Almost every assumption changes.

### 16.1 Measure before you suppress

The first question when a customer says "my disk filled up" is *what generated the volume*, and today nobody can answer it without parsing the file. The agent is uniquely positioned to answer it precisely, and this is probably the single most valuable operations feature.

`logctl top` — bytes emitted per logger over a window, sorted, with a projected daily total. Extend to per-throwable-type and per-rule. Byte counting happens at render stage, where you are already wrapping the formatter or encoder, so it is nearly free.

Make the output directly actionable: alongside the top offender, emit the rule that would fix it.

```
org.apache.http.impl.conn   412 MB/h   (9.6 GB/day)   98% stack traces
  suggested: trimStackTrace on org.apache.http — would save ~9.4 GB/day
```

A generated rule stub the user can paste is worth more than a paragraph of documentation.

### 16.2 `logctl doctor` — diagnose the configuration

Multi-GB single files usually mean rotation is misconfigured, not that suppression is missing. Before building any of the rule engine, a read-only checker that flags the common causes would resolve a meaningful share of real incidents:

- File handlers with no size cap, or a `max-backup-index` that permits unbounded total growth
- DEBUG or TRACE left enabled at the root, or on a known-chatty framework logger
- The same content written twice (console *and* file handler at the same level, or duplicate appender references)
- Autoflush on a high-volume handler
- Available disk space on the log volume measured against current write rate, with a time-to-full estimate

This is a few days of work, is read-only, and needs no rule engine. **Read-only diagnostic tools get into customer environments far faster than tools that change behaviour** — which makes `doctor` and `top` the natural first release, and the thing that tells you which suppression features actually matter before you build them.

Adjusting rotation at runtime (`logctl rotate --max-size 50M --keep 3`) is a legitimate follow-on and much easier than editing `standalone.xml` on a customer machine. Implementing rotation is not — every framework already has it.

### 16.3 Volume budgets and adaptive degradation

A primitive distinct from per-rule matching: bound the bytes, and let the tool decide how.

```yaml
budgets:
  - handler: FILE
    maxBytesPerHour: 200MB
    escalate:                    # each stage engages only if the previous is insufficient
      - trimAllStackTraces
      - dedupeAggressively
      - raiseRootLevel: WARN
    announce: true
    relaxAfter: 30m
```

Each stage announces itself in the log when it engages *and* when it relaxes. Silent behaviour change is how a tool like this destroys trust — the customer must be able to see, in the log itself, that the log is being managed.

### 16.4 The disk guard

Poll `FileStore.getUsableSpace()` on the log volume. On crossing a conservative threshold, escalate hard: trim all traces, root to WARN, then ERROR. Announce loudly at each step and record to the audit trail.

Default this **on**, with generous thresholds. The failure it prevents — an application killed by a full disk on a machine nobody is watching — is severe, unrecoverable without intervention, and the exact scenario the customer will blame you for. It is the strongest argument for the whole project in a constrained deployment, and worth leading with in customer-facing material.

### 16.5 Triage capture — bounded, packaged, self-reverting

The support workflow, as one command:

```
logctl capture --profile datasource --for 10m --max-size 20M --out bundle.zip
```

Four properties, all load-bearing:

- **Vendor-supplied named profiles**, not raw logger names. A support engineer should not need to know that the relevant logger is `org.jboss.jca.core.connectionmanager`. Shipping a versioned set of diagnostic profiles turns tribal knowledge into a reviewable artifact — for a product support organisation this may be the highest-leverage feature in the project.
- **Hard-bounded** by time *and* bytes, stopping at whichever comes first. A capture must never itself become the disk-filling event.
- **Self-reverting**, enforced by the agent, surviving the support engineer closing their laptop and the JVM restarting. This is the expiry machinery from §5 doing its real job. It converts "we asked the customer to turn on DEBUG and they left it on for six months" from a recurring incident into something that cannot happen.
- **Redacted on the way out**, using the same `redact` rules, because the bundle leaves the customer's premises. Compressed and size-bounded so it can be emailed.

### 16.6 Vendor-authored, customer-applied

Consider a deployment mode where rule packs and profiles ship from you and the local user may apply but not author them (`logaperture.rulesReadOnly=true`, rules loaded only from a designated directory). This keeps the support surface predictable, and prevents a customer from silencing output you will later need. Pair it with the protected-logger list (§9) so neither side can suppress audit or security categories.

### 16.7 Footprint is a feature

The customer JVM may have a small heap, and the agent's own memory is a real constraint rather than a rounding error:

- **All state must be size-bounded.** Dedupe maps, rate-limit windows, fingerprint sets, byte counters — every one of them is keyed on something unbounded (message shapes, logger names) and every one is a memory leak with a plausible-looking cause if left uncapped. LRU with an explicit, configurable cap, on all of them.
- Degrade by dropping *agent* state before dropping *log lines*.
- Publish a measured footprint in the README. "Adds under 5 MB and under 1% CPU at 10k events/sec" is a number this audience will look for and that no competing tool advertises.

### 16.8 Named as out of scope

"Too large to open in an editor" is a genuine problem on a customer machine with no `less` and no `tail`, and it is not the agent's job. If it becomes pressing, a small separate slicing utility in the same repository is defensible. Building a log viewer into a logging control agent is not.

---

## 17. Roadmap

The persona ordering has now shifted twice. Rather than reshuffle again, structure the work in layers so that the sequencing question becomes "which layer next" rather than "rewrite the plan".

**Layer 0 — Core.** Adapter SPI, JBoss LogManager adapter, classloader model, rule matchers, level control with enforced expiry, persistence, CLI transport, **and the capability and audit model of §9**. Authorization belongs here and nowhere else: a capability check added after the operations exist is a capability check with holes in it, and the sealed-at-boot settings (§9.4) constrain the shape of every API above them. Building §9 into M1 costs days; retrofitting it costs a redesign.

**Layer 1 — Measurement.** `top`, `doctor`, per-rule metrics, dry-run. Read-only. Useful to all three personas immediately.

**Layer 2 — Volume.** Gate-stage actions, `trimStackTrace`, budgets, disk guard, capture profiles. Serves support and customer.

**Layer 3 — Developer.** Highlight, banner, baselining, shell completion, CLI polish. Serves developers.

| | Scope | Exit criterion |
|---|---|---|
| **M0** | Spike against three points on the grid, deliberately chosen to keep both axes of §15.1 honest: plain JVM + Logback (the `none` baseline), WildFly + JBoss LogManager (the hardest container), Spring Boot + Logback (same adapter, different container). Enumerate contexts, set a level, install a filter, wrap a formatter. | All three work end to end from one adapter/container SPI pair, and WildFly boots correctly with the agent attached (§15.6). |
| **M1** | Layer 0 + Layer 1. Level control, three persistence tiers, CLI, `top`, `doctor`, **storm detection in report-only mode** (§7.1). | A read-only diagnostic release you can hand to support and to a customer without approval anxiety. This is a genuinely useful v0.1 on its own. |
| **M2** | Layer 2 first half: **automatic storm collapse** turned on, then gate-stage actions and `trimStackTrace`. Storm collapse first — it addresses the failure mode that actually fills disks, and needs no rules authored. | A tight-loop exception bug produces one full trace and a running count instead of gigabytes. |
| **M3** | Layer 2 second half: budgets, disk guard, capture profiles, redaction. | A support engineer captures a bounded, redacted bundle and the levels revert without anyone remembering to. |
| **M4** | Layer 3: highlight, banner, baselining, WildFly boot rule pack. | A clean boot on your project shows fewer than ten lines. |
| **M5** | Persistence hardening across redeploy and restart; stray-stdout attribution + companion lint rule. | |
| **M6** | Second and third adapters at depth, HTTP control plane, protected loggers, audit, 1.0. | |

The important change from the previous draft: **M1 ships nothing that modifies behaviour.** A tool that only measures is dramatically easier to get installed — on a customer site, in a support process, and in your own build — and the data it produces tells you which of Layers 2 and 3 to build first, rather than guessing from here.

---

## 18. Future enhancements

Deliberately post-1.0. Recorded now because several of them constrain decisions made earlier — §18.1 in particular is not really a future item at all.

### 18.1 The unifying principle: ride-along integrations

Items §18.2 to §18.5 are the same play four times. Each one borrows **distribution**, **security**, and **an existing user base** from a host that already has all three, in exchange for conforming to that host's plugin model. The leverage per unit of effort is high — and entirely contingent on two things being right first.

**They all require §8.1 to hold absolutely.** Every integration must be a client of the same command model, with no privileged path and no reaching into internals. Five plugins each poking at their own entry points is five things to break on every refactor. The prospect of a plugin ecosystem is the best available forcing function for getting that boundary right, and the worst possible thing to build on top of a boundary that is still soft.

**Host authenticates; the agent still authorizes.** Riding along on Hawtio's or an IDE's authentication is legitimate and saves real work. It does *not* transfer the capability model (§9.3) to the host. The agent must independently refuse anything the policy forbids, regardless of how convincingly the caller was authenticated upstream. A host that says "this user is an administrator" is answering a different question from "is this operation permitted on this machine". Trusting the host with authorization would also mean a customer's policy could be bypassed by installing a different console — which is precisely the property §9.2 exists to prevent.

**They also fragment the maintenance surface.** NetBeans platform APIs, Hawtio's Module Federation setup, the VS Code extension API, and the Quarkus extension SPI all evolve on schedules you do not control, in toolchains that are mostly not Java. Each is a standing tax. Pick one, do it properly, and let demand justify the second.

### 18.2 Quarkus extension

The idiomatic path is a Quarkus *extension* rather than a bare Maven plugin — `quarkus ext add logaperture` is about as low as the barrier to entry gets, and the extension catalogue is a genuine discovery channel.

Two things make this more interesting than it first appears:

**It forces the engine to be embeddable, which is worth doing regardless.** Inside a Quarkus application there is no need for a javaagent at all — the process already owns its JBoss LogManager contexts, so the extension can drive `logaperture-core` directly as a library. That should be true by construction: **the agent is one delivery mechanism for the engine, not the engine itself.** The module split in §4.6 already implies this; make it an explicit constraint and test it, because it is what makes a Spring Boot starter, an extension, or any other in-process integration cheap later. If `core` ever acquires a dependency on `agent`, that door closes quietly.

**Quarkus solves the dev-mode problem for you.** Its Dev UI is present only in dev mode, stripped from production builds by build-time augmentation — which is exactly the model §9.11 borrows. A Dev UI card would inherit that guarantee from the platform, along with a Dev UI implementation that has already been through the CVE-2022-4116 hardening (§9.11.1) rather than one you would have to harden yourself.

### 18.3 Hawtio plugin

Best audience fit for the WildFly, Camel, and Fuse ecosystem, which is where this project's first users are likely to come from.

Hawtio reaches JVMs over Jolokia — JMX exposed as HTTP and JSON — which means a Hawtio plugin is a thin frontend over the MBean surface that §8.1 already designates as the reference implementation. If that MBean is complete, the plugin is genuinely mostly presentation.

Two caveats worth recording:

- **It is a frontend project, not a Java one.** Current Hawtio plugins are TypeScript modules loaded through Webpack Module Federation. Different skill set, different toolchain, separate release cadence. Budget it as such rather than as "a bit more Java".
- **Hawtio implies a listening endpoint in the JVM**, which §8.2 says the agent must never add. The distinction is real and worth stating precisely in the documentation: the customer chose to deploy Hawtio and Jolokia; LogAperture did not open that port and does not require it. The agent's "opens no network connections" property survives intact, because the surface belongs to something else the operator installed deliberately.

### 18.4 VisualVM plugin

Probably the cheapest of the four. NetBeans-platform modules are Java and Swing — the same skill set as the rest of the project — and the VisualVM Plugins Center is a real distribution channel to exactly the developer audience of §14.

It also delivers, essentially free, the desktop GUI considered and set aside in §8.4: no browser, no listener, connecting over local attach with the same UID gating the CLI relies on. That makes it complementary to the TUI rather than redundant — the TUI serves a support engineer inside an SSH session, the VisualVM plugin serves a developer at a workstation.

The standing cost is that VisualVM's plugin API tracks NetBeans platform releases, and plugins need revalidation against them.

### 18.5 IDE integrations

**The value here is not the interface — it is the context.** An IDE knows the fully-qualified name of the class under the cursor. "Set this class to DEBUG for fifteen minutes," from a right-click, with no name to type and no logger to look up, is the single interaction that most decisively beats typing `System.out.println` (§14.1). A logger-browsing panel is a distant second; if only one feature ships, ship the right-click.

On ordering: **IntelliJ IDEA is missing from the original list and probably has the largest Java user base of the three.** VS Code is the cheapest to build and publish. Eclipse has the smallest share overall but is disproportionately present in exactly the enterprise WildFly shops this project targets first, so it may still be worth doing out of sequence.

### 18.6 Other candidates

- **The `downgrade` action** deferred from §7.2 — event mutation rather than gate or render, and the hardest of the three stages.
- **A community rule-pack registry.** Shared noise profiles for Hibernate, Apache HttpClient, WildFly boot, and so on. Contributing a rule pack is a far lower barrier than contributing code, and it is how a project like this acquires contributors.
- **Metrics export** — the per-rule and per-logger counters through Micrometer or OpenTelemetry, for deployments that do have a pipeline.
- **A container and Kubernetes story.** Ephemeral filesystems break the §6.3 file-based state store, and logs go to stdout rather than to a disk that can fill — the volume concern survives but its symptom becomes cost rather than outage.
- **Upstream contribution.** As noted in §3.2, if storm collapse belongs in Log4j 2 or Logback core, contributing it there reaches far more users than this project will.

---

## 19. First deliverables

1. This spec in the repo as `docs/design.md`.
2. The M0 spike as a throwaway branch, answering §13.1 and §15.6 before any architecture is committed to.
3. A `README.md` whose opening example is a WildFly boot log reduced to five lines with the deployment result bannered. For this audience that image sells the project faster than the trimmed-stack-trace example does.
4. A written threat model and compliance control mapping (§9.12), drafted *before* the capability enumeration is frozen — writing down the abuse cases is what reveals which capabilities need splitting.
5. An honest "Related projects" section covering Arthas, Byteman, and the WildFly CLI. Projects that name their neighbours accurately get taken more seriously than ones that don't.
