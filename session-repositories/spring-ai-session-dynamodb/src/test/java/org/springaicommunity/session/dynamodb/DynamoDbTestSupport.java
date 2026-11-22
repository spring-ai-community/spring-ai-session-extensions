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

package org.springaicommunity.session.dynamodb;

import java.util.Objects;
import java.util.UUID;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.dynamodb.services.local.embedded.DynamoDBEmbedded;
import software.amazon.dynamodb.services.local.shared.access.AmazonDynamoDBLocal;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

/**
 * One embedded, in-memory DynamoDB Local for the whole test JVM, and names for the tables
 * the tests create in it.
 *
 * @author Dominik Kovács
 */
final class DynamoDbTestSupport {

	private static final AmazonDynamoDBLocal LOCAL = start();

	private DynamoDbTestSupport() {
	}

	private static AmazonDynamoDBLocal start() {
		AmazonDynamoDBLocal local = DynamoDBEmbedded.create();
		Runtime.getRuntime().addShutdownHook(new Thread(local::shutdown));
		return local;
	}

	static DynamoDbClient client() {
		return LOCAL.dynamoDbClient();
	}

	static RecordingDynamoDbClient recordingClient() {
		return new RecordingDynamoDbClient(client());
	}

	static String newTableName() {
		return "session-" + UUID.randomUUID();
	}

	/** A repository builder on a new table of its own. */
	static DynamoDbSessionRepository.Builder repository(DynamoDbClient client) {
		return DynamoDbSessionRepository.builder().dynamoDbClient(client).tableName(newTableName());
	}

	static SessionEvent userEvent(String sessionId, String text) {
		return SessionEvent.builder().sessionId(sessionId).message(new UserMessage(text)).build();
	}

	/** The text of the event's message; fails if it has none. */
	static String text(SessionEvent event) {
		return Objects.requireNonNull(event.getMessage().getText(), () -> "Event '" + event.getId() + "' has no text");
	}

}
