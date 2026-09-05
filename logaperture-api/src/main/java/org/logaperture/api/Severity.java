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
 * How urgently a {@link DoctorFinding} needs attention — see
 * doc/specs/doctor.md "Data model". Declared least-to-most severe so this
 * enum's natural ordering doubles as the escalation ordering (Decision #6's
 * cross-check escalation compares two {@code Severity} values directly).
 */
public enum Severity {
    OK,
    INFO,
    WARNING,
    CRITICAL
}
