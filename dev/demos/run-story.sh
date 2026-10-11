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

# Runs one story in the bottom (logctl) pane. Started by record.sh; not meant to be run by
# hand. Waits until a client attaches (asciinema, or you when rehearsing), plays the story,
# writes its exit status to STATUS_FILE, then ends the tmux session, which ends the recording.
#
#   run-story.sh STORY STATUS_FILE

set -u
here=$(cd "$(dirname "$0")" && pwd)
story_name=$1
status_file=$2

export DEMO_ERROR_FILE=$status_file.err
# shellcheck source=lib.sh
source "$here/lib.sh"
# shellcheck disable=SC1090
source "$here/$story_name.sh"

until [[ -n $(tmux list-clients -t demo 2>/dev/null) ]]; do
    sleep 0.1
done
sleep 0.5
clear

(story)
echo $? > "$status_file"
tmux kill-session -t demo
