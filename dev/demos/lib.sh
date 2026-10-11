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

# The steps a demo script is written in (doc/specs/demo-recordings.md). Sourced by
# run-story.sh inside the bottom (logctl) pane; a story script is a list of these calls:
#
#   say   TEXT             type "# TEXT" at the prompt, as narration
#   run   CMD ARG...       type the command, run it, pause so its output can be read
#   hold  SECONDS          pause, so the viewer can watch the console pane
#   wait_for  REGEX [SECS] until a console-pane line matches, else fail the recording
#   wait_gone REGEX [SECS] until no console-pane line matches, else fail the recording
#   expect_absent  REGEX   fail the recording if a console-pane line matches now
#   expect_output  REGEX   fail the recording unless the last run's output matches
#
# A story may also define before_recording(), which record.sh runs before recording starts,
# after the shop is running, with DEMO_URL set to the shop's /demo endpoint.
#
# Pauses are timed (DEMO_INTERACTIVE=0), so a recording needs no one at the keyboard; when
# rehearsing (DEMO_INTERACTIVE=1) each pause waits for a key instead.

DEMO_CONTAINER=${DEMO_CONTAINER:-logaperture-wildfly}
DEMO_INTERACTIVE=${DEMO_INTERACTIVE:-0}
DEMO_TYPE_DELAY=${DEMO_TYPE_DELAY:-0.03}   # seconds per typed character
DEMO_READ_PAUSE=${DEMO_READ_PAUSE:-3}      # after a command's output
DEMO_SAY_PAUSE=${DEMO_SAY_PAUSE:-1}        # after a comment
DEMO_LOG_PANE=${DEMO_LOG_PANE:?record.sh sets the console pane}
DEMO_ERROR_FILE=${DEMO_ERROR_FILE:-/dev/null}   # record.sh prints it when a story fails
DEMO_LAST_OUTPUT=$(mktemp)

PROMPT=$'\e[1;34m$\e[0m '

# The screen shows `logctl ...` exactly as a user types it; underneath it runs in the dev
# WildFly container, as wildflyctl.py's `logctl --` does.
logctl() {
    docker exec "$DEMO_CONTAINER" java -jar /opt/logctl.jar "$@"
}

_type() {
    local text=$1 i
    for ((i = 0; i < ${#text}; i++)); do
        printf '%s' "${text:i:1}"
        sleep "$DEMO_TYPE_DELAY"
    done
}

_pause() {
    if [[ $DEMO_INTERACTIVE == 1 ]]; then
        read -rsn1
    else
        sleep "$1"
    fi
}

say() {
    printf '%s\e[2m' "$PROMPT"
    _type "# $*"
    printf '\e[0m\n'
    _pause "$DEMO_SAY_PAUSE"
}

run() {
    printf '%s' "$PROMPT"
    _type "$*"
    sleep 0.4
    printf '\n'
    "$@" 2>&1 | tee "$DEMO_LAST_OUTPUT"
    _pause "$DEMO_READ_PAUSE"
}

hold() {
    _pause "$1"
}

_fail() {
    printf '\ndemo failed: %s\n' "$1" | tee -a "$DEMO_ERROR_FILE" >&2
    _console >>"$DEMO_ERROR_FILE"
    exit 3
}

_console() {
    tmux capture-pane -p -J -t "$DEMO_LOG_PANE"
}

wait_for() {
    local regex=$1 timeout=${2:-10} deadline
    deadline=$((SECONDS + timeout))
    until _console | grep -Eq -- "$regex"; do
        if ((SECONDS >= deadline)); then
            _fail "no console line matched /$regex/ within ${timeout}s"
        fi
        sleep 0.25
    done
}

wait_gone() {
    local regex=$1 timeout=${2:-10} deadline
    deadline=$((SECONDS + timeout))
    while _console | grep -Eq -- "$regex"; do
        if ((SECONDS >= deadline)); then
            _fail "console lines still matched /$regex/ after ${timeout}s"
        fi
        sleep 0.25
    done
}

expect_output() {
    if ! grep -Eq -- "$1" "$DEMO_LAST_OUTPUT"; then
        _fail "the last command's output didn't match /$1/"
    fi
}

expect_absent() {
    if _console | grep -Eq -- "$1"; then
        _fail "a console line matched /$1/ too early"
    fi
}
