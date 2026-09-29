# `logctl doctor` — `-javaagent` Ordering Check

Status: implemented. Decisions #1–#4 signed off 2026-09-29, all on the recommended option. Tracked
as [#85](https://github.com/ddeuchert/logaperture/issues/85).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §16.2 (`doctor`), §18.14
(filtering events logged before the container's logging is ready — "document the `-javaagent`
ordering constraints … `logctl doctor` output, which could flag agents listed ahead of ours").
Builds on: [`doc/specs/doctor.md`](doctor.md) (the finding model, severities, rendering and
"a check that can't run is skipped"), [`doc/specs/vendor-defaults.md`](vendor-defaults.md)
"Surfaces" (the precedent for a process-wide finding that carries no context).

## Functional summary

After this feature, the user will be able to:

- Run `logctl doctor` and see whether other `-javaagent` entries are listed ahead of
  LogAperture's, which ones, and why that matters: anything those agents log while starting up
  is out of reach of `drop`/`trim` rules.
- See when the same agent jar is listed twice on the command line.
- See a clean `OK` line when LogAperture is the first agent, and nothing at all when LogAperture
  was attached to a running JVM rather than started with `-javaagent`.

## Motivation

An agent listed earlier on the command line runs its `premain` first. Since #87 the handler-level
install runs at LogAperture's own `premain` (§18.14, "Direction"), so a sticky or vendor
`drop`/`trim` rule reaches anything that is logged through the logging framework *after*
LogAperture's `premain` — including other agents' startup logging — but nothing logged before it.
Real case: a WildFly launch with `-javaagent:` order `perfmon4j`, `fss-security-agent`,
`destiny-agent`, `logaperture-agent`, where `DestinyAgent.premain` logs an `ERROR` plus stack
trace at boot that a sticky `trim` rule misses.

The constraint is written down in `USER_GUIDE_NOTES.md`, but the operator hitting it is looking at
`doctor`, not the notes. This check puts the fact — and the actual order on *this* JVM — where
they look.

## Scope

**In scope:**

- Two process-wide checks in `logctl doctor`, `agent.order` and `agent.duplicate` (below),
  surfaced through the existing `diagnose()` / `logctl doctor [--json]` with no new operation,
  flag, field or capability.
- Reading the JVM's own input arguments in-process. `doctor` runs inside the target JVM, so the
  "how does doctor obtain the arguments for a remote/attached JVM" question from #85 does not
  arise: it asks its own `RuntimeMXBean`, whatever transport the CLI used to reach it.

**Out of scope:**

- Native agents (`-agentpath:`, `-agentlib:`). They load at VM initialisation, before any
  `-javaagent` `premain` whatever their position, and don't log through the Java logging
  framework, so their order relative to ours means nothing this check could advise on.
- Naming or special-casing known agents (APM, security, profilers) — see Decision #2.
- Changing when LogAperture arms rules, or capturing output that other agents write before our
  `premain` or outside the logging framework — that is §18.14's remaining open work.
- Agents loaded by dynamic attach (`agentmain`) — theirs or ours. They never appear in the input
  arguments; see "When the check doesn't run".

## The checks

### Reading the order

The input is `RuntimeMXBean.getInputArguments()`, in the order returned. On HotSpot that list
already contains `JAVA_TOOL_OPTIONS` first, then `JDK_JAVA_OPTIONS`, then the command line —
the order the JVM processes them in, which is also the order it runs `premain`s in (verified on
JDK 21). Every element starting with `-javaagent:` is one agent entry; its jar path is the text
after the prefix up to the first `=` (the JVM's own split; the rest is that agent's options).

Each path is resolved against `user.dir` and normalised, then to its real path where the file
exists, so `./lib/a.jar`, `lib/a.jar` and a symlink to it compare equal.

**Which entry is ours** (Decision #3, resolved: Option A): the entry whose resolved path equals the jar LogAperture's
own classes were loaded from (the agent classes' code source). If that can't be determined — no
code source, or it isn't a jar — the first entry whose file name starts with `logaperture-agent`
and ends in `.jar`.

### `agent.order`

- LogAperture's entry is first → one `OK` row:
  `logaperture-agent.jar is the first -javaagent.`
- One or more entries precede it → one `INFO` row (Decision #1, resolved: Option A):

```
[INFO]  3 agents are listed ahead of logaperture-agent.jar: perfmon4j.jar, fss-security-agent.jar,
        destiny-agent.jar.
```

  - `subject`: LogAperture's resolved jar path.
  - `detail`: `an agent listed earlier runs its premain first, so anything it logs while starting
    up is out of reach of drop/trim rules. Listing LogAperture first narrows that window; output an
    agent writes outside the logging framework (its own console or file) stays out of reach
    either way.`
  - `suggestedFix`: `list -javaagent:<our path as given> before the other -javaagent entries.`
  - The summary names the other agents by file name, in command-line order; `detail` is where
    full paths would go if two share a file name.

### `agent.duplicate`

For each jar path (resolved as above) that appears in more than one `-javaagent:` entry, one row:

```
[INFO]  destiny-agent.jar is listed 2 times as a -javaagent.
```

  - `subject`: the resolved path.
  - `suggestedFix`: `remove all but one -javaagent:<path as first given> entry.`
  - Severity (Decision #4, resolved: Option A): `INFO` for another agent's jar, `WARNING` for
    LogAperture's own, whose `detail` says what that costs: every copy after the first fails to
    lock the state file and to register its control surface, and logs those errors at startup
    (observed with the shaded jar, 2026-09-29). A guard against a second bootstrap is a separate
    bug, not part of this feature.
  - No `OK` row when nothing is duplicated. This departs from the other checks, which report a
    clean `OK` line: a duplicate is rare enough that a permanent "no duplicate agents" line would
    be noise next to `agent.order`'s own `OK` line, which already tells the user the agent list
    was inspected.

### When the check doesn't run

Both checks contribute **no rows** — not an `OK`, not a failure — when:

- `getInputArguments()` throws (a `SecurityException` under a security manager, for instance);
- no `-javaagent` entry is identifiable as LogAperture's — the agent was loaded by dynamic attach,
  or the jar was renamed beyond recognition *and* the code-source match failed. `agent.duplicate`
  still runs in the second case: a duplicate of some other agent is worth knowing regardless.

This is `doctor.md` "Failure handling" unchanged: a check with nothing it can inspect is skipped,
never a `doctor` failure.

## Where the findings appear

The input arguments are process-wide, so the findings carry `context: null` and are produced once
by `AggregateLevelControl.diagnose()`, next to the vendor-defaults findings — never once per
logging context. The text renderer treats them like the vendor-defaults rows: no prefix when the
output covers one context, a `[-]` prefix when it spans several (the existing rule). `--json` rows
have `"context": null`.

Order in the output: the vendor-defaults rows, then `agent.order`, then `agent.duplicate`, then the
per-context rows, as today. The summary line's counts include them like any other finding.

The `logctl doctor` exit code is unchanged: advisory findings never make it non-zero.

## Implementation notes

- A small `AgentOrderCheck` in `logaperture-core` takes the argument list, the own-jar path
  (`Optional<Path>`) and `user.dir`, and returns `List<DoctorFinding>`. Pure function, no JVM
  access, so the unit tests feed it synthetic argument lists.
- `AggregateLevelControl` supplies the real inputs: `ManagementFactory.getRuntimeMXBean()
  .getInputArguments()`, the code source of its own class (in production the shaded agent jar
  carries `logaperture-core`), and `System.getProperty("user.dir")`. A test-visible constructor
  seam lets `AggregateLevelControlTest` replace the argument supplier.
- No `premain`-time work: the arguments are read when `diagnose()` is called, long after startup,
  so the §15.6 "premain gotcha" doesn't apply.

## Testing

- Unit — `AgentOrderCheckTest`: first / not first (one and several ahead) / not present (attached)
  / our jar identified by code source vs. by file-name fallback / relative vs. absolute vs.
  symlinked paths comparing equal / agent options after `=` ignored / duplicate of another agent /
  duplicate of our own / `JAVA_TOOL_OPTIONS`-style entries at the front counted as ahead.
- Unit — `AggregateLevelControlTest`: the rows appear once with `context == null` across a
  two-context aggregate; a throwing argument supplier yields no `agent.*` rows and the rest of
  `diagnose()` is unaffected.
- CLI — `CommandsTest`: an `agent.order` `INFO` row renders without a context prefix and counts in
  the summary line.
- Cross-process — `LevelControlEndToEndIT` already launches a fixture JVM with the shaded agent:
  assert `diagnose()` over JMX reports one `agent.order` `OK` row with no context there (the
  fixture's only agent), and no `agent.duplicate` row.
- Manual, with the shaded jar in a Logback app, reading the MBean in-process (2026-09-29): the jar
  renamed to `la.jar` behind another agent is identified by code source and reported `INFO`; a
  second agent listed twice is `INFO`; our own jar listed twice is `WARNING`; ours alone is `OK`.

## Decisions (signed off 2026-09-29)

All four resolved on Option A, the recommendation. The options are kept below for the record.

**#1 — Severity when agents are listed ahead of LogAperture.**
- **A (recommended): `INFO`.** Many sites are required to list an APM or security agent first,
  and since #87 the effect is bounded to what those agents log *during their own `premain`*.
  It explains a missed rule; it isn't a misconfiguration.
- B: `WARNING`. Louder, but it would be a permanent, unfixable warning on every site whose policy
  puts another agent first, which trains people to ignore `doctor`'s warnings.
- C: `WARNING` only when rules exist that could have applied (sticky or vendor `drop`/`trim`),
  `INFO` otherwise. More precise, but it couples a process-wide check to per-context rule state
  for a small gain.

**#2 — Name known-noisy agents?**
- **A (recommended): no.** Report every agent ahead of ours by file name, no built-in list. The
  finding is about position, not about which agent; a list is content to maintain with no
  mechanism benefit.
- B: a small seed list (e.g. Destiny, perfmon4j) that adds "known to log at startup" to the detail.

**#3 — How LogAperture's own entry is identified.**
- **A (recommended): code source first, file name as fallback** (as specified above). Survives a
  renamed jar (`/opt/agents/la.jar`) and a versioned name (`logaperture-agent-1.0.0.jar`).
- B: file name only (`logaperture-agent*.jar`). Simpler, but a renamed jar silently disables the
  check.

**#4 — Severity of a duplicated agent jar.**
- **A (recommended): `INFO` for another agent's jar, `WARNING` for LogAperture's own.** The JVM
  calls `premain` once per entry; for LogAperture that means two bootstraps, which `AgentBootstrap`
  has no guard against today. Checked during implementation: it is harmful, not merely wasteful
  (see `agent.duplicate`). The guard is a separate bug, not part of this slice.
- B: `INFO` for every duplicate, including ours (`USER_GUIDE_NOTES.md` currently calls a
  duplicate "harmless but pointless").
