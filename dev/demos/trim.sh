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

# Story 4: stack traces are burying the log. A `trim` rule keeps every payment warning but cuts
# its known ConnectException to one line, without a restart. doc/specs/demo-recordings.md.

DEMO_TITLE="Stack traces are burying the log"

# Any stack frame or "... N more" line: after the trim, the console has none.
FRAME_LINE='^ +(at |\.\.\. [0-9]+ more)'

# The console pane follows the server from when recording starts, so start the outage first:
# the opening screen is already a wall of stack frames.
before_recording() {
    curl -fsS -X POST "$DEMO_URL/payment-outage/start" >/dev/null
}

story() {
    wait_for "$FRAME_LINE" 10
    hold 2
    say "A web shop app deployed on a WildFly server. Its console is above."
    say "Its primary payment endpoint is down. Every order falls back, and logs a full stack trace:"
    hold 3
    say "Same ConnectException every time, nothing to learn from the trace. Keep the warning, one line:"
    run logctl add rule trim com.example.shop.payment.PaymentGateway --throwable java.net.ConnectException --any-cause
    wait_for '^Caused by: java\.net\.ConnectException: .*\[stack trace trimmed: ' 15
    wait_gone "$FRAME_LINE" 25
    run logctl list rules
    say "Every warning is still logged, with its cause on one line. The orders are readable again."
    hold 3
}
