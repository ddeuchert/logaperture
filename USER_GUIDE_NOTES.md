# User guide notes

Things to remember when the user guide is written. Not a user guide itself —
a scratch list, kept deliberately rough. Add an entry whenever a behaviour
or constraint comes up that a user will need to know. Delete entries as they
are absorbed into the real guide.

## `-javaagent` ordering

- Agents listed **before** `logaperture-agent.jar` run their `premain` first.
  Anything they log during startup is emitted before LogAperture can arm rules,
  so `drop`/`trim` rules (sticky or not) can't affect it.
- Put LogAperture **first** to shrink the window. It can't close it: on WildFly,
  install is deferred until JBoss LogManager has loaded, so rules are armed no
  earlier than that regardless of position.
- Early events from other agents may not go through JUL/JBoss LogManager at all
  (own formatter, console, private buffer); if so they are out of reach. The fix
  then belongs in the emitting agent's configuration.
- Listing the same agent jar twice is harmless but pointless; remove one.
- Example: a `destiny-agent` `premain` logging a `ConnectException` at boot while
  the sticky trim rule only works later in the log.
- Roadmap: spec §18.14; doctor check tracked in the issue filed for it.
