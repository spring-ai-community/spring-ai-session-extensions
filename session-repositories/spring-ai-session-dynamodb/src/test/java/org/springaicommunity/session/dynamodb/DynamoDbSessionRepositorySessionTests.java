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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session saves and lookups beyond what the shared contract checks.
 *
 * @author Dominik Kovács
 */
class DynamoDbSessionRepositorySessionTests {

	private final DynamoDbClient client = DynamoDbTestSupport.client();

	private final String tableName = DynamoDbTestSupport.newTableName();

	private final DynamoDbSessionRepository repository = DynamoDbSessionRepository.builder()
		.dynamoDbClient(this.client)
		.tableName(this.tableName)
		.build();

	@Test
	void saveWithoutExpiryRemovesTheExpiryAndItsSweepIndexEntry() {
		String id = UUID.randomUUID().toString();
		Instant expiresAt = Instant.parse("2026-01-01T00:00:00.123456Z");
		this.repository.save(Session.builder().id(id).expiresAt(expiresAt).build());
		assertThat(Objects.requireNonNull(this.repository.findById(id)).expiresAt()).isEqualTo(expiresAt);
		assertThat(expiryIndexEntries()).extracting(SessionItems::sessionId).containsExactly(id);

		this.repository.save(Session.builder().id(id).build());

		assertThat(Objects.requireNonNull(this.repository.findById(id)).expiresAt()).isNull();
		assertThat(expiryIndexEntries()).isEmpty();
	}

	@Test
	void findByMetadataReadsMoreThanOneBatchOfSessionsInCreationOrder() {
		String principal = "owner-" + UUID.randomUUID();
		Instant start = Instant.parse("2026-01-01T00:00:00Z");
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 120; i++) {
			ids.add(this.repository
				.save(Session.builder()
					.id(UUID.randomUUID().toString())
					.createdAt(start.plusSeconds(i))
					.metadata(SessionAttributes.PRINCIPAL_NAME, principal)
					.build())
				.id());
		}

		assertThat(this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, principal)).extracting(Session::id)
			.containsExactlyElementsOf(ids);
	}

	@Test
	void findByMetadataFindsAValueAsItReadsBackAndAChangeLeavesNoEntryBehind() {
		DynamoDbSessionRepository withTenant = indexing(Set.of("tenant"));
		String id = UUID.randomUUID().toString();
		withTenant.save(Session.builder().id(id).metadata("tenant", new BigDecimal("1.10")).build());
		// JSON reads 1.10 back as 1.1
		assertThat(Objects.requireNonNull(withTenant.findById(id)).metadata().get("tenant")).hasToString("1.1");

		assertThat(withTenant.findByMetadata("tenant", "1.1")).extracting(Session::id).containsExactly(id);

		withTenant.save(Session.builder().id(id).metadata("tenant", "acme").build());

		assertThat(indexEntries("tenant", "1.1")).isEmpty();
		assertThat(indexEntries("tenant", "1.10")).isEmpty();
		assertThat(indexEntries("tenant", "acme")).hasSize(1);
	}

	@Test
	void deleteRemovesTheIndexEntriesASessionWasSavedWithUnderOtherIndexedAttributes() {
		DynamoDbSessionRepository withTenant = indexing(Set.of(SessionAttributes.PRINCIPAL_NAME, "tenant"));
		String id = UUID.randomUUID().toString();
		String principal = "owner-" + UUID.randomUUID();
		String tenant = "tenant-" + UUID.randomUUID();
		withTenant.save(Session.builder()
			.id(id)
			.metadata(Map.of(SessionAttributes.PRINCIPAL_NAME, principal, "tenant", tenant))
			.build());
		assertThat(indexEntries(SessionAttributes.PRINCIPAL_NAME, principal)).hasSize(1);
		assertThat(indexEntries("tenant", tenant)).hasSize(1);

		// This instance indexes the principal only, as after a change of configuration
		this.repository.delete(id);

		assertThat(indexEntries(SessionAttributes.PRINCIPAL_NAME, principal)).isEmpty();
		assertThat(indexEntries("tenant", tenant)).isEmpty();
	}

	@Test
	void findByMetadataListsASessionOnceWhenTwoEntriesPointToIt() {
		String principal = "owner-" + UUID.randomUUID();
		Session session = this.repository.save(Session.builder()
			.id(UUID.randomUUID().toString())
			.metadata(SessionAttributes.PRINCIPAL_NAME, principal)
			.build());
		// A second entry for the same session, under an older creation time
		this.client.putItem(put -> put.tableName(this.tableName)
			.item(Map.of(SessionTable.PK, SessionItems.indexPk(SessionAttributes.PRINCIPAL_NAME, principal),
					SessionTable.SK, AttributeValue.fromS(SessionItems.sortable(Instant.EPOCH) + "#" + session.id()))));

		assertThat(this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, principal)).extracting(Session::id)
			.containsExactly(session.id());
	}

	/** Another instance on the same table that indexes the given metadata keys. */
	private DynamoDbSessionRepository indexing(Set<String> indexedAttributes) {
		return DynamoDbSessionRepository.builder()
			.dynamoDbClient(this.client)
			.tableName(this.tableName)
			.initializeTable(false)
			.indexedAttributes(indexedAttributes)
			.build();
	}

	/** The raw table's index entries for one metadata value, read consistently. */
	private List<Map<String, AttributeValue>> indexEntries(String name, String value) {
		return this.client
			.query(query -> query.tableName(this.tableName)
				.keyConditionExpression("pk = :pk")
				.expressionAttributeValues(Map.of(":pk", SessionItems.indexPk(name, value)))
				.consistentRead(true))
			.items();
	}

	private List<Map<String, AttributeValue>> expiryIndexEntries() {
		List<Map<String, AttributeValue>> entries = new ArrayList<>();
		for (int shard = 0; shard < SessionTable.EXPIRY_SHARDS; shard++) {
			String key = Integer.toString(shard);
			entries.addAll(
					this.client
						.query(query -> query.tableName(this.tableName)
							.indexName(SessionTable.EXPIRY_INDEX)
							.keyConditionExpression("expiryShard = :shard")
							.expressionAttributeValues(Map.of(":shard", AttributeValue.fromS(key))))
						.items());
		}
		return entries;
	}

}
