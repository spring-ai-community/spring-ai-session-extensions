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
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionBoundary;

/**
 * The reads of a session's events, through the {@code seq} index, strongly consistent.
 *
 * @author Dominik Kovács
 */
final class EventReads {

	private final DynamoDbClient client;

	private final String tableName;

	private final SessionItems items;

	EventReads(DynamoDbClient client, String tableName, SessionItems items) {
		this.client = client;
		this.tableName = tableName;
		this.items = items;
	}

	/**
	 * The events of the stored session that the filter selects, as
	 * {@link EventFilter#apply} defines them.
	 */
	List<SessionEvent> find(StoredSession stored, EventFilter filter) {
		String sessionId = stored.session().id();
		if (filter.activeView()) {
			return filter.apply(activeView(stored));
		}
		if (filter.window() instanceof EventFilter.Window.Last last) {
			return filter.apply(newestMatches(sessionId, filter, last.n()));
		}
		return filter.apply(events(from(sessionId, 0)));
	}

	/**
	 * The events the active view can contain, oldest first: everything from the stored
	 * boundary on, plus the older events that the newest boundary among them names. That
	 * boundary can be newer than the stored one when a compaction lands during the read.
	 * Without a boundary, the whole log.
	 */
	private List<SessionEvent> activeView(StoredSession stored) {
		String sessionId = stored.session().id();
		Long boundarySeq = stored.boundarySeq();
		if (boundarySeq == null) {
			return events(from(sessionId, 0));
		}
		List<SessionEvent> tail = events(from(sessionId, boundarySeq));
		CompactionBoundary boundary = CompactionBoundary.newest(tail);
		if (boundary == null) {
			// The boundary was deleted after the session was read: return what is left
			return tail;
		}
		Set<String> read = tail.stream().map(SessionEvent::getId).collect(Collectors.toSet());
		List<Map<String, AttributeValue>> named = Stream
			.of(boundary.pinnedEventIds(), boundary.summaryEventIds(), boundary.preservedEventIds())
			.flatMap(List::stream)
			.distinct()
			.filter(eventId -> !read.contains(eventId))
			.map(eventId -> SessionItems.eventKey(sessionId, stored.generation(), eventId))
			.toList();
		// The events named but not read are older than the stored boundary
		List<Map<String, AttributeValue>> older = new ArrayList<>(Batches.get(this.client, this.tableName, named));
		older.sort(Comparator.comparingLong(SessionItems::seq));
		return Stream.concat(events(older).stream(), tail.stream()).toList();
	}

	/**
	 * The newest events that match the filter's criteria, oldest first. Reads the log
	 * backwards until it holds {@code n} matches and, when the window can extend to its
	 * turn, a matching turn start at or before the oldest of them.
	 */
	private List<SessionEvent> newestMatches(String sessionId, EventFilter filter, int n) {
		boolean extendsToTurn = canMatchTurnStart(filter);
		List<SessionEvent> matches = new ArrayList<>();
		Iterator<Map<String, AttributeValue>> newestFirst = newestFirst(sessionId, n).iterator();
		boolean complete = false;
		// Checked before hasNext(), so that no page is fetched once the window is full
		while (!complete && newestFirst.hasNext()) {
			SessionEvent event = this.items.event(newestFirst.next());
			if (filter.matches(event)) {
				matches.add(event);
				complete = matches.size() >= n && (!extendsToTurn || event.isTurnStart());
			}
		}
		Collections.reverse(matches);
		return matches;
	}

	/** Whether a turn start, a user message event, can pass the filter's criteria. */
	private static boolean canMatchTurnStart(EventFilter filter) {
		Set<EventType> eventTypes = filter.eventTypes();
		Set<MessageType> messageTypes = filter.messageTypes();
		return (eventTypes == null || eventTypes.contains(EventType.MESSAGE))
				&& (messageTypes == null || messageTypes.contains(MessageType.USER));
	}

	/**
	 * The session's event items, newest first, fetched page by page as they are iterated.
	 */
	private SdkIterable<Map<String, AttributeValue>> newestFirst(String sessionId, int n) {
		return this.client
			.queryPaginator(query -> query.tableName(this.tableName)
				.indexName(SessionTable.SEQ_INDEX)
				.keyConditionExpression("pk = :pk")
				.expressionAttributeValues(Map.of(":pk", SessionItems.sessionPk(sessionId)))
				.scanIndexForward(false)
				// Twice the window leaves room for its turn; more pages only if needed
				.limit((int) Math.min(2L * n, 1_000))
				.consistentRead(true))
			.items();
	}

	/** The session's event items from the sequence number on, oldest first. */
	private List<Map<String, AttributeValue>> from(String sessionId, long seq) {
		List<Map<String, AttributeValue>> found = new ArrayList<>();
		this.client
			.queryPaginator(query -> query.tableName(this.tableName)
				.indexName(SessionTable.SEQ_INDEX)
				.keyConditionExpression("pk = :pk AND seq >= :seq")
				.expressionAttributeValues(
						Map.of(":pk", SessionItems.sessionPk(sessionId), ":seq", SessionItems.number(seq)))
				.consistentRead(true))
			.items()
			.forEach(found::add);
		return found;
	}

	private List<SessionEvent> events(List<Map<String, AttributeValue>> found) {
		return found.stream().map(this.items::event).toList();
	}

}
