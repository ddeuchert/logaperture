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
package org.logaperture.adapter.jul.standin;

import java.util.logging.StreamHandler;

/**
 * Stands in for JBoss LogManager's {@code org.jboss.logmanager.handlers.ConsoleHandler}, which is
 * not on this module's test classpath: a console handler named {@code ConsoleHandler} that does
 * not extend {@code java.util.logging.ConsoleHandler} (issue #188).
 */
public class ConsoleHandler extends StreamHandler {
}
