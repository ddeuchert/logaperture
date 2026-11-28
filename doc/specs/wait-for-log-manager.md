# Waiting for the application's `LogManager`

Status: **draft for review, 2026-11-27. Not signed off.** Decisions #1 to #12 below are proposals.
Issue: [#279](https://github.com/ddeuchert/logaperture/issues/279).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §15.2 (the `none` integration) and
§15.6 (the premain gotcha: never be the first `java.util.logging` caller).
Amends: [`plain-jvm-jul.md`](plain-jvm-jul.md) ("Found along the way"),
[`logctl-attach.md`](logctl-attach.md) (what `logctl attach` says about a waiting JVM),
[`vendor-defaults.md`](vendor-defaults.md) "Agent arguments" (another argument).

## Functional summary

After this feature, the user will be able to:

- Start an application that chooses its own `java.util.logging.manager` after start-up with
  `-javaagent:logaperture-agent.jar=--wait-for-log-manager=on`, and keep the manager it chose.
- Have vendor defaults, recipes and saved settings apply from the start while the agent waits.
- Be told by `logctl` that such a JVM is not active yet, instead of `logctl` disturbing it.
- Look up, in the guide, when `-Djava.util.logging.manager=…` on the command line is enough on its own.

Guide pages changed: [agent options](../../guide/reference/agent-options.md),
[Install on a plain JVM](../../guide/get-started/install-plain-jvm.md),
[Troubleshooting](../../guide/troubleshooting.md).

## Why

On a plain JVM the agent publishes the connection `logctl` uses as soon as the first logging framework
is installed. Publishing creates the platform MBean server, and the JDK initialises
`java.util.logging` as part of that. The JVM's `LogManager` is fixed at that moment. An application
that sets `java.util.logging.manager` in code afterwards gets the JDK's default manager, without an
error.

`LogbackLateJulManagerIT` reproduces this
([#425](https://github.com/ddeuchert/logaperture/pull/425)). Measured on one Linux machine, JDK
21.0.12, Logback 1.5.13: an application kept its own manager only if it set the property within
about 150 to 200 ms of its first Logback call.

Setting the property on the `java` command line avoids the problem when the manager class is on the
ordinary class path. It does not when only the application's own class loader can see the class, as in
a fat jar that nests it: the agent's thread can't load it, and the JDK falls back to its default.

A 10-second wait before installing ([#377](https://github.com/ddeuchert/logaperture/pull/377)) moved
the limit without removing it, delayed every Logback application, and delayed its settings.

## Scope

**In scope:** one agent argument that makes the agent publish only after something else has
initialised `java.util.logging`; `logctl` leaving a waiting JVM alone; the guide.

**Out of scope:**

- Changing what happens without the argument. By default the agent behaves as in 1.0.
- Offering the argument through `logctl attach`.
- The Quarkus extension, which has no agent arguments.
- Protecting a waiting JVM from other tools that start its management agent.

## Behaviour

### The argument

`--wait-for-log-manager=on|off`, default `off` (#1, #2). It is parsed like `--storm-detection`: an
unknown value is skipped with a warning, and the agent starts without waiting.

### What waits

Only publication waits (#4): registering the control MXBean, setting the `logaperture.version` marker,
and the startup line. Everything else happens as without the argument. Logging contexts install when
their framework is ready, vendor defaults, saved settings, rules and storm detection apply, and the
vendor defaults load is audited.

Both routes to publication wait: the one taken when the first context installs, and the one taken when
a framework is found and can't be bound ([`surface-on-bind-failure.md`](surface-on-bind-failure.md)).

### What ends the wait

The class `java.util.logging.LogManager$RootLogger` being loaded, which means something else has
initialised JUL and the manager is chosen (#3). The agent watches for it as the plain-JVM JUL
integration does ([`plain-jvm-jul.md`](plain-jvm-jul.md) #2): every 100 ms for the first minute, then
every second. It never gives up and never publishes early (#5). If it is still waiting after a minute,
it writes one line (#6):

```
[logaperture] WARN still waiting for java.util.logging to be initialised (--wait-for-log-manager); logctl cannot see this JVM until then
```

The agent does not check which manager was installed.

### `logctl`

While it waits, the agent sets the system property `logaperture.waiting`, from `premain`, and clears
it after publishing. `logctl` reads a target's properties before it starts the target's management
agent. Reading them starts nothing. Starting the management agent would initialise JUL.

| Command, against a waiting JVM | Result |
|---|---|
| Any command with `--pid` | Exit 3, nothing connected, with the message below (#7) |
| Any command without `--pid` | The JVM is not a candidate. When no other is, the "No LogAperture-enabled JVM found" message also names each waiting JVM (#8) |
| `logctl attach <pid>` | Nothing loaded; says the JVM already has LogAperture and is waiting for `java.util.logging` (#9) |

```
LogAperture in PID 576651 is not active yet: it is waiting for the application to set up
java.util.logging (--wait-for-log-manager). logctl has not connected, because connecting would set it
up first. Try again once the application has used java.util.logging.
```

### Which setups

The argument applies to the `none` integration, whatever it binds: Logback, or Log4j 2 found by the
class-loader detector (#10). A Log4j 2 application that sets `log4j-jul`'s manager in code is exposed
by the same mechanism; that case has not been reproduced. On plain `java.util.logging` the agent
already waits, so the argument changes nothing there. On WildFly and Quarkus it is ignored, with a
warning.

### Compatibility

The argument and the property are additions (§11.1). The MXBean interfaces, `--json` fields and exit
codes don't change. A new `logctl` against an older agent behaves as before. An older `logctl` given
`--pid` of a waiting JVM connects, which ends the wait early.

## Limits, to be stated in the guide

- `logctl` can't reach the JVM until the application, or a library in it, first uses
  `java.util.logging`. If nothing ever does, it never can. Use the argument only for an application
  that needs it.
- If a library uses `java.util.logging` before the application sets the property, the application gets
  the default manager, as it would without the agent.
- An older `logctl`, or another tool that starts the JVM's management agent, ends the wait early.
  JConsole and `jcmd ManagementAgent.start_local` are expected to do this; neither was tried.
- It is for `-javaagent` starts.

## Decisions

| Id | Decision | Options | Proposed |
|---|---|---|---|
| #1 | How waiting is switched on | (a) An explicit agent argument. (b) Automatically for every application on a plain JVM. (c) Automatically when `java.util.logging.manager` is set at start-up. | **(a)**. (b) and (c) hide a JVM from `logctl` for good if it never uses JUL. (c) also misses the property set in code, which is the case that has no other remedy. |
| #2 | The argument's name | (a) `--wait-for-log-manager=on\|off`. (b) `--wait-for-jul=on\|off`. (c) `--defer-to-log-manager=on\|off`. | **(a)**: it names the reason, and matches `--storm-detection=on\|off` in form. |
| #3 | What ends the wait | (a) Any initialisation of JUL. (b) A manager class the user names being loaded. | **(a)**. A loaded class is not yet the installed manager, and a mistyped name would wait for ever. |
| #4 | What waits | (a) Publication only. (b) The whole install. | **(a)**. Only publication initialises JUL, and settings should not be late. |
| #5 | Time limit | (a) None. (b) Publish after a limit. | **(a)**. A limit takes the choice away from the application after all, which is what #377 did. |
| #6 | What the log says while waiting | (a) One line, only if still waiting after a minute. (b) Also a line at start-up. | **(a)**: an application that uses JUL early gets no extra line. |
| #7 | `logctl --pid` on a waiting JVM | (a) Refuse, exit 3. (b) Wait for publication. (c) Refuse, with an option to connect anyway. | **(a)**. The wait has no limit, and (c) can be added if someone needs it. |
| #8 | `logctl` without `--pid` | (a) Unchanged. (b) Name the waiting JVMs when there is no candidate. | **(b)**: discovery already reads their properties, and "no JVM found" is otherwise misleading. |
| #9 | `logctl attach` on a waiting JVM | (a) Say it is waiting. (b) Keep "it is starting up". | **(a)**. |
| #10 | Where the argument applies | (a) The `none` integration; ignored with a warning on WildFly and Quarkus. (b) Every integration. | **(a)**. Those servers choose their own manager and already have readiness gates. |
| #11 | #279 once this ships | (a) Close it: the default is unchanged and the remedy is documented. (b) Keep it open for a change to the default. | **(a)**. |
| #12 | Release | (a) `1.1.0`, before the beta 1 feature freeze. (b) `1.x`, where #279 is now. | **(a)**: the change is small and opt-in. |

## Prototype results

Branch `spike/279-wait-for-log-manager`, commit `f6184250`, on the machine above. The prototype
differs from this draft in #8, #9 and #10: it leaves discovery and `logctl attach`'s message
unchanged, and applies the argument to every integration.

- With the argument, the application kept its own manager in every run: set in code 0 to 500 ms after
  the first Logback call (10 of 10 at each of five delays), at 2 s and 12 s (3 of 3 each), with the
  class visible only to the application's class loader, and with JBoss LogManager.
- Without the argument, #279 still reproduces.
- An application that used no JUL for 75 s loaded no `LogManager` class, with vendor defaults and
  storm detection on. A saved `sticky` setting applied during a wait.
- `logctl status`, `list loggers`, `set logger` and `attach`, given the PID of a waiting JVM, left it
  alone in 3 of 3 runs each. Before the guard, `logctl --pid` ended the wait.

## Tests

- Argument parsing: `on`, `off`, any case, a bad value, absent.
- `LogbackLateJulManagerIT`: the existing two cases, and with the argument the application's manager
  wins, `logaperture.waiting` is set during the wait and cleared after, and the JVM is controllable.
- A fat-jar-like class loader case in the same test.
- The unbound-framework publication route waits too.
- `logctl`: refuses on `waiting` without the version marker, connects with the marker or without
  `waiting`; the discovery and `attach` messages.
- The argument is ignored with a warning on WildFly and Quarkus.

## Not established

- The unbound-framework route during a wait (not exercised by the prototype).
- A Log4j 2 application with `log4j-jul` set in code, with and without the argument.
- A real Spring Boot fat jar; the nested case was simulated with a separate class loader.
- Windows, and JDK 17 and 25.
