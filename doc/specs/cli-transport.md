# CLI Transport — `logctl`

Status: implemented in M1.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §8 (control plane and
interfaces), §8.1 (CLI over the Attach API), §8.2 (the agent never listens), §9.8
(authentication from the OS), §14.5 (CLI ergonomics are the product), §17 (roadmap, M1's
"CLI" line item).
Builds on: [`doc/specs/level-control.md`](level-control.md) (Feature 1) and
[`doc/specs/persistence.md`](persistence.md) (Feature 2) — the operations this surface
drives already ship; nothing here adds a new operation.

## Functional summary

After this feature, the user will be able to:

- Run `logctl` from a shell to control logging in a running `java -jar` application,
  without opening `jconsole` or writing any JMX.
- Do so **without naming a process** — when exactly one LogAperture-enabled JVM is
  running, `logctl` finds it; when several are, it lists them and asks for `--pid`.
- List loggers and their levels: `logctl list loggers`, optionally filtered by a name
  prefix or a leading/trailing `*` pattern — `logctl list loggers *.infinispan
  --show-all` when the log line shows only the short category name (see
  [`list-command-surface.md`](list-command-surface.md)).
- Raise or lower a logger with a single dictatable command: `logctl set logger
  com.acme.batch DEBUG`, `logctl set logger com.acme.batch DEBUG for 30m`, `logctl set
  logger com.acme.chatty WARN sticky`, `logctl set logger com.acme.batch TRACE for 2h
  --reason INC-123` (see [`set-command-surface.md`](set-command-surface.md)).
- See what is currently overridden, in which tier, and when each one reverts:
  `logctl status`.
- Clear an override: `logctl reset logger com.acme.batch` for one logger, `logctl reset
  loggers` to return every logger to how the application configured it (`logctl reset
  handlers` does the same for handlers — see [`reset-command-surface.md`](reset-command-surface.md)).
- Get machine-readable output for any command with `--json`.
- Rely on the command only ever succeeding for someone who could already attach a
  debugger to that JVM — authorization is the operating system's, not a password.

## Scope of this slice

The roadmap (§17) lists "CLI" as an M1 / Layer 0 deliverable alongside level control and
the persistence tiers, on the reasoning that M1 is "a read-only diagnostic release you can
hand to support and to a customer" — and a support engineer on a customer's box has a
shell, not a JMX console. Features 1 and 2 built the operations and made them reachable
over JMX ("the reference implementation; everything else is a convenience over it", §8.1);
this slice builds the convenience.

It is deliberately a **transport and a command grammar over the operations that already
exist**, nothing more. It adds no operation, changes no operation signature, and adds no
code to the agent beyond a single marker system property (below).

**In scope:**

- A new `logaperture-cli` module producing an executable `logaperture-cli.jar` plus thin
  `logctl` / `logctl.cmd` launcher scripts.
- The attach-API + local-JMX transport (§8.1): discover a JVM, connect, invoke one
  operation, disconnect. No daemon, no persistent connection, nothing added to the target.
- **Zero-PID discovery** (§14.5): enumerate candidate JVMs, filter to the ones running the
  agent, target the sole survivor or report the ambiguity.
- Commands mapping 1:1 onto Feature 1 + Feature 2's surface:
  - `logctl levels [filter]` &rarr; `listLoggers`, later renamed `logctl list loggers
    [filter]` — [`list-command-surface.md`](list-command-surface.md) (issue #42, slice 3)
  - `logctl debug|trace|info|warn|error <logger> [session | for <duration> | sticky]`
    and `logctl set <logger> <level> [tier]` &rarr; `setLevel`
  - `logctl status` &rarr; `listLoggers`, pre-filtered to active overrides
  - `logctl reset <logger>` &rarr; `resetLevel`
  - `logctl reset --all` &rarr; `resetAll`
- The duration grammar (`30m`, `2h`, `90s`, `1d`) and tier resolution, including the
  4-hour default for a bare `logctl debug` (see below — this supersedes §14.5's original
  15-minute figure).
- `--json` output for every command; a table renderer as the default.
- `--reason`, `--include-children`, `--pid`, `--help`, `--version` options.
- A defined exit-code contract.
- The phone test (§6.2) enforced as an automated check over the CLI's own help text.

**Explicitly out of scope for this slice** (deferred, with a note each on what it needs):

- **`logctl top` and `logctl doctor`** (§16.1, §16.2). Both are separate Layer 1
  measurement features with infrastructure this slice doesn't build — `top` needs
  per-logger byte metering, `doctor` needs configuration introspection. The CLI grows
  these subcommands when those features land; this slice ships the transport they'll ride
  on.
- **`logctl undo`** (§14.5). "Revert the last change" needs the control surface to expose
  either recent audit history or a dedicated undo operation — neither exists yet. The
  audit record already carries the previous-value field such an operation would need
  (Feature 1, §9.7); wiring it to a command is its own slice. `logctl reset <logger>` is
  the escape hatch in the meantime (it reverts to baseline, not to the previous override
  value — that's the difference `undo` would close).
- **`logctl quiet` / `logctl loud` / `logctl new`** (§14.5). These drive rule packs and
  baseline modes that don't exist until Feature 3 / M4.
- **A glob on the `set` / level-named forms** (`logctl error *.infinispan`), top-level
  §18.7; tracked as [#41](https://github.com/ddeuchert/logaperture/issues/41), pulled
  forward to alpha-2. `logctl levels` already takes a glob for *lookup*; applying a level
  to every match is a fan-out layer over `setLevel`, and — decided, not deferred — it is
  a **standing rule** that also catches loggers registered later, not a one-shot, though
  narrowly a persisted per-pattern level rather than Feature 3 / §7's full squelch engine.
  Its own decisions (a confirmation preview before applying, `logctl reset <pattern>`
  symmetry that also retires the rule, one audit record per matched logger, a capability
  check per match) are still open — see §18.7. Until it lands, the workflow is `logctl
  list loggers *.infinispan --show-all` to find the category, then `set` on the resolved
  name.
- **Shell completion over live logger names** (§14.5). High-value, but it's a separate
  deliverable: completion scripts for bash/zsh/fish plus a fast name-only query path.
  `logctl levels --json` is the data source it will consume.
- **Injecting the agent into a JVM that started without `-javaagent:`** (`VirtualMachine
  .loadAgent`, the case JEP 451 warns about). This slice requires the agent to already be
  attached at launch — the same assumption Features 1 and 2 make (`premain` is "the
  primary, exit-criterion-tested entry point"). A future `logctl attach` command can add
  dynamic loading, with the `-XX:+EnableDynamicAgentLoading` caveat (§8.1) documented at
  that point.
- **The TUI (§8.3) and browser UI (§8.4).** Both are later renderers over this exact
  transport; §8.2's "UIs are clients of the attach transport, exactly like the CLI" is the
  whole point. This slice is the client they reuse. The TUI has since been named
  `logctl console` and pulled forward to alpha-2 — capture and open questions in
  [`console.md`](console.md); it adds no transport surface beyond what this slice builds.
- **The HTTP control plane (§8.1).** M6.
- **A distinct `"cli"` audit source.** In this slice the CLI reaches the operations *as a
  JMX client*, so the server records `source = "jmx"` — which is truthful. A separate
  source value waits for either a source parameter on the operation or a non-JMX
  transport; see "Capability and audit" below.
- Non-Logback frameworks and non-`none` containers — inherited unchanged from Features 1
  and 2.

## The transport

### Connection model

Each `logctl` invocation is a short-lived client. The sequence, lifted directly from the
one `LevelControlEndToEndIT` already proves works cross-process:

1. Resolve a target PID (see Discovery).
2. `VirtualMachine.attach(pid)`.
3. `vm.startLocalManagementAgent()` — returns a JMX connector address. This starts the
   JDK's *JMX* management agent in the target, not any LogAperture code, and is not the
   dynamic-agent-loading path JEP 451 warns about, so no `-XX:+EnableDynamicAgentLoading`
   is required for it.
4. `JMXConnectorFactory.connect(url)` &rarr; `MBeanServerConnection`.
5. If `org.logaperture:type=LevelControl` isn't registered, fail with a clear message
   (the JVM is up but has no agent, or the agent failed to install — see Failure
   handling). Otherwise `JMX.newMXBeanProxy(...)` and invoke the one operation the command
   maps to.
6. Close the connector, `vm.detach()`.

No connection is held between invocations. The agent gains no listener — §8.2's claim
that "this agent opens no network connections, inbound or outbound, ever" is preserved
because the JMX connector lives in the *JDK's* management agent, started on demand by an
already-UID-authorized attach, and torn down when `logctl` exits.

### Discovery

Per §14.5, "No PID argument when exactly one candidate JVM is running. Discover it."

1. `VirtualMachine.list()` enumerates every attachable JVM owned by the current OS user.
2. For each descriptor, `attach` and read `getSystemProperties()`; keep only those with
   the `logaperture.version` marker property set (see "Changes outside the new module").
   This filter is cheap and touches nothing — it does not start the management agent in
   JVMs that turn out not to be ours. A JVM that can't be attached to (a race, a
   permissions quirk, a different user) is not a candidate and does not stop discovery —
   but its count is carried forward, to be surfaced only on the zero-candidate path (next).
3. Resolve:
   - **exactly one** &rarr; use it.
   - **none** &rarr; exit 3, "No LogAperture-enabled JVM found. Start the application with
     `-javaagent:logaperture-agent.jar`." If one or more running JVMs could not be
     inspected during discovery, a second line reports how many and suggests `--pid <n>`
     if one of them is the target — so a transiently un-attachable real target reads as
     "couldn't look" rather than "nothing there." The `--pid` path is unaffected: an
     explicit PID never runs discovery.
   - **more than one** &rarr; exit 4, print a table (pid, main class, the marker's
     version) and "Several candidates — pass `--pid <n>`."
4. `--pid <n>` skips discovery entirely and targets that PID; if it has no marker
   property, the command still proceeds to step 5 of the connection model and fails there
   with the "up but no agent" message — an explicit PID is taken as "I know what I'm
   doing."

### Authentication

There is none to add. `VirtualMachine.attach` is UID-gated by the target JVM: only the
account that owns the process, or root, can attach (§9.8). That is already exactly the set
of principals who should be able to change its logging, it needs no credential store, and
the OS audits it. An attach that fails with a permission error is reported as exit 5 with
"Can't attach to PID <n> — run as the user that owns that process, or as root."

The audit record's principal is therefore the JVM's own UID, recorded server-side by the
existing operation, unchanged by this slice.

## Command surface

### Global options

| Option | Applies to | Meaning |
|---|---|---|
| `--pid <n>` | all | Target this PID; skip discovery. |
| `--json` | all | Emit machine-readable JSON instead of a table. |
| `--reason <text>` | mutating commands | Passed through as the override's `reason` (§5, §9.7). Optional; not enforced. |
| `--include-children` | — | **Superseded (shipped).** §18.7 / [#41](https://github.com/ddeuchert/logaperture/issues/41) dropped this flag from the operations API entirely (this row is stale — not updated at the time #41 shipped). No trailing-wildcard replacement takes its place either: [`pattern-selection-semantics.md`](pattern-selection-semantics.md) (issue #49) makes a trailing-wildcard `set` target a usage error, so `logctl set logger org.apache DEBUG` alone now covers `org.apache` and every descendant, via the logging framework's own level-inheritance. |
| `--version` | — | Print the CLI's version and exit 0. |
| `-h`, `--help` | — | Print usage and exit 0. |

Anything unrecognized, or a command with the wrong arity, is a usage error: exit 2, usage
to stderr.

### `logctl list loggers [filter]`/`list handlers`

**Superseded (shipped).** The bare `logctl levels [filter]` / `logctl handlers` surface
this section originally specced is retired — [`list-command-surface.md`](list-command-surface.md)
(issue #42, slice 3) replaces it with two namespace-scoped forms:

```
logctl list loggers [filter] [--show-all]
logctl list handlers [--show-all]
```

`list loggers [filter]` is the direct descendant of this section's old `levels [filter]`
— same filter grammar (`filter` is the same name-prefix/pattern Feature 1 defined: with no
`*` it is a name prefix; with one, it is a segment-anchored pattern — at most one leading
`*.` and/or one trailing `.*`, every other segment literal — so `logctl list loggers
'*.infinispan' --show-all` locates a logger from the abbreviated category a log line
prints rather than its fully-qualified name; an invalid pattern is a usage error naming
the problem and suggesting the fix, not a silent no-match), same table shape:

```
LOGGER                              CONFIGURED  EFFECTIVE  OVERRIDE
com.acme.batch.Worker               INFO        DEBUG      FOR, reverts 15:42:00 (in 27m) — "INC-123"
com.acme.payments                   —           WARN       STICKY — "known-noisy"
com.acme.web.RequestFilter          INFO        INFO       —
```

**New default: overrides-only.** Without `--show-all`, a row with no active override is
dropped before rendering — `com.acme.web.RequestFilter` above would not appear. This is a
genuine behavior change from the old `levels`' always-show-everything default; full
rationale: [`list-command-surface.md`](list-command-surface.md) Decision #2.

`CONFIGURED` shows `—` when no baseline was ever captured for that logger (Feature 1's
`configuredLevel == null`, i.e. a "Known" logger not yet instantiated). State labels
beyond Live/Known (§8.5's Inferred / Referenced-but-never-seen) don't exist in this
slice's data and aren't shown.

`--json` emits an array of objects with the `LoggerInfoData` fields verbatim, using that
type's own getter names: `name`, `configuredLevel`, `effectiveLevel`, `overrideActive`,
`overrideSource`, `overrideReason`, `tier`, `expiresAt` (the last two are `null` unless an
override is active, and `expiresAt` is `null` for a non-`FOR` override).

`list handlers [--show-all]` is the direct descendant of the old bare `handlers` — same
handler catalog, same overrides-only default and `--show-all` opt-out. Full
command-by-command output shapes and design rationale:
[`list-command-surface.md`](list-command-surface.md).

### `logctl set logger`/`set handler` and the retired level-named forms

**Superseded (shipped).** The bare `logctl set <logger> <level>` / level-named-verb
(`debug`/`trace`/`info`/`warn`/`error`) / bare `logctl handler <name> <level>` surface this
section originally specced is retired — [`set-command-surface.md`](set-command-surface.md)
(issue #42, slice 2) replaces it with two namespace-scoped forms:

```
logctl set logger <target> <level> [tier]
logctl set handler <name> <level>|AUTO [tier]
```

`set logger <target> <level>` is the direct descendant of this section's old `set
<logger> <level>`; the level-named verbs are retired entirely, no alias kept.
`set handler <name> <level>`/`AUTO` is the direct descendant of the old bare `handler
<name> <level>`/`AUTO` (`handler <name> reset` had already left in slice 1, in favor of
`reset handler <name>` — nothing remains under a bare `handler` verb after slice 2).
Every tier, duration, `--reason`, and pattern-preview/confirm behavior below carries over
unchanged — only the command's noun changed, not what it does once parsed. Full
command-by-command output shapes and design rationale:
[`set-command-surface.md`](set-command-surface.md).

`logctl set logger com.acme.batch DEBUG` is the explicit form. `<level>` is matched
against LogAperture's `Level` enum case-insensitively; an unknown level is a usage error
(exit 2). The tier grammar (`FOR 4h` default, `session`, `for <duration>`, `sticky`),
duration syntax, confirmation-line rendering, and `--json` shape are all unchanged from
this section's original design and are documented in full in
[`set-command-surface.md`](set-command-surface.md) rather than duplicated here.

### `logctl status`

`listLoggers(null)` filtered to `overrideActive == true`, sorted by revert time (soonest
first; `STICKY`/`SESSION` last), then by name. This is the concrete form of §6.1's
"`logctl status` lists what is active in each tier and when it reverts." Same columns as
`logctl list loggers` minus the `CONFIGURED`/`EFFECTIVE` split — just what's overridden, to
what, in which tier, why, and when it goes back:

```
LOGGER                   LEVEL   TIER     REVERTS                  REASON
com.acme.batch.Worker    DEBUG   FOR      15:42:00 (in 27m)        "INC-123"
com.acme.payments        WARN    STICKY   until reset              "known-noisy"
```

`REASON` is shown quoted (and `—` when none was given), the same rendering `logctl list
loggers` uses in its `OVERRIDE` column. Every logger with an active override appears here:
`setLevel` and persistence-resume both commit the logger to the override registry, so it
is at least "Known" and `listLoggers` returns it even if nothing has instantiated it yet.

Zero active overrides prints `No active overrides.` and exits 0. `logctl env` is the
separate, pasteable bug-report block — see [`environment-report.md`](environment-report.md)
— not folded into `status`.

### `logctl reset logger`/`loggers`/`handler`/`handlers`

**Superseded (shipped).** The single `logctl reset <logger>` / `logctl reset --all` /
`logctl handler <name> reset` surface this section originally specced is retired —
[`reset-command-surface.md`](reset-command-surface.md) (issue #42, slice 1) replaces it with
four namespace-scoped forms:

```
logctl reset logger <target> [--include-sticky] [--include-vendor-defaults]
logctl reset loggers [--include-sticky]
logctl reset handler <name> [--include-sticky]
logctl reset handlers [--include-sticky]
```

With a vendor defaults file ([`vendor-defaults.md`](vendor-defaults.md)), every form lands on
the file's level rather than the application's own. `--include-vendor-defaults` (also on `reset
rule`/`reset rules`) switches off vendor rules until restart; it is a usage error anywhere else.

`reset logger <target>` accepts an exact name or a pattern, exactly as `set` does. A
`STICKY`-tier override is skipped by default on every form — `--include-sticky` opts back
in — with an exact-name target (`reset logger`/`reset handler`) refusing outright (exit 2)
rather than silently skipping, since naming one specific thing and having it do nothing
would be a trap; a pattern or a bulk form (`reset loggers`/`reset handlers`) instead leaves
a sticky match in place and reports it, the same way "matched nothing" already isn't an
error. `logctl reset --all` no longer exists — `reset loggers` and `reset handlers` are the
only broad forms, and neither prompts for confirmation (same "someone typing this wants it
to just work" reasoning the old `reset --all` had). Full command-by-command output shapes,
exit codes, and design rationale: [`reset-command-surface.md`](reset-command-surface.md).

### The phone test, enforced

§6.2 sets a design criterion: a support engineer must be able to read any command down a
phone line without the customer mistyping it. Every command *synopsis* line in `--help`
output (the `logctl …` usage forms, not the surrounding prose or the sample confirmation
output, which necessarily shows `→` and clock times) is checked, in a test, to contain
none of `:` `=` `(` `)` `/` — a crude but sufficient automated proxy (the same way
`persistence.md`'s lock-collision test pins a prose claim to a check). `logctl set logger
com.acme.batch DEBUG for 30m` passes; a hypothetical `logctl set --logger=com.acme` would
not, and the test would catch it.

## Output and exit codes

- **Default:** aligned plain-text tables / confirmation lines to stdout; diagnostics to
  stderr.
- **`--json`:** a single JSON value to stdout (array for `list loggers`/`status`, object for a
  `set`, `{"reverted": N}` for `reset`), nothing else on stdout. Hand-written from the
  data objects — no third-party JSON library (the shapes are tiny and fully controlled,
  same reasoning as `persistence.md`'s hand-written state-file reader).

**Multi-context CONTEXT column (added by wildfly-support).** `list loggers` and `status` prepend
a `CONTEXT` column to their plain-text table **only when the result spans more than one
distinct logging context** — a plain `java -jar` app, and a stock standalone WildFly (one
shared system context), never see it. Display only: there is no `--context` flag and no
new exit code (overrides are blanket — see `doc/specs/wildfly-support.md`, "Broadcast
semantics"). `--json` output is unchanged.

| Exit | Meaning |
|---|---|
| 0 | Success. |
| 1 | Unexpected failure — connection dropped mid-call, marshalling error, an exception from the operation itself other than a bad-argument rejection. |
| 2 | Usage error — unknown command/flag, wrong arity, unparseable level or duration, or an invalid filter pattern (§18.7's segment-anchored grammar) rejected server-side and carried back as an `IllegalArgumentException`. |
| 3 | No LogAperture-enabled JVM found. |
| 4 | Ambiguous — several candidates; `--pid` required. |
| 5 | Attach denied — wrong OS user. |
| 6 | Operation refused by policy — a capability the operation needs is not granted. |
| 7 | Operation or option not supported by the connected agent's version — see §11.1. |

Exit 6 is distinct from exit 1 because "you're not allowed to do that" and "it broke" are
different answers for a support engineer, and the capability model (§9.3) is the whole
reason to tell them apart.

**Implementation note on how a server-side exception actually arrives:** `AgentConnection`
obtains the MXBean via `JMX.newMXBeanProxy`, whose invocation handler unwraps a
`RuntimeMBeanException` and rethrows the operation's original unchecked exception directly
— `CapabilityDeniedException` and `IllegalArgumentException` reach `Main.run` bare, not
wrapped, for every real invocation. `Main.run` catches both directly for that reason, with
a `RuntimeMBeanException` catch kept only as a defensive fallback for an invocation path
that might still hand one back wrapped (none is known to, today). A future change to this
handling that goes back to relying on the wrapped shape alone would silently stop
recognizing both cases in production despite passing a unit test built on a hand-written
MXBean fake that throws the wrapped shape directly — exercise the unwrapped shape too
(`logaperture-cli`'s `MainRunTest`), and prefer a real cross-process assertion
(`CliEndToEndIT`) for this specific contract.

Exit 7 is the graceful-degradation path of the component-versioning contract (top-level
§11.1): a newer `logctl` invoked an operation, or passed an option, that the older
same-major agent it connected to does not implement. `logctl` maps the JMX
"no such operation / attribute" outcome to this code with a message naming the minimum
agent version required, rather than surfacing exit 1 with a raw `ReflectionException`. No
mutation has occurred. It is distinct from exit 2 (a usage error is wrong on *every* agent;
exit 7 would succeed against a newer one).

## Naming reconciliation

The parent spec uses three verbs across three sections for "make it stop": `logctl
revert <id>` / `logctl revert --all` (§6.1), `logctl undo` (§14.5), and the operations
themselves are `resetLevel` / `resetAll` (§5). This spec standardizes the CLI on
**`reset`**, matching the operation names and Feature 1's vocabulary:

- `logctl reset <logger>` and `logctl reset --all` are this slice.
- `logctl undo` (revert the *last change*, to its *previous value*) stays a distinct,
  deferred command — it is not a synonym for `reset`, and it needs surface that doesn't
  exist yet (see Scope).
- `revert <id>` implied per-override IDs; this slice keys everything on the logger name
  (one override per logger, Feature 1), so there is no ID to pass and `reset <logger>` is
  the whole of it.

§6.1's `revert` wording is updated to `reset` in the same change that implements this
spec (CLAUDE.md's "update the spec alongside the code that caused the divergence"); §8.1's
and §14.5's `logctl undo` stay, since `undo` is still a real planned command.

## Changes outside the new module

Two, both small:

1. **`logaperture-agent`** — `AgentBootstrap`, on a successful install, sets a
   `logaperture.version` system property (value: the agent's Maven version). This is the
   marker the CLI's discovery filters on, and it doubles as a "is it installed, which
   version" probe for any future client. It is set only after install succeeds, so its
   presence is meaningful. No other agent behavior changes; no new dependency.
2. **`doc/logaperture-spec.md`** — add an `> Implementation spec:
   doc/specs/cli-transport.md` link under §8.1 (matching the style of the links under §5
   and §6), and change §6.1's `logctl revert <id>` / `logctl revert --all` to `logctl
   reset <logger>` / `logctl reset --all` per "Naming reconciliation" above. §8.1's and
   §14.5's `logctl undo` stay as written — `undo` remains a distinct, still-planned
   command (see Scope), not a synonym this slice renames.

## Module scope

One new module, no change to the layout §4.6 already anticipates (it lists
`logaperture-cli   attach-API client`):

```
logaperture-cli   NEW. Arg parsing, discovery, the attach+JMX transport, table/JSON
                  rendering, exit-code mapping, the logctl / logctl.cmd scripts.
```

Dependencies:

- `logaperture-api` — the `Level` enum, for validating `<level>` arguments.
- `logaperture-control-jmx` — `LevelControlMXBean`, `LoggerInfoData`, `LevelOverrideData`,
  and `JmxRegistrar.OBJECT_NAME`. This transitively pulls in `logaperture-core`, which the
  CLI actively uses: `CapabilityDeniedException` and `Capability` live there, and the CLI
  needs both on its classpath to unwrap a policy denial into exit 6 (see Failure
  handling). Dragging `core` in would be unacceptable for the *agent* — §9.11's "absence
  beats configuration" packaging discipline — but the CLI is a standalone tool, never
  shaded into a customer artifact, and all of this graph is first-party and JDK-only.
  There is no third-party dependency anywhere in it.
- The JDK's `jdk.attach` module (`com.sun.tools.attach`) — provided by the JDK at
  runtime. **`logctl` requires a JDK, not just a JRE**, and this is documented in the
  launcher script and README.

`logaperture-cli` must **not** depend on `logaperture-agent` (it is a client of a running
agent, not a peer of it) — asserted in the build, same as the existing `core`-must-not-
depend-on-`agent` rule.

Packaging: an executable `logaperture-cli.jar` (`Main-Class:
org.logaperture.cli.Main`), first-party deps folded in so `java -jar` needs nothing on
the classpath. `bin/logctl` (POSIX sh) and `bin/logctl.cmd` (Windows) are one-line
`exec java -jar` wrappers. `Main` is a thin shell over a testable
`int run(String[] args, PrintStream out, PrintStream err)` seam.

## Capability and audit

- **No capability check runs in the CLI.** The checks are server-side, in the operation,
  exactly where Features 1 and 2 put them — the CLI is just another caller. When a check
  fails, `LevelControlService` throws `CapabilityDeniedException` before any mutation; the
  MXBean impl lets it propagate and JMX delivers it to the client wrapped in a
  `RuntimeMBeanException`. The CLI catches that, and if the target exception is a
  `CapabilityDeniedException` it reports exit 6 naming `exception.capability()`; any other
  wrapped exception is exit 1.
- **Audit source stays `"jmx"`** for anything this CLI does, because the CLI genuinely
  invokes the operations through the `LevelControlMXBean`. `persistence.md` anticipated a
  `"cli"` source value; it becomes real only when the operation can be told its caller
  (a source parameter) or when the CLI stops going through JMX. Neither is worth doing in
  this slice — the principal (OS UID) is already recorded and is the fact that matters for
  §9.7's "by whom."
- No new audit records, no change to `AuditRecord` shape, no change to §9.7's deferred
  items (hash-chaining, syslog mirroring).

## Failure handling

Every failure is a clear one-line message to stderr and a specific exit code; nothing
dumps a stack trace unless `--debug` is passed (a hidden flag for the CLI's own
development).

| Situation | Message | Exit |
|---|---|---|
| No arguments, or `--help` | usage text | 0 for `--help`, 2 for no args |
| Unknown command/flag, wrong arity, unparseable level or duration | the specific problem + usage | 2 |
| No candidate JVM | "No LogAperture-enabled JVM found. Start with `-javaagent:logaperture-agent.jar`." — plus, if any running JVM couldn't be inspected, a second line with that count and a `--pid` hint | 3 |
| Several candidates, no `--pid` | table of pids + "pass `--pid <n>`" | 4 |
| `--pid` names a JVM that's gone | "No process with PID <n>." | 3 |
| Attach permission denied | "Can't attach to PID <n> — run as its owner or root." | 5 |
| Attached, but MBean not registered | "PID <n> is running but has no LogAperture agent (or it failed to install)." | 3 |
| `RuntimeMBeanException` wrapping `CapabilityDeniedException` | "Refused: this JVM's policy does not grant <capability>." | 6 |
| Any other exception from the operation (bad args reached the server, adapter failure) | the exception's message, prefixed `logctl:` | 1 |
| Connection drops mid-call | "Lost the connection to PID <n> before the operation completed." | 1 |

`logctl levels`/`status` that match no logger print `No loggers match '<filter>'.` /
`No active overrides.` respectively and exit 0 — an empty result is not an error.

The CLI never retries. A support engineer re-running a command is fine; a tool silently
retrying a mutation is not.

## Testing

Per top-level §12, the split Features 1 and 2 used: cheap exhaustive unit tests, one
shallow cross-process integration test.

**Unit (no JVM):**

- Duration parser: `30m`/`2h`/`90s`/`1d` map correctly; `0m`, `-5m`, `30`, `30x`, ``
  are usage errors.
- Tier resolver: bare &rarr; `FOR`/4h; `session` &rarr; `SESSION`; `for 30m` &rarr;
  `FOR`/30m; `sticky` &rarr; `STICKY`; `for` alone, `session 30m`, `sticky 5m` &rarr;
  usage errors.
- Level validation against `Level`, case-insensitive; unknown &rarr; exit 2.
- Option parsing: `--pid`, `--json`, `--reason`, `--include-children`, `--version`,
  `--help`; unknown flag &rarr; exit 2; no arguments &rarr; usage + exit 2.
- Rendering: table columns align; `--json` output parses and carries the expected keys;
  `status` with no overrides, and `levels` with a non-matching filter, print their
  empty-state lines and exit 0.
- Glob filter: a `levels` filter containing `*` is passed through to `listLoggers`
  verbatim — the CLI does no name matching or grammar validation of its own, that's
  Feature 1's job. (`NameFilter`'s unit tests cover the segment-anchored grammar —
  `level-control.md`'s "`*` present" rules — plus the rejection cases and their specific
  messages, and `LevelControlServiceTest` covers the same rejection at the `listLoggers`
  service seam, including with zero candidate logger names. `CommandsTest`'s stubbed
  `FakeLevelControlMXBean` asserts a glob filter's pass-through — the CLI does no matching
  of its own. `LevelControlEndToEndIT` proves a leading-`*` pattern survives the JMX
  boundary end-to-end; the cross-process `CliEndToEndIT` below runs a real fixture JVM and
  proves an invalid pattern's `IllegalArgumentException`, carried back as a
  `RuntimeMBeanException`, surfaces as a usage error (exit 2 naming the problem) rather
  than an unexpected-failure exit.)
- `reset` three-way outcome (stubbed transport): logger still listed &rarr; "→ level
  (baseline)"; unknown and never overridden &rarr; "nothing was overridden."; override
  cleared but logger drops out of `listLoggers` &rarr; the "not yet instantiated" line and,
  under `--json`, an object with `overrideActive: false` / `wasOverridden: true` rather
  than `null`.
- Exit-code mapping: each `Situation` row above, driven through a stubbed transport, lands
  on its stated code — including a stubbed `RuntimeMBeanException` wrapping a
  `CapabilityDeniedException` &rarr; exit 6 with the capability named.
- **Phone test:** every usage/example string emitted by `--help` contains none of
  `: = ( ) /`.

**Cross-process integration (`CliEndToEndIT`, mirroring `LevelControlEndToEndIT`):**

- One `-javaagent:`'d fixture JVM in a `@TempDir` `logaperture.home`:
  - `run(["levels", FIXTURE_LOGGER])` &rarr; exit 0, shows effective `INFO`, no override.
  - `run(["debug", FIXTURE_LOGGER, "for", "1m", "--reason", "cli-e2e"])` &rarr; exit 0,
    confirmation names tier `FOR` and a revert time.
  - `run(["status"])` &rarr; exit 0, one row, tier `FOR`, reason `cli-e2e`, a revert time.
  - `run(["reset", FIXTURE_LOGGER])` &rarr; exit 0; `status` then prints the empty-state
    line.
  - `run(["reset", "--all"])` &rarr; exit 0 with nothing active.
- Discovery:
  - With just the one fixture JVM, no `--pid` &rarr; commands target it.
  - A second `-javaagent:`'d JVM (its own `logaperture.home`, to avoid the instance lock)
    &rarr; `run(["levels"])` with no `--pid` &rarr; exit 4, both pids listed; adding
    `--pid` for either &rarr; exit 0.
  - A plain JVM with no agent running alongside the fixture &rarr; discovery still
    resolves to the one enabled JVM (marker property filters the plain one out) — an
    attachable non-LogAperture JVM is "not ours", not "uninspectable", so it adds no
    second line.
  - No enabled JVM at all &rarr; exit 3.
- The wrong-OS-user attach path is covered only at the unit level (stubbed attach failure
  &rarr; exit 5) — CI can't portably launch a JVM as another user.

## Exit criterion

From a plain shell, against a `java -jar` application started with
`-javaagent:logaperture-agent.jar` and nothing else special:

- `logctl set logger com.acme.batch.Worker DEBUG for 30m --reason INC-123` finds that JVM
  with no PID argument, sets the level, and prints the local-time revert instant.
- `logctl status` shows that override with its tier and countdown.
- `logctl list loggers com.acme --show-all` lists the package's loggers with configured vs
  effective levels; `logctl list loggers '*Worker' --show-all` finds the same logger from
  its suffix alone (the glob match is Feature 1's `NameFilter`, reached unchanged through
  the CLI).
- `logctl reset logger com.acme.batch.Worker` returns it to baseline; `logctl reset loggers`
  is a safe no-op when nothing is active.
- Two enabled JVMs make the un-`--pid`'d command exit 4 with both listed; zero make it
  exit 3.
- Every command in `logctl --help` passes the phone test.

All of it over the attach + local-JMX transport, with no listener added to the agent
(§8.2) and authorization coming entirely from the OS's attach permission (§9.8) — the
"hand it to support" half of the M1 bar (§17) that JMX-only couldn't reach.
