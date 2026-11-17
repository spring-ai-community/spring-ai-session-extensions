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

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;

import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The turn contract tests on Spring AI's JDBC store, with H2 and the schema the store
 * ships.
 *
 * @author Christian Tzolov
 */
class JdbcTurnServiceContractTests extends AbstractTurnServiceContractTests {

	private EmbeddedDatabase database;

	@Override
	protected SessionRepository createRepository() {
		this.database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
			.setName("turns-" + UUID.randomUUID())
			.addScript("classpath:org/springframework/ai/session/jdbc/schema-h2.sql")
			.build();
		return JdbcSessionRepository.builder().dataSource(this.database).build();
	}

	@AfterEach
	void shutDownDatabase() {
		this.database.shutdown();
	}

}
