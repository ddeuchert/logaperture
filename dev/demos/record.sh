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

# Records the demo stories against the dev WildFly (doc/specs/demo-recordings.md):
#
#   dev/demos/record.sh all                  every story, into guide/assets/demos/<story>.cast
#   dev/demos/record.sh why-no-trace         one story
#   dev/demos/record.sh --rehearse STORY     play it in this terminal, a key per step; no file
#
# Needs: the dev WildFly up with the sample deployed (dev/wildfly/wildflyctl.py up, deploy),
# tmux, asciinema 3, perl, curl. A story that fails a check writes no .cast.

set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out_dir=$root/guide/assets/demos
stories=(why-no-trace debug-for-a-while flood)

cols=120
rows=32
console_rows=12
container=${DEMO_CONTAINER:-logaperture-wildfly}
demo_url=http://localhost:8080/logaperture-sample-war/demo
socket=logaperture-demo

die() {
    printf 'record.sh: %s\n' "$*" >&2
    exit 1
}

logctl_hidden() {
    docker exec "$container" java -jar /opt/logctl.jar "$@" >/dev/null
}

# Every story starts from the server's own configuration with the shop running, and ends
# the same way, whatever an earlier run left behind.
reset_server() {
    curl -fsS -X POST "$demo_url/stop" >/dev/null
    logctl_hidden reset loggers --include-sticky
    logctl_hidden reset handlers --include-sticky
    logctl_hidden reset rules --include-sticky
}

record_story() {
    local story=$1 mode=$2 work status title
    [[ -f $here/$story.sh ]] || die "no story '$story' (have: ${stories[*]})"
    title=$(grep -m1 '^DEMO_TITLE=' "$here/$story.sh" | cut -d'"' -f2)
    work=$(mktemp -d)
    mkdir -p "$work/home"

    reset_server
    curl -fsS -X POST "$demo_url/start" >/dev/null
    sleep 3   # a few shop lines, so the console pane doesn't open empty
    (
        export DEMO_URL=$demo_url
        # shellcheck disable=SC1090
        source "$here/$story.sh"
        if declare -F before_recording >/dev/null; then
            before_recording
        fi
    )

    # A clean environment: no host name, user name or local path reaches the recording.
    local -a clean_env=(env -i "HOME=$work/home" "PATH=$PATH" "TERM=xterm-256color"
        "LANG=C.UTF-8" "SHELL=/bin/bash" "XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-}"
        "DEMO_CONTAINER=$container" "DEMO_INTERACTIVE=$([[ $mode == rehearse ]] && echo 1 || echo 0)")
    [[ -n ${DOCKER_HOST:-} ]] && clean_env+=("DOCKER_HOST=$DOCKER_HOST")

    tmux -L "$socket" kill-server 2>/dev/null || true
    local console_pane story_pane
    console_pane=$("${clean_env[@]}" tmux -L "$socket" -f "$here/tmux.conf" \
        new-session -d -P -F '#{pane_id}' -s demo -x "$cols" -y "$rows" "$here/console.sh")
    tmux -L "$socket" select-pane -t "$console_pane" -T "server console"
    story_pane=$(tmux -L "$socket" split-window -v -P -F '#{pane_id}' \
        -l $((rows - console_rows - 1)) -t "$console_pane" \
        "DEMO_LOG_PANE=$console_pane $here/run-story.sh $story $work/status")
    tmux -L "$socket" select-pane -t "$story_pane" -T "logctl"

    if [[ $mode == rehearse ]]; then
        tmux -L "$socket" attach -t demo
    else
        "${clean_env[@]}" asciinema rec --headless --quiet --overwrite \
            --output-format asciicast-v2 --window-size "${cols}x${rows}" --idle-time-limit 1 \
            --title "$title" --command "tmux -L $socket attach -t demo" "$work/$story.cast"
    fi

    status=$(cat "$work/status" 2>/dev/null || echo "no status")
    reset_server
    if [[ $status != 0 ]]; then
        cat "$work/status.err" >&2 2>/dev/null || true
        die "$story failed ($status); nothing written. Rehearse it: dev/demos/record.sh --rehearse $story"
    fi
    if [[ $mode == record ]]; then
        mkdir -p "$out_dir"
        # End on the story's last screen: drop tmux restoring the terminal ("[exited]"), which
        # arrives in one burst, split over several events, just before the exit event.
        perl -e '
            my @events = <STDIN>;
            my ($end) = map { /^\[([0-9.]+), "x"/ ? $1 : () } @events;
            for (@events) {
                my ($t) = /^\[([0-9.]+),/;
                print unless defined $t && defined $end && $t >= $end - 0.05;
            }
        ' <"$work/$story.cast" >"$out_dir/$story.cast"
        printf 'recorded %s\n' "${out_dir#"$root"/}/$story.cast"
    fi
    rm -rf "$work"
}

mode=record
if [[ ${1:-} == --rehearse ]]; then
    mode=rehearse
    shift
fi
[[ $# -gt 0 ]] || die "usage: record.sh [--rehearse] all | STORY..."
for tool in tmux asciinema perl curl docker; do
    command -v "$tool" >/dev/null || die "$tool is not installed"
done
curl -fsS "$demo_url/status" >/dev/null ||
    die "the sample app isn't answering at $demo_url; run dev/wildfly/wildflyctl.py up and deploy"

if [[ $1 == all ]]; then
    set -- "${stories[@]}"
fi
for story in "$@"; do
    record_story "$story" "$mode"
done
