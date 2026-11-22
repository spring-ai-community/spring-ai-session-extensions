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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionBoundary;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads that fetch only the events their view or window can contain.
 *
 * @author Dominik Kovács
 */
class DynamoDbSessionRepositoryReadsTests {

	private final RecordingDynamoDbClient client = DynamoDbTestSupport.recordingClient();

	private final DynamoDbSessionRepository repository = DynamoDbTestSupport.repository(this.client).build();

	@Test
	void lastNReadsTheWindowAndItsTurnNotTheWholeLog() {
		String sessionId = sessionWithTurns(100);
		this.client.resetItemsRead();

		List<SessionEvent> window = this.repository.findEvents(sessionId, EventFilter.lastN(3));

		assertThat(texts(window)).containsExactly("u99", "a99", "u100", "a100");
		// One page of twice the window
		assertThat(this.client.itemsRead()).isEqualTo(6);
	}

	@Test
	void lastNOfAFilterThatHidesTurnStartsStopsAtTheWindow() {
		String sessionId = sessionWithTurns(100);
		this.client.resetItemsRead();

		List<SessionEvent> window = this.repository.findEvents(sessionId,
				EventFilter.builder().messageTypes(Set.of(MessageType.ASSISTANT)).lastN(2).build());

		assertThat(texts(window)).containsExactly("a99", "a100");
		assertThat(this.client.itemsRead()).isEqualTo(4);
	}

	@Test
	void lastNLargerThanTheLogReturnsTheWholeLog() {
		String sessionId = sessionWithTurns(3);

		assertThat(texts(this.repository.findEvents(sessionId, EventFilter.lastN(50)))).containsExactly("u1", "a1",
				"u2", "a2", "u3", "a3");
	}

	@Test
	void lastNOfIntegerMaxValueReturnsTheWholeLog() {
		String sessionId = sessionWithTurns(3);

		assertThat(texts(this.repository.findEvents(sessionId, EventFilter.lastN(Integer.MAX_VALUE))))
			.containsExactly("u1", "a1", "u2", "a2", "u3", "a3");
	}

	@Test
	void activeViewReadsTheBoundaryOnwardAndTheEventsItNames() {
		String sessionId = newSession();
		List<SessionEvent> log = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			SessionEvent event = DynamoDbTestSupport.userEvent(sessionId, "m" + i);
			this.repository.appendEvent(event);
			log.add(event);
		}
		SessionEvent summary = SessionEvent.builder()
			.sessionId(sessionId)
			.message(new AssistantMessage("summary"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build();
		CompactionBoundary boundary = new CompactionBoundary(UUID.randomUUID().toString(), List.of(log.get(0).getId()),
				List.of(summary.getId()), List.of(log.get(98).getId(), log.get(99).getId()), 97, 0, "test");
		SessionEvent boundaryEvent = SessionEvent.builder()
			.id(boundary.id())
			.sessionId(sessionId)
			.type(EventType.COMPACTION)
			.payload(boundary.toPayload())
			.build();
		assertThat(this.repository.appendEvents(sessionId, List.of(boundaryEvent, summary),
				this.repository.getEventVersion(sessionId)))
			.isTrue();
		this.client.resetItemsRead();

		List<SessionEvent> view = this.repository.findEvents(sessionId, EventFilter.active());

		assertThat(texts(view)).containsExactly("m0", "summary", "m98", "m99");
		// The boundary and the summary, then the three older events the boundary names
		assertThat(this.client.itemsRead()).isEqualTo(5);
	}

	@Test
	void activeViewFollowsTheNewestBoundaryOfACompactionThatLandsDuringTheRead() {
		String table = DynamoDbTestSupport.newTableName();
		DynamoDbSessionRepository reader = DynamoDbSessionRepository.builder()
			.dynamoDbClient(this.client)
			.tableName(table)
			.build();
		DynamoDbSessionRepository compactor = DynamoDbSessionRepository.builder()
			.dynamoDbClient(DynamoDbTestSupport.client())
			.tableName(table)
			.initializeTable(false)
			.build();
		String sessionId = reader.save(Session.builder().id(UUID.randomUUID().toString()).build()).id();
		List<SessionEvent> log = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			SessionEvent event = DynamoDbTestSupport.userEvent(sessionId, "m" + i);
			reader.appendEvent(event);
			log.add(event);
		}
		assertThat(reader.appendEvents(sessionId,
				compaction(sessionId, List.of(log.get(0).getId()), List.of(log.get(18).getId(), log.get(19).getId())),
				reader.getEventVersion(sessionId)))
			.isTrue();
		// Between the reads of the session and of its events, a second compaction lands.
		// It pins an event older than the first boundary, one that boundary doesn't name.
		AtomicBoolean compacted = new AtomicBoolean();
		this.client.before(request -> {
			if (request instanceof QueryRequest && compacted.compareAndSet(false, true)) {
				assertThat(compactor.appendEvents(sessionId,
						compaction(sessionId, List.of(log.get(5).getId()), List.of(log.get(19).getId())),
						compactor.getEventVersion(sessionId)))
					.isTrue();
			}
		});

		List<SessionEvent> view;
		try {
			view = reader.findEvents(sessionId, EventFilter.active());
		}
		finally {
			this.client.clearHook();
		}

		List<SessionEvent> expected = EventFilter.active().apply(reader.findEvents(sessionId, EventFilter.all()));
		assertThat(compacted).isTrue();
		assertThat(ids(expected)).contains(log.get(5).getId());
		assertThat(ids(view)).isEqualTo(ids(expected));
	}

	/** A boundary event and its synthetic summary event, to append together. */
	private static List<SessionEvent> compaction(String sessionId, List<String> pinned, List<String> preserved) {
		SessionEvent summary = SessionEvent.builder()
			.sessionId(sessionId)
			.message(new AssistantMessage("summary"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build();
		CompactionBoundary boundary = new CompactionBoundary(UUID.randomUUID().toString(), pinned,
				List.of(summary.getId()), preserved, 0, 0, "test");
		SessionEvent boundaryEvent = SessionEvent.builder()
			.id(boundary.id())
			.sessionId(sessionId)
			.type(EventType.COMPACTION)
			.payload(boundary.toPayload())
			.build();
		return List.of(boundaryEvent, summary);
	}

	private static List<String> ids(List<SessionEvent> events) {
		return events.stream().map(SessionEvent::getId).toList();
	}

	private String newSession() {
		return this.repository.save(Session.builder().id(UUID.randomUUID().toString()).build()).id();
	}

	/** A session of the given number of turns: "u1", "a1", "u2", "a2", ... */
	private String sessionWithTurns(int turns) {
		String sessionId = newSession();
		for (int turn = 1; turn <= turns; turn++) {
			this.repository
				.appendEvent(SessionEvent.builder().sessionId(sessionId).message(new UserMessage("u" + turn)).build());
			this.repository.appendEvent(
					SessionEvent.builder().sessionId(sessionId).message(new AssistantMessage("a" + turn)).build());
		}
		return sessionId;
	}

	private static List<String> texts(List<SessionEvent> events) {
		return events.stream().map(DynamoDbTestSupport::text).toList();
	}

}
