# Quarkus extension

Status: **Signed off 2026-10-25** (decisions #1 to #14 below). Implemented on
`feature/267-quarkus-extension`; see "As built" for where the implementation adds to the decisions.
Release `1.1.0`.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.2 (Quarkus extension), §4.6
(module layout: `core` never depends on `agent`), §9 (capabilities and audit), §17.1 (release
plan). Evidence: [`doc/spikes/quarkus-extension.md`](../spikes/quarkus-extension.md). Builds on:
[`quarkus-support.md`](quarkus-support.md) (Quarkus JVM mode through the agent, #114). Related:
[`maven-central-publishing.md`](maven-central-publishing.md),
[`cli-transport.md`](cli-transport.md) ("Discovery"). Tracking issue:
[#267](https://github.com/ddeuchert/logaperture/issues/267).

## Functional summary

After this feature, the user will be able to:

- Add LogAperture to a Quarkus application as an extension, with
  `quarkus ext add org.logaperture:logaperture-quarkus` or the Maven dependency, rebuild, and
  control its logging with `logctl` as before. No `-javaagent` option and no change to the start
  command or the container image's `JAVA_OPTS`.
- Set LogAperture's options as `quarkus.logaperture.*` properties: in `application.properties`,
  as environment variables, or from a Kubernetes ConfigMap.
- Use `logctl set logger X TRACE` on a default build and see TRACE output, without rebuilding with
  a lower `quarkus.log.min-level`.
- Use it in `quarkus dev` too, with the engine following each live reload.
- See in `logctl env` and the startup line that LogAperture runs as the Quarkus extension, and
  which version.

Guide pages this changes: `get-started/install-quarkus.md` (the extension becomes the first
route, the agent the second; supported versions; Kubernetes); a new
`reference/quarkus-extension-config.md` (every `quarkus.logaperture.*` property);
`reference/agent-options.md` (a pointer to it); `reference/logctl.md` only if `env`'s help text
changes (regenerated); `troubleshooting.md` (agent and extension together); `mkdocs.yml`
navigation.

Not part of this feature: native image, request-scoped logging ("DEBUG for this request only"),
a Dev UI card, a listing in the Quarkus extension registry (see #12), and vendor defaults bundled
in the application jar ([#269](https://github.com/ddeuchert/logaperture/issues/269), the follow-up
in the same milestone).

## What the spike established

The spike ran a 15-line extension against the sample app on Quarkus 3.39.5 and the 3.33.4 LTS
([findings](../spikes/quarkus-extension.md)):

- The stock `logctl` discovers and drives an in-process engine: the extension sets the same
  `logaperture.version` marker and registers the same MXBean. Nothing in `logctl` or the control
  surface changes.
- A `@Record(RUNTIME_INIT)` step that consumes `LoggingSetupBuildItem` runs on `main` at about
  +0.9 s, after Quarkus has activated its handlers and before the rest of startup. The agent's
  readiness gate and early re-sweep are not needed, and restored `sticky` overrides catch startup
  logging.
- A `quarkus.log.min-level=TRACE` default shipped in the extension jar lowers the floor; the
  application's own `application.properties` still wins, and the floor stays a build-time value.
- Problems to solve: the agent and the extension race when both are present; dev mode breaks the
  Dev UI log stream (formatter wrap) and leaves a stale engine after live reload; the engine
  modules are not published.

## Decisions

All fourteen were agreed on 2026-10-25 from the sign-off review, each as the outcome in the last
column.

| Id | Decision | Options | Outcome |
|---|---|---|---|
| #1 | The `min-level` floor | (a) The extension ships `quarkus.log.min-level=TRACE` as a config default (ordinal 100); the application's own setting wins. (b) Leave the floor alone and keep the warning. | **(a)**. It is the one thing the agent cannot do, and it costs nothing in JVM mode (spike finding 2). The guide says so, and how to opt out (set `quarkus.log.min-level` yourself). |
| #2 | Packaging the engine | (a) Shade the engine modules into the extension's runtime jar, as the agent and `logctl` jars already are. (b) Publish the internal modules (`core`, `api`, `bridge`, `adapter-jul`, `container-*`, `control-jmx`) to Maven Central and depend on them. (c) Depend on the published agent jar. | **(a)**. Keeps the publishing rule that internal modules exist only inside shaded jars ([`maven-central-publishing.md`](maven-central-publishing.md)) and adds no permanent coordinates. (b) turns seven internal modules into apparent API. (c) drags the WildFly, Logback and `premain` code into every Quarkus app. |
| #3 | Agent and extension in one JVM | (a) The agent claims the JVM synchronously in `premain`; the extension sees the claim, logs one line and stands down. (b) The extension wins and the agent stands down. (c) Refuse to start either. | **(a)**. `premain` always runs before `main`, so it is deterministic. The stand-down check uses only a system property, so it never loads engine classes from either jar. |
| #4 | Dev mode | (a) Supported: the engine stops through Quarkus's `ShutdownContext` and a fresh one starts on each restart; settings carry across reloads through the state file. (b) Documented as unsupported, as for the agent. | **(a)**. `quarkus dev` is where Quarkus developers spend their day. |
| #5 | Handlers that downcast their formatter | The formatter wrap (`top`, `trim`) skips a handler whose own code requires its formatter's type, starting with the Dev UI log stream handler; `doctor` names any handler it skipped. Applies to the agent as well. | **Yes**, fixed in this feature, with its own issue so the agent's 1.0.x line can take it if needed. |
| #6 | Library recipes without `Instrumentation` | (a) Scan the application's class loader at run time (the scanner only needs class loaders). (b) Collect recipe files at build time in the deployment module. | **(a)**. One class loader holds every application jar in fast-jar; the existing scanner takes it unchanged. The spike did not run this (Not established). |
| #7 | How the engine names itself | `logctl env` prints `LogAperture extension <version> (Quarkus)` instead of `LogAperture agent`, and the startup line says `(Quarkus extension)`. `--json` gains an additive field naming the delivery (`agent` or `extension`). | **Yes**. Additive under §11.1. |
| #8 | Configuration | Every documented agent argument and `-Dlogaperture.*` property gets a `quarkus.logaperture.*` equivalent (`enabled`, `vendor-defaults`, `storm-detection`, `home`, `instance-id`, `recipes`, `audit.file`, `diagnostics.level`, `drop.summary-interval`, `follow-handlers`), read at run time, so environment variables and ConfigMaps work. Internal settings stay `-D` only. A `-Dlogaperture.*` given on the command line still wins. | **Yes**. Run-time config, not build-time, so one image serves every environment. `quarkus.logaperture.enabled=false` is the kill switch. |
| #9 | Supported Quarkus versions | Build against the current LTS (3.33), test on it and on the latest minor (3.39), both in CI, as #114 does. | **Yes**. The spike used three stable SPI types (`LoggingSetupBuildItem`, `FeatureBuildItem`, a recorder) and ran unchanged on both. |
| #10 | Shared start-up code | The agent's start-up work (vendor defaults load and audit, control-surface registration, marker, banner, recipes) moves out of `AgentBootstrap` into a shared internal module both the agent and the extension call. The Quarkus host set-up moves out of `QuarkusContainerIntegration.activate` the same way, so the agent runs it after its gate and the extension runs it directly. | **Yes**. Otherwise the extension copies them and they drift. It also makes §18.2's "the agent is one delivery mechanism" a tested property (#11 below). |
| #11 | Embeddability test | A test in the reactor starts the engine through the shared module with no agent on the classpath, and the build's existing `core`-never-depends-on-`agent` assertion extends to the extension modules. | **Yes**. §18.2 asks for it to be tested, not assumed. |
| #12 | Extension registry | (a) Maven coordinates only for 1.1.0 (`quarkus ext add org.logaperture:logaperture-quarkus`). (b) Also list the extension in the Quarkus extension catalog so `quarkus ext add logaperture` and code.quarkus.io find it. (c) Move it to Quarkiverse. | **(a) for 1.1.0, (b) right after**, as its own issue. (b) needs the extension published first. (c) moves the code out of this repository and its release line, which is a separate decision. |
| #13 | Where `logctl` runs | `logctl` needs a JDK and the same machine and user as the app (§9.8). Quarkus's default images carry a JRE. Document, and test in the slice: `kubectl debug --target=<container>` with the LogAperture container image (§17.1 "Distribution"), and Azure App Service's SSH console. | **Yes**, guide plus a test of the Kubernetes route. A remote control plane stays out of scope (§8.2). |
| #14 | Slot | `1.1.0`, after Quarkus JVM mode (#114) and plain-JVM JUL (#221). If it threatens the 1.1.0 date, 1.1.0 ships with the agent route and the extension becomes the head of `1.2.0`. | **Yes**, with that fallback decided now, not at the deadline. The fallback is moot: the extension merged on 2026-10-25 and 1.1.0 is now built around it (spec §17.1, #272). |

## What has to be built

1. **Shared start-up module (#10).** Extract from `AgentBootstrap` and
   `QuarkusContainerIntegration` the code both delivery routes need. The agent keeps `premain`,
   argument parsing, the readiness gate and the early re-sweep; nothing it does changes.
2. **`logaperture-quarkus` and `logaperture-quarkus-deployment` (#2, #9).** The standard extension
   pair, in the reactor. Runtime: a recorder that reads `quarkus.logaperture.*` (#8), checks the
   agent's claim (#3), starts the engine through the shared module, and registers a shutdown task
   (#4). Shades the engine modules (#2). Ships `META-INF/microprofile-config.properties` with
   `quarkus.log.min-level=TRACE` (#1) and the extension descriptor. Deployment: the feature
   build item and a `@Record(RUNTIME_INIT)` step consuming `LoggingSetupBuildItem`.
3. **The agent's claim (#3).** `premain` sets a claim property before anything else; the agent's
   existing duplicate-start guard is unaffected.
4. **Dev mode (#4, #5).** Stop on shutdown: unregister the MXBean, clear the marker, close the
   host and unwrap its handlers. Start again on the next restart in the new class loader. The
   formatter-wrap exclusion (#5) with a `doctor` finding.
5. **Recipes (#6) and naming (#7).** The runtime class-loader scan; the delivery label in `env`,
   `status`, the banner and `--json`.
6. **Publishing.** Both extension artifacts join the published set in
   [`maven-central-publishing.md`](maven-central-publishing.md) (a spec change in this PR), with
   the parent POM they name.
7. **Tests and docs (#9, #11, #13).** An extension IT on the sample app (a variant of
   `logaperture-sample-quarkus` with the extension instead of the agent), on both Quarkus versions;
   the embeddability test; the guide pages listed in the Functional summary.

## Acceptance

The extension IT builds the sample with the extension, runs `java -jar quarkus-run.jar` with no
`-javaagent`, and checks:

- boot is intact; `logctl env` (no `--pid`) shows the extension, its version and `Quarkus <version>`;
- the floor is TRACE on a default build and `set logger X TRACE` produces TRACE output; a build
  with `quarkus.log.min-level=DEBUG` in `application.properties` keeps DEBUG and the warning;
- `list handlers` shows `console` and `file`, `top` counts the log file, a `drop` rule works;
- a `sticky` override set in one run is in force before the first request of the next, and
  catches startup logging;
- `quarkus.logaperture.enabled=false` (as an environment variable) starts nothing;
- with the agent on the command line as well, only the agent runs and the extension logs one
  line saying so;
- in dev mode, after a live reload, `list handlers` still shows the console handler, the Dev UI
  log stream works, and a `sticky` override survives the reload;
- the agent's `QuarkusIT` and `WildFlyContainerIT` still pass after the start-up code moves (#10).

## Behaviour to document rather than change

- The extension lowers the build's `min-level` floor to TRACE unless the application sets it
  (#1). Per-category `min-level` is still bypassed by overrides, as with the agent.
- `logctl` still needs a JDK on the same machine as the app, as the same user (#13).

## Out of scope

Native image (§15.9: a build-time extension might make it possible later); request-scoped
context through a SmallRye `ThreadContextProvider`; a Dev UI card; the extension registry and
Quarkiverse (#12); a remote control plane; vendor defaults bundled at
`META-INF/logaperture/vendor-defaults.yaml` in the application jar
([#269](https://github.com/ddeuchert/logaperture/issues/269), which depends on this feature and
moves with it under #14; spec [`bundled-vendor-defaults.md`](bundled-vendor-defaults.md)).

## Not established

From the spike, to be closed by the slice: the recipe scan through Quarkus's class loader (#6);
`quarkus.logaperture.*` config (#8); uber-jar and container images; `kubectl debug` attach to a
JRE-based Quarkus image (#13); dev-mode restart with the shutdown task (#4); Quarkus versions
other than 3.39.5 and 3.33.4.

## As built

What the implementation settled that the decisions left open, or found along the way:

- **The claim (#3) is one system property, `logaperture.delivery`**, set to `agent` or `extension`
  with an atomic put-if-absent (`EngineStartup.claim`). The agent sets it first thing in `premain`,
  after the kill switch and the duplicate-start guard. The extension's recorder reads it before any
  engine class loads and, if set, prints one line and starts nothing. An `agentmain` attach into a
  JVM the extension owns stands down the same way. The same property names the route in `logctl
  env` (#7).
- **Shared start-up (#10)** is a new internal module, `logaperture-startup` (`EngineStartup`):
  vendor defaults load and audit, control-surface registration and withdrawal, the marker, the
  banner, recipes. `QuarkusEngine` in `logaperture-container-quarkus` builds the host and installs
  the system context for both routes; the agent's `QuarkusContainerIntegration` keeps its gate and
  early re-sweep around it.
- **Packaging (#2).** The engine modules are `optional` dependencies of `logaperture-quarkus`, shaded
  into its jar; the published POM is the dependency-reduced one and names only `quarkus-core`.
  Optional, because an application (or the reactor's own sample) would otherwise get each engine
  module a second time as its own jar. One consequence: `quarkus dev` on a project *inside this
  reactor* uses the extension's unshaded `target/classes` and fails with `NoClassDefFoundError`; a
  real application resolves the published jar and is unaffected. Dev mode is therefore checked
  from a standalone project against the installed artifacts.
- **Stopping (#4)** runs each step on its own, so one failing step can't keep the state-file lock
  held for the rest of the dev JVM. In order:
  1. Withdraw the control surface: unregister the MXBean, clear the marker and the claim.
  2. Put every logger LogAperture changed back to its own level
     (`AggregateLevelControl.restoreNativeLevelsForStop`). `SESSION` overrides are reverted for
     good, audited with source `stop`. `for` and `sticky` overrides and vendor-default levels go
     back to the native level on the adapter only, and stay in the registry and the state file.
  3. Close the host: stop the sweeper, release the state-file lock.
  4. Take the adapter's filters and formatter wraps off the handlers (`JulLoggingAdapter.uninstall`).
  5. Clear the `-Dlogaperture.*` properties that this start set from `quarkus.logaperture.*`.

  Found by testing and by review. Quarkus keeps logger levels across a dev-mode restart. A level
  left in place would either survive the reload with nothing managing it (a session override), or
  be captured by the next engine as the logger's native level, so a later reset or expiry would land
  on it (`for`, `sticky`, vendor). A property left set would look like a command-line one to the
  next start and hide a changed setting. A start that fails after the claim runs the same stop, so
  the next reload isn't refused.
- **Older agents (#3).** Agents from 1.0.0-beta.1 to beta.3 set no claim. The recorder also stands
  down when the `logaperture.version` marker is set, or when the agent's class file is on the system
  class path, where `-javaagent` puts it. It probes this as a resource and never loads the class.
- **Recipes (#6)** are scanned through both the extension's class loader and the thread's context
  loader at runtime init. In dev mode the application's own classes are only in the second.
- **#5's `doctor` check** is `handler.formatter-kept` (INFO), for both routes. The formatter-bound
  list holds one class, `io.quarkus.devui.runtime.logstream.MutinyLogHandler`.
- **#8:** the properties are as listed in the guide's `reference/quarkus-extension-config.md`; the
  recorder copies each into its `-Dlogaperture.*` property unless that is already set, before any
  engine class reads it.
- **#9:** the extension compiles against `quarkus.extension.version` (3.33.4); its descriptor says
  `requires-quarkus-version=[3.33,)`. CI and the release run `Quarkus*IT` (agent and extension) on
  3.39.5 and 3.33.4.
- **Found and fixed: the Quarkus samples ran the wrong JBoss LogManager.** The root POM's
  `dependencyManagement` pinned `jboss-logmanager` 3.0.6 for compiling, and a parent's managed
  version beats an imported BOM's, so both samples (and `QuarkusIT` since #114) ran 3.0.6 instead of
  the 3.2.x their Quarkus ships. The pin now lives only in the three modules that compile or test
  against it.
- **Checked by hand, not in `QuarkusExtensionIT`:** a build whose `application.properties` sets
  `quarkus.log.min-level=DEBUG` keeps DEBUG (spike finding 2; a second build of the sample just for
  this was not worth its time); dev mode across live reloads (above); and the Kubernetes route (#13),
  with Podman containers sharing the process and network namespaces as a pod's do, the application
  in a JRE-only image. A real cluster was not available.

## Estimate and slot

About 7 to 9 working days for one developer, spec sign-off included: the shared start-up module
and agent regression (2), the extension pair, config and claim (2), dev mode and the formatter
exclusion (1.5), recipes, naming and publishing (1), tests including Kubernetes (1.5), guide (1).
Release `1.1.0` (#14).
