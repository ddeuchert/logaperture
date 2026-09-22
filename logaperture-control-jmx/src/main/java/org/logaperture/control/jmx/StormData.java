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
package org.logaperture.control.jmx;

import org.logaperture.api.Storm;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link Storm} — same reasoning as {@link
 * DoctorFindingData}/{@link LoggerByteCountData}: a plain class with a
 * {@link ConstructorProperties} constructor and JavaBean getters, flattening
 * {@link org.logaperture.api.StormFingerprint} onto this bean rather than
 * nesting a second MXBean-shaped type. {@code status} is a {@code String},
 * matching every other enum-shaped field at this boundary. Timestamps are
 * ISO-8601, or {@code null} ({@code endedAt} when still {@code ONGOING}).
 */
public final class StormData {

    private final String loggerName;
    private final String level;
    private final String throwableClass;
    private final String normalizedMessage;
    private final List<String> topFrames;
    private final String status;
    private final String firstEventAt;
    private final String lastEventAt;
    private final String endedAt;
    private final long eventCount;
    private final String firstOccurrence;
    private final String context;

    @ConstructorProperties({"loggerName", "level", "throwableClass", "normalizedMessage", "topFrames", "status",
            "firstEventAt", "lastEventAt", "endedAt", "eventCount", "firstOccurrence", "context"})
    public StormData(String loggerName, String level, String throwableClass, String normalizedMessage,
            List<String> topFrames, String status, String firstEventAt, String lastEventAt, String endedAt,
            long eventCount, String firstOccurrence, String context) {
        this.loggerName = loggerName;
        this.level = level;
        this.throwableClass = throwableClass;
        this.normalizedMessage = normalizedMessage;
        this.topFrames = topFrames;
        this.status = status;
        this.firstEventAt = firstEventAt;
        this.lastEventAt = lastEventAt;
        this.endedAt = endedAt;
        this.eventCount = eventCount;
        this.firstOccurrence = firstOccurrence;
        this.context = context;
    }

    public static StormData from(Storm storm) {
        return new StormData(
                storm.fingerprint().loggerName(),
                storm.fingerprint().level().name(),
                storm.fingerprint().throwableClass(),
                storm.fingerprint().normalizedMessage(),
                storm.fingerprint().topFrames(),
                storm.status().name(),
                storm.firstEventAt().toString(),
                storm.lastEventAt().toString(),
                storm.endedAt() == null ? null : storm.endedAt().toString(),
                storm.eventCount(),
                storm.firstOccurrence(),
                storm.context());
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getLevel() {
        return level;
    }

    public String getThrowableClass() {
        return throwableClass;
    }

    public String getNormalizedMessage() {
        return normalizedMessage;
    }

    public List<String> getTopFrames() {
        return topFrames;
    }

    public String getStatus() {
        return status;
    }

    public String getFirstEventAt() {
        return firstEventAt;
    }

    public String getLastEventAt() {
        return lastEventAt;
    }

    public String getEndedAt() {
        return endedAt;
    }

    public long getEventCount() {
        return eventCount;
    }

    public String getFirstOccurrence() {
        return firstOccurrence;
    }

    public String getContext() {
        return context;
    }
}
