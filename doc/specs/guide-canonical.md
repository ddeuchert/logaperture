# Canonical links that point at pages that exist (issue #414)

Status: **signed off 2026-11-20, CN1 to CN7 all agreed as recommended.** Milestone `1.1.0`; branches from and
targets `develop` ([`release-lines.md`](release-lines.md)).
Issue: [#414](https://github.com/ddeuchert/logaperture/issues/414).
Parent spec: [`user-documentation.md`](user-documentation.md) (the site this changes), and through it
[`doc/logaperture-spec.md`](../logaperture-spec.md) §17.1 "User documentation".
Related: [`social-preview.md`](social-preview.md) (#413, which found this and worked around it for `og:url`),
[`release-lines.md`](release-lines.md) (R5, when `latest` moves).

## Functional summary

After this feature, the user will be able to:

- Find a LogAperture guide page through a search engine and land on the current guide's page, not on a
  page that does not exist.
- Share a link from any version of the guide and have the preview, and the address the platform shows, name a
  page that opens.

Guide pages changed: none. This changes the site's page head only.

## Why

`mkdocs.yml` sets `site_url: https://logaperture.org/`, which has no version in it, so every page of every
version renders `<link rel="canonical" href="https://logaperture.org/<page>/">`, for example
`https://logaperture.org/troubleshooting/`. The site is published by `mike` under `/latest/`, `/<minor>/` and
`/dev/`, and the root holds only the redirect page, so that URL is a 404. Search engines are told, on every page,
to treat a missing URL as the real one. The sitemap each build writes lists the same missing URLs.

[#413](https://github.com/ddeuchert/logaperture/issues/413) found it and avoided it for `og:url` only, by naming
the page under `/latest/`.

## Design

`site_url` is what Material's canonical link, the sitemap and the 404 page are built from, so the fix is to give
each build the URL it will be served at.

### Which URL (CN1)

| Build | `site_url` | Why |
|---|---|---|
| A release (no pre-release suffix), including a late patch of an older minor | `https://logaperture.org/latest/` | Every released version defers to the current guide, so a reader of an old link ends on the current page; this is how versioned docs usually canonicalise. A page that the current guide no longer has is the cost (see CN6). |
| `dev`, and a pre-release | `https://logaperture.org/<version>/` (`/dev/`, `/1.1/`) | These document things `latest` does not have; pointing them at `latest` would name pages that may not exist, the defect this fixes. They are self-canonical. |
| Any build with nothing set (a local build, the PR build) | `https://logaperture.org/latest/` | The default is the common case, and is what the CI check looks for. |

`mkdocs.yml` reads it from the environment, like `DOCS_VERSION` and `DOCS_PRERELEASE`:
`site_url: !ENV [DOCS_SITE_URL, "https://logaperture.org/latest/"]`. The docs workflow's "Work out the version to
deploy" step adds a `site_url` output from the rule above (`dev`, or a non-empty pre-release, gives the version's
own path; anything else leaves `latest`), and the "Deploy with mike" step exports it as `DOCS_SITE_URL`. An
**empty** `DOCS_SITE_URL` turns the canonical link off (checked), so the step exports it only when set.

### `og:url` goes back to the canonical (CN2)

With a correct canonical, the `og:url` workaround in `guide-overrides/main.html` and `social-preview.md` is
removed: `og:url` is `page.canonical_url` again. That also removes a defect of the workaround, `og:url` naming a
`/latest/` page that a `dev`-only page does not have.

### The sitemap (CN3)

Each version's build writes its own `sitemap.xml`, now with URLs that exist. Nothing at the root links to those
files (no `robots.txt`, no root sitemap), so search engines do not find them, as before. Adding one is a separate
decision about indexing and is out of scope.

### Local preview and the zip (CN4)

`mkdocs serve` mounts the site at the path of `site_url`, so the default makes the local preview
`http://127.0.0.1:8000/latest/` (checked in the build output: the canonical for the offline build reads
`https://logaperture.org/latest/troubleshooting.html`). The comment at the top of `mkdocs.yml` and
`DEVELOPMENT.md` say so. The release zip's copy opens from disk and nothing reads its canonical link; it is left
as it is.

## Decisions

| # | Decision | Agreed |
|---|---|---|
| CN1 | The rule | Releases canonicalise to `/latest/`; `dev` and pre-releases to themselves; the default is `/latest/`. Alternative: every version self-canonical, which is simplest but lets old versions compete with `latest` in search. |
| CN2 | `og:url` | Back to `page.canonical_url`; the #413 workaround and its note in `social-preview.md` are removed. |
| CN3 | Sitemap | Fixed per version by the same change; a root `robots.txt` or sitemap is out of scope. |
| CN4 | Mechanism | `DOCS_SITE_URL` from the environment with a `latest` default, set by the docs workflow; `mkdocs serve` then lives under `/latest/`, documented. Alternative: a build hook that rewrites the URL, which keeps `serve` at the root but is more code for the same result. |
| CN5 | `dev` indexing | Self-canonical only; no `noindex`. Whether the `dev` guide should be indexed at all is a separate question. |
| CN6 | A page that `latest` no longer has | Accepted: a released page canonicalises to `/latest/<page>/` even if the current guide dropped it. Rare for this guide, and a wrong canonical is ignored by search engines rather than breaking the page. |
| CN7 | Branch and reach | `develop`, milestone 1.1.0. It takes effect for the deploys made from `develop` and for 1.1.0 on; the released 1.0 guide is not edited (as in #413), so its canonicals stay as they are until 1.1.0 supersedes it. |

## Testing

- `mkdocs build --strict` passes, and so does the docs job.
- The docs job's tag check (from #413) also requires `rel="canonical"` to start with
  `https://logaperture.org/latest/` in the default build.
- In a local build with `DOCS_SITE_URL=https://logaperture.org/dev/` the canonical, `og:url` and the sitemap name
  `/dev/` pages; with it unset, `/latest/` pages.
- `OFFLINE=true mkdocs build --strict` still passes and the copy still opens from disk.
- The workflow rule is a shell step, so it is checked by reading its output for the three cases (a tag, `dev`, a
  manual run for a pre-release) on a branch run of the workflow, before merge.
- After the first deploy, by hand: view source on a `/latest/` page and a `/dev/` page and open each canonical
  link; check Search Console later for the pages it reports.

## Out of scope

- A root `robots.txt` or sitemap, and `noindex` on `dev`.
- Editing the 1.0 guide, which keeps its current canonicals.
- The canonical link inside the release zip.
