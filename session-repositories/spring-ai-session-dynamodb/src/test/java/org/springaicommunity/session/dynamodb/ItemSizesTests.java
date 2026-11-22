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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.support.SessionEventCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Item sizes as DynamoDB counts them, checked against DynamoDB Local.
 *
 * @author Dominik Kovács
 */
class ItemSizesTests {

	private final DynamoDbClient client = DynamoDbTestSupport.client();

	@Test
	void countsUtf8BytesOfNamesAndValues() {
		assertThat(ItemSizes.size(Map.of("pk", AttributeValue.fromS("S#1")))).isEqualTo(5);
		assertThat(ItemSizes.size(Map.of("t", AttributeValue.fromS("😀")))).isEqualTo(1 + 4);
		assertThat(ItemSizes.size(Map.of("n", AttributeValue.fromN("1"), "b", AttributeValue.fromBool(true))))
			.isEqualTo(1 + 21 + 1 + 1);
	}

	@Test
	void countsAStringSetAsTheUtf8BytesOfItsElements() {
		assertThat(ItemSizes.size(Map.of("ss", AttributeValue.fromSs(List.of("ab", "😀"))))).isEqualTo(2 + 2 + 4);
	}

	@Test
	void itemWithAStringSetOfExactlyTheLimitIsAcceptedAndOneByteMoreIsNot() {
		String tableName = plainTable();
		Map<String, AttributeValue> atLimit = withStringSet(itemOfSize(ItemSizes.MAX_ITEM_BYTES - 7));
		Map<String, AttributeValue> over = withStringSet(itemOfSize(ItemSizes.MAX_ITEM_BYTES - 6));

		assertThat(ItemSizes.size(atLimit)).isEqualTo(ItemSizes.MAX_ITEM_BYTES);
		putItem(tableName, atLimit);
		assertThatExceptionOfType(DynamoDbException.class).isThrownBy(() -> putItem(tableName, over))
			.withMessageContaining("Item size has exceeded");
	}

	@Test
	void itemOfExactlyTheLimitIsAcceptedAndOneByteMoreIsNot() {
		String tableName = plainTable();
		Map<String, AttributeValue> atLimit = itemOfSize(ItemSizes.MAX_ITEM_BYTES);
		Map<String, AttributeValue> over = itemOfSize(ItemSizes.MAX_ITEM_BYTES + 1);

		assertThat(ItemSizes.size(atLimit)).isEqualTo(ItemSizes.MAX_ITEM_BYTES);
		putItem(tableName, atLimit);
		assertThatExceptionOfType(DynamoDbException.class).isThrownBy(() -> putItem(tableName, over))
			.withMessageContaining("Item size has exceeded");
	}

	@Test
	void eventItemOfTheEventLimitFitsTheSessionTable() {
		String tableName = DynamoDbTestSupport.newTableName();
		SessionTable.initialize(this.client, tableName);
		SessionItems items = new SessionItems(new SessionEventCodec());
		// The payload of an event that is not a message is the one attribute that grows
		String generation = UUID.randomUUID().toString();
		int room = ItemSizes.MAX_EVENT_ITEM_BYTES - ItemSizes.size(items.eventItem(status(""), generation, 0));
		Map<String, AttributeValue> atLimit = items.eventItem(status("x".repeat(room)), generation, 0);

		assertThat(ItemSizes.size(atLimit)).isEqualTo(ItemSizes.MAX_EVENT_ITEM_BYTES);
		putItem(tableName, atLimit);
	}

	private void putItem(String tableName, Map<String, AttributeValue> item) {
		this.client.putItem(request -> request.tableName(tableName).item(item));
	}

	private static SessionEvent status(String text) {
		return SessionEvent.builder()
			.id("e-1")
			.sessionId("s-1")
			.type(EventType.of("STATUS"))
			.payload("status", text)
			.build();
	}

	private String plainTable() {
		String tableName = DynamoDbTestSupport.newTableName();
		this.client.createTable(request -> request.tableName(tableName)
			.billingMode(BillingMode.PAY_PER_REQUEST)
			.attributeDefinitions(
					AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
			.keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()));
		return tableName;
	}

	/**
	 * A copy of the item with a string set that adds 7 bytes: name "ss", elements "a" and
	 * "b\0cd".
	 */
	private static Map<String, AttributeValue> withStringSet(Map<String, AttributeValue> item) {
		Map<String, AttributeValue> withSet = new HashMap<>(item);
		withSet.put("ss", AttributeValue.fromSs(List.of("a", "b\0cd")));
		return withSet;
	}

	/** A string-only item of exactly this many bytes: names "pk" and "text", key "p". */
	private static Map<String, AttributeValue> itemOfSize(int bytes) {
		int filler = bytes - "pk".length() - "p".length() - "text".length();
		return Map.of("pk", AttributeValue.fromS("p"), "text", AttributeValue.fromS("x".repeat(filler)));
	}

}
