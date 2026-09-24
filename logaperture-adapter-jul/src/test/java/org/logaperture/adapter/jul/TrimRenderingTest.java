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
import org.logaperture.core.TrimDecision;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/trim-rule.md "Text formatters only". */
class TrimRenderingTest {

    private static String rendered(Throwable trimmed) {
        StringWriter sink = new StringWriter();
        trimmed.printStackTrace(new PrintWriter(sink));
        return sink.toString();
    }

    @Test
    void buildTrimmed_zeroFrames_oneLinerWithMarkerNamingTheFullOriginalCount() {
        RuntimeException original = new RuntimeException("boom");
        int originalFrameCount = original.getStackTrace().length;

        Throwable trimmed = TrimRendering.buildTrimmed(original, new TrimDecision("r1", 0, false));

        assertEquals(original + " [stack trace trimmed: " + originalFrameCount + " frames omitted]",
                trimmed.toString());
        assertEquals(0, trimmed.getStackTrace().length);
    }

    @Test
    void buildTrimmed_framesAtOrAboveActualDepth_stillPrintsTheMarker_withZeroOmitted() {
        // Decision #1 (doc/specs/trim-rule.md "Open decisions for sign-off"): the marker always
        // prints, reading "0 frames omitted", never suppressed.
        RuntimeException original = new RuntimeException("boom");
        int originalFrameCount = original.getStackTrace().length;

        Throwable trimmed = TrimRendering.buildTrimmed(original, new TrimDecision("r1", originalFrameCount + 100, false));

        assertTrue(trimmed.toString().endsWith("[stack trace trimmed: 0 frames omitted]"));
        assertEquals(originalFrameCount, trimmed.getStackTrace().length);
    }

    @Test
    void buildTrimmed_positiveFrames_keepsExactlyThatManyTopFrames() {
        RuntimeException original = new RuntimeException("boom");

        Throwable trimmed = TrimRendering.buildTrimmed(original, new TrimDecision("r1", 2, false));

        assertEquals(2, trimmed.getStackTrace().length);
        assertEquals(original.getStackTrace()[0], trimmed.getStackTrace()[0]);
        assertEquals(original.getStackTrace()[1], trimmed.getStackTrace()[1]);
    }

    @Test
    void buildTrimmed_causeChain_eachLevelGetsItsOwnOneLiner() {
        RuntimeException root = new RuntimeException("root cause");
        RuntimeException wrapper = new RuntimeException("wrapper", root);

        Throwable trimmed = TrimRendering.buildTrimmed(wrapper, new TrimDecision("r1", 0, false));

        assertEquals(wrapper + " [stack trace trimmed: " + wrapper.getStackTrace().length + " frames omitted]",
                trimmed.toString());
        Throwable trimmedCause = trimmed.getCause();
        assertEquals(root + " [stack trace trimmed: " + root.getStackTrace().length + " frames omitted]",
                trimmedCause.toString());

        String printed = rendered(trimmed);
        assertTrue(printed.contains("Caused by:"), printed);
    }

    @Test
    void buildTrimmed_collapseCauses_dropsTheWholeChain() {
        RuntimeException root = new RuntimeException("root cause");
        RuntimeException wrapper = new RuntimeException("wrapper", root);

        Throwable trimmed = TrimRendering.buildTrimmed(wrapper, new TrimDecision("r1", 0, true));

        assertNull(trimmed.getCause());
        assertFalse(rendered(trimmed).contains("Caused by:"), rendered(trimmed));
    }

    @Test
    void buildTrimmed_cyclicCauseChain_doesNotStackOverflow() {
        // Throwable disallows direct self-causation (initCause(this) throws), so a real cycle
        // needs two levels: a's cause is b, and b's cause is set back to a afterward.
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        Throwable trimmed = TrimRendering.buildTrimmed(a, new TrimDecision("r1", 0, false));

        Throwable trimmedB = trimmed.getCause();
        assertNull(trimmedB.getCause(), "the cycle back to an already-rendered level (a) stops the chain");
    }
}
