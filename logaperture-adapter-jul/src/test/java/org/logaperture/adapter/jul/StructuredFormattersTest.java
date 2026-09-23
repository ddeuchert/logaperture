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
package org.logaperture.adapter.jul;

import org.junit.jupiter.api.Test;

import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/trim-rule.md "Text formatters only". */
class StructuredFormattersTest {

    private static final class JsonFormatter extends SimpleFormatter {
    }

    private static final class XmlFormatter extends SimpleFormatter {
    }

    private static final class SomeVendorJsonFormatter extends SimpleFormatter {
    }

    @Test
    void isStructured_matchesByTrailingSimpleName() {
        assertTrue(StructuredFormatters.isStructured(new JsonFormatter()));
        assertTrue(StructuredFormatters.isStructured(new XmlFormatter()));
        assertTrue(StructuredFormatters.isStructured(new SomeVendorJsonFormatter()));
    }

    @Test
    void isStructured_plainTextFormatter_isNotStructured() {
        assertFalse(StructuredFormatters.isStructured(new SimpleFormatter()));
    }

    @Test
    void isStructured_null_isFalse() {
        assertFalse(StructuredFormatters.isStructured(null));
    }
}
