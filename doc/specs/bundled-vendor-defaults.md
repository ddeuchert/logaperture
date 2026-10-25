# Bundled vendor defaults (Quarkus extension)

Status: **Signed off 2026-10-25** (decisions #1 to #10 below, each as its lean). Implemented on
`feature/269-bundled-vendor-defaults`; see "As built". Release `1.1.0`, with
the Quarkus extension; if the extension moves to 1.2.0 ([`quarkus-extension.md`](quarkus-extension.md)
#14), this moves with it.

Parent spec: [`quarkus-extension.md`](quarkus-extension.md) (the extension, its configuration #8).
Builds on: [`vendor-defaults.md`](vendor-defaults.md) (the file, its baseline layer, its surfaces),
[`vendor-defaults-export.md`](vendor-defaults-export.md) and
[`export-round-trip.md`](export-round-trip.md) (exporting, and loading what was exported). Tracking
issue: [#269](https://github.com/ddeuchert/logaperture/issues/269).

## Functional summary

After this feature, the user will be able to:

- Ship vendor defaults inside a Quarkus application: put the file at
  `src/main/resources/META-INF/logaperture/vendor-defaults.yaml`, rebuild, and it applies wherever
  the application runs, with no property, volume or ConfigMap.
- Have a mistake in that file stop the build, with each error and its line number, instead of
  finding out at startup that none of it applied.
- See the bundled file named in the startup line, `logctl env`, `logctl doctor` and the audit trail,
  with its hash, as an external file is today.
- Replace the bundled file at one site with an external file (`quarkus.logaperture.vendor-defaults`),
  or turn it off there (`quarkus.logaperture.bundled-vendor-defaults=false`), without rebuilding.
- Tune a running application, `logctl export vendor-defaults` the result, and drop the exported file
  in at that path for the next build.

Guide pages this changes: `vendors/vendor-defaults.md` ("Shipping it": a Quarkus section);
`reference/quarkus-extension-config.md` (the new property, and how the two sources combine);
`get-started/install-quarkus.md` (a pointer). `reference/doctor-checks.md` only if #7 adds a check.

Not part of this feature: the same convention for the agent (a fat jar or a WAR on WildFly, #9).

## Why

With the extension, `quarkus.logaperture.vendor-defaults` names a file on disk. Getting that file
next to the application means a volume, a ConfigMap mount or an extra `COPY` in the image: the kind
of launch-time setup the extension exists to remove. A Quarkus developer, and a vendor shipping a
Quarkus-based product, expects configuration to travel with the jar.

## How it works

At build time, the extension's deployment module reads one resource from the **application's own
archive** (the root archive: `target/classes` in development, the application jar when packaged).
If it is there, it is validated with the existing vendor-defaults parser, and its text is recorded
into the application, so the runtime has it without reading anything from disk. At runtime the
recorder parses the recorded text again and hands it to the engine exactly as a loaded file is
handed today: baseline layer, recipes, `reset` to it, `--to-native`, export, all unchanged.

## Decisions

All ten were agreed on 2026-10-25 from the sign-off review, each as the outcome in the last column.

| Id | Decision | Options | Outcome |
|---|---|---|---|
| #1 | The path | (a) `META-INF/logaperture/vendor-defaults.yaml`, next to where libraries put `recipes.yaml`. (b) A top-level `logaperture/vendor-defaults.yaml`. (c) Configurable at build time. | **(a)**. One fixed, documented place, already LogAperture's namespace in a jar; nothing to configure. |
| #2 | Which archive | (a) Only the application's own archive; the same path in a dependency is ignored, and the build log names each one ignored. (b) Any archive on the class path. | **(a)**. A library must not be able to set an application's baseline; libraries have recipes for that. The warning makes a misplaced file visible. |
| #3 | When it is checked | (a) At build time: a bundled file with errors fails the build, listing each error with its line. (b) At startup, rejected like an external file (none of it applies, `doctor` says why). | **(a)**. The file is part of the build, so the build is where a mistake should stop; Quarkus extensions fail the build on bad configuration. In `quarkus dev` this shows as Quarkus's error page until the file is fixed. |
| #4 | Bundled and external together | (a) An external file (`quarkus.logaperture.vendor-defaults`) replaces the bundled one entirely; one file is in force, as today. (b) Layer them: external entries over bundled entries. | **(a)**. One baseline, one hash in the audit, and the existing "one vendor defaults file" model holds. A site that wants the bundled settings plus its own exports, edits and ships a whole file. |
| #5 | Turning the bundled file off at a site | (a) `quarkus.logaperture.bundled-vendor-defaults=false`, a run-time property, default `true`. (b) No switch: replace it with an empty external file. | **(a)**. An operator debugging a site needs a way to see the application without the vendor's settings and without a rebuild; `--to-native` already covers this per logger, but not at startup. |
| #6 | How it is named | Everywhere a file path is shown today (startup line, `env`, `doctor`, audit `file=`, the export header), a bundled file shows as `bundled:META-INF/logaperture/vendor-defaults.yaml`, with the same `sha256`. | **Yes**. Distinct from any real path, and says where to look. |
| #7 | The writable-file warning | (a) Not applicable to a bundled file: no warning, no check. (b) A new `doctor` check that the application jar is writable. | **(a)**. The jar's own protection is the image's or the host's concern, as it is for the application's code. |
| #8 | Export round trip | The export of a running application with a bundled file says `Started from: bundled:META-INF/logaperture/vendor-defaults.yaml`; the exported file, dropped in at the path and rebuilt, takes over the matching sticky settings, as an external file does (export-round-trip.md). Covered by a test. | **Yes**. Nothing new to build if #6 holds; the test is the point. |
| #9 | The agent | (a) Out of scope: the agent keeps `--vendor-defaults=` only. (b) The agent also reads the path from the launched jar or a deployment. | **(a)**. The agent doesn't know which archive is "the application", and on WildFly there can be many. A separate issue if wanted. |
| #10 | Dev mode | Editing the bundled file in `quarkus dev` triggers a live reload, and the new engine loads the edited file. | **Yes**. Without it the edit is silently ignored until something else reloads. |

## What has to be built

1. **A vendor-defaults source that is not a path.** Today a loaded file is identified by its `Path`.
   A bundled file is identified by its label (#6). The parser and the loaded-file value take a
   source label; a `Path` stays available for an external file only (the writable check, #7).
2. **Deployment step** (#1, #2, #3, #10): read the root archive's resource, warn about the same path
   in other archives, validate, fail the build on errors, record the text; register the resource as
   a watched file for dev mode.
3. **Runtime** (#4, #5): choose the source at startup (external if configured, else bundled unless
   switched off, else none) and load it through the existing start-up code.
4. **Surfaces** (#6): the label wherever a path is shown.
5. **Tests**: build-time rejection (a unit test of the deployment step's validation, and a
   deliberately broken sample build); `QuarkusExtensionIT` with a bundled file (applied, named in
   `env`, replaced by an external file, switched off); the export round trip (#8); dev-mode edit
   (#10), by hand as for the extension.
6. **Guide**: the pages listed in the Functional summary, with output captured from a real run.

## As built

- **The source label (#6).** `VendorDefaults` has a `source()`: the absolute path for an external
  file, `bundled:META-INF/logaperture/vendor-defaults.yaml` for a bundled one, whose `path()` is
  empty. Every surface that showed the path shows `source()`; the output for an external file is
  unchanged. `VendorDefaultsFile.parseBundled(text, resource)` parses without touching the file
  system and is never writable (#7).
- **Build step.** `getRootArchive().getChildPath(...)` reads the application's own file. Other copies
  are found through the build's class loader (`getResources`) and named in a `WARNING` (#2), best
  effort. A file with errors throws from the build step, so the build fails with Quarkus's
  "Build step ... threw an exception" and then each error with its line (#3). The resource is a
  `HotDeploymentWatchedFileBuildItem` (#10).
- **The text reaches the runtime as recorded string constants**, split into chunks of 16 000
  characters, because one constant is limited to 64 KB.
- **The deployment jar shades `core`, `api` and `bridge`** (the parser) as normal dependencies left
  out of its dependency-reduced POM. Inside this reactor Quarkus resolves the extension's modules as
  their unshaded `target/classes`, so the build step could not use the runtime jar's shaded copy; a
  deployment module's dependencies are only on the build's class path, never in the application.
- **Checked by hand:** the build failure and the library warning, from a standalone project against
  the installed artifacts (inside this reactor a deliberately broken sample would break the build);
  and the dev-mode edit (#10). Automated: the parser and chunking, the source choice (#4, #5), the
  export round trip from a bundled file (#8), and `QuarkusExtensionIT` (bundled file applied and
  named, replaced by an external file, switched off).

## Out of scope

The agent (#9); layering two vendor files (#4); a configurable path (#1).

## Estimate

About 2 days: the source label (0.5), the deployment step and runtime choice (0.5), surfaces and
tests (0.5), guide (0.5).
