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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Failures the real service produces and DynamoDB Local does not, injected through the
 * recording client: transactions cancelled by a conflict or an error, writes that
 * conflict with a transaction, and batch calls that leave work unprocessed.
 *
 * @author Dominik Kovács
 */
class DynamoDbSessionRepositoryFailureTests {

	private final DynamoDbClient raw = DynamoDbTestSupport.client();

	private final RecordingDynamoDbClient client = DynamoDbTestSupport.recordingClient();

	private final String tableName = DynamoDbTestSupport.newTableName();

	private final DynamoDbSessionRepository repository = DynamoDbSessionRepository.builder()
		.dynamoDbClient(this.client)
		.tableName(this.tableName)
		.build();

	@Test
	void transactionCancelledByAConflictIsRetried() {
		String sessionId = newSession();
		AtomicInteger transactions = cancelFirstTransaction("TransactionConflict", "None");

		this.repository.appendEvent(DynamoDbTestSupport.userEvent(sessionId, "hello"));

		assertThat(transactions).hasValue(2);
		assertThat(this.repository.getEventVersion(sessionId)).isEqualTo(1);
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).hasSize(1);
	}

	@Test
	void transactionCancelledForAReasonThatIsNoRaceIsRethrown() {
		String sessionId = newSession();
		AtomicInteger transactions = cancelFirstTransaction("ValidationError", "None");

		SessionEvent hello = DynamoDbTestSupport.userEvent(sessionId, "hello");

		assertThatExceptionOfType(TransactionCanceledException.class)
			.isThrownBy(() -> this.repository.appendEvent(hello));

		assertThat(transactions).hasValue(1);
		assertThat(this.repository.getEventVersion(sessionId)).isZero();
	}

	@Test
	void appendEventIsRetriedWhenThePutFailsWhileTheSessionConflicts() {
		String sessionId = newSession();
		// A failed event write proves a replay only if the session's condition held
		AtomicInteger transactions = cancelFirstTransaction("TransactionConflict", "ConditionalCheckFailed");

		this.repository.appendEvent(DynamoDbTestSupport.userEvent(sessionId, "hello"));

		assertThat(transactions).hasValue(2);
		assertThat(this.repository.getEventVersion(sessionId)).isEqualTo(1);
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).hasSize(1);
	}

	@Test
	void appendEventsIsRetriedWhenAPutFailsWhileTheSessionConflicts() {
		String sessionId = newSession();
		List<SessionEvent> events = List.of(DynamoDbTestSupport.userEvent(sessionId, "m0"),
				DynamoDbTestSupport.userEvent(sessionId, "m1"));
		// A failed event write means a duplicate id only if the session's condition held
		AtomicInteger transactions = cancelFirstTransaction("TransactionConflict", "None", "ConditionalCheckFailed");

		assertThat(this.repository.appendEvents(sessionId, events, 0)).isTrue();

		assertThat(transactions).hasValue(2);
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).hasSize(2);
	}

	@Test
	void deletesABatchLeftUnprocessedAreSentAgain() {
		String sessionId = sessionWithEvents(4);
		List<BatchWriteItemRequest> sent = new CopyOnWriteArrayList<>();
		// The first call deletes two events and leaves the others unprocessed
		this.client.answer(request -> {
			if (!(request instanceof BatchWriteItemRequest batch)) {
				return null;
			}
			sent.add(batch);
			if (sent.size() > 1) {
				return null;
			}
			List<WriteRequest> writes = batch.requestItems().get(this.tableName);
			this.raw.batchWriteItem(write -> write.requestItems(Map.of(this.tableName, writes.subList(0, 2))));
			return BatchWriteItemResponse.builder()
				.unprocessedItems(Map.of(this.tableName, writes.subList(2, writes.size())))
				.build();
		});

		this.repository.delete(sessionId);

		assertThat(sent).hasSize(2);
		assertThat(sent.get(1).requestItems().get(this.tableName)).hasSize(2);
		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void deletesThatStayUnprocessedFailTheDeleteAfterSixteenAttempts() {
		String sessionId = sessionWithEvents(3);
		AtomicInteger attempts = new AtomicInteger();
		this.client.answer(request -> {
			if (!(request instanceof BatchWriteItemRequest batch)) {
				return null;
			}
			attempts.incrementAndGet();
			return BatchWriteItemResponse.builder().unprocessedItems(batch.requestItems()).build();
		});

		assertThatIllegalStateException().isThrownBy(() -> this.repository.delete(sessionId))
			.withMessage("DynamoDB left deletes unprocessed after 16 attempts");
		this.client.clearHook();

		assertThat(attempts).hasValue(16);
		// The deletion stopped halfway, so the session is hidden.
		// The next deletion finishes it.
		assertThat(this.repository.findById(sessionId)).isNull();
		this.repository.delete(sessionId);
		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void keysABatchReadLeftUnprocessedAreRequestedAgain() {
		String principal = "owner-" + UUID.randomUUID();
		List<String> ids = sessionsOf(principal, 4);
		List<BatchGetItemRequest> sent = new CopyOnWriteArrayList<>();
		// The first call reads two sessions and leaves the other keys unprocessed
		this.client.answer(request -> {
			if (!(request instanceof BatchGetItemRequest batch)) {
				return null;
			}
			sent.add(batch);
			if (sent.size() > 1) {
				return null;
			}
			KeysAndAttributes keys = batch.requestItems().get(this.tableName);
			BatchGetItemResponse read = this.raw.batchGetItem(get -> get
				.requestItems(Map.of(this.tableName, keys.toBuilder().keys(keys.keys().subList(0, 2)).build())));
			return read.toBuilder()
				.unprocessedKeys(Map.of(this.tableName,
						keys.toBuilder().keys(keys.keys().subList(2, keys.keys().size())).build()))
				.build();
		});

		assertThat(this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, principal)).extracting(Session::id)
			.containsExactlyElementsOf(ids);

		assertThat(sent).hasSize(2);
		assertThat(sent.get(1).requestItems().get(this.tableName).keys()).hasSize(2);
	}

	@Test
	@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void keysThatStayUnprocessedFailTheReadAfterSixteenAttempts() {
		String principal = "owner-" + UUID.randomUUID();
		sessionsOf(principal, 2);
		AtomicInteger attempts = new AtomicInteger();
		this.client.answer(request -> {
			if (!(request instanceof BatchGetItemRequest batch)) {
				return null;
			}
			attempts.incrementAndGet();
			return BatchGetItemResponse.builder().unprocessedKeys(batch.requestItems()).build();
		});

		assertThatIllegalStateException()
			.isThrownBy(() -> this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, principal))
			.withMessage("DynamoDB left reads unprocessed after 16 attempts");

		assertThat(attempts).hasValue(16);
	}

	@Test
	void conflictOnTheRemovalOfTheSessionItemDoesNotFailTheDelete() {
		String sessionId = sessionWithEvents(3);
		AtomicBoolean conflicted = conflictOnceOnTheRemovalOf(sessionId);

		this.repository.delete(sessionId);

		assertThat(conflicted).isTrue();
		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void conflictOnTheRemovalOfTheSessionItemDoesNotFailTheSweep() {
		Session expired = this.repository
			.save(Session.builder().id(UUID.randomUUID().toString()).expiresAt(Instant.now().minusSeconds(60)).build());
		AtomicBoolean conflicted = conflictOnceOnTheRemovalOf(expired.id());

		assertThat(this.repository.deleteExpiredSessions(Instant.now())).isEqualTo(1);

		assertThat(conflicted).isTrue();
		assertThat(partition(expired.id())).isEmpty();
	}

	@Test
	void conflictOnTheDeleteOfTheEventsIsRetried() {
		String sessionId = sessionWithEvents(3);
		AtomicBoolean conflicted = new AtomicBoolean();
		this.client.before(request -> {
			if (request instanceof BatchWriteItemRequest && conflicted.compareAndSet(false, true)) {
				throw RecordingDynamoDbClient.conflict();
			}
		});

		this.repository.delete(sessionId);

		assertThat(conflicted).isTrue();
		assertThat(partition(sessionId)).isEmpty();
	}

	/**
	 * Cancels the first removal of the session item with a {@code TransactionConflict}
	 * reason, as the real service does while another transaction holds the item.
	 */
	private AtomicBoolean conflictOnceOnTheRemovalOf(String sessionId) {
		Map<String, AttributeValue> key = SessionItems.sessionKey(sessionId);
		AtomicBoolean conflicted = new AtomicBoolean();
		this.client.before(request -> {
			if (removesInATransaction(request, key) && conflicted.compareAndSet(false, true)) {
				throw RecordingDynamoDbClient.cancellation("TransactionConflict");
			}
		});
		return conflicted;
	}

	private static boolean removesInATransaction(SdkRequest request, Map<String, AttributeValue> key) {
		if (!(request instanceof TransactWriteItemsRequest transaction) || transaction.transactItems().size() != 1) {
			return false;
		}
		TransactWriteItem write = transaction.transactItems().get(0);
		return write.delete() != null && key.equals(write.delete().key());
	}

	/**
	 * Cancels the first transaction with the given reasons, one per write, as the real
	 * service does, and counts every transaction.
	 */
	private AtomicInteger cancelFirstTransaction(String... reasons) {
		AtomicInteger transactions = new AtomicInteger();
		this.client.before(request -> {
			if (request instanceof TransactWriteItemsRequest && transactions.incrementAndGet() == 1) {
				throw RecordingDynamoDbClient.cancellation(reasons);
			}
		});
		return transactions;
	}

	private String newSession() {
		return this.repository.save(Session.builder().id(UUID.randomUUID().toString()).build()).id();
	}

	/**
	 * Saves sessions of the principal, created a second apart, and returns their ids in
	 * the order of their creation.
	 */
	private List<String> sessionsOf(String principal, int count) {
		Instant start = Instant.parse("2026-01-01T00:00:00Z");
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(this.repository
				.save(Session.builder()
					.id(UUID.randomUUID().toString())
					.createdAt(start.plusSeconds(i))
					.metadata(SessionAttributes.PRINCIPAL_NAME, principal)
					.build())
				.id());
		}
		return ids;
	}

	private String sessionWithEvents(int count) {
		String sessionId = newSession();
		List<SessionEvent> events = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			events.add(DynamoDbTestSupport.userEvent(sessionId, "m" + i));
		}
		assertThat(this.repository.appendEvents(sessionId, events, 0)).isTrue();
		return sessionId;
	}

	/** Every item in the session's partition, read from the raw table. */
	private List<Map<String, AttributeValue>> partition(String sessionId) {
		return this.raw
			.query(query -> query.tableName(this.tableName)
				.keyConditionExpression("pk = :pk")
				.expressionAttributeValues(Map.of(":pk", SessionItems.sessionPk(sessionId)))
				.consistentRead(true))
			.items();
	}

}
