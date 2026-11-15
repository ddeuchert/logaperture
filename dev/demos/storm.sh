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

# Story 5: what is bursting? Storm detection is off by default: switch it on, ask which message is
# bursting (one message whose numbers change), then drop it. doc/specs/demo-recordings.md.

DEMO_TITLE="What is bursting?"

RETRY_LINE='Retrying payment for order'

# The console pane follows the server from when recording starts, so start the storm first: the
# opening screen is already scrolling. Detection is still off, so it starts counting when the story
# turns it on.
before_recording() {
    curl -fsS -X POST "$DEMO_URL/retry-storm/start" >/dev/null
}

# Silent: polls until detection lists the storm, so the visible `logctl storms` is a single call.
# A slow machine waits longer; one that never detects it fails the recording after the limit.
wait_for_storm() {
    local deadline=$((SECONDS + $1))
    until logctl storms 2>/dev/null | grep -Eq '^\[ONGOING\]'; do
        if ((SECONDS >= deadline)); then
            _fail "no storm was listed within ${1}s of enabling detection"
        fi
        sleep 1
    done
    sleep 1
}

story() {
    wait_for "$RETRY_LINE" 10
    hold 2
    say "A web shop app deployed on a WildFly server. Its console is scrolling too fast to read."
    say "Storm detection is off by default. Turn it on, give it a few seconds, then ask what is bursting:"
    run logctl enable storms
    wait_for_storm 30
    run logctl storms
    expect_output '^\[ONGOING\] +com\.example\.shop\.payment\.RetryQueue'
    say "One message, thousands of times, the numbers changing. Keep it out of the log:"
    run logctl add rule drop com.example.shop.payment.RetryQueue --message-contains Retrying
    wait_gone "$RETRY_LINE" 30
    say "The console is readable again; the shop still logs."
    hold 3
}
