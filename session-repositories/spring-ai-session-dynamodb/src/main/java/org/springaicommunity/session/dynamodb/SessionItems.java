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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.util.Assert;

/**
 * Converts sessions, events and index entries to items of the {@link SessionTable} and
 * back. A session is {@code (S#<id>, SESSION)}, an event {@code (S#<session id>,
 * E#<generation>#<event id>)}, and an index entry {@code (I#<hash of name and value>,
 * <created at>#<session id>)}. Ids are read back from the keys.
 *
 * @author Dominik Kovács
 */
final class SessionItems {

	static final String CREATED_AT = "createdAt";

	static final String METADATA = "metadata";

	static final String EVENT_VERSION = "eventVersion";

	static final String NEXT_SEQ = "nextSeq";

	static final String BOUNDARY_SEQ = "boundarySeq";

	static final String REVISION = "revision";

	static final String GENERATION = "generation";

	static final String DELETING = "deleting";

	static final String INDEX_ENTRIES = "indexEntries";

	static final String TIMESTAMP = "timestamp";

	static final String EVENT_TYPE = "eventType";

	static final String MESSAGE_TYPE = "messageType";

	static final String TEXT = "text";

	static final String PAYLOAD = "payload";

	private static final String SESSION_PREFIX = "S#";

	private static final String EVENT_PREFIX = "E#";

	private static final String SESSION_SK = "SESSION";

	private static final String INDEX_PREFIX = "I#";

	private final SessionEventCodec codec;

	SessionItems(SessionEventCodec codec) {
		this.codec = codec;
	}

	// Keys

	static AttributeValue sessionPk(String sessionId) {
		return AttributeValue.fromS(SESSION_PREFIX + sessionId);
	}

	static Map<String, AttributeValue> sessionKey(String sessionId) {
		return Map.of(SessionTable.PK, sessionPk(sessionId), SessionTable.SK, AttributeValue.fromS(SESSION_SK));
	}

	/**
	 * The key of an event in one generation of its session. A generation is a UUID, so it
	 * contains no {@code #}.
	 */
	static Map<String, AttributeValue> eventKey(String sessionId, String generation, String eventId) {
		return Map.of(SessionTable.PK, sessionPk(sessionId), SessionTable.SK,
				AttributeValue.fromS(eventPrefix(generation) + eventId));
	}

	/** The prefix of the sort key of every event in one generation of a session. */
	static String eventPrefix(String generation) {
		return EVENT_PREFIX + generation + "#";
	}

	static AttributeValue indexPk(String name, String value) {
		return entryPk(indexEntry(name, value));
	}

	/**
	 * The key of a session's index entry. Entries sort by the session's creation time,
	 * then its id.
	 * @param entry the entry, as {@link #indexEntries} names it
	 */
	static Map<String, AttributeValue> indexKey(String entry, Session session) {
		return Map.of(SessionTable.PK, entryPk(entry), SessionTable.SK,
				AttributeValue.fromS(sortable(session.createdAt()) + "#" + session.id()));
	}

	private static AttributeValue entryPk(String entry) {
		byte[] hash = sha256().digest(entry.getBytes(StandardCharsets.UTF_8));
		return AttributeValue.fromS(INDEX_PREFIX + HexFormat.of().formatHex(hash));
	}

	/**
	 * The session id of an item keyed by it: a session, an event, or an expiry index
	 * entry.
	 */
	static String sessionId(Map<String, AttributeValue> item) {
		return item.get(SessionTable.PK).s().substring(SESSION_PREFIX.length());
	}

	/** The id of an event item: its sort key after the generation. */
	static String eventId(Map<String, AttributeValue> item) {
		String sk = item.get(SessionTable.SK).s();
		return sk.substring(sk.indexOf('#', EVENT_PREFIX.length()) + 1);
	}

	/** The session an index entry points to: its sort key after the instant. */
	static String indexedSessionId(Map<String, AttributeValue> indexEntry) {
		String sk = indexEntry.get(SessionTable.SK).s();
		return sk.substring(sk.indexOf('#') + 1);
	}

	static long seq(Map<String, AttributeValue> eventItem) {
		return longValue(eventItem.get(SessionTable.SEQ));
	}

	// Sessions

	/**
	 * The item of a new session of the given generation, which records the index entries
	 * it owns; its counters start at zero.
	 */
	Map<String, AttributeValue> sessionItem(Session session, String generation, Set<String> indexEntries) {
		Map<String, AttributeValue> item = new HashMap<>(sessionKey(session.id()));
		item.put(CREATED_AT, instant(session.createdAt()));
		item.put(METADATA, json(session.metadata()));
		item.put(EVENT_VERSION, number(0));
		item.put(NEXT_SEQ, number(0));
		item.put(REVISION, number(0));
		item.put(GENERATION, AttributeValue.fromS(generation));
		if (!indexEntries.isEmpty()) {
			item.put(INDEX_ENTRIES, stringSet(indexEntries));
		}
		Instant expiresAt = session.expiresAt();
		if (expiresAt != null) {
			item.put(SessionTable.EXPIRES_AT, instant(expiresAt));
			item.put(SessionTable.EXPIRY_SHARD, expiryShard(session.id()));
		}
		return item;
	}

	StoredSession storedSession(Map<String, AttributeValue> item) {
		Assert.state(item.containsKey(GENERATION), () -> "Session item '" + sessionId(item)
				+ "' has no generation: it was written by an earlier, incompatible build of the repository");
		AttributeValue expiresAt = item.get(SessionTable.EXPIRES_AT);
		AttributeValue boundarySeq = item.get(BOUNDARY_SEQ);
		AttributeValue indexEntries = item.get(INDEX_ENTRIES);
		Session session = Session.builder()
			.id(sessionId(item))
			.createdAt(instant(item.get(CREATED_AT)))
			.expiresAt((expiresAt != null) ? instant(expiresAt) : null)
			.metadata(this.codec.fromJsonMap(item.get(METADATA).s()))
			.build();
		return new StoredSession(session, longValue(item.get(EVENT_VERSION)), longValue(item.get(NEXT_SEQ)),
				(boundarySeq != null) ? longValue(boundarySeq) : null, longValue(item.get(REVISION)),
				item.get(GENERATION).s(), (indexEntries != null) ? Set.copyOf(indexEntries.ss()) : Set.of(),
				item.containsKey(DELETING));
	}

	/**
	 * The index entries of the session: {@code name + '\0' + value} for each indexed name
	 * its metadata has. The value is a string of the metadata as it reads back from its
	 * JSON, which is what {@code findByMetadata} compares; {@code 1.10} reads back as
	 * {@code 1.1}, for example.
	 */
	Set<String> indexEntries(Session session, Set<String> indexedAttributes) {
		Map<String, Object> metadata = this.codec.fromJsonMap(this.codec.toJson(session.metadata()));
		Set<String> entries = new HashSet<>();
		for (String name : indexedAttributes) {
			Object value = metadata.get(name);
			if (value != null) {
				entries.add(indexEntry(name, String.valueOf(value)));
			}
		}
		return entries;
	}

	private static String indexEntry(String name, String value) {
		return name + '\0' + value;
	}

	/** A non-empty set of strings as a string set attribute. */
	static AttributeValue stringSet(Set<String> strings) {
		return AttributeValue.fromSs(List.copyOf(strings));
	}

	AttributeValue json(Map<String, Object> map) {
		return AttributeValue.fromS(this.codec.toJson(map));
	}

	// Events

	Map<String, AttributeValue> eventItem(SessionEvent event, String generation, long seq) {
		SessionEventCodec.EncodedEvent encoded = this.codec.encode(event);
		Map<String, AttributeValue> item = new HashMap<>(eventKey(event.getSessionId(), generation, event.getId()));
		item.put(SessionTable.SEQ, number(seq));
		item.put(TIMESTAMP, instant(event.getTimestamp()));
		item.put(EVENT_TYPE, AttributeValue.fromS(encoded.type().name()));
		MessageType messageType = encoded.messageType();
		if (messageType != null) {
			item.put(MESSAGE_TYPE, AttributeValue.fromS(messageType.name()));
		}
		String text = encoded.text();
		if (text != null) {
			item.put(TEXT, AttributeValue.fromS(text));
		}
		item.put(PAYLOAD, AttributeValue.fromS(encoded.payload()));
		item.put(METADATA, json(event.getMetadata()));
		return item;
	}

	/**
	 * A copy of an event item, keyed in the given generation of its session and at the
	 * sequence number, so that an event encoded once can be written in each attempt.
	 */
	static Map<String, AttributeValue> sequenced(Map<String, AttributeValue> eventItem, String generation, long seq) {
		Map<String, AttributeValue> item = new HashMap<>(eventItem);
		item.putAll(eventKey(sessionId(eventItem), generation, eventId(eventItem)));
		item.put(SessionTable.SEQ, number(seq));
		return item;
	}

	SessionEvent event(Map<String, AttributeValue> item) {
		AttributeValue messageType = item.get(MESSAGE_TYPE);
		AttributeValue text = item.get(TEXT);
		SessionEventCodec.EncodedEvent encoded = new SessionEventCodec.EncodedEvent(
				EventType.of(item.get(EVENT_TYPE).s()),
				(messageType != null) ? MessageType.valueOf(messageType.s()) : null, (text != null) ? text.s() : null,
				item.get(PAYLOAD).s());
		return this.codec.decode(encoded)
			.id(eventId(item))
			.sessionId(sessionId(item))
			.timestamp(instant(item.get(TIMESTAMP)))
			.metadata(this.codec.fromJsonMap(item.get(METADATA).s()))
			.build();
	}

	// Values

	static AttributeValue number(long value) {
		return AttributeValue.fromN(Long.toString(value));
	}

	static long longValue(AttributeValue value) {
		return Long.parseLong(value.n());
	}

	/** An instant as epoch seconds with nine decimals, so that no precision is lost. */
	static AttributeValue instant(Instant instant) {
		BigDecimal seconds = BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9));
		return AttributeValue.fromN(seconds.toPlainString());
	}

	static Instant instant(AttributeValue value) {
		BigDecimal seconds = new BigDecimal(value.n());
		BigDecimal whole = seconds.setScale(0, RoundingMode.FLOOR);
		return Instant.ofEpochSecond(whole.longValueExact(), seconds.subtract(whole).movePointRight(9).intValueExact());
	}

	/** An instant as fixed-width text that sorts in time order, for sort keys. */
	static String sortable(Instant instant) {
		// Flipping the sign bit makes unsigned order follow signed order, negatives first
		String seconds = Long.toUnsignedString(instant.getEpochSecond() ^ Long.MIN_VALUE);
		return "0".repeat(20 - seconds.length()) + seconds + String.format(Locale.ROOT, "%09d", instant.getNano());
	}

	static AttributeValue expiryShard(String sessionId) {
		return AttributeValue.fromS(Integer.toString(Math.floorMod(sessionId.hashCode(), SessionTable.EXPIRY_SHARDS)));
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
