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
package org.logaperture.api;

import java.util.Objects;

/**
 * What {@code logctl alter rule <id>} changes -- doc/specs/alter-rule.md "What can change" (A2)
 * and "Patch semantics" (A3). Every part not named is kept as the rule has it.
 *
 * @param message                  the message matcher: set (with {@code messageIgnoreCase} saying
 *                                 which of the two forms), cleared, or unchanged
 * @param messageIgnoreCase        meaningful only when {@code message} is {@link Field#set set}
 * @param throwableType            {@code --throwable} / {@code --no-throwable}
 * @param throwableMessageContains {@code --throwable-message-contains} / its {@code --no-} form
 * @param anyCause                 {@code --any-cause} / {@code --no-any-cause}; {@code null} = unchanged
 * @param levelAtMost              the compiled {@code --below} bound; {@code null} = unchanged
 * @param sampleFull               drop only; {@code null} = unchanged
 * @param frames                   trim only; {@code null} = unchanged
 * @param collapseCauses           trim only; {@code null} = unchanged
 * @param reason                   {@code null} = unchanged
 */
public record RuleChange(
        Field<String> message,
        boolean messageIgnoreCase,
        Field<String> throwableType,
        Field<String> throwableMessageContains,
        Boolean anyCause,
        Level levelAtMost,
        SampleFullPolicy sampleFull,
        Integer frames,
        Boolean collapseCauses,
        String reason) {

    public RuleChange {
        message = message == null ? Field.unchanged() : message;
        throwableType = throwableType == null ? Field.unchanged() : throwableType;
        throwableMessageContains = throwableMessageContains == null ? Field.unchanged() : throwableMessageContains;
        if (frames != null && frames < 0) {
            throw new IllegalArgumentException("frames must be >= 0, got " + frames);
        }
    }

    /** Changes nothing -- the starting point for {@code with…}-style construction in tests. */
    public static RuleChange none() {
        return new RuleChange(null, false, null, null, null, null, null, null, null, null);
    }

    /** {@code true} if no part is named; a lifetime-only alter is still legal (A6). */
    public boolean isEmpty() {
        return !message.isChanged() && !throwableType.isChanged() && !throwableMessageContains.isChanged()
                && anyCause == null && levelAtMost == null && sampleFull == null && frames == null
                && collapseCauses == null && reason == null;
    }

    /** {@code true} if a part only a drop has is named. */
    public boolean touchesDropOnly() {
        return sampleFull != null;
    }

    /** {@code true} if a part only a trim has is named. */
    public boolean touchesTrimOnly() {
        return frames != null || collapseCauses != null;
    }

    /** The matchers {@code current} becomes under this change. */
    public CompiledMatchers applyTo(CompiledMatchers current) {
        String newMessage = message.applyTo(current.messageContains());
        boolean newIgnoreCase = message.kind() == Field.Kind.SET ? messageIgnoreCase
                : newMessage != null && current.messageIgnoreCase();
        return new CompiledMatchers(
                levelAtMost != null ? levelAtMost : current.levelAtMost(),
                newMessage,
                newIgnoreCase,
                throwableType.applyTo(current.throwableType()),
                throwableMessageContains.applyTo(current.throwableMessageContains()),
                anyCause != null ? anyCause : current.anyCause());
    }

    /**
     * One optional part of a rule: unchanged, set to a value, or cleared.
     *
     * @param <T> the part's value type
     */
    public record Field<T>(Kind kind, T value) {

        public enum Kind { UNCHANGED, SET, CLEARED }

        public Field {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.SET) {
                Objects.requireNonNull(value, "value");
            } else if (value != null) {
                throw new IllegalArgumentException("only a SET field carries a value");
            }
        }

        public static <T> Field<T> unchanged() {
            return new Field<>(Kind.UNCHANGED, null);
        }

        public static <T> Field<T> set(T value) {
            return new Field<>(Kind.SET, value);
        }

        public static <T> Field<T> cleared() {
            return new Field<>(Kind.CLEARED, null);
        }

        public boolean isChanged() {
            return kind != Kind.UNCHANGED;
        }

        public T applyTo(T current) {
            return switch (kind) {
                case UNCHANGED -> current;
                case SET -> value;
                case CLEARED -> null;
            };
        }
    }
}
