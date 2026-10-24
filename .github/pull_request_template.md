<!-- Title: what changed, then (issue #N). Reference the issue; don't use "Closes #N" — merging to develop isn't a release. -->

## What and why



## Checklist

- [ ] The base branch matches the issue's milestone: `release/1.0` for 1.0.0 / 1.0.x fixes, `develop` for everything else. A change merged to `release/1.0` is also merged forward into `develop`.
- [ ] The spec in `doc/` is updated in this PR (or this change needs none).
- [ ] User-visible change? The guide pages in `guide/` it affects are updated in this PR (or there is none).
- [ ] `CHANGELOG.md` has an entry under Unreleased (or this change needs none).
