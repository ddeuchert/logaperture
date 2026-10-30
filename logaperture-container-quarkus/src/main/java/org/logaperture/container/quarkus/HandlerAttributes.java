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
package org.logaperture.container.quarkus;

import org.logaperture.adapter.jul.JulLoggingAdapter;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Formatter;
import java.util.logging.Handler;

/**
 * Checks a running handler against the attributes the configuration states for the name paired with
 * it (doc/specs/quarkus-handler-names.md, amendment #10): a file handler's path, a syslog or socket
 * handler's host and port, a console handler's format. Never the level: {@code set handler} changes it.
 *
 * <p>All by reflection, as the handler classes are Quarkus's and JBoss LogManager's. An attribute the
 * handler does not let us read is not held against the pair; the pair then counts as matched by type
 * alone ({@link Result#confirmed()} false), which {@code doctor} can report.
 */
final class HandlerAttributes {

    /** Quarkus's console handlers wrap their pattern formatter in JBoss LogManager's banner formatter. */
    private static final String DELEGATING_FORMATTER = "org.jboss.logmanager.ExtFormatter$Delegating";

    private HandlerAttributes() {
    }

    /**
     * @param confirmed at least one configured attribute was compared and agreed
     * @param mismatch  what disagreed, for {@code doctor}; {@code null} when nothing did
     */
    record Result(boolean confirmed, String mismatch) {
        static final Result TYPE_ONLY = new Result(false, null);
    }

    static Result check(String name, String type, Map<String, String> configured, Handler handler) {
        return switch (type) {
            case "file" -> checkPath(name, configured.get("path"), handler);
            case "syslog" -> checkEndpoint(name, configured.get("endpoint"), handler, "getServerAddress");
            case "socket" -> checkEndpoint(name, configured.get("endpoint"), handler, "getAddress");
            case "console" -> checkFormat(name, configured.get("format"), handler);
            default -> Result.TYPE_ONLY;
        };
    }

    private static Result checkPath(String name, String configured, Handler handler) {
        if (configured == null) {
            return Result.TYPE_ONLY;
        }
        Object file = call(handler, "getFile");
        if (!(file instanceof File actual)) {
            return Result.TYPE_ONLY;
        }
        // Quarkus opens a relative path against the working directory, as toAbsolutePath does.
        Path expected = Path.of(configured).toAbsolutePath().normalize();
        Path written = actual.toPath().toAbsolutePath().normalize();
        if (expected.equals(written)) {
            return new Result(true, null);
        }
        return new Result(false, name + ": the configuration says path " + expected + ", the handler writes " + written);
    }

    private static Result checkEndpoint(String name, String configured, Handler handler, String addressGetter) {
        if (configured == null) {
            return Result.TYPE_ONLY;
        }
        int colon = configured.lastIndexOf(':');
        Object port = call(handler, "getPort");
        if (colon < 0 || !(port instanceof Integer actualPort)) {
            return Result.TYPE_ONLY;
        }
        String expectedHost = configured.substring(0, colon).replace("[", "").replace("]", "");
        int expectedPort;
        try {
            expectedPort = Integer.parseInt(configured.substring(colon + 1).trim());
        } catch (NumberFormatException notAPort) {
            return Result.TYPE_ONLY;
        }
        Object address = call(handler, addressGetter);
        if (expectedPort == actualPort && hostAgrees(address, expectedHost)) {
            return new Result(true, null);
        }
        return new Result(false, name + ": the configuration says endpoint " + configured + ", the handler sends to "
                + (address instanceof InetAddress inet ? inet + " port " : "port ") + actualPort);
    }

    /**
     * Whether {@code address} is the host {@code expected} names, without a DNS lookup: an address
     * literal is compared by its bytes, a host name with the name the address was made for. When that
     * cannot be told (no address, or one made from a literal while the configuration gives a name),
     * the host is not held against the pair.
     */
    private static boolean hostAgrees(Object address, String expected) {
        if (!(address instanceof InetAddress inet)) {
            return true;
        }
        if (isAddressLiteral(expected)) {
            try {
                return InetAddress.getByName(expected).equals(inet); // a literal: parsed, not looked up
            } catch (java.net.UnknownHostException | RuntimeException notALiteral) {
                return true;
            }
        }
        String text = inet.toString(); // "name/literal", or "/literal" when made from a literal
        int slash = text.indexOf('/');
        return slash <= 0 || text.substring(0, slash).equalsIgnoreCase(expected);
    }

    private static boolean isAddressLiteral(String host) {
        return host.contains(":") || (!host.isEmpty() && host.chars().allMatch(c -> c == '.' || Character.isDigit(c)));
    }

    private static Result checkFormat(String name, String configured, Handler handler) {
        if (configured == null) {
            return Result.TYPE_ONLY;
        }
        Object pattern = call(patternFormatterOf(handler.getFormatter()), "getPattern");
        if (!(pattern instanceof String actual)) {
            return Result.TYPE_ONLY; // not a pattern formatter: a JSON console, say, where format does not apply
        }
        if (actual.equals(configured)) {
            return new Result(true, null);
        }
        return new Result(false, name + ": the configuration says format " + configured + ", the handler uses " + actual);
    }

    /** The formatter under LogAperture's wraps and JBoss LogManager's delegating ones (the banner). */
    private static Formatter patternFormatterOf(Formatter formatter) {
        Formatter current = JulLoggingAdapter.originalFormatter(formatter);
        for (int depth = 0; current != null && depth < 8; depth++) {
            Formatter inner = delegateOf(current);
            if (inner == null) {
                return current;
            }
            current = JulLoggingAdapter.originalFormatter(inner);
        }
        return current;
    }

    private static Formatter delegateOf(Formatter formatter) {
        for (Class<?> type = formatter.getClass(); type != null; type = type.getSuperclass()) {
            if (DELEGATING_FORMATTER.equals(type.getName())) {
                try {
                    Field delegate = type.getDeclaredField("delegate");
                    delegate.setAccessible(true);
                    return delegate.get(formatter) instanceof Formatter inner ? inner : null;
                } catch (ReflectiveOperationException | RuntimeException notReadable) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object call(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException notThere) {
            return null;
        }
    }
}
