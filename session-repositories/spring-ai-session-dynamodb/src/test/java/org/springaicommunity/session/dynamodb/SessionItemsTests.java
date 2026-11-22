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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.support.SessionEventCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Sessions, events and index entries through their items.
 *
 * @author Dominik Kovács
 */
class SessionItemsTests {

	private final SessionItems items = new SessionItems(new SessionEventCodec());

	@Test
	void sessionRoundTripsWithItsCountersAtZero() {
		Session session = Session.builder()
			.id("s-1")
			.createdAt(Instant.parse("2026-03-01T10:15:30.123456789Z"))
			.expiresAt(Instant.parse("2026-03-02T10:15:30Z"))
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.build();

		StoredSession stored = this.items.storedSession(this.items.sessionItem(session, "g-1", Set.of()));

		assertThat(stored.session().id()).isEqualTo("s-1");
		assertThat(stored.session().createdAt()).isEqualTo(session.createdAt());
		assertThat(stored.session().expiresAt()).isEqualTo(session.expiresAt());
		assertThat(stored.session().metadata()).containsEntry(SessionAttributes.PRINCIPAL_NAME, "alice");
		assertThat(stored.eventVersion()).isZero();
		assertThat(stored.nextSeq()).isZero();
		assertThat(stored.boundarySeq()).isNull();
		assertThat(stored.revision()).isZero();
		assertThat(stored.generation()).isEqualTo("g-1");
		assertThat(stored.indexEntries()).isEmpty();
		assertThat(stored.deleting()).isFalse();
	}

	@Test
	void sessionItemRecordsItsIndexEntriesAndOmitsAnEmptySet() {
		Session session = Session.builder().id("s-5").build();
		Set<String> entries = Set.of(SessionAttributes.PRINCIPAL_NAME + "\0alice", "tenant\0acme");

		Map<String, AttributeValue> item = this.items.sessionItem(session, "g-5", entries);

		assertThat(item.get(SessionItems.INDEX_ENTRIES).ss()).containsExactlyInAnyOrderElementsOf(entries);
		assertThat(this.items.storedSession(item).indexEntries()).isEqualTo(entries);
		assertThat(this.items.sessionItem(session, "g-5", Set.of())).doesNotContainKey(SessionItems.INDEX_ENTRIES);
	}

	@Test
	void sessionWithoutExpiryHasNoExpiryIndexKeys() {
		Map<String, AttributeValue> item = this.items.sessionItem(Session.builder().id("s-2").build(), "g-2", Set.of());

		assertThat(item).doesNotContainKeys(SessionTable.EXPIRES_AT, SessionTable.EXPIRY_SHARD);
		assertThat(this.items.storedSession(item).session().expiresAt()).isNull();
	}

	@Test
	void sessionItemWithoutGenerationIsRejected() {
		Map<String, AttributeValue> item = new HashMap<>(
				this.items.sessionItem(Session.builder().id("s-4").build(), "g-4", Set.of()));
		item.remove(SessionItems.GENERATION);

		assertThatIllegalStateException().isThrownBy(() -> this.items.storedSession(item))
			.withMessage("Session item 's-4' has no generation: it was written by an earlier, incompatible build "
					+ "of the repository");
	}

	@Test
	void eventRoundTripsThroughItsItem() {
		SessionEvent event = SessionEvent.builder()
			.id("e-1")
			.sessionId("s-1")
			.timestamp(Instant.parse("2026-03-01T10:15:30.123456Z"))
			.message(new UserMessage("hello"))
			.metadata("model", "m-1")
			.build();

		Map<String, AttributeValue> item = this.items.eventItem(event, "g-1", 7);
		SessionEvent read = this.items.event(item);

		assertThat(SessionItems.seq(item)).isEqualTo(7);
		assertThat(read.getId()).isEqualTo("e-1");
		assertThat(read.getSessionId()).isEqualTo("s-1");
		assertThat(read.getTimestamp()).isEqualTo(event.getTimestamp());
		assertThat(read.getMessageType()).isEqualTo(MessageType.USER);
		assertThat(read.getMessage().getText()).isEqualTo("hello");
		assertThat(read.getMetadata()).containsEntry("model", "m-1");
	}

	@Test
	void eventThatIsNotMessageKeepsItsTypeAndPayload() {
		SessionEvent event = SessionEvent.builder()
			.sessionId("s-1")
			.type(EventType.of("MY_APP.PROGRESS"))
			.payload("step", 2)
			.build();

		SessionEvent read = this.items.event(this.items.eventItem(event, "g-1", 0));

		assertThat(read.getType()).isEqualTo(EventType.of("MY_APP.PROGRESS"));
		assertThat(read.hasMessage()).isFalse();
		assertThat(((Number) read.getPayload().get("step")).intValue()).isEqualTo(2);
	}

	@Test
	void idsWithHashesAndColonsReadBackFromKeys() {
		String sessionId = "test-with:complex@chars#123/x";
		Session session = Session.builder().id(sessionId).build();

		assertThat(SessionItems.sessionId(this.items.sessionItem(session, "g-3", Set.of()))).isEqualTo(sessionId);
		assertThat(SessionItems.indexedSessionId(SessionItems.indexKey("principal\0alice", session)))
			.isEqualTo(sessionId);
		assertThat(SessionItems.eventId(SessionItems.eventKey(sessionId, "g-3", "e#1"))).isEqualTo("e#1");
	}

	@Test
	void eventKeysCarryTheGenerationOfTheirSession() {
		SessionEvent event = SessionEvent.builder().id("e-1").sessionId("s-1").message(new UserMessage("hi")).build();
		Map<String, AttributeValue> encoded = this.items.eventItem(event, "g-1", 0);

		Map<String, AttributeValue> item = SessionItems.sequenced(encoded, "g-2", 5);

		assertThat(encoded.get(SessionTable.SK).s()).isEqualTo("E#g-1#e-1");
		assertThat(item.get(SessionTable.SK).s()).isEqualTo("E#g-2#e-1").startsWith(SessionItems.eventPrefix("g-2"));
		assertThat(SessionItems.seq(item)).isEqualTo(5);
		assertThat(item).doesNotContainKey(SessionItems.GENERATION);
		assertThat(this.items.event(item).getId()).isEqualTo("e-1");
	}

	@Test
	void instantsKeepNanosecondsIncludingBeforeTheEpoch() {
		for (Instant instant : List.of(Instant.parse("2026-03-01T10:15:30.123456789Z"), Instant.EPOCH,
				Instant.ofEpochSecond(-1, 5), Instant.parse("1969-12-31T23:59:59.999999999Z"))) {
			assertThat(SessionItems.instant(SessionItems.instant(instant))).isEqualTo(instant);
		}
		assertThat(SessionItems.instant(AttributeValue.fromN("1763755000")))
			.isEqualTo(Instant.ofEpochSecond(1763755000));
		assertThat(SessionItems.instant(Instant.ofEpochSecond(1772360130, 123456789)).n())
			.isEqualTo("1772360130.123456789");
		assertThat(SessionItems.instant(Instant.ofEpochSecond(-1, 5)).n()).isEqualTo("-0.999999995");
	}

	@Test
	void sortableInstantsSortInTimeOrder() {
		List<String> sortable = Stream
			.of(Instant.ofEpochSecond(-5, 1), Instant.ofEpochSecond(-1), Instant.EPOCH, Instant.ofEpochSecond(0, 1),
					Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00.000000001Z"))
			.map(SessionItems::sortable)
			.toList();

		assertThat(sortable).isSorted()
			.doesNotHaveDuplicates()
			.allSatisfy(text -> assertThat(text).matches("[0-9]{29}"));
	}

	@Test
	void sortableInstantsUseAsciiDigitsWhateverTheDefaultLocale() {
		Locale defaultLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("ar-EG"));
			assertThat(SessionItems.sortable(Instant.ofEpochSecond(0, 5))).isEqualTo("09223372036854775808000000005");
		}
		finally {
			Locale.setDefault(defaultLocale);
		}
	}

	@Test
	void indexKeysTellLongValuesSharingAPrefixApart() {
		String value = "p-" + "x".repeat(600);

		assertThat(SessionItems.indexPk("principal", value))
			.isNotEqualTo(SessionItems.indexPk("principal", value + "tail"));
		assertThat(SessionItems.indexPk("principal", "a")).isNotEqualTo(SessionItems.indexPk("other", "a"));
		assertThat(SessionItems.indexPk("principal", value).s()).hasSize(2 + 64);
		assertThat(SessionItems.indexPk("ab", "c")).isNotEqualTo(SessionItems.indexPk("a", "bc"));
		assertThat(SessionItems.indexPk("principal", "alice").s())
			.isEqualTo("I#58fd98b9b74d0691e1ac8b00d0028dd79809385c5a5a298397622fb1fe68b1ea");
	}

	@Test
	void expiryShardsStayInRange() {
		for (int i = 0; i < 1000; i++) {
			int shard = Integer.parseInt(SessionItems.expiryShard(UUID.randomUUID().toString()).s());
			assertThat(shard).isBetween(0, SessionTable.EXPIRY_SHARDS - 1);
		}
		assertThat("polygenelubricants".hashCode()).isEqualTo(Integer.MIN_VALUE);
		assertThat(SessionItems.expiryShard("polygenelubricants").s()).isEqualTo("0");
	}

	@Test
	void indexEntriesHoldTheIndexedNamesWithTheirValuesAsTheyReadBack() {
		Session session = Session.builder()
			.id("s")
			.metadata(Map.of(SessionAttributes.PRINCIPAL_NAME, 4242L, "agent", "support", "score",
					new BigDecimal("1.10")))
			.build();

		assertThat(this.items.indexEntries(session, Set.of(SessionAttributes.PRINCIPAL_NAME, "score", "tenant")))
			.containsExactlyInAnyOrder(SessionAttributes.PRINCIPAL_NAME + "\0" + "4242", "score\0" + "1.1");
	}

	@Test
	void anIndexEntryIsKeyedOnTheHashOfItsNameAndValue() {
		Session session = Session.builder().id("s-6").build();

		assertThat(SessionItems.indexKey("principal\0alice", session).get(SessionTable.PK))
			.isEqualTo(SessionItems.indexPk("principal", "alice"));
	}

}
