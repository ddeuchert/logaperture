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
package org.logaperture.sample;

import java.io.IOException;
import java.util.List;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code GET /logaperture-sample-war/log} — log one line through a
 * caller-chosen framework and level, through every ancestor category of a
 * caller-chosen logger name, and report what was logged plus the {@code
 * logctl} line that would raise it. All four query parameters are optional:
 *
 * <ul>
 * <li>{@code logger} — the logger name to log through, and whose ancestor
 * categories also get a line each. Default {@value #DEFAULT_LOGGER} (logs
 * through {@code org}, {@code org.logaperture} and {@code
 * org.logaperture.log}).
 * <li>{@code message} — the text to log. Default {@value #DEFAULT_MESSAGE}.
 * <li>{@code level} — {@code trace}, {@code debug}, {@code info}, {@code
 * warn} or {@code error}. Default {@code info}.
 * <li>{@code implementation} — {@code slf4j}, {@code jul} or {@code log4j}.
 * Default {@code slf4j}.
 * </ul>
 *
 * An unrecognized {@code level} or {@code implementation} value is a 400,
 * not a silent fall back to the default -- the whole point of this endpoint
 * is showing exactly what got logged and how.
 */
@WebServlet("/log")
public class LogServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_LOGGER = "org.logaperture.log";
    private static final String DEFAULT_MESSAGE = "Message";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String loggerName = param(req, "logger", DEFAULT_LOGGER);
        String message = param(req, "message", DEFAULT_MESSAGE);
        WorkLog.Level level;
        WorkLog.Implementation implementation;
        try {
            level = WorkLog.Level.parse(param(req, "level", "info"));
            implementation = WorkLog.Implementation.parse(param(req, "implementation", "slf4j"));
        } catch (IllegalArgumentException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().println(e.getMessage());
            return;
        }

        List<String> categories = WorkLog.emit(loggerName, message, level, implementation);

        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().printf(
                "logged \"%s\" at %s via %s, once per category:%n"
                        + "  %s%n"
                        + "%n"
                        + "raise it with:  logctl set logger %s DEBUG for 5m%n",
                message, level, implementation, String.join("\n  ", categories), loggerName);
    }

    /** {@code req}'s {@code name} query parameter, or {@code fallback} if absent/blank. */
    private static String param(HttpServletRequest req, String name, String fallback) {
        String value = req.getParameter(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
