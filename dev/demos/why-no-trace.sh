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

# Story 1: why don't I see my TRACE? The CONSOLE handler is at INFO, so a logger raised to TRACE
# would show nothing there -- but the console follows the change (doc/specs/
# default-handlers-follow.md), so one command is enough. doc/specs/demo-recordings.md.

DEMO_TITLE="Why don't I see my TRACE?"

TRACE_LINE='TRACE +\[com\.example\.shop\.payment\.'

story() {
    hold 1
    say "A web shop app deployed on a WildFly server. Its console is above."
    hold 1
    say "A customer reports failed payments. Turn on TRACE for the payment code:"
    run logctl set logger com.example.shop.payment TRACE for 15m
    wait_for "$TRACE_LINE" 10
    say "The CONSOLE handler is at INFO, so it follows the change to let the TRACE through."
    hold 3
    run logctl status
    say "Both revert on their own. Nothing in standalone.xml changed."
    hold 3
}
