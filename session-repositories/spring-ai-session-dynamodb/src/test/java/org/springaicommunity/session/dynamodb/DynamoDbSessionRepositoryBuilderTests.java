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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * What the builder sets up and checks.
 *
 * @author Dominik Kovács
 */
class DynamoDbSessionRepositoryBuilderTests {

	private final DynamoDbClient client = DynamoDbTestSupport.client();

	@Test
	void buildCreatesAMissingTable() {
		String tableName = DynamoDbTestSupport.newTableName();

		DynamoDbSessionRepository.builder().dynamoDbClient(this.client).tableName(tableName).build();

		assertThat(SessionTable.problems(this.client.describeTable(request -> request.tableName(tableName)).table()))
			.isEmpty();
	}

	@Test
	void buildWithoutTableInitializationMakesNoTableCalls() {
		RecordingDynamoDbClient recording = DynamoDbTestSupport.recordingClient();

		DynamoDbSessionRepository.builder()
			.dynamoDbClient(recording)
			.tableName(DynamoDbTestSupport.newTableName())
			.initializeTable(false)
			.build();

		assertThat(recording.tableCalls()).isZero();
	}

	@Test
	void buildWithTableInitializationMakesTableCalls() {
		RecordingDynamoDbClient recording = DynamoDbTestSupport.recordingClient();

		DynamoDbSessionRepository.builder()
			.dynamoDbClient(recording)
			.tableName(DynamoDbTestSupport.newTableName())
			.build();

		assertThat(recording.tableCalls()).isPositive();
	}

	@Test
	void buildFailsOnATableThatDoesNotFit() {
		String tableName = DynamoDbTestSupport.newTableName();
		this.client.createTable(request -> request.tableName(tableName)
			.billingMode(BillingMode.PAY_PER_REQUEST)
			.attributeDefinitions(
					AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
			.keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build()));

		DynamoDbSessionRepository.Builder builder = DynamoDbSessionRepository.builder()
			.dynamoDbClient(this.client)
			.tableName(tableName);

		assertThatIllegalStateException().isThrownBy(builder::build)
			.withMessageContaining("does not fit the session repository");
	}

	@Test
	void buildRequiresAClient() {
		DynamoDbSessionRepository.Builder builder = DynamoDbSessionRepository.builder();

		assertThatIllegalArgumentException().isThrownBy(builder::build).withMessage("dynamoDbClient must be set");
	}

	@Test
	void indexedAttributesAreCappedAtThirtyTwoKeysThePrincipalIncluded() {
		Set<String> keys = IntStream.range(0, 32).mapToObj(i -> "key-" + i).collect(Collectors.toSet());
		DynamoDbSessionRepository.Builder builder = DynamoDbSessionRepository.builder();

		assertThatIllegalArgumentException().isThrownBy(() -> builder.indexedAttributes(keys))
			.withMessage("At most 32 metadata keys can be indexed, the principal included, not 33");
		keys.remove("key-0");
		assertThat(builder.indexedAttributes(keys)).isSameAs(builder);
		keys.add(SessionAttributes.PRINCIPAL_NAME);
		assertThat(builder.indexedAttributes(keys)).isSameAs(builder);
	}

	@Test
	void findByMetadataAnswersForTheIndexedAttributesAndAlwaysForThePrincipal() {
		DynamoDbSessionRepository repository = DynamoDbTestSupport.repository(this.client)
			.indexedAttributes(Set.of("tenant"))
			.build();
		Session session = repository.save(Session.builder()
			.id(UUID.randomUUID().toString())
			.metadata(Map.of("tenant", "acme", SessionAttributes.PRINCIPAL_NAME, "alice", "agent", "support"))
			.build());

		assertThat(repository.findByMetadata("tenant", "acme")).extracting(Session::id).containsExactly(session.id());
		assertThat(repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice")).extracting(Session::id)
			.containsExactly(session.id());
		assertThat(repository.findByMetadata("agent", "support")).isEmpty();
	}

}
