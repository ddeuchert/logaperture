# Demo recordings

Status: **signed off 2026-10-10**, decisions D1–D12 agreed as recommended. Implemented
2026-10-10: story 1 as the prototype (D12), reviewed, then stories 2 and 3. Story 4 (a `trim`
rule) added 2026-10-18, decisions T1–T6 agreed as recommended.
Issues: [#196](https://github.com/ddeuchert/logaperture/issues/196) (stories 1–3),
[#226](https://github.com/ddeuchert/logaperture/issues/226) (story 4). Milestone `1.0.0`;
documentation and a test fixture only, so it can land after the beta 1 feature freeze (Oct 15).
Parent spec: [`doc/specs/user-documentation.md`](user-documentation.md) (the guide this adds to), and
through it [`doc/logaperture-spec.md`](../logaperture-spec.md) §17.1 "User documentation".
Related: [`wildfly-dev-environment.md`](wildfly-dev-environment.md) (the server the demos run
against).

## Functional summary

After this feature, the user will be able to:

- Watch a short terminal recording of LogAperture fixing a real problem, on logaperture.org and
  on the README, with the server's console above and `logctl` below.
- Pause a recording on the guide and copy a command straight out of it, including in the guide
  that ships in the release zip.
- See four stories: **why don't I see my TRACE?** (a handler stricter than the logger),
  **debug one package for a while, then it cleans itself up** (`set logger … for`),
  **something is flooding the log** (`top`, then a `drop` rule), and **stack traces are burying
  the log** (a `trim` rule).
- Trust that what a recording shows matches the release they run: the recordings are made by a
  script against a real server and redone for each release.

Guide pages changed: `index.md` ("See it work") and `get-started/quick-start.md` (step 4) for
story 1, `how-to/raise-a-level.md` for story 2, `how-to/silence-noise.md` for stories 3 and 4.
README: one GIF near the top.

For the maintainer: `dev/demos/record.sh` re-records every demo against the dev WildFly; the
release steps in `DEVELOPMENT.md` gain "re-record the demos".

## Why

A user deciding whether to try LogAperture reads the README for a few seconds. The README says
what it does in words; a recording shows a level raised and reverting, and a flood stopped, in
the time it takes to read a paragraph. On the guide, the recordings sit next to the how-to that
explains the same steps.

## Out of scope

- Story 5 (`doctor`, storm detection, vendor defaults with `export`), first planned as story 4:
  a later issue, [#198](https://github.com/ddeuchert/logaperture/issues/198) (D11, T1).
- A narrated screen capture (OBS, audio). The issue leaves that for later.
- Running the recordings in CI. They need Docker, a WildFly and a minute of wall-clock time each;
  the release step is manual (D10).

## The stories

Each is under 90 seconds: one problem and its fix. `# comment` lines narrate, since there's no
audio, and each opens by saying what is running.

### 1. Why don't I see my TRACE? (implemented)

The demo shop is logging at `INFO` on the console pane. The recording runs about 40 seconds.

```sh
# A web shop app deployed on a WildFly server. Its console is above.
# A customer reports failed payments. Turn on TRACE for the payment code:
logctl set logger com.example.shop.payment TRACE for 15m
#   logctl warns that CONSOLE is at INFO and will drop the TRACE records
# No TRACE above: the CONSOLE handler is at INFO. Let it follow the logger:
logctl set handler CONSOLE AUTO
#   TRACE lines from com.example.shop.payment now appear on the console
logctl status
# Both revert on their own. Nothing in standalone.xml changed.
```

The first line names the app and the server, since the closing line's `standalone.xml` means
nothing without it, and the README's GIF has no page text around it to say so. `CONSOLE` rather
than `DEFAULT_HANDLERS`: it is the name `logctl`'s own warning gives a moment
earlier, and the one the quick start's step 4 uses. This is the confusion real users have.

### 2. Debug one package for a while, then it cleans itself up (implemented)

About 60 seconds, most of it the console scrolling while the override runs out.

```sh
# A web shop app deployed on a WildFly server. Its console is above.
# Debug the checkout code for a while: 30 seconds here, an hour in real life.
logctl set logger com.example.shop.checkout DEBUG for 30s
logctl set handler CONSOLE AUTO
#   DEBUG cart lines appear on the console
logctl list loggers com.example
# Now wait. Nobody has to remember to put it back...
#   the agent's audit line "action=REVERSION … source=expiry-sweep" reaches the console,
#   and the DEBUG lines stop
logctl status
# The override is gone, and CONSOLE went back to INFO with it.
```

The issue says `for 10m`; a recording can't wait ten minutes, so the demo uses a short duration
and says so in a comment (D7).

### 3. Something is flooding the log (implemented)

The demo shop's stock sync is failing: a `ConnectException` with a stack trace, 20 times a
second. About 40 seconds.

```sh
# A web shop app deployed on a WildFly server. Something is flooding its console.
# Which logger is writing all this?
logctl top --limit 5
# StockSync, mostly stack traces. Drop that exception, keep one a minute:
logctl add rule drop com.example.shop.inventory.StockSync --throwable java.net.ConnectException --sample-full 1m
#   the stack traces stop; orders keep logging
logctl list rules
# The flood is gone; the shop still logs. HITS counts every event dropped.
```

`top` ranks loggers by bytes since the agent started, not by recent rate, so on a dev WildFly
that has been up a while the flood needs time to lead. The story's hidden setup starts the
flood 20 seconds before recording, and the story checks that StockSync is `top`'s first line.

### 4. Stack traces are burying the log (issue #226)

The demo shop's primary payment endpoint refuses connections: every order fails once, then goes
through on the fallback, and each failure logs a WARN with a stack trace 28 lines long. The
console is a wall of frames that says the same thing every time. About 40 seconds.

```sh
# A web shop app deployed on a WildFly server. Its console is above.
# Its primary payment endpoint is down. Every order falls back, and logs a full stack trace:
#   the console is all stack frames
# Same ConnectException every time, nothing to learn from the trace. Keep the warning, one line:
logctl add rule trim com.example.shop.payment.PaymentGateway --throwable java.net.ConnectException --any-cause
#   each warning is now 2 lines: the WARN, and
#   "Caused by: java.net.ConnectException: Connection refused [stack trace trimmed: 24 frames omitted]"
logctl list rules
# Every warning is still logged, with its cause on one line. The orders are readable again.
```

The point is noise reduction for a failure whose cause is known: the real case behind this story
was a `ConnectException` from an environment problem, the same root cause every time, where the
full trace added nothing. Story 3 drops a flood; this one keeps every event, so the narration
says why not `drop`.

The rule names the exception (T3). `--throwable` matches the top-level exception's exact class,
and here the `ConnectException` is the cause of the shop's `PaymentException`, as client
libraries usually wrap it, so the rule adds `--any-cause`. It has no `--frames`: the default, 0,
prints each exception as one line. The command fits the 120-column pane on one line.

Measured on the dev WildFly: 28 lines per warning before the rule, 2 after. WildFly's pattern
puts the `PaymentException`'s summary on the WARN line itself, and the pane cuts that long line
before its marker; the `Caused by:` line, with the exception class and its marker, fits. Every
order also logs two INFO lines, so after the rule a warning and its order take 4 lines, and the
11-row console pane shows two or three orders at once.

The story fails the recording unless, before narrating, a stack frame is on the console pane;
then, after the rule, a `Caused by: java.net.ConnectException: … [stack trace trimmed: ` line
reaches it within 15 s, and every stack frame and `... N more` line leaves it within 25 s
(`wait_gone`) (T6). Its hidden setup starts the outage before recording, so the first frame
already shows the problem.

## Design

### The demo app (D1)

The recordings must show generic, realistic logs, never a real customer system. The sample WAR
has a demo shop (`DemoShop`, switched by `DemoServlet`). `POST /demo/start` starts a background
thread that places an order every 1.5 s and syncs stock every 6 s, logging through SLF4J, which
WildFly routes into JBoss LogManager:

| Logger | Logs |
|---|---|
| `com.example.shop.checkout.CheckoutService` | INFO `Order 10423 placed: 3 items, 84.20 EUR`; DEBUG the cart |
| `com.example.shop.payment.PaymentGateway` | INFO `Authorised order 10423: 84.20 EUR`; DEBUG the request; TRACE request and response detail; now and then a WARN `Order 10428 declined: do not honour` |
| `com.example.shop.inventory.StockSync` | INFO `Stock synced: 214 SKUs updated` |

`POST /demo/flood/start` makes `StockSync` log a WARN with a `java.net.ConnectException` and its
stack trace every 50 ms until `POST /demo/flood/stop`. `POST /demo/stop` stops everything, and
`GET /demo/status` says what is running.

`POST /demo/payment-outage/start` (story 4, T2) makes the primary payment endpoint refuse
connections until `POST /demo/payment-outage/stop`: every order's first attempt fails, and
`PaymentGateway` logs a WARN `Primary payment endpoint unreachable for order 10425, using
fallback` with a `com.example.shop.payment.PaymentException` caused by a
`java.net.ConnectException: Connection refused`; the order then goes through as usual. The trace
comes from real nested calls inside the demo shop — checkout calls the gateway, which calls a
provider client and its interceptor chain, which calls a transport, which refuses — not from a
real network failure, so every recording shows the same trace (T4). It is 28 lines printed: the
`PaymentException` with its 10 frames, then the `Caused by:` with the 15 frames it doesn't share
and `... 9 more`. `/demo/stop` ends the outage too.

These classes live in `com.example.shop.checkout` and `com.example.shop.payment`
(`CheckoutService`, `PaymentGateway`, `PaymentException`, `ProviderClient`, `HttpTransport`),
not under the project's `org.logaperture` package root: the recording shows their frames and the
exceptions' class names, and they should read as the shop's own code. The exception is allowed
because the sample WAR is a test fixture that never ships.

Order numbers, amounts and cards come from a fixed seed,
so two recordings show the same orders. Messages are kept short, so that a line with its logger
and thread names fits the 120-column pane.

The sample WAR is a test fixture, never shipped, so this changes nothing a user installs.

### The demo scripts (D2, D3, D4)

```
dev/demos/
  README.md               how to record, rehearse and write a story
  record.sh               record one story, or all of them
  run-story.sh            runs a story in the bottom pane
  console.sh              the top pane
  lib.sh                  the steps a story is written in
  tmux.conf               the recordings' tmux settings
  why-no-trace.sh         story 1
  debug-for-a-while.sh    story 2
  flood.sh                story 3
  trim.sh                 story 4
guide/assets/demos/
  why-no-trace.cast       the recordings, committed (asciicast v2 text: ~40 KB each,
  debug-for-a-while.cast  ~580 KB for the flood, whose stack traces are most of it)
  flood.cast
  trim.cast
```

A story script is a `story()` function made of steps:

```sh
say "A customer reports failed payments. Turn on TRACE for the payment code:"
run logctl set logger com.example.shop.payment TRACE for 15m
expect_absent "$TRACE_LINE"     # fail if the console pane already shows one
run logctl set handler CONSOLE AUTO
wait_for "$TRACE_LINE" 10       # until the console pane shows one, at most 10 s
```

The other checks are `wait_gone` (until no console line matches, e.g. the flood has scrolled
away) and `expect_output` (the last command's output matched, e.g. StockSync leads `top`). A
story may define `before_recording`, run before recording starts; story 3 starts the flood there.

- `say` types a dimmed `# comment` at the prompt. `run` types the command at a readable speed,
  runs it, and pauses so the viewer can read the output. Our own `lib.sh`, not the vendored
  demo-magic script: it needs no `pv`, and it can run unattended (D3).
- **Unattended by default.** Pauses are timed, not keypresses, so `record.sh all` re-records every
  demo with no one at the keyboard. `record.sh --rehearse STORY` plays it in your terminal and
  waits for a key at each pause instead, writing nothing (D4).
- `wait_for` and `expect_absent` read the console pane (`tmux capture-pane`). A failed check prints
  what the pane showed and writes no `.cast`, so a demo never shows a step whose effect didn't
  happen, and a broken one can't be committed by accident.
- `logctl` in the scripts is a shell function that runs `logctl` inside the dev WildFly container,
  as `wildflyctl.py logctl --` does, so the screen shows `logctl …` exactly as a user types it.
- **Hidden setup and teardown.** Before recording, `record.sh` stops the shop, resets every
  logger, handler and rule (`--include-sticky`), starts the shop again and lets it log for a few
  seconds; afterwards it stops the shop and resets again. A story always starts from the server's
  own configuration.

### The screen (D5, D6)

`record.sh` starts tmux on a socket of its own (none of the user's tmux settings apply), fixed
at **120×32**, split top and bottom, each pane titled in its top border:

- **Top, 11 rows: `server console`** — `docker logs --follow` of the dev WildFly from the moment
  recording starts. It shows what the `CONSOLE` handler writes, which is what story 1 is about:
  `server.log` gets the `TRACE` lines either way, because WildFly's `FILE` handler is at `ALL`.
  A line wider than the pane is cut with `…` rather than wrapped, so each record stays one row.
  The colours are WildFly's own and pass through unmodified: the stock `standalone.xml` gives
  `CONSOLE` the `COLOR-PATTERN` formatter, whose `%K{level}` colours DEBUG green, WARN yellow and
  ERROR red. They are kept because they make each story easier to follow, and they are what a
  default WildFly prints on Linux and macOS. On Windows, JBoss LogManager prints no colour
  (unless `ANSICON` is set), and `-Dorg.jboss.logmanager.nocolor=true` turns it off anywhere.
  `server.log` never has colours.
- **Bottom, 19 rows: `logctl`**, with the prompt `$ `. A story's whole bottom pane fits in 19
  lines, so its last frame, which is the guide's poster and the GIF's held frame, shows the whole
  story from its first line.

Top and bottom rather than side by side, because log lines and `logctl` tables both need the
width. asciinema records the tmux client headless (`asciinema rec --headless --window-size
120x32 --idle-time-limit 1 --output-format asciicast-v2`). The recording runs with a clean
environment (`env -i`, a scratch `HOME`), so no host name, user name or local path ends up in a
`.cast` file. `record.sh` drops the events in the last 50 ms before the cast's exit event, which
are tmux restoring the terminal, so a recording ends on the story's final screen.

### On the guide (D8)

asciinema-player 3.17.0 (Apache 2.0) is vendored into `guide/assets/asciinema-player/` (its JS,
CSS and licence), not loaded from a CDN: the guide in the release zip opens with no network
access. `mkdocs.yml` adds it through `extra_javascript` / `extra_css`, and a page embeds a
recording by name:

```html
<div class="la-cast" data-cast="why-no-trace"></div>
```

`guide/assets/javascripts/casts.js` turns each such div into a player, fit to the page width,
with the poster at the last frame so the page shows the finished story before anyone presses
play. It loads the recording as `assets/demos/<name>.cast.js` through a `<script>` tag, not with
`fetch()`, because browsers refuse `fetch()` on `file://`, where the zip's guide is opened. A
MkDocs hook, `guide-hooks/demo_casts.py`, writes those `.cast.js` files at build time, and fails
the build when a page names a recording that `guide/assets/demos/` doesn't have.

| Recording | Pages |
|---|---|
| Why don't I see my TRACE? | `index.md` ("See it work"), `get-started/quick-start.md` step 4 |
| Debug one package for a while | `how-to/raise-a-level.md` |
| Something is flooding the log | `how-to/silence-noise.md` |
| Stack traces are burying the log | `how-to/silence-noise.md`, "Shorten a stack trace", above the static example, which stays (T5) |

### On the README (D9)

GitHub won't run the player, so the README shows a GIF made from the `.cast` with
[agg](https://github.com/asciinema/agg). Only one: story 1, linked to the guide's "See it work".
The GIF isn't committed: the docs workflow installs agg 1.9.0 (checked against its SHA-256) and
builds with `DEMO_GIFS=1`, which makes the hook render the recordings named in `mkdocs.yml`'s
`extra.readme_gifs` to `assets/demos/<name>.gif` in the site;
the README embeds `https://logaperture.org/latest/assets/demos/why-no-trace.gif`. That keeps a
~1 MB binary that changes every release out of the git history. The cost: the image is missing
until a release tag publishes `latest` with it. `main` only moves at a release, so the README
people see on GitHub gets the image link and the published GIF together.

### Re-recording at each release (D10)

`DEVELOPMENT.md`'s release steps gain one, before the Central dry run: with the release version's
agent in the dev WildFly, run `dev/demos/record.sh all`, watch each recording, and commit the new
`.cast` files. A recording whose output changed is a hint that user-visible output changed — the
guide's example output may need the same update.

## Decisions

| # | Decision | Agreed |
|---|---|---|
| D1 | Where the demo traffic comes from | A demo mode in the sample WAR logging `com.example.shop.*`, with a flood switch. Not a new app: the dev WildFly already deploys the sample. |
| D2 | Where the scripts live | `dev/demos/`, next to `dev/wildfly/` which they drive. The issue's `doc/demos/` is for design docs. |
| D3 | demo-magic or our own helper | Our own `lib.sh`: no `pv` dependency, no vendored third-party script, and unattended runs. |
| D4 | Keypress between steps, or timed | Timed by default (reproducible, unattended re-recording); `--rehearse` for rehearsal. |
| D5 | Terminal size and split | 120×32, console on top, `logctl` below. The issue's 110×30 is too narrow for `list handlers`. |
| D6 | What the log pane shows | The server console (`docker logs`), not `server.log`: story 1 is about the console handler. |
| D7 | Story 2's duration | `for 30s`, with a comment saying it would be `10m` in real life. Showing the real revert beats editing time. |
| D8 | asciinema-player: CDN or vendored | Vendored into `guide/assets/`, pinned, so the offline guide in the zip plays them. |
| D9 | README GIF: committed or built | Built by the docs workflow and served from logaperture.org; one GIF (story 1) only. |
| D10 | Keeping recordings current | A manual release step (`record.sh all`); no CI check, since recording needs Docker and WildFly. |
| D11 | Scope for 1.0 | Stories 1–3. Story 4 (`doctor`, storms, vendor export) gets its own issue. |
| D12 | Order of work | Story 1 end to end first (app, scripts, player, one page, README GIF) as the prototype, for review; then stories 2 and 3. |

Story 4 (issue #226), agreed 2026-10-18:

| # | Decision | Agreed |
|---|---|---|
| T1 | Numbering | The `trim` story is story 4; the `doctor` / storms / export story from D11 becomes story 5 (#198). |
| T2 | Scenario | A payment-outage switch in the demo shop: every order's first attempt fails, and `PaymentGateway` WARNs with a `PaymentException` caused by a `ConnectException`, 28 lines printed. Revised after the first recording: a timeout on every third order with `--frames 3` didn't look buried in an 11-row pane. |
| T3 | The rule | `trim <logger> --throwable java.net.ConnectException --any-cause`, default `--frames 0`: a known root cause cut to one line. Revised from a bare `--frames 3` to put the emphasis on noise reduction, after the real case that motivated `trim`. |
| T4 | Where the trace comes from | Real nested calls in the demo shop (checkout → gateway → client → transport), not a real network failure: the same trace in every recording. In `com.example.shop.*`, not `org.logaperture`, so the frames read as the shop's (agreed during implementation). |
| T5 | Guide placement | `how-to/silence-noise.md`, "Shorten a stack trace", next to story 3; the static example stays. |
| T6 | Checks | A stack frame on the console before the story starts; after the rule, a trimmed `Caused by: java.net.ConnectException` line within 15 s, then `wait_gone` on every frame and `... N more` line. |

### Changes during implementation

Details the agreed decisions left open, settled while building story 1:

- Stories 1 and 2 use `set handler CONSOLE AUTO`, not `DEFAULT_HANDLERS` (see story 1).
- The console pane is 11 rows plus its title border; `--interactive` became `--rehearse`.
- Recordings are asciicast v2, which every player and agg version reads, rather than asciinema
  3's default v3.
- The player loads recordings from generated `.cast.js` files, so they play from `file://` (D8
  as agreed would otherwise have failed in the zip).
- The flood is a WARN with a top-level `ConnectException` and no cause, so the story 3 rule
  matches it with plain `--throwable` and the default `--below ERROR`.

Found while recording story 4 (issue #226), on a machine with podman's `docker` shim rather
than Docker:

- Rootless podman finds its containers under the user's XDG data and config dirs, which the
  clean environment's scratch `HOME` hid, so `record.sh` passes `XDG_DATA_HOME` and
  `XDG_CONFIG_HOME` through. They are only read, never printed, so no path reaches a `.cast`.
- The shim prints an "Emulate Docker CLI" notice on every call, which would be recorded in both
  panes. `record.sh` stops unless it is quieted (`/etc/containers/nodocker`); `dev/demos/README.md`
  says so.

## Testing

- `mkdocs build --strict` (CI already runs it) fails on a page naming a missing recording, or a
  missing player asset.
- The sample WAR has no unit tests (the `/timer` endpoints have none either); `record.sh` drives
  the demo endpoints on every recording, and fails without a working shop.
- `record.sh` fails, rather than writing a `.cast`, when a `wait_for` or `expect_absent` check
  fails, so a broken demo can't be committed by accident.
- Looked at by a person before each commit of new recordings. There's no automated check that a
  recording is good.
