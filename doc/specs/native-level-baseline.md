# The baseline carries the framework's own level (`FATAL` survives a reset)

Status: **Signed off 2026-11-07** (decisions N1 to N6 below, every recommendation taken). Release `1.1.0`.

Parent specs: [`level-control.md`](level-control.md) (baseline capture, reset),
[`log4j2-adapter.md`](log4j2-adapter.md) (A2, "A limit: `FATAL`"). Tracking issue:
[#312](https://github.com/ddeuchert/logaperture/issues/312), under #302.

## Functional summary

After this feature, the user will be able to:

- Run `logctl reset logger <name>` (or `reset all`, or let an override expire) on a Log4j 2
  application and get back exactly the level the application declared, including `FATAL` and a custom
  level, instead of the nearest level `logctl` can name (`ERROR`).

Guide pages this changes: none. The Log4j 2 limit is documented only in the spec today; the guide pages
for Log4j 2 (written with #302) say nothing about it, and still need no sentence once it is fixed.

Not part of this feature: showing `FATAL` in `logctl list loggers` or `set logger x fatal` (the level
names `logctl` offers do not change); handler levels (N5).

## The problem

A logger the application declares at a level the API cannot name (Log4j 2's `FATAL`, or a custom level)
reads back through `LoggingAdapter.configuredLevel` as the nearest API level (`FATAL` as `ERROR`).
`core` captures that as the baseline, and a reset writes it back with `applyLevel(name, ERROR)`, so a
declared `fatal` becomes `error` and the application emits more than it declared.

The adapter cannot fix it alone: a reset and a deliberate `logctl set logger x error` reach `applyLevel`
as the same call. A first attempt (remember the native level the adapter read, write it back when the
API level matches) turned a deliberate `error` into `FATAL`, dropping the events the user asked for,
and was reverted. The caller has to say which of the two it means.

## Design

**N1. The adapter names the native level with an opaque token.** Two default methods on
`LoggingAdapter`, additive under the versioning policy (§11.1):

```java
/** An opaque handle on this logger's own configured level, or null when the API level says it all. */
default Object nativeLevelToken(String loggerName) { return null; }

/** Puts back a baseline: the level (which is what core captured) and, if there was one, the token. */
default void restoreLevel(String loggerName, Level level, Object token) { applyLevel(loggerName, level); }
```

Logback and JUL adapters are unchanged. The Log4j 2 adapter returns the `LoggerConfig`'s explicit
level object only when it does **not** round-trip (`FATAL`, a custom level), else `null`, so the token
exists for the rare logger only.

**N2. `restoreLevel` validates the token.** The Log4j 2 adapter applies the token's level only if
`LevelMapper.toApi(token)` equals the `level` passed. A token that no longer matches (the level was
read at a different moment from the token, or the baseline is rebased) falls back to `applyLevel`.
A wrong token therefore degrades to today's behaviour, never to a different level.

**N3. Core captures the token with the baseline and uses it only to put a baseline back.**
`BaselineRegistry` stores the token beside the captured `Optional<Level>` (one read of each, level
first); `rebaseNative` takes the token read with the level. A new `LevelControlService.restoreBaseline`
calls `adapter.restoreLevel(name, level, token)` at every site that writes the baseline back: reset
(`resetOne`), expiry, undo, the sweep's reapply of a baseline, `restoreNativeLevelsForStop`. It uses
the token only when the level written is the **native** value; when a vendor default is in effect
(`baselines.get` returns the vendor level) it calls `applyLevel`, so a vendor `error` is never
turned into `FATAL`. Every other `applyLevel` (an override, a vendor default being applied) is
untouched, which is what keeps a deliberate `set logger x error` an `ERROR`.

**N4. Tokens are not persisted or exported.** A baseline is recaptured from the framework at start
(level-control.md), so the token is too; nothing in the state file, `--json` or the MXBean changes.

## Decisions to sign off

| # | Decision | Recommendation |
|---|----------|----------------|
| N1 | Token + `restoreLevel` as default SPI methods (the issue's proposal), not a richer `configuredLevel` return type | Yes: additive, no change to the two adapters that do not need it |
| N2 | Token validated against the level in the adapter | Yes |
| N3 | Token used only on baseline write-backs and never over a vendor level | Yes |
| N5 | Handler levels (a handler declared `FATAL` reads as `ERROR`, log4j2-adapter.md) | Out of scope: a handler reset has its own baseline registry and its own restore path; file it separately if wanted |
| N6 | Tests: core unit tests with a fake adapter carrying tokens (reset, expiry, vendor-level case, deliberate `error` stays `ERROR`); Log4j 2 adapter test on 2.20.0, 2.24.3, 2.25.5 with a `fatal` and a custom-level logger; `BundledLog4j2EndToEndIT` gets a declared-`fatal` logger reset | Yes. No gate benchmark: nothing runs per call |

(N4 is a statement, not a decision.)

## What changes in other documents

`log4j2-adapter.md` "A limit: `FATAL`" and the Not-established line about handlers are updated to
point here; `level-control.md` gets one sentence on the baseline token. `CHANGELOG.md` gets an entry.
