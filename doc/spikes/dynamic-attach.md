# Spike — dynamic attach and JBang

Status: **done.** Every question is answered except those listed under "Not established".

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §2 (`premain` primary, `agentmain`
best-effort), §8.1 (CLI over the Attach API, JEP 451), §13 risk 3 (loggers created before install).
Related: [`doc/specs/cli-transport.md`](../specs/cli-transport.md) (the deferral of `VirtualMachine
.loadAgent`), [`doc/specs/doctor-agent-order.md`](../specs/doctor-agent-order.md),
[`early-handler-install.md`](early-handler-install.md).
Issue: [#219](https://github.com/ddeuchert/logaperture/issues/219).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the harness that produced it. The harness is not committed. See "Harness" below.

## Question

1. If the agent is loaded into a JVM that started without `-javaagent:`, how much of LogAperture
   works, on plain Logback and on WildFly?
2. What does JEP 451 cost on JDK 17, 21 and 25?
3. Does `logctl doctor` cope when there is no `-javaagent` entry?
4. Is JBang viable as a way to run `logctl`? It was dropped on 2026-09-27 because `logctl` needs
   the agent attached at launch; that reason weakens if dynamic attach works.

## Environment

One machine. Every result below comes from it.

- Linux (Fedora, kernel 7.2), Temurin JDK 17.0.20, 21.0.12 and 25.0.4, JBang 0.142.0 (unpacked in
  a scratch directory, not installed).
- Agent and `logctl` built from `develop` at 9874ad4 (`1.0.0-beta.2-SNAPSHOT`).
- Plain JVM: a 20-line app logging once a second through SLF4J 2.0.17 / Logback 1.5.12 (root at
  INFO) and through `java.util.logging`. Loaded with `VirtualMachine.loadAgent` from a separate
  JVM, which is what a `logctl attach` would do.
- WildFly: `quay.io/wildfly/wildfly:26.1.3.Final-jdk17` started **without** the agent, with the
  sample WAR deployed, then attached from inside the container.

## Findings

### 1. `agentmain` already exists, but on plain Logback it silently does nothing

`LogApertureAgent.agentmain` delegates to the same `AgentBootstrap.start` as `premain`, and the
manifest carries `Agent-Class`. Loading the shipped jar into a running Logback app completes
("loaded"), and then `logctl status` reports *No LogAperture-enabled JVM found*. There is no
error, no warning and no `logaperture.version` property.

Cause: `LogbackLoadDetector.awaitLogbackAndThen` installs a class-file transformer and waits for
`ch.qos.logback.classic.LoggerContext` to load. By attach time it has loaded, so the event never
comes and the install never starts. This is the exact case §13 risk 3 anticipated.

### 2. A small change makes plain Logback work

A throwaway patch scanned `Instrumentation.getAllLoadedClasses()` first and, if `LoggerContext`
was already loaded, started the same `confirmBoundThenRun` poll directly:

```java
for (Class<?> c : inst.getAllLoadedClasses()) {
    if ("ch.qos.logback.classic.LoggerContext".equals(c.getName())) {
        Thread t = new Thread(() -> confirmBoundThenRun(onReady), "logaperture-detector");
        t.setDaemon(true);
        t.start();
        return;
    }
}
// ...then the existing transformer, for the not-yet-loaded case
```

With it, against a Logback app that had been running for several seconds:

| Check | Result |
|---|---|
| `logctl status` | works |
| `logctl set logger com.acme DEBUG for 1m` | works: DEBUG lines appear in the app's output |
| `logctl doctor` | works (see finding 6) |
| `logctl add rule drop --help` | help renders (the rule path itself was not exercised) |
| JDK 17, 21, 25 | `set logger` works on all three |

The patch was reverted; it is not committed. The real change belongs in a feature spec.

### 3. WildFly works with no change to the agent

Attached to a WildFly 26.1.3 that had been running for several seconds with the sample WAR
deployed. No patch was needed: the WildFly integration is not a class-load gate the way the
Logback one is.

- The agent reported *active (WildFly)* and `logctl status` found it.
- `set logger org.logaperture.sample.work.Worker DEBUG for 2m` took effect; the DEBUG line
  appeared in `server.log`, and the handler-floor hint named `CONSOLE` correctly.
- `doctor` ran its WildFly checks (`FILE` and `CONSOLE` autoflush, no size cap); `reset loggers`
  reverted the override.
- The one error was the state store: the image's `$HOME` is not writable by the `jboss` user, so
  the agent logged a stack trace and fell back to session-only persistence. This is the same
  image quirk the dev environment already works around with `-Dlogaperture.home`; it is not
  specific to attach. On attach there is no launch flag to carry it, so the property has to be
  on the target's command line already, or the agent has to pick a writable fallback.

### 4. JEP 451

| JDK | Dynamic load | Behaviour |
|---|---|---|
| 17 | allowed | no warning |
| 21 | allowed | four-line `WARNING: A Java agent has been loaded dynamically` on the **target's** stderr |
| 25 | allowed | the same four lines; the text still says loading "will be disallowed by default in a future release" |
| 25, target started with `-XX:-EnableDynamicAgentLoading` | **refused** | the *attaching* JVM gets `AgentLoadException: Dynamic agent loading is not enabled. Use -XX:+EnableDynamicAgentLoading to launch target VM.` |

So today the cost is a warning in the application's own console, and the flag only matters if
the target explicitly turned dynamic loading off. A future JDK that flips the default would
make `-XX:+EnableDynamicAgentLoading` mandatory on the target at launch, which defeats the
"no restart" point of attaching. `logctl attach` should print this when it sees the exception.

### 5. A second load is harmless

`AgentBootstrap.STARTED` (issue #120) already makes `agentmain` after `premain`, or a second
attach, log one line and return. Not re-tested here; it is covered by its own tests.

### 6. `doctor` copes, quietly

On a `-javaagent` launch `doctor` runs two checks, including *logaperture-agent.jar is the first
-javaagent*. On an attached JVM it runs one: the agent-order check is omitted with no line.
That matches `doctor-agent-order.md` ("agents loaded by dynamic attach never appear in the
input arguments"), but a user will not know why it is missing. Worth one explanatory line.

### 7. JBang is viable as a zero-install client

- `jbang org.logaperture:logaperture-cli:1.0.0-beta.1 status` resolved the published CLI from
  Maven Central and ran it against a `-javaagent` JVM; start-up was about 1.5 s with a warm
  cache. `Main-Class` in the manifest is all JBang needs.
- `jbang info classpath org.logaperture:logaperture-agent:1.0.0-beta.1` resolves the agent jar
  to a local path, which is what `loadAgent` needs. So a JBang-run `logctl attach` can find the
  agent without a separate download step.
- JBang uses the running JDK, so `jdk.attach` is present on a JDK install. A JRE-only machine
  would not have it; that is true with or without JBang.

What JBang does not change: the agent still has to be inside the JVM, so JBang is a way to run
the client, not a way to get the agent into a JVM that was started without one. Only dynamic
attach does that.

## Not established

- **Plain `java.util.logging`.** `NoneContainerIntegration` binds only Logback, so there is
  nothing to attach to on a plain JUL app, with or without dynamic attach (the 1.0 plain-JVM support is
  Logback levels only, spec §11). Attach does not make it worse.
- **Attach before Logback is loaded.** The existing transformer path should work (new loads are
  observed after `agentmain`); not tested.
- **Quarkus, Spring Boot, Tomcat.** None are supported yet, and their attach behaviour depends
  on the work that adds them. Quarkus's `QuarkusDelayedHandler` problem (spike #113) would
  apply to attach as well.
- **Windows and macOS.** The Attach API's transport differs per OS; only Linux was run.
- **WildFly versions other than 26.1.3 / JDK 17**, and WildFly behind a service wrapper.
- **A real `logctl attach`** and a JBang script that performs the attach. Only the pieces were
  checked: `loadAgent` from a separate JVM, and JBang resolving both jars.
- **Events logged before the attach.** Obviously not seen; a state-store resume would still
  apply persisted overrides, but that was not run (the WildFly state store was unwritable).

## Recommendation

Dynamic attach is feasible and cheaper than the spec's "best-effort" language suggests. The
engine, the CLI and the WildFly integration need no change; plain Logback needs one readiness
change. Do it **after 1.0.0** as a `logctl attach <pid>` command, with its own spec, and keep it
out of the 1.0 guarantees: every 1.0 claim is still validated against `-javaagent`.

Estimate for the feature (working days): readiness gate "already loaded" for Logback and its
test, 0.5; `logctl attach` (discovery of the agent jar next to the CLI, the exception messages
for JEP 451 and for no `jdk.attach`, refuse a JVM that already has the agent), 1; the agent
logging why it did not start when it is attached, 0.5; the state-home fallback, 0.5;
`doctor` explanatory line, 0.25; IT on Logback plus the existing WildFly IT, 1; guide and spec,
0.5. About 4 days.

JBang: no change to the 1.0 distribution plan. After `logctl attach` exists the original reason
for dropping it no longer holds, so a one-line `jbang` recipe in the guide is cheap to add then.
It stays a way to run the client.

## Harness

Not committed. A 20-line Logback/JUL logging loop; a 3-line `Attach` class calling
`VirtualMachine.attach(pid).loadAgent(agentJar)`; the patch in finding 2 applied to
`LogbackLoadDetector`, built with `./mvnw -pl logaperture-agent,logaperture-cli -am clean
package`; a `docker run` of the pinned WildFly image with the agent and CLI jars mounted and
the sample WAR deployed; JBang unpacked in a scratch directory.
