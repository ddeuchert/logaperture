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

/**
 * {@code logctl top}'s public contract — the {@link DoctorOperations}
 * counterpart for byte-volume measurement (doc/specs/top.md). Every control
 * surface is a client of this interface, same as {@link DoctorOperations}.
 */
public interface TopOperations {

    /**
     * Every tracked logger's byte counts, sorted worst-first by {@code
     * totalBytes}, alongside when measurement began.
     *
     * @param limit keep only the {@code limit} worst offenders; {@code
     *              limit <= 0} means "every tracked logger"
     *              (doc/specs/top.md Decision #9)
     */
    TopReport topLoggers(int limit);
}
