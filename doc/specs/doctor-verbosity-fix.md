# `doctor`: the right fix for a chatty logger left on

Status: **Signed off 2026-11-07** (decisions D1 to D6 below, every recommendation taken). Release `1.1.0`.

Parent specs: [`doctor.md`](doctor.md) (the checks), [`reset-to-native.md`](reset-to-native.md) and
[`vendor-defaults.md`](vendor-defaults.md) (what `reset` goes back to). Tracking issue:
[#345](https://github.com/ddeuchert/logaperture/issues/345), found by the code review of the `doctor` demo
film (#342).

## Functional summary

After this feature, the user will be able to:

- Run `logctl doctor` and, for a chatty framework logger (`org.hibernate` and the like) left at `DEBUG`, be
  given a command that actually turns it down: `logctl reset logger <name>` when it undoes something that
  `logctl` set, and `logctl set logger <name> INFO sticky` when the application's own configuration, a vendor
  defaults file or the application's code set it.
- Read in the finding why `reset` would not help, so the suggestion is not a surprise.

Guide pages this changes: `reference/doctor-checks.md` (the `logger.verbosity-left-on` fix), and the one
sentence under the `doctor` film in `how-to/find-disk-usage.md` and `now-showing.md` that says the film's
`DEBUG` was set at runtime.

Not part of this feature: the root logger finding, which offers no command today and still does not; any
other check; a change to what `reset` does.

## The problem

`DoctorService.checkVerbosityLeftOn` flags a known-chatty framework logger at `DEBUG`, `TRACE` or `ALL` and
always suggests `logctl reset logger <name>`. `reset` puts a logger back to its baseline. When a
`logctl set logger` override raised it, that is the fix. When the server's configuration sets the level, the
baseline *is* that level, and the same goes for a vendor defaults file, so the suggested command reports the
logger back at `DEBUG (baseline)` and changes nothing.

`DoctorService` is deliberately stateless and knows nothing of overrides, so it cannot tell the cases apart
today.

## Design

**D1. Doctor is told what a reset would do.** *(Revised twice after review, 2026-11-07. The first version asked where
the level comes from, which misclassifies an override over a chatty configured level. The second asked what a
reset lands on, using the effective baseline, and Copilot's review showed two more cases it gets wrong: a reset
does nothing at all when no override or vendor reset-to-native state exists, and a plain reset clears
reset-to-native first, so it lands on the vendor file's level, not the native one.)* A small function the container
supplies, `Function<String, ResetEffect>`, answered from what `logctl reset logger` really does
(`LevelControlService.resetLogger`/`resetOne`):

- the **landing level** is the vendor defaults file's level when the file names the logger (a plain reset clears a
  reset-to-native state before it applies the baseline), else the application's configured baseline;
- it **changes anything** only when an override is present or a vendor reset-to-native state exists to undo.

| Value | When |
|---|---|
| `FIXES` | it changes something, and the landing level is not chatty (`DEBUG`, `TRACE`, `ALL`) |
| `NOTHING_TO_RESET` | the landing level is not chatty, but there is nothing to undo: the application set the level itself, in code |
| `LANDS_ON_VENDOR_LEVEL` | the landing level is a chatty one the vendor defaults file sets |
| `LANDS_ON_CONFIGURED_LEVEL` | the landing level is a chatty one the application's configuration sets, or the baseline was never captured |

`ResetEffect.resolver(overrides, baselines)` builds it from the `OverrideRegistry` and `BaselineRegistry` a context
already holds, so `DoctorService` stays free of any state of its own and stays read-only. There are two
composition roots, `NoneContainer` and `JBossLogManagerContainer` (the WildFly path, with its own copy of the
install wiring), and both pass it; the first version of this change wired only the first, and the real check on
the dev WildFly is what found the second. A `DoctorService` built without one takes every level to land on the
configuration's (a `set logger ... INFO sticky` suggestion, which fixes an override too but with a detail line that
is not so); a new container must pass a resolver.

**D2. What the finding says.**

| Value | Summary (unchanged) | Detail | Suggested fix |
|---|---|---|---|
| `FIXES` | `<name> is explicitly set to DEBUG — a known-chatty framework logger.` | none, as today | `logctl reset logger <name>` |
| `NOTHING_TO_RESET` | the same | `no logctl change is in force on it, so reset would do nothing: the application set this level itself` | `logctl set logger <name> INFO sticky` |
| `LANDS_ON_CONFIGURED_LEVEL` | the same | `reset would go back to the level the application's configuration sets, which is also chatty` | `logctl set logger <name> INFO sticky` |
| `LANDS_ON_VENDOR_LEVEL` | the same | `reset would go back to the level the vendor defaults file sets, which is also chatty` | `logctl set logger <name> INFO sticky` |

**D3. Why `INFO sticky`.** `INFO` is what a chatty framework logger is normally left at; `sticky` keeps the
change across restarts, so it holds until the configuration is fixed, which is the one place that makes it
permanent (the guide already says so). A shorter-lived change would put `DEBUG` back at the next restart with
no sign that doctor had been right. Like any `set logger` it changes that one logger: a child with a level of its
own keeps it (the guide says so). The suggestion carries no `--reason`, to stay short enough to copy.

**D4. Nothing else changes.** The finding's id (`logger.verbosity-left-on`), severity (`WARNING`) and
summary text stay as they are, so `--json` and the drift-tested check list are unaffected; `detail` and
`suggestedFix` are fields it already has. The root logger finding still offers no command.

**D5. The demo film (#342) is unchanged.** Its `DEBUG` is an override over an `INFO` baseline, the `FIXES` row,
which is exactly what it recorded. Only the sentence below it in the guide changes.

**D6. Tests.** `DoctorServiceTest`: each effect gets its detail and suggested fix; the function is asked for
the logger's name; no function means `LANDS_ON_CONFIGURED_LEVEL`. `ResetEffectTest`: the cases above. `DoctorFixExecutionTest`:
each case **runs the suggested command** against the real `LevelControlService` and requires the finding to be
gone (an override over `INFO`, a configured `DEBUG`, an override over it, a vendor `DEBUG`, a vendor `DEBUG` reset to
native and then overridden, a level the application raised in code, with and without a vendor `INFO`). A check against the dev WildFly with `org.hibernate`
set at `DEBUG` in the server's own configuration, and one raised in code after it was first seen (the case the bug is about), then the suggested
`set logger ... INFO sticky`, and `doctor` again.

## Decisions to sign off

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | A supplied function answering what a reset lands on (revised), not state in `DoctorService` | Yes |
| D2 | `reset` for an override; `set logger <name> INFO sticky` plus a detail line for configuration and vendor | Yes |
| D3 | `INFO` and `sticky` | Yes |
| D4 | Id, severity, summary and the root finding unchanged | Yes |
| D5 | The film stays as recorded | Yes |
| D6 | Unit tests plus the real-configuration check on the dev WildFly | Yes |
