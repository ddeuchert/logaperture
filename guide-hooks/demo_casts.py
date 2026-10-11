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

"""The demo recordings on the guide (doc/specs/demo-recordings.md).

- A page that embeds a recording, <div class="la-cast" data-cast="NAME"></div>, fails the
  build when guide/assets/demos/NAME.cast doesn't exist; so does a missing player file
  (extra_css / extra_javascript), which MkDocs itself only links to.
- Each built assets/demos/NAME.cast gets a NAME.cast.js next to it, which casts.js loads
  with a <script> tag: browsers refuse fetch() on file://, where the zip's guide is opened.
- With DEMO_GIFS=1 (the docs workflow), the recordings named in extra.readme_gifs are also
  rendered to NAME.gif with agg, for the README. Without agg on the PATH that fails the build
  rather than skipping.
"""

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

from mkdocs.exceptions import PluginError

_EMBED = re.compile(r'class="la-cast"\s+data-cast="([^"]+)"')


def on_page_content(html, page, config, files):
    demos = Path(config["docs_dir"]) / "assets" / "demos"
    for name in _EMBED.findall(html):
        if not (demos / f"{name}.cast").is_file():
            raise PluginError(
                f"{page.file.src_path} embeds recording '{name}', but "
                f"guide/assets/demos/{name}.cast doesn't exist (record it with dev/demos/record.sh)"
            )
    return html


def on_post_build(config):
    site = Path(config["site_dir"])
    for asset in [*config["extra_css"], *config["extra_javascript"]]:
        path = str(getattr(asset, "path", asset))
        if not (site / path).is_file():
            raise PluginError(f"mkdocs.yml loads {path}, but guide/{path} doesn't exist")

    demos = site / "assets" / "demos"
    casts = sorted(demos.glob("*.cast")) if demos.is_dir() else []
    for cast in casts:
        text = cast.read_text(encoding="utf-8")
        script = f"window.laCasts = window.laCasts || {{}};\nwindow.laCasts[{json.dumps(cast.stem)}] = {json.dumps(text)};\n"
        cast.with_name(cast.name + ".js").write_text(script, encoding="utf-8")

    if os.environ.get("DEMO_GIFS") != "1":
        return
    agg = shutil.which("agg")
    if agg is None:
        raise PluginError("DEMO_GIFS=1 but agg isn't on the PATH")
    for name in config["extra"].get("readme_gifs", []):
        cast = demos / f"{name}.cast"
        if not cast.is_file():
            raise PluginError(f"extra.readme_gifs names '{name}', but guide/assets/demos/{name}.cast doesn't exist")
        subprocess.run(
            [agg, "--quiet", "--idle-time-limit", "1", "--last-frame-duration", "6",
             str(cast), str(cast.with_suffix(".gif"))],
            check=True,
        )
