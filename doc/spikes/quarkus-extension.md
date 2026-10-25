# Spike — Quarkus extension

Status: **done.** The three questions it was opened for are answered. What it did not establish
is listed under "Not established". The recommendation for the spec is at the end.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.2 (Quarkus extension, and
its list of open design questions), §4.6 (`core` never depends on `agent`). Related:
[`quarkus-jvm-mode.md`](quarkus-jvm-mode.md) (the agent route; this spike reuses its findings),
[`doc/specs/quarkus-support.md`](../specs/quarkus-support.md),
[`doc/specs/cli-transport.md`](../specs/cli-transport.md) ("Discovery"). Issue
[#267](https://github.com/ddeuchert/logaperture/issues/267), milestone 1.1.0.

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the harness that produced it. The harness is not committed. See "Harness" below.

## Questions

1. Can the unchanged `logctl` find and drive an engine started in-process by an extension, with
   no `-javaagent`?
2. Can an extension do anything about the build-time `quarkus.log.min-level` floor, which the
   agent can only warn about?
3. Does an extension start early enough to catch loggers and overrides at startup, or does it
   still need the agent's readiness gate and early re-sweep?

## Environment

- Linux (Fedora, kernel 7.2), Temurin JDK 21.0.12, Maven 3.9.16.
- Quarkus **3.39.5** (the tested minor of [`quarkus-support.md`](../specs/quarkus-support.md)),
  and the same harness rebuilt unchanged against the **3.33.4** LTS.
- App: a copy of `logaperture-sample-quarkus` (`quarkus-rest`, the `/log` resource), with the
  spike extension as a dependency and no agent. Fast-jar, launched with `java -jar
  quarkus-run.jar`, `-Dquarkus.log.file.enabled=true` and a scratch `-Dlogaperture.home`.
- LogAperture: `develop` at `90eb452`, installed to the local Maven repository, unchanged.
- `logctl`: the stock `logaperture-cli.jar` from the same build.

## Findings

### 1. `logctl` reaches the in-process engine unchanged

- **Zero-PID discovery found it.** `logctl env` with no `--pid` listed the app and connected.
  Discovery attaches from outside and reads the `logaperture.version` system property
  ([`cli-transport.md`](../specs/cli-transport.md) "Discovery"); the extension sets that
  property and registers the same MXBean through `JmxRegistrar`, so nothing in `logctl` or the
  control surface had to change. Attach is to the target JVM from outside, so neither
  `-Djdk.attach.allowAttachSelf` nor any other command-line flag is needed.
- **Every command tried worked:** `env`, `list handlers --show-all` (console and file, with the
  file's path), `set logger … DEBUG for 10m` (DEBUG appeared on console and in the file),
  `set logger … TRACE` (the existing below-floor warning, word for word), `doctor` (the same
  findings as with the agent, including the min-level one), `top`, and `sticky` settings
  restored on the next start (`source=resume` audit record, `restored=2`).
- **What the engine needs from the host, without an agent:** a `JBossLogManagerContainer`, a
  `JulLoggingAdapter` with `QuarkusHandlerNameResolver`, `installContext`, `JmxRegistrar.register`
  and the marker property. About 15 lines in a Quarkus recorder. All of it is public API of
  existing modules; `logaperture-agent` is not on the extension's classpath. §4.6 holds.
- **Two places assume "agent".** `logctl env` prints `LogAperture agent  dev`: the label is fixed
  and the version comes from the agent jar's manifest. The banner and `logctl status` would need
  the same. The library-recipe scan (`LibraryRecipeScanner`) takes `Instrumentation::
  getAllLoadedClasses`, which an extension does not have; the spike passed no recipes.

### 2. The min-level floor: an extension can lower it, and the application still decides

- **How Quarkus computes the floor.** `LoggingResourceProcessor.setMinLevelForInitialConfigurator`
  takes the lowest of `quarkus.log.min-level` and every per-category `min-level` from build-time
  config, and emits it as a `SystemPropertyBuildItem`
  (`logging.initial-configurator.min-level`). It does not read
  `LogCategoryMinLevelDefaultsBuildItem`, the build item extensions use for per-category
  defaults, so that route cannot lower the floor.
- **A config default in the extension jar does lower it.** The runtime jar shipped
  `META-INF/microprofile-config.properties` with `quarkus.log.min-level=TRACE`. MicroProfile
  Config gives that file ordinal 100, below `application.properties` (250). Result:
  - Default app: floor `400` (TRACE). `set logger … TRACE` produced TRACE output (`trace=true`),
    where the same app without the extension's default stays at `500` and warns.
  - App with `quarkus.log.min-level=DEBUG` in its own `application.properties`: floor `500`.
    The application's choice wins.
  - `QUARKUS_LOG_MIN_LEVEL=INFO` at run time: floor stays `400`, and Quarkus warns "build time
    fixed to 'TRACE'". The floor is a build decision, as before.
- **Cost in JVM mode: none measurable by construction.** The other code the floor drives
  (`generateLogManagerLogger`, `MinLevelCompute`) produces native-image substitutions; none of it
  was in the JVM-mode `generated-bytecode.jar`. In JVM mode the floor is one extra integer
  comparison in `LoggerNode.isLoggableLevel`, made either way. Nothing below a logger's own level
  is emitted, so a lower floor changes no output until someone sets a logger below DEBUG.
- **Per-category `min-level` is unchanged:** still a level promotion at runtime init, still
  bypassed by an override ([`quarkus-jvm-mode.md`](quarkus-jvm-mode.md) finding 4).

### 3. Timing: no readiness gate, no early re-sweep

- **The recorder runs after logging is set up and before the rest of startup.** A
  `@Record(RUNTIME_INIT)` build step that consumes `LoggingSetupBuildItem` ran on the main thread
  at +0.80 to +0.97 s after JVM start (eight runs), before the Quarkus banner. At that moment
  JBoss LogManager is the JUL manager, the floor property is set, and the root's
  `QuarkusDelayedHandler` is already **activated** with its real console and file handlers.
- **So the agent's start-up machinery is not needed.** The readiness gate exists to avoid being
  the first JUL caller; the early re-sweep exists because Quarkus reconfigures logging after the
  agent installs. In an extension both have already happened when the recorder runs. The
  recorder installed directly, with no polling thread.
- **Restored overrides catch startup logging.** With `io.quarkus` set to DEBUG `sticky`, the
  next start logged Vert.x's two startup DEBUG lines (`VertxCoreRecorder`, `main` thread), which
  run after this recorder. `top` counted `io.quarkus`'s startup lines.
- **Earlier than this is possible but pointless.** Before `LoggingSetupBuildItem`, records go
  into the delayed handler's queue and are filtered by the levels in force when they were
  logged, which LogAperture would have set; but the handlers it wraps do not exist yet.

### 4. Agent and extension in one JVM: the current marker is a race

With both present, the agent installed first in the one run tried, set `logaperture.version`,
and the extension saw it and stood down. That ordering is not guaranteed: the agent publishes the
marker from its detector thread, which polls every 100 ms after `LogContext` loads, while the
extension's recorder runs on `main`. If the recorder wins, both engines install on the same log
context. A deterministic claim is needed: `premain` always runs before `main`, so an agent that
claims the JVM synchronously in `premain` wins every time, and the extension stands down on the
claim.

### 5. Dev mode: works once, then needs a lifecycle

- **First start works:** installed at +4.9 s (dev mode builds first), `logctl` connected.
- **A bug that also affects the agent in dev mode.** Dev mode adds the Dev UI log stream handler
  (`MutinyLogHandler`). LogAperture's formatter wrap replaced its formatter, and the handler
  casts its formatter to `io.quarkus.devui.runtime.logstream.JsonFormatter`: `ClassCastException`
  on publish, reported by JBoss LogManager as `WRITE_FAILURE`. Dev mode is unsupported for the
  agent ([`quarkus-support.md`](../specs/quarkus-support.md)), but the class of bug is general:
  a handler that downcasts its own formatter breaks when wrapped.
- **Live reload leaves the first engine behind.** On restart Quarkus rebuilt its handlers and ran
  the recorder again in a new class loader. The recorder saw the old marker and stood down. The
  old engine, loaded by the previous class loader, stayed registered: `set logger` still worked
  (loggers live in the system log context), but `list handlers` showed no console or file
  handler, and the old class loader is pinned. An extension needs to stop the engine through
  Quarkus's `ShutdownContext` and start a fresh one on each restart.

### 6. Packaging

- The extension's runtime module depends on `logaperture-core`, `-api`, `-bridge`,
  `-adapter-jul`, `-container-jbosslogmanager`, `-container-quarkus` and `-control-jmx`. Today
  those are built into the agent jar and are **not published** (`maven.deploy.skip`,
  [`maven-central-publishing.md`](../specs/maven-central-publishing.md)). A published extension
  needs them on Maven Central, or shaded into its runtime jar.
- The extension is the standard pair: `logaperture-quarkus` (runtime, with the
  `quarkus-extension-maven-plugin` descriptor) and `logaperture-quarkus-deployment`. It built and
  ran unchanged against 3.39.5 and the 3.33.4 LTS, using only `LoggingSetupBuildItem`,
  `FeatureBuildItem` and a recorder.

## Not established

- `quarkus ext add logaperture` by short name. That needs the extension in the Quarkus extension
  registry (Quarkiverse, or a listing in the extension catalog); with only Maven coordinates it is
  `quarkus ext add org.logaperture:logaperture-quarkus`. Not tried.
- `quarkus.logaperture.*` configuration (a `@ConfigMapping` for the agent arguments). Standard
  Quarkus; not built.
- Native image, request-scoped context (SmallRye `ThreadContextProvider`) and a Dev UI card:
  out of scope, untouched.
- Uber-jar and container images. Fast-jar only.
- Quarkus versions other than 3.39.5 and 3.33.4.

## Recommendation for the spec

The extension is smaller than the agent's Quarkus support, not larger: it drops the readiness
gate and the early re-sweep, and it can do what the agent can only warn about. Spec it for
1.1.0, with these decisions to settle at sign-off:

| # | Decision | Spike's lean |
|---|---|---|
| 1 | Lower the floor by default (`quarkus.log.min-level=TRACE` shipped in the extension), application config wins | Yes; document it on the extension's guide page |
| 2 | Publish the engine modules to Maven Central, or shade them into the runtime jar | Publish; shading next to an agent jar that has the same classes invites conflicts |
| 3 | Agent and extension together: deterministic claim in `premain`, agent wins, extension stands down with one line | Yes |
| 4 | Dev mode: supported (stop on shutdown, start per restart) or documented as unsupported like the agent | Supported; it is where Quarkus developers are |
| 5 | Formatter wrap skips handlers that downcast their formatter (Dev UI log stream), for the agent too | Fix in the same release; file as its own issue |
| 6 | Library recipes without `Instrumentation`: build-time dependency list from the deployment module, or a class-loader resource scan | Build time |
| 7 | How the engine names itself: `env`/`status`/banner say "extension" and its version | Yes |
| 8 | Registry listing (Quarkiverse or the extension catalog) for `quarkus ext add logaperture` | Decide separately; Maven coordinates work without it |
| 9 | Supported Quarkus window | Current LTS and the latest minor, both in the build |

Out of scope for 1.1.0: native image, request-scoped context, a Dev UI card.

## Harness

Not committed. A standalone Maven project depending on the installed `1.1.0-SNAPSHOT` modules:

- `runtime/`: `LogApertureRecorder.start()` builds the `JBossLogManagerContainer`, installs the
  `system` context with `JulAdapterFactory.forCurrentContext(new QuarkusHandlerNameResolver())`,
  registers the MXBean and sets the marker; it first prints the time since JVM start, the floor
  property, the JUL manager, and the root handlers with `isActivated()`. Stands down if
  `logaperture.version` is set. Optionally `META-INF/microprofile-config.properties` with
  `quarkus.log.min-level=TRACE`.
- `deployment/`: `FeatureBuildItem("logaperture")` and a `@Record(RUNTIME_INIT)` step taking
  `LoggingSetupBuildItem`.
- `app/`: the sample's `WorkResource` and `application.properties`.
- Scripts: `start.sh` (launch with file logging and a scratch home, wait for HTTP), `lc.sh`
  (stock `logctl`), `stop.sh`.
