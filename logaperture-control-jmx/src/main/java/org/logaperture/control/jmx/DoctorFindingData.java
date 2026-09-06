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

import org.logaperture.api.DoctorFinding;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link DoctorFinding} — same reasoning as
 * {@link LoggerInfoData}: a plain class with a {@link ConstructorProperties}
 * constructor and JavaBean getters rather than the {@code api} record
 * directly, since record-to-{@code CompositeType} MXBean support only
 * landed around JDK 20 and this project is pinned to Java 17. {@code
 * severity} is a {@code String} at this boundary, matching every other
 * enum-shaped field on this bean ({@code tier}, levels).
 */
public final class DoctorFindingData {

    private final String check;
    private final String severity;
    private final String subject;
    private final String summary;
    private final String detail;
    private final String suggestedFix;
    private final String context;

    @ConstructorProperties({"check", "severity", "subject", "summary", "detail", "suggestedFix", "context"})
    public DoctorFindingData(String check, String severity, String subject, String summary, String detail,
            String suggestedFix, String context) {
        this.check = check;
        this.severity = severity;
        this.subject = subject;
        this.summary = summary;
        this.detail = detail;
        this.suggestedFix = suggestedFix;
        this.context = context;
    }

    public static DoctorFindingData from(DoctorFinding finding) {
        return new DoctorFindingData(
                finding.check(),
                finding.severity().name(),
                finding.subject(),
                finding.summary(),
                finding.detail(),
                finding.suggestedFix(),
                finding.context());
    }

    public String getCheck() {
        return check;
    }

    public String getSeverity() {
        return severity;
    }

    public String getSubject() {
        return subject;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public String getSuggestedFix() {
        return suggestedFix;
    }

    public String getContext() {
        return context;
    }
}
