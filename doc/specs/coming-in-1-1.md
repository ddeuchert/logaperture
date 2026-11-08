# A "Coming in 1.1" page in the 1.0.0 guide (issue #353)

Status: **signed off 2026-11-08** (CS1 to CS9 agreed as written). Milestone `1.0.0`; documentation only, so it may land after the feature
freeze. Written on `release/1.0` ([`release-lines.md`](release-lines.md) R2), so it is in the 1.0.0 guide.
Parent spec: [`user-documentation.md`](user-documentation.md) (the guide this adds to), and through it
[`doc/logaperture-spec.md`](../logaperture-spec.md) §17.1 "User documentation" and the 1.1.0 roadmap rows.
Related: [`release-lines.md`](release-lines.md).

## Functional summary

After this feature, the user will be able to:

- Read, in the 1.0.0 guide, what is coming in 1.1: **Quarkus** support without a `-javaagent`,
  **`logctl attach`** (try LogAperture on a running JVM with no restart), **Log4j 2** (2.20 and later),
  `java.util.logging` on a **plain JVM**, and running `logctl` with **JBang**.
- See that a 1.1.0 pre-release is planned for late November or early December, with 1.1.0 after it, and where to
  look for it.
- Find where to try early versions: the in-progress 1.1 guide now, and, as each pre-release ships, its download and
  its guide, linked from the same place on the page.
- Tell at a glance that none of this is in 1.0, so no command on the page is mistaken for one that works today.
- Find the page from the guide's home page and its own entry in the navigation.

Guide pages this changes: `coming-in-1-1.md` (new), `index.md` (one pointer), `mkdocs.yml` (the nav entry);
`DEVELOPMENT.md` gains one release step.

## Why

1.0.0 is a deliberately narrow release: WildFly and Logback. The reader who finds that their stack is Quarkus,
Log4j 2 or a plain JVM on `java.util.logging` today leaves the guide with "not supported", and has no way to know
that it is the next release. A page that says so, and says roughly when, keeps them (and the people who are
weighing LogAperture against doing nothing) watching.

## Design

### The page

`guide/coming-in-1-1.md`, titled **Coming in 1.1**, in the navigation after Overhead and before Release notes. It
opens with a short note: this page describes work in progress; none of it is in 1.0; details may change before it
ships; a pre-release is planned for late November or early December and 1.1.0 follows it; the guide's "latest" stays
on 1.0 until 1.1.0 is out. Then one short section per enhancement, each with *what you will be able to do*, *why it
matters*, and where there is one, how it will look:

| Section | What the reader learns |
|---|---|
| **Quarkus** (#114) | LogAperture on a Quarkus application in JVM mode, by adding one dependency (the extension, nothing in the launch command) or by `-javaagent`; it suits platforms where you don't control the command line, such as Kubernetes or Azure App Service. Native images can never be supported. |
| **Try it without a restart** (#273) | `logctl attach <pid>` loads the agent into a running JVM: WildFly or a plain JVM. It lasts until that JVM stops. |
| **Log4j 2** (#302) | Log4j 2.20 and later, on the class path or bundled in the application (a Spring Boot fat jar, a Tomcat webapp, a WildFly deployment with its own `log4j-core`). The commands are the ones they know. |
| **A plain JVM** (#221) | `java.util.logging` with no container: the WildFly commands work there too, except the file checks in `doctor`. |
| **JBang** (#274) | `jbang logctl@ddeuchert/logaperture`: nothing to download, which with `attach` is a no-install trial. |
| **Further out** | One sentence each, no commitment: Spring Boot, Tomcat with per-webapp Logback, configurable storm suppression, the later console. From §17.1's 1.2.0 and 1.3+ rows. |

A command shown on the page is in a `text` block, not `sh`, under a line saying it does not exist in 1.0.0, so it is
not copied and run. Links go to the public issues (a reader can follow progress there), never to guide pages that
exist only on `develop`: the 1.0 guide's strict build fails on a link to a page it does not have.

### "Try it early" (CS10 to CS12)

A box near the top of the page, under the timing paragraph, and the one part of the page that is edited at each
pre-release. Its first version links only to places that already work: the `dev` guide at `logaperture.org/dev/`
(built from `develop` on every push), the GitHub releases page (where pre-releases are listed) and Discussions,
and says that no pre-release has been published yet. There is no downloadable dev build (snapshots are published
only for labelled pull requests), so the first version offers none and does not suggest building from source.

When `1.1.0-alpha.1` is published the box is edited to name it: its download (the release tag) and its guide
(`logaperture.org/1.1/`, which a pre-release tag deploys with a pre-release banner, without moving `latest`). The
same at each beta and at 1.1.0, where it says 1.1.0 is out and points to the current guide. The edit is a docs-only
commit on `release/1.0`, published by running the *Docs* workflow from that branch for version `1.0` (its manual
run redeploys a chosen version from the branch it is run on): no release and no patch version. The guide copy
inside the 1.0.0 release zip stays as it was released. The step is in `DEVELOPMENT.md`, with the pre-release steps.

### Where it lives, and when it goes (CS2)

It is written on `release/1.0`, so the 1.0.0 guide has it, and merged forward into `develop` like any fix. On
`develop` the guide documents all of this for real, so in the forward-merge pull request, in a commit of its own,
the page's navigation entry and the `index.md` pointer are removed and the file is added to `exclude_docs`, so the
1.1 guide never publishes it. The file itself stays, so that each later edit to the box on `release/1.0` merges
forward without a modify/delete conflict. The 1.1.0 release cut deletes the file (and the `exclude_docs` line)
from `develop`; the 1.0 guide keeps the page until 1.1.0 supersedes it.

## Decisions

| # | Decision | Agreed |
|---|---|---|
| CS1 | Name and place | `guide/coming-in-1-1.md`, "Coming in 1.1", in the nav after Overhead; one line on `index.md` pointing to it. |
| CS2 | Branch and lifecycle | Written on `release/1.0`. On `develop`, unpublished by the forward-merge PR (nav entry and pointer removed, file in `exclude_docs`) and deleted at the 1.1.0 cut. Agreed with David 2026-11-08 in the shape "release/1.0, removed at 1.1.0"; amended the same day (from "deleted in the forward merge") so that later edits to the box merge forward cleanly. |
| CS3 | Dates | "A pre-release is planned for late November or early December; 1.1.0 follows it", as a plan, with no day. Agreed 2026-11-08. The roadmap rows in §17.1 on `develop` still say alpha mid/late December, beta early January, 1.1.0 late January: updated on `develop` separately (#353 follow-up), not in this change. |
| CS4 | What is on it | Quarkus, `attach`, Log4j 2, plain-JVM `java.util.logging`, JBang, and one "Further out" paragraph. Agreed 2026-11-08. |
| CS5 | Statuses | None per feature. A "built" or "in progress" label would be stale the day 1.0.0 ships, and the page cannot be updated before a patch. The page says the pre-release carries what is merged by then and that its release notes say exactly what that is. |
| CS6 | Examples | Real commands from the 1.1 guide, in `text` blocks under an explicit "not in 1.0.0" note (above). Output is shown only if it was captured from a real run, as everywhere in the guide. |
| CS7 | Where the pre-release is announced | Said generally: "announced on the GitHub releases page and in Discussions". No Maven coordinates or download steps, since they are not decided until the pre-release exists. |
| CS8 | Demo recordings | Not embedded. The guide's build requires every recording in `guide/assets/demos/` to have a story script and a Now Showing film, and the `attach` film is for 1.1. |
| CS10 | A "Try it early" box | Agreed 2026-11-08. A box near the top of the page pointing to the `dev` guide, the releases page and Discussions, and saying no pre-release is out yet; each of its links works from day one. |
| CS11 | Keeping it current | Agreed 2026-11-08. Edited at each 1.1 pre-release and at 1.1.0 (the download and its guide), as a docs-only commit on `release/1.0` published by running the *Docs* workflow from that branch for `1.0`. A step in `DEVELOPMENT.md`; no release is needed. The alternative (static links only, never edited) was declined for being less direct. |
| CS12 | No build-from-source offer | Agreed 2026-11-08. There is no packaged dev build, so the box does not send a reader to build `develop`; the alpha is the packaged download. |
| CS9 | Reviewing the wording | The wording of the page is read by David before the PR merges, since it makes public promises about timing. |

## Testing

- `mkdocs build --strict` (CI's `docs` job) passes: the page is in the nav, and links to no missing page.
- `GuideReferenceDriftTest` and the demo-casts hook are unaffected: the page documents no property, agent argument
  or `doctor` check, and embeds no recording.
- A person reads the rendered page before the PR merges (CS9).
- At the first pre-release: the box names it, its two links open, and the *Docs* run from `release/1.0` for `1.0`
  shows the edit on logaperture.org without changing `latest` (CS11). Checked by hand at that release.

## Out of scope

- Updating §17.1's dates and the rest of the roadmap on `develop`: a separate change there.
- The release-notes page and the changelog: unchanged by this page.
