# `set logger`: the default handlers follow the change

Status: **draft, under review.** Tracked as [#210](https://github.com/ddeuchert/logaperture/issues/210).
Parent: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.18. Related:
[`handler-floor-control.md`](handler-floor-control.md) ("Warning on level commands", "AUTO handler
level", "Default handler group"), [`set-logger-force.md`](set-logger-force.md) (the `forcedBy` tagging
precedent), [`level-control.md`](level-control.md).

## Functional summary

After this feature, the user will be able to:

- Run `logctl set logger com.mycompany DEBUG` and see the DEBUG lines on the console as well as in
  the log file, with no second command. The console goes back to its own level when the change
  expires or is reset.
- See, in what `set logger` prints, which handler was let through and for how long.
- Run `logctl set logger com.mycompany DEBUG --keep-handlers` to change only the logger, as before.

Guide pages that change: `get-started/quick-start.md` (step 4), `how-to/raise-a-level.md`,
`reference/logctl.md` (regenerated), `reference/json-output.md`, `reference/audit-log.md`, and the
`why-no-trace` demo recording.

## The problem

A logger's level decides what gets logged; each handler has a level of its own that decides what it
writes. WildFly's `CONSOLE` sits at `INFO`. So `set logger com.mycompany DEBUG` does what it says, but
the person watching the console sees nothing, and gets:

```
WARN: handler CONSOLE is at INFO and will drop DEBUG records from this logger.
      To see them: logctl set handler CONSOLE DEBUG
```

The quick start spends a whole step on this, and in a team demo it was the hardest thing to explain.
Almost everyone who raises a logger wants to see the output, and `AUTO` (issue #20) already does the
right thing for the handler, if you know to ask for it. This feature asks for it on the user's behalf,
for the handlers `DEFAULT_HANDLERS` names (by default the one console handler).

## Scope

In: `set logger` (exact name, pattern, guided, `--force`); the `--keep-handlers` opt-out; how a
following handler's lifetime is tied to the logger changes; output, `--json`, JMX, audit and state
file.

Out:

- **Handlers outside `DEFAULT_HANDLERS`.** They keep today's WARN, with the command for each. A
  vendor who wants the file handler to follow too widens `defaultHandlers` in the vendor defaults
  file, as for every other `DEFAULT_HANDLERS` use (#188).
- **Other commands that change logger levels**: `alter`, recipes, the vendor defaults file, and
  `import`/resume. Only a `set logger` the operator types (or picks in guided mode) brings handlers
  along.
- **Logback / the plain-JVM container**: its handlers (appenders) have no level of their own, so
  there is nothing to follow (handler-floor-control.md "Logback / none").
- **Per-logger handler routing.** A following handler is in `AUTO`, which tracks the lowest active
  logger change in the whole context, not just the one it follows. That is AUTO's existing behavior
  and is fine for output (a handler only sees records its loggers emit).

## Behavior

### Which handlers follow (H2)

After `set logger <target> <level>` applies, a handler follows when **all** of these hold:

1. It's a member of `DEFAULT_HANDLERS` in that context.
2. It's on the path of a logger that was just made more verbose (the same walk the WARN uses today:
   the logger, its parents up to the root, stopping where `useParentHandlers` is off).
3. Its level is stricter than `<level>`, so it would drop the records.
4. **No override already covers it for long enough**: an operator `set handler`, `AUTO`, or group
   override that lets the records through for at least as long as the logger change means nothing
   changes; one that ends sooner is carried on, and a fixed one that blocks is left alone (H5).

Condition 3 is read after `AUTO` has recomputed, so an `AUTO` handler that just tracked down to the
new level is not blocking.

Every other blocking handler keeps today's WARN. When the WARN would collapse to `ALL_HANDLERS`
(a handler that can't be named yet, #13), the default members are still checked one by one, so a
named console can follow while the WARN still covers the rest.

### What following means (H3, H4)

The handler gets an ordinary `AUTO` override, **tagged with the logger change(s) it follows**
(`follows: [com.mycompany]`), in the manner of `forcedBy` (set-logger-force.md F8):

- **It ends with them.** When the last change it follows ends (expires, is reset, or is replaced
  with one that no longer needs it), the `AUTO` override is removed and the handler goes back to its
  own level, exactly as `reset handler` would. That's the "expire at the same time" requirement, and
  it holds whatever tier the logger change had.
- **More than one logger.** `set logger com.a DEBUG` then `set logger com.b TRACE`: `CONSOLE`
  follows both (`follows: [com.a, com.b]`) and ends when both have ended. Its level is `AUTO`'s:
  the lowest active change, `TRACE`.
- **Its tier** is the longest among the changes it follows (`sticky` > `session` > `for`), so a
  sticky logger change's handler survives a restart with it. It has no expiry of its own; the
  logger changes decide.
- **A change made since wins.** `set handler CONSOLE INFO`, `set handler CONSOLE AUTO sticky` or
  `reset handler CONSOLE` replaces the tagged override with the operator's choice; nothing ends it
  later on the logger's behalf.

### When the handler already has an override (H5)

The rule: **if the handler already shows the records and will keep showing them for at least as
long as the logger change lasts, nothing changes.** Only a handler that blocks now, or would start
blocking before the logger change ends, is touched.

- **Already lets the records through, natively or from the vendor defaults file** (`FILE` at
  `ALL`): nothing changes; it isn't blocking and never will be on its own.
- **An operator override (`AUTO`, or a fixed level that lets the records through) lasting at least
  as long as the logger change**: nothing changes. "As long" compares lifetimes: `sticky` outlasts
  `session`, which outlasts any `for`; two `for`s compare their expiry times.
- **The same, but ending sooner** (`set handler CONSOLE AUTO for 30m`, then `set logger com.mycompany
  DEBUG for 4h`): the operator's override stays exactly as it is, and is tagged so that **when it
  ends, the handler carries on following** the logger change (a tagged `AUTO`, as above) instead of
  going back to `INFO` half-way through. The output says so:
  `handler CONSOLE: AUTO until 14:02, then follows com.mycompany`.
- **A fixed level set by the operator that blocks** (`set handler CONSOLE WARN`): left alone, today's
  WARN is printed. They chose it.
- **A group override** (`ALL_HANDLERS` / `DEFAULT_HANDLERS`) covering it: treated as the operator
  override it is, by the same three rules.
- **A level from the vendor defaults file that blocks** (not an override): followed. The vendor's
  level is a default, like the native one.

### The opt-out (H1, H6)

`--keep-handlers` on `set logger` changes only the logger: today's WARN, nothing follows. Guided
`set logger` asks nothing new; the confirmation shows what will follow (H8), and answering No to
"Apply?" is the way out, as for everything else it shows.

### Permission (H7)

Following needs `handler.lower` (and `persist` for a sticky or `for` lifetime), like `set handler
AUTO`. If either is denied, `set logger` still succeeds, nothing follows, and the WARN is printed as
today, with one extra line saying why (`handler.lower not granted`).

### Output (H10)

```
$ logctl set logger com.mycompany DEBUG
com.mycompany → DEBUG   (FOR, reverts 14:32:07 local — in 4h)
handler CONSOLE → AUTO, currently DEBUG   (follows com.mycompany)
```

With a handler that can't follow, the WARN follows the lines above, for that handler only.

Guided confirmation (H8):

```
This is the command:
  logctl set logger com.mycompany DEBUG
CONSOLE will follow it (AUTO) until the change ends.
Apply? [Y/n]
```

`--keep-handlers` is added to the printed command when the user passed it.

## Surfaces

- **JMX.** An additive `setLogger` overload taking `keepHandlers`; the existing overloads mean
  "follow" against a new agent. `SetLevelResultData` gains `following`: each handler that now follows,
  with its level before and after. `HandlerLevelOverrideData` gains `follows` (logger names). Additive
  within the major (§11.1). An older agent ignores none of this because it never receives it: a new
  `logctl` calls the old overloads when `--keep-handlers` isn't given, so against an older agent
  `set logger` behaves as it does today.
- **`--json`.** `following` on `set logger`'s output; `follows` on a handler override in `status`
  and `list handlers`.
- **`status` / `list handlers`** show a following handler as `AUTO` with `follows com.mycompany` in
  the reason column when it has no reason of its own (the `forced by` precedent, F10); `REVERTS`
  reads `with com.mycompany`.
- **Audit.** One `MUTATION` record for the handler, `origin=follows com.mycompany`; one `REVERSION`
  when it ends with the logger change.
- **State file.** A `follows:` line on a handler override, written only when set; schema 12 → 13. A
  version-12 file loads with no tag on anything.
- **`export vendor-defaults`** leaves a following handler out (H11): it exists only for the logger
  change, and writing it as `level: AUTO` would make the console follow forever on every server the
  file goes to.
- **Multi-context (WildFly).** Each context decides for its own `DEFAULT_HANDLERS`; resume and
  re-broadcast restore the tag with the override.

## Decisions (open)

| # | Question | Recommended |
|---|---|---|
| H1 | On by default, or opt-in? | **On by default**, opt out with `--keep-handlers`. The point is that the first command works. Risk: on a server whose console feeds a log pipeline, DEBUG now reaches it too; bounded by the 4h default lifetime, and a logger the native config already sets to DEBUG will show on the console too while it follows. |
| H2 | Which handlers follow? | **`DEFAULT_HANDLERS` members on the raised logger's path that would drop the level**, and only those. Others keep the WARN. |
| H3 | What the handler gets | **An `AUTO` override tagged with the logger change(s) it follows.** Alternative: a fixed level equal to the logger's, with the same tier; simpler, but two raised loggers at different levels fight over it, and it can't end "when the change ends" without the same tag. |
| H4 | Lifetime | **Ends when the last change it follows ends**; tier = the longest of them, no expiry of its own. |
| H5 | Handler already overridden or already showing the records | **Nothing changes if it shows them for at least as long as the logger change** (your requirement, 2026-10-16). One that shows them but ends sooner is left as it is and carries on following when it ends. A fixed override that blocks keeps the WARN. A vendor-file level that blocks is followed. |
| H6 | Name of the opt-out | **`--keep-handlers`**. Alternatives: `--no-follow`, `--logger-only`. |
| H7 | Permission denied | **`set logger` still succeeds**; WARN as today plus why. |
| H8 | Guided `set logger` | **No new question**; the confirmation says what will follow. |
| H9 | Commands other than `set logger` | **Out**: `alter`, recipes, vendor file and import don't bring handlers along. |
| H10 | Output | One `handler … → AUTO, currently …   (follows …)` line per handler, in place of its WARN. |
| H11 | `export vendor-defaults` | **Leave following handlers out.** |
| H12 | Release | **Decide on the finished PR**: develop for 1.0.0-beta.2, as a stated exception to the beta.1 feature freeze, or hold. After 1.0 it's a behavior change to a core command, so it would ship in **1.1.0**, not a 1.0.x patch (§11.1). |

## Testing

- Core: a default member on the path follows; a non-default blocker keeps the WARN; a handler with a
  fixed blocking override keeps the WARN; an AUTO, permissive fixed or group override lasting at
  least as long changes nothing; one ending sooner carries on following when it ends (`for` vs
  `for`, `for` vs `session`/`sticky`); vendor-file blocking level followed; two loggers, one ends,
  the other keeps it; both end → handler back to its own level; expiry, reset and replacement end
  it; `set handler` after supersedes the tag; tier = longest; sticky resume keeps `follows`;
  `--keep-handlers`; `handler.lower` denied; `--force` descendants count as changes it follows;
  multi-context.
- State file: `follows` round trip; a version-12 file loads.
- CLI: the follow line, `--keep-handlers`, the WARN for the rest, `--json`, guided confirmation;
  `status` / `list handlers` rendering; old-agent fallback.
- WildFly IT: `set logger` at DEBUG, the DEBUG line reaches `CONSOLE`; reset, `CONSOLE` back to
  `INFO`.
