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
package org.logaperture.core.loader;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The class loaders already handled for one framework, held weakly and compared by identity
 * (doc/specs/class-loader-detector.md, "Scope" 3).
 *
 * <p>Never a {@code WeakHashMap}, {@code HashSet} or anything else that calls {@code equals} or
 * {@code hashCode} on a loader: JBoss Modules' {@code ModuleClassLoader} overrides both, so the
 * old and the new loader of a redeployed deployment compare equal and the new one would be
 * mistaken for the one already bound (spike WF5).
 */
final class LoaderIdentitySet {

    private final List<WeakReference<ClassLoader>> loaders = new ArrayList<>();

    /** Adds {@code loader} unless the very same object is already present. @return whether it was added. */
    synchronized boolean add(ClassLoader loader) {
        purge();
        for (WeakReference<ClassLoader> ref : loaders) {
            if (ref.get() == loader) {
                return false;
            }
        }
        loaders.add(new WeakReference<>(loader));
        return true;
    }

    /** Forgets {@code loader}, so a later scan that finds it again binds it again. */
    synchronized void remove(ClassLoader loader) {
        loaders.removeIf(ref -> ref.get() == loader);
    }

    /** How many loaders are still alive in the set. */
    synchronized int size() {
        purge();
        return loaders.size();
    }

    private void purge() {
        loaders.removeIf(ref -> ref.get() == null);
    }
}
