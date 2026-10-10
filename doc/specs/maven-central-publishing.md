# Maven Central publishing

Status: **signed off, 2026-10-10** (M1–M12 below). Built on `feature/186-maven-central`, checked
locally end to end except the upload itself.
Issue: [#186](https://github.com/ddeuchert/logaperture/issues/186). Needed for `1.0.0-beta.1`
(Oct 15), the first Maven Central publish (§17.1 "Distribution").
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §17.1, which this amends: the
parent POM is published as well (M1).
Related: [`doc/specs/distribution-bundle.md`](distribution-bundle.md) (the release zip, now also a
Maven artifact).

## Functional summary

After this feature, the user will be able to:

- Fetch the agent, `logctl` or the release zip from Maven Central under `org.logaperture`
  (`logaperture-agent`, `logaperture-cli`, and `logaperture` as a `zip`), for example in a build
  script or a container image, instead of downloading the zip from GitHub.
- Check a release's files against GPG signatures published next to them on Maven Central.

Guide pages changed: `get-started/install-wildfly.md` and `get-started/install-plain-jvm.md`
(an "Or get it from Maven Central" tip).

For the maintainer: run the **Maven Central dry run** workflow before tagging a release. Pushing a
release tag then publishes to Maven Central as well as to GitHub Releases.

## What is published

Group `org.logaperture`, for each release version (never a `-SNAPSHOT`, M11):

| Artifact | Files | Why |
|---|---|---|
| `logaperture-parent` | `.pom` | the agent's and `logctl`'s POMs name it as their parent (M1) |
| `logaperture-agent` | `.jar` (shaded), `-sources.jar`, `-javadoc.jar`, `.pom` | the `-javaagent` jar |
| `logaperture-cli` | `.jar` (shaded), `-sources.jar`, `-javadoc.jar`, `.pom` | `logctl` |
| `logaperture` | `.zip`, `.pom` | the release zip, byte-identical to the GitHub Release asset and with the same file name (M2) |

Every file has a `.asc` signature and `.md5`/`.sha1` checksums. The agent's and `logctl`'s POMs
are the shade plugin's dependency-reduced POMs: everything first-party is inside the jar, so they
declare no runtime dependencies. Nothing else is published: the internal modules (`bridge`,
`api`, `core`, the adapters, the container integrations, `control-jmx`) exist only inside the two
shaded jars, and `logaperture-it`, `logaperture-sample-war` and `logaperture-bench` are test
fixtures.

## How it works

1. **The `release` Maven profile** (parent POM) adds sources and javadoc jars, attaches the
   release zip to the `logaperture-dist` module's artifact (`logaperture`, M2), and signs every artifact with the GPG key in
   `MAVEN_GPG_KEY` (BouncyCastle signer: no `gpg` keyring needed).
2. **`mvn -Prelease deploy -DaltDeploymentRepository=staging::file:target/staging`** writes the
   signed release into a local staging repository, never to Central. Modules that are never
   published set `maven.deploy.skip` (M4).
3. **`.github/scripts/central.sh bundle`** checks the staging repository holds exactly the files in
   the table above, each signed and checksummed, and nothing else; then zips it into a Central
   bundle.
4. **`central.sh upload`** sends the bundle to the Central Portal's Publisher API as a
   *user-managed* deployment: Central validates it and then waits. **`central.sh wait`** polls
   until it is `VALIDATED` (or fails with Central's errors printed).
5. Then either **`central.sh drop`** (the dry run: nothing is published) or **`central.sh publish`**
   followed by `wait … published` (a release).

Two workflows use it:

- **`central.yml`, the dry run**: started by hand (Actions → *Maven Central dry run*), or by
  adding the label `central dry run` to a pull request. GitHub only offers the manual start for
  workflows on the default branch, `main`, which moves only at a release. Until this lands there,
  the label is the only way to start it. It builds `develop` as the release
  version (the POM version without `-SNAPSHOT`, set in the checkout only, never committed),
  steps 1–4, then drops the deployment. Tests are skipped: CI has already run them, and the
  packaging is what is under test. The bundle is kept as a workflow artifact.
- **`release.yml`** (on a `v*` tag): the existing build becomes steps 1–3 with tests, then upload,
  validate, publish, wait until published, and only then the GitHub Release. Publishing to Central
  is the one step that can't be undone, so everything that can fail goes before it.

Secrets (repository): `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` (a Central Portal
user token), `GPG_PRIVATE_KEY` (ASCII-armored secret key) and `GPG_PASSPHRASE`.

**Prerequisites outside the repo**, which only the dry run can confirm: the `org.logaperture`
namespace is verified on the Central Portal, and the signing key's public half is on a keyserver
Central checks (`keys.openpgp.org` or `keyserver.ubuntu.com`).

## Found while building it

**The published agent jar would have been the thin one.** The agent and `logctl` set `finalName`
inside the shade plugin's configuration. When that differs from the build's own `finalName`, the
shade plugin writes the shaded jar beside the module's artifact instead of replacing it, so Maven
would have published a 15 KB agent jar with no `Premain-Class` and none of the engine. The release
zip was never affected, because it copies `target/logaperture-agent.jar` by path. Fixed by moving
`finalName` to `<build>`: the file is still `target/logaperture-agent.jar`, and it is now also the
module's artifact. `logaperture-it`, which depends on the agent at test scope, now gets the shaded
jar too.

## Decisions

All signed off 2026-10-10. M2, M8 and M10 were David's calls; the rest are as recommended.

**M1 — Publish the parent POM.** The agent's and `logctl`'s POMs name `logaperture-parent` as
their parent, and Maven resolves a POM's parent even when it needs nothing from it, so the parent
must be on Central. §17.1 lists only the agent, `logctl` and the zip. *Alternative:* the flatten
plugin, to strip the parent from the published POMs. That's one more plugin to interact with the
shade plugin's dependency-reduced POM, to save publishing one small file. *Recommended:* publish
it.

**M2 — The zip's coordinates: `org.logaperture:logaperture:<version>:zip`.** The file Central
serves is then `logaperture-<version>.zip`, the same name as the GitHub Release asset. Only the
published `artifactId` is `logaperture`. The module directory stays `logaperture-dist`, so the
repo layout and `-pl logaperture-dist` don't change. There is no single convention for this:
WildFly publishes `wildfly-dist` and Keycloak `keycloak-quarkus-dist`, but Apache Maven publishes
`apache-maven` (classifier `bin`) and Jetty `jetty-home`. Nothing else is expected to want the
plain name: a BOM or a Spring Boot starter would have its own suffix. The cost is that a
`<dependency>` on `org.logaperture:logaperture` without `<type>zip</type>` fails, looking for a
jar. That's a loud failure, and the guide gives the type. No classifier: there is only one
archive. *Rejected:* `logaperture-dist`. Coordinates are permanent once published. **Decided
(David).**

**M3 — Upload through the Publisher API with a script, not Sonatype's
`central-publishing-maven-plugin`.** Tried first. Its `skipPublishing` (0.9.0+) skips each module
outright and writes no bundle, and it needs a `central` server entry even then, so the bundle
can't be built or checked without uploading it. The script does the upload with `curl` in about
fifty lines, the same way for the dry run and the release, and checks the bundle against a fixed
list first. *Recommended:* the script.

**M4 — Opt out per module with `maven.deploy.skip`, and check the result against an allow-list.**
Each never-published module sets `maven.deploy.skip`, as `logaperture-bench` already did. A new
module is published by default, so `central.sh bundle` fails on any file not in the table above.
A module that loses its skip can't slip out unnoticed. *Recommended:* as built.

**M5 — Sources and javadoc jars are per module.** Central requires a `-sources.jar` and a
`-javadoc.jar` for each jar. The agent's hold the agent module's own 13 source files, not the
engine shaded into it. *Alternative:* the shade plugin's `createSourcesJar`, for sources that match
everything in the jar, which helps anyone stepping into the agent in a debugger. That's worth doing
later, but not needed for beta.1. Javadoc runs with `doclint` off: it's internal documentation,
not a published API, and it must not fail a release. *Recommended:* per module for beta.1.

**M6 — Signing with the BouncyCastle signer.** `maven-gpg-plugin` 3.2 reads the armored key from
`MAVEN_GPG_KEY` and the passphrase from `MAVEN_GPG_PASSPHRASE` (`bestPractices`: it refuses a
passphrase in the POM). CI then needs no `gpg` keyring import step. *Recommended:* as built.

**M7 — Order in `release.yml`: build, test, sign, validate on Central, publish to Central, then the
GitHub Release.** A tag whose Central validation fails produces no GitHub Release either. It can be
fixed and the tag pushed again, since nothing permanent has happened. *Recommended:* as built.

**M8 — No manual approval before the Central publish.** Pushing a release tag is already the
deliberate act, and the dry run comes before it. *Alternative:* a GitHub environment with a
required reviewer on the publish step: one more click per release, but a last look at a validated
deployment before it becomes permanent. **Decided (David): no gate.** Revisit the first time
a `x.y.0` has to be followed quickly by an `x.y.1`, or if a second maintainer joins.

**M9 — The dry run is manual and drops only what validated.** A `FAILED` deployment is left on the
Portal's Deployments page with its errors, and also printed in the workflow log, because Central's
guidance is to keep failed deployments if support is needed. Drop it there by hand.
*Recommended:* as built.

**M10 — No developer email in the POM.** `developers` names David Deuchert with the GitHub
profile URL, and the POM's `url` is logaperture.org, which leads to the GitHub project. Anyone who
needs to reach the project can open an issue there. Central requires a name, and an email
published there gets scraped. **Decided (David): no email.**

**M11 — No snapshots on Central.** The Portal can host snapshots, but nothing consumes them, and
they would need the namespace's snapshot switch turned on. *Recommended:* none.

**M12 — The dry run builds `develop`, not a tag.** Its point is to find packaging and credential
problems before the tag exists. It runs the version the next tag will have, so Central validates
the same coordinates the release will publish. A dropped deployment doesn't reserve or burn the
version. *Recommended:* as built.

## Testing

Done locally on the prototype, with a throwaway GPG key:

- `mvn -Prelease deploy` to a staging directory under version `1.0.0-beta.1`, then `central.sh
  bundle`: exactly the 11 files in the table, each with `.asc`, `.md5` and `.sha1`. Every signature
  verifies.
- The bundled agent jar has `Premain-Class`, all 140 `org.logaperture.core` classes and no
  Logback or JBoss LogManager classes, and is byte-identical to `target/logaperture-agent.jar`.
  `logctl`'s has `Main-Class`. The bundled zip is byte-identical to the GitHub Release zip.
- `central.sh bundle` refuses an extra module's jar, a missing signature and a `-SNAPSHOT`
  version. `wait`, `drop` and `publish` ran against a stand-in `curl`: pending, then validated;
  failed (prints Central's errors, exits 1); validated, then publishing, then published; and no
  token (exits before any request).
- The guide's command (`mvn dependency:copy … -Dmdep.stripVersion=true`) fetches
  `logaperture-agent.jar` with its `Premain-Class` from the staged repository.
- All unit tests pass with the shade change (`mvn verify -DskipITs`, 1,555 tests). CI runs the
  integration tests, including `logaperture-it` against the shaded agent.

Still to do, and possible only with the real secrets: **run the dry run**. Add the label
`central dry run` to this feature's pull request. It is the test of the namespace, the token, the key and Central's own
validation of the bundle.
