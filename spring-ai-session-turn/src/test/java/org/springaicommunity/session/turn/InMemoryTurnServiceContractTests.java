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
package org.springaicommunity.session.turn;

import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;

/**
 * The turn contract tests on Spring AI's in-memory store.
 *
 * @author Christian Tzolov
 */
class InMemoryTurnServiceContractTests extends AbstractTurnServiceContractTests {

	@Override
	protected SessionRepository createRepository() {
		return InMemorySessionRepository.builder().build();
	}

}
