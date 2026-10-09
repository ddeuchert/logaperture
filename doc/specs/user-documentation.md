# User documentation

Status: **signed off 2026-10-08, decisions U1–U12 all agreed as recommended.** Slice 1 (pipeline)
implemented. Slice 2 (`logctl help`, decisions H1–H7) signed off and implemented 2026-10-08. Slice 3
(beta-1 pages) implemented 2026-10-09.
Issue: [#159](https://github.com/ddeuchert/logaperture/issues/159).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §17.1, "User documentation"
(the tool, layout and hosting decisions this spec builds on) and the release table (`1.0.0-beta.1`:
"docs site live").
Related: [`distribution-bundle.md`](distribution-bundle.md) (the zip the rendered guide ships
inside), [`cli-transport.md`](cli-transport.md) §6.2 (the synopsis lines the command reference is
built from), `USER_GUIDE_NOTES.md` (absorbed and deleted by this work).

## Functional summary

After this feature, the user will be able to:

- Read the LogAperture guide at `logaperture.org`: install the agent, run a first `logctl`
  session, and find how to do a task without reading the design documents.
- Pick the guide for the version they run (`1.0`, `1.1`, …) or the `dev` guide for what is on
  `develop`, from a version menu on every page.
- Open the same guide from `docs/index.html` in the release zip, on a machine with no network
  path out, with search still working.
- Look up any `logctl` command, agent option or `-Dlogaperture.*` property on one reference
  page, and trust that it matches the `logctl --help` of that version.
- Run `logctl help <command>` (e.g. `logctl help add rule`), or add `--help` to any command
  (`logctl set logger --help`), to get the same text the reference page shows for that command.
- Get a short `logctl --help` that fits on a screen, and, after a mistyped command, see just that
  command's forms instead of the whole help.

## Why

The user-facing knowledge is spread across five places, none of them written for a user who
didn't build the tool: the README's feature list, `doc/wildfly-test-drive.md`, the bundle's
`INSTALL-wildfly.md`, a 200-line `logctl --help`, and `USER_GUIDE_NOTES.md`. The design spec is
the only place several things are explained (configuration layers §6.6, the safety rules §9), and
it is written for implementers.

1.0 starts the §11.1 compatibility promise. What we promise has to be written down somewhere a
user reads, and beta 1 (Oct 15) is when outside testers arrive.

## What §17.1 already decided

Not reopened here:

- Markdown in `guide/`, separate from `doc/` (design docs stay where they are).
- MkDocs Material, versioned with `mike`: one version per minor, plus `dev` from `develop`, with
  `latest` aliased.
- GitHub Pages at `logaperture.org`.
- The rendered HTML ships inside the release zip.
- The command reference is generated from `logctl`'s own help.
- `USER_GUIDE_NOTES.md` is absorbed.
- Once `guide/` exists, a PR that changes user-visible behaviour updates it in the same PR.

## Readers

| Reader | Wants | Comes in through |
|---|---|---|
| **Operator** | Tune a running system: raise a level for 30 minutes, silence a noisy logger, find where the disk is going. | Install, Quick start, How-to |
| **Vendor** | Ship a product with sane logging defaults; tune in a sandbox, export a vendor defaults file. | Vendor guide |
| **Library author** | Ship a `recipes.yaml` so operators can switch on the right loggers for their library. | Recipes for library authors |
| **Evaluator** | Decide whether to adopt it: what it does, what it costs, what it will never do. | Home, Overhead, Security |

The operator is the default reader. Vendor and library-author material lives in its own section
so the operator path doesn't have to step around it.

## Site layout (proposed, U2)

```
guide/
  index.md                     Home: what it is, what it will never do, support matrix
  get-started/
    install-wildfly.md         from INSTALL-wildfly.md
    install-plain-jvm.md       from DEVELOPMENT.md "Controlling a running JVM"
    quick-start.md             from wildfly-test-drive.md, trimmed to ~10 minutes
  how-to/
    raise-a-level.md           set logger, tiers, expiry, handler floors, AUTO
    find-a-logger.md           list loggers, patterns, the abbreviated-category problem
    silence-noise.md           add rule drop / trim, alter, reset; protected categories
    find-disk-usage.md         top, doctor
    storms.md                  enable/disable storms, reading the report
    undo-changes.md            status, reset forms, --include-sticky, --to-native
    recipes.md                 list / show / apply / reset recipe
    several-jvms.md            discovery, --pid, the picker
    scripting.md               --json, --yes, exit codes, non-terminal behaviour
  concepts/
    configuration-layers.md    §6.6 diagram and tables, lifted as-is
    tiers-and-expiry.md        session / for / sticky, the state file
    rules.md                   gate vs render, inheritance, useParentRules, safety set
    security.md                attach model, UID gate, audit trail, no sockets
    agent-order.md             -javaagent ordering (from USER_GUIDE_NOTES)
  vendors/
    vendor-defaults.md         the file, the four choices per entry, export
    library-recipes.md         recipes.yaml, namespaces, class-path location
  reference/
    logctl.md                  generated (U4)
    agent-options.md           agent arguments and -Dlogaperture.* properties
    json-output.md             --json shapes covered by §11.1
    file-formats.md            vendor defaults, recipes.yaml, state file (read-only note)
    doctor-checks.md           every check id, severity, what it means, the fix
    audit-log.md               line format
  troubleshooting.md           -Dlogaperture.disabled, diagnostics level, common failures
  overhead.md                  published numbers (§10, #128)
  release-notes.md             from CHANGELOG.md
```

Shape follows the four kinds of documentation (tutorial / how-to / concepts / reference). How-to
pages are named for the task, not the command, because the user arrives with a task.

## Decisions

Each is recorded with the options considered; the outcome is in
[Decisions (signed off 2026-10-08)](#decisions-signed-off-2026-10-08).

### U1 — What "docs site live" means at beta 1

Beta 1 is Oct 15, seven days out. §17.1 lets docs keep changing through the freeze.

- **(a) Recommended.** Beta 1: site and pipeline live, with Home, both installs, Quick start,
  Configuration layers, and the generated `logctl` reference. Everything else in the layout by
  `1.0.0-rc.1` (Nov 9), with stub pages that say "coming in rc.1" so the navigation is final
  from day one.
- (b) The whole layout by beta 1. Not realistic next to the freeze work.
- (c) Site at rc.1 only; beta testers use the README and test drive. Breaks the §17.1 promise.

### U2 — Site layout

The tree above. Open for edits: page names, merges, anything missing. Particular questions:
does `concepts/security.md` duplicate the §9.12 threat model (deliverable for 1.0 anyway) or link
to it? Recommended: the guide page is a user-level summary and links to the threat model.

### U3 — Hosting layout on `logaperture.org`

- **(a) Recommended.** `mike` owns the whole site: `logaperture.org/` redirects to `/latest/`,
  versions at `/1.0/`, `/dev/`. No separate landing page; the guide's Home is the landing page.
- (b) A hand-made landing page at `/`, docs under `/docs/<version>/`. More to maintain; only
  worth it if the site will host more than the guide (blog, downloads page).

Needs the Pages `CNAME` and DNS records alongside the existing Maven Central TXT record.

### U4 — Generating the command reference

`logctl --help` today is one block: 29 synopsis lines, an options list, then prose paragraphs
that each cover a group of commands. There is no per-command help to generate pages from.

- **(a) Recommended.** Restructure `HelpText` into one entry per command group (synopses,
  description, options that apply, one or two examples). `logctl --help` prints the short
  synopsis list plus "`logctl help <command>` for more"; `logctl help <command>` prints one
  entry; a build step writes `guide/reference/logctl.md` from the same entries. One source,
  three outputs, and a reviewer sees help and docs change together.
  Cost: ~1–2 days, and it is a user-visible CLI change landing at the freeze (see U5).
- (b) Dump the current `--help` verbatim into a page, inside a code block. Cheap and honest, but
  unreadable as a reference and not linkable per command.
- (c) Hand-write the reference; a test asserts every line of `HelpText.SYNOPSES` appears in it.
  Cheapest that reads well, but two texts to keep in step — exactly what §17.1 meant to avoid.

### U5 — `logctl help <command>` and the feature freeze

Only if U4 (a). It is a new verb, so strictly a feature.

- **(a) Recommended.** Land it before Oct 15 as part of beta 1; it touches only `HelpText`,
  `Parser` and a test, not the agent.
- (b) Treat it as documentation, allowed through the freeze.
- (c) Generate the reference from the restructured entries, but defer the `help` verb to 1.1.

### U6 — The other references: generated or checked

Agent options and properties (19 `-Dlogaperture.*` properties today, two agent arguments),
`doctor` check ids and `--json` fields are all in the §11.1 contract review.

- **(a) Recommended.** Hand-written pages, each with a drift test in the module that owns the
  names: every property name the code reads appears in `agent-options.md`, every doctor check id
  appears in `doctor-checks.md`. The contract review (before rc.1) uses these pages as its
  checklist, so it is done once.
- (b) Generate them from a registry in code. Needs a registry first; worth it later, not now.

Properties that are tuning knobs rather than contract (`storm.normalizationCacheSize`,
`wildfly.handlerNames.debug`, …) are listed in an "Internal, may change" table, not left out —
users find them in `env` output and bug reports anyway.

### U7 — Building the site, and getting it into the zip

MkDocs is Python; the build is Maven.

- **(a) Recommended.** The release workflow builds the site (`pip install` pinned
  `requirements.txt`, `mkdocs build`) before `mvn package`, and `logaperture-dist` copies
  the built site into `docs/` when it exists. A local `mvn package` without it still builds a zip,
  with a `docs/README.md` that points at `logaperture.org`. Java developers need no Python.
- (b) A Maven `-Pdocs` profile runs `mkdocs` via `exec-maven-plugin`. Same result, but Python
  becomes a Maven concern.

Offline: Material's `offline` plugin, so search and navigation work from `file://`. Mermaid is
bundled, not pulled from a CDN.

### U8 — Publishing and versions

- `dev` is deployed by CI on every push to `develop` that touches `guide/`. (The generated
  command reference is committed under `guide/`, slice 2 H6, so a help change counts.)
- A release tag deploys `<major>.<minor>` (`1.0`).
- **Recommended for the beta period:** deploy `1.0` from the beta and rc tags with a banner
  ("pre-release — 1.0.0-beta.1"), and point `latest` at it from beta 1. Before GA there is no
  other version to send people to. The banner comes off at the `1.0.0` tag.
- Patch releases (`1.0.1`) redeploy `1.0`. A doc fix that can't wait for the next patch is
  deployed by running the docs workflow by hand against a ref, naming the version to replace.

### U9 — What happens to the existing documents

| Document | Fate |
|---|---|
| `USER_GUIDE_NOTES.md` | Absorbed into the pages named in the layout, then deleted. |
| `doc/wildfly-test-drive.md` | Becomes `get-started/quick-start.md`; the old file becomes a one-line pointer for anyone holding an old link. |
| `logaperture-dist/src/dist/INSTALL-wildfly.md` | Becomes `get-started/install-wildfly.md`; the zip ships the rendered guide instead. |
| `README.md` | Keeps the pitch, the status box and the design principles; the feature list and "Try the alpha" shrink to a few lines and link to the guide. |
| `DEVELOPMENT.md` | Stays, for contributors. Its "Controlling a running JVM" section moves to the plain-JVM install page and is replaced by a link. |
| `doc/` design docs | Stay. The guide links to the spec only for the threat model and overhead method, never for how to use a command. |
| `CHANGELOG.md` | Stays the source; the site includes it. |

### U10 — Keeping the guide current

§17.1's rule ("a PR that changes user-visible behaviour updates `guide/` in the same PR") is
added to `CLAUDE.md` as a development standard next to the spec rule, and to a PR template
checkbox. A feature spec's Functional summary names the guide page(s) it changes. Recommended:
no CI enforcement beyond the drift tests in U4/U6 — "user-visible" is a judgement call.

### U11 — Platform coverage in the text

1.0 covers WildFly fully and plain JVM + Logback for levels only.

- **(a) Recommended.** One support matrix on Home (feature × platform), and an admonition on each
  page whose feature doesn't work everywhere ("JUL / JBoss LogManager only in 1.0"). Pages don't
  fork per platform.
- (b) Per-platform sections in every page. Clearer for a Logback user, but most sections would
  read "not yet".

### U12 — Examples and their output

Examples show real `logctl` output, which changes.

- **(a) Recommended for 1.0.** Hand-captured from the WildFly dev environment, with the version
  noted in a page comment. A re-capture pass is part of the rc.1 checklist.
- (b) Capture output in `logaperture-it` and include it with `--8<--` snippets, so CI fails when
  it goes stale. The right end state; deferred to 1.x as its own issue.

## Slices

1. **Pipeline (beta 1).** `guide/` skeleton with final navigation and stubs, `mkdocs.yml`,
   pinned `requirements.txt`, Pages workflow with `mike`, `CNAME`, offline build into the zip.
2. **Command reference (beta 1).** U4/U5 per sign-off.
3. **Beta-1 pages.** Home with support matrix, both installs, Quick start, Configuration layers.
   README and bundle README point at the site.
4. **Everything else (by rc.1).** How-to, concepts, vendor, remaining reference with drift
   tests; `USER_GUIDE_NOTES.md` deleted; `CLAUDE.md` rule and PR template.

## Slice 1: the pipeline

Nothing here is user-visible content yet; it is the machinery every later slice writes into.

**Files.**

| Path | What |
|---|---|
| `mkdocs.yml` (repo root) | Site config. `docs_dir: guide`, `site_dir: site`. Material theme, light/dark toggle, `navigation.tabs`, `navigation.sections`, `search.suggest`, `content.code.copy`. `extra.version.provider: mike`. The full navigation of the [layout](#site-layout-proposed-u2). |
| `requirements-docs.txt` | `mkdocs`, `mkdocs-material` and `mike`, pinned to exact versions, so the site builds the same in CI and on a laptop. |
| `guide-overrides/` | Two theme overrides: the pre-release banner, and a footer naming the version. |
| `guide/**` | Every page in the layout. Pages not written yet are stubs: a title, one line on what the page will cover, and a note that it is being written for 1.0. |
| `.github/workflows/docs.yml` | Builds and deploys the site (below). |
| `.gitignore` | `site/` and `.venv-docs/`. |
| `logaperture-dist/src/assembly/dist.xml` | Copies `site/` into `docs/` when present. |
| `logaperture-dist/src/dist/docs-README.md` | Goes in as `docs/README.md` always: open `index.html` here, or read the guide at `logaperture.org`. |
| `DEVELOPMENT.md` | A "User guide" section: how to preview the guide locally. |

The bundle kept `docs/INSTALL-wildfly.md` until slice 3 wrote the WildFly install page, which
removed it.

**Two builds from one config.** The deployed site is a normal MkDocs build. The zip's copy is
built with `OFFLINE=true`, which turns on Material's `offline` plugin (links to `.html` files,
search from `file://`) and `privacy` plugin (downloads Google Fonts, Mermaid and the other
external assets into the site, so nothing loads from the network). That adds about 4 MB to the
zip, most of it Mermaid. The version menu reads `versions.json`, which
an offline copy doesn't have, so the menu doesn't appear there; the page footer names the version
instead.

**The docs workflow** (`docs.yml`):

- On a push to `develop` that touches `guide/**`, `guide-overrides/**`, `mkdocs.yml`,
  `requirements-docs.txt` or `CHANGELOG.md` (the release notes page includes it):
  `mike deploy --push dev`. Until the first release tag there is no `latest`, so `/` redirects to
  `dev` meanwhile.
- On a pull request touching the same paths: `mkdocs build --strict` only, no deploy.
- On a `v*` tag: `mike deploy --push --update-aliases <major>.<minor> latest` and
  `mike set-default --push latest`. A tag with a pre-release suffix (`-beta`, `-rc`) deploys with
  the pre-release banner (U8); a release tag without one deploys without it.
- By hand (`workflow_dispatch`), with a ref and a version name, for an out-of-band doc fix.
- Deploys go to the `gh-pages` branch. GitHub Pages serves that branch on `logaperture.org`.
  The workflow writes a root `CNAME` on `gh-pages` if one isn't there, since `mike` only manages
  its version folders and leaves root files alone.

**The release workflow** (`release.yml`) gains two steps before the Maven build: install
`requirements-docs.txt`, then `OFFLINE=true mkdocs build`. The zip then contains the rendered
guide.

**Toolchain risk.** MkDocs 2.0 is announced as incompatible with Material and with plugins
generally. `mkdocs` is pinned at 1.6.1, so nothing changes under us; moving off MkDocs 1.x, if it
comes to that, is a post-1.0 decision.

**One-time setup, outside the repo:** in the repository's Pages settings, source = `gh-pages`
branch, custom domain `logaperture.org`, enforce HTTPS; at the DNS host, the four GitHub Pages
`A` records (and `AAAA`) on the apex and a `www` `CNAME` to `ddeuchert.github.io`.

**Verification.** `mkdocs build --strict` passes (no broken links or nav entries pointing at
missing pages), in both the normal and `OFFLINE=true` builds; `mvn -pl logaperture-dist -am
package` with a built `site/` produces a zip whose `docs/index.html` opens from disk with working
search and no network requests; without `site/`, the zip still builds and contains
`docs/README.md`.

## Slice 2: `logctl help` and the generated reference

Today `HelpText.usage()` is one 200-line block: the synopses, every option, then paragraphs that
each cover a few commands. Slice 2 splits it into **topics**, one per command or small command
group, each holding its own synopses, description, options and examples. Three things are printed
from the topics: `logctl --help`, `logctl help <command>`, and `guide/reference/logctl.md`.

### H1 — The topics

| Topic | Synopses it owns |
|---|---|
| `list loggers` | `list loggers` |
| `list handlers` | `list handlers` |
| `list rules` | `list rules` |
| `status` | `status` |
| `doctor` | `doctor` |
| `env` | `env` |
| `top` | `top` |
| `storms` | `storms`, `enable storms`, `disable storms` |
| `set logger` | `set logger` |
| `set handler` | both `set handler` forms (level and `AUTO`); also explains `ALL_HANDLERS` |
| `default-handler` | `set default-handler`, `reset default-handler`; explains `DEFAULT_HANDLERS` |
| `reset` | `reset logger`, `reset loggers`, `reset handler`, `reset handlers`, `reset rule`, `reset rules` |
| `add rule` | `add rule drop`, `add rule trim` |
| `alter rule` | `alter rule` |
| `recipes` | `list recipes`, `show recipe`, `apply recipe`, `reset recipe` |
| `export vendor-defaults` | `export vendor-defaults` |

Every synopsis belongs to exactly one topic. `reset` stays one topic because its forms share the
same rules (sticky is skipped, `--include-sticky`, `--to-native`, baseline vs native); splitting
it would repeat them six times. The vendor-defaults file and the configuration layers get one
paragraph in `reset` and a link to the guide's concepts pages, not a topic of their own.

### H2 — `logctl --help` and `logctl help`

`logctl --help` and `logctl help` with no command print the same short overview, about 50 lines:

```
logctl — runtime logging control for a running JVM

Usage:
  logctl list loggers [filter] [--show-all]
  … every synopsis, as today, grouped by topic …

Options for every command:
  --pid <n>         target this JVM instead of discovering one
  --reason <text>   why — shown in status, kept in the audit trail
  --json            machine-readable output
  --yes             never ask
  --version, -h, --help

A tier says how long a change lasts: session, for <duration> (30s, 30m, 4h, 2d) or sticky.
'set' without one means 'for 4h'.
On a terminal, list, set, reset, add rule and alter rule ask for anything left out.

'logctl help <command>' explains one command, for example 'logctl help add rule'.
Full guide: https://logaperture.org/
```

The per-command options and all the explanatory paragraphs move into the topics. The synopsis
list stays complete, so the phone test (`cli-transport.md` §6.2) still checks every form in the
overview.

### H3 — Finding a topic

`logctl help <words…>` and `logctl <words…> --help` both look up a topic from the command words:

1. The words name a topic exactly (`help reset`, `help add rule`): that topic.
2. Otherwise, the topics owning a synopsis that starts with those words. One: that topic
   (`help reset recipe` → `recipes`, `help set default-handler` → `default-handler`,
   `help enable storms` → `storms`, `help export` → `export vendor-defaults`).
3. Several (`help list`, `help set`): a short list of them, one line each, with the command to
   see each one.
4. None: "No command 'xyz'.", then the overview, exit code 2 (usage).

`--help` wins over everything else on the line, as today: `logctl set logger --help` shows the
topic and never starts a guided command or contacts a JVM. Non-command words after `--help`
(a logger name, a level) are ignored when looking up the topic.

### H4 — Usage errors

Today a usage error prints the message and then the whole 200-line help. After this slice it
prints the message, then the synopses of the topic the command words point to (H3), then
`Run 'logctl help <topic>' for more.` When no topic matches (`logctl` alone off a terminal,
an unknown command), it prints the overview instead. The exit code is unchanged (2).

### H5 — Where the text lives

A `HelpTopic` record per topic (name, one-line summary, synopses, description paragraphs,
options, examples) in a `HelpTopics` class, with the global options and the overview footer
beside it. Paragraphs are written unwrapped with Markdown code spans: `` `set logger` ``.

- On the terminal: paragraphs wrapped to 80 columns; a code span prints as `'set logger'`, which
  is how the help quotes commands today.
- In the reference page: paragraphs as written, code spans kept.

Options shared by two topics (the rule matchers in `add rule` and `alter rule`,
`--include-sticky` in `reset` and `recipes`) are defined once and referenced by both.

### H6 — Generating the reference page

`guide/reference/logctl.md` is **generated and committed**. A test in `logaperture-cli`
(`HelpReferenceTest`) renders the page from the topics and compares it with the committed file;
a difference fails the build with the command that regenerates it:

```
mvn -pl logaperture-cli test -Dtest=HelpReferenceTest -Dlogaperture.help.regenerate=true
```

So a help change and its reference change land in the same PR and show up in the same diff, CI
catches a forgotten regeneration, and the docs workflow needs no Java. The generated file starts
with a comment saying it is generated and from where.

### H7 — The reference page

One page, `reference/logctl.md`:

- An introduction: the options for every command, tiers and durations, guided commands. This is the
  overview from H2 without the synopsis list.
- A table of every topic with its one-line summary, linking to its section.
- One section per topic, anchored by its name (`#set-logger`, `#add-rule`): synopses in a code
  block, the description, an options table, examples.

How-to pages link to these anchors instead of re-explaining options.

### Tests

- Every synopsis in exactly one topic; every topic has at least one synopsis.
- Every `--option` in a topic's synopses is documented in that topic's options or the global ones.
- The phone test over every synopsis, as today. Examples are exempt: they hold real recipe ids
  (`io.undertow:sessions`) and quoted message text, which the phone test's characters forbid.
- Every example leads back to its own topic by lookup, so an example can't drift to another
  command's syntax unnoticed.
- No line of a topic is wider than 80 columns, except synopses and examples, which print as written.
- Lookup (H3): exact, unique prefix, ambiguous, unknown; `--help` after a command, after a
  guided-command prefix (`logctl set --help`), with extra words.
- Usage error output (H4) for a known and an unknown command.
- `HelpReferenceTest` (H6).

## Decisions (signed off 2026-10-08)

All twelve agreed as recommended. Numbering is stable and matches the review artifact.

| # | Decision | Agreed |
|---|---|---|
| U1 | What "docs site live" means at beta 1 | **Pipeline plus Home, both installs, Quick start, Configuration layers and the generated `logctl` reference** by beta 1; the rest by rc.1, stubbed so the navigation is final from day one. |
| U2 | Site layout | **The proposed tree.** `concepts/security.md` is a user-level summary that links to the §9.12 threat model. |
| U3 | Hosting paths | **`mike` owns the whole site**; `/` redirects to `/latest/`. |
| U4 | Command reference | **One help entry per command group**, feeding `--help`, `logctl help <command>` and the generated reference page. |
| U5 | `logctl help` and the freeze | **Lands before beta 1.** |
| U6 | Other references | **Hand-written, each with a drift test**; internal tuning properties in their own "may change" table. |
| U7 | Build and zip | **The release workflow builds the site**; Maven only copies it when present. Offline build for the zip. |
| U8 | Versions | **`dev` from `develop`, `<major>.<minor>` from tags; `1.0` with a pre-release banner and `latest` from beta 1.** |
| U9 | Existing documents | **As in the table.** |
| U10 | Keeping current | **`CLAUDE.md` rule and PR template checkbox**; no CI enforcement beyond the drift tests. |
| U11 | Platform coverage | **One support matrix on Home, admonitions on pages**; no per-platform forks. |
| U12 | Examples | **Hand-captured for 1.0**, re-captured for rc.1; IT-captured snippets are a 1.x issue. |

Slice 2, H1–H7, agreed as written on 2026-10-08: the topics in H1, the one-screen overview (H2),
topic lookup from command words (H3), usage errors showing only the command's forms (H4), topic
records with Markdown code spans (H5), the reference page generated, committed and checked by
`HelpReferenceTest` (H6), and the page layout in H7.

## Not in scope

- Translations.
- A blog or news section on `logaperture.org`.
- API documentation (Javadoc) — there is no public Java API in 1.0.
- Docs for platforms 1.0 doesn't support (Quarkus, Spring Boot, Tomcat); they get pages when
  they ship.
