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

import org.logaperture.api.HandlerDiagnostics;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.logging.Handler;

/**
 * Best-effort resolution of a live {@link Handler}'s own configuration —
 * doc/specs/doctor.md "Adapter SPI". Every lookup here goes through {@code
 * handler.getClass()} (never {@code Class.forName} by name), the same
 * classloader-safe discipline the retired {@code JbossHandlerNames} used —
 * see that history in {@code JulLoggingAdapter}'s class doc.
 *
 * <p>Unlike that retired attempt, the facts here are real, WildFly-populated
 * configuration, not something already confirmed absent: {@code
 * org.jboss.logmanager.ExtHandler} — the common base of every JBoss
 * LogManager handler ({@code FileHandler}, {@code ConsoleHandler}, the
 * periodic/size-rotating file handlers) — publicly exposes {@code
 * isAutoFlush()}, and {@code FileHandler} publicly exposes {@code getFile()}.
 * The one genuinely uncertain part is {@code maxFileSizeBytes}/{@code
 * backupCount}: {@code SizeRotatingFileHandler}/{@code
 * PeriodicSizeRotatingFileHandler} keep {@code rotateSize}/{@code
 * maxBackupIndex} as private fields with no public getter, so reading them
 * needs {@link Field#setAccessible}, which JBoss Modules may or may not
 * permit for a class this adapter's own classloader doesn't own — confirmed
 * against real WildFly by {@code WildFlyContainerIT}, not assumed here. On
 * any failure this degrades to {@code null} for that one fact, same
 * complete-degrade-to-empty discipline as everywhere else in this class.
 *
 * <p>A plain {@code PeriodicRotatingFileHandler} (WildFly's stock {@code
 * FILE}, time-based rotation only) has no {@code rotateSize}/{@code
 * maxBackupIndex} fields at all — {@code maxFileSizeBytes}/{@code
 * backupCount} are {@code null} for it deterministically, no reflection risk
 * involved, which is exactly doctor's "unbounded growth" finding: stock
 * WildFly's {@code FILE} genuinely has no size-based cap.
 */
final class JulHandlerDiagnostics {

    private JulHandlerDiagnostics() {
    }

    static HandlerDiagnostics of(Handler handler) {
        Path targetPath = targetPathOf(handler);
        Boolean autoFlush = autoFlushOf(handler);
        Long maxFileSizeBytes = longFieldOf(handler, "rotateSize");
        Integer backupCount = intFieldOf(handler, "maxBackupIndex");
        return new HandlerDiagnostics(maxFileSizeBytes, backupCount, autoFlush, targetPath);
    }

    private static Path targetPathOf(Handler handler) {
        try {
            Method getFile = handler.getClass().getMethod("getFile");
            Object file = getFile.invoke(handler);
            return file instanceof File f ? f.toPath() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // not a file-backed handler (console), or this handler type doesn't expose one
        }
    }

    private static Boolean autoFlushOf(Handler handler) {
        try {
            Method isAutoFlush = handler.getClass().getMethod("isAutoFlush");
            Object result = isAutoFlush.invoke(handler);
            return result instanceof Boolean b ? b : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // this handler type doesn't expose the concept (plain JUL, not JBoss LogManager)
        }
    }

    private static Long longFieldOf(Handler handler, String fieldName) {
        try {
            Field field = declaredFieldSomewhereInHierarchy(handler.getClass(), fieldName);
            field.setAccessible(true);
            return field.getLong(handler);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // field doesn't exist on this handler type, or reflection was denied
        }
    }

    private static Integer intFieldOf(Handler handler, String fieldName) {
        try {
            Field field = declaredFieldSomewhereInHierarchy(handler.getClass(), fieldName);
            field.setAccessible(true);
            return field.getInt(handler);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Field declaredFieldSomewhereInHierarchy(Class<?> type, String fieldName)
            throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
