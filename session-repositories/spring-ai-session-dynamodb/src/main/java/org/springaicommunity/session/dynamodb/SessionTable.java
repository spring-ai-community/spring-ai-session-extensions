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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import org.springframework.util.Assert;

/**
 * The table the repository keeps sessions in: its key, its two indexes, and the setup
 * that creates a missing table and checks an existing one.
 *
 * @author Dominik Kovács
 */
final class SessionTable {

	static final String PK = "pk";

	static final String SK = "sk";

	static final String SEQ = "seq";

	static final String EXPIRY_SHARD = "expiryShard";

	static final String EXPIRES_AT = "expiresAt";

	/** The local secondary index that orders a session's events by {@link #SEQ}. */
	static final String SEQ_INDEX = "seq";

	/** The sparse global secondary index the expiry sweep queries. */
	static final String EXPIRY_INDEX = "expiry";

	/**
	 * How many {@link #EXPIRY_SHARD} values there are, so that no key of the expiry index
	 * is hot.
	 */
	static final int EXPIRY_SHARDS = 16;

	private static final Map<String, ScalarAttributeType> ATTRIBUTE_TYPES = Map.of(PK, ScalarAttributeType.S, SK,
			ScalarAttributeType.S, SEQ, ScalarAttributeType.N, EXPIRY_SHARD, ScalarAttributeType.S, EXPIRES_AT,
			ScalarAttributeType.N);

	private SessionTable() {
	}

	/**
	 * Creates the table when it is missing, waits until it is active, and checks that its
	 * key and indexes are the ones the repository needs.
	 * @throws IllegalStateException if the table does not fit
	 */
	static void initialize(DynamoDbClient client, String tableName) {
		if (describe(client, tableName) == null) {
			create(client, tableName);
		}
		List<String> problems = problems(waitUntilActive(client, tableName));
		Assert.state(problems.isEmpty(),
				() -> "Table '" + tableName + "' does not fit the session repository: " + String.join("; ", problems));
	}

	/** The request that creates the table, billed on demand. */
	static CreateTableRequest createTableRequest(String tableName) {
		return CreateTableRequest.builder()
			.tableName(tableName)
			.billingMode(BillingMode.PAY_PER_REQUEST)
			.attributeDefinitions(ATTRIBUTE_TYPES.entrySet()
				.stream()
				.map(entry -> AttributeDefinition.builder()
					.attributeName(entry.getKey())
					.attributeType(entry.getValue())
					.build())
				.toList())
			.keySchema(keySchema(PK, SK))
			.localSecondaryIndexes(LocalSecondaryIndex.builder()
				.indexName(SEQ_INDEX)
				.keySchema(keySchema(PK, SEQ))
				.projection(projection -> projection.projectionType(ProjectionType.ALL))
				.build())
			.globalSecondaryIndexes(GlobalSecondaryIndex.builder()
				.indexName(EXPIRY_INDEX)
				.keySchema(keySchema(EXPIRY_SHARD, EXPIRES_AT))
				.projection(projection -> projection.projectionType(ProjectionType.KEYS_ONLY))
				.build())
			.build();
	}

	/** What keeps the table from serving the repository; empty when it fits. */
	static List<String> problems(TableDescription table) {
		List<String> problems = new ArrayList<>();
		if (!keySchema(PK, SK).equals(table.keySchema())) {
			problems.add("its key must be " + PK + " (HASH), " + SK + " (RANGE)");
		}
		Map<String, ScalarAttributeType> types = table.attributeDefinitions()
			.stream()
			.collect(Collectors.toMap(AttributeDefinition::attributeName, AttributeDefinition::attributeType));
		ATTRIBUTE_TYPES.forEach((name, type) -> {
			if (types.containsKey(name) && types.get(name) != type) {
				problems.add("attribute " + name + " must be of type " + type);
			}
		});
		boolean seqIndex = table.localSecondaryIndexes()
			.stream()
			.anyMatch(index -> SEQ_INDEX.equals(index.indexName()) && keySchema(PK, SEQ).equals(index.keySchema())
					&& index.projection().projectionType() == ProjectionType.ALL);
		if (!seqIndex) {
			problems.add("it needs the local secondary index '" + SEQ_INDEX + "' on " + PK + " (HASH), " + SEQ
					+ " (RANGE) with projection ALL");
		}
		boolean expiryIndex = table.globalSecondaryIndexes()
			.stream()
			.anyMatch(index -> EXPIRY_INDEX.equals(index.indexName())
					&& keySchema(EXPIRY_SHARD, EXPIRES_AT).equals(index.keySchema()));
		if (!expiryIndex) {
			problems.add("it needs the global secondary index '" + EXPIRY_INDEX + "' on " + EXPIRY_SHARD + " (HASH), "
					+ EXPIRES_AT + " (RANGE)");
		}
		return problems;
	}

	private static @Nullable TableDescription describe(DynamoDbClient client, String tableName) {
		try {
			return client.describeTable(request -> request.tableName(tableName)).table();
		}
		catch (ResourceNotFoundException ex) {
			return null;
		}
	}

	private static void create(DynamoDbClient client, String tableName) {
		try {
			client.createTable(createTableRequest(tableName));
		}
		catch (ResourceInUseException ex) {
			// Another instance created it first; the wait and the check still apply
		}
	}

	private static TableDescription waitUntilActive(DynamoDbClient client, String tableName) {
		try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(client).build()) {
			return waiter.waitUntilTableExists(request -> request.tableName(tableName))
				.matched()
				.response()
				.map(DescribeTableResponse::table)
				.orElseThrow(() -> new IllegalStateException("Table '" + tableName + "' did not become active"));
		}
	}

	private static List<KeySchemaElement> keySchema(String hash, String range) {
		return List.of(KeySchemaElement.builder().attributeName(hash).keyType(KeyType.HASH).build(),
				KeySchemaElement.builder().attributeName(range).keyType(KeyType.RANGE).build());
	}

}
