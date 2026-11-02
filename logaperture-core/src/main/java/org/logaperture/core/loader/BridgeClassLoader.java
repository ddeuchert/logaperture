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

import java.util.List;
import java.util.Objects;

/**
 * The class loader that holds one framework adapter (doc/specs/class-loader-detector.md, C2, C3).
 * One per bound owner; closed when its instance lets go.
 *
 * <p>The adapter has to link against the framework in the <em>owner's</em> loader (a fat jar's
 * {@code LaunchedClassLoader}, a webapp's loader, a WildFly module) and against LogAperture's own
 * types in the system loader. It cannot be defined by either: the system loader cannot see the
 * framework, and the owner's loader does not know the adapter. So this loader sits beside both and
 * delegates narrowly, in this order:
 *
 * <ol>
 *   <li>the JDK, from the platform loader;</li>
 *   <li>the framework's packages ({@code ownerPackages}), from the owner;</li>
 *   <li>LogAperture's {@code api}, {@code core} and {@code bridge} packages, from the system loader
 *       (the agent's, which is never stopped);</li>
 *   <li>everything else the {@link BridgeSource} holds, defined here.</li>
 * </ol>
 *
 * <p>A class outside all four is a {@link ClassNotFoundException}, not a fall-through to the owner:
 * a stopped Tomcat webapp loader refuses even JDK classes, so a bridge loader whose parent was the
 * webapp loader failed at shutdown (spike TC6). The parent here is the bootstrap loader, and the
 * owner is consulted only for its own packages.
 */
public final class BridgeClassLoader extends ClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    /** Package prefixes the system class loader supplies: LogAperture's own, shared types. */
    static final List<String> SHARED_PACKAGES = List.of(
            "org.logaperture.api.", "org.logaperture.core.", "org.logaperture.bridge.");

    private static final List<String> JDK_PACKAGES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.", "org.ietf.", "netscape.");

    private final List<String> ownerPackages;
    private final BridgeSource source;
    private volatile ClassLoader owner;
    private volatile ClassLoader system;

    /**
     * @param owner         the loader that holds the framework
     * @param ownerPackages package prefixes (each ending in a dot) that come from {@code owner}
     * @param system        the loader that holds LogAperture's shared packages
     * @param source        where the adapter's own classes are read from
     */
    public BridgeClassLoader(ClassLoader owner, List<String> ownerPackages, ClassLoader system,
                             BridgeSource source) {
        super(null);
        this.owner = Objects.requireNonNull(owner, "owner");
        this.system = Objects.requireNonNull(system, "system");
        this.ownerPackages = List.copyOf(ownerPackages);
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Cuts this loader's references to the owner and the system loader, so a bridge that outlives
     * its framework (an adapter instance still being collected) cannot keep the owner alive, and
     * refuses to define anything further.
     */
    public void close() {
        owner = null;
        system = null;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = locate(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> locate(String name) throws ClassNotFoundException {
        if (startsWithAny(name, JDK_PACKAGES)) {
            try {
                return ClassLoader.getPlatformClassLoader().loadClass(name);
            } catch (ClassNotFoundException notTheJdk) {
                // javax.servlet, javax.inject and the like are the owner's, and fall through
                if (name.startsWith("java.")) {
                    throw notTheJdk;
                }
            }
        }
        ClassLoader currentOwner = owner;
        ClassLoader currentSystem = system;
        if (currentOwner == null || currentSystem == null) {
            throw new ClassNotFoundException(name + " (bridge loader closed)");
        }
        if (startsWithAny(name, ownerPackages)) {
            return currentOwner.loadClass(name);
        }
        if (startsWithAny(name, SHARED_PACKAGES)) {
            return currentSystem.loadClass(name);
        }
        byte[] bytes = source.classBytes(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // Resources are not delegated: nothing the adapter needs is a resource, and a lookup through
    // the owner is exactly the dependency on the owner's liveness that TC6 ruled out.
}
