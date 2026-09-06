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

import org.logaperture.core.TopReport;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link TopReport} — {@code measurementStartedAt}
 * is ISO-8601, or {@code null} if measurement hasn't started (no persistent
 * handler exists anywhere to count against), same nullable-timestamp
 * convention as {@link LevelOverrideData#getExpiresAt()}. {@code
 * trackedCount} is the true number of tracked loggers, independent of
 * {@code loggers}' own size once {@code --limit} has truncated it — see
 * {@link TopReport#trackedCount()}.
 */
public final class TopReportData {

    private final List<LoggerByteCountData> loggers;
    private final String measurementStartedAt;
    private final int trackedCount;

    @ConstructorProperties({"loggers", "measurementStartedAt", "trackedCount"})
    public TopReportData(List<LoggerByteCountData> loggers, String measurementStartedAt, int trackedCount) {
        this.loggers = loggers;
        this.measurementStartedAt = measurementStartedAt;
        this.trackedCount = trackedCount;
    }

    public static TopReportData from(TopReport report) {
        List<LoggerByteCountData> loggers = report.loggers().stream().map(LoggerByteCountData::from).toList();
        String startedAt = report.measurementStartedAt() == null ? null : report.measurementStartedAt().toString();
        return new TopReportData(loggers, startedAt, report.trackedCount());
    }

    public List<LoggerByteCountData> getLoggers() {
        return loggers;
    }

    public String getMeasurementStartedAt() {
        return measurementStartedAt;
    }

    public int getTrackedCount() {
        return trackedCount;
    }
}
