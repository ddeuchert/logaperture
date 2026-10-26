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
package org.logaperture.adapter.jul;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TypeHandlerNameResolverTest {

    /** Named like the JBoss LogManager types so the by-simple-name rule is what's tested. */
    private static class FileHandler extends Handler {
        @Override public void publish(LogRecord record) { }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static final class SizeRotatingFileHandler extends FileHandler { }

    private static final class SyslogHandler extends FileHandler { }

    private static final class SocketHandler extends Handler {
        @Override public void publish(LogRecord record) { }
        @Override public void flush() { }
        @Override public void close() { }
    }

    @Test
    void namesByType() {
        Handler console = new ConsoleHandler();
        Handler file = new FileHandler();
        Handler syslog = new SyslogHandler();
        Handler socket = new SocketHandler();
        Map<Handler, String> names = new TypeHandlerNameResolver().resolve(List.of(console, file, syslog, socket));
        assertEquals("console", names.get(console));
        assertEquals("file", names.get(file));
        assertEquals("syslog", names.get(syslog), "the nearest known type wins over a FileHandler ancestor");
        assertEquals("socket", names.get(socket));
        console.close();
    }

    @Test
    void aSubclassOfAKnownTypeIsThatType() {
        Handler rotating = new SizeRotatingFileHandler();
        assertEquals("file", new TypeHandlerNameResolver().resolve(List.of(rotating)).get(rotating));
    }

    @Test
    void anUnknownTypeIsLeftUnnamed() {
        Handler other = new StreamHandler();
        assertFalse(new TypeHandlerNameResolver().resolve(List.of(other)).containsKey(other));
    }

    @Test
    void aSecondHandlerOfATypeIsNumbered() {
        Handler first = new FileHandler();
        Handler second = new FileHandler();
        Map<Handler, String> names = new TypeHandlerNameResolver().resolve(List.of(first, second));
        assertEquals("file", names.get(first));
        assertEquals("file-2", names.get(second));
    }

    @Test
    void aNameOnceGivenIsKept_andALaterHandlerNeverTakesANameInUse() {
        TypeHandlerNameResolver resolver = new TypeHandlerNameResolver();
        Handler first = new FileHandler();
        resolver.resolve(List.of(first));

        Handler later = new FileHandler();
        // the new handler comes first in the list: it still must not take "file"
        Map<Handler, String> names = resolver.resolve(List.of(later, first));
        assertEquals("file", names.get(first));
        assertEquals("file-2", names.get(later));
        assertNotEquals(names.get(first), names.get(later));
    }
}
