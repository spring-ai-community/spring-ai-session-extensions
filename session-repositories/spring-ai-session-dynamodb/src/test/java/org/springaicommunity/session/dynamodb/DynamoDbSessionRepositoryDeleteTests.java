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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Deletions of large sessions, deletions that stop halfway, and the expiry sweep.
 *
 * @author Dominik Kovács
 */
class DynamoDbSessionRepositoryDeleteTests {

	private final DynamoDbClient raw = DynamoDbTestSupport.client();

	private final RecordingDynamoDbClient client = DynamoDbTestSupport.recordingClient();

	private final String tableName = DynamoDbTestSupport.newTableName();

	private final DynamoDbSessionRepository repository = DynamoDbSessionRepository.builder()
		.dynamoDbClient(this.client)
		.tableName(this.tableName)
		.build();

	@ParameterizedTest
	@ValueSource(ints = { 30, 130 })
	void deleteRemovesEveryEventOfALargeSession(int events) {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		appendMany(sessionId, events);
		assertThat(partition(sessionId)).hasSize(events + 1);

		this.repository.delete(sessionId);

		assertThat(this.repository.findById(sessionId)).isNull();
		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void deleteOfAnUnknownSessionIsANoOp() {
		String sessionId = UUID.randomUUID().toString();

		assertThatNoException().isThrownBy(() -> this.repository.delete(sessionId));

		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void interruptedDeleteHidesTheSessionAndTheNextDeleteFinishesIt() {
		String principal = "owner-" + UUID.randomUUID();
		String sessionId = newSession(principal);
		appendMany(sessionId, 3);

		interruptDelete(sessionId);

		assertThat(this.repository.findById(sessionId)).isNull();
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).isEmpty();
		assertThat(this.repository.getEventVersion(sessionId)).isZero();
		assertThat(this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, principal)).isEmpty();
		assertThat(items(SessionItems.indexPk(SessionAttributes.PRINCIPAL_NAME, principal))).isEmpty();
		SessionEvent late = DynamoDbTestSupport.userEvent(sessionId, "late");
		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.appendEvent(late));
		assertThat(partition(sessionId)).hasSize(4);

		this.repository.delete(sessionId);

		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void sweepFinishesAnInterruptedDeleteOfASessionWithoutExpiry() {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		appendMany(sessionId, 3);
		interruptDelete(sessionId);

		assertThat(this.repository.deleteExpiredSessions(Instant.now().plusSeconds(1))).isEqualTo(1);

		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void saveIfAbsentOfTheSameIdFinishesAnInterruptedDeleteFirst() {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		this.repository.appendEvent(DynamoDbTestSupport.userEvent(sessionId, "old"));
		interruptDelete(sessionId);

		assertThat(this.repository.saveIfAbsent(Session.builder().id(sessionId).build())).isTrue();

		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).isEmpty();
		assertThat(this.repository.getEventVersion(sessionId)).isZero();
	}

	@Test
	void saveOfTheSameIdFinishesAnInterruptedDeleteFirst() {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		this.repository.appendEvent(DynamoDbTestSupport.userEvent(sessionId, "old"));
		interruptDelete(sessionId);

		this.repository.save(Session.builder().id(sessionId).build());

		assertThat(this.repository.findById(sessionId)).isNotNull();
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).isEmpty();
		assertThat(this.repository.getEventVersion(sessionId)).isZero();
	}

	@Test
	void sweepDeletesExpiredSessionsWithTheirEventsAndKeepsTheOthers() {
		Instant now = Instant.now();
		Session expired = this.repository
			.save(Session.builder().id(UUID.randomUUID().toString()).expiresAt(now.minusSeconds(60)).build());
		Session live = this.repository
			.save(Session.builder().id(UUID.randomUUID().toString()).expiresAt(now.plusSeconds(60)).build());
		appendMany(expired.id(), 30);

		assertThat(this.repository.deleteExpiredSessions(now)).isEqualTo(1);

		assertThat(partition(expired.id())).isEmpty();
		assertThat(this.repository.findById(live.id())).isNotNull();
	}

	@Test
	void sweepKeepsASessionWhoseExpiryIsExtendedWhileItRuns() {
		Instant now = Instant.now();
		String sessionId = UUID.randomUUID().toString();
		this.repository.save(Session.builder().id(sessionId).expiresAt(now.minusSeconds(60)).build());
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean extended = new AtomicBoolean();
		// Right before the sweep marks the session, another instance extends its expiry
		this.client.before(request -> {
			if (request instanceof TransactWriteItemsRequest && extended.compareAndSet(false, true)) {
				other.save(Session.builder().id(sessionId).expiresAt(now.plusSeconds(60)).build());
			}
		});

		assertThat(this.repository.deleteExpiredSessions(now)).isZero();

		assertThat(extended).isTrue();
		assertThat(this.repository.findById(sessionId)).isNotNull();
	}

	@Test
	void sweepCountsOnceASessionSavedAgainWhileStillExpired() {
		Instant now = Instant.now();
		Instant expiresAt = now.minusSeconds(60);
		String sessionId = UUID.randomUUID().toString();
		this.repository.save(Session.builder().id(sessionId).expiresAt(expiresAt).build());
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean saved = new AtomicBoolean();
		// Right before the sweep marks the session, another instance saves it again.
		// The session stays expired, so the sweep's retry still deletes it.
		this.client.before(request -> {
			if (request instanceof TransactWriteItemsRequest && saved.compareAndSet(false, true)) {
				other.save(Session.builder()
					.id(sessionId)
					.expiresAt(expiresAt)
					.metadata(SessionAttributes.PRINCIPAL_NAME, "changed")
					.build());
			}
		});

		assertThat(this.repository.deleteExpiredSessions(now)).isEqualTo(1);

		assertThat(saved).isTrue();
		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void sweepDoesNotCountASessionAnotherCallerRemovedFirst() {
		Instant now = Instant.now();
		String sessionId = UUID.randomUUID().toString();
		this.repository.save(Session.builder().id(sessionId).expiresAt(now.minusSeconds(60)).build());
		appendMany(sessionId, 3);
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean removed = new AtomicBoolean();
		// Right before the sweep deletes the events, another instance deletes the session
		this.client.before(request -> {
			if (request instanceof BatchWriteItemRequest && removed.compareAndSet(false, true)) {
				other.delete(sessionId);
			}
		});

		assertThat(this.repository.deleteExpiredSessions(now)).isZero();

		assertThat(removed).isTrue();
		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void sweepGoesOnPastSessionsItFailsToDeleteAndThenThrowsTheFirstFailure() {
		Instant now = Instant.now();
		List<String> ids = threeIdsOfOneExpiryShard();
		for (int i = 0; i < ids.size(); i++) {
			this.repository.save(Session.builder().id(ids.get(i)).expiresAt(now.minusSeconds(60 - i)).build());
		}
		// The sweep reads a shard's sessions in expiry order.
		// Reading the first two of them fails.
		Set<Map<String, AttributeValue>> failing = Set.of(SessionItems.sessionKey(ids.get(0)),
				SessionItems.sessionKey(ids.get(1)));
		this.client.before(request -> {
			if (request instanceof GetItemRequest get && failing.contains(get.key())) {
				throw SdkClientException.create("Injected failure of " + SessionItems.sessionId(get.key()));
			}
		});

		assertThatExceptionOfType(SdkClientException.class).isThrownBy(() -> this.repository.deleteExpiredSessions(now))
			.withMessage("Injected failure of " + ids.get(0))
			.satisfies(ex -> assertThat(ex.getSuppressed()).extracting(Throwable::getMessage)
				.containsExactly("Injected failure of " + ids.get(1)));
		this.client.clearHook();

		assertThat(partition(ids.get(2))).isEmpty();
		assertThat(this.repository.findById(ids.get(0))).isNotNull();
		assertThat(this.repository.findById(ids.get(1))).isNotNull();
	}

	@Test
	void sweepStopsAtOnceWhenItsThreadIsInterrupted() {
		Instant now = Instant.now();
		List<String> ids = threeIdsOfOneExpiryShard();
		for (int i = 0; i < ids.size(); i++) {
			this.repository.save(Session.builder().id(ids.get(i)).expiresAt(now.minusSeconds(60 - i)).build());
		}
		AtomicBoolean interrupted = new AtomicBoolean();
		// The first transaction of the sweep loses a race, and the thread is interrupted.
		// The pause before the retry then fails.
		this.client.before(request -> {
			if (request instanceof TransactWriteItemsRequest && interrupted.compareAndSet(false, true)) {
				Thread.currentThread().interrupt();
				throw RecordingDynamoDbClient.cancellation("TransactionConflict");
			}
		});

		boolean stayedInterrupted;
		try {
			assertThatIllegalStateException().isThrownBy(() -> this.repository.deleteExpiredSessions(now))
				.withMessage("Interrupted while waiting to retry")
				.satisfies(ex -> assertThat(ex.getSuppressed()).isEmpty());
		}
		finally {
			// Also clears the flag, so that later tests run on an uninterrupted thread
			stayedInterrupted = Thread.interrupted();
			this.client.clearHook();
		}

		assertThat(stayedInterrupted).as("the sweep left its thread interrupted").isTrue();
		assertThat(interrupted).isTrue();
		assertThat(ids).allSatisfy(id -> assertThat(this.repository.findById(id)).isNotNull());
	}

	@Test
	void lateFinisherLeavesASessionReCreatedUnderTheSameIdAlone() {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		appendMany(sessionId, 3);
		interruptDelete(sessionId);
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean recreated = new AtomicBoolean();
		// Right before the late finisher reads the events, another instance steps in.
		// It finishes the deletion and re-creates the session with an event.
		this.client.before(request -> {
			if (request instanceof QueryRequest && recreated.compareAndSet(false, true)) {
				other.delete(sessionId);
				other.saveIfAbsent(Session.builder().id(sessionId).build());
				other.appendEvent(DynamoDbTestSupport.userEvent(sessionId, "new"));
			}
		});

		this.repository.delete(sessionId);

		assertThat(recreated).isTrue();
		assertThat(this.repository.findById(sessionId)).isNotNull();
		assertThat(texts(sessionId)).containsExactly("new");
	}

	@Test
	void lateFinisherLeavesADeletingReCreatedSessionForItsOwnDelete() {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		appendMany(sessionId, 3);
		interruptDelete(sessionId);
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean recreated = new AtomicBoolean();
		// Right before the late finisher reads the events, another instance steps in.
		// It finishes the deletion and re-creates the session with an event.
		// It then marks the new session deleting, as a deletion stopped halfway would.
		this.client.before(request -> {
			if (request instanceof QueryRequest && recreated.compareAndSet(false, true)) {
				other.delete(sessionId);
				other.saveIfAbsent(Session.builder().id(sessionId).build());
				other.appendEvent(DynamoDbTestSupport.userEvent(sessionId, "new"));
				this.raw.updateItem(update -> update.tableName(this.tableName)
					.key(SessionItems.sessionKey(sessionId))
					.updateExpression("SET deleting = :true")
					.expressionAttributeValues(Map.of(":true", AttributeValue.fromBool(true))));
			}
		});

		this.repository.delete(sessionId);
		this.client.clearHook();

		assertThat(recreated).isTrue();
		assertThat(partition(sessionId)).hasSize(2);

		this.repository.delete(sessionId);

		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void lateFinisherLeavesAnEventOfAReCreatedSessionThatReusesAnEventIdAlone() {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		this.repository.appendEvent(userEventWithIdE1(sessionId, "old"));
		interruptDelete(sessionId);
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean recreated = new AtomicBoolean();
		// The late finisher has read the keys of the old events.
		// Right before it deletes them, another instance finishes the deletion.
		// It then re-creates the session with an event of the same id.
		this.client.before(request -> {
			if (request instanceof BatchWriteItemRequest && recreated.compareAndSet(false, true)) {
				other.delete(sessionId);
				other.saveIfAbsent(Session.builder().id(sessionId).build());
				other.appendEvent(userEventWithIdE1(sessionId, "new"));
			}
		});

		this.repository.delete(sessionId);

		assertThat(recreated).isTrue();
		assertThat(this.repository.findById(sessionId)).isNotNull();
		assertThat(texts(sessionId)).containsExactly("new");
	}

	@Test
	void appendThatRacesADeleteAndReCreateNeverLeavesAnEventBehind() {
		String sessionId = UUID.randomUUID().toString();
		this.repository.save(Session.builder().id(sessionId).build());
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean recreated = new AtomicBoolean();
		// Right before the appending transaction, another instance deletes the session.
		// It then creates it again, with the same counters.
		this.client.before(request -> {
			if (request instanceof TransactWriteItemsRequest && recreated.compareAndSet(false, true)) {
				other.delete(sessionId);
				other.saveIfAbsent(Session.builder().id(sessionId).build());
			}
		});

		this.repository.appendEvent(DynamoDbTestSupport.userEvent(sessionId, "late"));
		this.client.clearHook();

		assertThat(recreated).isTrue();
		assertThat(texts(sessionId)).containsExactly("late");

		other.delete(sessionId);

		assertThat(partition(sessionId)).isEmpty();
	}

	@Test
	void deleteThatRacesADeleteAndReCreateRemovesTheNewSessionWithItsIndexEntry() {
		String principal = "owner-" + UUID.randomUUID();
		String sessionId = newSession(principal);
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean recreated = new AtomicBoolean();
		// Right before the deletion marks the session, another instance deletes it.
		// It then creates it again, with the same counters.
		this.client.before(request -> {
			if (request instanceof TransactWriteItemsRequest && recreated.compareAndSet(false, true)) {
				other.delete(sessionId);
				other.saveIfAbsent(
						Session.builder().id(sessionId).metadata(SessionAttributes.PRINCIPAL_NAME, principal).build());
			}
		});

		this.repository.delete(sessionId);

		assertThat(recreated).isTrue();
		assertThat(partition(sessionId)).isEmpty();
		assertThat(items(SessionItems.indexPk(SessionAttributes.PRINCIPAL_NAME, principal))).isEmpty();
	}

	@Test
	void saveThatRacesADeleteAndReCreateMovesTheNewSessionsIndexEntry() {
		String sessionId = newSession("owner-" + UUID.randomUUID());
		String recreatedPrincipal = "owner-" + UUID.randomUUID();
		String savedPrincipal = "owner-" + UUID.randomUUID();
		DynamoDbSessionRepository other = otherInstance();
		AtomicBoolean recreated = new AtomicBoolean();
		// Right before the save commits, another instance deletes the session.
		// It then creates it again, with the same counters.
		this.client.before(request -> {
			if (request instanceof TransactWriteItemsRequest && recreated.compareAndSet(false, true)) {
				other.delete(sessionId);
				other.saveIfAbsent(Session.builder()
					.id(sessionId)
					.metadata(SessionAttributes.PRINCIPAL_NAME, recreatedPrincipal)
					.build());
			}
		});

		this.repository
			.save(Session.builder().id(sessionId).metadata(SessionAttributes.PRINCIPAL_NAME, savedPrincipal).build());

		assertThat(recreated).isTrue();
		assertThat(items(SessionItems.indexPk(SessionAttributes.PRINCIPAL_NAME, recreatedPrincipal))).isEmpty();
		assertThat(items(SessionItems.indexPk(SessionAttributes.PRINCIPAL_NAME, savedPrincipal))).hasSize(1);
	}

	private List<String> texts(String sessionId) {
		return this.repository.findEvents(sessionId, EventFilter.all())
			.stream()
			.map(DynamoDbTestSupport::text)
			.toList();
	}

	private static SessionEvent userEventWithIdE1(String sessionId, String text) {
		return SessionEvent.builder().id("e1").sessionId(sessionId).message(new UserMessage(text)).build();
	}

	private static List<String> threeIdsOfOneExpiryShard() {
		List<String> ids = new ArrayList<>(List.of(UUID.randomUUID().toString()));
		AttributeValue shard = SessionItems.expiryShard(ids.get(0));
		while (ids.size() < 3) {
			String id = UUID.randomUUID().toString();
			if (shard.equals(SessionItems.expiryShard(id))) {
				ids.add(id);
			}
		}
		return ids;
	}

	private DynamoDbSessionRepository otherInstance() {
		return DynamoDbSessionRepository.builder()
			.dynamoDbClient(this.raw)
			.tableName(this.tableName)
			.initializeTable(false)
			.build();
	}

	private String newSession(String principal) {
		return this.repository
			.save(Session.builder()
				.id(UUID.randomUUID().toString())
				.metadata(SessionAttributes.PRINCIPAL_NAME, principal)
				.build())
			.id();
	}

	private void appendMany(String sessionId, int count) {
		for (int from = 0; from < count; from += 99) {
			List<SessionEvent> batch = new ArrayList<>();
			for (int i = from; i < Math.min(from + 99, count); i++) {
				batch.add(DynamoDbTestSupport.userEvent(sessionId, "m" + i));
			}
			assertThat(this.repository.appendEvents(sessionId, batch, this.repository.getEventVersion(sessionId)))
				.isTrue();
		}
	}

	/** Starts a deletion that fails after its first step, when it deletes the events. */
	private void interruptDelete(String sessionId) {
		this.client.before(RecordingDynamoDbClient.failBatchWrites());
		assertThatExceptionOfType(SdkClientException.class).isThrownBy(() -> this.repository.delete(sessionId));
		this.client.clearHook();
	}

	/** Every item in the session's partition: the session item and its events. */
	private List<Map<String, AttributeValue>> partition(String sessionId) {
		return items(SessionItems.sessionPk(sessionId));
	}

	/** Every item of the raw table with the partition key, read consistently. */
	private List<Map<String, AttributeValue>> items(AttributeValue pk) {
		List<Map<String, AttributeValue>> items = new ArrayList<>();
		this.raw
			.queryPaginator(query -> query.tableName(this.tableName)
				.keyConditionExpression("pk = :pk")
				.expressionAttributeValues(Map.of(":pk", pk))
				.consistentRead(true))
			.items()
			.forEach(items::add);
		return items;
	}

}
