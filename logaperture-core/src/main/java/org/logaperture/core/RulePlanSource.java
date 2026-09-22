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
 * A live reader of the current {@link RulePlan} — what {@link
 * org.logaperture.core.spi.LoggingAdapter#installRulePipeline} hands to an
 * adapter's gate filter. {@link RuleService} is the only implementation;
 * kept as a narrow interface (rather than handing the adapter the whole
 * service) so the adapter can only ever read the plan, never mutate rule
 * state.
 */
@FunctionalInterface
public interface RulePlanSource {

    RulePlan currentPlan();
}
