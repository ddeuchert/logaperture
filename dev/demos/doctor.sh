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

# Story 6: is my logging config going to hurt me? `doctor` reads the configuration and says what
# will hurt; one finding, a framework logger left at DEBUG, is `logctl`'s to fix, and `doctor`
# prints the command. The rest are the server's own handler settings, reported and left alone.
# doc/specs/demo-recordings.md.

DEMO_TITLE="Is my logging config going to hurt me?"

# Someone left DEBUG on for a chatty framework logger. A real override, which record.sh's reset
# clears; `doctor` reads it as "explicitly set to DEBUG" however it got there.
before_recording() {
    logctl_hidden set logger org.hibernate DEBUG --reason "left on"
}

story() {
    hold 2
    say "A web shop app deployed on a WildFly server. Is its logging configuration going to hurt it?"
    run logctl doctor
    expect_output 'org\.hibernate is explicitly set to DEBUG'
    expect_output 'suggested: logctl reset logger org\.hibernate'
    say "doctor only reports. This one is ours to fix, and it says how:"
    run logctl reset logger org.hibernate
    expect_output 'org\.hibernate → INFO \(baseline\)'
    say "The other three are WildFly's own handler settings: doctor names them and leaves them to you."
    hold 3
}
