/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Turns each <div class="la-cast" data-cast="NAME"></div> on a page into an asciinema player
// for assets/demos/NAME.cast (doc/specs/demo-recordings.md).
//
// The recording is loaded as assets/demos/NAME.cast.js, which guide-hooks/demo_casts.py
// writes at build time, through a <script> tag rather than fetch(): browsers refuse fetch()
// on file://, and the guide in the release zip is opened from disk.
(function () {
  "use strict";

  var assets = document.currentScript.src.replace(/javascripts\/casts\.js(\?.*)?$/, "");
  window.laCasts = window.laCasts || {};

  // Where playback ends, in player time: the gaps between events, each capped at the
  // recording's idle time limit, as the player caps them.
  function endTime(cast) {
    var lines = cast.split("\n").filter(function (line) { return line.trim() !== ""; });
    var limit = JSON.parse(lines[0]).idle_time_limit || Infinity;
    var previous = 0;
    var total = 0;
    for (var i = 1; i < lines.length; i++) {
      var t = JSON.parse(lines[i])[0];
      total += Math.min(t - previous, limit);
      previous = t;
    }
    return total;
  }

  function mount(el, cast) {
    AsciinemaPlayer.create({ data: cast }, el, {
      fit: "width",
      idleTimeLimit: 1,
      // Show the finished story before anyone presses play.
      poster: "npt:" + Math.max(0, endTime(cast) - 0.05).toFixed(2),
    });
  }

  function load(el) {
    var name = el.getAttribute("data-cast");
    if (window.laCasts[name]) {
      mount(el, window.laCasts[name]);
      return;
    }
    var script = document.createElement("script");
    script.src = assets + "demos/" + name + ".cast.js";
    script.onload = function () { mount(el, window.laCasts[name]); };
    document.head.appendChild(script);
  }

  function init() {
    Array.prototype.forEach.call(document.querySelectorAll(".la-cast[data-cast]"), load);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
