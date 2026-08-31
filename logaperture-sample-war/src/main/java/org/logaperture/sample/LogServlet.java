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
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code GET /logaperture-sample-war/log} — emit one burst of log lines
 * (TRACE..ERROR across {@code java.util.logging}, SLF4J and Log4j) and report
 * what was logged, plus the {@code logctl} line that would raise it.
 */
@WebServlet("/log")
public class LogServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final AtomicLong hits = new AtomicLong();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String marker = "http GET /log #" + hits.incrementAndGet();
        WorkLog.emitAllLevels(marker);

        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().printf(
                "logged \"%s\" at TRACE, DEBUG, INFO, WARN, ERROR%n"
                        + "via java.util.logging, SLF4J and Log4j%n"
                        + "%n"
                        + "logger name: %s%n"
                        + "raise it with:  logctl debug %s for 5m%n",
                marker, WorkLog.LOGGER_NAME, WorkLog.LOGGER_NAME);
    }
}
