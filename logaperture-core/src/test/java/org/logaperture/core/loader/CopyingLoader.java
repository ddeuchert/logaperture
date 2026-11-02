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

import java.io.IOException;
import java.io.InputStream;

/**
 * Defines its own copy of {@link ScannerMarker}, as a webapp loader would of a bundled framework.
 * With {@code equalToAll} it also claims to equal every other loader, as JBoss Modules'
 * {@code ModuleClassLoader} does for a redeployed deployment (spike WF5).
 */
final class CopyingLoader extends ClassLoader {

    private final boolean equalToAll;

    CopyingLoader(boolean equalToAll) {
        super(CopyingLoader.class.getClassLoader());
        this.equalToAll = equalToAll;
    }

    /** The marker class as defined by this loader (not the parent's). */
    Class<?> markerCopy() throws ClassNotFoundException {
        return loadClass(ScannerMarker.class.getName());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.equals(ScannerMarker.class.getName())) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                String resource = name.replace('.', '/') + ".class";
                try (InputStream in = getParent().getResourceAsStream(resource)) {
                    byte[] bytes = in.readAllBytes();
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            return loaded;
        }
    }

    @Override
    public boolean equals(Object other) {
        return equalToAll ? other instanceof ClassLoader : super.equals(other);
    }

    @Override
    public int hashCode() {
        return equalToAll ? 0 : super.hashCode();
    }
}
