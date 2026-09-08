# `logctl console` — Full-screen Terminal UI

Status: **capture only — not yet specced or signed off.** This file records the
intent and the open questions so implementation is not started before a full
spec has been through a sign-off review (per
[`CLAUDE.md`](../../CLAUDE.md) — "Sign-off reviews happen in an artifact").
Nothing below is a design decision yet.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §8.3 (the TUI is
"the recommended first interface"), §8.5 ("what the interface is actually for" —
the logger tree, the four discovery states, configured-vs-effective level), §8.6
(enriching discovery from loaded classes), §8.1–8.2 (every surface is a client of
the same command model; the agent never listens), §17 (roadmap — pulled forward
to alpha-2).
Builds on: [`doc/specs/cli-transport.md`](cli-transport.md) — "The TUI (§8.3) …
[is a] later renderer over this exact transport"; `console` adds no new agent
surface and no new transport, only a different front-end over the attach
connection the CLI already opens. Also reuses the read models of
[`doc/specs/level-control.md`](level-control.md) (`listLoggers`),
[`doc/specs/top.md`](top.md) (bytes per logger), [`doc/specs/doctor.md`](doctor.md)
(findings), and `logctl status` (active overrides).

## Functional summary

After this feature, the user will be able to:

- Run **`logctl console`** to open a full-screen, `htop`-style live view of a
  target JVM's logging, and drive the common tasks by keystroke instead of by
  composing `logctl` command lines.
- Browse loggers as a collapsible package **tree** with fuzzy search, seeing each
  logger's **configured** and **effective** level side by side, its discovery
  state (live / known / inferred / referenced-but-never-seen), and its byte
  volume.
- Select a logger and **change its level for a duration** from the interface,
  with the expiry countdown and an `undo` visible on screen, and see the
  active-overrides panel at all times.
- See the `top` volume view and the `doctor` findings as panels in the same
  interface.
- Quit the interface and have made only the changes they explicitly applied —
  `console` is a renderer over the same command model and capability checks
  (§8.1) as the CLI, with nothing extra added to the agent.

The one-shot `logctl` verbs (`levels`, `debug`, `top`, `doctor`, `status`,
`undo`, …) are unchanged and remain the scripting interface; `console` is an
additional front-end, not a replacement.

## Motivation

From alpha-1 regression testing: the `logctl` command line is cumbersome for
interactive use — every question ("what's noisy?", "what did I already change?",
"what's the effective level here?") is a separate command to compose and its
output to eyeball, and setting a level means first resolving the logger's full
name. It is exactly right for scripting and stays that way. But the interactive
"find a logger, see its level, change it" loop (§14.1) — the interaction this
project exists to make trivial — wants a single live screen, which is what §8.3
already nominates as the recommended first interface.

## Roadmap placement

Layer 3 (Developer) in the §17 layer model, **pulled forward to alpha-2** — the
developer turnkey workflow release. Read-mostly: the browsing, volume, and
findings panels need only the `VIEW` capability (§9.3); applying a level from the
interface goes through the same capability check and audit record as
`logctl debug`. No new agent surface, no listener (§8.2), no new transport
(`cli-transport.md`).

## Open design questions

Deferred — to be resolved when this is specced, not here.

1. **Toolkit.** JLine vs. Lanterna (§8.3 names both as candidates). Bearing on
   the distribution size and on how the screen is drawn/refreshed.
2. **Scope of the first slice.** Minimum that is worth shipping in alpha-2 —
   e.g. logger tree + search + set-level-with-expiry + active-overrides panel —
   versus folding in the `top` and `doctor` panels from the start. If the
   exception-detail threshold (top-level §18.8) lands in the same window, the
   tree/edit panel needs a slot for its per-logger value too.
3. **Refresh model.** Poll the target on a fixed interval (like `htop`), poll
   only on user action, or a hybrid; and what the interval is.
4. **Target selection.** `console` takes the same `--pid` / discovery rules as
   the rest of `logctl` (`cli-transport.md`); open question is what it does when
   discovery is ambiguous — pick a target in-UI, or require `--pid` up front like
   the one-shot verbs.
5. **Apply-from-UI confirmation.** Whether a level change made in the interface
   needs an explicit confirm step, and how the duration is chosen (a default
   with a prompt to change it, vs. always prompting).
6. **`--json` / non-interactive.** `console` is interactive by definition; decide
   whether a non-TTY invocation is an error, or falls back to a one-shot summary.
7. **Relationship to `logctl ui`.** §8.4's browser UI is a separate later
   renderer over the same transport; confirm `console` (terminal) and `ui`
   (browser) stay independent front-ends with no shared server component.
8. **Name collision check.** "console" also names the WildFly `CONSOLE` handler in
   handler-control vocabulary; confirm `logctl console` (a subcommand) and
   `logctl handler console <level>` (a handler argument) don't read as the same
   thing to a user.

## Explicitly not in this capture

- The full interaction design — panels, keymap, navigation model. That is the
  spec, written at sign-off.
- The browser UI (`logctl ui`, §8.4) — separate renderer, separate future work.
- Any new operation on the agent. `console` composes existing operations.
