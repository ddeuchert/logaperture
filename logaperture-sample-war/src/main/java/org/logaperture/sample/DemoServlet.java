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
 * Switches {@link DemoShop}, the demo recordings' log traffic, on and off over HTTP:
 *
 * <ul>
 *   <li>{@code POST /logaperture-sample-war/demo/start} — start placing orders.</li>
 *   <li>{@code POST /logaperture-sample-war/demo/flood/start} — make the stock sync fail
 *       many times a second.</li>
 *   <li>{@code POST /logaperture-sample-war/demo/flood/stop} — end the flood.</li>
 *   <li>{@code POST /logaperture-sample-war/demo/stop} — stop everything.</li>
 *   <li>{@code GET  /logaperture-sample-war/demo/status} — running? flooding?</li>
 * </ul>
 *
 * <p>A start of something already running is a no-op that still answers {@code 200}, so the
 * recording scripts can call it unconditionally.
 */
@WebServlet("/demo/*")
public class DemoServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"/status".equals(req.getPathInfo())) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "GET supports only /demo/status");
            return;
        }
        writeStatus(resp, "status");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        DemoShop shop = DemoShop.INSTANCE;
        String path = req.getPathInfo();
        if ("/start".equals(path)) {
            writeStatus(resp, shop.start() ? "started" : "already running");
        } else if ("/stop".equals(path)) {
            writeStatus(resp, shop.stop() ? "stopped" : "was not running");
        } else if ("/flood/start".equals(path)) {
            writeStatus(resp, shop.startFlood() ? "flood started" : "already flooding");
        } else if ("/flood/stop".equals(path)) {
            writeStatus(resp, shop.stopFlood() ? "flood stopped" : "was not flooding");
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "POST supports /demo/start, /demo/stop, /demo/flood/start and /demo/flood/stop");
        }
    }

    private static void writeStatus(HttpServletResponse resp, String action) throws IOException {
        DemoShop shop = DemoShop.INSTANCE;
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().printf("%s%nrunning: %s%nflooding: %s%n", action, shop.running(), shop.flooding());
    }
}
