# A social preview image and favicon for logaperture.org (issue #413)

Status: **signed off 2026-11-19, SP1 to SP9 all agreed as recommended.** Milestone `1.1.0`; branches from and
targets `develop` ([`release-lines.md`](release-lines.md)).
Issue: [#413](https://github.com/ddeuchert/logaperture/issues/413).
Parent spec: [`user-documentation.md`](user-documentation.md) (the site this changes), and through it
[`doc/logaperture-spec.md`](../logaperture-spec.md) §17.1 "User documentation" (MkDocs Material, versioned with
`mike`, published at `logaperture.org`).
Related: [`release-lines.md`](release-lines.md) (R5, when `latest` moves).

## Functional summary

After this feature, the user will be able to:

- Paste a link to any page of the LogAperture guide into Slack, Teams, LinkedIn or X and see the LogAperture
  preview image, the page title and a description.
- Paste `https://logaperture.org/latest/` (the bare `logaperture.org` too, where the platform allows) and get
  the same preview.
- Recognise LogAperture in a browser tab, a bookmark list or a phone's home screen by its icon.

Guide pages changed: none. This changes the site's page head and adds image files, not page content.

## Why

A link to the guide shows today as a bare URL, and the tab shows the theme's default icon. The project already
has a preview image on its GitHub page; the guide should present itself the same way when shared.

## What was found

- The site is built by `mkdocs build --strict` in `.github/workflows/docs.yml` and published to `gh-pages` with
  `mike`. `mkdocs.yml` already sets `site_url: https://logaperture.org/` and `theme.custom_dir: guide-overrides`,
  where `main.html` already extends `base.html` (the pre-release banner).
- `https://logaperture.org/` is a redirect page `mike` writes on `mike set-default` (the workflow calls it for
  `latest` at a release of the current line, and for `dev` until the first release). The workflow also writes
  `CNAME` once; `mike` touches nothing else at the root.
- No web manifest is defined, so the two `android-chrome-*` files are not needed.
- `latest` is an alias that moves only at a release of the current line (R5). A file added on `develop` is not
  under `/latest/` until the next release that moves it.

## Design

### Tags on every page (SP4, SP5)

`guide-overrides/main.html` gets an `extrahead` block, with `{{ super() }}` first so the theme's own head
(canonical, description, generator, viewport) is untouched:

| Tag | Value |
|---|---|
| `og:type` | `website` |
| `og:site_name` | `LogAperture` |
| `og:title` | the page title and the site name (`Install · LogAperture`); the site name alone on a page with no title |
| `og:description` | the page's `description` front matter, else `site_description` |
| `og:url` | the page's canonical URL |
| `og:image` | `https://logaperture.org/latest/assets/social-preview.png` (absolute) |
| `og:image:width`, `og:image:height` | `1280`, `640` |
| `og:image:alt` | a short description of the image |
| `twitter:card` | `summary_large_image` |

`og:image` is the same absolute URL on every version of the guide, so `1.0`, `1.1` and `dev` share one current
image and no old version needs editing. `og:url` is `page.canonical_url`. It first named the page under `/latest/` because `site_url` had no version and
the canonical was a missing URL; [`guide-canonical.md`](guide-canonical.md) (#414) fixed the canonical and this follows it.

### The image (SP2, SP3)

`guide/assets/social-preview.png` is the 1280x640 centred variant (`logaperture-social-preview-teams.png`),
supplied by the maintainer. Teams crops link thumbnails to about 3:2 around the centre, which the centred
variant survives. The file is never generated or substituted by the implementer.

### The favicon (SP6)

Supplied by the maintainer and placed in `guide/assets/`: `favicon.svg`, `favicon.ico` (16, 32, 48 px) and
`apple-touch-icon.png` (180 px). `theme.favicon` points at `assets/favicon.ico`, the fallback (the theme emits it without a type);
`extrahead` adds the SVG link with `type="image/svg+xml" sizes="any"`, which SVG-capable browsers prefer, and the
`apple-touch-icon` link. The built head must
carry no second, default Material icon. Links are relative to the page, so each version, and the copy in the
release zip, uses its own files. The theme logo is untouched.

### The root redirect (SP7)

`mike`'s redirect page can be given a template (`--template` on `set-default`). A template in the repo
(`guide-overrides/root-redirect.html`) keeps the redirect to `latest` (or `dev`) and adds the same tags with
fixed text: title `LogAperture`, the `site_description`, the same image, `og:url` `https://logaperture.org/`.
The workflow passes it on both `mike set-default` calls. The page is rewritten the next time `set-default`
runs, which is the 1.1.0 release for `latest`.

If `mike` does not support the template in the version the workflow installs, or it cannot carry the tags,
nothing is hacked around it: the PR says so, and `https://logaperture.org/latest/` is named as the URL to share.

### Check in CI (SP8)

After `mkdocs build --strict`, a short step in the docs job fails the build if the built `index.html` lacks
`og:image`, `twitter:card` or the favicon links, or if a file those tags point to (relative to the build) is
missing. Without it, a theme change could silently drop the tags.

### The release zip

The offline build carries the tags and the files and makes no network request for them: tags are only read by
crawlers. No change to the zip's layout.

## Decisions

| # | Decision | Agreed |
|---|---|---|
| SP1 | Branch and lifecycle | `develop`, milestone 1.1.0. The preview appears on `/latest/` when 1.1.0 releases and moves `latest`. The 1.0 guide is not edited (non-goal), so no backport to `release/1.0`, unlike "Coming in 1.1". Until then `dev` and `1.1` pages point `og:image` at a `latest` that lacks the file, and show no image in a preview. |
| SP2 | Where the image lives and is referenced | In `guide/assets/` (so the build check and the offline zip see it), referenced by the absolute `/latest/` URL. The alternative, a root-level file written once like `CNAME`, would work before 1.1.0 and for every version, at the cost of a second publishing path outside `mike`. |
| SP3 | Which image | The centred "teams" variant, as `social-preview.png`. To confirm: GitHub's repository preview may be the wider original; the guide uses the centred one. |
| SP4 | Mechanism | The `extrahead` block in the existing `guide-overrides/main.html`, with `{{ super() }}`. |
| SP5 | Title and description | As in the table: page title plus site name; page `description` front matter, else `site_description`. No page content or front matter is changed by this work. |
| SP6 | Favicon | `theme.favicon` for the `.ico` fallback, `extrahead` for the typed SVG link and the Apple touch icon, relative links. No `android-chrome-*` files, no manifest. |
| SP7 | Root redirect | Use `mike`'s redirect template on both `set-default` calls; if it cannot carry the tags, document the limitation instead. |
| SP8 | Build check | A small grep-and-file-exists step in the docs job, as above. |
| SP9 | Not done | The Material `social` plugin, the GitHub repository's own preview (a Settings change), a root `/favicon.ico` for the bare domain, and any edit to released versions. |

## Testing

- `mkdocs build --strict` and the CI `docs` job pass, including the SP8 check.
- In a local build: every page's head has all the SP4 tags and still has `canonical`, `description` and
  `generator`; `og:image` is absolute; the favicon links appear with no default Material icon beside them; the
  files exist in the output. A page with `description` front matter uses it; one without uses `site_description`.
- `OFFLINE=true mkdocs build` still opens from disk and still has no network requests.
- Rendering the root redirect template locally shows the redirect and the tags (`mike` is not part of the local
  build, so this is checked from the template and again after the first deploy).
- After the 1.1.0 release, by hand: fetch `/latest/` and confirm the tags; paste it into Slack and Teams; refresh
  LinkedIn Post Inspector and the Facebook Sharing Debugger; hard-refresh a tab to see the icon; paste the bare
  domain and note whether it previews.

## Out of scope

- Per-page generated cards (the `social` plugin), a web manifest, and the GitHub repository's social preview.
- Editing any released version of the guide in place.
