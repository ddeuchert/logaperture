# `logctl env` — Environment Report for Bug Reports

Status: implemented and verified end-to-end, including against two real standalone WildFly
distributions (`WildFlyContainerIT`, plus hand verification against a second image — see
Decision #3's revision notes) — `logctl env` reports the real JBoss LogManager backend version
(read off the root logger's own runtime package) and the real WildFly container version. All
six decisions below were signed off with the recommendation taken as-is, via the published
review artifact per CLAUDE.md's sign-off process; Decision #3's version source was revised
twice, post-implementation, after real-world feedback (a user got no version at all against
real WildFly) surfaced first a wrong file-path assumption and then a deeper premain-timing bug
(below). A small, no-fresh-sign-off addition followed: the fully-qualified **state file** path
("State file"), confirmed against real WildFly to match exactly where overrides are written.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §17 (roadmap — "Pulled forward:
an environment report for bug reports"), §4.5 (self-diagnostics — `-Dlogaperture.diagnostics.level`),
§9.3 (capability model — `view` only), §11.1 (component versioning — the agent already publishes
`-Dlogaperture.version` on install, reused here), §15.2 (`ContainerIntegration` SPI), §15.4
(one JVM, several frameworks, at once).
Builds on: [`doc/specs/doctor.md`](doctor.md) (sibling Layer 1 read-only diagnostic — same
JMX-first convention, same "adapter fact defaults to empty/skipped" discipline),
[`doc/specs/handler-floor-control.md`](handler-floor-control.md) (the adapter-SPI default-method
pattern this reuses for backend facts).
Tracked as [#32](https://github.com/ddeuchert/logaperture/issues/32).

## Functional summary

After this feature, the user will be able to:

- Run **`logctl env`** against a live JVM and get one pasteable block of facts for a bug
  report: the LogAperture agent and `logctl` versions, the Java version and vendor, the OS and
  architecture, the detected logging backend and its version, the detected application
  framework or container and its version, and the fully-qualified path of the file this JVM
  persists overrides to.
- Paste that block straight into an issue instead of being asked the same handful of
  follow-up questions every time.
- Get the same facts as `--json`, for attaching to an automated report or a support ticket.
- Run `logctl env` against a framework or backend this feature doesn't recognize without it
  failing — that one fact is left out, not the whole command.

## Scope of this slice

Layer 1 (§17) — read-only, no rule engine, `view` capability only, no audit record (reads
aren't audited, only mutations are — §9.7, and `doctor.md`'s own precedent for this same
capability level).

**In scope:**

- One new read-only operation, surfaced as `logctl env` / `--json`, JMX first per
  `level-control.md`'s "JMX first, `logctl` later" convention — added to the same
  `LevelControlMXBean` surface `diagnose()`/`topLoggers()` already live on, not a new MBean.
- The facts named in the issue and shown in "The operation" below: agent version, `logctl`
  version, Java version and vendor, OS name/version/architecture, logging backend name and
  version, detected container/framework name and version.
- `--json` output, matching `cli-transport.md`'s existing per-command conventions (pending
  Decision #4).
- Real backend/container version detection for the two environments this codebase currently
  implements — the JBoss LogManager adapter (WildFly, Quarkus-JVM) and the WildFly
  `ContainerIntegration` — the same slice-scoping `doctor.md` used for its adapter coverage
  (pending Decision #6).

**Explicitly out of scope for this slice** (deferred, each with what it needs):

- Logback backend-version detection (the `none` container's real adapter), and every other
  `ContainerIntegration` besides `wildfly` reporting a real version — not because those don't
  exist (the Logback adapter is real and already used by `none`), but the same slice-scoping
  `doctor.md` used for its own adapter coverage: WildFly is "the primary motivating environment"
  (§16). The new SPI default methods (below) make adding a real Logback implementation later
  additive, not a spec change.
- A JVM that genuinely runs more than one logging backend at once (§15.4's Tomcat scenario —
  JULI plus a webapp's own Logback). No implemented container exhibits this yet (WildFly's
  per-deployment `LogContext`s all share one JBoss LogManager instance, so they never
  disagree); this slice reports the first non-empty backend fact found across contexts and
  defers genuine multi-backend rendering to whichever container spec first needs it.
- `doctor`-style findings or suggested fixes — `env` states facts, it does not judge them;
  that's `doctor`'s job.
- A `describeOperations()`/`capabilities()` contract-version probe (§11.1's "Better (deferred)"
  tier) — `env`'s version fields are for a human pasting into an issue, not for `logctl` to
  make a compatibility decision against.

## The operation

`logctl env [--json]`

No target, no tier, nothing to confirm — the universal `--pid`/`--json` options are the only
ones that apply.

```
$ logctl env
LogAperture agent    0.1.0-alpha.2  (logctl 0.1.0-alpha.2)
Java                 21.0.4  Eclipse Adoptium
OS                   Linux 6.10.3  x86_64
Logging backend      JBoss LogManager 3.1.1.Final
Framework/container  WildFly 34.0.1.Final
Diagnostics level    —
State file           /home/alice/.logaperture/instances/8f2c1a-myapp.state.yaml
```

A fact this JVM/environment doesn't resolve is left out of the block entirely (Decision #3),
not printed as a literal `unknown` line — consistent with `doctor`'s "skip silently, don't
manufacture a value" discipline for adapter facts that don't apply. **State file** is the one
exception, same as **Diagnostics level**: shown either way, `—` when persistence is degraded to
session-only, since "no persistence" is itself a useful fact for a bug report, not noise to
suppress.

`--json` emits one flat object, `EnvironmentReportData` (below). Phone-test clean: no `--json`
addition changes that.

## Data model

```
EnvironmentReport {
    agentVersion: String              // from the agent's own package/manifest, same
                                       // resolution AgentBootstrap already uses
    javaVersion: String                // java.version
    javaVendor: String                 // java.vendor
    osName: String                     // os.name
    osVersion: String                  // os.version
    osArch: String                     // os.arch
    backendName: String?               // e.g. "JBoss LogManager" -- null if undetected
    backendVersion: String?            // best-effort, null if not resolvable
    containerName: String?             // e.g. "WildFly" -- null for the "none" baseline
    containerVersion: String?          // best-effort, null if not resolvable
    diagnosticsLevel: String?          // -Dlogaperture.diagnostics.level, pending Decision #5
    stateFilePath: String?             // fully-qualified StateStore location, null if degraded
                                        // to session-only or this store names no single file
}
```

`cliVersion` is deliberately **not** on this shape — `logctl` already knows its own version
locally (`Main.java`'s existing `version()`) and stitches it into the rendered/JSON output
alongside the agent-reported facts, the same "logctl-known vs. agent-reported" split
`cli-transport.md` already draws elsewhere. Lives in `api`, alongside `DoctorFinding` and
`HandlerDiagnostics`, so both `core` and every control surface depend on it without depending
on any one adapter.

## Adapter / container SPI

Two new default methods, following the same "everything empty unless an implementation
overrides it" discipline as `handlerDiagnostics` (`doctor.md`, "Adapter SPI"):

```java
// LoggingAdapter
/** Best-effort name/version of the logging backend this adapter fronts --
 *  e.g. ("JBoss LogManager", "3.1.1.Final"). Default both empty; a real
 *  adapter overrides with what it can cheaply resolve (a manifest
 *  attribute or a well-known class's package version -- see doc/specs/
 *  environment-report.md Decision #3), never by loading a class it
 *  wouldn't otherwise touch. */
default BackendInfo backendInfo() {
    return BackendInfo.EMPTY;
}

BackendInfo { name: String?; version: String? }
```

```java
// ContainerIntegration
/** Best-effort version of the detected container/framework itself (not the
 *  logging backend it routes through) -- e.g. "34.0.1.Final" for WildFly.
 *  Default empty; id() already supplies the name. */
default Optional<String> version() {
    return Optional.empty();
}
```

`AggregateLevelControl` gains the container's name as a constructor-supplied plain string, and
its version as a constructor-supplied `Supplier<Optional<String>>` — re-invoked fresh on every
`environmentReport()` call, not resolved once — rather than a dependency on `ContainerIntegration`
itself. Each container module's `activate()` already holds `this` and passes `this::version`
through, exactly where `id()` is already read today (`AgentBootstrap.publishControlSurface`'s
log line). `core` keeps depending on nothing but the SPI interfaces it already knows (§4.6). The
supplier indirection (rather than resolving eagerly at `activate()` time) is load-bearing, not
a style choice — see Decision #3's second revision note: for WildFly specifically, the version
genuinely isn't resolvable yet at the point `activate()` runs.

The JVM/OS facts (`java.version`, `java.vendor`, `os.name`, `os.version`, `os.arch`) and the
diagnostics-level property need no SPI at all — read directly as system properties inside
`AggregateLevelControl.environmentReport()` itself, since they're process-wide, not per
context. Only the logging backend can, in principle, differ per context (§15.4), so a small
new per-context `EnvironmentReportService` (`core`, alongside `DoctorService`/`TopService`)
wraps just `adapter.backendInfo()`; `environmentReport()` takes the first non-empty one across
registered contexts.

### State file (added post-implementation, small addition — no fresh sign-off needed)

One more process-wide fact, added once real use surfaced it as missing: the fully-qualified
path of the file this JVM persists `--for`/`--sticky` overrides to. Small enough to skip a
fresh artifact review (CLAUDE.md's own bar for one) — one naming question, confirmed inline
("state file", matching this codebase's existing vocabulary — `FileStateStore`, `.state.yaml`,
§6.3 "State store" — over "data file," which appears nowhere else).

New `StateStore` SPI method, same "empty unless overridden" discipline as `backendInfo()`/
`version()`:

```java
/** The on-disk location this store persists to, fully qualified, or empty
 *  for one with no single filesystem location to name (StateStore.noOp(),
 *  a future shared/external store). */
default Optional<Path> location() {
    return Optional.empty();
}
```

`FileStateStore` overrides it with its already-resolved, already-absolute `stateFile`. Unlike
`containerVersion`, this needs no supplier indirection: the `StateStore` is fully opened
(`FileStateStore.open()`, including resolving `-Dlogaperture.home`) *before* either composition
root (`NoneContainer`/`WildFlyContainer`) ever constructs its `AggregateLevelControl` — there is
no equivalent to `jboss.home.dir`'s premain-timing gap here, since `-Dlogaperture.home` is a
property this project owns end to end, never re-applied later by someone else's bootstrap code.
Confirmed directly: manually verified against the real `dev/wildfly` environment (a
`-Dlogaperture.home` pointed at a container-writable path, per that environment's own
`docker-compose.yml` comment about the image's real `$HOME` not being writable) that the
reported path is exactly where the file is written once an override is actually persisted, not
merely a plausible-looking guess.

**A real bug this fact caught, in the test harness rather than the feature:** `WildFlyContainerIT`
itself never set `-Dlogaperture.home`, so it inherited the same `$HOME`-not-writable problem
`dev/wildfly`'s own compose file already documents a fix for — every run of that suite had
silently been degrading to session-only persistence (`WildFlyContainer.openStateStore()`'s
existing `AccessDeniedException` → `StateStore.noOp()` fail-open path) and nothing in the suite
had ever asserted persistence strongly enough to notice, since none of its scenarios restart the
whole JVM. `env`'s own cross-process test caught it immediately (`State file —` where a real
path was expected) the first time it ran for real. Fixed by giving `WildFlyContainerIT` the same
`-Dlogaperture.home` `dev/wildfly` already carries.

One process-wide value, threaded through `AggregateLevelControl`'s constructor as a plain
(non-deferred) `String`, alongside `containerName` — both composition roots resolve their
`StateStore` before constructing their `AggregateLevelControl` already, so the ordering was
already right; `none` gains this fact for the first time too (it previously used the neutral
static `new AggregateLevelControl()`, which now exists only for tests that don't care about
either process-wide fact).

## Failure handling

- A fact this environment doesn't resolve (adapter returns `BackendInfo.EMPTY`, container
  `version()` empty, a system property somehow absent, `StateStore.location()` empty) is
  rendered as `null` in JSON; in text, every fact except **Diagnostics level** and **State
  file** is left out of the block entirely rather than shown as a placeholder — those two are
  shown either way (`—` when absent), since "not set" / "no persistence" are themselves useful
  facts for a bug report, not noise to suppress.
- `logctl env` never exits non-zero for a missing fact; a non-zero exit is reserved for "the
  command itself failed" (couldn't attach, JMX error), same convention as `doctor`.

## Testing

- Unit — JBoss LogManager adapter: `backendInfo()` resolves a real name/version from a
  well-known class's package version (mirroring `doctor.md`'s reflection precedent), degrades
  to `BackendInfo.EMPTY` if the class shape doesn't match.
- Unit — WildFly `ContainerIntegration`: `version()` resolves against fixture files copied
  verbatim from each real source Decision #3 settles on (Galleon's `provisioning.xml`, the
  classic product manifest), prefers Galleon when both are present, and degrades to empty when
  neither is.
- Unit — core: `AggregateLevelControl.environmentReport()` assembles the full report from JVM
  system properties, the constructor-supplied container name and version supplier, and the
  first context whose `EnvironmentReportService` resolves a non-empty backend; a throwing/empty
  fact (including the version supplier itself throwing) degrades to that one field being
  absent, never a failed call; the version supplier is re-invoked on every call, confirmed by a
  test where it starts empty and only resolves on a later call, matching the real-WildFly
  timing story in Decision #3's second revision note.
- Cross-process (`logaperture-it`): run `logctl env` against real standalone WildFly and
  confirm the real backend/container facts resolve (mirroring `WildFlyContainerIT`'s pattern
  in `doctor.md`/`top.md`).
- `logctl env --json` round-trips through a JSON parser with the documented shape.
- Unit — `FileStateStore.location()` resolves the real, already-absolute state file path.
- Unit — `none`/WildFly composition roots (`NoneContainerTest`/`WildFlyContainerTest`):
  `environmentReport().stateFilePath()` is the real state file path under that test's temp
  `logaperture.home`, absolute, ending in `.state.yaml`.
- Unit — core: `environmentReport()` reports `stateFilePath` as absent (`null`), never a
  failure, when constructed without one (the no-arg constructor's own case).
- Cross-process: `logctl env`/`--json` against real standalone WildFly assert an actual
  absolute path ending in `.state.yaml`, not merely that the "State file" line is present —
  the same lesson Decision #3's revision note already drew about weak assertions. Manually
  verified, additionally, that the reported path is where the file is actually written once an
  override is persisted (`dev/wildfly`, not just `logaperture-it`).

## Decisions (resolved at sign-off)

*Resolved during review:* all six recommendations below, taken as-is, no changes — see the
published review artifact linked from the roadmap discussion (issue #32).

**#1 — Command name.** `env`, `about`, or `info`.
*Recommendation:* `env` — it's the issue's own primary example, shorter than the alternatives,
and doesn't collide with `about`/`info` connotations (project metadata, help text) that this
command isn't.

**#2 — Scope of one invocation.** Report only the target JVM's environment (via the agent), or
also the `logctl` process's own environment (the machine/JVM running `logctl` itself)?
*Recommendation:* target JVM only. Per §8.2/§8.1, the attach transport is local-only — `logctl`
and the target JVM already share one OS/architecture, so the only fact that could genuinely
differ is `logctl`'s own Java runtime, which isn't what a bug report about the *target
application's* logging needs. Keeps the report to one JVM's worth of facts, one code path.

**#3 — Framework/container version source, and the "not recognized" line.** A manifest
`Implementation-Version` attribute (mirrors `AgentBootstrap.agentVersion()`/`Main.version()`
exactly) vs. a well-known version class/API per framework vs. a container-specific probe. And:
when nothing resolves, leave the line out entirely, or print an explicit `unknown`?
*Recommendation:* manifest attribute first, falling back to a well-known class's package
version where the manifest isn't populated (JBoss LogManager ships both) — same resolution
order already proven for the agent's own version, no new mechanism to trust. Leave the line
out entirely on failure (matches `doctor`'s "skip silently" precedent) rather than printing
`unknown`, which reads like a finding when this command doesn't have any.

**Revised post-implementation, after real-world feedback:** the original WildFly-specific
container-probe design ("`$JBOSS_HOME/version.txt`") shipped, passed its own cross-process
test, and still turned out wrong — a user ran it against real WildFly and got no version at
all. Investigation (`docker run` against the actual images) found that **no image tried ships
`version.txt`**, and, worse, that WildFly's own on-disk layout for its version changed between
major versions, so no single path is stable across the coverage matrix:

- **26.1.3.Final** (this project's pinned dev/IT image) — classic layout: a plain manifest at
  `$JBOSS_HOME/modules/system/layers/base/org/jboss/as/product/main/dir/META-INF/MANIFEST.MF`
  carries a `JBoss-Product-Release-Version` attribute. This is the exact file WildFly's own
  `org.jboss.as.version.ProductConfig` reads to print its boot banner ("WildFly Full
  26.1.3.Final ... started"). No `.galleon` directory exists on this image at all.
- **34.0.1.Final** — Galleon-provisioned layout (the default from WildFly 27 on): no product
  manifest exists at all; instead `$JBOSS_HOME/.galleon/provisioning.xml` names the resolved
  feature-pack as `wildfly@maven(...):current#34.0.1.Final`.

`WildFlyContainerIntegration.version()` now tries both, in order (Galleon first, since it's the
current default for new images), pure file reads either way — no `version.txt` anywhere, since
neither real image ships it. This is also the finding that exposed a **test-quality gap**: the
original cross-process assertions only checked that the word "WildFly" appeared in the output,
which is also true when no version renders at all (`nameAndVersion` falls back to the bare
name) — so the broken implementation passed its own IT run. The assertions now check for the
actual version number, not just the container's name.

**A second, more fundamental finding, from the same investigation:** fixing the file paths
alone still didn't work. `jboss.home.dir` is not actually a genuine JVM launcher `-D` property
at the point `WildFlyContainerIntegration.activate()` runs (premain time) — it reads back
`null` from `System.getProperty` there in every real launch tried, and only becomes visible
once WildFly's own bootstrap (`org.jboss.modules.Main` / `org.jboss.as`) has run far enough to
set it *programmatically*, strictly after premain (which runs before any application `main()`).
`detect()` tolerates this by design — `jboss.home.dir` is only one of its two disjuncts, and the
`sun.java.command` match is what actually fires — but `version()` had no such fallback, so
resolving it eagerly at `activate()` time silently baked in "no version" on every real launch,
forever, regardless of which file-layout fix was in place.

**Fix:** `version()` is no longer called once, eagerly, at agent-install time. `AggregateLevelControl`
now takes the container's version as a `Supplier<Optional<String>>`, re-invoked fresh on every
`environmentReport()` call (`WildFlyContainerIntegration.activate()` passes `this::version`) —
by the time a human actually runs `logctl env`, the server has long finished its own bootstrap,
so the same file reads that failed at premain succeed. This costs nothing (plain file I/O off
the hot path) and self-heals rather than requiring the fact to be known at one specific moment.

**#4 — `--json`.** In the first slice, or a fast-follow?
*Recommendation:* first slice — `doctor.md` and `top.md` both shipped `--json` in their first
slice as a matter of course, and this shape is a flat object, cheaper to add now than to retrofit.

**#5 — Agent self-diagnostics (§4.5).** Include `-Dlogaperture.diagnostics.level` in the same
report, or keep it out?
*Recommendation:* include it. A support thread's first question after "what versions" is often
"turn on diagnostics and try again" — showing the current setting (including "not set") closes
that loop in the same paste instead of opening a second one.

**#6 — Version-detection depth for this slice.** Real backend/container version detection for
JBoss LogManager + WildFly only this slice (name still reported for `none`/other adapters via
whatever's cheaply available, version left out), with Logback and every other container as an
explicit fast-follow — or hold the whole feature until more than one backend/container has real
detection?
*Recommendation:* JBoss LogManager + WildFly only, same reasoning `doctor.md` used to scope
itself to one adapter (WildFly is "the primary motivating environment," §16) — the new SPI
default methods make every other adapter/container additive later, not a spec change.

## Exit criterion

Met:

- `logctl env` against a plain `java -jar` process reports agent version, `logctl` version,
  Java, and OS accurately, with backend/container facts absent (the Logback adapter used by
  `none` doesn't override the new SPI methods this slice) — confirmed unit-tested
  (`AggregateLevelControlTest`).
- `logctl env` against standalone WildFly additionally reports the real JBoss LogManager
  backend version and the real WildFly container version — confirmed cross-process against a
  real WildFly 26.1.3.Final (`WildFlyContainerIT`, assertions on the actual version numbers, not
  just the names): the backend line (`JBoss LogManager 3.1.1.Final`, read off the root logger's
  own runtime package) and the container line (`WildFly 26.1.3.Final`, read from the classic
  product manifest — see Decision #3's revision note) both render correctly. The Galleon
  source (34.0.1.Final) was confirmed by hand, not by an automated cross-process test — this
  project's pinned dev/IT image is 26.1.3.Final.
- `logctl env --json` round-trips through a JSON parser with the documented shape — confirmed
  cross-process and unit-tested (`JsonTest`).
- A fact this environment can't resolve is absent from both renderings, never a command
  failure — confirmed by a unit test exercising a throwing/empty adapter and container.
- No capability beyond `view` is required.
- `logctl env` reports the JVM's actual state file path, fully qualified, matching where
  overrides are really persisted — confirmed unit-tested (`FileStateStoreTest`,
  `NoneContainerTest`, `WildFlyContainerTest`), cross-process (`WildFlyContainerIT`, asserting
  the real path shape, not just line presence), and manually verified end to end against
  `dev/wildfly` (set a `--sticky` override, confirmed the reported path is exactly the file
  that appeared on disk with that override in it).
