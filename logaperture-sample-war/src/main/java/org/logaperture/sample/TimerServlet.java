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

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Switches {@link SampleTimer} on and off over HTTP:
 *
 * <ul>
 *   <li>{@code POST /logaperture-sample-war/timer/start} — begin logging a
 *       burst every {@code periodMs} (query param; default
 *       {@value SampleTimer#DEFAULT_PERIOD_MS}, clamped
 *       {@value SampleTimer#MIN_PERIOD_MS}..{@value SampleTimer#MAX_PERIOD_MS}).
 *       {@code ?restart=true} replaces a running timer; otherwise a second
 *       start returns {@code 409}.</li>
 *   <li>{@code POST /logaperture-sample-war/timer/stop} — stop it.</li>
 *   <li>{@code GET  /logaperture-sample-war/timer/status} — running?, period,
 *       tick count.</li>
 * </ul>
 */
@WebServlet("/timer/*")
public class TimerServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"/status".equals(req.getPathInfo())) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "GET supports only /timer/status");
            return;
        }
        writeStatus(resp, HttpServletResponse.SC_OK, "status");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if ("/start".equals(path)) {
            long requestedPeriodMs = parsePeriodMs(req.getParameter("periodMs"));
            boolean restart = Boolean.parseBoolean(req.getParameter("restart"));
            boolean started = SampleTimer.INSTANCE.start(requestedPeriodMs, restart);
            writeStatus(resp,
                    started ? HttpServletResponse.SC_OK : HttpServletResponse.SC_CONFLICT,
                    started ? "started" : "already running — pass ?restart=true to replace");
        } else if ("/stop".equals(path)) {
            boolean wasRunning = SampleTimer.INSTANCE.stop();
            writeStatus(resp, HttpServletResponse.SC_OK, wasRunning ? "stopped" : "was not running");
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "POST supports /timer/start and /timer/stop");
        }
    }

    private static long parsePeriodMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return SampleTimer.DEFAULT_PERIOD_MS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return SampleTimer.DEFAULT_PERIOD_MS;
        }
    }

    private static void writeStatus(HttpServletResponse resp, int status, String action)
            throws IOException {
        SampleTimer timer = SampleTimer.INSTANCE;
        resp.setStatus(status);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().printf(
                "%s%nrunning: %s%nperiodMs: %d%nticks: %d%nlogger: %s%n",
                action, timer.running(), timer.periodMs(), timer.tickCount(), WorkLog.LOGGER_NAME);
    }
}
