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
 * Renders {@link HelpTopics} as the guide's command reference, guide/reference/logctl.md
 * (doc/specs/user-documentation.md, slice 2, H6 and H7). The page is committed; {@code
 * HelpReferenceTest} fails when it no longer matches and says how to regenerate it.
 */
final class HelpReference {

    static final String REGENERATE =
            "mvn -pl logaperture-cli test -Dtest=HelpReferenceTest -Dlogaperture.help.regenerate=true";

    private HelpReference() {
    }

    static String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!--\n");
        sb.append("  Generated from logaperture-cli/src/main/java/org/logaperture/cli/HelpTopics.java.\n");
        sb.append("  Don't edit by hand: edit HelpTopics.java, then regenerate with\n");
        sb.append("    ").append(REGENERATE).append('\n');
        sb.append("-->\n\n");
        sb.append("# logctl\n\n");
        sb.append("`logctl` controls the logging of a running JVM through the LogAperture agent. This page "
                + "has the same text as `logctl help <command>` on the command line.\n\n");

        sb.append("## Options for every command {#options}\n\n");
        appendOptions(sb, HelpTopics.GLOBAL_OPTIONS);

        sb.append("## How every command behaves {#behaviour}\n\n");
        for (String note : HelpTopics.NOTES) {
            sb.append(note).append("\n\n");
        }

        sb.append("## Commands {#commands}\n\n");
        sb.append("| Command | What it does |\n|---|---|\n");
        for (HelpTopic topic : HelpTopics.ALL) {
            sb.append("| [`").append(topic.name()).append("`](#").append(topic.anchor()).append(") | ")
                    .append(topic.summary()).append(" |\n");
        }
        sb.append('\n');

        for (HelpTopic topic : HelpTopics.ALL) {
            sb.append("## ").append(topic.name()).append(" {#").append(topic.anchor()).append("}\n\n");
            sb.append(topic.summary()).append("\n\n");
            sb.append("```text\n");
            for (String synopsis : topic.synopses()) {
                sb.append(synopsis).append('\n');
            }
            sb.append("```\n\n");
            for (String paragraph : topic.paragraphs()) {
                sb.append(paragraph).append("\n\n");
            }
            if (!topic.options().isEmpty()) {
                sb.append("**Options**\n\n");
                appendOptions(sb, topic.options());
            }
            if (!topic.examples().isEmpty()) {
                sb.append("**Examples**\n\n```sh\n");
                for (String example : topic.examples()) {
                    sb.append(example).append('\n');
                }
                sb.append("```\n\n");
            }
        }
        // One trailing newline, not a blank line, at the end of the file.
        return sb.toString().stripTrailing() + "\n";
    }

    private static void appendOptions(StringBuilder sb, List<HelpTopic.Option> options) {
        for (HelpTopic.Option option : options) {
            sb.append('`').append(option.flag()).append("`\n");
            sb.append(":   ").append(option.description()).append("\n\n");
        }
    }
}
