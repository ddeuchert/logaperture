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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An {@link AuditLog} that accumulates records in memory — for tests. */
public final class InMemoryAuditLog implements AuditLog {

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditRecord record) {
        records.add(record);
    }

    public List<AuditRecord> records() {
        return List.copyOf(records);
    }
}
