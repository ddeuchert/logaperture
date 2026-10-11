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

# Story 3: something is flooding the log. `top` finds the logger, and a `drop` rule silences
# just that exception without a restart. doc/specs/demo-recordings.md.

DEMO_TITLE="Something is flooding the log"

FLOOD_LINE='Stock sync failed|ConnectException|^ +at '

# top ranks loggers by bytes since the agent started, so give the flood long enough to lead
# on a server that has been up a while.
before_recording() {
    curl -fsS -X POST "$DEMO_URL/flood/start" >/dev/null
    sleep 20
}

story() {
    hold 2
    say "A web shop app deployed on a WildFly server. Something is flooding its console."
    say "Which logger is writing all this?"
    run logctl top --limit 5
    expect_output '^com\.example\.shop\.inventory\.StockSync '
    say "StockSync, mostly stack traces. Drop that exception, keep one a minute:"
    run logctl add rule drop com.example.shop.inventory.StockSync --throwable java.net.ConnectException --sample-full 1m
    wait_gone "$FLOOD_LINE" 30
    run logctl list rules
    say "The flood is gone; the shop still logs. HITS counts every event dropped."
    hold 3
}
