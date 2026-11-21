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

package org.springaicommunity.session.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.lettuce.core.ReadFrom;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.compaction.CompactionBoundary;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.ai.session.support.SessionEventCodec.EncodedEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * A {@link SessionRepository} on Redis.
 *
 * <p>
 * Each session is one Redis hash, {@code <prefix>s:<sessionId>}, holding the session's
 * fields, its events and the position of each event id. Because a session is one key, it
 * lives in one cluster slot and one {@code UNLINK} erases it.
 *
 * <p>
 * Every write that checks a rule is one Lua script on the session's key, so no other call
 * can land between its check and its write. A script is not rolled back when it fails, so
 * each one checks everything before it writes. Reads and {@link #delete} are plain
 * commands.
 *
 * <p>
 * The user index for {@link #findByMetadata} and the expiry set for
 * {@link #deleteExpiredSessions} are separate keys, so in a cluster they can't change in
 * the same step as the session. They are hints: a crash can leave an extra entry, never a
 * missing one, and every read checks the entry against the session itself.
 *
 * <p>
 * Event ids are scoped per session. Redis must run with
 * {@code maxmemory-policy noeviction}, or it may drop live sessions, and the scripts need
 * Redis 7 or later.
 *
 * @author Soby Chacko
 * @since 0.12.0
 */
public final class RedisSessionRepository implements SessionRepository {

	/** The key prefix used unless {@link Builder#keyPrefix(String)} sets another. */
	public static final String DEFAULT_KEY_PREFIX = "ai:session:";

	// The metadata keys that are always indexed for findByMetadata
	private static final Set<String> DEFAULT_INDEXED_ATTRIBUTES = Set.of(SessionAttributes.PRINCIPAL_NAME);

	// Field names of the session hash; none contains ':', so none can clash with an
	// event field ("e:<seq>") or an id field ("i:<eventId>").
	private static final String CREATED = "created";

	private static final String EXPIRES = "expires";

	private static final String METADATA = "metadata";

	private static final String VERSION = "version";

	private static final String NEXT = "next";

	private static final String BOUNDARY = "boundary";

	// How many fields one HMGET asks for, so a long log is read in several steps
	private static final int READ_CHUNK = 1000;

	private static final RedisScript<String> SAVE = script("save.lua", String.class);

	private static final RedisScript<Long> SAVE_IF_ABSENT = script("save-if-absent.lua", Long.class);

	private static final RedisScript<Long> APPEND_EVENT = script("append-event.lua", Long.class);

	private static final RedisScript<Long> APPEND_EVENTS = script("append-events.lua", Long.class);

	private static final RedisScript<String> DELETE_IF_EXPIRED = script("delete-if-expired.lua", String.class);

	private final StringRedisTemplate redis;

	private final SessionEventCodec codec = new SessionEventCodec(JsonMapper.builder().build());

	private final Set<String> indexedAttributes;

	private final String keyPrefix;

	private final String expiriesKey;

	private RedisSessionRepository(StringRedisTemplate redis, Set<String> indexedAttributes, String keyPrefix) {
		this.redis = redis;
		this.indexedAttributes = Set.copyOf(indexedAttributes);
		this.keyPrefix = keyPrefix;
		this.expiriesKey = keyPrefix + "expiries";
	}

	/** Returns a new {@link Builder}. */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		String key = sessionKey(session.id());
		String metadataJson = this.codec.toJson(session.metadata());
		Map<String, Object> metadata = this.codec.fromJsonMap(metadataJson);
		// Index entries first: if this call dies halfway, an extra entry is harmless,
		// a missing one would hide the session from its user
		addIndexEntries(session, metadata);
		Instant expiresAt = session.expiresAt();
		String previous = this.redis.execute(SAVE, List.of(key), String.valueOf(toMicros(session.createdAt())),
				(expiresAt != null) ? String.valueOf(toMicros(expiresAt)) : "", metadataJson);
		if (previous != null) {
			removeStaleIndexEntries(session.id(), this.codec.fromJsonMap(previous), metadata);
		}
		// The session now holds its new expiry, so the entry can move to it, later too
		if (expiresAt != null) {
			this.redis.opsForZSet().add(this.expiriesKey, session.id(), toMicros(expiresAt));
		}
		else {
			this.redis.opsForZSet().remove(this.expiriesKey, session.id());
		}
		// Re-read: an update keeps the stored creation time, so the input is not what
		// was saved
		return Objects.requireNonNull(findById(session.id()), () -> "Session vanished after save: " + session.id());
	}

	@Override
	public boolean saveIfAbsent(Session session) {
		Assert.notNull(session, "session must not be null");
		String metadataJson = this.codec.toJson(session.metadata());
		// Index entries first, as in save. If this call loses the race, its entries
		// are extra: a read checks them against the winner's session and skips them
		addIndexEntries(session, this.codec.fromJsonMap(metadataJson));
		Instant expiresAt = session.expiresAt();
		Long created = this.redis.execute(SAVE_IF_ABSENT, List.of(sessionKey(session.id())),
				String.valueOf(toMicros(session.createdAt())),
				(expiresAt != null) ? String.valueOf(toMicros(expiresAt)) : "", metadataJson);
		return created != null && created == 1L;
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		List<String> values = hash().multiGet(sessionKey(sessionId), List.of(CREATED, EXPIRES, METADATA));
		String created = values.get(0);
		if (created == null) {
			return null;
		}
		String expires = values.get(1);
		String metadata = values.get(2);
		return Session.builder()
			.id(sessionId)
			.createdAt(fromMicros(created))
			.expiresAt((expires != null) ? fromMicros(expires) : null)
			.metadata((metadata != null) ? this.codec.fromJsonMap(metadata) : Map.of())
			.build();
	}

	@Override
	public List<Session> findByMetadata(String name, String value) {
		Assert.hasText(name, "name must not be null or empty");
		Assert.notNull(value, "value must not be null");
		if (!this.indexedAttributes.contains(name)) {
			return List.of();
		}
		Set<String> candidates = this.redis.opsForZSet().range(indexKey(name, value), 0, -1);
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		// The index is a hint; the session's own metadata decides
		List<Session> sessions = new ArrayList<>();
		for (String id : candidates) {
			Session session = findById(id);
			Object current = (session != null) ? session.metadata().get(name) : null;
			if (session != null && current != null && value.equals(indexValue(current))) {
				sessions.add(session);
			}
		}
		sessions.sort(Comparator.comparing(Session::createdAt).thenComparing(Session::id));
		return sessions;
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String key = sessionKey(sessionId);
		// No rule to check, so no script: if this call dies after the UNLINK, the index
		// entries it leaves behind are extra, and reads skip them
		String previous = hash().get(key, METADATA);
		this.redis.unlink(key);
		if (previous != null) {
			removeIndexEntries(sessionId, this.codec.fromJsonMap(previous));
		}
		this.redis.opsForZSet().remove(this.expiriesKey, sessionId);
	}

	@Override
	public int deleteExpiredSessions(Instant before) {
		Assert.notNull(before, "before must not be null");
		long cutOff = toMicros(before);
		Set<String> candidates = this.redis.opsForZSet()
			.rangeByScore(this.expiriesKey, Double.NEGATIVE_INFINITY, cutOff);
		if (candidates == null) {
			return 0;
		}
		int deleted = 0;
		for (String id : candidates) {
			String result = this.redis.execute(DELETE_IF_EXPIRED, List.of(sessionKey(id)), String.valueOf(cutOff));
			// No session (null) leaves the entry: a create writes it before the session
			if (result != null && !result.isEmpty()) {
				deleted++;
				removeIndexEntries(id, this.codec.fromJsonMap(result));
				this.redis.opsForZSet().remove(this.expiriesKey, id);
			}
		}
		return deleted;
	}

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		Long result = this.redis.execute(APPEND_EVENT, List.of(sessionKey(event.getSessionId())), event.getId(),
				encode(event), isBoundary(event) ? "1" : "0");
		if (result != null && result == -1L) {
			throw new IllegalArgumentException("Session not found: " + event.getSessionId());
		}
	}

	@Override
	public boolean appendEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notEmpty(events, "events must not be empty");
		Set<String> batchIds = new HashSet<>();
		int boundary = -1;
		List<String> args = new ArrayList<>(2 + events.size() * 2);
		args.add(String.valueOf(expectedVersion));
		args.add("");
		for (int i = 0; i < events.size(); i++) {
			SessionEvent event = events.get(i);
			Assert.isTrue(sessionId.equals(event.getSessionId()),
					() -> "Event '" + event.getId() + "' belongs to session '" + event.getSessionId() + "'");
			Assert.isTrue(batchIds.add(event.getId()),
					() -> "Event id '" + event.getId() + "' appears twice in the batch");
			if (isBoundary(event)) {
				boundary = i;
			}
			args.add(event.getId());
			args.add(encode(event));
		}
		args.set(1, String.valueOf(boundary));
		Long result = this.redis.execute(APPEND_EVENTS, List.of(sessionKey(sessionId)), args.toArray());
		if (result == null) {
			throw new IllegalStateException("No reply from Redis for session " + sessionId);
		}
		if (result == -1L) {
			throw new IllegalArgumentException("Session not found: " + sessionId);
		}
		if (result == -2L) {
			throw new IllegalArgumentException("An event id of the batch is already in the log of " + sessionId);
		}
		return result == 1L;
	}

	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String version = hash().get(sessionKey(sessionId), VERSION);
		return (version != null) ? Long.parseLong(version) : 0L;
	}

	/**
	 * Reads the events and hands them to {@link EventFilter#apply(List)}.
	 * <p>
	 * The log only grows and its positions have no gaps, so reading up to the
	 * {@code next} position seen first is a consistent snapshot, without a script, as
	 * long as the session isn't deleted during the read. The active view reads from the
	 * newest boundary on, plus the older events the boundary names; any other filter
	 * reads the whole log.
	 */
	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");
		String key = sessionKey(sessionId);
		List<String> header = hash().multiGet(key, List.of(NEXT, BOUNDARY));
		String nextValue = header.get(0);
		if (nextValue == null) {
			return List.of();
		}
		long next = Long.parseLong(nextValue);
		String boundaryValue = header.get(1);
		if (!filter.activeView() || boundaryValue == null) {
			return filter.apply(readRange(sessionId, 0, next));
		}
		long boundarySeq = Long.parseLong(boundaryValue);
		List<SessionEvent> fromBoundary = readRange(sessionId, boundarySeq, next);
		if (fromBoundary.isEmpty()) {
			return List.of();
		}
		CompactionBoundary boundary = CompactionBoundary.from(fromBoundary.get(0));
		List<SessionEvent> rows = new ArrayList<>(readNamed(sessionId, boundary, boundarySeq));
		rows.addAll(fromBoundary);
		return filter.apply(rows);
	}

	/** The events at positions {@code from} (inclusive) to {@code to} (exclusive). */
	private List<SessionEvent> readRange(String sessionId, long from, long to) {
		List<SessionEvent> events = new ArrayList<>();
		for (long start = from; start < to; start += READ_CHUNK) {
			List<String> fields = new ArrayList<>();
			for (long seq = start; seq < Math.min(to, start + READ_CHUNK); seq++) {
				fields.add("e:" + seq);
			}
			for (String value : hash().multiGet(sessionKey(sessionId), fields)) {
				if (value != null) {
					events.add(decode(sessionId, value));
				}
			}
		}
		return events;
	}

	/** The values of the given fields, read {@code READ_CHUNK} fields at a time. */
	private List<String> readFields(String sessionId, List<String> fields) {
		List<String> values = new ArrayList<>(fields.size());
		for (int start = 0; start < fields.size(); start += READ_CHUNK) {
			values.addAll(hash().multiGet(sessionKey(sessionId),
					fields.subList(start, Math.min(fields.size(), start + READ_CHUNK))));
		}
		return values;
	}

	/**
	 * The events older than the boundary that it names, in log order. An id the log
	 * doesn't hold is skipped.
	 */
	private List<SessionEvent> readNamed(String sessionId, CompactionBoundary boundary, long boundarySeq) {
		List<String> ids = new ArrayList<>(boundary.pinnedEventIds());
		ids.addAll(boundary.summaryEventIds());
		ids.addAll(boundary.preservedEventIds());
		if (ids.isEmpty()) {
			return List.of();
		}
		List<String> positions = readFields(sessionId, ids.stream().map(id -> "i:" + id).toList());
		List<Long> seqs = positions.stream()
			.filter(Objects::nonNull)
			.map(Long::parseLong)
			.filter(seq -> seq < boundarySeq)
			.distinct()
			.sorted()
			.toList();
		if (seqs.isEmpty()) {
			return List.of();
		}
		List<SessionEvent> events = new ArrayList<>();
		for (String value : readFields(sessionId, seqs.stream().map(seq -> "e:" + seq).toList())) {
			if (value != null) {
				events.add(decode(sessionId, value));
			}
		}
		return events;
	}

	/**
	 * Adds the index and expiry entries of a session. The metadata is the session's as it
	 * reads back from JSON, so the index holds the same text a lookup compares against.
	 */
	private void addIndexEntries(Session session, Map<String, Object> metadata) {
		for (String name : this.indexedAttributes) {
			Object value = metadata.get(name);
			if (value != null) {
				this.redis.opsForZSet()
					.add(indexKey(name, indexValue(value)), session.id(), toMicros(session.createdAt()));
			}
		}
		Instant expiresAt = session.expiresAt();
		if (expiresAt != null) {
			// LT only ever lowers the score: until the session itself changes, its
			// current expiry must stay where the sweep can find it
			double score = toMicros(expiresAt);
			byte[] key = this.expiriesKey.getBytes(StandardCharsets.UTF_8);
			byte[] member = session.id().getBytes(StandardCharsets.UTF_8);
			this.redis.execute((RedisCallback<Boolean>) connection -> connection.zSetCommands()
				.zAdd(key, score, member, ZAddArgs.empty().lt()));
		}
	}

	private void removeStaleIndexEntries(String sessionId, Map<String, Object> previous, Map<String, Object> current) {
		for (String name : this.indexedAttributes) {
			Object old = previous.get(name);
			Object value = current.get(name);
			if (old != null && (value == null || !indexValue(old).equals(indexValue(value)))) {
				this.redis.opsForZSet().remove(indexKey(name, indexValue(old)), sessionId);
			}
		}
	}

	private void removeIndexEntries(String sessionId, Map<String, Object> metadata) {
		for (String name : this.indexedAttributes) {
			Object value = metadata.get(name);
			if (value != null) {
				this.redis.opsForZSet().remove(indexKey(name, indexValue(value)), sessionId);
			}
		}
	}

	/**
	 * The index key of one metadata value. The name and value are hashed, so a long value
	 * makes a short key, and the length prefix keeps {@code ("a:b", "c")} and
	 * {@code ("a", "b:c")} apart.
	 */
	private String indexKey(String name, String value) {
		return this.keyPrefix + "idx:" + sha256(name.length() + ":" + name + value);
	}

	private static String indexValue(@Nullable Object value) {
		return String.valueOf(value);
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private static boolean isBoundary(SessionEvent event) {
		return EventType.COMPACTION.equals(event.getType());
	}

	/** One event as one JSON string: the codec's four fields plus id, time, metadata. */
	private String encode(SessionEvent event) {
		EncodedEvent encoded = this.codec.encode(event);
		Map<String, @Nullable Object> fields = new LinkedHashMap<>();
		fields.put("id", event.getId());
		fields.put("timestamp", event.getTimestamp().toString());
		fields.put("type", encoded.type().name());
		MessageType messageType = encoded.messageType();
		fields.put("messageType", (messageType != null) ? messageType.name() : null);
		fields.put("text", encoded.text());
		fields.put("payload", encoded.payload());
		fields.put("metadata", event.getMetadata());
		return this.codec.toJson(fields);
	}

	@SuppressWarnings("unchecked")
	private SessionEvent decode(String sessionId, String json) {
		Map<String, Object> fields = this.codec.fromJsonMap(json);
		Object messageType = fields.get("messageType");
		Object text = fields.get("text");
		Object metadata = fields.get("metadata");
		EncodedEvent encoded = new EncodedEvent(EventType.of(String.valueOf(fields.get("type"))),
				(messageType != null) ? MessageType.valueOf(messageType.toString()) : null,
				(text != null) ? text.toString() : null, String.valueOf(fields.getOrDefault("payload", "")));
		return this.codec.decode(encoded)
			.id(String.valueOf(fields.get("id")))
			.sessionId(sessionId)
			.timestamp(Instant.parse(String.valueOf(fields.get("timestamp"))))
			.metadata((metadata instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of())
			.build();
	}

	// "s:" keeps session keys apart from the index and expiry keys, whatever the id
	private String sessionKey(String sessionId) {
		return this.keyPrefix + "s:" + sessionId;
	}

	private HashOperations<String, String, String> hash() {
		return this.redis.opsForHash();
	}

	private static long toMicros(Instant instant) {
		return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
	}

	private static Instant fromMicros(String micros) {
		return Instant.EPOCH.plus(Long.parseLong(micros), ChronoUnit.MICROS);
	}

	private static <T> RedisScript<T> script(String name, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(name, RedisSessionRepository.class));
		script.setResultType(resultType);
		return script;
	}

	/**
	 * Builds a {@link RedisSessionRepository} on a
	 * {@link #connectionFactory(RedisConnectionFactory) connection factory}; everything
	 * else has a default.
	 */
	public static final class Builder {

		private @Nullable RedisConnectionFactory connectionFactory;

		private Set<String> indexedAttributes = DEFAULT_INDEXED_ATTRIBUTES;

		private String keyPrefix = DEFAULT_KEY_PREFIX;

		private Builder() {
		}

		/** The connection to Redis: standalone, Sentinel or cluster, Lettuce or Jedis. */
		public Builder connectionFactory(RedisConnectionFactory connectionFactory) {
			Assert.notNull(connectionFactory, "connectionFactory must not be null");
			this.connectionFactory = connectionFactory;
			return this;
		}

		/**
		 * The metadata keys {@link RedisSessionRepository#findByMetadata} answers for.
		 * The principal name is always included, and a lookup on any other key finds
		 * nothing.
		 */
		public Builder indexedAttributes(Set<String> indexedAttributes) {
			Assert.notNull(indexedAttributes, "indexedAttributes must not be null");
			Set<String> keys = new HashSet<>(indexedAttributes);
			keys.addAll(DEFAULT_INDEXED_ATTRIBUTES);
			this.indexedAttributes = keys;
			return this;
		}

		/**
		 * The prefix of every key this repository writes;
		 * {@value RedisSessionRepository#DEFAULT_KEY_PREFIX} by default. Two repositories
		 * on one Redis need different prefixes.
		 */
		public Builder keyPrefix(String keyPrefix) {
			Assert.hasText(keyPrefix, "keyPrefix must not be empty");
			this.keyPrefix = keyPrefix;
			return this;
		}

		/** Builds the repository. */
		public RedisSessionRepository build() {
			Assert.notNull(this.connectionFactory, "connectionFactory must be set");
			assertReadsFromPrimary(this.connectionFactory);
			StringRedisTemplate redis = new StringRedisTemplate(this.connectionFactory);
			return new RedisSessionRepository(redis, this.indexedAttributes, this.keyPrefix);
		}

		/**
		 * Rejects a Lettuce connection factory that reads from replicas. A replica lags
		 * behind the primary, so a read could miss a write this repository just made.
		 */
		private static void assertReadsFromPrimary(RedisConnectionFactory connectionFactory) {
			if (!ClassUtils.isPresent("io.lettuce.core.ReadFrom", Builder.class.getClassLoader())
					|| !(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
				return;
			}
			lettuce.getClientConfiguration()
				.getReadFrom()
				.ifPresent(readFrom -> Assert.isTrue(readFrom == ReadFrom.UPSTREAM || readFrom == ReadFrom.MASTER,
						"The connection factory must read from the primary, not from replicas"));
		}

	}

}
