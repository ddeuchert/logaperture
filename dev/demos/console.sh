#!/usr/bin/env bash
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

# The top pane: the dev WildFly's console from now on, as the CONSOLE handler writes it.
# A line wider than the pane is cut with "…" rather than wrapped, so each record stays one
# row. WildFly's colour codes only ever lead a line, so the cut counts what follows them. Each
# line's newline is written before the next line rather than after it, so the cursor never
# takes the pane's last row.

set -u
container=${DEMO_CONTAINER:-logaperture-wildfly}
width=$(tput cols)

docker logs --follow --tail 0 "$container" 2>&1 |
    perl -CS -ne '
        BEGIN { $| = 1; $w = shift @ARGV }
        chomp;
        s/\r$//;
        s/\t/    /g;
        my ($codes, $text) = /^((?:\e\[[0-9;]*m)*)(.*)$/;
        $text = substr($text, 0, $w - 1) . "\x{2026}" if length($text) > $w;
        print STDOUT ($. == 1 ? "" : "\n"), "$codes$text\e[0m";
    ' "$width"
