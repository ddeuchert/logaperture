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
package org.logaperture.fixture.bridge;

import org.logaperture.core.spi.ContextHandle;
import org.logaperture.fixture.owner.OwnerType;

/** Stands in for an adapter entry class: it links to the owner's, LogAperture's and the JDK's types. */
public final class BridgeEntry {

    public Class<?> ownerType() {
        return OwnerType.class;
    }

    public Class<?> sharedType() {
        return ContextHandle.class;
    }

    public Class<?> jdkType() {
        return java.sql.Driver.class; // a platform-loader module, not java.base
    }

    public ClassLoader definingLoader() {
        return BridgeEntry.class.getClassLoader();
    }
}
