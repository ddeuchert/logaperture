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
package org.logaperture.sample.quarkus;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Logs at every level on category {@code org.logaperture.sample.quarkus.Worker} through JBoss
 * Logging, and on {@code org.logaperture.sample.quarkus.Jul} through java.util.logging, and tells
 * QuarkusIT what each logger will emit. QuarkusIT parses the plain-text answer.
 */
@Path("/log")
public class WorkResource {

    private static final Logger WORKER = Logger.getLogger("org.logaperture.sample.quarkus.Worker");
    private static final java.util.logging.Logger JUL =
            java.util.logging.Logger.getLogger("org.logaperture.sample.quarkus.Jul");

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String log(@QueryParam("tag") String tag, @QueryParam("repeat") Integer repeat) {
        String label = tag == null ? "none" : tag;
        WORKER.trace("TRACE-line " + label);
        WORKER.debug("DEBUG-line " + label);
        WORKER.info("INFO-line " + label);
        WORKER.warn("WARN-line " + label);
        WORKER.error("ERROR-line " + label, new IllegalStateException("sample"));
        JUL.fine("JUL-FINE-line " + label);
        for (int i = 0; repeat != null && i < repeat; i++) {
            WORKER.info("REPEAT-line " + label + " " + i);
        }
        return "trace=" + WORKER.isTraceEnabled()
                + " debug=" + WORKER.isDebugEnabled()
                + " julFine=" + JUL.isLoggable(java.util.logging.Level.FINE)
                + " floor=" + System.getProperty("logging.initial-configurator.min-level")
                + " manager=" + System.getProperty("java.util.logging.manager");
    }
}
