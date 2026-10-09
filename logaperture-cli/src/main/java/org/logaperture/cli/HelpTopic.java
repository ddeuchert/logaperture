/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.logaperture.cli;

import java.util.List;

/**
 * One command, or a small group of commands, as {@code logctl help <name>} explains it and as
 * the generated reference page (guide/reference/logctl.md) shows it
 * (doc/specs/user-documentation.md, slice 2, H1 and H5).
 *
 * @param name       what {@code logctl help} takes to show this topic, and the page anchor's source
 * @param summary    one sentence, shown in topic lists
 * @param synopses   the command forms this topic owns, each starting with {@code logctl}; every
 *                   synopsis belongs to exactly one topic
 * @param paragraphs the explanation, one string per paragraph, unwrapped, with Markdown code spans
 * @param options    the options that matter for these commands, beyond the ones every command takes
 * @param examples   complete command lines, shown as written
 */
record HelpTopic(String name, String summary, List<String> synopses, List<String> paragraphs,
        List<Option> options, List<String> examples) {

    /**
     * @param flag        the option as typed, with its argument if it takes one ({@code --below <level>})
     * @param description one sentence or two, with Markdown code spans like the paragraphs
     */
    record Option(String flag, String description) {
    }

    /** The anchor of this topic's section on the reference page: {@code set logger} → {@code set-logger}. */
    String anchor() {
        return name.replace(' ', '-');
    }
}
