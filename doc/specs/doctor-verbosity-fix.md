# `doctor`: the right fix for a chatty logger left on

Status: **Signed off 2026-11-07** (decisions D1 to D6 below, every recommendation taken). Release `1.1.0`.

Parent specs: [`doctor.md`](doctor.md) (the checks), [`reset-to-native.md`](reset-to-native.md) and
[`vendor-defaults.md`](vendor-defaults.md) (what `reset` goes back to). Tracking issue:
[#345](https://github.com/ddeuchert/logaperture/issues/345), found by the code review of the `doctor` demo
film (#342).

## Functional summary

After this feature, the user will be able to:

- Run `logctl doctor` and, for a chatty framework logger (`org.hibernate` and the like) left at `DEBUG`, be
  given a command that actually turns it down: `logctl reset logger <name>` when someone set it with
  `logctl`, and `logctl set logger <name> INFO sticky` when the application's own configuration or a vendor
  defaults file set it.
- Read in the finding where the level comes from, so it is clear why `reset` would not help.

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

**D1. Doctor is told where a level comes from.** A small function the container supplies,
`Function<String, LevelOrigin>`, with `LevelOrigin` one of `OVERRIDE` (an active `logctl` override),
`VENDOR` (the vendor defaults file names the logger and it is not reset to native) and `CONFIGURATION`
(neither). `LevelOrigin.resolver(overrides, baselines)` builds it from the `OverrideRegistry` and the
`BaselineRegistry` a context already holds, so `DoctorService` stays free of any state of its own and stays
read-only. There are two composition roots, `NoneContainer` and `JBossLogManagerContainer` (the WildFly path, with
its own copy of the install wiring), and both pass it; the first version of this change wired only the first, and
the real check below on the dev WildFly is what found the second. A `DoctorService` built
without one treats every level as `CONFIGURATION`.

**D2. What the finding says.**

| Origin | Summary (unchanged) | Detail | Suggested fix |
|---|---|---|---|
| `OVERRIDE` | `<name> is explicitly set to DEBUG — a known-chatty framework logger.` | none, as today | `logctl reset logger <name>` |
| `CONFIGURATION` | the same | `the level comes from the application's configuration, so reset would not change it` | `logctl set logger <name> INFO sticky` |
| `VENDOR` | the same | `the level comes from the vendor defaults file, so reset would not change it` | `logctl set logger <name> INFO sticky` |

**D3. Why `INFO sticky`.** `INFO` is what a chatty framework logger is normally left at; `sticky` keeps the
change across restarts, so it holds until the configuration is fixed, which is the one place that makes it
permanent (the guide already says so). A shorter-lived change would put `DEBUG` back at the next restart with
no sign that doctor had been right.

**D4. Nothing else changes.** The finding's id (`logger.verbosity-left-on`), severity (`WARNING`) and
summary text stay as they are, so `--json` and the drift-tested check list are unaffected; `detail` and
`suggestedFix` are fields it already has. The root logger finding still offers no command.

**D5. The demo film (#342) is unchanged.** Its `DEBUG` is an override, the `OVERRIDE` row, which is
exactly what it recorded. Only the sentence below it in the guide changes.

**D6. Tests.** `DoctorServiceTest`: each origin gets its detail and suggested fix; the function is asked for
the logger's name; no function means `CONFIGURATION`. A check against the dev WildFly with `org.hibernate`
set at `DEBUG` in the server's own configuration (the case the bug is about), then the suggested
`set logger ... INFO sticky`, and `doctor` again.

## Decisions to sign off

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | A supplied origin function, not state in `DoctorService` | Yes |
| D2 | `reset` for an override; `set logger <name> INFO sticky` plus a detail line for configuration and vendor | Yes |
| D3 | `INFO` and `sticky` | Yes |
| D4 | Id, severity, summary and the root finding unchanged | Yes |
| D5 | The film stays as recorded | Yes |
| D6 | Unit tests plus the real-configuration check on the dev WildFly | Yes |
