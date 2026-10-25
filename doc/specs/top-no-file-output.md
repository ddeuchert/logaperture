# `logctl top` — explain when there is no file output to measure

Status: **Signed off 2026-10-24** (decisions TN1 to TN5 below, all agreed as recommended). Implemented on
`feature/264-top-no-file-output` (issue [#264](https://github.com/ddeuchert/logaperture/issues/264)). Milestone `1.1.0`.

Parent spec: [`doc/specs/top.md`](top.md) (the feature this amends), [`doc/logaperture-spec.md`](../logaperture-spec.md)
§16.1 (`top`), §11.1 (compatibility: the `--json` surface is additive-only within a major).
Found during: [`quarkus-dev-environment.md`](quarkus-dev-environment.md), where `logctl top` printed
"No byte-volume measurements available yet." for as long as the app ran, because Quarkus logs to the
console by default and `top` only counts file-backed handlers.

## Functional summary

After this feature, the user will be able to:

- Run `logctl top` against an application that logs only to the console and be **told why there is
  nothing to show**: that `top` measures log files, and that none of the application's handlers write to
  one. Today it says "No byte-volume measurements available yet", which reads as "wait a bit".
- See **which handlers `logctl` did find**, and be pointed at `logctl list handlers`, so the next step
  (enable a file handler for the application) is obvious.
- Still get the existing "no measurements yet" message when a file-backed handler *does* exist and simply
  has not written anything.
- Tell the two cases apart in `logctl top --json`, for scripts and monitoring checks.

Guide pages this changes: `how-to/find-disk-usage.md` (a new section with the real output), `troubleshooting.md`
and `get-started/install-quarkus.md` (a line saying `top` needs a file handler there). The generated
`reference/logctl.md` is unaffected: the help text does not change.

## Behavior

`top` today (`Commands.top`) prints one message when the report has no rows or no measurement start. That
single message covers two different situations:

| Situation | Today | After |
|---|---|---|
| No persistent (file-backed) handler exists | "No byte-volume measurements available yet." | A message that says there is no file-backed output, lists the handlers found, and points at `logctl list handlers` |
| A persistent handler exists, nothing written through it yet | the same message | unchanged |

Sketch of the new text (wording is TN2):

```
$ logctl top
No log files to measure: `top` counts bytes written to files, and this application's handlers
write to none (found: console).
See `logctl list handlers`; send output to a file to measure it.
```

Exit status stays `0`: this is a report, not an error (as in `top.md`'s Functional summary).

## Decisions

All five were agreed on 2026-10-24 as recommended.

| Id | Question | Recommendation |
|---|---|---|
| TN1 | Where is "no file-backed handler" decided? | In `logctl`, from the handler listing it can already fetch (`list handlers` carries a persistent flag per handler). No agent or MXBean change, so no version-skew concern between agent and `logctl`, and the older-agent case degrades to today's message. A new field on `TopReport` is the alternative, and is additive, but buys nothing here. |
| TN2 | How specific is the advice? | Generic in the tool: say what `top` measures, list the handlers found, point at `logctl list handlers`. Framework-specific instructions (for Quarkus, `quarkus.log.file.enabled`) go in the guide's install page, not in `logctl`, so the CLI carries no per-framework text to keep current. |
| TN3 | `--json`? | Add `"persistentHandlers": N` (a count). It is additive within the 1.x compatibility promise. Existing fields and the empty-array shape are unchanged, so a script can distinguish "no files" (`0`) from "nothing written yet" (`> 0`). |
| TN4 | Does the handler listing failing change the result? | No. If the extra lookup fails, or the framework lists no handlers at all (Logback does not, and that says nothing about files), `top` prints today's message and `--json` omits `persistentHandlers`. The new text is an improvement on a report that already worked, never a new way for `top` to fail. |
| TN5 | Slot? | Milestone `1.1.0`, branch `feature/<issue>-top-no-file-output` from `develop`, spec committed with the code. |

## Out of scope

- Making `top` measure the console handler. `top.md` defines the feature as bytes to *disk*; a console
  stream has no growth to size.
- Turning on a file handler for the user, or suggesting a command that does (no such command exists).
- A `doctor` check for the same condition. Possible later; `doctor` does not currently mention `top`.

## Exit criterion

On a Quarkus app with the default console-only logging: `logctl top` explains that no file-backed output
exists and names `console`. With `quarkusctl up --file-log` and some traffic, `top` lists loggers as
before. `top --json` reports `persistentHandlers` `0` in the first case and `1` in the second. Unit tests
for both messages and the lookup-failed fallback in `CommandsTest`; the `top` case in `QuarkusIT` (which
already enables the file handler) is unchanged.
