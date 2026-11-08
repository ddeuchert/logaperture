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
- Tell at a glance that none of this is in 1.0, so no command on the page is mistaken for one that works today.
- Find the page from the guide's home page and its own entry in the navigation.

Guide pages this changes: `coming-in-1-1.md` (new), `index.md` (one pointer), `mkdocs.yml` (the nav entry).

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

### Where it lives, and when it goes (CS2)

It is written on `release/1.0`, so the 1.0.0 guide has it, and merged forward into `develop` like any fix. On
`develop` the guide documents all of this for real, so the page is removed there in the forward-merge pull request,
in a commit of its own, along with its navigation entry and the `index.md` pointer. The 1.1.0 guide, built from
`develop`, therefore never contains it; the 1.0 guide keeps it until it is superseded. A later edit to the page on
`release/1.0` (a 1.0.x patch updating the dates) conflicts with that deletion on the next forward merge and is
resolved by keeping the deletion.

## Decisions

| # | Decision | Agreed |
|---|---|---|
| CS1 | Name and place | `guide/coming-in-1-1.md`, "Coming in 1.1", in the nav after Overhead; one line on `index.md` pointing to it. |
| CS2 | Branch and lifecycle | Written on `release/1.0`; removed on `develop` in the forward-merge PR (see above). Agreed with David 2026-11-08 in the shape "release/1.0, removed at 1.1.0"; the removal moves to the forward merge so that `develop`'s guide never has a page contradicting itself. |
| CS3 | Dates | "A pre-release is planned for late November or early December; 1.1.0 follows it", as a plan, with no day. Agreed 2026-11-08. The roadmap rows in §17.1 on `develop` still say alpha mid/late December, beta early January, 1.1.0 late January: updated on `develop` separately (#353 follow-up), not in this change. |
| CS4 | What is on it | Quarkus, `attach`, Log4j 2, plain-JVM `java.util.logging`, JBang, and one "Further out" paragraph. Agreed 2026-11-08. |
| CS5 | Statuses | None per feature. A "built" or "in progress" label would be stale the day 1.0.0 ships, and the page cannot be updated before a patch. The page says the pre-release carries what is merged by then and that its release notes say exactly what that is. |
| CS6 | Examples | Real commands from the 1.1 guide, in `text` blocks under an explicit "not in 1.0.0" note (above). Output is shown only if it was captured from a real run, as everywhere in the guide. |
| CS7 | Where the pre-release is announced | Said generally: "announced on the GitHub releases page and in Discussions". No Maven coordinates or download steps, since they are not decided until the pre-release exists. |
| CS8 | Demo recordings | Not embedded. The guide's build requires every recording in `guide/assets/demos/` to have a story script and a Now Showing film, and the `attach` film is for 1.1. |
| CS9 | Reviewing the wording | The wording of the page is read by David before the PR merges, since it makes public promises about timing. |

## Testing

- `mkdocs build --strict` (CI's `docs` job) passes: the page is in the nav, and links to no missing page.
- `GuideReferenceDriftTest` and the demo-casts hook are unaffected: the page documents no property, agent argument
  or `doctor` check, and embeds no recording.
- A person reads the rendered page before the PR merges (CS9).

## Out of scope

- Updating §17.1's dates and the rest of the roadmap on `develop`: a separate change there.
- The release-notes page and the changelog: unchanged by this page.
