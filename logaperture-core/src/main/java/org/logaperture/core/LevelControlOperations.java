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
package org.logaperture.core;

import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;

import java.util.List;

/**
 * Feature 1's public contract — see doc/specs/level-control.md
 * "Operations". Every control surface (JMX now, others later) is a client
 * of this interface; no surface is a privileged path
 * (doc/logaperture-spec.md §8.1).
 */
public interface LevelControlOperations {

    List<LoggerInfo> listLoggers(String filter);

    LevelOverride setLevel(String loggerName, Level level, SetLevelOptions options);

    void resetLevel(String loggerName);

    void resetAll();
}
