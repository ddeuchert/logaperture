# Spike — Quarkus JVM mode

Status: **done.** Every question is answered except those listed under "Not established". The
recommendation for the Oct 4, 2026 decision is at the end.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §15.1–15.5 (container SPI, "assume
the hooks will be discarded", the verification sweep), §15.9 (Quarkus), §18.2 (Quarkus extension,
out of scope here). Related: [`doc/specs/wildfly-support.md`](../specs/wildfly-support.md)
("Detection and the premain gotcha"),
[`doc/specs/wildfly-deferred-handler-install.md`](../specs/wildfly-deferred-handler-install.md),
[`early-handler-install.md`](early-handler-install.md).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the harness that produced it. The harness is not committed. See "Harness" below.

## Question

What would it take for LogAperture to support Quarkus in JVM mode? Do the existing JUL adapter
and the WildFly readiness gate (`WildFlyLogManagerReadiness`) work there unchanged?

## Environment

One machine. Every result below comes from it.

- Linux (Fedora, kernel 7.2), Temurin JDK 21.0.12, Maven 3.9.16.
- Quarkus **3.39.5** (the latest 3.x Final on Maven Central on 2026-09-27; 3.40.0.CR1 was
  available but skipped). JBoss LogManager 3.2.2.Final and JBoss Logging 3.6.3.Final, as the
  Quarkus BOM pins them.
- App: `quarkus create` with the `rest` extension only, plus one resource `GET /log?tag=` that
  logs TRACE/DEBUG/INFO/WARN and ERROR (with an exception) through JBoss Logging on category
  `org.example.Work`, and FINEST/FINE/INFO through `java.util.logging` on `org.example.Jul`.
  It returns `isTraceEnabled()`, `isDebugEnabled()` and JUL `isLoggable(FINEST)`.
- Launched directly with `java -javaagent:… -jar quarkus-run.jar` (fast-jar, the default), and
  `java -jar …-runner.jar` for the uber-jar. No Docker.
- LogAperture: `develop` at `e1109f6`, plus the throwaway integration described under "Harness".
- Most runs also enabled Quarkus's file handler and one named handler, both runtime properties:
  `-Dquarkus.log.file.enabled=true`, `-Dquarkus.log.handler.file."AUDITFILE".*` and
  `-Dquarkus.log.category."org.example.Jul".handlers=AUDITFILE`.

Note: another agent-attached JVM (a dev WildFly) was running on the same machine, so every `logctl`
call used `--pid`.

## Findings

### 1. Boot safety

- **The stock `develop` agent does nothing on Quarkus, and it breaks nothing.** No integration
  but `none` detects, and `none` waits for Logback, which never loads. The app boots with its own
  logging intact, `logaperture.version` is never set, and `logctl` never lists the JVM.
- **With a Quarkus integration bound, the app still boots with its own logging intact** (banner,
  format, console and file output), in fast-jar, uber-jar and dev mode. Quarkus's own
  "started in" time was 0.93–1.0 s with the agent and 1.07–1.12 s without. That is run-to-run
  noise; no startup cost was measurable.
- **Who sets `java.util.logging.manager`, and when:**
  - *fast-jar:* the first statement of `io.quarkus.bootstrap.runner.QuarkusEntryPoint.main` is
    `System.setProperty("java.util.logging.manager", org.jboss.logmanager.LogManager.class.getName())`.
    The class literal loads `org.jboss.logmanager.LogManager` at that moment, but does not
    initialise JUL. That happens **after** `premain`: premain ran at about +0.92 s after JVM
    start, and the property appeared at about +1.02–1.04 s. The generated
    `io.quarkus.runner.ApplicationImpl` static initialiser then sets the property again,
    **together with `logging.initial-configurator.min-level`**, about 45–55 ms later. JUL itself
    (`org.jboss.logmanager.LogContext`) is first initialised about 150–200 ms after `main` starts.
    Timeline of one run (1 ms polling, 4 runs agree within ±25 ms):
    `jul.manager prop +1019ms`, `JBoss LogManager class +1020ms`, `ApplicationImpl +1063ms`,
    `min-level prop +1066ms`, `LogContext +1189ms`, `InitialConfigurator +1192ms`.
  - *uber-jar:* `Main-Class` is `io.quarkus.runner.GeneratedMain`. Only the `ApplicationImpl`
    static initialiser sets the property, as a string, so the JBoss LogManager class is not loaded
    until JUL is actually initialised.
  - *dev mode:* the property is set by the dev-mode launcher. JUL is initialised before the
    application class exists.
- **Is `org.jboss.logmanager.LogManager` visible to the system classloader?** Yes, in both
  layouts. In fast-jar, `quarkus-run.jar`'s manifest `Class-Path` lists every `lib/boot/*.jar`,
  including `jboss-logmanager` and `quarkus-bootstrap-runner`. The readiness gate saw the class
  defined by `ClassLoaders$AppClassLoader`. In uber-jar everything is flat on the system
  classpath. The application's own classes (`lib/main`, `app/`) are behind
  `io.quarkus.bootstrap.runner.RunnerClassLoader`, and the agent never needs them. The one
  exception is reading Quarkus config for handler names (finding 6).

### 2. The readiness gate is *not* safe unchanged: it can initialise JUL before Quarkus is ready

The WildFly gate fires when `java.util.logging.manager` names JBoss LogManager and that class is
loaded. It then makes the **first** JUL call itself. On fast-jar both conditions become true on
line 1 of `QuarkusEntryPoint.main`. That is about 150 ms *before* Quarkus initialises JUL itself,
and about 50 ms *before* Quarkus sets the build-time min-level floor.

JBoss LogManager 3.x fixes each logger node's floor once, when the node is created
(`LoggerNode.effectiveMinLevel` is `final`). It reads the floor from the
`LogContextInitializer`. Quarkus's initializer (`io.quarkus.bootstrap.logging.InitialConfigurator`)
reads the floor from the system property `logging.initial-configurator.min-level` in its static
initialiser. If our first JUL call wins the race, the floor is permanently `ALL` instead of the
built `DEBUG`. **The agent has then changed the application's logging semantics**, and whether it
does depends on timing.

| Gate | Poll | Runs | `InitialConfigurator.MIN_LEVEL` after boot |
|---|---|---|---|
| WildFly, unchanged | 100 ms (the default), no offset | 8 | `FINE` (correct) in 8 |
| WildFly, unchanged | 100 ms, detector start delayed 0–90 ms (stands in for other agents' premain time) | 21 | **`ALL` in 3** (3 of the 4 runs at a 10 ms offset), `FINE` in 18 |
| WildFly, unchanged | 50 / 25 / 10 / 5 ms | 9 | `FINE` in 9 |
| WildFly, unchanged | 1 ms | 2 | **`ALL` in 2** |
| WildFly + "JUL already initialised" (below) | 1 ms and 100 ms | 16 | `FINE` in 16 |
| WildFly, unchanged, uber-jar | 1 ms and 100 ms | 2 | `FINE` in 2 |

When the race was won, `logctl set logger org.example.Work TRACE` produced TRACE output that the
built app would otherwise never show. In one sense that is "better". But it is an agent side
effect that depends on another agent's `premain` duration or on machine speed, which is exactly
the kind of behaviour §15.6's premain rule exists to prevent. A bigger app has a bigger
`quarkus-application.dat` and more jars, so the window between `main` and the min-level property
widens, and a stock 100 ms poll lands in it more often.

**The fix is one more class-presence condition:** also wait until the application itself has
started initialising JBoss LogManager, signalled by `org.jboss.logmanager.LogContext` being
loaded. With that condition the gate is never the first JUL caller. It then only joins an
initialisation the application already began (JVM class-init locking makes the concurrent call
safe). The first JUL call, the TCCL handling and the "installed manager is JBoss" check are
otherwise unchanged:

```java
// in the Supplier<Class<?>> passed to awaitJBossLogManagerThen(...), after the existing checks
Class<?>[] all = inst.getAllLoadedClasses();
Class<?> manager = findClassByName(all, "org.jboss.logmanager.LogManager");
if (manager == null) return null;
if (findClassByName(all, "org.jboss.logmanager.LogContext") == null) return null; // app hasn't initialised JUL yet
return manager;
```

Everything else in `WildFlyLogManagerReadiness` is WildFly-agnostic, as the hypothesis said:
the poll, the timeout, TCCL = the manager class's loader (here the app classloader), and the
"installed manager is JBoss" check. `WildFlyContainer` also worked unchanged as the Quarkus host.
Its only WildFly-specific part is `CONTAINER_NAME`, which is why `logctl env` printed
`Framework/container  WildFly`.

Whether the extra condition is also right for **WildFly** was not tested (see "Not
established"). The shared gate must be regression-tested on both.

### 3. Level control end to end

With the fixed gate:

| Operation | Result |
|---|---|
| `set logger org.example.Work DEBUG`, hit endpoint | DEBUG line on console and in the file; `isDebugEnabled()` true |
| `set logger org.example.Work TRACE` (default build) | **no TRACE output**; `isTraceEnabled()` stays false; `logctl` reports success and `list loggers` shows `EFFECTIVE TRACE` (see finding 4) |
| `set logger org.example.Jul TRACE` | JUL `FINE` appears; `FINEST` does not (it is below TRACE=`FINER`, and below the floor) |
| `list loggers org.example --show-all` | correct names and configured/effective levels |
| `set logger … DEBUG for 10s` | worked; reverted by the expiry sweep, and the next request had no DEBUG |
| `reset handler ALL_HANDLERS` / `reset rules` | worked |
| `set logger org.example.Jul DEBUG sticky`, restart | resumed at install; `FINE` shown on the first request after the restart |
| sticky `drop` rule, restart | resumed **but not effective for up to one sweep interval** (finding 5) |
| `add rule trim org.example.Work --below OFF --frames 1` | stack trimmed to one frame on console and file |
| `add rule drop org.example.Work --message-contains WARN-` | first match let through (sample-full, by design), later matches dropped on console and file |
| `set handler ALL_HANDLERS WARN` | INFO suppressed everywhere (it gates the delayed handler, below) |
| `top` | **only counted `org.example.Jul`**, whose named file handler is on the category; the main `quarkus.log.file` handler was not counted (finding 5) |
| `doctor` | ran; saw only the category-attached handlers; flagged Quarkus's default `autoflush` as a WARN on both of them |
| `list handlers --show-all` | **only `ALL_HANDLERS` and `DEFAULT_HANDLERS (auto: QuarkusDelayedHandler@…)`** (finding 6) |

**Handler-floor gap (new).** With `-Dquarkus.log.console.level=INFO`, `set logger
org.example.Work DEBUG` produced DEBUG in the file but not on the console, **and printed no
handler-floor hint**. `set handler ALL_HANDLERS DEBUG` did not help either. The console handler is
nested inside `QuarkusDelayedHandler`, so neither the hint nor `ALL_HANDLERS` fan-out reaches it.
This is the same user problem `handler-floor-control.md` solved for WildFly, hidden one level
deeper.

### 4. Build-time `quarkus.log.min-level`

- **Mechanism: a floor in the log context, not call-site elimination.** At build time the value
  is written into the generated `ApplicationImpl` static initialiser as
  `System.setProperty("logging.initial-configurator.min-level", "500")` (500 = DEBUG). Quarkus's
  `LogContextInitializer` (`InitialConfigurator.getMinimumLevel(name)`) returns it for every
  logger. JBoss LogManager stores it per node as a `final effectiveMinLevel`, and
  `LoggerNode.isLoggableLevel` tests `level >= effectiveMinLevel && level >= effectiveLevel`. So
  a logger level below the floor can be set and read back, but nothing below the floor is ever
  loggable: `isTraceEnabled()` is false, and records are never created.
- **Default build (min-level DEBUG):** `set logger X TRACE` produces **no** TRACE output
  in every run where the floor was intact. DEBUG works.
- **Build with `quarkus.log.min-level=TRACE`:** the property is `400`. `set logger X TRACE`
  produces TRACE output.
- **Per-category `quarkus.log.category."X".min-level` is different.** At runtime init
  `LoggingSetupRecorder` only *promotes the category's configured level* up to it, with a
  "promoting it to" warning. It is not a floor. Our override bypasses it: with category min-level
  `INFO` on `org.example.Work`, `set logger org.example.Work DEBUG` produced DEBUG output. That is
  arguably what a debugging tool should do, but it must be documented.
- **No call-site elimination in JVM mode** was observed. Both the TRACE build and the
  per-category bypass showed the call sites still executing.
- **Detectable by a runtime tool, generically:** after applying a level, the adapter can ask
  `Logger.isLoggable(level)`. JBoss LogManager's answer includes the floor, and plain JUL's does
  not need it. When the answer is false after a successful set, `logctl set` can warn and
  `doctor` can report it. No Quarkus-specific code is needed. The Quarkus-specific explanation
  ("rebuild with `quarkus.log.min-level=TRACE`") can key off the system property
  `logging.initial-configurator.min-level`, which is readable with `System.getProperty`. Today
  `list loggers` reports `EFFECTIVE TRACE` for a logger that cannot emit TRACE. That is misleading
  on any backend with a floor.
- *Dev mode* runs with floor `ALL`, as Quarkus's own behaviour: `min-level prop=null`, and the
  fixed gate was not the first JUL caller. TRACE worked there.

### 5. Startup reconfiguration and the handler wrappers

Handler topology on Quarkus, captured by the harness's reporter:

```
at our install (+1.5 s, fixed gate):
  logger '' -> QuarkusDelayedHandler   filter=JulRuleFilter          (no children yet)
after Quarkus runtime init (+2.5 s):
  logger '' -> QuarkusDelayedHandler   filter=LogCleanupFilter        <- our filters discarded
    child -> ConsoleHandler            formatter=TextBannerFormatter
    child -> SizeRotatingFileHandler   formatter=PatternFormatter     (quarkus.log.file)
  logger 'org.example.Jul' -> SizeRotatingFileHandler (AUDITFILE)
after the first verification sweep:
  logger '' -> QuarkusDelayedHandler   filter=JulRuleFilter
    child -> ConsoleHandler            formatter=JulTrimFormatter>TextBannerFormatter
    child -> SizeRotatingFileHandler   formatter=JulTrimFormatter>PatternFormatter
  logger 'org.example.Jul' -> SizeRotatingFileHandler formatter=ByteCountingFormatter>JulTrimFormatter>PatternFormatter filter=JulRuleFilter
```

- **Quarkus discards our install.** `LoggingSetupRecorder.initializeLogging` calls `setFilter` on
  the root `QuarkusDelayedHandler`, replacing our rule and storm filters with its
  `LogCleanupFilter`. It then attaches the real handlers as the delayed handler's children
  (`setHandlers`, which also activates it and replays the records queued since boot). Our install
  necessarily runs before this, because the fixed gate fires at about +1.2 s and runtime logging
  init follows.
- **The verification sweep re-establishes everything, idempotently.** No double wraps were seen
  over 8 ticks. Our filters wrap `LogCleanupFilter`, so Quarkus's own cleanup still applies. Trim
  reached the nested console and file handlers through the #80 `SubHandlers` walk. Drop works
  because `ExtHandler.publish` consults the delayed handler's filter before fanning out.
- **How long:** until the first sweep tick. With `-Dlogaperture.sweep.seconds=5` that was about
  +6.5 s after JVM start. With the **default 30 s**, a sticky drop rule leaked every matching
  event for the first ~30 s: with one request a second, the first 20 WARNs got through and the next
  20 were dropped.
  The JBoss LogManager configuration listener registers successfully but **never fires**, because
  Quarkus configures programmatically and never calls `readConfiguration`.
- **An earlier hook exists and works:** `QuarkusDelayedHandler.isActivated()` (public). It turns
  true inside `setHandlers`, after Quarkus has installed its filters and handlers. The harness
  polled it every 25 ms after install and then called `runVerificationSweepNow()`. Wrappers were
  back at +1.77 s, *before* Quarkus's "started in" banner, and the first request's WARN was
  already dropped (0 leaks). A container-agnostic alternative is a fast sweep, for example every
  250 ms for the first 10–20 s after install. That would help WildFly and Spring too, but it is
  less exact.
- **`top` misses the main log file.** `installByteCounting` wraps only `realHandlers()`
  (handlers attached to a logger). Quarkus's `quarkus.log.file` handler is a *child* of the
  delayed handler, so it is never counted. Only category-attached named handlers are counted.
  Trim got the sub-handler walk in #80; `top` (and `doctor`'s file-handler checks) did not. The
  same probably applies to WildFly `AsyncHandler` children (not checked).
- **Dev-mode live reload** did not touch logger levels (a SESSION TRACE override survived). It did
  discard the rule filter (one WARN leaked right after the reload), and the next sweep put it back.

### 6. Handler names

`logctl list handlers` shows only `ALL_HANDLERS` and `DEFAULT_HANDLERS (auto:
QuarkusDelayedHandler@…)`. On JBoss LogManager the JUL adapter advertises only handlers whose
names resolved (issue #13/#14), and with `HandlerNameResolver.NONE` nothing resolves. Even with
names, the useful handlers (console, file) are nested inside `QuarkusDelayedHandler`, and
`realHandlers()` deliberately excludes sub-handlers.

Recovering Quarkus's names is feasible from the agent:

- **Unnamed defaults** (`quarkus.log.console|file|syslog|socket`): Quarkus allows at most one of
  each, and they are the delayed handler's children, so the name follows from type. Console is a
  `ConsoleHandler`; file is a `FileHandler` subclass, which also exposes `getFile()` to match
  against `quarkus.log.file.path`; and so on.
- **Named handlers** (`quarkus.log.handler.<type>."<name>"`): attached to a category logger in
  the order of `quarkus.log.category."<cat>".handlers`, or to the root via
  `quarkus.log.handlers`. The harness read these from the agent at runtime with
  `ConfigProvider.getConfig(loader)`, where `loader` is `ConfigProvider`'s own defining loader
  (`RunnerClassLoader`), found through `getAllLoadedClasses()`. It printed, for example,
  `quarkus.log.category."org.example.Jul".handlers = AUDITFILE` and
  `quarkus.log.handler.file."AUDITFILE".path = logs/audit.log`. That is enough to map a handler
  to its configured name through its category, its position, and its type or path.
- Either way, the adapter has to treat `QuarkusDelayedHandler` as **transparent**: catalogue its
  children in its place for `list handlers`, handler-level control, the handler-floor hint,
  `top` and `doctor`. It must keep the delayed handler itself as the attach point for filters.

### 7. Plain `java -jar` on `java.util.logging` (secondary)

With a throwaway `PlainJul` main (sleeps 300 ms, then logs):

- None of `java.util.logging.Logger`, `LogManager`, `LogManager$LoggerContext` or
  `LogManager$RootLogger` is loaded at `premain`. All four appear together at the application's
  first `Logger.getLogger` (+1.27 s).
- When the app sets `java.util.logging.manager=org.jboss.logmanager.LogManager` itself in `main`,
  the property appears 1 ms before JUL initialises, and JBoss LogManager is installed correctly.
- **Safe readiness signal:** "the application has initialised JUL", meaning
  `java.util.logging.LogManager$RootLogger` (or `$LoggerContext`) is loaded. That is class
  presence only, and it is true only after *someone else* made the first JUL call, so the agent
  can never pre-empt a programmatic `java.util.logging.manager` in `main`. It is the same
  principle as the fixed Quarkus gate. The `none` integration would then bind a JUL adapter next
  to (or instead of) Logback: §15.4's "several frameworks at once", which the aggregate already
  supports. Handler names for plain JUL are `<class>@<idhash>` tokens (`HandlerNameResolver.NONE`).
  That is acceptable there because tokens are advertised on non-JBoss JUL.
- A detection caveat seen here: `quarkus-bootstrap-runner` on a non-Quarkus classpath makes a
  "`QuarkusEntryPoint.class` resource present" check fire. Detection should also require
  `sun.java.command` to name `quarkus-run.jar`, `io.quarkus.runner.GeneratedMain` or a
  `*-runner.jar`, or `quarkus-application.dat` next to the launch jar.

### 8. Dev mode (quick look)

`mvn quarkus:dev -Djvm.args="-javaagent:…"` works. The forked dev JVM is detected, the fixed gate
fires, `logctl` controls it, TRACE works (floor `ALL` in dev mode), a live reload keeps level
overrides, and rules come back on the next sweep. Not a 1.0 target. Nothing was seen that argues
for doing more than documenting "works, unsupported".

## Not established

- **Whether the "application initialised JUL" condition (`LogContext` loaded) is correct for
  WildFly**, including the #86/#87 launch where `jboss.modules.system.pkgs` loads JBoss LogManager
  at `premain`. Not tried. The shared gate needs `WildFlyContainerIT` plus that launch shape
  before it replaces the WildFly gate. The Quarkus-only condition can ship first if it does not.
- How the race odds scale with app size, slower disks, or containers with CPU limits. Only this
  one small app on one fast machine was measured. The mechanism is not in doubt; the odds are.
- Quarkus versions other than 3.39.5, in particular the current LTS stream.
  `QuarkusDelayedHandler.isActivated()`, `InitialConfigurator` and the `logging.initial-configurator.min-level`
  property are Quarkus internals, not API.
- `quarkus.log.console.async` / `quarkus.log.file.async` (an `AsyncHandler` inside the delayed
  handler, so two levels of nesting), and syslog/socket handlers.
- Whether the order of `Logger.getHandlers()` on a category always matches
  `quarkus.log.category."X".handlers`. Only one handler per category was tried.
- Storm detection on Quarkus. The storm filter is installed with the rule filter (confirmed by
  code, not by a storm run).
- Whether `top` misses `AsyncHandler` children on WildFly as well.
- Legacy-jar packaging, Quarkus in a container image, and `--vendor-defaults` on Quarkus.

## Recommendation

**A preview in 1.0 is feasible in 6 working days, and 7 with config-derived handler names.** That
fits the Oct 4–13 window only if the spec is written and signed off on the first day and nothing
else competes for the window. The unchanged WildFly gate must **not** ship for Quarkus, because it
can pre-empt Quarkus's JUL initialisation. The fix is small and well understood (finding 2).
Everything else a preview needs is a known quantity: re-sweep on `QuarkusDelayedHandler`
activation, a transparent delayed handler for the handler catalog and `top`, and a min-level
warning. None of it needs new architecture; the aggregate, services, store and CLI worked
unchanged. If the window is shared with other 1.0 work, ship 1.0 with Quarkus listed as "not yet
supported" in the README and land the preview in 1.1. The risks below are about correctness on
real apps, not about feasibility.

### Estimate (working days)

The spec comes first, per [`CLAUDE.md`](../../CLAUDE.md). Days assume one developer.

| Day | Work |
|---|---|
| 1 | `doc/specs/quarkus-jvm.md` (Functional summary, scope, the decisions below as numbered opens), sign-off. Link from §15.9. |
| 2 | **Shared gate refactor.** Lift `WildFlyLogManagerReadiness` into shared code with the "app initialised JUL" condition, keeping the TCCL and manager checks. Generalise `WildFlyContainer` into a JBoss-LogManager host with the container name as a parameter. `WildFlyContainerIT` regression plus the #87 launch shape. |
| 3 | **`QuarkusContainerIntegration`:** detection (resource and launch-command or `quarkus-application.dat`, never touching JUL), install guidance, version (from the `quarkus-core` jar manifest, file read only), an activation re-sweep through `QuarkusDelayedHandler.isActivated()` reflectively (or a fast startup sweep, decision #3). Unit tests. |
| 4 | **Transparent delayed handler in the JUL adapter:** children catalogued in its place (handler-level, `ALL_HANDLERS` fan-out, handler-floor hint), `top` byte counting and `doctor` walking sub-handlers the way trim already does (#80). Type-based names `console` / `file` / `syslog` / `socket` for its children. |
| 5 | **Min-level honesty:** `isLoggable` check after `set logger` with a warning, a `doctor` finding naming `quarkus.log.min-level` when `logging.initial-configurator.min-level` is set, and `list loggers` stops claiming an effective level below the floor. |
| 6 | **IT:** `logaperture-sample-quarkus` module in the reactor (behind a profile if its build time hurts) plus `QuarkusIT`, which runs `java -jar quarkus-run.jar` with the agent (Testcontainers later if wanted): boot intact, floor unchanged, DEBUG works, TRACE warns, drop/trim effective before first request, sticky across restart. |
| 7 | Docs (README, user guide: supported/unsupported, the min-level and per-category notes, dev mode "works, unsupported", native "never") and review fixes. **Optional in the same day or 1.1:** named-handler resolution from Quarkus config (finding 6). |

### Risks

1. **Gate race (finding 2).** It is fixed by the extra condition, but the fix changes shared
   code that WildFly depends on. Keep it Quarkus-only if WildFly regression testing is short.
2. **Reliance on Quarkus internals.** `QuarkusDelayedHandler`, `InitialConfigurator`, the
   `logging.initial-configurator.min-level` property and `RunnerClassLoader` are not API. Only
   3.39.5 was tested. Pin a tested range, add an LTS version to the IT, and degrade to the
   periodic sweep when the reflection finds nothing.
3. **Silent TRACE no-op (finding 4).** This is the most likely first-impression bug report ("I set
   TRACE and nothing happened"). The warning is part of the preview, not a follow-up.
4. **Startup window (finding 5).** Without the activation re-sweep, sticky drop/trim/storm rules
   do nothing for the first sweep interval (30 s by default) of every start.
5. **Handler catalogue (findings 3, 6).** Without the transparent delayed handler, the
   handler-floor fix is invisible on Quarkus and `top` misses the main log file. That is a worse
   experience than WildFly's, and users will compare the two.
6. **Detection false positives (finding 7)** from bootstrap-runner jars on non-Quarkus
   classpaths.
7. **Behaviour to document rather than fix:** overrides bypass per-category `min-level`; dev mode
   runs with floor `ALL`; Quarkus's default handlers have `autoflush` on, so `doctor` warns about
   it on every stock Quarkus app. Either tune that check for Quarkus or accept the noise.
8. **Reactor cost:** a Quarkus sample module brings the Quarkus BOM and plugin into the build.
   Expect a noticeably longer cold CI build.

## Harness

Not committed. About 250 lines of Java plus shell scripts, easy to rebuild:

- **`QuarkusContainerIntegration`**, placed in `org.logaperture.container.wildfly` so it could
  reuse the package-private `WildFlyLogManagerReadiness` and `WildFlyContainer` unchanged. It was
  inserted between `WildFlyContainerIntegration` and `NoneContainerIntegration` in
  `AgentBootstrap.integrations()`. Detection was a resource probe on the system loader (no class
  defined, no JUL):

  ```java
  boolean entryPoint = ClassLoader.getSystemClassLoader()
          .getResource("io/quarkus/bootstrap/runner/QuarkusEntryPoint.class") != null;
  ```

  `activate` built `new WildFlyContainer(policy, auditLog, SweepPolicy.interval(), Optional::empty,
  vendorDefaults)` and bound `JulAdapterFactory.forCurrentContext(HandlerNameResolver.NONE)` as the
  `system` context from the gate's `onReady`, exactly like the WildFly integration.
- Knobs: `-Dspike.gate=wildfly|initialized|observe|observe-jul` (unchanged gate / plus
  `LogContext` loaded / 1 ms milestone logger only / the plain-JUL milestone logger),
  `-Dspike.pollMs`, `-Dspike.offsetMs` (delays the detector thread to shift the poll phase),
  `-Dspike.activationHook=true` (poll `QuarkusDelayedHandler.isActivated()` every 25 ms after
  install, then `runVerificationSweepNow()`), and `-Dspike.plain=true` (force detection for the
  plain-JUL app).
- Reporters: at the moment the gate turns true, before the first JUL call, it logs the
  min-level property and whether `ApplicationImpl`, `LogContext` and `InitialConfigurator` are
  loaded. After install it logs `InitialConfigurator.MIN_LEVEL` (reflective). Once a second (or
  every `-Dspike.reportMs`) it logs the handler topology: every logger's handlers and their
  sub-handlers, with level, formatter chain and filter class. Once it logs the
  `quarkus.log.handler.*` / `quarkus.log.category.*.handlers` config read through
  `ConfigProvider`.
- Scripts: `start.sh` (launch with the agent, `-Dlogaperture.home` in a scratch directory, and
  debug diagnostics, then wait for HTTP), `lc.sh` (`logctl --pid` against the launched JVM),
  `tracecheck.sh` / `race.sh` (start, set TRACE, hit the endpoint, report `MIN_LEVEL` and the
  TRACE line count, stop; repeated N times), and `observe.sh` (milestone timings).
- App variants: default build; `quarkus.log.min-level=TRACE` (with a per-category `INFO`
  min-level on `org.example.Jul`); a per-category `min-level=INFO` on `org.example.Work`; the
  uber-jar (`-Dquarkus.package.jar.type=uber-jar`); and `mvn quarkus:dev` with `-Djvm.args`.
