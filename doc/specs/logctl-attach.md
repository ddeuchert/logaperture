# `logctl attach` — load the agent into a running JVM

Status: **Signed off 2026-10-26** (decisions #1 to #10 below, #2 revised in the review to embed the
agent jar in the CLI jar). Release `1.1.0`.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §2 (`premain` primary, `agentmain`
best-effort), §8.1 (the CLI over the Attach API), §13 risk 3 (loggers created before install),
§17.1 (1.1.0). Evidence: [`doc/spikes/dynamic-attach.md`](../spikes/dynamic-attach.md) (#219).
Builds on: [`plain-jvm-jul.md`](plain-jvm-jul.md) (#221, the readiness gate that already handles a
class that is loaded), [`cli-transport.md`](cli-transport.md) (the deferral of
`VirtualMachine.loadAgent`), [`doctor-agent-order.md`](doctor-agent-order.md),
[`pick-jvm.md`](pick-jvm.md), [`persistence.md`](persistence.md). Tracking issue:
[#273](https://github.com/ddeuchert/logaperture/issues/273).

## Functional summary

After this feature, the user will be able to:

- Run `logctl attach <pid>` against a JVM that was started without `-javaagent`, and control it
  with `logctl` straight away: `set logger`, `top`, `doctor`, rules, on WildFly and on a plain JVM
  (Logback or `java.util.logging`), without restarting the application.
- Run `logctl attach` with no PID and pick the JVM from a list of the ones that can be attached.
- Pass the same options the `-javaagent` line takes (`--vendor-defaults`, `--storm-detection`)
  to `logctl attach`.
- Be told plainly when an attach cannot work (a JVM started with dynamic agent loading turned
  off, a JRE with no attach support, a JVM owned by another user) and what to do about it.
- Be told, if the JVM already runs LogAperture, that it does, instead of getting a second copy.
- See in `logctl doctor` why the `-javaagent` order check is missing for an attached JVM.

An attach lasts until the JVM stops; adding `-javaagent` to the launch line is still the way to
keep LogAperture permanently.

Guide pages this changes: `reference/logctl.md` (generated from the help); `get-started/
install-plain-jvm.md` and `get-started/install-wildfly.md` (a "try it without a restart" route);
`how-to/try-without-restart.md` (new); `concepts/agent-order.md`; `concepts/security.md` (what an
attach lets `logctl` do); `reference/doctor-checks.md` (the new line); `troubleshooting.md` (the
JEP 451 messages); `reference/agent-options.md` only if decision #8 adds a property.

Not part of this feature: publishing `logctl` through JBang, SDKMAN or Homebrew (#274, #275, #276;
the embedded agent jar means none of them has to ship the agent separately);
`logctl detach` (an agent cannot be unloaded from a JVM); attaching into a JVM that runs the
Quarkus extension (it refuses, see "Already running"); Spring Boot and Tomcat.

## What is already established

From the spike, on Linux with JDK 17, 21 and 25:

- **`agentmain` exists** and calls the same `AgentBootstrap.start` as `premain`, with the agent
  argument string. A second start in the same JVM logs one line and returns (#120), and a start in
  a JVM where the Quarkus extension already runs logs one line and returns.
- **WildFly needs no agent change**: attached to a running server, `set logger`, the handler-floor
  hint and `doctor` all worked.
- **Plain Logback needs one change**: the readiness gate waits for a class-load event for
  `LoggerContext`, which has already happened, so nothing installs and nothing is said. Scanning
  `Instrumentation.getAllLoadedClasses()` first fixed it on all three JDKs.
- **Plain `java.util.logging`**: #221's `JulLoadDetector` polls loaded classes and already checks
  once immediately, so it finds `LogManager$RootLogger` on attach with no change.
- **JEP 451**: JDK 17 is silent; JDK 21 and 25 print a four-line warning on the *target's* stderr;
  a target started with `-XX:-EnableDynamicAgentLoading` refuses, and the *attaching* JVM sees
  `AgentLoadException`.
- **The state store** failed on the WildFly image because the `jboss` user's `$HOME` is not
  writable; the agent logged a stack trace and went session-only. An attach has no launch flag
  to carry `-Dlogaperture.home`.
- **`doctor`** omits the agent-order check on an attached JVM and says nothing about it.

## Decisions

All ten were agreed on 2026-10-26 from the sign-off review, each as the outcome in the last column.

| Id | Decision | Options | Outcome |
|---|---|---|---|
| #1 | Command shape | (a) `logctl attach [<pid>] [--vendor-defaults <file>] [--storm-detection on\|off] [--agent-jar <file>]`. With no PID: a numbered list of attachable JVMs without the agent, as in `pick-jvm.md`, or exit 4 with the table when nothing may be asked. (b) PID required. (c) A `--attach` flag on every command that auto-attaches. | **(a)**. It matches how every other command finds its JVM, and the option names mirror the `-javaagent` arguments. (c) makes a read-only command load code into a process. `--pid <n>` is accepted too, as elsewhere. |
| #2 | Which agent jar is loaded | (a) **The CLI jar carries a copy of the agent jar** (a build step embeds the shaded agent jar; about 0.6 MB, taking `logaperture-cli.jar` from about 0.65 MB to about 1.3 MB). Choice order: **1.** `--agent-jar <file>`, always, exactly as given; **2.** a `logaperture-agent.jar` in the directory holding the CLI jar (`lib/` in the zip), or in `LOGCTL_JAR_HOME` when the launcher set it, whose version has the same major as the embedded one and is newer; **3.** the embedded copy. (b) Find the jar next to the CLI only; fail when it is not there. (c) Download it from Maven Central. | **(a)**, from the 2026-10-26 review. The normal install needs no flag and cannot fail for a missing file, and a JBang- or Homebrew-installed `logctl` (#274, #276) can attach with nothing else to fetch. A newer same-major jar next to the CLI wins so that an operator who upgraded the agent on their JVMs but not the CLI attaches the version they run (the component versioning policy lets any same-major pair work). An equal version behaves the same as the embedded one, so it is not looked for. (c) makes `logctl` a network client and would run unverified code in a production JVM. The embedded copy is written out once to `${logaperture.home}/agent/logaperture-agent-<version>-<digest>.jar` (home resolved as in #8, the temp-directory fallback second) and reused; it is **never deleted**, because the target keeps the file on its class path for its whole life. The name carries a digest of the content so that a rebuilt jar with the same version string (every snapshot) never overwrites the file a running JVM has open. `logctl` says which jar it used and why. A target in another filesystem (a container) cannot see that path: use `--agent-jar` with a path valid inside the target. |
| #3 | A JVM that already runs LogAperture | (a) Print "already running LogAperture `<version>` (via `-javaagent` / attach / Quarkus extension)" and exit 0, no second load. (b) Same, exit 6. (c) Load anyway and let the agent ignore it. | **(a)**: attaching is idempotent, so a script can run it without checking first. The check reads the `logaperture.version` marker property that discovery already uses; the Quarkus extension is recognised by the claim it makes (`quarkus-extension.md` #3). Never (c): the load prints the JEP 451 warning on the target again for nothing. |
| #4 | Failure messages | The attach can fail four ways. (a) Name each with its cause and its fix: **no `jdk.attach`** (run `logctl` with a JDK, not a JRE); **JEP 451 refusal** (the target was started with `-XX:-EnableDynamicAgentLoading`; restart it with `-javaagent`, or without that flag); **permission** (the target belongs to another user; run as that user); **no such PID**. (b) Print the exception text. | **(a)**, with the existing exit codes: 3 no JVM, 5 `ATTACH_DENIED` for permission and JEP 451, 6 `REFUSED` for a JVM that cannot be attached by design. The JEP 451 text also says that a future JDK may make `-XX:+EnableDynamicAgentLoading` mandatory, which defeats the point of attaching. |
| #5 | What `attach` reports after the load | (a) Wait up to 10 s for the `logaperture.version` marker, then print "LogAperture `<version>` active (`<backend>`) in PID `<n>`" and exit 0. If it does not appear, print that the agent is loaded but waiting for a logging backend (Logback or `java.util.logging`), that `logctl status` will find it once the application logs, and exit 0. (b) Return as soon as the load returns. (c) Wait indefinitely. | **(a)**. The install is asynchronous by design (§4.1); the 10 s covers the usual "the application is already logging" case and the message covers an application that has not logged yet. Exit 0 in both, since the agent *is* loaded; `--json` carries the state. A JEP 451 warning on the target's stderr is mentioned once in the output so the user is not surprised by it. |
| #6 | The agent says why it did not start | (a) After an attach into a plain JVM, if LogAperture is not up after one second, the agent writes one line to the target's stderr saying it is waiting for Logback or `java.util.logging` (nothing is written when it comes up at once, as it does when the application is already logging); if still nothing after 60 s, one line saying which backends were looked for and that another, such as Log4j 2, cannot be controlled. Premain keeps its current silence. (b) Always log both. (c) Nothing. | **(a)**. A silent attach on a Log4j 2 app is the worst outcome in the spike (finding 1: no error, no marker). The lines appear only on an attach, where the user is watching; a `-javaagent` start is not changed. |
| #7 | Logback "already loaded" | (a) In `LogbackLoadDetector`, scan `getAllLoadedClasses()` first; if `LoggerContext` is loaded, start the same bound-confirmation poll directly, else register the transformer as today. (b) Replace the transformer with a poll, like #221's `JulLoadDetector`. | **(a)**. A `-javaagent` start never sees the class loaded, so its path is byte-for-byte as in 1.0 (Decision #1 of `plain-jvm-jul.md` keeps Logback apps unchanged). (b) is cleaner but changes the 1.0 start-up path for no user-visible gain. |
| #8 | State home when `$HOME` is not writable | (a) If `${logaperture.home}` (default `~/.logaperture`) cannot be created or locked, fall back to `${java.io.tmpdir}/logaperture-<user>` for every start, log one line naming the directory (no stack trace), and report it in `logctl env`. `-Dlogaperture.home` still wins. (b) A `--home <dir>` option on `attach`, carried as an agent argument. (c) Leave it: session-only, as today. | **(a)**, which also fixes the same problem for a `-javaagent` start on a container image with a read-only home. Overrides then survive an agent restart but not a wipe of the temp directory; the line says so. `persistence.md` is updated with the fallback. (b) is the smallest change but only helps attach; it can still be added later. |
| #9 | `doctor` and the missing agent-order check | (a) A new INFO check `agent.attached` that appears only on an attached JVM: "Loaded by attach, so the -javaagent order check does not apply. Add -javaagent to the launch line to make LogAperture permanent." (b) Print the note in `status` too. (c) Nothing. | **(a)**. It fills the gap the spike found without touching `status`. The check id is added to `reference/doctor-checks.md`, which `GuideReferenceDriftTest` enforces. The agent records the entry point (`premain` or `agentmain`) at start. |
| #10 | Platforms and users | (a) Linux is the tested platform; macOS and Windows use the same Attach API and are expected to work but are documented as untested until someone checks. The target must be the same OS user as `logctl`, as for every other command. (b) Block the command off Linux. | **(a)**. `logctl` already attaches to read the MBean on all three; only the load differs. The guide says "tested on Linux". |

## Scope

1. **The command (#1, #2, #3, #4, #5).** A new `attach` command in `logaperture-cli`, parsed like
   the others but run without an MBean connection: it resolves a JVM, checks the marker, chooses
   the agent jar (#2), loads it through `VirtualMachine.loadAgent(jar, args)`, and polls for the
   marker. The build embeds the shaded agent jar as a resource of the CLI jar (copied by path at
   `prepare-package`, with a clear failure when the agent jar is not built; not a dependency, which
   the CLI's enforcer rule still bans). Build both with `mvn -pl logaperture-agent,logaperture-cli
   -am clean package`. It never
   asks for confirmation, since `logctl` can already change any attached JVM's log levels; the
   help states plainly that it loads code into the target. `--json` output carries
   `{pid, state, version, backend}`.
2. **Agent arguments (#1).** `--vendor-defaults` and `--storm-detection` become the agent
   argument string with the same syntax as `-javaagent:...=`. A relative `--vendor-defaults` path
   is resolved against the CLI's working directory (the *target's* working directory would be
   wrong), so it is made absolute before it is sent.
3. **Logback readiness (#7).** `LogbackLoadDetector` scans for an already-loaded `LoggerContext`
   before it registers the transformer. JUL (#221) needs no change.
4. **Diagnostics on attach (#6).** `AgentBootstrap` passes the entry point to the container
   integration's detector setup; the two lines are written through `Diagnostics`.
5. **State home (#8).** `FileStateStore.resolveHome` becomes a fallback chain; `logctl env`
   reports which directory is in use and why.
6. **`doctor` (#9).** `agent.attached`, INFO, recorded from the entry point.
7. **Help and guide.** `attach` in `HelpTopics.java`, then regenerate `guide/reference/logctl.md`;
   the guide changes named in the functional summary. Example output is captured from a real
   run, on the WildFly dev environment and on a plain JVM.

## Failure handling

The command fails closed and the agent fails open (§9). A failed attach changes nothing in the
target: `loadAgent` either runs the agent or does not. Inside the target, `agentmain` never
throws: every error becomes a `Diagnostics` line, and `logaperture.disabled` still wins.

## Test plan

- **Unit**: command parsing and help; agent-jar choice (`--agent-jar` wins; a newer same-major jar
  beats the embedded one; an older, equal, different-major or unreadable one does not; embedded
  copy extracted once and reused); argument-string
  building (relative path made absolute); each failure message and exit code; the marker check
  for "already running"; `LogbackLoadDetector` with the class already loaded and not yet loaded;
  state-home fallback order; the `agent.attached` check appears only on an attach.
- **Integration**: a Logback application started *without* the agent, then `logctl attach`, then
  `set logger` observed in its output, on the JDKs the matrix already covers; the same on
  `java.util.logging`; the WildFly container IT started without the agent; a second attach is a
  no-op; a target started with `-XX:-EnableDynamicAgentLoading` gives exit 5 and the JEP 451
  message; a target with no supported backend gets the 60 s line.
- **By hand**: macOS and Windows are not in CI and are checked once before the release, or stay
  documented as untested (#10).

## Estimate

About 4 days, per the spike: readiness and tests 0.5, the command 1.5 (the picker and the
messages are most of it, plus embedding and choosing the agent jar), diagnostics lines and the doctor check 0.75, state-home fallback 0.5,
integration tests 1, guide 0.5.
