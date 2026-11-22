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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.springaicommunity.session.dynamodb.Retries.TryAgain;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * The batch calls of the repository, within DynamoDB's limits per call. What DynamoDB
 * leaves unprocessed is sent again after a {@linkplain Retries#RESENDS pause}, at most
 * {@value Retries#MAX_ATTEMPTS} times in all.
 *
 * @author Dominik Kovács
 */
final class Batches {

	private static final int MAX_GET_KEYS = 100;

	private static final int MAX_WRITES = 25;

	private Batches() {
	}

	/**
	 * The items with the given keys, read strongly consistent, in no particular order;
	 * keys without an item are skipped. The keys must be distinct.
	 * @throws IllegalStateException if DynamoDB keeps leaving keys unprocessed
	 */
	static List<Map<String, AttributeValue>> get(DynamoDbClient client, String tableName,
			List<Map<String, AttributeValue>> keys) {
		List<Map<String, AttributeValue>> items = new ArrayList<>();
		for (int from = 0; from < keys.size(); from += MAX_GET_KEYS) {
			KeysAndAttributes chunk = KeysAndAttributes.builder()
				.keys(keys.subList(from, Math.min(from + MAX_GET_KEYS, keys.size())))
				.consistentRead(true)
				.build();
			sendAll(Map.of(tableName, chunk), "reads", request -> {
				BatchGetItemResponse response = client.batchGetItem(batch -> batch.requestItems(request));
				items.addAll(response.responses().getOrDefault(tableName, List.of()));
				return response.unprocessedKeys();
			});
		}
		return items;
	}

	/**
	 * Deletes the items with the given keys, 25 per call, sending again what DynamoDB
	 * left unprocessed and what conflicted with a transaction.
	 * @throws IllegalStateException if DynamoDB keeps leaving deletions unprocessed
	 */
	static void delete(DynamoDbClient client, String tableName, List<Map<String, AttributeValue>> keys) {
		for (int from = 0; from < keys.size(); from += MAX_WRITES) {
			List<WriteRequest> writes = keys.subList(from, Math.min(from + MAX_WRITES, keys.size()))
				.stream()
				.map(key -> WriteRequest.builder().deleteRequest(delete -> delete.key(key)).build())
				.toList();
			sendAll(Map.of(tableName, writes), "deletes",
					pending -> client.batchWriteItem(batch -> batch.requestItems(pending)).unprocessedItems());
		}
	}

	/**
	 * Sends the request items, then what each call returns as unprocessed, until nothing
	 * is left. A call that conflicts with a transaction that holds one of its items is
	 * sent again whole.
	 * @param what what the items are, for the message of the failure
	 * @param send sends items and returns those left unprocessed
	 */
	private static <T> void sendAll(Map<String, T> requestItems, String what, UnaryOperator<Map<String, T>> send) {
		AtomicReference<Map<String, T>> pending = new AtomicReference<>(requestItems);
		Retries.run(Retries.RESENDS, () -> {
			Map<String, T> unprocessed = send.apply(pending.get());
			pending.set(unprocessed);
			if (!unprocessed.isEmpty()) {
				throw new TryAgain();
			}
		}, () -> "DynamoDB left " + what + " unprocessed after " + Retries.MAX_ATTEMPTS + " attempts");
	}

}
