# Spike — WildFly handler names before `server-state=running`

Status: **done.** Every question is answered; the follow-up is a decision for David.

Parent spec: [`doc/specs/handler-floor-control.md`](../specs/handler-floor-control.md), "WildFly
handler name resolution (issue #14)" and its "Gated on `server-state == running`" paragraph
(issue [#66](https://github.com/ddeuchert/logaperture/issues/66)).
Related: [`early-handler-install.md`](early-handler-install.md).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the probe code that produced it. The probe is not committed. The sample WAR's startup delay
is kept (see "What is kept").

## Question

On WildFly, `logctl list handlers --show-all` shows `CONSOLE`, `FILE` and the other configured
names only once the server reports `server-state=running`, which is after every deployment is up.
For an application with a start of several minutes, the names are missing for minutes after the
logging subsystem is long since configured.

1. Can the names be resolved earlier through the MSC service container?
2. Can `/subsystem=logging` be read from the model before `running`?
3. Is there another source of the name-to-instance mapping that is ready earlier?

## Environment

- Linux (Fedora, kernel 7.2), Docker, `quay.io/wildfly/wildfly:26.1.3.Final-jdk17` (WildFly Core
  18.1.2.Final), standalone, default `standalone.xml` (handlers `CONSOLE` and `FILE`).
- Agent and `logctl` built from `develop` at `b8d2161`, with throwaway probes in
  `WildFlyHandlerNameResolver` (`-Dlogaperture.wildfly.handlerNames.debug=true`).
- The sample WAR with a 60 s startup delay, `-Dsample.startupDelaySeconds=60`, standing in for a
  slow application. Four boots in all; the timings below repeat to within a second.

## Findings

### Timeline of one boot (60 s delay)

| Time | Event |
|---|---|
| 0 s | JVM start (first log line) |
| about 2 s | **Handler names and instances available** (finding 3) |
| about 4 s | `WFLYSRV0027` deployment of the sample WAR starts |
| about 64 s | the delay ends, `WFLYSRV0010` deployed, `WFLYSRV0025` started, `server-state=running` |
| about 64 s | the existing resolver binds `CONSOLE` and `FILE` |

So the names the resolver waits for were available about 60 s sooner, and a slow application
moves the last line, not the second.

### 1. The MSC service container has nothing to read

The container holds 38 services at the first sweep and 640 to 720 once the subsystems are in.
None is a logging handler: the only names containing `logging` or `handler` are unrelated
(`jboss.ejb3.logging.enabled`, `jboss.ws...recording-handlers`, `org.wildfly.extension.undertow
.handler.welcome-content`, the deployment's `logging.configuration`). WildFly's logging subsystem
doesn't register its handlers as services in this version. **Dead end.**

### 2. `/subsystem=logging` is not readable before the boot finishes

With the `server-state` gate lifted and the read tried while the server was `starting`:

- At the first sweep, the read fails with `WFLYCTL0030` (no resource definition), which is the
  failure #66 describes.
- Thirty seconds and sixty seconds into the sample application's delay, with the subsystem long
  configured, it fails with `WFLYCTL0216` (management resource not found). WildFly logs each as
  an `ERROR` (`WFLYCTL0013`), seven per attempt.

The model is built inside the one boot operation and isn't visible to readers until it commits,
which is at `running`. There is no earlier "core is ready" signal in the model. **Dead end,** and
it explains why the gate was the only reliable test.

### 3. The logging subsystem's own configuration has the names and the instances

`org.jboss.as.logging.logmanager.ConfigurationPersistence.getConfigurationPersistence(LogContext)`
returns the object WildFly's logging subsystem configures the log context through. It implements
`org.jboss.logmanager.config.LogContextConfiguration`, so:

```
persistence.getHandlerNames()                       -> [CONSOLE, FILE]
persistence.getHandlerConfiguration(name).getInstance() -> the live java.util.logging.Handler
```

A couple of seconds into the boot, 60 s before `running`, it returned both names, with the
`ConsoleHandler` and the `PeriodicRotatingFileHandler`. Both were the **same instances** (`==`) as
the handlers the adapter finds on the logger tree, in all four boots. No model read is made and
no `WFLYCTL0013` is logged.

Two details:

- The first call, a second earlier, returns a `ClassCastException`: the log context still has the
  `PropertyConfigurator` that reads `logging.properties`, not yet WildFly's persistence. The
  next sweep succeeds, so it needs a retry, not a wait for `running`.
- The class is reached through the boot module loader as module `org.jboss.as.logging`, the same
  way the resolver already reaches `org.jboss.as.server`. Methods are called through the public
  interfaces (`LogContextConfiguration`, `HandlerConfiguration`, `ObjectConfigurable`), per the
  existing rule against calling through `getClass()`.

This is an exact match by identity, which is stronger than the shape matching (type and file
name) that the model path needs. An earlier attempt at this route, recorded in the #14 spec as
retired, "found nothing"; it was most likely run too early or against the wrong object.

## Conclusions

- The delay is real and it is by design: the only gate that was safe for the **model** path was
  `running`.
- A source that is ready about 60 s earlier exists, needs no model read, and binds by identity.
- Its cost is a dependency on two WildFly-internal classes (`ConfigurationPersistence` in
  `wildfly-logging`, and `LogContextConfiguration` in JBoss LogManager). Both are stable across
  the supported range in principle, but this was measured on 26.1.3 only.

## Not established

- WildFly 27 and later, and the oldest supported 26.1.x: the class names and the timing were
  seen on 26.1.3 only.
- EAP and domain mode (not supported).
- A custom handler (`custom-handler`) and a handler added later at runtime: not exercised. The
  existing model path would still cover them once `running` is reached.
- Whether `getInstance()` can return `null` for a handler still being constructed. It didn't in
  four boots.

## Recommendation

Make the persistence route the **first** source for names, with the existing model route kept as
the fallback and the `running` gate unchanged for it. Names would then appear within a few
seconds of start on this version, whatever the application's start time, and the
`WFLYCTL0013` noise stays gone. If the persistence route fails (a different class layout), the
behavior is today's.

That is a change to the #14 design, so it needs a short spec update (the "Gated on `server-state`"
paragraph and a new "earlier source" paragraph) and an issue, and would go in with a WildFly IT
that lists the handlers while a deployment is still starting. It touches no MXBean or `--json`
surface.

## What is kept

`logaperture-sample-war` reads `-Dsample.startupDelaySeconds` (default 0) and sleeps that long in
its context listener before doing anything else; `dev/wildfly/docker-compose.yml` passes it from
`SAMPLE_STARTUP_DELAY_SECONDS` and the resolver debug flag from `HANDLER_NAMES_DEBUG`, so the
slow-start case can be reproduced:

```sh
SAMPLE_STARTUP_DELAY_SECONDS=60 HANDLER_NAMES_DEBUG=true python3 dev/wildfly/wildflyctl.py up
```

The probes in `WildFlyHandlerNameResolver` (service-name dump, early model read, persistence
read) were removed.
