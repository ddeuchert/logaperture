# Spike — early handler install aborts WildFly startup

Status: **done.** Trigger isolated to a category of install step; mechanism not identified.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §15.6 (the premain gotcha),
§18.14 (filtering events logged before the container's logging is ready). Related:
[`doc/specs/wildfly-support.md`](../specs/wildfly-support.md) ("Detection and the premain gotcha").
Issues: [#86](https://github.com/ddeuchert/logaperture/issues/86) (the fix),
[#87](https://github.com/ddeuchert/logaperture/issues/87) (the open mechanism question).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the harness that produced it. The harness is not committed — see "Harness" below.

## Question

A real WildFly launch aborts at startup with `org.jboss.modules.ModuleNotFoundException:
org.jboss.as.standalone` when the LogAperture agent is loaded. Which part of LogAperture's
install causes it, and why?

## Environment

One machine, one launch configuration; every result below is from it.

- Windows, JDK 21 (from the stack frames), WildFly 33.0.2.Final (WildFly Core 25.0.2), JBoss Modules 2.1.5, started through
  a Tanuki Java Service Wrapper 3.2.3 (`WrapperSimpleApp` → `org.jboss.modules.Main`).
- Several other `-javaagent`s, in this order: perfmon4j, an `fss-security-agent`, a `destiny-agent`,
  then LogAperture (later moved to first); `fss-security-agent` was also listed a second time.
  Plus a SentinelOne `-agentpath`.
- `-Djava.util.logging.manager=org.jboss.logmanager.LogManager`,
  `-Dlogging.configuration=file:../standalone/configuration/logging.properties`, and
  `-Djboss.modules.system.pkgs=...,org.jboss.logging,org.jboss.logmanager` — the last one makes the
  JBoss LogManager classes come from the system classloader rather than a module.

## Symptom

```
org.jboss.modules.ModuleNotFoundException: org.jboss.as.standalone
        at org.jboss.modules.ModuleLoader.loadModule(ModuleLoader.java:301)
        at org.jboss.modules.Main.main(Main.java:355)
        ... org.tanukisoftware.wrapper.WrapperSimpleApp.run(WrapperSimpleApp.java:240)
```

No `Caused by`. It appears right after the last agent's `premain` finishes, at the start of
`Main`; the JVM then exits (the vault agent's `NoClassDefFoundError` and the "Quarantine file
scanner terminated" lines that follow are shutdown fallout).

- With LogAperture **lower** in the `-javaagent` list: rare, intermittent.
- With LogAperture **first**: every start.
- With `-Dlogaperture.disabled=true` (jar loaded, our `premain` inert): boots cleanly.
- With the perfmon4j `-javaagent` removed (its classes still present through `destiny-agent`):
  still fails, so perfmon4j-as-agent is not required to reproduce.

## What the logs showed

With LogAperture first and debug diagnostics on:

- `org.jboss.logmanager.LogManager loaded by ...ClassLoaders$AppClassLoader` was the first
  LogAperture line — the class is loaded by the system classloader at `premain`, before jboss-modules
  runs. Perfmon4j (which watches class loads through the JVM) reports it as found in a run with
  LogAperture disabled too, so something else loads it early regardless of us.
- The install completed about 0.7 s after JVM start, including resuming the sticky rules (audit
  lines with `source=resume`) and the JMX registration. The design assumed this would be after
  jboss-modules had loaded the LogManager. The readiness gate
  (`WildFlyLogManagerReadiness`: "the class is in `getAllLoadedClasses()` and the manager property
  is set") was already true at `premain` on this launch.
- Side effect that mattered for [§18.14](../logaperture-spec.md): with the early install, a sticky
  trim rule *did* apply to another agent's `premain`-time `ERROR` — those agents log through the JBoss
  LogManager's boot handlers, which existed at `premain`. This is exactly what the early install is
  bought with, and exactly what is being given up below.

## Method

A throwaway harness added `-Dlogaperture.experiment=<mode>` and related flags to defer or skip
individual steps of the WildFly install (`WildFlyContainerIntegration`, `WildFlyContainer`). Each
configuration was started several times with LogAperture first, perfmon4j present. Sample sizes
are small — "3 in a row" where a count is given, otherwise the observer's plain "worked" /
"failed" — so treat pass/fail as directional. No configuration recorded as passing failed in any
reported run.

| # | Configuration | What ran early (at `premain`) | Result |
|---|---|---|---|
| 1 | `disabled=true` | nothing | boots |
| 2 | `defer-jmx` | everything except JMX registration (delayed 20 s) | fails (no difference from baseline) |
| 3 | `defer-jul` | JMX registration only; first JUL call and everything after delayed 20 s | boots |
| 4 | `julcall-only` | the first JUL call; adapter, install, listener delayed | boots (3/3) |
| 5 | `no-listener` | first JUL call, adapter, full `installContext`; only the config listener delayed | fails |
| 6 | `no-listener` + `stop=resume` | stages up to and including persisted-rule resume | boots (3/3) |
| 7 | `no-listener` + `stop=trim` | resume, then `installTrimRendering` | fails |
| 8 | `no-listener` + `stop=top` | resume, trim, top's byte counting | fails |
| 9 | `no-listener` + `skip=trim,top` | everything except trim and top (i.e. storm, pipeline run) | fails |
| 10 | `no-listener` + `skip=trim,top,storm,pipeline` | everything except all four handler-mutating stages | boots (3/3) |
| 11 | `no-listener` + `skip=trim,top,storm` | pipeline only | fails |
| 12 | `no-listener` + `skip=trim,top,pipeline` | storm only | fails |

(Run 9's log confirmed the skip took effect: `SPIKE: skipping stage 'trim'` and `'top'` lines
were present.)

The stages of `WildFlyContainer.installContext`, in order: baseline capture (enumerate loggers,
capture levels) → handler/level services and persisted-rule resume → trim rendering → top's byte
counting → storm detection → rule pipeline → registration. Trim and top each **set a formatter**
on every handler; storm detection and the rule pipeline each **set a filter** on every handler.

## Findings

1. **The trigger is installing any of our filters or formatters on the JBoss LogManager's
   handlers at `premain`.** Each of the four handler-mutating stages fails on its own (7, 11, 12;
   trim alone in 7), and skipping all four boots (10), with everything else still running early.
2. **Safe at `premain` on this launch:** JMX registration (2), the first JUL call (4), adapter
   creation, logger enumeration and baseline capture, resuming persisted levels/rules and handler
   overrides, and constructing the doctor/environment services (6, 10).
3. **The readiness gate is not a "jboss-modules is up" signal here.** With
   `jboss.modules.system.pkgs` naming `org.jboss.logmanager`, the class is on the system
   classloader and loads at `premain` for reasons unrelated to us. The gate's premise (that it
   becomes true only after jboss-modules has loaded the LogManager) does not hold on this launch.
4. **Agent order changes the odds, not the cause.** Listing LogAperture first turned a rare failure
   into a certain one on this launch; earlier "put LogAperture first" advice is therefore wrong as
   a general rule for WildFly and is corrected in §18.14 and `USER_GUIDE_NOTES.md`.

## Not established

- **The mechanism.** Why setting a filter/formatter on a boot handler makes jboss-modules fail to
  find `org.jboss.as.standalone` is not known. `ModuleNotFoundException` here carries no cause.
  Untested ideas: a class-loading effect of our wrapper classes (on the app classloader) being
  exercised on the logging path during module load; the wrappers not being the JBoss `Ext*`
  types; an `Error` on the logging path being swallowed by module loading and reported as "not
  found". Tracked in #87.
- Whether `top`'s formatter wrap fails **on its own** — every run that includes it also included
  trim (7, 8), and no run isolated it.
- Whether the rare failures with LogAperture lower in the list have the same cause.
- Anything about other containers, other JDKs, non-Windows, or a stock (no `system.pkgs`,
  no wrapper) WildFly launch. The failure was not reproduced there; nothing was tried there.

## Design consequences

Feeds the spec for #86 (not yet written):

- Do only logger-level work at `premain` (baseline, resume, level/rule bookkeeping — findings 2).
  Install handler filters/formatters only once WildFly's logging is actually configured.
  Candidate signals: the JBoss LogManager configuration-change listener (already wired in
  `wireConfigurationListener`) and the existing periodic verification sweep, which already
  re-installs every wrap idempotently on each tick (default 30 s,
  `logaperture.sweep.seconds`). WildFly's logging subsystem replaces the boot handlers during
  startup anyway, so wrappers set on them at `premain` are short-lived regardless.
- Cost, stated plainly: trim/drop rules will not affect events logged in the first seconds of boot,
  including other agents' `premain`-time logging. §18.14's "be as proactive as possible" therefore
  cannot be met by wrapping boot handlers early on this launch unless #87 finds a safe way.
- Consider whether the readiness gate should also check a bootstrap signal, independent of the
  handler deferral, so the other early steps (JMX, resume) don't depend on this launch's classloader
  layout either.

## Harness

The harness is not committed. It is ~200 lines across two files and can be rebuilt from this
description:

- `WildFlyContainerIntegration.activate`: read `logaperture.experiment` (`defer-jmx`, `defer-jul`,
  `defer-all`, `julcall-only`, `adapter-only`, `no-listener`) and `logaperture.experiment.delayMs`
  (default 20000); run the detector thread's steps (JMX registration, readiness wait + install,
  config listener) in the orders in the table, sleeping `delayMs` where a step is deferred.
- `WildFlyContainer.installContext`: `logaperture.experiment.stop=<stage>` returns after the named
  stage (`baseline`, `resume`, `trim`, `top`, `storm`); `logaperture.experiment.skip=<csv>` skips the
  named stages (`trim`, `top`, `storm`, `pipeline`). Each logs a `LogAperture SPIKE:` line at
  `INFO` when it takes effect.

Built on `feature/34-trim-rule` (so the trim feature the user's launch used was present).
