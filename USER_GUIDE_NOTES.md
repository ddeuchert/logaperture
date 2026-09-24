# User guide notes

Things to remember when the user guide is written. Not a user guide itself —
a scratch list, kept deliberately rough. Add an entry whenever a behaviour
or constraint comes up that a user will need to know. Delete entries as they
are absorbed into the real guide.

## `-javaagent` ordering

- Agents listed **before** `logaperture-agent.jar` run their `premain` first.
  Anything they log during startup is emitted before LogAperture can arm rules,
  so `drop`/`trim` rules (sticky or not) can't affect it.
- Do **not** assume "LogAperture first" is better. It can shrink the window of unfiltered
  early events, but on WildFly it also runs our install earlier; on one real launch
  (Tanuki wrapper, several other agents, `jboss.modules.system.pkgs` naming
  `org.jboss.logmanager`) that made a rare startup failure (`ModuleNotFoundException:
  org.jboss.as.standalone`) happen every time. Cause: installing our filters/formatters on the
  JBoss handlers at `premain` (see `doc/spikes/early-handler-install.md`, issue #86). If startup
  fails with LogAperture first, try moving it later, or `-Dlogaperture.disabled=true` to confirm.
- On WildFly, rules take effect once the server's logging is configured, not at `premain`, so
  events logged in the first seconds of boot (including other agents' `premain` logging) can't be
  trimmed or dropped.
- Early events from other agents may not go through JUL/JBoss LogManager at all
  (own formatter, console, private buffer); if so they are out of reach. The fix
  then belongs in the emitting agent's configuration.
- Listing the same agent jar twice is harmless but pointless; remove one.
- Example: a `destiny-agent` `premain` logging a `ConnectException` at boot while
  the sticky trim rule only works later in the log.
- Roadmap: spec §18.14; doctor check tracked in #85; the startup-abort fix in #86; mechanism
  follow-up in #87.
