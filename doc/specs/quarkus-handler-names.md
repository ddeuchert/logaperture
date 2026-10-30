# Quarkus: configured handler names

Status: **Signed off 2026-10-28** (decisions #1 to #8 below, every recommendation taken). Release `1.1.0`.

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
| #1 | How a name is tied to a handler | (a) By position in the configured `.handlers` list, checked against the handler's type; (b) by attributes (a file's path, a console's format); (c) both, with attributes as a tie-break | **(a)**. The experiment shows the order is reliable and the instance is shared, and it is exact for any number of handlers of one type, where (b) cannot tell two console handlers with the same format apart. The type check catches the cases where Quarkus changes behaviour. (c) is more code for a case (a) already decides. |
| #2 | Names for handlers Quarkus creates without a name | (a) Unchanged: `console`, `file`, `syslog`, `socket`; (b) also show them as `<type>` only when no named handler of that type exists | **(a)**. Nothing changes for users who name nothing, and the unnamed defaults are still one per type. |
| #3 | A configured name that equals a type name (a handler called `console`) | (a) The configured name wins and the unnamed default takes the next free number; (b) the unnamed default keeps `console` and the configured one is shown as `console-2`; (c) refuse to name it and keep the type name | **(a)**. The name in `application.properties` is what the user will type. The collision is rare, and `list handlers` shows paths, so the two can be told apart. |
| #4 | Case of a name | (a) Exactly as written in the configuration, case-sensitive; (b) fold to one case | **(a)**. WildFly's resolver shows names as configured, and `set handler AUDIT` should not guess at `audit`. | *Review amendment:* the name shown is the one in the `handlers` list, exactly as written, but it is matched to its handler definition ignoring case, because an environment variable (`QUARKUS_LOG_HANDLER_FILE__AUDIT__ENABLE`) cannot carry case and MicroProfile Config reports the definition as `"audit"`.
| #5 | Where the configuration is read | (a) By reflection on `ConfigProvider`, from the class loader that defines it (found from the loaded classes in the agent; the extension passes the application's); (b) parse `application.properties` and the environment ourselves | **(a)**. It sees every source Quarkus does (profiles, environment variables, ConfigMaps, which the Azure and Kubernetes targets use), and it adds no compile-time dependency. (b) would miss those. |
| #6 | When it is read and how often | (a) Once the delayed handler is active, then again only when the set of live handlers changes (a dev-mode reload, a handler replaced); (b) on every sweep | **(a)**. Reading config every 30 seconds buys nothing, and a changed configuration reaches a running Quarkus only through a reload. | *Review amendment:* a successful read is kept (the configuration does not change while the JVM runs); an unreadable one is retried, and a type name already given is dropped if a configured name later claims it. One property that cannot be read (an unexpandable `${...}`) costs only that property.
| #7 | Tested versions | (a) The same pair as Q4: 3.39.x and the 3.33 LTS stream, in the IT; other versions degrade to type names with the `doctor` line | **(a)**. It is the commitment already made for Quarkus support. |
| #8 | What persists | (a) Nothing new; a sticky handler override is saved under the handler's name as now | **(a)**. 1.1.0 is the first release with Quarkus support, so no `file-2` override is saved anywhere that a new name could orphan. If this ever ships after 1.1.0 it needs a migration note instead. |

## Known limits (PR review, not changed here)

- A list is matched by position and type (#1). Root names are taken from the last *n* handlers inside the delayed
  handler, so a handler another extension adds there can shift them; a category whose logger carries an extra
  handler is skipped (its handlers keep type names, with no `doctor` line).
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
