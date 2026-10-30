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
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.jboss.logmanager.handlers.FileHandler;

import java.io.FileNotFoundException;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Adds a file handler to the root logger after start-up, as application code or a library calling
 * {@code addHandler} would. QuarkusIT uses it to check that the configured root handler names stay on
 * their handlers (doc/specs/quarkus-handler-names.md, amendment for issue #294).
 */
@Path("/handlers")
public class HandlerResource {

    @GET
    @Path("/add-root-file")
    @Produces(MediaType.TEXT_PLAIN)
    public String addRootFile(@QueryParam("path") String path) throws FileNotFoundException {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            // Quarkus keeps the root's handlers inside its delayed handler; a handler added later goes there too.
            if (handler instanceof ExtHandler delayed && handler.getClass().getSimpleName().equals("QuarkusDelayedHandler")) {
                FileHandler added = new FileHandler(path);
                added.setFormatter(new PatternFormatter("%m%n"));
                delayed.addHandler(added);
                return "added";
            }
        }
        return "no delayed handler";
    }
}
