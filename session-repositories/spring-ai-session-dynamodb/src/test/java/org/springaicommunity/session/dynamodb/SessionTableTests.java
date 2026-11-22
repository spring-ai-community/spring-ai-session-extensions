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

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Creating the session table and checking an existing one.
 *
 * @author Dominik Kovács
 */
class SessionTableTests {

	private final DynamoDbClient client = DynamoDbTestSupport.client();

	@Test
	void createsMissingTableWithKeyAndIndexes() {
		String tableName = DynamoDbTestSupport.newTableName();

		SessionTable.initialize(this.client, tableName);

		TableDescription table = describe(tableName);
		assertThat(table.tableStatus()).isEqualTo(TableStatus.ACTIVE);
		assertThat(SessionTable.problems(table)).isEmpty();
		assertThat(table.localSecondaryIndexes()).extracting(LocalSecondaryIndexDescription::indexName)
			.containsExactly("seq");
		assertThat(table.globalSecondaryIndexes()).extracting(GlobalSecondaryIndexDescription::indexName)
			.containsExactly("expiry");
	}

	@Test
	void acceptsExistingTableThatFits() {
		String tableName = DynamoDbTestSupport.newTableName();
		this.client.createTable(SessionTable.createTableRequest(tableName));

		SessionTable.initialize(this.client, tableName);
		SessionTable.initialize(this.client, tableName);

		assertThat(SessionTable.problems(describe(tableName))).isEmpty();
	}

	@Test
	void rejectsTableWithOtherKey() {
		String tableName = DynamoDbTestSupport.newTableName();
		this.client.createTable(request -> request.tableName(tableName)
			.billingMode(BillingMode.PAY_PER_REQUEST)
			.attributeDefinitions(attribute("id", ScalarAttributeType.S))
			.keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build()));

		assertThatIllegalStateException().isThrownBy(() -> SessionTable.initialize(this.client, tableName))
			.withMessageContaining("Table '" + tableName + "' does not fit the session repository")
			.withMessageContaining("its key must be pk (HASH), sk (RANGE)")
			.withMessageContaining("local secondary index 'seq'")
			.withMessageContaining("global secondary index 'expiry'");
	}

	@Test
	void rejectsSeqIndexThatProjectsOnlyKeys() {
		String tableName = DynamoDbTestSupport.newTableName();
		CreateTableRequest request = SessionTable.createTableRequest(tableName);
		this.client.createTable(request.toBuilder()
			.localSecondaryIndexes(request.localSecondaryIndexes()
				.get(0)
				.toBuilder()
				.projection(projection -> projection.projectionType(ProjectionType.KEYS_ONLY))
				.build())
			.build());

		assertThatIllegalStateException().isThrownBy(() -> SessionTable.initialize(this.client, tableName))
			.withMessageContaining("local secondary index 'seq' on pk (HASH), seq (RANGE) with projection ALL");
	}

	@Test
	void rejectsTableWithoutExpiryIndex() {
		String tableName = DynamoDbTestSupport.newTableName();
		this.client.createTable(SessionTable.createTableRequest(tableName)
			.toBuilder()
			.attributeDefinitions(attribute("pk", ScalarAttributeType.S), attribute("sk", ScalarAttributeType.S),
					attribute("seq", ScalarAttributeType.N))
			.globalSecondaryIndexes((Collection<GlobalSecondaryIndex>) null)
			.build());

		assertThatIllegalStateException().isThrownBy(() -> SessionTable.initialize(this.client, tableName))
			.withMessageContaining("global secondary index 'expiry' on expiryShard (HASH), expiresAt (RANGE)");
	}

	@Test
	void rejectsExpiryIndexWithOtherKey() {
		String tableName = DynamoDbTestSupport.newTableName();
		CreateTableRequest request = SessionTable.createTableRequest(tableName);
		this.client.createTable(request.toBuilder()
			.globalSecondaryIndexes(request.globalSecondaryIndexes()
				.get(0)
				.toBuilder()
				.keySchema(KeySchemaElement.builder().attributeName("expiresAt").keyType(KeyType.HASH).build(),
						KeySchemaElement.builder().attributeName("expiryShard").keyType(KeyType.RANGE).build())
				.build())
			.build());

		assertThatIllegalStateException().isThrownBy(() -> SessionTable.initialize(this.client, tableName))
			.withMessageContaining("global secondary index 'expiry' on expiryShard (HASH), expiresAt (RANGE)");
	}

	@Test
	void rejectsKeyAttributeOfOtherType() {
		String tableName = DynamoDbTestSupport.newTableName();
		CreateTableRequest request = SessionTable.createTableRequest(tableName);
		this.client.createTable(request.toBuilder()
			.attributeDefinitions(request.attributeDefinitions()
				.stream()
				.map(definition -> "pk".equals(definition.attributeName()) ? attribute("pk", ScalarAttributeType.N)
						: definition)
				.toList())
			.build());

		assertThatIllegalStateException().isThrownBy(() -> SessionTable.initialize(this.client, tableName))
			.withMessage(
					"Table '" + tableName + "' does not fit the session repository: attribute pk must be of type S");
	}

	@Test
	void acceptsATableAnotherInstanceCreatedFirst() {
		String tableName = DynamoDbTestSupport.newTableName();
		RecordingDynamoDbClient recording = DynamoDbTestSupport.recordingClient();
		AtomicBoolean raced = new AtomicBoolean();
		// Another instance creates the table between this one's check and its creation
		recording.before(request -> {
			if (request instanceof CreateTableRequest create && raced.compareAndSet(false, true)) {
				this.client.createTable(create);
				throw ResourceInUseException.builder().message("Table already exists: " + tableName).build();
			}
		});

		SessionTable.initialize(recording, tableName);

		assertThat(raced).isTrue();
		assertThat(SessionTable.problems(describe(tableName))).isEmpty();
	}

	private TableDescription describe(String tableName) {
		return this.client.describeTable(request -> request.tableName(tableName)).table();
	}

	private static AttributeDefinition attribute(String name, ScalarAttributeType type) {
		return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
	}

}
