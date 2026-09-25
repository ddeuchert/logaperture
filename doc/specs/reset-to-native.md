# Reset to the native default: `--to-native` (issue #94)

Status: **signed off 2026-09-25** (T1–T7). Rules are out of scope: parked to issue #96.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §6.6 "Precedence: configuration
layers" — the canonical terms (native configuration, vendor defaults, baseline, override,
effective level, native default) and personas (operator, vendor) used here.
Builds on: [`vendor-config-epic.md`](vendor-config-epic.md),
[`vendor-defaults.md`](vendor-defaults.md) (slice 1, merged as PR #93, unreleased),
[`reset-command-surface.md`](reset-command-surface.md),
[`handler-floor-control.md`](handler-floor-control.md).
Feeds: [`vendor-defaults-export.md`](vendor-defaults-export.md) (#62), see "Effect on the export".

## Functional summary

After this feature, the user will be able to:

- Rely on `logctl reset` always returning a logger, handler or the default-handler list to its
  baseline: the vendor defaults where they name it, the native configuration otherwise.
- Return it to the native configuration instead, ignoring the vendor defaults, with
  `logctl reset … --to-native`, and undo that with a plain `logctl reset`.
- As a vendor, take an entry out of the next vendor defaults file by resetting it with
  `--to-native` and exporting.

## Why

A vendor building the next vendor defaults file has three ways to treat an entry the file already
has: keep it, change it (`set … sticky`), or pin it to today's native value (`set … <native
level> sticky`). The last two write a value into the file, which then overrides the native
configuration for good. What was missing is "I'm done with this entry: whatever the native
configuration says from now on is fine" — the entry leaves the file. `--to-native` is that
option (§6.6 "Reset and `--to-native`", the vendor's table).

## Command surface (T1)

```
logctl reset logger <target>  [--include-sticky] [--to-native]
logctl reset loggers          [--include-sticky] [--to-native]
logctl reset handler <name>   [--include-sticky] [--to-native]
logctl reset handlers         [--include-sticky] [--to-native]
logctl reset default-handler                     [--to-native]
```

Rules: out of scope, see "Rules — out of scope".

## Behaviour

| Target | `reset` | `reset --to-native` |
|---|---|---|
| Logger | baseline: vendor level, or native if the vendor defaults don't name it | native level |
| Handler | baseline: vendor level or vendor `AUTO` tracking, or native | native level |
| Default handlers | the vendor defaults' `defaultHandlers`, or the automatic pick | the automatic pick |

- **Lands on the native default** (T2): the vendor layer is ignored for that target.
- **Follows the native configuration** (T3): the reset puts the target back on the native value
  captured at startup, and from then on LogAperture leaves it alone — the verification sweep and
  framework-reconfiguration re-apply skip it — so a later change to the native configuration (a
  WildFly management change, a Logback reload) simply takes effect.
- **Until restart** (T4): never written to the state file. On the next start the vendor defaults
  apply again.
- **A plain reset undoes it** (T5): it clears the reset-to-native state along with any override,
  so the target lands on its baseline.
- **Sticky overrides** (T6): `--to-native` removes a sticky override only with
  `--include-sticky`, like every reset; the flags are independent. Without it, a single target
  refuses and the plural forms skip and report, exactly as today.
- **Targets the vendor defaults don't name** (T7): `--to-native` is a plain reset. No error, so
  `reset loggers --to-native` works without knowing what the file covers.
- **`set` on a target reset to native** layers an override on top as usual. A plain reset then
  clears both (baseline); `reset --to-native` clears the override and keeps it at native.
- Pattern targets (`reset logger 'org.perfmon4j.*' --to-native`) expand as a plain pattern reset
  does, and apply the flag to each match.

## Surfaces

- `list loggers` / `list handlers`: the `VENDOR` column shows `DEBUG (reset to native)` for a
  target reset to native; the default `list loggers` view keeps listing it.
- `DEFAULT_HANDLERS`' catalog row shows `(auto: …)` while the vendor list is reset to native.
- `--json`: loggers and handlers gain `resetToNative` (boolean).
- Reset output says where it landed: `org.perfmon4j → INFO (native default, until restart)`;
  a plain reset of it: `org.perfmon4j → DEBUG (vendor default)`.
- `status`: the vendor defaults line appends `, 2 reset to native` when any are.
- Audit: a REVERSION with reason `reset to native default until restart`; a plain reset that
  clears it, reason `vendor default restored`.
- Capabilities: the same as the reset it's on.
- `reset default-handler --to-native --json` returns `defaultHandlerMembers` (the explicit
  membership, always `[]` after a reset) plus `membersInEffect` and `toNative`; a plain `reset
  default-handler --json` is unchanged.

## Effect on the export (#62)

A target reset to native is **left out** of the export, with no warning (the export is a vendor
task in a sandbox). This is the one exception to #62's X2 ("session and `for` changes never reach
the file"). #62's spec is updated to say so.

## Rules — out of scope (issue #96)

Rules are parked to #96, a broader redesign: rules become changeable (`set rule`), and a vendor
rule gets the same reset / `--to-native` shape as a logger. Until then, slice 1's rule behaviour is
unchanged: `reset rule vendor:<id> --include-vendor-defaults` switches a vendor rule off until
restart, and `--to-native` is not accepted on `reset rule` / `reset rules`. `reset logger X
--to-native` resets the logger's level only; the rules attached to `X` are reset exactly as a
plain `reset logger X` does today.

## Module scope (loggers, handlers, default handlers)

- `logaperture-core`: `BaselineRegistry`, `HandlerBaselineRegistry` and
  `DefaultHandlerGroupRegistry` gain a per-target "reset to native" set (the effective baseline
  becomes native for those targets); `LevelControlService` and `HandlerLevelControlService` reset
  paths; `AggregateLevelControl` broadcast.
- `logaperture-api`: `LoggerInfo`/`HandlerInfo` `resetToNative`.
- `logaperture-control-jmx`: additive overloads `resetLogger`, `resetAllLoggers`,
  `resetHandler`, `resetAllHandlers` taking `toNative`; `resetDefaultHandler(boolean toNative)`;
  data-class fields.
- `logaperture-cli`: `--to-native` on each reset; `VENDOR` cells; JSON; `status`; help.
- Specs: `vendor-defaults.md`, `reset-command-surface.md`, `cli-transport.md` reference,
  `vendor-defaults-export.md`.

## Testing

**Unit (core):** each row of the behaviour table with and without the flag; plain reset after
`--to-native` restores the vendor default; a native change (framework reconfiguration) followed
while reset to native; sweep re-asserting native; `set` then each reset; `--include-sticky`
independence (refuse and skip-and-report); unnamed targets; pattern targets; audit records;
fresh services (restart) bring the vendor layer back.

**CLI:** the flag on each reset form; cells, JSON, `status`, reset messages.

**Integration:** `WildFlyVendorDefaultsIT`: a vendor-defaulted logger and handler reset with
`--to-native` land on WildFly's configured levels, follow a `/subsystem=logging` change, and
return to the vendor level on a plain reset. `none` container: native → vendor round trip.

## Member decisions

- **T1 — The flag is `--to-native`**, on `reset logger`, `loggers`, `handler`, `handlers` and
  `default-handler`. **Agreed.**
- **T2 — It lands on the native default**, ignoring the vendor defaults for that target.
  **Agreed.**
- **T3 — It follows the native configuration** while it lasts, rather than pinning today's
  native value. **Agreed.**
- **T4 — It lasts until restart**; nothing in the state file. **Agreed.**
- **T5 — A plain reset undoes it**, landing on the baseline. **Agreed.**
- **T6 — Sticky overrides still need `--include-sticky`**; the flags are independent.
  **Agreed.**
- **T7 — On a target the vendor defaults don't name, it's a plain reset**, not an error.
  **Agreed.**
- **Rules** — out of scope; issue #96.

## Settled during implementation

Details the text above left open, decided while building it; none changes an agreed decision.

- **"Follows native" means "unmanaged".** LogAperture can't see a native change it is itself
  overriding, so T3 is implemented as: land on the native value captured at startup, then stop
  managing the target. `list loggers`' `CONFIGURED` column keeps showing that startup value, as it
  already did for any native change.
- **Group refs apply both ways.** `reset handler ALL_HANDLERS` (or `DEFAULT_HANDLERS`) makes the
  vendor-layer change for each member: `--to-native` resets each vendor-named member to native,
  and a plain reset puts each one back on its vendor default.
- **A handler covered by a group override** is marked reset to native underneath it without
  disturbing the group's level; when the group override goes (reset, expiry), it lands on native.
- **A vendor handler that hasn't resolved yet**, reset to native, is never applied when it does
  resolve, and `doctor` stops reporting it as pending.
- **Pattern resets** also reach vendor-named loggers that aren't instantiated yet.
- **`reset default-handler`**: `--to-native` uses the new MXBean `resetDefaultHandler(boolean)`;
  a plain reset keeps using `setDefaultHandlerMembers([])`, which every agent has, so a new
  `logctl` still works against an older agent. In the agent both are the same plain reset (the
  empty-set spelling now also puts a reset-to-native vendor list back). Its audit record's new
  value is `<vendor-defaults>` when the vendor list is back in effect, else `<rule-derived>`.
- **A `DEFAULT_HANDLERS` override moves with the membership.** When a reset (or `set
  default-handler`) changes which handlers are in the group, an active `DEFAULT_HANDLERS`
  override is applied to the new members, each member that left goes back to its baseline, and
  AUTO tracking is recomputed. Before this, `set default-handler` left the override on the old
  members.
- **Multi-context.** Reset-to-native state is copied to a context that registers later, the same
  way active overrides are, so it holds in every context until restart.
- **Concurrent sweep.** A verification sweep that re-applies a vendor level at the moment a reset
  `--to-native` lands re-checks after writing and undoes its write, since nothing else would
  re-assert a target reset to native.
- **Audit on group resets.** The `reset to native default until restart` reason is recorded only
  for members whose vendor entry was actually switched; other members' records carry none.
- **`status` / `env`**: the vendor defaults status reads e.g. `loaded (2 loggers), 1 reset to
  native`, counted in the first context (resets broadcast to every context); `status` shows it as
  `2 loggers, 1 reset to native`.
- **Bulk output**: `reset loggers --to-native` prints `Reset N logger(s) to their native default
  (until restart).`; `reset handlers --to-native` the handler equivalent.
