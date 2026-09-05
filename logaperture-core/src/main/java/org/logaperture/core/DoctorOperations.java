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
package org.logaperture.core;

import org.logaperture.api.DoctorFinding;

import java.util.List;

/**
 * {@code logctl doctor}'s public contract — the {@link
 * LevelControlOperations} counterpart for read-only diagnosis (doc/specs/
 * doctor.md). Every control surface is a client of this interface, same as
 * {@link LevelControlOperations}.
 */
public interface DoctorOperations {

    /**
     * Runs every check this slice implements and returns every finding —
     * including the "clean" {@code OK} rows for a check that ran and found
     * nothing wrong. A check with nothing it can inspect for this adapter
     * contributes no rows at all (doc/specs/doctor.md "Failure handling").
     */
    List<DoctorFinding> diagnose();
}
