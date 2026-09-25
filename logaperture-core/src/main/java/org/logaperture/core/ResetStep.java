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

/**
 * What resetting one logger or handler did -- shared by {@link LevelControlService} and {@link
 * HandlerLevelControlService} (doc/specs/reset-to-native.md).
 *
 * @param changed         whether anything changed -- an override removed, or the vendor layer
 *                        switched (reset to native, or put back)
 * @param overrideRemoved whether an override was removed; the caller persists that
 */
record ResetStep(boolean changed, boolean overrideRemoved) {

    /**
     * The audit reason for a reset that switched the vendor layer, or {@code null} for one that
     * didn't.
     */
    static String reason(boolean layerChanged, boolean toNative) {
        if (!layerChanged) {
            return null;
        }
        return toNative ? VendorDefaults.RESET_TO_NATIVE_REASON : VendorDefaults.VENDOR_RESTORED_REASON;
    }
}
