# Spike — `logctl` as a handler, and `logctl listen`

Status: **in progress.** Question 1 (transport) is answered for plain Logback on JDK 21, and
question 2 (installing and removing the handler) for plain Logback, plain JUL and WildFly.
Questions 3–7 are open.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §8.2 (the agent never listens),
§9.9 (the agent never transmits anything), §13 risk 4 (scope creep toward "log router"), §16.7
(all state size-bounded).
Related: [`doc/specs/cli-transport.md`](../specs/cli-transport.md) (attach + local JMX),
[`doc/specs/handler-floor-control.md`](../specs/handler-floor-control.md),
[`doc/specs/filtering-epic.md`](../specs/filtering-epic.md),
[`dynamic-attach.md`](dynamic-attach.md).
Issue: [#230](https://github.com/ddeuchert/logaperture/issues/230), feeding
[#195](https://github.com/ddeuchert/logaperture/issues/195). Post-1.0 (milestone `1.x`).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the harness that produces it.

## The idea

Today `logctl` only changes levels and rules on handlers the application already has. This
spike looks at letting `logctl` receive records itself:

- a command that adds a LogAperture-owned handler to a running JVM;
- `logctl listen`, which streams that handler's records to stdout, to a file, or to both.

#195 describes the longer path this leads to: `listen`, then a Chainsaw-style console view, then
general on-the-fly handlers.

## Question

1. **Transport, against §8.2 and §9.9.** The agent opens no network connections, inbound or
   outbound, ever. #195 suggests a dedicated local socket, which would break that. Can records
   reach `logctl` without one? Candidates:
   - (a) `logctl` polls a bounded ring buffer through an MBean operation (`drain(cursor, max)`)
     over the attach + local JMX connection `logctl` already uses;
   - (b) JMX notifications. On the RMI connector the client pulls them, and the server keeps a
     bounded buffer that reports how many were lost, so this may suit log volume better than
     #195 assumed;
   - (c) the agent writes a bounded, rotating file and `logctl` tails it;
   - (d) for comparison only, a Unix-domain socket that `logctl` creates and the agent connects
     to.

   For each one, measure records/s sustained, latency, CPU on both sides, and what loss looks
   like.
2. **Installing and removing a handler at runtime**, on plain Logback, plain JUL and WildFly
   (JBoss LogManager). Does it survive a logging reconfiguration through the re-apply-on-reset
   hook? On WildFly it's invisible to jboss-cli. Does that matter, and does `doctor` need to
   report it?
3. **Lifecycle.** Is adding the handler separate from `listen`, or does `listen` attach it and
   remove it when it exits? Who removes the handler when a listener dies without cleaning up (a
   lease, or a heartbeat that has to arrive before a deadline)? Can several listeners share one
   handler? Which tier (session / for / sticky) can a handler have?
4. **Never slowing the application.** Measure the cost per record with the handler installed and
   nobody draining it, and with a slow consumer, using the
   [overhead harness](../specs/overhead-benchmarks.md). Records are dropped and counted, never
   blocking.
5. **Where the tap sits in the rule pipeline.** Does it see records before or after `drop`,
   `trim` and storm collapse? Which is the useful default?
6. **Listening below the configured level.** Can `listen <logger> TRACE` work without flooding
   the application's other handlers? Is a listener-only filter feasible with the existing filter
   layering?
7. **Security and audit.** Streaming is a data egress channel (§9.9). Does it need its own
   capability so it can be denied separately, like `capture`? What audit lines does it write?

## Environment

One machine. Every result below comes from it.

- Linux (Fedora, kernel 7.2), 8 cores, 23 GB. Temurin JDK 21.0.12 for both the application and
  the client.
- Application: SLF4J 2.0.17 / Logback 1.5.18, root at INFO, one `FileAppender` writing to
  `/dev/null`, `-Xmx512m`. Four threads log one parameterised INFO line each (about 90
  characters) at a fixed total rate, round-robin over 20 loggers.
- Tap: a throwaway agent loaded with `-javaagent`. It adds one `UnsynchronizedAppenderBase` to
  the root logger. The appender copies level, logger, thread, time and the formatted message into
  a 65,536-slot ring with a sequence number per record. Writers never wait, and a reader that
  falls behind skips ahead. Each sequence gap is a lost record, so loss is counted the same way
  for every transport.
- Client: a stand-in for `logctl listen`. It connects the same way `logctl` does today
  (`VirtualMachine.attach`, `startLocalManagementAgent`, local JMX), starts the tap, formats
  every record into a text line and writes it to a file (the `--file` case).
- Question 2 used a second throwaway agent. It adds the same ring-backed handler to whichever
  framework is present, with an optional hook that re-adds the handler after a reconfiguration.
  The test application logs every 10 ms through two loggers: an ordinary one, and one whose
  records don't reach the root (`additivity="false"` in Logback, `useParentHandlers=false` in
  JUL). On WildFly the agent was loaded into the running server with `VirtualMachine.loadAgent`.
  The server was the dev environment's `quay.io/wildfly/wildfly:26.1.3.Final-jdk17`, with the
  sample WAR's timer logging, and the changes were made through `jboss-cli.sh`.
- Each transport run lasts 20 s (16 s for the flat-out and slow-listener runs). The application runs
  5 s before the listener connects. CPU is process CPU time over the window, as a percentage of
  one core. "Live heap" is the application's used heap right after a forced full GC, taken just
  before the tap stops.

## Findings: transport (question 1)

### 1. The candidates, as built

Four transports share the ring and the appender. They differ only in how records leave the JVM:

| | Transport | Thread in the agent | Opens a connection |
|---|---|---|---|
| (a) **RING** | `logctl` calls an MBean operation `drain(cursor, 8192)` over the existing JMX connection, every 10 ms when idle. Returns one `byte[]` batch. | none | no |
| (b) **NOTIFY** | An agent thread drains the ring every 10 ms into one JMX notification per batch (up to 8,192 records). | one | no |
| (b′) **NOTIFY_EACH** | One JMX notification per record, sent from the application thread. This is the design #195 rejected. | none | no |
| (c) **FILE** | An agent thread drains the ring into 8 MB segment files, keeping the newest four, in a directory `logctl` names. `logctl` tails them. | one | no |
| (d) **UDS** | `logctl` binds a Unix-domain socket; an agent thread connects to it and writes batches. Measured for comparison only. | one | **yes, outbound** |

The JMX connection isn't new. `startLocalManagementAgent` already opens the JDK's loopback RMI
connector for every `logctl` command today. (a) and (b) add no endpoint of their own.

### 2. Throughput, latency and CPU at fixed rates

Application CPU is shown as extra percentage points over the same rate with nobody listening.

| Rate | | RING | NOTIFY | NOTIFY_EACH | FILE | UDS |
|---|---|---|---|---|---|---|
| 1k/s | app CPU | +22 | +17 | +35 | +4 | +3 |
| | latency p50 / p99 | 7 / 16 ms | 7 / 13 ms | 1 / 3 ms | 11 / 20 ms | 6 / 11 ms |
| 10k/s | app CPU | +35 | +23 | +64 | +7 | +12 |
| | latency p50 / p99 | 9 / 23 ms | 7 / 14 ms | 1 / 83 ms | 11 / 20 ms | 7 / 12 ms |
| | lost | 0 | 0 | 27 | 0 | 0 |
| 50k/s | app CPU | +50 | +61 | +105 | +43 | +43 |
| | latency p50 / p99 | 13 / 161 ms | 9 / 69 ms | 2 / 40 ms | 14 / 151 ms | 9 / 90 ms |
| | lost | 0 | 0 | **3.1 %** | 0 | 0 |
| flat out | delivered | 151k/s | 146k/s | — | 108k/s | 134k/s |
| | lost | **2.5 %** | 0 | — | 0 | 0 |

At a 200k/s target the application topped out at 115–165k records/s. Delivered rates and
loss there matched the flat-out row. NOTIFY_EACH lost 8.5 %, and the JMX client reported
246,677 notifications lost.

- **Per-record notifications are out**, as #195 assumed. They cost the most CPU on both sides.
  The connector's buffer holds 1,000 notifications, so they start losing records at 50k/s.
- **RING's cost at low rates is the polling.** It makes 100 RMI calls a second even when there
  is nothing to fetch, and that costs the application more than every other design at 1k–10k/s.
  Polling less often when a batch comes back empty would cut that and add latency. That wasn't
  measured.
- **RING tops out around 125–150k/s** with this client: one thread fetching 8,192 records per
  round trip. Beyond that it loses 1–2.5 %, counted exactly. That's a limit of the client loop,
  not of the ring, and bigger batches or pipelined calls weren't tried.
- **FILE and UDS are cheapest for the application** at low and moderate rates, because the
  agent writes raw batches and does no RMI serialization.

### 3. Batched JMX notifications hold hundreds of megabytes

| Flat out, live heap | none | RING | NOTIFY | FILE | UDS |
|---|---|---|---|---|---|
| MB | 6 | 16 | **282** | 17 | 16 |

The ring itself costs about 10 MB everywhere. NOTIFY adds about 270 MB more, and the JDK source
explains why. `com.sun.jmx.remote.internal.ArrayNotificationBuffer` keeps the last
`jmx.remote.x.notification.buffer.size` notifications (default 1,000). It drops one only when
the queue is full, never because a client has fetched it. At full batches that is up to about
8 million records held on the application's heap, even while the client keeps up.

There are three more problems:

- **The buffer is JVM-wide.** It's shared by every MBean and every connector server. Log
  batches would push out the notifications other JMX tools in the same JVM depend on, such as
  JConsole, VisualVM and monitoring agents. Those tools would then see `NOTIFS_LOST`.
- **The agent can't size it.** It's a system property read when the connector server is
  created, and it applies to the whole JVM.
- **A slow listener receives stale records for minutes** (see §4). The buffer delivers its
  backlog in order and the agent can't see how far behind the client is.

Smaller batches would hold less heap but hit the 1,000-notification limit sooner. So batched
notifications are out as well.

### 4. A slow listener never slows the application

50k records/s offered, with the client capped at 5k/s:

| | none | RING | NOTIFY | FILE | UDS |
|---|---|---|---|---|---|
| app calls/s | 50,000 | 50,000 | 50,000 | 50,000 | 50,000 |
| not delivered | — | 83 %, counted | 90 %, backlog in JMX | 92 %, whole segments | 81 %, counted |
| live heap | 5 MB | 15 MB | 86 MB | 15 MB | 18 MB |

The ring holds in every case: the application kept logging at exactly its target rate.

How each one loses records differs:

- **RING and UDS** drop the oldest records and count every one.
- **FILE** loses whole 8 MB segments when they are rotated away before the reader gets to them.
  The gaps are coarse, and records sit on disk in the meantime.
- **NOTIFY** holds the backlog on the application's heap and keeps delivering it after the
  listener stops. With 16 s of output queued at 5k/s, the client would have been reading
  records minutes old. The harness had to stop waiting for it.

### 5. Recommendation

**(a) RING: `logctl` pulls batches from a bounded ring over the existing JMX connection.**

- It is the only design that adds nothing to the agent's boundary. There's no socket, no file,
  and no agent thread. It uses the same connection, and the same OS-user authorization, as
  every other `logctl` command. §8.2 and §9.9 hold as written.
- Memory is bounded by the ring and belongs to LogAperture alone. Nothing shared with other JMX
  users grows.
- Loss is exact. `logctl` sees each sequence gap and can print `[dropped 1,234 records]`, as
  #195 asks.
- When the listener stops, the agent stops: if nobody drains, nothing is sent. Removing the
  handler on a missed lease (question 3) fits naturally, because the agent already sees every
  `drain` call.
- Its costs are application CPU for RMI serialization at 10k–50k/s, and a client ceiling of
  around 150k/s. Both are tuning problems in the client loop and poll interval, not design
  problems.

`listen --file` should be written by **`logctl`**, not the agent. That keeps log content out of
any file the agent creates, and it's what the harness did.

**(d) UDS was the best performer, but it is excluded by design.** The agent would make an
outbound connection, which is exactly what §9.9 rules out "for any feature". Reopening that
would be a spec decision, and nothing here forces it.

**(c) FILE is the fallback** if RING's CPU cost turns out to matter. It doesn't break §8.2 as
written, but it leaves log content at rest in agent-written files. Its loss is coarse, and the
files need cleaning up when `logctl` dies.

Two requirements carry into any spec:

- **The ring needs a byte cap, not just a slot count.** A 65,536-slot ring of 90-character
  messages is about 10 MB. The same ring full of 16 KB messages or rendered stack traces would
  be about a gigabyte. §16.7 applies.
- **Formatting stays on the application thread, as Logback's `AsyncAppender` does it.** The
  harness formatted the message in `append` because the arguments a caller passes may change
  after the call returns (Logback's `prepareForDeferredProcessing` exists for this reason). That cost is in every figure above.

## Findings: installing and removing the handler (question 2)

### 6. Adding and removing works on all three; the root alone is not enough

Adding the handler to the root logger at runtime, and removing it again, worked on plain
Logback, plain JUL and WildFly. On WildFly that includes loading the agent into the running
server. There, the handler sits on JBoss LogManager's root next to the subsystem's
`PeriodicRotatingFileHandler` and `ConsoleHandler`.

**A handler on the root never sees a logger whose records stop before the root.** That means
Logback `additivity="false"` and JUL `useParentHandlers=false`. In the test,
`com.acme.private.Audit` delivered nothing. Adding the handler to the root **and** to every
logger that stops propagation fixes it, and each record still arrives exactly once: 188/188 on
Logback and 173/173 on JUL. A record's propagation ends at the first such logger or at the root,
so it reaches exactly one of those attachment points. Since propagation settings change on
reconfiguration, the set of attachment points has to be recomputed every time the handler is
re-added.

### 7. Every framework drops the handler on reconfiguration, without telling anyone

| Change | Handler afterwards |
|---|---|
| Logback: `reset` + Joran, which is what `scan="true"` does | removed |
| JUL: `LogManager.readConfiguration()` | removed |
| WildFly: root `level` changed, a handler added or removed through jboss-cli | **kept** |
| WildFly: `root-logger=ROOT:write-attribute(name=handlers, …)`, even with an unchanged list | removed |
| WildFly: `:reload` | removed; the agent's MBean survives (same JVM) |

Nothing fails and nothing is logged. The listener just stops receiving records. Since the ring
is no longer being fed, there's no sequence gap to report either. **`listen` can't tell "the
application went quiet" from "the handler was removed".** The agent must report
removal itself, so `logctl` can print something like `[handler removed by a logging
reconfiguration at 12:41:07; re-added 12:41:30]`.

### 8. Re-adding: what each framework offers

| Framework | Hook | Result |
|---|---|---|
| Logback | `LoggerContextListener.onReset`, `isResetResistant() == true` | Re-adds to the root after every reset. It runs **before** Joran applies the new file, though, while every logger still propagates to the root. So a logger the file declares `additivity="false"` is missed. |
| Logback | `ConfigurationEventListener`, any `*ENDED*` event | Runs after Joran finishes. Re-added correctly, both loggers, across three reconfigurations in a row. Present in 1.3.14, 1.4.14 and 1.5.x; **absent** from 1.2.13, 1.3.0 and 1.4.0, which the §11 matrix also covers. |
| JUL | `LogManager.addConfigurationListener` (public since JDK 9) | Runs after `readConfiguration`. Re-added correctly, both loggers. §4.3 says JUL has no reconfiguration hook ("none — poll"); that is out of date for every JDK LogAperture supports. |
| WildFly | JBoss LogManager's `addConfigurationListener` | **Never fired.** Not for a root level change, a handler-list replacement, a new logger with `use-parent-handlers=false`, or `:reload`. |

So on WildFly the only way back is a periodic check, like the verification sweep that already
re-applies level overrides (5 s in the dev environment, 30 s by default). Records logged in that
window are lost. On older Logback, a reset-resistant `onReset` plus the same periodic check
covers the gap.

### 9. On WildFly the handler is invisible to the management model

`/subsystem=logging/root-logger=ROOT:read-attribute(name=handlers)` lists only `CONSOLE` and
`FILE` while the handler is attached, and `standalone.xml` is never touched. This is consistent
with how LogAperture treats WildFly elsewhere: nothing it does is written to server
configuration. But an administrator looking at the server through jboss-cli can't see that log
content is being streamed. They can only find out from `logctl` and the audit log, so `logctl
status` and `doctor` should show an attached handler (question 7).

### 10. Found along the way: two gaps in shipped re-application

These are about level overrides, not the handler. They came out of checking how the agent's
existing hooks behave, against the `develop` build at b7d4a6c.

- **Logback: the agent's reconfiguration hook only works once.** `LogbackLoggingAdapter.onReset`
  registers a `LoggerContextListener` with `isResetResistant() == false`. Logback removes such
  listeners after the reset they fire on. Measured: after the first reconfiguration,
  `com.acme.public` stayed at DEBUG. After the second and third, it fell to INFO until the
  verification sweep restored it, 9–16 s later (30 s interval). Separately, because `onReset` runs before
  Joran, a logger the file gives a level (`com.acme.private`) is overwritten even the first time
  and waits for the sweep.
- **WildFly: the configuration listener doesn't fire for `/subsystem=logging` changes** on
  26.1.3, contrary to the comment on `WildFlyContainerIntegration.wireConfigurationListener`.
  Measured: after `logger=…Worker:add(level=INFO)`, the agent's DEBUG override read INFO, and
  read DEBUG again 7 s later, by the 5 s sweep.

In both cases the sweep is the safety net it was designed to be, so no override is lost for
good. But each reconfiguration opens a window of up to one sweep interval where it isn't
applied. Both deserve their own issues, outside this spike.

## Not established

- **Overhead on the application thread at flat out.** The flat-out throughput was too noisy to
  read: 144k calls/s with nobody listening and 172k with NOTIFY, which is backwards. The cost
  per record with the handler installed (question 4) needs the JMH harness from
  [`overhead-benchmarks.md`](../specs/overhead-benchmarks.md), not this one.
- **RING tuning.** Adaptive polling (backing off when a batch is empty), bigger batches and
  pipelined `drain` calls were not tried. They're the obvious next step if the CPU cost at
  10k–50k/s matters.
- **Transport on JDK 17 and 25, JUL and WildFly.** The transport comparison ran on JDK 21 with
  plain Logback only.
- **WildFly per-deployment logging.** A deployment with its own logging configuration or a
  logging profile gets its own log context, which a handler on the server's root never sees.
  Not tested, and WildFly versions other than 26.1.3 weren't tried.
- **The exact Logback version that added `ConfigurationEventListener`.** Only 1.2.13, 1.3.0,
  1.3.14, 1.4.0, 1.4.14 and 1.5.x were checked.
- **Records with stack traces.** No record carried a throwable. Rendering one on the
  application thread, as the byte cap above assumes, is a separate cost.

## Harness

Not committed, per `CLAUDE.md`. The transport harness has five pieces:

- `TapRing`: the ring and the batch encoding.
- `Tap` with `TapMBean`: the agent. `start(mode, arg)`, `stop()`, `drain(cursor, max)`.
- `App`: the rate-controlled logger.
- `Listen`: the client. `Listen <pid> <mode> <seconds> [records/s cap]`. It prints one result
  line with offered, received, lost, latency percentiles, CPU on both sides, JMX notifications
  lost and live heap.
- `run.sh`: starts the application with the agent, waits 5 s, runs `Listen` and reports the
  application's median calls/s while listening.

The question 2 harness reuses `TapRing` and adds `Tap2` (the agent, with one binding for
Logback and one for JUL), `AppQ2` (the two-logger application) and `Probe`. `Probe` attaches
to a pid and runs a list of steps: `start` and `start+` (with the re-add hook), `stop`,
`reconf`, `status`, `count:<seconds>` and `sh:<command>`, which ran jboss-cli inside the WildFly
container.
