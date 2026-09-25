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

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.Level;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.control.jmx.RuleData;

import java.time.Duration;

/**
 * {@link org.logaperture.api.RuleExpression} for a {@code list rules} row -- the {@code EXPRESSION}
 * column of {@code logctl list rules --verbose} and the {@code expression} JSON field
 * (doc/specs/list-rules-verbose.md). The rendering itself lives in {@code api}, shared with the
 * agent's audit records (doc/specs/alter-rule.md "Audit").
 */
final class RuleExpression {

    private RuleExpression() {
    }

    static String of(RuleData rule) {
        CompiledMatchers matchers = new CompiledMatchers(
                rule.getLevelAtMost() == null ? null : Level.valueOf(rule.getLevelAtMost()),
                rule.getMessageContains(), rule.isMessageIgnoreCase(), rule.getThrowableType(),
                rule.getThrowableMessageContains(), rule.isAnyCause());
        SampleFullPolicy sampleFull = null;
        if (Boolean.FALSE.equals(rule.getSampleFullEnabled())) {
            sampleFull = SampleFullPolicy.disabled();
        } else if (Boolean.TRUE.equals(rule.getSampleFullEnabled()) && rule.getSampleFullEveryMillis() != null) {
            sampleFull = SampleFullPolicy.every(Duration.ofMillis(rule.getSampleFullEveryMillis()));
        }
        return org.logaperture.api.RuleExpression.of(matchers, sampleFull, rule.getFrames(), rule.getCollapseCauses());
    }

    static String belowFor(String levelAtMost) {
        return org.logaperture.api.RuleExpression.belowFor(Level.valueOf(levelAtMost));
    }

    static String duration(long millis) {
        return org.logaperture.api.RuleExpression.duration(millis);
    }

    static String quote(String value) {
        return org.logaperture.api.RuleExpression.quote(value);
    }
}
