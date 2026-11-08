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

# Story 7: ship what you tuned. Tune live with sticky changes, then `export vendor-defaults` writes
# them as the file a product ships, so customers start from them. doc/specs/demo-recordings.md.

DEMO_TITLE="Ship what you tuned"

# A shorter console, so the whole story fits the logctl pane: a logger and a rule export as 16
# lines (V2). record.sh reads this line.
DEMO_CONSOLE_ROWS=6

story() {
    hold 2
    say "A web shop app deployed on a WildFly server. You have tuned its logging live; now ship it."
    run logctl set logger com.example.shop.payment WARN sticky
    run logctl add rule drop com.example.shop.inventory.StockSync --message-contains synced --below WARN sticky
    say "Both are sticky: they survive a restart. Export what you tuned:"
    run logctl export vendor-defaults
    expect_output '^# Exported by logctl export vendor-defaults'
    expect_output 'name: com\.example\.shop\.payment'
    expect_output 'level: WARN'
    expect_output 'action: drop'
    expect_output 'messageContains: synced'
    say "Ship it with the product and start the agent with --vendor-defaults=<file>: these become the baseline."
    hold 3
}
