# Feature 2 — Persistence and Resume

Status: draft, not yet implemented.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §6 (feature definition), §9
(capability/audit model), §17 (roadmap).
Builds on: [`doc/specs/level-control.md`](level-control.md) (Feature 1, M1's already-shipped
slice).

## Scope of this slice

Per the roadmap (§17), Feature 2 is still Layer 0 / M1 — level control alone is not the "read-only
diagnostic release... without approval anxiety" bar M1 sets, because `--session`-only overrides
don't survive a redeploy, which is the exact WildFly-restart problem §3 identifies as the gap.
This slice fills in the rest of M1's persistence surface, deliberately staying inside the
container/framework/control-surface boundary Feature 1 already drew.

**In scope:**

- The `--for <duration>` and `--sticky` persistence tiers (§6.1); `--session` (Feature 1) is
  unchanged.
- The file-based `StateStore` (§6.3): default location, on-disk format, atomic writes.
- The identity problem (§6.4): deriving a stable per-JVM state file from the canonical working
  directory, with `logaperture.instanceId` as the explicit override.
- Expiry enforcement for `--for`: a scheduled sweep, per §5's "expiry should be enforced by a
  scheduled sweep *and* persisted, so it survives restart."
- Resume on restart: reading persisted state back on agent install and re-applying it.
- Reconfiguration re-application (§6.5) for the one framework this project has: wiring Logback's
  own reset event to `LevelControlService.reapplyActiveOverrides` (already implemented and tested
  in Feature 1's slice — see `OverrideReapplyTest` — but nothing calls it yet).
- Precedence items 1 and 2 (§6.6): runtime mutations over persisted state from previous sessions.
- The `persist` capability (§9.3).

**Explicitly out of scope for this slice** (deferred to later feature specs):

- WildFly, Spring Boot, Tomcat, Quarkus containers (§15) — reconfiguration re-application is
  wired for Logback's own reset event only; a container-specific reset source (e.g. Spring Boot's
  `LoggingSystem` reinitializing the whole `LoggerContext`, per the M0 spike's point 3 finding) is
  still deferred, same as Feature 1.
- Log4j 2, JUL, Log4j 1 adapters.
- `logctl` / CLI-over-attach (§8.3) — JMX remains the only control surface, same reasoning as
  Feature 1: "everything else is a convenience over it" (§8.1). The MXBean surface is extended
  (below) so the new tiers are actually reachable, but no new client ships.
- Precedence item 3 (§6.6): a static rules file supplied at agent start. That's Feature 3
  territory (declarative rules), not persistence of runtime mutations.
- A shared or external `StateStore` implementation — the SPI is designed for it (§6.3), nothing
  beyond file-based ships.
- Hash-chained audit entries and syslog/Event Log mirroring (§9.7) — still deferred, as in
  Feature 1.
- `snapshot()` / `restore(snapshot)`.

## Operations

Feature 1's four operations are unchanged in shape; two of the types they use change, and the
JMX surface gains the parameters needed to actually reach the new tiers.

### `PersistenceTier` — new type in `logaperture-api`

```
enum PersistenceTier { SESSION, FOR, STICKY }
```

Named at the point of use, per §6.2's phone test and §6.1's "the choice is conscious." Replaces
the implicit "non-null `expiresIn` means `--for`" convention `SetLevelOptions`'s Javadoc
originally reserved — that convention had no way to express `--sticky`, and this feature needs
one.

### `SetLevelOptions` — additive per its own Javadoc's promise

```
record SetLevelOptions(boolean includeChildren, String reason, Duration expiresIn, PersistenceTier tier)
```

- `tier` defaults to `SESSION` (`SetLevelOptions.defaults()`/`withReason()` are unchanged for
  existing callers).
- Validated at construction: `tier == FOR` requires a non-null, positive `expiresIn`; `tier !=
  FOR` requires `expiresIn == null`. `--sticky` genuinely has no expiry — that's the entire
  difference between it and `--for`.
- Adding `tier` alongside the already-reserved `expiresIn` field keeps Feature 1's stated promise
  intact: no field is removed or repurposed, only added to.

### `LevelOverride` — gains the same two facts, because they must survive a restart

```
record LevelOverride(
    String loggerName, Level level, boolean includeChildren, String reason,
    Instant appliedAt, String source, PersistenceTier tier, Instant expiresAt)
```

- `expiresAt` is an absolute `Instant` (`appliedAt + expiresIn`), not the relative `Duration` from
  `SetLevelOptions` — resume needs an absolute deadline to compute *remaining* time; a relative
  duration re-read from disk would silently reset the clock on every restart, which defeats
  `--for`'s entire purpose ("survives restart *within the window*," §6.1).
- `null` unless `tier == FOR`, mirroring `SetLevelOptions`.
- This is still pure state (Feature 1's data model note) — applying it is still `OverrideApplier`'s
  job, unchanged by this slice.

### JMX surface changes

`LevelControlMXBean.setLevel` gains the two new parameters, using the same "plain types for
generic JMX tooling" convention Feature 1 established:

```
LevelOverrideData setLevel(
    String loggerName, String level, boolean includeChildren, String reason,
    String tier, long forSeconds)
```

`tier` is `"SESSION"`/`"FOR"`/`"STICKY"`; `forSeconds` is ignored unless `tier` is `"FOR"`. This is
a breaking signature change to a pre-1.0, unreleased interface with one existing caller
(`LevelControlEndToEndIT`) — acceptable here in a way it would not be once `logctl` or any other
real client exists. `LevelOverrideData` and `LoggerInfoData` both gain `tier` and `expiresAt`
(String, ISO-8601 or `null`) fields, so a `jconsole` operator (or the eventual `logctl status`)
can see what's active and when it reverts without a second surface — §6.1's "`logctl status` lists
what is active in each tier and when it reverts" is satisfied by JMX attributes in this slice,
same as Feature 1 satisfied the four operations over JMX before any CLI existed.

## Semantics to pin down

### Precedence (§6.6, items 1–2 only in this slice)

1. Runtime mutations made via JMX in the current session — unconditionally win; a live `setLevel`
   call always overwrites whatever's in `OverrideRegistry`, regardless of what's persisted.
2. Persisted state from a previous session, applied once at resume (below), unless it's expired.

Items 3 (static rules file) and 4 (the application's own baseline) are unaffected by this slice:
3 doesn't exist yet, and 4 is already how `BaselineRegistry` behaves.

### Expiry enforcement

A single-threaded scheduled sweep, owned by the composition root (`NoneContainer`, same layer
that already owns baseline capture), not by `LevelControlService` itself — `core` stays free of an
opinion about *when* it runs, only *what* running it does.

- On every active `FOR` override, at `expiresAt`: revert to baseline via the same path
  `resetLevel` uses (`applyReset`), so the resulting audit record is indistinguishable in shape
  from an operator-initiated reset except for `source` (`"expiry-sweep"` instead of `"jmx"`).
- The sweep also removes the entry from the `StateStore` (below) — an expired override must not
  reappear on the next restart.
- Sweep interval: check every 30s (not once per exact deadline) — simple, bounded worst-case
  drift, no per-override timer bookkeeping. `--for`'s minimum granularity is documented as
  "within ~30s of the requested duration," which is a fine bound for its stated use case ("quick
  check during a redeploy loop," "support work, triage") and materially simpler than one
  `ScheduledFuture` per override plus the cancellation bookkeeping that implies.

### Resume on restart

Runs once, inside `NoneContainer.install`, after baseline capture and before the
`LevelControlService` is handed back to the caller — so nothing can observe a half-resumed state:

1. Resolve this JVM's state file (below). If none exists yet, resume is a no-op.
2. Load all persisted entries.
3. For each `STICKY` entry: apply unconditionally via `OverrideApplier`, commit to
   `OverrideRegistry`, write an audit record (`Action.MUTATION`, `source = "resume"`, `reason`
   carried over from the persisted entry) — the audit trail shows the override reappearing, not a
   gap where the previous session's record simply stops.
4. For each `FOR` entry whose `expiresAt` is still in the future: same as `STICKY`, plus
   re-scheduling it with the expiry sweep for its *remaining* time (`expiresAt`, unchanged — this
   is exactly why `expiresAt` is absolute).
5. For each `FOR` entry whose `expiresAt` has already passed while the JVM was down: never
   applied this session, but still written as a `REVERSION` audit record (`source = "resume"`,
   `reason` noting it expired while stopped) and removed from the `StateStore`. The alternative —
   silently dropping it — would mean the only evidence a `--for` override ever existed vanishes
   the moment the process that would have reverted it wasn't running to do so, which fails the
   "revert is recorded too" bar §9.7 already sets for the in-process case.

### Reconfiguration re-application

`logaperture-adapter-logback` gains a `LoggerContextListener` (Logback's own reset-notification
mechanism, per §4.3) that calls back into the composition root on `resetComplete`. The composition
root's response is exactly the sequence Feature 1's plumbing already anticipated:

1. Re-capture baseline for any logger not already in `BaselineRegistry` (a reset can introduce
   loggers the original capture never saw).
2. Call `LevelControlService.reapplyActiveOverrides(adapter)` — literally the method
   `OverrideReapplyTest` already exercises against a fake adapter; this slice is what finally
   calls it against a real one.
3. Re-install is idempotent by construction — `OverrideApplier.apply` is a plain `setLevel` call
   on the adapter, not a stateful wrap-and-track operation, so there's no double-wrapping failure
   mode here the way there is for encoder wrapping (§6.5 item 3) — that risk belongs to a later
   slice that touches rendering, not this one.

`none` still has no reset event of its own (confirmed by the M0 spike, per Feature 1's spec) — this
wiring exists in the Logback adapter because Logback can be told to reload (`scan="true"`, JMX
`JMXConfigurator`, or an application explicitly calling `context.reset()`), independent of which
container is hosting it. `none` benefits from this immediately; it isn't waiting on a container
slice the way §6.5's Spring Boot/WildFly cases are.

## State store

### Location and identity

```
${logaperture.home}/instances/<instanceHash>-<slug>.state.yaml
```

- `logaperture.home` defaults to `${user.home}/.logaperture/` — the OS user-profile directory
  (`%USERPROFILE%` on Windows; Java's `user.home` system property already resolves correctly
  there without any platform-specific code), **not** the JVM's working directory. This supersedes
  the top-level spec's earlier `${user.dir}/.logaperture/` default (§6.3, now corrected) — a
  per-user home is fixed and discoverable; a per-launch working directory is neither, and an
  operator looking for "where did this JVM's state go" would otherwise need to already know where
  the process was started from.
- `<instanceHash>` disambiguates JVMs sharing that one discoverable home. Computed once at agent
  install:
  1. `identityString` = the explicit `logaperture.instanceId` agent/system property if set;
     otherwise the JVM's canonical working directory, `Paths.get(System.getProperty("user.dir"))
     .toRealPath()` — resolving symlinks and normalizing case (matters on Windows) so the same
     real location always hashes the same way even if it was reached via a different-looking
     path.
  2. `instanceHash` = the first 16 hex characters (64 bits — ample for this purpose; these are
     disambiguating identifiers on one machine, not a security boundary) of `SHA-256
     (identityString)`.
- `<slug>` is a filesystem-safe, human-readable tag purely for discoverability when someone is
  browsing the directory by hand — the last path segment of the canonical working directory,
  lowercased, non-`[a-z0-9-]` characters collapsed to `-`, truncated to 40 characters. Not part of
  the identity computation; two directories that happen to share a final segment still get
  different hashes and different files.
- `logaperture.instanceId`, per §6.4, is the escape hatch for the case the default can't
  disambiguate on its own (below).

### The same-working-directory collision

Two JVMs launched from the identical working directory — a real and not-uncommon case for
horizontally-scaled instances of the same app — would derive the identical `instanceHash` by
default. Detected, not silently merged:

1. At agent install, after resolving the state file path, acquire an exclusive `FileLock` on a
   sibling `.lock` file (create-if-absent). The lock file's content is this JVM's PID.
2. Lock acquired → proceed normally; the channel stays open for the JVM's lifetime (OS releases it
   on process exit even on a hard kill, which is what makes this safe to rely on rather than just
   advisory).
3. Lock held by another process → read the recorded PID and check liveness via
   `ProcessHandle.of(pid)`. If that process is gone, the lock is stale from an abnormal exit that
   somehow didn't release it (rare, but possible on some network filesystems) — delete and retry
   acquisition once.
4. Still held by a live process → this instance degrades to session-only for its entire lifetime:
   no resume, no persisted writes, a loud diagnostic (§4.5) explaining why and naming
   `logaperture.instanceId` as the fix. This is the fail-open discipline (§9) applied to identity:
   two JVMs silently sharing one state file would mean each one's resume clobbers or misreads the
   other's overrides, which is worse than one of them simply not persisting.

### File format

A hand-written minimal writer/parser for this schema, not a general-purpose YAML library. The
schema is a flat list of scalar-field records the agent itself fully controls on both ends —
pulling in SnakeYAML (or similar) to parse a format only this code ever writes would add the
project's first third-party `core` dependency, one that then rides along in the agent's shaded
uber-jar (unlike Logback, which is deliberately excluded — §4.6, `Can-Retransform-Classes` note in
Feature 1's spec), for a parsing problem this constrained a hundred or so lines solve directly.
Output is valid YAML — §6.3's "human-readable and hand-editable" bar — the constraint is on the
reader's generality, not the writer's correctness.

```yaml
schemaVersion: 1
overrides:
  - loggerName: com.acme.batch.Worker
    level: DEBUG
    includeChildren: false
    reason: "investigating slot exhaustion"
    appliedAt: 2026-08-21T03:14:02Z
    source: jmx
    tier: FOR
    expiresAt: 2026-08-21T03:44:02Z
  - loggerName: com.acme.payments
    level: WARN
    includeChildren: true
    reason: "known-noisy, muted for good"
    appliedAt: 2026-08-15T10:00:00Z
    source: jmx
    tier: STICKY
    expiresAt: null
```

- Written atomically: serialize to a temp file in the same directory, `fsync`, then
  `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` — never a partial file on disk, even on a crash
  mid-write.
- Whole-file rewrite on every mutation. The expected entry count is small (active overrides on one
  JVM, not a log stream), so there's no scaling concern that would justify an append-log or
  partial-update scheme.
- `schemaVersion` exists from the first release specifically so a future format change has
  somewhere to branch from — unread by this slice's own writer/parser beyond asserting it's `1`.

### `StateStore` SPI

```
interface StateStore {
    List<LevelOverride> loadAll();
    void save(LevelOverride override);   // upsert by loggerName
    void remove(String loggerName);
    void clear();
}
```

Lives in `org.logaperture.core.spi`, alongside `LoggingAdapter` — same rationale as §6.3's
"pluggable from day one": the file-based implementation (`FileStateStore`, in `core` itself, per
the module layout's existing "state store" line) is what ships, but a shared/external store for
containerized or clustered deployments (§18.6) is a later implementation of this same interface,
not a redesign of anything that calls it.

## Data model summary

```
PersistenceTier { SESSION, FOR, STICKY }          — logaperture-api, new

LevelOverride {                                    — logaperture-api, extended
    loggerName, level, includeChildren, reason,
    appliedAt, source,
    tier: PersistenceTier,                          new
    expiresAt: Instant?                             new, non-null iff tier == FOR
}

SetLevelOptions {                                  — logaperture-api, extended
    includeChildren, reason, expiresIn,
    tier: PersistenceTier = SESSION                 new
}

StateStore {                                       — logaperture-core.spi, new
    loadAll() -> List<LevelOverride>
    save(LevelOverride)
    remove(loggerName)
    clear()
}
```

## Module scope for this slice

No new modules — everything here fits the layout §4.6 already drew:

```
logaperture-api                PersistenceTier (new); LevelOverride, SetLevelOptions (extended)
logaperture-core                StateStore SPI + FileStateStore, expiry sweep, resume wiring in
                                 NoneContainer, Capability.PERSIST
logaperture-adapter-logback      LoggerContextListener for reconfiguration re-application
logaperture-container-none       resume-on-install, expiry sweep lifecycle (start/stop)
logaperture-control-jmx          setLevel/LevelOverrideData/LoggerInfoData extended for tier+expiry
```

`core`'s "no dependency on `agent`" constraint (§4.6, already enforced in the build) is unaffected
— `FileStateStore` only needs `java.nio.file` and `java.security.MessageDigest`, both JDK-standard.

## Capability and audit

- New capability: `persist` (§9.3) — "making a change outlive the process, rather than expiring."
  Required in addition to `level.raise`/`level.lower` whenever `tier != SESSION`; checked at the
  same pre-flight point Feature 1's fan-out capability check already occupies, so a denied
  `persist` grant fails the whole `setLevel` call before any mutation happens, not partway through
  writing state.
  - Default policy for this slice, matching Feature 1's stance: granted, local-only — proving the
    check exists at the right seam, not shipping a hardened default (§9.11 is still a later
    milestone).
- New audit sources, both per-mutation, same `AuditRecord` shape as Feature 1 defined:
  - `"resume"` — an override reappearing (or expiring-while-stopped) at agent install, per the
    Resume section above.
  - `"expiry-sweep"` — an override auto-reverting on its `--for` deadline.
- Nothing here changes §9.7's deferred items (hash-chaining, syslog/Event Log mirroring) — same
  deferral as Feature 1.

## Failure handling

- `StateStore` I/O failure (unreadable file, unwritable directory, corrupt YAML) on **load**: log
  a diagnostic, proceed with an empty resume set — a JVM that can't read its own state starts
  clean rather than refusing to start, per §9's fail-open discipline. This is a real gap
  worth being honest about: a corrupt state file is indistinguishable, from the operator's
  perspective, from "nothing was ever persisted" unless they read the diagnostic.
- `StateStore` I/O failure on **write** (disk full, permissions changed mid-run): the in-memory
  mutation still succeeds (the running JVM's behavior is correct for this session) but the
  persistence write is logged as failed and *not* silently retried indefinitely — one attempt,
  one diagnostic line, move on. A `--sticky` override that can't be persisted degrades to
  behaving like `--session` for that session, which is a safe direction to fail in (less durable,
  never more).
- The same-working-directory lock failure (above) is not treated as an error — it's an expected,
  documented degradation path, not an exception that propagates.

## Testing

Per top-level §12, same split Feature 1 used: adapter-behavior tests dominate, container tests stay
shallow.

Minimum coverage before this slice is done:

- `FileStateStore` round-trips every field, including `null` `expiresAt` for `STICKY`/`SESSION`
  and a populated one for `FOR`.
- Atomic write: an interrupted write (kill mid-`save` in a test harness, or assert via a
  temp-file-then-rename seam) never leaves a corrupt or partial `state.yaml`.
- Identity derivation: same canonical working directory (including via a symlink) always yields
  the same file; different directories never collide (short of an actual 64-bit hash collision,
  not asserted directly).
- `logaperture.instanceId` override takes precedence over path derivation.
- Lock collision: two `install()` calls against the same identity in one test process — the second
  degrades to session-only, logs a diagnostic, and does not throw.
- Resume: `STICKY` always reapplies; unexpired `FOR` reapplies with correctly-reduced remaining
  time; expired `FOR` never reapplies and is recorded as a `REVERSION`.
- Expiry sweep: a `FOR` override reverts within the sweep's bound after its deadline, with a
  correct audit record, and is removed from the store so it doesn't reappear on a subsequent
  resume.
- Reconfiguration re-application: trigger a real Logback `context.reset()` mid-test (not just a
  fake adapter, unlike Feature 1's `OverrideReapplyTest`) and assert overrides survive and are not
  double-applied.
- Capability: `persist` denial blocks `--for`/`--sticky` `setLevel` calls without touching the
  adapter or the registry, mirroring Feature 1's capability-check tests.
- Chaos case: a `StateStore` that throws on every call must still let level control function
  correctly in-memory for the session (fail-open, per Failure handling above).

## Exit criterion

A `--for` override, set over JMX against a plain `java -jar` process running Logback, survives a
full process restart from the same working directory with its remaining expiry correctly reduced,
and still expires on schedule after resuming. A `--sticky` override survives the same restart
unconditionally and is removed only by an explicit `resetLevel`/`resetAll`. Both are visible over
JMX with their tier and expiry, both appear correctly in the audit trail across the restart
boundary, and a Logback `context.reset()` mid-session reapplies every active override without
duplication — matching the M1 bar this slice exists to complete: "a read-only diagnostic release
you can hand to support and a customer without approval anxiety," now genuinely surviving the
restart the WildFly problem (§3) was named after.
