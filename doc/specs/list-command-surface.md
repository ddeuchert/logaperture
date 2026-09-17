# List command surface: logger/handler namespacing, overrides-only default

Status: implemented (slice 3 of #42).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.9 (roadmap entry — the
broader command-surface refactor), §6.2 (the phone test), §14.5 ("CLI ergonomics are the
product").
Builds on, unchanged: [`doc/specs/reset-command-surface.md`](reset-command-surface.md) (slice
1 — the `reset logger`/`reset loggers`/`reset handler`/`reset handlers` split this slice
mirrors the naming of), [`doc/specs/set-command-surface.md`](set-command-surface.md) (slice 2
— the `set logger`/`set handler` split).
Implementation spec for: [`doc/specs/cli-transport.md`](cli-transport.md)'s "Command surface"
— this slice replaces that document's `levels`/`handlers` sections on landing.
Tracks: slice 3 of [issue #42](https://github.com/ddeuchert/logaperture/issues/42).

## Functional summary

After this feature, the user will be able to:

- List loggers with `logctl list loggers [filter]`, replacing today's `logctl levels
  [filter]` — same filter grammar (exact name, prefix, or leading/trailing `*` pattern).
- List handlers with `logctl list handlers`, replacing today's `logctl handlers`.
- Notice that both default to **overrides-only** — the common "what did I change" question —
  and pass `--show-all` to get today's full-catalog listing (every known logger/handler,
  overridden or not).
- Keep `logctl status` exactly as it is today — the cross-namespace "what's currently
  active, and when does it revert" overview, spanning both loggers and handlers together.
  It is not retired, renamed, or folded into `list`.
- Keep `logctl env` exactly as it is today — the separate, pasteable bug-report block. A
  pointer line is added to `status`'s help text noting `env` exists, so a user reaching for
  "what's my environment" from `status` finds it.
- Notice that `logctl levels` and `logctl handlers` (the old bare spellings) no longer exist
  — each is a usage error naming its `list` replacement.

## Scope of this slice

Slice 3 of [#42](https://github.com/ddeuchert/logaperture/issues/42)'s broader
command-surface refactor — the `list` half, and the last of the three. `reset` namespacing
shipped in slice 1 ([`reset-command-surface.md`](reset-command-surface.md)); `set`
namespacing shipped in slice 2 ([`set-command-surface.md`](set-command-surface.md)).

**In scope:**

- Two CLI forms: `list loggers [filter] [--show-all]`, `list handlers [--show-all]`.
- Retiring the bare `levels`/`handlers` top-level verbs in favor of `list loggers`/`list
  handlers`.
- New default: both forms show **only rows with an active override** unless `--show-all` is
  given, which restores today's full-catalog behavior.
- Helpful, specific usage errors for both retired spellings, naming their replacement — same
  standard slices 1 and 2 held themselves to.
- `status`'s help text gets a one-line pointer to `env`, since this slice is touching the
  neighboring help copy anyway.

**Explicitly out of scope:**

- **`status` and `env` themselves** — both keep their current command name, arguments,
  output shape, and behavior untouched. `status` is reframed in this spec's prose as the
  extensible cross-namespace overview it already functionally is, not given new columns or
  content here.
- **`top`/`doctor`** — unrelated listings, untouched.
- **Any JMX/MXBean method rename.** Unlike slices 1 and 2, this slice has no CLI-visible verb
  that needs to match a renamed service method — `listLoggers`/`listHandlerOverrides` are
  already named for what they do and aren't touched.
- **The exception-detail/filter follow-on** (§18.8) — unrelated to this slice.

## Design decisions

### Decision #1: `list loggers`/`list handlers` naming

Same split slices 1 and 2 already applied to `reset`/`set` — a two-word noun phrase under one
verb, rather than two unrelated bare top-level words (`levels`, `handlers`) that don't read as
a pair. `list loggers`/`list handlers` complete the parity `reset logger(s)`/`reset
handler(s)` and `set logger`/`set handler` already established; after this slice, every
noun-bearing verb in the CLI (`set`, `reset`, `list`) follows the same `<verb> <noun>` shape.

`levels` (plural noun, no verb) becomes `list loggers` (verb + plural noun) rather than `list
levels` — "loggers" names the namespace being listed, matching `reset loggers`/`set logger`'s
choice of noun; "levels" is what's shown in a column, not what's being enumerated.

### Decision #2: default to overrides-only, `--show-all` for the full catalog

**This is a genuine behavior change**, not a rename — flagged here for explicit sign-off
rather than folded in silently the way Decisions #1 and #3 are close to mechanical.

Today, `logctl levels [filter]` and `logctl handlers` always print every logger/handler that
exists (optionally narrowed by `levels`' filter), whether or not it carries an override. In
practice the question an operator reaches for `list` to answer is almost always "what have I
(or someone) changed" — the same question `status` answers, but split by namespace and with
`list loggers`' fuller per-row detail (`CONFIGURED`/`EFFECTIVE` alongside `OVERRIDE`, not just
`status`'s active-tier view). Defaulting to the full catalog means that question is answered
by scrolling past every logger that's simply running at its configured level — the common
case buried under the uncommon one.

`--show-all` restores today's default: every known logger/handler, overridden or not. A
`filter` on `list loggers` still narrows by name/pattern first, then the overrides-only (or
`--show-all`) filter applies on top — `list loggers com.acme --show-all` lists every logger
under `com.acme`; `list loggers com.acme` lists only the ones under `com.acme` carrying an
override.

**Interaction with `status`:** `list loggers`/`list handlers` (no `--show-all`) and `status`
now both answer "what's overridden," from different angles — `status` fans both namespaces
into one revert-countdown-oriented view, `list loggers`/`list handlers` stay per-namespace
with the configured/effective-level detail `status` doesn't carry. This is deliberate
duplication of purpose, not accidental overlap: an operator working one namespace at a time
reaches for `list`, one watching everything that's about to revert reaches for `status`.

### Decision #3: retired spellings get a specific usage error

Matching slices 1 and 2's standard:

```
$ logctl levels com.acme
logctl: 'levels' no longer exists -- use 'list loggers com.acme'.

$ logctl handlers
logctl: 'handlers' no longer exists -- use 'list handlers'.
```

Both exit 2 (usage error), consistent with every other malformed-command case.

## Command surface

Replaces `cli-transport.md`'s `logctl levels [filter]` and `logctl handlers` sections on
landing.

### `logctl list loggers [filter] [--show-all]`

Calls `listLoggers(filter)`, same as today's `levels`. `<filter>` grammar is unchanged: exact
name, prefix, or a pattern with a leading `*.` and/or trailing `.*`. Without `--show-all`,
rows with no active override are dropped from the result before rendering — table columns
(`CONTEXT` when multi-context, `LOGGER`, `CONFIGURED`, `EFFECTIVE`, `OVERRIDE`) are unchanged.
Empty-result message distinguishes "the filter matched nothing" from "it matched, just nothing
overridden" — the two are different facts and conflating them would mislead:

- `No loggers match '<filter>'.` — the filter itself matched zero loggers, regardless of
  `--show-all`.
- `No loggers matching '<filter>' have an active override.` — the filter matched real
  loggers, but none carry an override (no `--show-all`).
- `No loggers have an active override.` — unfiltered, no `--show-all`, nothing overridden.
- `No loggers known yet.` — unfiltered, `--show-all`, nothing known at all (today's message).

### `logctl list handlers [--show-all]`

Calls the same handler-catalog read `handlers` uses today. Without `--show-all`, only handlers
carrying an active override are shown. Empty-result message likewise distinguishes the two
facts: a genuinely empty catalog (a framework with no addressable handlers, e.g. Logback)
always prints today's `This framework's handlers have no level of their own — nothing to
list.`, regardless of `--show-all`; a non-empty catalog with nothing overridden (no
`--show-all`) prints `No handlers have an active override.` instead.

### Removed

- `logctl levels [filter]` (bare, no `list` noun) — usage error naming `list loggers
  [filter]`.
- `logctl handlers` (bare, no `list` noun) — usage error naming `list handlers`.

### Unchanged

- `logctl status` — output, columns, and behavior exactly as today. Help text gains one line
  pointing to `env`.
- `logctl env` — untouched.

## Testing

Per top-level §12's cheap-unit-tests-plus-one-shallow-integration-test split, mirroring
slices 1 and 2's coverage:

**Unit:**

- Parser: `list loggers`/`list handlers` accept `--show-all` and (for loggers) a filter; bare
  `levels`/`handlers` are usage errors naming their `list` replacement.
- `Commands.listLoggers`/`Commands.listHandlers` (stubbed MXBean): overrides-only filtering
  applied correctly on top of the existing filter/render logic; `--show-all` restores
  today's full-catalog `CommandsTest` coverage unchanged.
- Empty-result message selection: the message depends on whether the filter/catalog itself
  matched nothing versus matched but had nothing overridden — not solely on `--show-all` —
  covering all four messages above for `list loggers` and both for `list handlers`.

**Cross-process integration (`CliEndToEndIT`, extending the existing suite):**

- `run(["list", "loggers", FIXTURE_LOGGER])` with no active override on `FIXTURE_LOGGER` →
  the "no active override" empty message; `run(["list", "loggers", FIXTURE_LOGGER,
  "--show-all"])` → the logger's row, matching today's `run(["levels", FIXTURE_LOGGER])`.
- `run(["levels", FIXTURE_LOGGER])` → exit 2, naming `list loggers`.
- `run(["handlers"])` → exit 2, naming `list handlers`.

## Exit criterion

From a plain shell, against a `java -jar` application started with
`-javaagent:logaperture-agent.jar`:

- `logctl set logger com.acme.batch.Worker DEBUG` followed by `logctl list loggers
  com.acme.batch.Worker` shows just that one overridden row; `logctl list loggers
  com.acme.batch.Worker --show-all` shows every logger under that package, overridden or
  not.
- `logctl list handlers` shows only handlers with an active override; `logctl list handlers
  --show-all` shows every known handler.
- `logctl levels` and `logctl handlers` (the two retired spellings) are usage errors naming
  their replacement.
- `logctl status` and `logctl env` behave exactly as they do before this slice.
- Every command in `logctl --help` still passes the phone test.

## Sign-off

Open — drafted for review. Decision #2 (overrides-only default, `--show-all` opt-out) is the
one genuine judgment call and the one most worth confirming explicitly, since it changes
`list loggers`/`list handlers`' default output for every existing script or habit built on
today's `levels`/`handlers` always showing the full catalog. Decisions #1 and #3 are close to
mechanical, matching slices 1 and 2's precedent. Once agreed, fold any changes back into this
file before implementation starts (CLAUDE.md).
