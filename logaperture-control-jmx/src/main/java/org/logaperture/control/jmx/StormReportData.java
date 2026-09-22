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

import org.logaperture.api.StormReport;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link StormReport} — see {@link TopReportData}
 * for the analogous {@code top} shape. {@code trackedCount}/{@code
 * ongoingCount} are the true pre-{@code --limit} counts, independent of
 * {@code storms}' own size once truncated.
 */
public final class StormReportData {

    private final List<StormData> storms;
    private final int trackedCount;
    private final int ongoingCount;
    private final String measurementStartedAt;
    private final int notRetainedCount;

    @ConstructorProperties({"storms", "trackedCount", "ongoingCount", "measurementStartedAt", "notRetainedCount"})
    public StormReportData(List<StormData> storms, int trackedCount, int ongoingCount, String measurementStartedAt,
            int notRetainedCount) {
        this.storms = storms;
        this.trackedCount = trackedCount;
        this.ongoingCount = ongoingCount;
        this.measurementStartedAt = measurementStartedAt;
        this.notRetainedCount = notRetainedCount;
    }

    public static StormReportData from(StormReport report) {
        List<StormData> storms = report.storms().stream().map(StormData::from).toList();
        String startedAt = report.measurementStartedAt() == null ? null : report.measurementStartedAt().toString();
        return new StormReportData(storms, report.trackedCount(), report.ongoingCount(), startedAt,
                report.notRetainedCount());
    }

    public List<StormData> getStorms() {
        return storms;
    }

    public int getTrackedCount() {
        return trackedCount;
    }

    public int getOngoingCount() {
        return ongoingCount;
    }

    public String getMeasurementStartedAt() {
        return measurementStartedAt;
    }

    public int getNotRetainedCount() {
        return notRetainedCount;
    }
}
