# Shared class-loader detector

Status: **Signed off 2026-11-01** (decisions C1 to C8 below, every recommendation taken). Release `1.1.0`.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.4 (class-loader model), §15.4
(one JVM, several frameworks), §15.5 (assume the hooks will be discarded), §15.7 (Spring Boot),
§15.8 (Tomcat), §17.1 (1.1.0). Evidence: [`doc/spikes/log4j2-adapter.md`](../spikes/log4j2-adapter.md)
(Spring Boot SB2, Tomcat TC1 to TC7, WildFly WF1 to WF7, and "Init timing"). Builds on:
[`plain-jvm-jul.md`](plain-jvm-jul.md) (decisions #1 and #2, which this one extends).
First consumer: [`log4j2-adapter.md`](log4j2-adapter.md) (#302). Next consumer: Logback in a
Spring Boot fat jar and in a Tomcat webapp (1.2.0), unchanged. Tracking issue:
[#301](https://github.com/ddeuchert/logaperture/issues/301).

## Functional summary

After this feature, the user will be able to:

- Use the agent, with nothing extra on the command line, in an application whose logging
  framework is bundled inside the application and not on the JVM's class path: a Spring Boot fat
  jar, a Tomcat webapp, a WildFly deployment with its own copy of the framework.
- Have a webapp that is redeployed or undeployed get its overrides back on the new copy
  automatically, without the agent keeping the old copy in memory.
- Run Tomcat with two webapps that bundle different versions of the same framework, and control
  each one separately in `logctl list loggers`.
- See, in one `logctl` session, both the container's own logging (for instance Tomcat's, which
  logs through `java.util.logging`) and the logging of the webapps on it.

The detector itself has no command. It is what makes the Log4j 2 adapter (and, in 1.2.0, the
Logback one) reachable in these setups.

Guide pages this changes: `get-started/install-plain-jvm.md` (an app whose framework is bundled
in a fat jar), a new `get-started/install-tomcat.md`, `get-started/install-wildfly.md` (a
deployment that excludes the server's own Log4j API), `troubleshooting.md` (a framework the agent
found but could not bind), and `index.md` (the support table). `reference/agent-options.md` does
not change (no new property or argument, see C4).

Not part of this feature: the Log4j 2 adapter itself (#302); Logback in a fat jar or a webapp
(1.2.0); a warning for logger names no bound backend can reach (#303).

## What is already established

All from the spike, on one machine, JDK 21 (WildFly: 17); nothing here is new measurement.

- **The system class loader cannot see these frameworks.** In a Spring Boot fat jar Log4j lives in
  `LaunchedClassLoader`; in a Tomcat webapp in a `ParallelWebappClassLoader`; in a WildFly
  deployment in a `ModuleClassLoader`. Adapter code on the system class path cannot link against
  it (SB2, SB11).
- **A detector finds them.** A daemon thread scanning `Instrumentation.getAllLoadedClasses()` for
  a marker class saw every one of them, in all four setups (TC1, WF1), and it leaves the
  application's own configuration alone (I-4); the alternatives that touch the framework first do
  not (I-1 to I-3).
- **A separate loader holds the adapter.** A loader whose classes come from outside the system
  class path, with the framework's packages delegated to the owning loader, links and works (SB2).
  It must be narrow: a stopped webapp loader refuses even JDK classes, so a bridge loader whose
  parent was the webapp loader failed at shutdown (TC6).
- **Lifecycle comes from the framework.** The framework's own shutdown listener tells the
  adapter instance to let go, and the detector's next sweep binds the new copy (TC5, WF4). No
  Tomcat or WildFly API is needed.
- **Two traps, both in the detector (WF5, WF6).** `ModuleClassLoader` overrides `equals` and
  `hashCode`, so the old and new loaders of a redeployed deployment compare equal: a `WeakHashMap`
  of bound loaders loses them. And `getAllLoadedClasses()` returns a class whose loader is already
  being unloaded: an instance bound to it waited forever and kept the loader alive.
- **Shipping code is clear of WF5.** No shipping code keys a weak map by class loader today.

## Decisions

| Id | Decision | Options | Outcome |
|---|---|---|---|
| C1 | Which backends bind in one JVM | (a) Keep `plain-jvm-jul.md` #1: one backend, chosen up front. (b) Bind every framework found, each when it initialises. (c) Keep #1 for the *system class path* backends (Logback, else JUL), and bind every framework the detector finds *in addition*. | **(c).** (a) cannot serve Tomcat (JULI plus Log4j webapps) and, with `log4j-jul`, ends up controlling a JUL tree that is only a front for Log4j. (b) is what #1 ruled out: the agent initialises JUL itself, so a JUL context would appear in every Logback app. (c) leaves every 1.0 and 1.1 plain-JVM app as it is, and adds contexts only where the detector finds a bundled framework. One refinement: JUL is **not** bound when `java.util.logging.manager` names a Log4j (`org.apache.logging.log4j.jul.LogManager`) or Logback manager; that tree is a front for a backend this detector already binds, and a second context for it would double-list every logger. Read as a system property, which touches no JUL. |
| C2 | Where the adapter classes live, and how the bridge loader reads them | (a) A separate jar on disk next to the agent jar. (b) A jar nested inside the agent jar, extracted to a temporary file at bind time. (c) Class files stored in the agent jar under a prefix the system class loader never looks in, read by the bridge loader through the agent jar's own resource stream. | **(c).** (a) breaks the "one jar" distribution (`-javaagent:logaperture-agent.jar` and nothing else) and `logctl attach`, which embeds the agent. (b) needs a writable temp directory, which the constrained environments of §16 do not always have. (c) needs neither. The system class loader cannot load them by accident, which is the reason the spike kept them off the class path (SB2). The cost is a build step that moves the adapter module's classes under the prefix; checked by a test that no such class loads from the system class path. |
| C3 | What the bridge loader delegates | (a) Everything to the owner, with the platform loader as fallback. (b) Narrow: the JDK from the platform loader; the framework's packages from the owner; LogAperture's own `api`, `core` and `bridge` packages from the system loader; everything else from the bridge's own jar. | **(b).** (a) is what failed in TC6. The system loader is the agent's, which is never stopped, so delegating LogAperture's own types to it is safe. A class outside all four sets is a load error in the bridge, not a fall-through to the owner. |
| C4 | Cadence and bounds | (a) Poll like `plain-jvm-jul.md` #2: every 100 ms for 60 s, then every 1 s, never give up; a bound instance gives up 3 s after it was created if its framework has no started context. (b) Make the numbers properties. | **(a), constants, no property.** The cadence and the never-give-up rule are the ones already shipped and measured for JUL; the 3 s bound is from WF6, where 4 of 4 loaders were collected with it and 2 leaked without. A property is a guide page and a drift test for a number nobody has asked to change; add one if someone does. |
| C5 | One scan, or one per watcher | (a) Each watcher (JUL, Logback, Log4j 2, later others) runs its own thread and scan. (b) One scanner thread, one `getAllLoadedClasses()` per tick, every watcher checked against the result. | **(b) for the new watchers; leave `JulLoadDetector` and `LogbackLoadDetector` as they are in 1.1.0.** They are shipped and signed off, and the extra cost of two scans a second is small (`plain-jvm-jul.md` estimates well under a millisecond per scan of 10,000 classes, not yet measured). Moving them onto the shared scanner is a clean-up for 1.2.0, when Logback joins it. |
| C6 | Where the detector runs | (a) In the `none` integration only. (b) In the agent, independent of which `ContainerIntegration` was chosen, handing each bound context to that integration's container. | **(b).** WF3 showed that on WildFly the real agent (the `wildfly` integration) never sees a deployment that brings its own Log4j; (a) would leave exactly that case uncovered, and Quarkus apps can bundle one too. Costs one small addition to the SPI: a way for the agent to hand a `ContextHandle` to the container it activated (`AggregateLevelControl` already has `register` and `removeContext`). |
| C7 | Context identity | (a) The class loader's identity hash. (b) A name the framework gives its own context when it has one, else a counter within the JVM run. | **(b).** An identity hash changes every run and every redeploy, so persisted state could not be matched. A framework's own name (Log4j: `log4j-web`'s `/appA`) is stable across a redeploy (TC3). Each consumer spec says how it forms the name; the detector only requires that it is stable across a redeploy when the framework offers a name, and carries the `framework:` prefix so two frameworks cannot collide. |
| C8 | Non-detection | (a) Silent. (b) A `Diagnostics` line when a loader was found and the bind failed or gave up; `doctor` reports it. | **(b).** A silent failure is the "reassuring answer that does nothing" of WF3 again. The line names the framework, the owning loader's class and name, and the reason. Not a new check id in this spec: `doctor` reuses the consumer's (see `log4j2-adapter.md` A12). |

## Scope

1. **The scanner.** A daemon thread started by the agent, independent of the container
   integration (C6), with the cadence of C4 and a single scan per tick (C5). It checks each
   registered watcher's marker class, by name, against the loaded classes. It loads nothing and
   calls into no framework.
2. **The watcher contract.** A watcher names: the marker class; the package prefixes the owner
   supplies (C3); the entry class in the bridge jar; and how a bound instance forms its context
   name (C7). The Log4j 2 watcher is the first; Logback is the next, with no change to the
   contract.
3. **Identity tracking.** The set of loaders already bound is kept as weak references compared
   with `==`, never as a `WeakHashMap` or any structure that calls `equals` or `hashCode` on a
   loader (WF5). Entries whose reference has been cleared are dropped on each scan.
4. **The bridge loader** (C2, C3). One per bound owner. Closed when its instance lets go.
5. **The bound instance.** Created in the bridge loader; binds only once its framework has a
   started context; gives up after 3 s with none and closes its bridge loader (WF6); lets go when
   the framework's shutdown listener fires, removing its listeners and ending its threads (TC5).
   The instance's threads take the system loader as context class loader, so a container does not
   attribute them to the webapp (TC7).
6. **Hand-off to the container** (C6): `register` on bind, `removeContext` on let-go, keyed by
   the stable context key (C7).
7. **The readiness constraint** (spike, "Init timing"): nothing in the scanner or the watcher
   touches a framework class, and nothing touches `java.util.logging` when `log4j-jul` or another
   framework is its manager, before the instance's readiness check passes. Framework types appear
   only in classes the bridge loader defines.
8. **Docs.** §4.4 gains the bridge-loader description and the pointer to this spec; §11 and §15.4
   take C1; the guide pages in the Functional summary, with output captured from real runs.

## Acceptance

A test harness app per setup, in `logaperture-it`, each a small app that logs once a second through
a bundled framework; the first consumer is Log4j 2 (`log4j2-adapter.md`), so these tests run
against it. All are in the 1.1.0 CI.

- **Fat jar:** the scanner finds the framework in the application's loader and binds an instance
  that links; the adapter classes do not load from the system class path (checked by looking up
  the class from the system loader).
- **Tomcat, two webapps, two framework versions:** two instances, each controlling only its own
  webapp, no linkage errors; a redeploy of one webapp ends its instance, and a new one is bound
  within a few seconds with the override back; an undeploy ends it with no "Illegal access"
  message in Tomcat's log.
- **Leak test, weak-reference based:** after a redeploy and after an undeploy the old loader is
  collected (`System.gc()` and a weak reference, polled), and Tomcat's own leak detection reports
  nothing. Repeated 10 times in one JVM.
- **WildFly deployment with a framework of its own** (excluding the server's Log4j API): the
  `wildfly` integration is active and the deployment is still found and bound.
- **Identity:** a test double whose `equals` returns `true` for two distinct loaders; the second
  is still bound (WF5).
- **Dead loader:** a loader that is being unloaded is handed to the scanner; the instance gives up
  within 3 s and the loader is collected (WF6).
- **Untouched application:** an app that sets its own configuration file and context selector in
  `main()` keeps both, with and without `log4j-jul` as the JUL manager (I-1 to I-4).
- A plain-JVM app on Logback or on JUL alone is unchanged in behaviour: same contexts, same
  startup line.

## Behaviour to document rather than change

- A framework that no watcher knows is not found. `doctor` cannot tell the user what was missed.
- Binding happens up to about 100 ms after a context starts, longer on the 1 s cadence once an
  app has been up a minute (a webapp deployed late). Lines logged before then go out under the
  application's own levels.
- A webapp's overrides are broadcast like every other (`wildfly-support.md`, "Broadcast
  semantics"): a name set on one context is pre-set on all.

## Out of scope

The Log4j 2 adapter (#302); Logback in a fat jar or a webapp (1.2.0); the warning for names no
backend can reach (#303); moving `JulLoadDetector` and `LogbackLoadDetector` onto the shared
scanner (C5); OSGi; a framework installed as a shared or global module several deployments use
(not tested, see below).

## Not established

- The cost of the scan in a large app, with three or four watchers (estimate only).
- A framework shared by several webapps from Tomcat's `lib/`, or a WildFly global module: one
  owner, many contexts. The contract assumes one instance per owner and several contexts per
  instance are allowed, but nothing exercised it.
- Spring Boot devtools restarts (a second loader in the same JVM).
- JDK 25 (every measurement was JDK 21, WildFly JDK 17).
- Whether the system class loader delegation in C3 holds under JPMS with the agent on the module
  path; the agent jar is on the class path in every setup tried.

## Estimate and slot

About 6 working days: scanner, watcher contract, identity tracking, bridge loader and the
hand-off to the container, 3; the build step for C2 and its test, 1; the fat-jar, Tomcat, WildFly
and leak tests, 2 (the harnesses exist as spike probes and have to be rebuilt as committed
tests). Release `1.1.0`, first in the build order, ahead of the Log4j 2 adapter (#302), which
cannot start without it.
