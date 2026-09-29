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
package org.logaperture.core;

import org.junit.jupiter.api.Test;
import org.logaperture.core.VendorYaml.ListNode;
import org.logaperture.core.VendorYaml.MapNode;
import org.logaperture.core.VendorYaml.ScalarNode;
import org.logaperture.core.VendorYaml.SyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/recipes.md #2: literal block text ({@code key: |}) in the vendor defaults YAML subset. */
class VendorYamlTest {

    @Test
    void blockText_keepsLineBreaks_removesIndentation_dropsFinalBreak() throws SyntaxException {
        MapNode root = VendorYaml.parse("""
                description: |
                  Logs each session create/expire/invalidate with its id. Moderate volume
                    under load, indented further.
                  Session ids are sensitive; don't leave this on.
                next: value
                """);

        assertEquals("Logs each session create/expire/invalidate with its id. Moderate volume\n"
                + "  under load, indented further.\n"
                + "Session ids are sensitive; don't leave this on.", text(root, "description"));
        assertEquals("value", text(root, "next"));
    }

    @Test
    void blockText_keepsCommentsAndInnerBlankLines_asText() throws SyntaxException {
        MapNode root = VendorYaml.parse("""
                description: |   # this comment is on the key's line
                  # not a comment

                  second paragraph --- with dashes


                next: value
                """);

        assertEquals("# not a comment\n\nsecond paragraph --- with dashes", text(root, "description"));
        assertEquals("value", text(root, "next"));
    }

    @Test
    void blockText_insideListItemMap_endsAtTheNextSiblingKey() throws SyntaxException {
        MapNode root = VendorYaml.parse("""
                recipes:
                  - name: sessions
                    description: |
                      line one
                      line two
                    summary: after the text
                  - name: second
                """);

        ListNode recipes = (ListNode) root.entries().get("recipes");
        MapNode first = (MapNode) recipes.items().get(0);
        assertEquals("line one\nline two", text(first, "description"));
        assertEquals("after the text", text(first, "summary"));
        assertEquals(2, recipes.items().size());
    }

    @Test
    void blockText_withNothingIndentedUnderIt_isEmpty() throws SyntaxException {
        MapNode root = VendorYaml.parse("description: |\nnext: value\n");

        assertEquals("", text(root, "description"));
        assertEquals("value", text(root, "next"));
    }

    @Test
    void blockText_isQuoted_soTrueStaysText() throws SyntaxException {
        ScalarNode value = (ScalarNode) VendorYaml.parse("flag: |\n  true\n").entries().get("flag");

        assertEquals("true", value.value());
        assertTrue(value.quoted());
    }

    @Test
    void otherBlockForms_areRejectedByName() {
        for (String form : new String[] {">", "|-", "|+", "|2", ">-"}) {
            SyntaxException e = assertThrows(SyntaxException.class,
                    () -> VendorYaml.parse("description: " + form + "\n  text\n"));
            assertEquals(1, e.line());
            assertTrue(e.getMessage().startsWith("only plain '|' block text is supported, not '" + form + "'"),
                    e.getMessage());
        }
    }

    @Test
    void blockText_asAListItem_isRejected() {
        SyntaxException e = assertThrows(SyntaxException.class, () -> VendorYaml.parse("items:\n  - |\n    text\n"));

        assertEquals(2, e.line());
    }

    @Test
    void blockText_dedentedInsideItsOwnIndent_isAnError() {
        SyntaxException e = assertThrows(SyntaxException.class, () -> VendorYaml.parse("""
                a:
                  description: |
                      deep first line
                    shallower line
                """));

        assertEquals(4, e.line());
    }

    @Test
    void structureAfterBlockText_isStillChecked() {
        SyntaxException tabs = assertThrows(SyntaxException.class,
                () -> VendorYaml.parse("description: |\n  text\nloggers:\n\t- name: x\n"));
        assertEquals(4, tabs.line());

        SyntaxException documents = assertThrows(SyntaxException.class,
                () -> VendorYaml.parse("description: |\n  text\n---\n"));
        assertEquals(3, documents.line());
    }

    private static String text(MapNode map, String key) {
        return ((ScalarNode) map.entries().get(key)).value();
    }
}
