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

package org.springaicommunity.session.turn;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;

/**
 * A repository for the turn service tests that counts the version-checked appends and can
 * run an action right after the version is read, the moment another writer would slip in.
 *
 * @author Christian Tzolov
 */
class ContendedRepository implements SessionRepository {

	final AtomicInteger casWrites = new AtomicInteger();

	private final SessionRepository store;

	private @Nullable Runnable afterVersionRead;

	private int remaining;

	ContendedRepository(SessionRepository store) {
		this.store = store;
	}

	/** Runs the action after each of the next {@code times} version reads. */
	void onVersionRead(int times, Runnable action) {
		this.afterVersionRead = action;
		this.remaining = times;
	}

	@Override
	public long getEventVersion(String sessionId) {
		long version = this.store.getEventVersion(sessionId);
		Runnable action = this.afterVersionRead;
		if (action != null && this.remaining > 0) {
			this.remaining--;
			action.run();
		}
		return version;
	}

	@Override
	public boolean appendEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		this.casWrites.incrementAndGet();
		return this.store.appendEvents(sessionId, events, expectedVersion);
	}

	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		return this.store.findEvents(sessionId, filter);
	}

	@Override
	public Session save(Session session) {
		return this.store.save(session);
	}

	@Override
	public boolean saveIfAbsent(Session session) {
		return this.store.saveIfAbsent(session);
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		return this.store.findById(sessionId);
	}

	@Override
	public List<Session> findByMetadata(String name, String value) {
		return this.store.findByMetadata(name, value);
	}

	@Override
	public void delete(String sessionId) {
		this.store.delete(sessionId);
	}

	@Override
	public void appendEvent(SessionEvent event) {
		this.store.appendEvent(event);
	}

	@Override
	public int deleteExpiredSessions(Instant before) {
		return this.store.deleteExpiredSessions(before);
	}

}
