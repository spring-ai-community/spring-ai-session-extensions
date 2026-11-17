/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The turns of a Spring AI session, recorded as events in its log: whether a turn runs,
 * under which run id, and why the last one ended; a request to stop it; and a question a
 * turn parks on, with its answer. {@link org.springaicommunity.session.turn.TurnService}
 * reads and writes them through a {@code SessionService}, and works with any store.
 */
@NullMarked
package org.springaicommunity.session.turn;

import org.jspecify.annotations.NullMarked;
