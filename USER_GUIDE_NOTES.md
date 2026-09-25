# User guide notes

Things to remember when the user guide is written. Not a user guide itself —
a scratch list, kept deliberately rough. Add an entry whenever a behaviour
or constraint comes up that a user will need to know. Delete entries as they
are absorbed into the real guide.

## `-javaagent` ordering

- Agents listed **before** `logaperture-agent.jar` run their `premain` first.
  Anything they log during startup is emitted before LogAperture can arm rules,
  so `drop`/`trim` rules (sticky or not) can't affect it.
- Listing LogAperture first shrinks the window of unfiltered early events. Before issues
  #86/#87 were fixed, it also made a startup abort (`ModuleNotFoundException:
  org.jboss.as.standalone`) certain on launches with `jboss.modules.system.pkgs` naming
  `org.jboss.logmanager`. That cause is fixed (see `doc/spikes/early-handler-install.md`
  "Mechanism"). If startup still fails, `-Dlogaperture.disabled=true` confirms whether LogAperture
  is involved.
- On WildFly, `drop`/`trim` rules (and `top`'s byte counts, storm detection) take effect when the
  agent installs. `-Dlogaperture.handlerInstallDelaySeconds=<n>` (default `0`) holds them back `n`
  seconds if a launch needs that; the server log then says when the deferred install completes.
  Levels always apply immediately.
- Early events from other agents may not go through JUL/JBoss LogManager at all
  (own formatter, console, private buffer); if so they are out of reach. The fix
  then belongs in the emitting agent's configuration.
- Listing the same agent jar twice is harmless but pointless; remove one.
- Example: a `destiny-agent` `premain` logging a `ConnectException` at boot while
  the sticky trim rule only works later in the log.
- Roadmap: spec §18.14; doctor check tracked in #85; the startup-abort fix in #86; mechanism
  follow-up in #87.

## Configuration layers, reset and `--to-native`

Canonical definition: spec §6.6 "Precedence: configuration layers". Lift the diagram and both
tables into the guide more or less as they are.

- The layers, bottom to top: **native configuration** (the app's own `log4j2.xml`,
  `logging.properties`, `standalone.xml`) → **vendor defaults** (`--vendor-defaults=` file) =
  **baseline** → **override** (`set`, tier `session` / `for <duration>` / `sticky`) =
  **effective level** (the `EFFECTIVE` column). Sticky overrides live in the **state file** and
  come back at startup.
- `reset` always returns to the **baseline**, not to native configuration. Users coming from
  "reset means back to my log4j2.xml" need telling this once, early.
- `reset … --to-native` returns to the native configuration, ignoring the vendor defaults, until
  restart, and follows native changes while it lasts. A plain `reset` undoes it.
- Two personas to write for separately: the **operator** (tuning a running system) and the
  **vendor** (building the next vendor defaults file in a sandbox, then `logctl export
  vendor-defaults`). The vendor's four choices per entry (nothing / `set … sticky` new value /
  `set … <native level> sticky` / `reset --to-native`) and what each puts in the exported file:
  the table in §6.6.
- Rules (vendor `drop`/`trim` rules) and `--to-native`: being redesigned in #96.
