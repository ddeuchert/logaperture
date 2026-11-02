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
import java.util.Set;

/**
 * An owner loader: defines its own copy of the named classes, takes the rest from the system
 * loader, and can be "stopped", after which it refuses every class -- the JDK's too -- as a stopped
 * Tomcat webapp loader does (spike TC6).
 */
final class FixtureOwnerLoader extends ClassLoader {

    private final Set<String> own;
    private volatile boolean stopped;

    FixtureOwnerLoader(String... ownClasses) {
        super(FixtureOwnerLoader.class.getClassLoader());
        this.own = Set.of(ownClasses);
    }

    void stop() {
        stopped = true;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (stopped) {
            throw new IllegalStateException("webapp loader is stopped: " + name);
        }
        if (!own.contains(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] bytes = in.readAllBytes();
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            return loaded;
        }
    }
}
