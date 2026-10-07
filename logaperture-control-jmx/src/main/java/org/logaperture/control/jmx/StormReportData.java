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
import org.logaperture.core.StormDetectionSwitch;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link StormReport} — see {@link TopReportData}
 * for the analogous {@code top} shape. {@code trackedCount}/{@code
 * ongoingCount} are the true pre-{@code --limit} counts, independent of
 * {@code storms}' own size once truncated.
 *
 * <p>{@code detectionEnabled}/{@code detectionChangedAt} say whether storm detection is enabled
 * (doc/specs/storm-detection-toggle.md "How it is reported"). A report from an agent that predates
 * the switch reconstructs through the narrower constructor, as enabled -- which it always was.
 */
public final class StormReportData {

    private final List<StormData> storms;
    private final int trackedCount;
    private final int ongoingCount;
    private final String measurementStartedAt;
    private final int notRetainedCount;
    private final boolean detectionEnabled;
    private final String detectionChangedAt;

    @ConstructorProperties({"storms", "trackedCount", "ongoingCount", "measurementStartedAt", "notRetainedCount",
            "detectionEnabled", "detectionChangedAt"})
    public StormReportData(List<StormData> storms, int trackedCount, int ongoingCount, String measurementStartedAt,
            int notRetainedCount, boolean detectionEnabled, String detectionChangedAt) {
        this.storms = storms;
        this.trackedCount = trackedCount;
        this.ongoingCount = ongoingCount;
        this.measurementStartedAt = measurementStartedAt;
        this.notRetainedCount = notRetainedCount;
        this.detectionEnabled = detectionEnabled;
        this.detectionChangedAt = detectionChangedAt;
    }

    /** The shape before the storm-detection switch: always enabled, never changed. */
    @ConstructorProperties({"storms", "trackedCount", "ongoingCount", "measurementStartedAt", "notRetainedCount"})
    public StormReportData(List<StormData> storms, int trackedCount, int ongoingCount, String measurementStartedAt,
            int notRetainedCount) {
        this(storms, trackedCount, ongoingCount, measurementStartedAt, notRetainedCount, true, null);
    }

    public static StormReportData from(StormReport report) {
        return from(report, new StormDetectionSwitch.State(true, true, null));
    }

    public static StormReportData from(StormReport report, StormDetectionSwitch.State detection) {
        List<StormData> storms = report.storms().stream().map(StormData::from).toList();
        String startedAt = report.measurementStartedAt() == null ? null : report.measurementStartedAt().toString();
        String changedAt = detection.changedAt() == null ? null : detection.changedAt().toString();
        return new StormReportData(storms, report.trackedCount(), report.ongoingCount(), startedAt,
                report.notRetainedCount(), detection.enabled(), changedAt);
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

    public boolean isDetectionEnabled() {
        return detectionEnabled;
    }

    /** The last runtime change to storm detection, or {@code null} while still in its starting position. */
    public String getDetectionChangedAt() {
        return detectionChangedAt;
    }
}
