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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.support.SessionEventCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * DynamoDB's item and transaction limits, checked before anything is written.
 *
 * @author Dominik Kovács
 */
class DynamoDbSessionRepositoryLimitsTests {

	/** Stands in for a generation, which is a UUID of 36 characters. */
	private static final String GENERATION = "x".repeat(36);

	/** What a session item keeps free, as the repository does. */
	private static final int SESSION_ITEM_RESERVE = 96;

	private final DynamoDbSessionRepository repository = DynamoDbTestSupport.repository(DynamoDbTestSupport.client())
		.build();

	private final SessionItems items = new SessionItems(new SessionEventCodec());

	@Test
	void eventOverTheEventItemLimitIsRejectedAndNothingIsWritten() {
		String sessionId = newSession();
		// The codec stores a message's text twice, so half the limit is too much
		SessionEvent large = DynamoDbTestSupport.userEvent(sessionId, "x".repeat(ItemSizes.MAX_EVENT_ITEM_BYTES / 2));

		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.appendEvent(large))
			.withMessageContaining("Event '" + large.getId() + "' of session '" + sessionId + "'")
			.withMessageContaining("bytes an event item can have");

		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).isEmpty();
		assertThat(this.repository.getEventVersion(sessionId)).isZero();
	}

	@Test
	void eventJustUnderTheEventItemLimitIsStored() {
		String sessionId = newSession();
		String text = "x".repeat(ItemSizes.MAX_EVENT_ITEM_BYTES / 2 - 2_000);

		this.repository.appendEvent(DynamoDbTestSupport.userEvent(sessionId, text));

		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).singleElement()
			.satisfies(event -> assertThat(event.getMessage().getText()).isEqualTo(text));
	}

	@Test
	void eventAtTheEventItemLimitIsStoredAndOneByteMoreIsRejected() {
		String sessionId = newSession();
		int room = ItemSizes.MAX_EVENT_ITEM_BYTES
				- ItemSizes.size(this.items.eventItem(status(sessionId, ""), GENERATION, 0));
		SessionEvent atTheLimit = status(sessionId, "x".repeat(room));
		assertThat(ItemSizes.size(this.items.eventItem(atTheLimit, GENERATION, 0)))
			.isEqualTo(ItemSizes.MAX_EVENT_ITEM_BYTES);

		this.repository.appendEvent(atTheLimit);

		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).singleElement()
			.satisfies(event -> assertThat(event.getId()).isEqualTo(atTheLimit.getId()));
		SessionEvent oneByteMore = status(sessionId, "x".repeat(room + 1));
		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.appendEvent(oneByteMore))
			.withMessageContaining("bytes an event item can have");
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).hasSize(1);
		assertThat(this.repository.getEventVersion(sessionId)).isEqualTo(1);
	}

	@Test
	void sessionOverTheItemLimitIsRejected() {
		Session large = Session.builder()
			.id(UUID.randomUUID().toString())
			.metadata("notes", "x".repeat(ItemSizes.MAX_ITEM_BYTES))
			.build();

		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.save(large))
			.withMessageStartingWith("Session '" + large.id() + "' is ")
			.withMessageEndingWith(" bytes by DynamoDB's count, more than the "
					+ (ItemSizes.MAX_ITEM_BYTES - SESSION_ITEM_RESERVE) + " bytes a session item can have");
		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.saveIfAbsent(large));
		assertThat(this.repository.findById(large.id())).isNull();
	}

	@Test
	void sessionAtTheSessionItemLimitIsStoredAndOneByteMoreIsRejected() {
		String id = UUID.randomUUID().toString();
		int room = ItemSizes.MAX_ITEM_BYTES - SESSION_ITEM_RESERVE - ItemSizes.size(sessionItem(notes(id, "")));
		Session atTheLimit = notes(id, "x".repeat(room));
		assertThat(ItemSizes.size(sessionItem(atTheLimit)) + SESSION_ITEM_RESERVE).isEqualTo(ItemSizes.MAX_ITEM_BYTES);

		this.repository.save(atTheLimit);

		assertThat(this.repository.findById(id)).isNotNull();
		int limit = ItemSizes.MAX_ITEM_BYTES - SESSION_ITEM_RESERVE;
		Session oneByteMore = notes(id, "x".repeat(room + 1));
		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.save(oneByteMore))
			.withMessage("Session '" + id + "' is " + (limit + 1) + " bytes by DynamoDB's count, more than the " + limit
					+ " bytes a session item can have");
		assertThat(Objects.requireNonNull(this.repository.findById(id)).metadata()).containsEntry("notes",
				"x".repeat(room));
	}

	@Test
	void batchOfAHundredEventsIsRejectedAndOfNinetyNineIsStored() {
		String sessionId = newSession();
		List<SessionEvent> batch = IntStream.range(0, 100)
			.mapToObj(i -> DynamoDbTestSupport.userEvent(sessionId, "m" + i))
			.toList();

		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.appendEvents(sessionId, batch, 0))
			.withMessageContaining("at most 99 events");
		assertThat(this.repository.appendEvents(sessionId, batch.subList(0, 99), 0)).isTrue();

		assertThat(this.repository.getEventVersion(sessionId)).isEqualTo(1);
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).hasSize(99);
	}

	@Test
	void batchWithANullEventIsRejectedAndNothingIsWritten() {
		String sessionId = newSession();
		// The second element stays null on purpose: the repository must reject it
		SessionEvent[] events = new SessionEvent[2];
		events[0] = DynamoDbTestSupport.userEvent(sessionId, "m");
		List<SessionEvent> batch = Arrays.asList(events);

		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.appendEvents(sessionId, batch, 0))
			.withMessage("events must not contain null");

		assertThat(this.repository.getEventVersion(sessionId)).isZero();
	}

	@Test
	void batchJustUnderTheBatchLimitIsStored() {
		String sessionId = newSession();
		// 25 events of about 150 KB are about 3.76 MB, under the limit of 3,784,704 bytes
		List<SessionEvent> batch = statusEvents(sessionId, 25);

		assertThat(this.repository.appendEvents(sessionId, batch, 0)).isTrue();

		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).hasSize(25);
	}

	@Test
	void batchOverTheBatchLimitIsRejectedAndNothingIsWritten() {
		String sessionId = newSession();
		// Each event fits on its own; 26 of them are about 3.9 MB, over the limit of
		// 3,784,704 bytes but under a transaction's 4 MB: the session item needs room too
		List<SessionEvent> batch = statusEvents(sessionId, 26);

		assertThatIllegalArgumentException().isThrownBy(() -> this.repository.appendEvents(sessionId, batch, 0))
			.withMessageContaining("bytes of events one transaction can take");

		assertThat(this.repository.getEventVersion(sessionId)).isZero();
		assertThat(this.repository.findEvents(sessionId, EventFilter.all())).isEmpty();
	}

	private static List<SessionEvent> statusEvents(String sessionId, int count) {
		return IntStream.range(0, count).mapToObj(i -> status(sessionId, "x".repeat(150_000))).toList();
	}

	/** An event whose payload grows by one byte per character of the text. */
	private static SessionEvent status(String sessionId, String text) {
		return SessionEvent.builder().sessionId(sessionId).type(EventType.of("STATUS")).payload("status", text).build();
	}

	/**
	 * A session whose item grows by one byte per character of the text. Its principal has
	 * an index entry, which its item records.
	 */
	private static Session notes(String id, String text) {
		return Session.builder()
			.id(id)
			.metadata(Map.of("notes", text, SessionAttributes.PRINCIPAL_NAME, "owner-" + id))
			.build();
	}

	/** The item of the session, with the index entries the repository records. */
	private Map<String, AttributeValue> sessionItem(Session session) {
		return this.items.sessionItem(session, GENERATION,
				this.items.indexEntries(session, Set.of(SessionAttributes.PRINCIPAL_NAME)));
	}

	private String newSession() {
		return this.repository.save(Session.builder().id(UUID.randomUUID().toString()).build()).id();
	}

}
