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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Handler;

/**
 * Names handlers by what they are -- {@code console}, {@code file}, {@code syslog}, {@code socket}
 * -- matching by simple class name, so the JDK's own {@code java.util.logging} handlers and JBoss
 * LogManager's are both named. Used by Quarkus (doc/specs/quarkus-support.md Q3: Quarkus keeps no
 * stable, readable handler name in the running JVM; the configured names are issue #222) and by a
 * plain JVM on {@code java.util.logging} (doc/specs/plain-jvm-jul.md #4).
 *
 * <p>A second handler of a type gets a numbered name ({@code file-2}). A name, once given to a
 * handler instance, is kept for as long as that instance lives, and a new instance takes the
 * first name no live handler holds, so a later handler can never be given a name already in use.
 * Which of several same-type handlers is {@code file} and which {@code file-2} follows the order
 * the adapter finds them in (on Quarkus, the handlers inside the root {@code QuarkusDelayedHandler}
 * first), which is the same on every start of the same configuration. A handler of any other type
 * is left unnamed, and the adapter falls back to its identity token for it.
 */
public final class TypeHandlerNameResolver implements HandlerNameResolver {

    private static final Map<String, String> TYPE_BY_CLASS_NAME = Map.of(
            "ConsoleHandler", "console",
            "FileHandler", "file",
            "SyslogHandler", "syslog",
            "SocketHandler", "socket");

    /** Handler instance to the name it was given; weak, so a replaced handler's name is free again. */
    private final Map<Handler, String> given = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public synchronized Map<Handler, String> resolve(List<Handler> handlers) {
        Map<Handler, String> names = new IdentityHashMap<>();
        Set<String> taken = new HashSet<>();
        List<Handler> unnamed = new java.util.ArrayList<>();
        for (Handler handler : handlers) {
            String kept = given.get(handler);
            if (kept != null) {
                names.put(handler, kept);
                taken.add(kept);
            } else if (typeOf(handler) != null) {
                unnamed.add(handler);
            }
        }
        for (Handler handler : unnamed) {
            String type = typeOf(handler);
            String name = type;
            for (int n = 2; taken.contains(name); n++) {
                name = type + "-" + n;
            }
            taken.add(name);
            given.put(handler, name);
            names.put(handler, name);
        }
        return new HashMap<>(names);
    }

    /** {@code console}, {@code file}, {@code syslog} or {@code socket} for a handler of that kind (or a subclass of it), else {@code null}. */
    static String typeOf(Handler handler) {
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            String known = TYPE_BY_CLASS_NAME.get(type.getSimpleName());
            if (known != null) {
                return known;
            }
        }
        return null;
    }
}
