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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.session.dynamodb.Retries.TryAgain;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.core.retry.Retryable;
import org.springframework.util.Assert;

/**
 * The {@link SessionRepository} on Amazon DynamoDB. One table holds every session as an
 * item with its counters, every event as an item in its session's partition, and an index
 * entry per indexed metadata value, written in the same transaction as the session.
 * <p>
 * Every read the contract relies on is strongly consistent. A save or an append reads the
 * session item and commits one conditional transaction. A deletion first marks the
 * session in one, which hides it, and then removes its events and its item; the next save
 * or deletion of the session, or the expiry sweep, finishes a deletion that stopped
 * halfway. A lost race is retried after a pause that grows with every attempt, at most
 * {@value Retries#MAX_ATTEMPTS} times in all. {@link #findByMetadata} answers for the
 * principal and the other {@linkplain Builder#indexedAttributes(Set) indexed attributes}
 * only. The class is thread-safe, as the {@link DynamoDbClient} is.
 *
 * @author Dominik Kovács
 */
public final class DynamoDbSessionRepository implements SessionRepository {

	/**
	 * The most metadata keys one repository indexes, so that a save, which can delete and
	 * put an index entry per key in its transaction, stays below a transaction's 100
	 * writes.
	 */
	private static final int MAX_INDEXED_ATTRIBUTES = 32;

	/** One transaction holds 100 writes: the session item and 99 events. */
	private static final int MAX_BATCH_EVENTS = 99;

	/**
	 * The event bytes one batch may hold: a transaction's 4 MB, less room for the session
	 * item it also updates.
	 */
	private static final int MAX_BATCH_EVENT_BYTES = ItemSizes.MAX_TRANSACTION_BYTES - ItemSizes.MAX_ITEM_BYTES;

	/**
	 * Room a session item keeps for the attributes it can gain after a save, at their
	 * largest: boundarySeq from an append, and deleting, expiresAt and expiryShard from a
	 * deletion.
	 */
	private static final int SESSION_ITEM_RESERVE = 96;

	private static final int MAX_SESSION_ITEM_BYTES = ItemSizes.MAX_ITEM_BYTES - SESSION_ITEM_RESERVE;

	/**
	 * Stands in for the generation, a UUID of fixed length, in items that are measured or
	 * encoded before the session is read.
	 */
	private static final String GENERATION_PLACEHOLDER = new UUID(0, 0).toString();

	private final DynamoDbClient client;

	private final String tableName;

	private final Set<String> indexedAttributes;

	private final SessionItems items;

	private final EventReads reads;

	private DynamoDbSessionRepository(DynamoDbClient client, String tableName, Set<String> indexedAttributes,
			SessionItems items) {
		this.client = client;
		this.tableName = tableName;
		this.indexedAttributes = indexedAttributes;
		this.items = items;
		this.reads = new EventReads(client, tableName, items);
	}

	public static Builder builder() {
		return new Builder();
	}

	// Sessions

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		checkSessionSize(session);
		return retrying(session.id(), () -> trySave(session));
	}

	private Session trySave(Session session) {
		StoredSession stored = readSession(session.id());
		if (stored == null) {
			if (!insert(session)) {
				throw new TryAgain();
			}
			return session;
		}
		if (stored.deleting()) {
			finishDelete(session.id(), stored.generation());
			throw new TryAgain();
		}
		Session merged = Session.builder()
			.id(session.id())
			.createdAt(stored.session().createdAt())
			.expiresAt(session.expiresAt())
			.metadata(session.metadata())
			.build();
		if (!update(stored, merged)) {
			throw new TryAgain();
		}
		return merged;
	}

	@Override
	public boolean saveIfAbsent(Session session) {
		Assert.notNull(session, "session must not be null");
		checkSessionSize(session);
		return retrying(session.id(), () -> {
			if (insert(session)) {
				return true;
			}
			// The id is taken, or the insert lost a race: what is stored now decides
			StoredSession stored = readSession(session.id());
			if (stored == null) {
				throw new TryAgain();
			}
			if (stored.deleting()) {
				finishDelete(session.id(), stored.generation());
				throw new TryAgain();
			}
			return false;
		});
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		StoredSession stored = readSession(sessionId);
		return (stored != null && !stored.deleting()) ? stored.session() : null;
	}

	@Override
	public List<Session> findByMetadata(String name, String value) {
		Assert.hasText(name, "name must not be null or empty");
		Assert.notNull(value, "value must not be null");
		if (!this.indexedAttributes.contains(name)) {
			return List.of();
		}
		// A session listed twice would make BatchGetItem reject the request
		Set<String> sessionIds = new LinkedHashSet<>();
		this.client
			.queryPaginator(query -> query.tableName(this.tableName)
				.keyConditionExpression("pk = :pk")
				.expressionAttributeValues(Map.of(":pk", SessionItems.indexPk(name, value)))
				.consistentRead(true))
			.items()
			.forEach(entry -> sessionIds.add(SessionItems.indexedSessionId(entry)));
		Map<String, StoredSession> sessions = new HashMap<>();
		for (Map<String, AttributeValue> item : Batches.get(this.client, this.tableName,
				sessionIds.stream().map(SessionItems::sessionKey).toList())) {
			StoredSession stored = this.items.storedSession(item);
			sessions.put(stored.session().id(), stored);
		}
		// An entry is a candidate; the session's own metadata settles a hash collision
		return sessionIds.stream()
			.map(sessions::get)
			.filter(stored -> stored != null && !stored.deleting() && hasValue(stored.session(), name, value))
			.map(StoredSession::session)
			.toList();
	}

	private static boolean hasValue(Session session, String name, String value) {
		Object actual = session.metadata().get(name);
		return actual != null && value.equals(String.valueOf(actual));
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		retrying(sessionId, () -> {
			StoredSession stored = readSession(sessionId);
			if (stored == null) {
				return;
			}
			if (!stored.deleting() && failsToMark(stored, null)) {
				throw new TryAgain();
			}
			finishDelete(sessionId, stored.generation());
		});
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Goes through the expiry index shard by shard, one session at a time. The count
	 * includes interrupted deletions this call finished. When a session or a shard fails,
	 * the sweep goes on with the others, and then throws the first failure, with the
	 * later ones {@linkplain Throwable#getSuppressed() suppressed}. A failure while the
	 * thread is interrupted, or caused by an interrupt, stops the sweep at once; the
	 * thread stays interrupted. The sessions it removed stay removed.
	 */
	@Override
	public int deleteExpiredSessions(Instant before) {
		Assert.notNull(before, "before must not be null");
		List<RuntimeException> failures = new ArrayList<>();
		int deleted = 0;
		for (int shard = 0; shard < SessionTable.EXPIRY_SHARDS && !interrupted(failures); shard++) {
			try {
				deleted += deleteExpiredIn(shard, before, failures);
			}
			catch (RuntimeException ex) {
				// The shard's query failed; its other sessions wait for the next sweep
				failures.add(ex);
			}
		}
		if (failures.isEmpty()) {
			return deleted;
		}
		if (interrupted(failures)) {
			// The caller wants the thread to stop, so it stays interrupted
			Thread.currentThread().interrupt();
		}
		RuntimeException first = failures.get(0);
		failures.subList(1, failures.size()).forEach(first::addSuppressed);
		throw first;
	}

	/**
	 * Deletes the sessions of one shard that expired before the instant, and adds the
	 * failure of each session to the list. Stops early once the sweep
	 * {@linkplain #interrupted(List) has to stop}.
	 */
	private int deleteExpiredIn(int shard, Instant before, List<RuntimeException> failures) {
		int deleted = 0;
		Iterator<Map<String, AttributeValue>> entries = expiredIn(shard, before).iterator();
		while (!interrupted(failures) && entries.hasNext()) {
			try {
				deleted += deleteExpired(SessionItems.sessionId(entries.next()), before) ? 1 : 0;
			}
			catch (RuntimeException ex) {
				failures.add(ex);
			}
		}
		return deleted;
	}

	/**
	 * Whether the sweep has to stop: it failed while the thread is interrupted, or its
	 * newest failure came from an interrupt.
	 */
	private static boolean interrupted(List<RuntimeException> failures) {
		if (failures.isEmpty()) {
			return false;
		}
		if (Thread.currentThread().isInterrupted()) {
			return true;
		}
		for (Throwable cause = failures.get(failures.size() - 1); cause != null; cause = cause.getCause()) {
			if (cause instanceof InterruptedException || cause instanceof AbortedException) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The sessions of one shard that expired before the instant, as the eventually
	 * consistent index lists them.
	 */
	private SdkIterable<Map<String, AttributeValue>> expiredIn(int shard, Instant before) {
		return this.client
			.queryPaginator(query -> query.tableName(this.tableName)
				.indexName(SessionTable.EXPIRY_INDEX)
				.keyConditionExpression("expiryShard = :shard AND expiresAt < :before")
				.expressionAttributeValues(Map.of(":shard", AttributeValue.fromS(Integer.toString(shard)), ":before",
						SessionItems.instant(before))))
			.items();
	}

	/**
	 * Deletes one expired session, unless its expiry has moved past the instant;
	 * {@code true} only if this call, not another caller, removed it.
	 */
	private boolean deleteExpired(String sessionId, Instant before) {
		return retrying(sessionId, () -> {
			StoredSession stored = readSession(sessionId);
			if (stored == null) {
				return false;
			}
			if (!stored.deleting()) {
				Instant expiresAt = stored.session().expiresAt();
				if (expiresAt == null || !expiresAt.isBefore(before)) {
					return false;
				}
				if (failsToMark(stored, before)) {
					throw new TryAgain();
				}
			}
			return finishDelete(sessionId, stored.generation());
		});
	}

	/**
	 * The first step of a deletion, the one that decides it. Marks the session as
	 * deleting, which hides it from every operation; moves its expiry to now, so that a
	 * sweep finishes a deletion that stopped halfway; and removes the index entries
	 * recorded on its item. With a {@code before} instant, only if the session still
	 * expires before it.
	 * @return {@code true} if the session changed meanwhile and nothing was written, a
	 * lost race to try again
	 */
	private boolean failsToMark(StoredSession stored, @Nullable Instant before) {
		String sessionId = stored.session().id();
		Map<String, AttributeValue> values = new HashMap<>();
		values.put(":true", AttributeValue.fromBool(true));
		values.put(":now", SessionItems.instant(Instant.now()));
		values.put(":shard", SessionItems.expiryShard(sessionId));
		values.put(":revision", SessionItems.number(stored.revision()));
		values.put(":generation", AttributeValue.fromS(stored.generation()));
		String unchanged = "revision = :revision AND generation = :generation AND attribute_not_exists(deleting)";
		if (before != null) {
			values.put(":before", SessionItems.instant(before));
		}
		String condition = (before != null) ? unchanged + " AND expiresAt < :before" : unchanged;
		List<TransactWriteItem> writes = new ArrayList<>();
		writes.add(TransactWriteItem.builder()
			.update(update -> update.tableName(this.tableName)
				.key(SessionItems.sessionKey(sessionId))
				.updateExpression("SET deleting = :true, expiresAt = :now, expiryShard = :shard")
				.conditionExpression(condition)
				.expressionAttributeValues(values))
			.build());
		stored.indexEntries().forEach(entry -> writes.add(deleteIndexEntry(entry, stored.session())));
		return !transact(writes).isCommitted();
	}

	/**
	 * The rest of a deletion: removes the events of the given generation of the session,
	 * then the session item of that generation. Safe to run again, by any caller, until
	 * the session item is gone, and it touches nothing of a session re-created under the
	 * same id.
	 * @return whether this call removed the session item
	 * @throws TryAgain if the removal conflicted with another transaction on the item
	 */
	private boolean finishDelete(String sessionId, String generation) {
		List<Map<String, AttributeValue>> eventKeys = new ArrayList<>();
		this.client
			.queryPaginator(query -> query.tableName(this.tableName)
				.keyConditionExpression("pk = :pk AND begins_with(sk, :events)")
				.expressionAttributeValues(Map.of(":pk", SessionItems.sessionPk(sessionId), ":events",
						AttributeValue.fromS(SessionItems.eventPrefix(generation))))
				.projectionExpression("pk, sk")
				.consistentRead(true))
			.items()
			.forEach(eventKeys::add);
		Batches.delete(this.client, this.tableName, eventKeys);
		// A transaction of one write, so that a conflict is a cancellation reason
		Outcome outcome = transact(List.of(TransactWriteItem.builder()
			.delete(delete -> delete.tableName(this.tableName)
				.key(SessionItems.sessionKey(sessionId))
				.conditionExpression("attribute_exists(deleting) AND generation = :generation")
				.expressionAttributeValues(Map.of(":generation", AttributeValue.fromS(generation))))
			.build()));
		if (outcome.conditionFailed(0)) {
			// Another caller finished the deletion, or the id holds a newer session
			return false;
		}
		if (!outcome.isCommitted()) {
			throw new TryAgain();
		}
		return true;
	}

	/**
	 * Inserts a new session with its index entries, which its item records; {@code false}
	 * if an item with its id exists, or the transaction lost a race.
	 */
	private boolean insert(Session session) {
		Set<String> entries = indexEntries(session);
		List<TransactWriteItem> writes = new ArrayList<>();
		writes.add(TransactWriteItem.builder()
			.put(put -> put.tableName(this.tableName)
				.item(this.items.sessionItem(session, UUID.randomUUID().toString(), entries))
				.conditionExpression("attribute_not_exists(pk)"))
			.build());
		entries.forEach(entry -> writes.add(putIndexEntry(entry, session)));
		return transact(writes).isCommitted();
	}

	/**
	 * Replaces the metadata and expiry of a stored session. Deletes the recorded index
	 * entries it no longer has, puts the new ones, and records the result. {@code false}
	 * if the session changed meanwhile.
	 */
	private boolean update(StoredSession stored, Session merged) {
		Set<String> recorded = stored.indexEntries();
		Set<String> entries = indexEntries(merged);
		List<TransactWriteItem> writes = new ArrayList<>();
		writes.add(sessionUpdate(stored, merged, entries));
		recorded.stream()
			.filter(entry -> !entries.contains(entry))
			.forEach(entry -> writes.add(deleteIndexEntry(entry, stored.session())));
		entries.stream()
			.filter(entry -> !recorded.contains(entry))
			.forEach(entry -> writes.add(putIndexEntry(entry, merged)));
		return transact(writes).isCommitted();
	}

	private TransactWriteItem sessionUpdate(StoredSession stored, Session merged, Set<String> indexEntries) {
		Map<String, AttributeValue> values = new HashMap<>();
		values.put(":metadata", this.items.json(merged.metadata()));
		values.put(":revision", SessionItems.number(stored.revision()));
		values.put(":generation", AttributeValue.fromS(stored.generation()));
		values.put(":one", SessionItems.number(1));
		List<String> set = new ArrayList<>(List.of("metadata = :metadata", "revision = revision + :one"));
		List<String> remove = new ArrayList<>();
		Instant expiresAt = merged.expiresAt();
		if (expiresAt != null) {
			values.put(":expiresAt", SessionItems.instant(expiresAt));
			values.put(":shard", SessionItems.expiryShard(merged.id()));
			set.add("expiresAt = :expiresAt, expiryShard = :shard");
		}
		else {
			remove.add("expiresAt, expiryShard");
		}
		if (!indexEntries.isEmpty()) {
			values.put(":indexEntries", SessionItems.stringSet(indexEntries));
			set.add("indexEntries = :indexEntries");
		}
		else {
			remove.add("indexEntries");
		}
		String expression = "SET " + String.join(", ", set)
				+ (remove.isEmpty() ? "" : " REMOVE " + String.join(", ", remove));
		return TransactWriteItem.builder()
			.update(update -> update.tableName(this.tableName)
				.key(SessionItems.sessionKey(merged.id()))
				.updateExpression(expression)
				.conditionExpression(
						"revision = :revision AND generation = :generation AND attribute_not_exists(deleting)")
				.expressionAttributeValues(values))
			.build();
	}

	private Set<String> indexEntries(Session session) {
		return this.items.indexEntries(session, this.indexedAttributes);
	}

	private TransactWriteItem putIndexEntry(String entry, Session session) {
		return TransactWriteItem.builder()
			.put(put -> put.tableName(this.tableName).item(SessionItems.indexKey(entry, session)))
			.build();
	}

	private TransactWriteItem deleteIndexEntry(String entry, Session session) {
		return TransactWriteItem.builder()
			.delete(delete -> delete.tableName(this.tableName).key(SessionItems.indexKey(entry, session)))
			.build();
	}

	// Events

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		// Encoded once; each attempt sets the generation and the sequence number
		Map<String, AttributeValue> item = this.items.eventItem(event, GENERATION_PLACEHOLDER, 0);
		checkEventSize(event, item);
		retrying(event.getSessionId(), () -> tryAppend(event, item));
	}

	private void tryAppend(SessionEvent event, Map<String, AttributeValue> item) {
		StoredSession stored = requireSession(event.getSessionId());
		Outcome outcome = transact(
				List.of(advance(stored, List.of(event)), putEvent(item, stored.generation(), stored.nextSeq())));
		// The event write failed and the session's condition held: the id is in the log.
		// The event is a replay, and the call a no-op.
		boolean done = outcome.isCommitted() || (outcome.firstWritePassed() && outcome.conditionFailed(1));
		if (!done) {
			throw new TryAgain();
		}
	}

	@Override
	public boolean appendEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notEmpty(events, "events must not be empty");
		Assert.noNullElements(events, "events must not contain null");
		Assert.isTrue(events.size() <= MAX_BATCH_EVENTS, () -> "A batch holds at most " + MAX_BATCH_EVENTS
				+ " events, the limit of one transaction, not " + events.size());
		Set<String> ids = new HashSet<>();
		for (SessionEvent event : events) {
			Assert.isTrue(sessionId.equals(event.getSessionId()), () -> "Event '" + event.getId()
					+ "' belongs to session '" + event.getSessionId() + "', not '" + sessionId + "'");
			Assert.isTrue(ids.add(event.getId()), () -> "Event id '" + event.getId() + "' appears twice in the batch");
		}
		List<Map<String, AttributeValue>> eventItems = events.stream()
			.map(event -> this.items.eventItem(event, GENERATION_PLACEHOLDER, 0))
			.toList();
		int bytes = 0;
		for (int i = 0; i < events.size(); i++) {
			bytes += checkEventSize(events.get(i), eventItems.get(i));
		}
		int total = bytes;
		Assert.isTrue(total <= MAX_BATCH_EVENT_BYTES,
				() -> "The batch for session '" + sessionId + "' is " + total
						+ " bytes by DynamoDB's count, more than the " + MAX_BATCH_EVENT_BYTES
						+ " bytes of events one transaction can take next to its session item");
		return retrying(sessionId, () -> tryAppendAll(sessionId, events, eventItems, expectedVersion));
	}

	private boolean tryAppendAll(String sessionId, List<SessionEvent> events,
			List<Map<String, AttributeValue>> eventItems, long expectedVersion) {
		StoredSession stored = requireSession(sessionId);
		if (stored.eventVersion() != expectedVersion) {
			return false;
		}
		List<TransactWriteItem> writes = new ArrayList<>();
		writes.add(advance(stored, events));
		for (int i = 0; i < eventItems.size(); i++) {
			writes.add(putEvent(eventItems.get(i), stored.generation(), stored.nextSeq() + i));
		}
		Outcome outcome = transact(writes);
		if (outcome.isCommitted()) {
			return true;
		}
		// A failed event write means a duplicate id only if the session's condition held
		int failed = outcome.firstWritePassed() ? outcome.firstFailedCondition() : -1;
		if (failed > 0) {
			throw new IllegalArgumentException("Event '" + events.get(failed - 1).getId()
					+ "' is already in the log of session '" + sessionId + "'");
		}
		// The version moved, the session went, or the transaction lost a race: read again
		throw new TryAgain();
	}

	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		StoredSession stored = readSession(sessionId);
		return (stored != null && !stored.deleting()) ? stored.eventVersion() : 0;
	}

	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");
		StoredSession stored = readSession(sessionId);
		if (stored == null || stored.deleting()) {
			return List.of();
		}
		return this.reads.find(stored, filter);
	}

	private StoredSession requireSession(String sessionId) {
		StoredSession stored = readSession(sessionId);
		if (stored == null || stored.deleting()) {
			throw new IllegalArgumentException("Session '" + sessionId + "' does not exist");
		}
		return stored;
	}

	/**
	 * Advances the session's version once and its next sequence number past the events,
	 * under the version and generation it was read with, and only while no deletion has
	 * started. The newest compaction boundary among the events becomes the session's
	 * boundary.
	 */
	private TransactWriteItem advance(StoredSession stored, List<SessionEvent> events) {
		Map<String, AttributeValue> values = new HashMap<>();
		values.put(":one", SessionItems.number(1));
		values.put(":count", SessionItems.number(events.size()));
		values.put(":version", SessionItems.number(stored.eventVersion()));
		values.put(":generation", AttributeValue.fromS(stored.generation()));
		int boundary = newestCompaction(events);
		if (boundary >= 0) {
			values.put(":boundary", SessionItems.number(stored.nextSeq() + boundary));
		}
		String expression = "SET eventVersion = eventVersion + :one, nextSeq = nextSeq + :count"
				+ ((boundary >= 0) ? ", boundarySeq = :boundary" : "");
		return TransactWriteItem.builder()
			.update(update -> update.tableName(this.tableName)
				.key(SessionItems.sessionKey(stored.session().id()))
				.updateExpression(expression)
				.conditionExpression(
						"eventVersion = :version AND generation = :generation AND attribute_not_exists(deleting)")
				.expressionAttributeValues(values))
			.build();
	}

	/** The position of the newest compaction boundary among the events, or -1 if none. */
	private static int newestCompaction(List<SessionEvent> events) {
		for (int i = events.size() - 1; i >= 0; i--) {
			if (EventType.COMPACTION.equals(events.get(i).getType())) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Puts the event item in the generation of its session at the sequence number, unless
	 * an event with its id is already in that generation's log.
	 */
	private TransactWriteItem putEvent(Map<String, AttributeValue> item, String generation, long seq) {
		Map<String, AttributeValue> sequenced = SessionItems.sequenced(item, generation, seq);
		return TransactWriteItem.builder()
			.put(put -> put.tableName(this.tableName).item(sequenced).conditionExpression("attribute_not_exists(pk)"))
			.build();
	}

	private void checkSessionSize(Session session) {
		int size = ItemSizes.size(this.items.sessionItem(session, GENERATION_PLACEHOLDER, indexEntries(session)));
		Assert.isTrue(size <= MAX_SESSION_ITEM_BYTES,
				() -> "Session '" + session.id() + "' is " + size + " bytes by DynamoDB's count, more than the "
						+ MAX_SESSION_ITEM_BYTES + " bytes a session item can have");
	}

	/**
	 * Checks the event item against {@link ItemSizes#MAX_EVENT_ITEM_BYTES} and returns
	 * its size.
	 */
	private static int checkEventSize(SessionEvent event, Map<String, AttributeValue> item) {
		int size = ItemSizes.size(item);
		Assert.isTrue(size <= ItemSizes.MAX_EVENT_ITEM_BYTES,
				() -> "Event '" + event.getId() + "' of session '" + event.getSessionId() + "' is " + size
						+ " bytes by DynamoDB's count, more than the " + ItemSizes.MAX_EVENT_ITEM_BYTES
						+ " bytes an event item can have");
		return size;
	}

	// Table access

	private @Nullable StoredSession readSession(String sessionId) {
		GetItemResponse response = this.client
			.getItem(get -> get.tableName(this.tableName).key(SessionItems.sessionKey(sessionId)).consistentRead(true));
		return (response.hasItem() && !response.item().isEmpty()) ? this.items.storedSession(response.item()) : null;
	}

	/**
	 * Commits the writes as one transaction. A cancellation by a failed condition or a
	 * conflict is returned as its outcome; any other failure is thrown.
	 */
	private Outcome transact(List<TransactWriteItem> writes) {
		try {
			this.client.transactWriteItems(request -> request.transactItems(writes));
			return Outcome.COMMITTED;
		}
		catch (TransactionCanceledException ex) {
			List<String> reasons = ex.cancellationReasons().stream().map(CancellationReason::code).toList();
			if (reasons.isEmpty() || !Outcome.RACE_REASONS.containsAll(reasons)) {
				throw ex;
			}
			return new Outcome(reasons);
		}
	}

	/**
	 * Runs the attempt until it returns; throwing {@link TryAgain} is a lost race, tried
	 * again after a pause. Throws an {@link IllegalStateException} after
	 * {@value Retries#MAX_ATTEMPTS} attempts that lost.
	 */
	private <T> T retrying(String sessionId, Retryable<T> attempt) {
		return Retries.run(Retries.LOST_RACES, attempt, () -> contended(sessionId));
	}

	private void retrying(String sessionId, Runnable attempt) {
		Retries.run(Retries.LOST_RACES, attempt, () -> contended(sessionId));
	}

	private static String contended(String sessionId) {
		return "Session '" + sessionId + "' stayed contended after " + Retries.MAX_ATTEMPTS + " attempts";
	}

	/**
	 * How a transaction ended: committed, or cancelled with one reason per write, in
	 * order.
	 */
	private record Outcome(List<String> reasons) {

		static final Outcome COMMITTED = new Outcome(List.of());

		/**
		 * The reason of a write that was fine, in a transaction another write cancelled.
		 */
		static final String NONE = "None";

		static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

		static final String TRANSACTION_CONFLICT = "TransactionConflict";

		/**
		 * The reasons a race cancels a transaction with; any other reason is an error.
		 */
		static final Set<String> RACE_REASONS = Set.of(NONE, CONDITIONAL_CHECK_FAILED, TRANSACTION_CONFLICT);

		boolean isCommitted() {
			return this.reasons.isEmpty();
		}

		boolean conditionFailed(int write) {
			return !isCommitted() && CONDITIONAL_CHECK_FAILED.equals(this.reasons.get(write));
		}

		/**
		 * Whether the first write passed its condition: when appending, the session
		 * item's.
		 */
		boolean firstWritePassed() {
			return !isCommitted() && NONE.equals(this.reasons.get(0));
		}

		/** The position of the first write whose condition failed, or -1 if none did. */
		int firstFailedCondition() {
			return this.reasons.indexOf(CONDITIONAL_CHECK_FAILED);
		}

	}

	/** Builds a {@link DynamoDbSessionRepository}. */
	public static final class Builder {

		private @Nullable DynamoDbClient dynamoDbClient;

		private String tableName = "spring-ai-session";

		private Set<String> indexedAttributes = Set.of(SessionAttributes.PRINCIPAL_NAME);

		private boolean initializeTable = true;

		private JsonMapper jsonMapper = JsonMapper.builder().build();

		private Builder() {
		}

		/**
		 * The application's client, with its region, credentials, endpoint and retry
		 * policy; required. The repository never closes it.
		 */
		public Builder dynamoDbClient(DynamoDbClient dynamoDbClient) {
			Assert.notNull(dynamoDbClient, "dynamoDbClient must not be null");
			this.dynamoDbClient = dynamoDbClient;
			return this;
		}

		/**
		 * The name of the table; {@code spring-ai-session} by default. Sessions stay in
		 * the table they were saved to, so a new name starts with an empty table.
		 */
		public Builder tableName(String tableName) {
			Assert.hasText(tableName, "tableName must not be empty");
			this.tableName = tableName;
			return this;
		}

		/**
		 * The metadata keys {@link DynamoDbSessionRepository#findByMetadata} answers for,
		 * besides {@link SessionAttributes#PRINCIPAL_NAME}, which is always indexed. A
		 * key added later finds only the sessions saved after it was added. At most 32
		 * keys, the principal included.
		 */
		public Builder indexedAttributes(Set<String> indexedAttributes) {
			Assert.notNull(indexedAttributes, "indexedAttributes must not be null");
			Assert.noNullElements(indexedAttributes, "indexedAttributes must not contain null");
			Set<String> keys = new HashSet<>(indexedAttributes);
			keys.add(SessionAttributes.PRINCIPAL_NAME);
			Assert.isTrue(keys.size() <= MAX_INDEXED_ATTRIBUTES, () -> "At most " + MAX_INDEXED_ATTRIBUTES
					+ " metadata keys can be indexed, the principal included, not " + keys.size());
			this.indexedAttributes = Set.copyOf(keys);
			return this;
		}

		/**
		 * Whether {@link #build()} creates a missing table and checks an existing one;
		 * {@code true} by default. When off, it makes no table calls and needs no
		 * {@code DescribeTable} or {@code CreateTable} permission.
		 */
		public Builder initializeTable(boolean initializeTable) {
			this.initializeTable = initializeTable;
			return this;
		}

		/**
		 * The mapper of event payloads and metadata; a plain {@link JsonMapper} by
		 * default.
		 */
		public Builder jsonMapper(JsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Builds the repository. When {@link #initializeTable(boolean)} is on, first
		 * creates the table if it is missing, waits until it is active, and checks its
		 * key and indexes.
		 * @throws IllegalArgumentException if no client is set
		 * @throws IllegalStateException if the table does not fit
		 */
		public DynamoDbSessionRepository build() {
			DynamoDbClient client = this.dynamoDbClient;
			Assert.notNull(client, "dynamoDbClient must be set");
			if (this.initializeTable) {
				SessionTable.initialize(client, this.tableName);
			}
			return new DynamoDbSessionRepository(client, this.tableName, this.indexedAttributes,
					new SessionItems(new SessionEventCodec(this.jsonMapper)));
		}

	}

}
