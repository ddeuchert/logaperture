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

# Story 2: debug one package for a while, then it cleans itself up. A `for` override reverts on
# its own timer, and CONSOLE's AUTO level follows it back down. doc/specs/demo-recordings.md.

DEMO_TITLE="Debug one package for a while"

DEBUG_LINE='DEBUG +\[com\.example\.shop\.checkout\.'

story() {
    hold 1
    say "A web shop app deployed on a WildFly server. Its console is above."
    hold 1
    say "Debug the checkout code for a while: 30 seconds here, an hour in real life."
    run logctl set logger com.example.shop.checkout DEBUG for 30s
    run logctl set handler CONSOLE AUTO
    wait_for "$DEBUG_LINE" 10
    run logctl list loggers com.example
    say "Now wait. Nobody has to remember to put it back..."
    wait_for 'action=REVERSION' 45
    wait_gone "$DEBUG_LINE" 15
    run logctl status
    say "The override is gone, and CONSOLE went back to INFO with it."
    hold 3
}
