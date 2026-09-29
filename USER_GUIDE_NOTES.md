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
- Listing the same agent jar twice is pointless; remove one. For `logaperture-agent.jar` itself it
  is worse: the second copy fails to lock the state file and to register its control surface, and
  logs errors at startup.
- `logctl doctor` reports the order it sees (`agent.order`: `OK` when LogAperture is first, `INFO`
  naming the agents ahead of it) and any jar listed twice (`agent.duplicate`). Agents set in
  `JAVA_TOOL_OPTIONS` / `JDK_JAVA_OPTIONS` count as listed first. No ordering line when
  LogAperture was attached to a running JVM; a jar listed twice is still reported.
- Example: a `destiny-agent` `premain` logging a `ConnectException` at boot while
  the sticky trim rule only works later in the log.
- Roadmap: spec §18.14; doctor check in `doc/specs/doctor-agent-order.md` (#85); the startup-abort fix in #86; mechanism
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

## Recipes

- A library's recipe file lives at `META-INF/logaperture/recipes.yaml` **on its class path**. In a
  war that means `WEB-INF/classes/META-INF/logaperture/recipes.yaml` (the war's own `META-INF/` is not
  on the class path), or inside any jar in `WEB-INF/lib`. In an ear: inside a jar in `lib/`.
- Namespaces: use a reverse domain you control, normally the library's Maven `groupId`
  (`io.undertow:sessions`). Not enforced; `vendor`, `logaperture` and `org.logaperture` are reserved.
- A library's recipes appear only once the library has loaded — `list recipes` looks at what's
  running.
- A library's recipe can only raise levels; lowering ones are skipped. Rules and handler levels are
  only allowed in the vendor defaults file or the recipes folder.
- Writing a description: `description: |` then the text indented below it. `#` inside it is text.
- `apply recipe` changes are ordinary overrides and rules that remember the recipe. Changing one by
  hand (`set logger`, `alter rule`) makes it yours: `reset recipe` then leaves it alone.
- Re-applying a recipe replaces its rules (new `rN` ids) and resets the tier of its levels.
- A recipe's changes stay after its library is undeployed; `list recipes` still shows it as `(no
  longer offered)` so it can be reset.
