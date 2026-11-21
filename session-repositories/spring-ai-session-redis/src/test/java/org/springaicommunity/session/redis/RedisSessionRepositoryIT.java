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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.test.session.AbstractSessionRepositoryContractTests;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the {@link SessionRepository} contract against {@link RedisSessionRepository} on a
 * Redis container, plus the parts that are particular to Redis: the key layout and the
 * index entries that a crash can leave behind.
 *
 * @author Soby Chacko
 */
class RedisSessionRepositoryIT extends AbstractSessionRepositoryContractTests {

	private final StringRedisTemplate redis = new StringRedisTemplate(RedisSessionTestSupport.connectionFactory());

	@Override
	protected SessionRepository createRepository() {
		return RedisSessionRepository.builder().connectionFactory(RedisSessionTestSupport.connectionFactory()).build();
	}

	@Test
	void sessionAndItsLogAreOneHash() {
		Session session = newSession("user-layout");
		append(session.id(), new UserMessage("hi"));

		String key = RedisSessionRepository.DEFAULT_KEY_PREFIX + "s:" + session.id();
		Map<Object, Object> fields = this.redis.opsForHash().entries(key);

		assertThat(fields).containsKeys("created", "metadata", "version", "next", "e:0");
		assertThat(fields.keySet()).anyMatch(field -> field.toString().startsWith("i:"));
		assertThat(fields).containsEntry("version", "1").containsEntry("next", "1");

		this.repository.delete(session.id());
		assertThat(this.redis.hasKey(key)).isFalse();
	}

	@Test
	void staleIndexEntryIsIgnored() {
		// A save that changed the metadata and died before removing the old index
		// entry: the entry still names the session under the old value
		String old = "user-old-" + UUID.randomUUID();
		Session session = newSession(old);
		this.redis.opsForHash()
			.put(RedisSessionRepository.DEFAULT_KEY_PREFIX + "s:" + session.id(), "metadata",
					"{\"" + SessionAttributes.PRINCIPAL_NAME + "\":\"user-new\"}");

		assertThat(this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, old)).isEmpty();
	}

	@Test
	void staleIndexEntryForRemovedAttributeIsIgnored() {
		// A save that removed the principal and died before removing its index entry,
		// where the old value was the text "null"
		Session session = newSession("null");
		this.redis.opsForHash().put(RedisSessionRepository.DEFAULT_KEY_PREFIX + "s:" + session.id(), "metadata", "{}");

		assertThat(this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "null")).extracting(Session::id)
			.doesNotContain(session.id());
	}

	@Test
	void removingAttributeWithTextNullRemovesItsIndexEntry() {
		Session session = newSession("null");
		this.repository.save(Session.builder().id(session.id()).build());
		this.repository.delete(session.id());

		Set<String> indexKeys = this.redis.keys(RedisSessionRepository.DEFAULT_KEY_PREFIX + "idx:*");
		assertThat(indexKeys).allSatisfy(key -> assertThat(this.redis.opsForZSet().score(key, session.id())).isNull());
	}

	@Test
	void sweepKeepsExpiryEntryOfSessionThatIsNotThere() {
		// A create writes its expiry entry before the session: a missing session may
		// be one that is being created right now, so its entry must stay
		String id = UUID.randomUUID().toString();
		String expiries = RedisSessionRepository.DEFAULT_KEY_PREFIX + "expiries";
		this.redis.opsForZSet().add(expiries, id, 1);

		this.repository.deleteExpiredSessions(Instant.now());

		assertThat(this.redis.opsForZSet().score(expiries, id)).isNotNull();
	}

	@Test
	void sessionIdThatNamesAnInternalKeyIsStoredLikeAnyOther() {
		this.repository
			.save(Session.builder().id(UUID.randomUUID().toString()).expiresAt(Instant.now().plusSeconds(60)).build());

		this.repository.save(Session.builder().id("expiries").expiresAt(Instant.now().plusSeconds(60)).build());

		assertThat(this.repository.findById("expiries")).isNotNull();
		this.repository.delete("expiries");
	}

	@Test
	void writesKeepWorkingAfterTheServerForgetsTheScripts() {
		// A failover or a restart leaves the server without the scripts loaded so far
		Session session = newSession("user-noscript");
		append(session.id(), new UserMessage("before"));

		this.redis.execute((RedisCallback<Void>) connection -> {
			connection.scriptingCommands().scriptFlush();
			return null;
		});
		append(session.id(), new UserMessage("after"));

		assertThat(texts(session.id(), EventFilter.all())).containsExactly("before", "after");
	}

	@Test
	void deletesStillWorkWhenRedisIsOutOfMemory() {
		Session expired = this.repository
			.save(Session.builder().id(UUID.randomUUID().toString()).expiresAt(Instant.now().minusSeconds(60)).build());
		Session live = newSession("user-oom");

		// With noeviction, a server over its memory limit refuses writes; deleting is
		// how memory is freed, so it must still run
		try {
			setMaxMemory("1");
			this.repository.delete(live.id());
			this.repository.deleteExpiredSessions(Instant.now());
		}
		finally {
			setMaxMemory("0");
		}

		assertThat(this.repository.findById(live.id())).isNull();
		assertThat(this.repository.findById(expired.id())).isNull();
	}

	@Test
	void losingCreateWithLaterExpiryLeavesExpiredSessionForTheSweep() {
		String id = UUID.randomUUID().toString();
		this.repository.save(Session.builder().id(id).expiresAt(Instant.now().minusSeconds(60)).build());

		// The losing create writes its expiry entry before it finds the session exists
		boolean created = this.repository
			.saveIfAbsent(Session.builder().id(id).expiresAt(Instant.now().plusSeconds(3600)).build());

		assertThat(created).isFalse();
		assertThat(this.repository.deleteExpiredSessions(Instant.now())).isEqualTo(1);
	}

	@Test
	void sameEventIdInTwoSessionsIsStoredInBoth() {
		Session first = newSession("user-ids-a");
		Session second = newSession("user-ids-b");
		String eventId = UUID.randomUUID().toString();

		this.repository.appendEvent(
				SessionEvent.builder().id(eventId).sessionId(first.id()).message(new UserMessage("a")).build());
		this.repository.appendEvent(
				SessionEvent.builder().id(eventId).sessionId(second.id()).message(new UserMessage("b")).build());

		assertThat(this.repository.findEvents(second.id(), EventFilter.all())).extracting(SessionEvent::getId)
			.containsExactly(eventId);
	}

	@Test
	void lookupOnAttributeThatIsNotIndexedFindsNothing() {
		this.repository.save(Session.builder().id(UUID.randomUUID().toString()).metadata("agent", "support").build());

		assertThat(this.repository.findByMetadata("agent", "support")).isEmpty();
	}

	@Test
	void extraIndexedAttributeIsFoundAndThePrincipalStillIs() {
		RedisSessionRepository repository = RedisSessionRepository.builder()
			.connectionFactory(RedisSessionTestSupport.connectionFactory())
			.indexedAttributes(Set.of("agent"))
			.build();
		String agent = "agent-" + UUID.randomUUID();
		String user = "user-" + UUID.randomUUID();
		Session session = Session.builder()
			.id(UUID.randomUUID().toString())
			.metadata("agent", agent)
			.metadata(SessionAttributes.PRINCIPAL_NAME, user)
			.build();
		repository.save(session);

		assertThat(repository.findByMetadata("agent", agent)).extracting(Session::id).containsExactly(session.id());
		assertThat(repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, user)).extracting(Session::id)
			.containsExactly(session.id());
	}

	@Test
	void numericAttributeIsFoundByTheValueItReadsBackAs() {
		RedisSessionRepository repository = RedisSessionRepository.builder()
			.connectionFactory(RedisSessionTestSupport.connectionFactory())
			.indexedAttributes(Set.of("tier"))
			.build();
		Session session = repository
			.save(Session.builder().id(UUID.randomUUID().toString()).metadata("tier", new BigDecimal("1.00")).build());

		String stored = String.valueOf(session.metadata().get("tier"));

		assertThat(repository.findByMetadata("tier", stored)).extracting(Session::id).contains(session.id());
	}

	@Test
	void builderNeedsConnectionFactory() {
		assertThatThrownBy(() -> RedisSessionRepository.builder().build()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void builderRejectsConnectionFactoryThatReadsFromReplicas() {
		LettuceConnectionFactory replicaReads = new LettuceConnectionFactory(new RedisStandaloneConfiguration(),
				LettuceClientConfiguration.builder().readFrom(ReadFrom.REPLICA_PREFERRED).build());

		assertThatThrownBy(() -> RedisSessionRepository.builder().connectionFactory(replicaReads).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void setMaxMemory(String bytes) {
		this.redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().setConfig("maxmemory", bytes);
			return null;
		});
	}

}
