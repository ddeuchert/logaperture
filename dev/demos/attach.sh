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

# Story 8: try it, no restart. A server that does not run LogAperture, and `logctl attach` loads
# the agent into it while it runs; a `set logger` then shows its effect at once. The target is the
# agent-less WildFly (`wildflyctl plain-up`), which record.sh starts fresh for this story.
# doc/specs/demo-recordings.md.

DEMO_TITLE="Try it, no restart"
DEMO_SERVER=plain

story() {
    hold 2
    say "A web shop on a WildFly server without LogAperture. Customers report failed payments, and it can't be restarted."
    expect_absent 'DEBUG .*com\.example\.shop\.payment'
    run jps -l
    local pid
    pid=$(jvm_pid jboss-modules)
    say "Load LogAperture into the running server:"
    run logctl attach "$pid"
    expect_output 'is active in PID'
    say "It now works as if the server had started with it. Debug the payment code for a while:"
    run logctl set logger com.example.shop.payment DEBUG for 5m
    wait_for 'DEBUG .*com\.example\.shop\.payment' 15
    say "Nothing in the launch line changed, and the next restart puts the server back as it was."
    hold 3
}
