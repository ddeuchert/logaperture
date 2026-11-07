# Spike — writing the audit trail through the application's file handler

Status: **desk study done (2026-11-06); runtime checks listed under "Still to prove"**. The
answers below come from reading the code and the signed-off specs, not from running a harness.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §9.7 (the audit trail), §4.5
(the agent does not use the application's logging framework), §9.6 (suppression is never silent).
Related: [`doc/specs/quieter-output.md`](../specs/quieter-output.md) Q5 (drop summary through the
platform logger) and Q6 (audit stays where it is), issue
[#124](https://github.com/ddeuchert/logaperture/issues/124),
[#181](https://github.com/ddeuchert/logaperture/issues/181) (hash chain).
Issue: [#328](https://github.com/ddeuchert/logaperture/issues/328). Milestone `1.x` until this
spike says where it fits.

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document.

## Question

Can the audit trail be written through the application's own logging (its file handler), so an
operator gets one log in one format, without losing what §9.7 promises: squelch rules cannot
match it, and nothing is lost while LogAperture is starting?

## What exists today

- `AuditLog` is a one-method interface (`record(AuditRecord)`). `StderrAuditLog` is the only
  production implementation: stderr, or `-Dlogaperture.audit.file=<path>`.
- It is constructed with `new StderrAuditLog()` in two places, `AgentBootstrap` and the Quarkus
  `ExtensionEngine`, and passed down to the services that record. A second sink is a change at
  those two sites, not in the services.
- Precedent for a platform route: the drop summary (Q5). `AggregateLevelControl` holds a
  `Consumer<String>` sink; `JBossLogManagerContainer` sets it to
  `Logger.getLogger("org.logaperture.drop").info(line)`; `RuleService` never lets a rule reach
  that category; if the sink throws, the line falls back to `Diagnostics`. Only the JBoss
  LogManager container wires it. Logback, Log4j 2 and the none container still use `Diagnostics`.

## Findings

**F1. The seam is small.** A composite `AuditLog` (dedicated sink plus an optional platform
sink) covers it. No service changes.

**F2. Re-entrancy is not the hard part.** Audit records come from the JMX thread (`logctl`), the
sweep thread (expiry, verification, auto-recompute) and context-ready (resume, vendor defaults).
None runs inside an application log call, which is the same reason Q5 is safe. One case to
prove: whether context-ready can run on a thread that is inside a log call on WildFly.

**F3. The bootstrap window is smaller than it looks.** Q1/Q2 already cut startup audit volume
to two records, and the vendor-defaults audit is written at context-ready, not in `premain`
(see the comment in `AgentBootstrap`). The first audit record probably arrives only once the
adapter exists, which is also when the platform logger can be reached. If that holds, no replay
buffer is needed for the normal case. What stays is the failure case: the platform never binds
(no supported framework, bind failure, #311/#322). There the audit must still appear.

**F4. Replacing the dedicated sink loses §9.7; teeing keeps it.** If the audit goes only through
the platform, then (a) `set logger org.logaperture.audit WARN` or a handler level silences it, and
(b) another handler's formatter or filter can change the line, which would break a hash chain
(#181). If the dedicated sink stays as the record of truth and the platform copy is a tee, both
problems go away: an operator can turn the platform copy down without touching the trail. The
cost is the same line in two places unless the dedicated sink is pointed at a file or off.

**F5. A rule guard is needed, and is the same as Q5's.** Extend `RuleService`'s own-category
guard to `org.logaperture.audit` so no `drop` or `trim` on `org` or the root reaches it, and make
`add rule` refuse that target.

**F6. Coverage is uneven across platforms.** JUL and WildFly and Quarkus (JBoss LogManager) are
one `Logger.getLogger(...)` call, as for the drop summary. Logback needs the adapter to reach the
application's Logback through its class loader; Log4j 2 needs the Log4j 2 adapter's logger. Plain
JVM with no framework has no platform to write to. That is the same gap the drop summary has
today.

**F7. Format.** The line gets the application's prefix (timestamp, level, category, thread) in
front of the existing `[logaperture-audit] ...` text, so `grep 'handler='` still works. A
platform copy therefore has a second timestamp. Whether to keep the prefix in the message is a
user-visible choice for `guide/reference/audit-log.md`.

## Still to prove

1. On WildFly, which thread writes the first audit record and is the platform logger usable then?
   (F2, F3)
2. With a tee and a console handler, the audit appears twice on the console (stderr and the
   handler). Check what the default should be.
3. A level override or handler floor on `org.logaperture.audit`: confirm the dedicated sink is
   unaffected.
4. Logback and Log4j 2: where the agent gets a logger from outside the application's class loader.

## Recommendation

Build it as a **tee, not a redirect**: keep the dedicated sink, add an opt-in platform copy
(JUL/JBoss LogManager first, since that is one call and has the Q5 precedent), guard the category
against rules, and fall back to the dedicated sink alone when the platform is not bound. Leave
Logback and Log4j 2 for when their platform-logger work lands.

This changes spec §9.7 ("the runtime cannot redirect" stays true: the choice is made at startup
and the dedicated record is never removed) and Q6, and it changes the audit-log guide page, so it
needs its own spec under `doc/specs/` before code.

## Decisions for the owner

1. **Tee or replace?** Recommended: tee.
2. **How is it switched on?** A `-Dlogaperture.audit.*` property and agent argument, off by
   default, or on by default where a platform logger exists?
3. **Dedicated sink default when the tee is on.** Keep stderr, or default to a file, to avoid the
   double console line?
4. **Timeline.** JUL/JBoss LogManager only is small enough for 1.1.0; Logback and Log4j 2 would
   follow with their platform-logger work.
