# Quarkus: configured handler names

Status: **Signed off 2026-10-28** (decisions #1 to #8 below, every recommendation taken). Release `1.1.0`.
Amendment for [#294](https://github.com/ddeuchert/logaperture/issues/294) (decisions #9 to #13, and a change to #1):
**signed off 2026-10-30**, every recommendation taken. See [Amendment: lists with extra handlers](#amendment-lists-with-extra-handlers-issue-294).

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §15.9 (Quarkus), §17.1 (1.1.0).
Builds on: [`quarkus-support.md`](quarkus-support.md) (Q3, which shipped type-based names and left
this open), [`quarkus-extension.md`](quarkus-extension.md) (the same engine runs in the extension),
[`wildfly-early-handler-names.md`](wildfly-early-handler-names.md) and
[`handler-floor-control.md`](handler-floor-control.md) (the WildFly behaviour this brings Quarkus
level with). Evidence: [`doc/spikes/quarkus-jvm-mode.md`](../spikes/quarkus-jvm-mode.md) finding 6,
and the experiment under "What is already established". Tracking issue:
[#222](https://github.com/ddeuchert/logaperture/issues/222).

## Functional summary

After this feature, the user will be able to:

- See a handler by the name written in `application.properties`: with
  `quarkus.log.handler.file."AUDIT".path=…`, `logctl list handlers` shows `AUDIT`, not `file-2`.
- Raise or lower one such handler by that name: `logctl set handler AUDIT DEBUG`, as on WildFly.
- Name Quarkus handlers in a vendor defaults file, and in a library recipe, the same way as for
  WildFly.
- Keep getting `console`, `file`, `syslog` and `socket` for Quarkus's own unnamed handlers.
- Be told when a name could not be worked out, with `list handlers` still showing the handler under
  its type name.

Guide pages this changes: `get-started/install-quarkus.md` ("Handler names" and the limits list);
the vendor defaults page where it says handler names differ by platform, if it does; the changelog.
No command or option is added, so `reference/logctl.md` does not change.

Not part of this feature: asynchronous handlers (`quarkus.log.*.async.enable`, still unsupported);
a named handler that no category and not the root uses (Quarkus attaches it to nothing, so it logs
nothing); custom handler types beyond the four Quarkus ships.

## What is already established

Quarkus keeps no handler name on the running handler. Its logging setup builds a map of name to
handler while it starts and throws the map away. So the names have to come from configuration. The
experiment below answers the open question in the issue (does the order of the handlers on a logger
match the configured list?). It ran the sample application (Quarkus 3.39.5, JDK 21, JBoss LogManager
3.0.6) with two named file handlers and one named console handler, on two categories and on the
root, and a probe agent that printed each logger's handlers.

- **A category's handlers are in the configured order.**
  `quarkus.log.category."org.example".handlers=ZETA,ALPHA,SIDE` gave a logger with, in this order, the
  `ZETA` file handler, the `ALPHA` file handler and the `SIDE` console handler. `ZETA` was `file-2` and
  `ALPHA` was `file-3` in `list handlers`, matching their paths.
- **A named handler is one shared instance.** `ALPHA` listed on a second category was the same object
  on both loggers.
- **Root handlers come after the unnamed defaults.**
  `quarkus.log.handlers=ROOTC,ROOTF` put the console and file handlers for those two after
  Quarkus's own console and file handlers, inside the root `QuarkusDelayedHandler`, in the configured
  order.
- **Extension handlers come before the named root handlers.** Read from `LoggingSetupRecorder.initializeLogging`
  in 3.39.5 and 3.33.4 (bytecode, the same in both): the root list is built as Quarkus's console, file,
  syslog and socket handlers, then the handlers extensions contribute (OpenTelemetry, Sentry and the
  like), then the named handlers from `quarkus.log.handlers`, and is then set on the delayed handler
  in one call. So at start-up the named root handlers are always the last *n*. A handler added after
  that (by application code, or a library calling `addHandler`) goes after them.
- **A named file handler needs `enable=true`.** Without it Quarkus logs "Handler with name 'ZETA' is
  linked to a category but not configured" and attaches nothing. A named console handler does not.
- **Everything is already found.** `list handlers` lists all of these today under numbered type names,
  with their paths. Only the name is missing.
- Reading the configured names works with `ConfigProvider.getConfig(loader)` from the agent, where
  `loader` is the class loader that defines `ConfigProvider` (spike finding 6). The extension has the
  application's class loader to hand.

## Design

A `QuarkusHandlerNameResolver`, in `logaperture-container-quarkus`, installed where
`QuarkusEngine.installSystemContext` creates the adapter today, so the agent and the extension both
get it. It implements the existing `HandlerNameResolver` and wraps `TypeHandlerNameResolver`.

1. Read the configuration: the names under `quarkus.log.handler.<type>."<name>"`, the `.handlers`
   list of every `quarkus.log.category."<category>"`, and `quarkus.log.handlers` for the root. By
   reflection on the `ConfigProvider` found through the right class loader. Nothing is read before
   Quarkus has activated its delayed handler.
2. For each configured list, walk the live handlers in step: the logger's handlers for a category;
   the last *n* children of the root delayed handler for the root, *n* being the length of the list.
   The *i*th name goes with the *i*th handler, if the handler is of the type the name was configured
   under (a `file` name needs a file handler). On any mismatch the whole list is skipped, and its
   handlers keep their type names.
3. A handler named this way keeps that name while the instance lives (as `TypeHandlerNameResolver`
   already does for its names). Every other handler goes on to the type resolver, and the unnamed
   defaults stay `console`, `file`, `syslog` and `socket`.
4. If the configuration cannot be read at all, everything stays type-named and `doctor` says so
   once, as it does today when the early re-sweep trigger is missing.

## Decisions

| # | Question | Options | Recommendation |
|---|---|---|---|
| #1 | How a name is tied to a handler | (a) By position in the configured `.handlers` list, checked against the handler's type; (b) by attributes (a file's path, a console's format); (c) both, with attributes as a tie-break | **(a)**. The experiment shows the order is reliable and the instance is shared, and it is exact for any number of handlers of one type, where (b) cannot tell two console handlers with the same format apart. The type check catches the cases where Quarkus changes behaviour. (c) is more code for a case (a) already decides. | *Amendment (#294, signed off 2026-10-30):* **(c)**. Position stays the rule, but a pair must also agree on the handler's attributes where the configuration says what they are (#10), and the position is anchored where Quarkus put the handlers, not where they are now (#9). Position and type alone can attach a name to the wrong file once another handler is added after start-up.
| #2 | Names for handlers Quarkus creates without a name | (a) Unchanged: `console`, `file`, `syslog`, `socket`; (b) also show them as `<type>` only when no named handler of that type exists | **(a)**. Nothing changes for users who name nothing, and the unnamed defaults are still one per type. |
| #3 | A configured name that equals a type name (a handler called `console`) | (a) The configured name wins and the unnamed default takes the next free number; (b) the unnamed default keeps `console` and the configured one is shown as `console-2`; (c) refuse to name it and keep the type name | **(a)**. The name in `application.properties` is what the user will type. The collision is rare, and `list handlers` shows paths, so the two can be told apart. |
| #4 | Case of a name | (a) Exactly as written in the configuration, case-sensitive; (b) fold to one case | **(a)**. WildFly's resolver shows names as configured, and `set handler AUDIT` should not guess at `audit`. | *Review amendment:* the name shown is the one in the `handlers` list, exactly as written, but it is matched to its handler definition ignoring case, because an environment variable (`QUARKUS_LOG_HANDLER_FILE__AUDIT__ENABLE`) cannot carry case and MicroProfile Config reports the definition as `"audit"`.
| #5 | Where the configuration is read | (a) By reflection on `ConfigProvider`, from the class loader that defines it (found from the loaded classes in the agent; the extension passes the application's); (b) parse `application.properties` and the environment ourselves | **(a)**. It sees every source Quarkus does (profiles, environment variables, ConfigMaps, which the Azure and Kubernetes targets use), and it adds no compile-time dependency. (b) would miss those. |
| #6 | When it is read and how often | (a) Once the delayed handler is active, then again only when the set of live handlers changes (a dev-mode reload, a handler replaced); (b) on every sweep | **(a)**. Reading config every 30 seconds buys nothing, and a changed configuration reaches a running Quarkus only through a reload. | *Review amendment:* a successful read is kept (the configuration does not change while the JVM runs); an unreadable one is retried, and a type name already given is dropped if a configured name later claims it. One property that cannot be read (an unexpandable `${...}`) costs only that property.
| #7 | Tested versions | (a) The same pair as Q4: 3.39.x and the 3.33 LTS stream, in the IT; other versions degrade to type names with the `doctor` line | **(a)**. It is the commitment already made for Quarkus support. |
| #8 | What persists | (a) Nothing new; a sticky handler override is saved under the handler's name as now | **(a)**. 1.1.0 is the first release with Quarkus support, so no `file-2` override is saved anywhere that a new name could orphan. If this ever ships after 1.1.0 it needs a migration note instead. |

## Known limits (PR review, not changed here)

- A list is matched by position and type (#1). Root names are taken from the last *n* handlers inside the delayed
  handler, so a handler added there after start-up can shift them; a category whose logger carries an extra
  handler is skipped (its handlers keep type names, with no `doctor` line). *Addressed by the amendment below
  (#294).*
- Whether a named `socket`/`syslog` handler is on by default is assumed from the tested versions.
- `TypeHandlerNameResolver.resolve(List, Set)` is public because the Quarkus module is in another package.

## Testing

- Unit tests for the resolver against fake handlers and a fake configuration: the order match; a
  type mismatch skips the list; a shared instance across categories; root handlers after defaults;
  the collision rule (#3); a configuration that cannot be read; a reload that replaces handlers.
- The sample Quarkus application gets two named handlers in its test configuration, and `QuarkusIT`
  asserts `list handlers` shows the names and `set handler <name> DEBUG` reaches the right file, on
  3.39.x and on 3.33. The same IT runs with the extension.
- A real run on the dev environment with named console and file handlers, for the guide's example
  output.

## Amendment: lists with extra handlers (issue #294)

Tracking issue: [#294](https://github.com/ddeuchert/logaperture/issues/294). Changes decision #1 from (a) to (c). Release `1.1.0` (#13).

### Functional summary

After this amendment, the user will be able to:

- Rely on `logctl set handler AUDIT DEBUG` reaching the `AUDIT` file, even when something added another
  handler to the application after it started.
- Keep seeing configured names on a category that has an extra handler, instead of the names quietly
  falling back to `file-2`.
- Run `logctl doctor` and be told which configured handler names could not be matched, and why.

Guide pages this changes: `get-started/install-quarkus.md` ("Handler names": the sentence about a list
that does not line up, and what `doctor` reports); `reference/doctor-checks.md` (`quarkus.handler-names`,
which today says a list that does not line up is skipped without a finding); the changelog. No command or
option is added.

### The problem

Decision #1 pairs the *i*th configured name with the *i*th handler, checked by type only. Two cases
go wrong:

- **Root: wrong handler, no warning.** Root names come from the last *n* children of the delayed
  handler. A handler added to it after start-up is now last, so every root name moves one handler along.
  If the types still line up (two file handlers, say), the names are given out in the wrong order, and
  `set handler AUDIT DEBUG` changes another file. Nothing reports it.
- **Category: skipped, no warning.** A category logger with one handler more than its list fails the
  count check, so the whole list keeps type names, and `doctor` says nothing.

Issue #294 named extension handlers (OpenTelemetry, Sentry) as the cause. They are not: Quarkus adds
them before the named root handlers (see "What is already established"). The cause is a handler added
after start-up. That is rarer, so this is a fix for a narrow case, not a redesign.

### Design

Three changes to design step 2, so the risk is both reduced and reported:

1. **Anchor where Quarkus put the handlers (#9).** The root list is matched against the last *n*
   children of the delayed handler *as they were the first time names are resolved after Quarkus
   activated it*. Handlers added later go after that snapshot, so they no longer shift the names. A
   category list is matched against the first *n* handlers on its logger, which is where Quarkus puts
   them, so a handler added after them no longer makes the list fail. The snapshot is taken again only
   when none of the handlers the names went to is left (a dev-mode reload replaces them), not when
   handlers are added, and not when some other handler is removed: a removed handler keeps its place in
   the snapshot, so the others keep theirs. An empty root is never kept as the snapshot. Names are
   resolved only once Quarkus has activated the delayed handler (#6); when there is no delayed handler
   to look into (a Quarkus version that names it differently), `doctor` says so at `INFO` instead of
   blaming the list.
2. **Check attributes the configuration states (#10).** A pair must agree on type, as now, and also on
   each attribute the configuration gives for that name: a file handler's path, a syslog or socket
   handler's host and port, and a console handler's format if the name sets one. An attribute that is
   not configured is not checked (Quarkus's default applies, and defaults change between versions).
3. **Report what could not be matched (#12).** `doctor` reports each list that was skipped, and each
   list that was matched by position and type alone while the logger has handlers outside the matched
   ones (the case the attribute check cannot rule out, typically console handlers).

When a pair fails a check, the whole list keeps type names, as now (#11).

### Decisions

| # | Question | Options | Recommendation |
|---|---|---|---|
| #9 | Which handlers a list is matched against when a logger has more than the list names | (a) A snapshot: the root's last *n* handlers as they were when Quarkus activated the delayed handler, a category's first *n*; (b) slide over every position and take the one where all checks pass; (c) unchanged: the root's last *n* now, a category's handlers only if the count is exact | **(a)**. It follows how Quarkus builds the lists: named root handlers last at start-up, anything later appended. (b) can match in more than one place when the checks cannot tell handlers apart (two consoles), and then has to guess. (c) is the bug. |
| #10 | Which attributes a pair must agree on | (a) A file's path, a syslog or socket handler's host and port; console by type only; (b) as (a), and a console's format when the name configures one; (c) as (b), and the level | **(b)**. A named console handler usually has its own format, and the configured pattern is still readable under the formatters wrapped around it: LogAperture's own (`trim`, `top`) and Quarkus's banner formatter. A console whose formatter is not a pattern (a JSON console, where `format` does not apply) is matched by type alone. Not the level (c): `set handler` changes it, so it would stop matching after the first change. A path is compared as an absolute path, as Quarkus resolves a relative one against the working directory. |
| #11 | What happens to a list when one pair fails a check | (a) The whole list keeps type names, as now; (b) the pairs that pass are named and the rest keep type names | **(a)**. A failed check means the order is not what Quarkus normally produces, so the pairs that passed may be right only by chance (a console that passed by type). The `doctor` line (#12) says which list and why. |
| #12 | How `doctor` reports it | (a) The existing check `quarkus.handler-names`: `WARNING` for a skipped list (naming the list, the expected types and the handlers found), `INFO` for a list matched by position and type alone next to extra handlers; (b) a new check id for alignment; (c) no `doctor` line, only the guide | **(a)**. The user sees one check about handler names, and it is already on `reference/doctor-checks.md`. `WARNING` because `set handler AUDIT` fails with "unknown handler" until it is fixed. The unreadable-configuration case stays `INFO`. |
| #13 | Which release | (a) `1.1.0`, with #222; (b) a `1.1.x` patch; (c) `1.x`, unscheduled | **(a)**. #222 has not shipped, so this changes no released behaviour and needs no migration note (#8). A silent skip in a feature that is new in 1.1.0 is better fixed before anyone relies on it. If 1.1.0 is cut first, (b). |

### Not covered

- A handler added to a category logger *before* Quarkus attaches its own (application code that runs
  earlier) sits in the first *n*: Quarkus attaches a category's handlers with `addHandler`, after
  whatever is there (`LoggingSetupRecorder.addNamedHandlersToCategory`, 3.39.5). The attribute check
  catches it for file, syslog and socket handlers; for console handlers it can still go wrong, and
  `doctor` reports that list at `INFO` (#12). The root does not have this case: Quarkus sets the delayed
  handler's children in one call, replacing anything there before.
- The root snapshot is taken the first time names are resolved after Quarkus activates its delayed
  handler, which is as Quarkus's own start-up finishes and before application code runs. A root handler
  that gets in before that (application code running inside Quarkus's logging set-up) is in the
  snapshot. The attribute check catches it for file, syslog and socket handlers. For a console handler
  matched by type alone it can still take a name, and `doctor` does not report it: the `INFO` line
  (#12) looks for handlers after the matched ones, and this one is among them. Rare enough to leave.
- A host is compared without a DNS lookup, so a configured endpoint whose host is a name, on a handler
  made from an address literal, is checked by port alone.

### Testing

- Unit tests for the resolver: a handler added to the root after start-up leaves the names where they
  were; a handler added to a category after its list still names the list; a file pair whose path does
  not match skips the list; a console pair with a configured format that does not match skips the list;
  an unconfigured attribute is not checked; a reload that replaces the root handlers takes the snapshot
  again, while removing some other root handler does not; a root seen empty before Quarkus attached
  anything is not kept; a missing delayed handler is reported at `INFO`; an invalid configured path
  costs only that pair its check; a console format is compared as written, spaces included; and the
  `doctor` findings for each case (#12), with their severities.
- `QuarkusIT`, on 3.39.x and 3.33: the test asks the sample application, through an HTTP endpoint it
  has for this, to add a file handler to the root after start-up, and `set handler <name> DEBUG` still
  reaches the right file.
- A real run on the dev environment, for the updated guide text and the `doctor` example output.
