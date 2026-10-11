# Copyright 2026 David Deuchert
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""The release a guide version describes, filled into its pages at build time
(doc/specs/user-documentation.md, slice 5, R5).

A release build sets DOCS_VERSION to the full version (the docs workflow on a tag, the release
workflow for the zip's copy), e.g. 1.0.0-beta.1. Anything else (dev, or a hand-run deploy named
1.0) builds an unreleased guide. In a page:

- {{ logaperture.version }}      the version, or <version> in an unreleased guide
- {{ logaperture.release_url }}  that release's GitHub page, or the list of releases
- <!-- released --> ... <!-- /released -->      kept only in a release build
- <!-- unreleased --> ... <!-- /unreleased -->  kept only in an unreleased build

A {{ logaperture. token left over after that, a misspelt one, fails the build.
"""

import os
import re

from mkdocs.exceptions import PluginError

_RELEASE = re.compile(r"^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$")
_RELEASES = "https://github.com/ddeuchert/logaperture/releases"


def _release():
    version = os.environ.get("DOCS_VERSION", "")
    return version if _RELEASE.match(version) else None


def _keep_only(markdown, keep, drop):
    markdown = re.sub(rf"<!-- {drop} -->.*?<!-- /{drop} -->\n?", "", markdown, flags=re.S)
    return re.sub(rf"<!-- /?{keep} -->\n?", "", markdown)


def on_page_markdown(markdown, page, config, files):
    if "logaperture." not in markdown and "released -->" not in markdown:
        return markdown
    version = _release()
    if version:
        markdown = _keep_only(markdown, "released", "unreleased")
        markdown = markdown.replace("{{ logaperture.version }}", version)
        markdown = markdown.replace("{{ logaperture.release_url }}", f"{_RELEASES}/tag/v{version}")
    else:
        markdown = _keep_only(markdown, "unreleased", "released")
        markdown = markdown.replace("{{ logaperture.version }}", "<version>")
        markdown = markdown.replace("{{ logaperture.release_url }}", _RELEASES)
    leftover = re.search(r"\{\{ *logaperture\.[^}]*\}\}", markdown)
    if leftover:
        raise PluginError(f"{page.file.src_path}: unknown token {leftover.group(0)}")
    return markdown
