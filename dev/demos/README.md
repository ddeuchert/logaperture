# Demo recordings

Scripted terminal recordings of LogAperture fixing a problem, for the guide and the README.
Each is a short story run against the [dev WildFly](../wildfly/README.md) and recorded with
asciinema: the server console on top, `logctl` below. Design:
[`doc/specs/demo-recordings.md`](../../doc/specs/demo-recordings.md).

| Story | Script | Recording | Shown on |
|---|---|---|---|
| Why don't I see my TRACE? | [`why-no-trace.sh`](why-no-trace.sh) | `guide/assets/demos/why-no-trace.cast` | guide home, Quick start step 4, README GIF |
| Debug one package for a while | [`debug-for-a-while.sh`](debug-for-a-while.sh) | `guide/assets/demos/debug-for-a-while.cast` | How-to: Raise a log level |
| Something is flooding the log | [`flood.sh`](flood.sh) | `guide/assets/demos/flood.cast` | How-to: Silence noise |
| Stack traces are burying the log | [`trim.sh`](trim.sh) | `guide/assets/demos/trim.cast` | How-to: Silence noise |

## Prerequisites

- The dev WildFly up with the sample deployed: `python3 dev/wildfly/wildflyctl.py up`, then `deploy`.
- `tmux`, `asciinema` 3 (`dnf install asciinema`), `perl`, `curl`.
- With podman's `docker` shim (Fedora's `podman-docker`) rather than Docker:
  `sudo touch /etc/containers/nodocker`, or its "Emulate Docker CLI" notice ends up in the
  recording. `record.sh` checks and stops if it is still printed.
- To preview the README GIF: [agg](https://github.com/asciinema/agg/releases) on the `PATH`.

## Record

```sh
dev/demos/record.sh all             # every story, into guide/assets/demos/
dev/demos/record.sh why-no-trace    # one story
```

`all` takes about three minutes. Before each story, `record.sh` resets every
logger, handler and rule (sticky ones too) and starts the sample's demo shop
(`POST /logaperture-sample-war/demo/start`); afterwards it resets again and stops the shop.
**Don't record against a server whose overrides you want to keep.**

A story checks its own effect: `wait_for` waits for a line to reach the console pane, and
`expect_absent` checks one hasn't yet. A failed check prints what the console showed and writes
no `.cast`, so a broken demo can't be committed by accident.

Watch the result before committing it:

```sh
asciinema play -i 1 guide/assets/demos/why-no-trace.cast
```

or build the guide (`mkdocs serve`) and play it there. `DEMO_GIFS=1 mkdocs build` also writes
`site/assets/demos/*.gif`, the README's GIF.

## Rehearse

```sh
dev/demos/record.sh --rehearse why-no-trace
```

plays the story in your terminal (at 120×32, whatever its size) and waits for a key at every
pause. Nothing is written.

## Write a story

A story is a `story()` function made of the steps in [`lib.sh`](lib.sh): `say` (a typed
`# comment`), `run` (type a command, run it, pause), `hold`, and the checks `wait_for`,
`wait_gone`, `expect_absent` and `expect_output`. An optional `before_recording()` runs before
recording starts, with the shop already running (`flood.sh` starts the flood there).
`logctl` in a story runs inside the WildFly container, but the screen shows it as a user types
it. Add the script's name to `stories` in `record.sh`.

Keep the whole bottom pane to 19 lines, so the last frame (the guide's poster and the GIF's
held frame) shows the story from its first line. Open by saying what is running, since the GIF
has no page around it. Keep it to one problem and its fix, under 90 seconds, with generic names
(`com.example…`) and the sample's demo shop as the application. Never record a real system.

## Files

| File | |
|---|---|
| `record.sh` | sets up tmux and the server, runs asciinema, writes the `.cast` |
| `run-story.sh` | runs a story in the bottom pane |
| `console.sh` | the top pane: the WildFly console, one row per line |
| `lib.sh` | the steps a story is written in |
| `tmux.conf` | the recordings' tmux settings, on a socket of their own |
