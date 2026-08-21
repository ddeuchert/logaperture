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

/**
 * LogAperture's own logging-level model, independent of any single
 * framework's {@code Level} type — see doc/logaperture-spec.md §4.6: {@code
 * logaperture-api} stays framework-independent, and each adapter maps
 * between this enum and its framework's own level type (see {@code
 * logaperture-adapter-logback}'s {@code LevelMapper}).
 *
 * <p>Declared verbose-to-quiet, so this enum's natural ordering ({@link
 * #compareTo}, ordinal order) doubles as the verbosity ordering — no
 * separate comparator needed anywhere a caller needs to know whether one
 * level is "more verbose than" another.
 *
 * <p>{@code ALL} and {@code OFF} are included alongside the five
 * conventional levels so this type round-trips losslessly against a real
 * {@code logback.xml} (or equivalent), which can legitimately configure a
 * logger at either extreme.
 */
public enum Level {
    ALL,
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OFF;

    /**
     * @return {@code true} if this level is strictly more verbose than {@code other}
     */
    public boolean isMoreVerboseThan(Level other) {
        return this.compareTo(other) < 0;
    }
}
