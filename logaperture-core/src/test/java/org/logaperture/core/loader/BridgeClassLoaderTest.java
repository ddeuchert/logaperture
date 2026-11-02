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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/class-loader-detector.md C2 and C3; spike SB2, TC6. */
class BridgeClassLoaderTest {

    private static final String ENTRY = "org.logaperture.fixture.bridge.BridgeEntry";
    private static final String OWNER_TYPE = "org.logaperture.fixture.owner.OwnerType";
    private static final List<String> OWNER_PACKAGES = List.of("org.logaperture.fixture.owner.");

    /** The adapter's class files, read from the test class path as a jar would supply them. */
    private static final BridgeSource ADAPTER_CLASSES = name -> {
        if (!name.startsWith("org.logaperture.fixture.bridge.")) {
            return null;
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    };

    private static BridgeClassLoader bridgeOver(ClassLoader owner) {
        return new BridgeClassLoader(owner, OWNER_PACKAGES, ClassLoader.getSystemClassLoader(), ADAPTER_CLASSES);
    }

    private static Object newEntry(BridgeClassLoader bridge) throws Exception {
        return bridge.loadClass(ENTRY).getDeclaredConstructor().newInstance();
    }

    private static Object call(Object entry, String method) throws Exception {
        return entry.getClass().getMethod(method).invoke(entry);
    }

    @Test
    void ownerTypesComeFromTheOwnerNotTheSystemLoader() throws Exception {
        FixtureOwnerLoader owner = new FixtureOwnerLoader(OWNER_TYPE);
        Class<?> ownersCopy = owner.loadClass(OWNER_TYPE);
        assertNotSame(ClassLoader.getSystemClassLoader().loadClass(OWNER_TYPE), ownersCopy);

        Object entry = newEntry(bridgeOver(owner));

        assertSame(ownersCopy, call(entry, "ownerType"));
    }

    @Test
    void logApertureTypesComeFromTheSystemLoader() throws Exception {
        Object entry = newEntry(bridgeOver(new FixtureOwnerLoader(OWNER_TYPE)));

        assertSame(org.logaperture.core.spi.ContextHandle.class, call(entry, "sharedType"));
    }

    @Test
    void theAdapterIsDefinedByTheBridgeAndNotTheSystemOrOwnerLoader() throws Exception {
        BridgeClassLoader bridge = bridgeOver(new FixtureOwnerLoader(OWNER_TYPE));

        Object entry = newEntry(bridge);

        assertSame(bridge, call(entry, "definingLoader"));
        assertNotSame(ClassLoader.getSystemClassLoader().loadClass(ENTRY), entry.getClass());
    }

    @Test
    void jdkTypesComeFromThePlatformLoader() throws Exception {
        Object entry = newEntry(bridgeOver(new FixtureOwnerLoader(OWNER_TYPE)));

        assertSame(java.sql.Driver.class, call(entry, "jdkType"));
        assertSame(String.class, bridgeOver(new FixtureOwnerLoader()).loadClass("java.lang.String"));
    }

    /** C3: a class in none of the four sets is an error, not a fall-through to the owner or system. */
    @Test
    void aClassOutsideEverySetIsNotFound() {
        BridgeClassLoader bridge = bridgeOver(new FixtureOwnerLoader());

        assertThrows(ClassNotFoundException.class, () -> bridge.loadClass("org.junit.jupiter.api.Test"));
        assertThrows(ClassNotFoundException.class,
                () -> bridge.loadClass("org.logaperture.fixture.bridge.NoSuchAdapterClass"));
    }

    /** TC6: the owner stops, and the bridge still loads the JDK, LogAperture's types and its own. */
    @Test
    void worksAfterTheOwnerHasStopped() throws Exception {
        FixtureOwnerLoader owner = new FixtureOwnerLoader(OWNER_TYPE);
        BridgeClassLoader bridge = bridgeOver(owner);
        owner.stop();

        Object entry = newEntry(bridge);

        assertSame(java.sql.Driver.class, call(entry, "jdkType"));
        assertSame(org.logaperture.core.spi.ContextHandle.class, call(entry, "sharedType"));
        assertSame(String.class, bridge.loadClass("java.lang.String"));
    }

    @Test
    void theBridgeIsNotChildOfTheOwner() {
        assertNull(bridgeOver(new FixtureOwnerLoader()).getParent());
    }

    @Test
    void aClosedBridgeLoadsNothingFurther() throws Exception {
        BridgeClassLoader bridge = bridgeOver(new FixtureOwnerLoader(OWNER_TYPE));
        bridge.close();

        assertThrows(ClassNotFoundException.class, () -> bridge.loadClass(ENTRY));
    }

    /** A closed bridge that is itself still referenced must not keep the owner alive (WF6). */
    @Test
    void aClosedBridgeDoesNotKeepTheOwnerAlive() throws Exception {
        BridgeClassLoader bridge;
        WeakReference<ClassLoader> ownerRef;
        {
            FixtureOwnerLoader owner = new FixtureOwnerLoader(OWNER_TYPE);
            ownerRef = new WeakReference<>(owner);
            bridge = bridgeOver(owner);
            newEntry(bridge);
        }
        bridge.close();

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (ownerRef.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(ownerRef.get(), "the closed bridge kept the owner reachable");
        assertNotNull(bridge);
    }

    /** C2: class files under a prefix in a jar are readable by the bridge but not loadable normally. */
    @Test
    void readsClassFilesStoredUnderAPrefixInAJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("agent.jar");
        String entryName = ENTRY.replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out);
             InputStream in = ClassLoader.getSystemResourceAsStream(entryName)) {
            jos.putNextEntry(new JarEntry("META-INF/lap/test/" + entryName));
            jos.write(in.readAllBytes());
            jos.closeEntry();
        }
        try (URLClassLoader reader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertThrows(ClassNotFoundException.class, () -> reader.loadClass(ENTRY)); // not at the root
            BridgeClassLoader bridge = new BridgeClassLoader(new FixtureOwnerLoader(OWNER_TYPE), OWNER_PACKAGES,
                    ClassLoader.getSystemClassLoader(), BridgeSource.underPrefix(reader, "META-INF/lap/test"));

            Object entry = newEntry(bridge);

            assertSame(bridge, call(entry, "definingLoader"));
            assertEquals(ENTRY, entry.getClass().getName());
            assertTrue(BridgeSource.underPrefix(reader, "META-INF/lap/test/").classBytes(ENTRY) != null);
            assertNull(BridgeSource.underPrefix(reader, "META-INF/lap/test").classBytes("no.Such"));
        }
    }
}
