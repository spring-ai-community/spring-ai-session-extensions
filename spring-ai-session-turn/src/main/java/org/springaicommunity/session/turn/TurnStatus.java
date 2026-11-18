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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.SessionEvent;
import org.springframework.util.Assert;

/**
 * The turn status of a session: whether a turn runs on it, under which run id, and why
 * the last one ended. It is read from the session's log, not stored on the session: the
 * newest {@link TurnEventTypes#STATUS} event, typed, or {@link #idle()} for a session
 * that has none. {@link TurnService#status} returns it; {@link TurnService#start},
 * {@link TurnService#end}, {@link TurnService#interrupt}, {@link TurnService#park} and
 * {@link TurnService#resume} change it.
 *
 * @param status whether a turn runs
 * @param runId the run id of the turn that runs, or of the last one that ended;
 * {@code null} when no turn ran yet
 * @param stopReason why the last turn ended; {@code null} while a turn runs, and when no
 * turn ran yet
 * @param since when the status was recorded, the timestamp of its event; {@code null}
 * when no turn ran yet
 * @param eventId the id of the status event; {@code null} when no turn ran yet
 * @author Christian Tzolov
 * @since 0.1.0
 */
public record TurnStatus(SessionStatus status, @Nullable String runId, @Nullable StopReason stopReason,
		@Nullable Instant since, @Nullable String eventId) {

	private static final TurnStatus NO_TURN = new TurnStatus(SessionStatus.IDLE, null, null, null, null);

	public TurnStatus {
		Assert.notNull(status, "status must not be null");
		if (status == SessionStatus.RUNNING) {
			Assert.hasText(runId, "a running turn must have a run id");
			Assert.isNull(stopReason, "a running turn has no stop reason");
		}
	}

	/**
	 * Returns the status of a session on which no turn ran yet: idle, without an event.
	 */
	public static TurnStatus idle() {
		return NO_TURN;
	}

	/**
	 * Reads the status a {@link TurnEventTypes#STATUS} event records.
	 * @throws IllegalArgumentException if the event is not a status event
	 */
	public static TurnStatus from(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		return new TurnStatus(SessionStatusEvents.status(event), SessionStatusEvents.runId(event),
				SessionStatusEvents.stopReason(event), event.getTimestamp(), event.getId());
	}

	/** Returns whether a turn runs on the session. */
	public boolean isRunning() {
		return this.status == SessionStatus.RUNNING;
	}

	/**
	 * Returns the run id of the turn that runs on the session, or {@code null} while no
	 * turn runs.
	 */
	public @Nullable String runningRunId() {
		return isRunning() ? this.runId : null;
	}

	/** Returns whether a turn runs on the session under the given run id. */
	public boolean isRunning(String runId) {
		return isRunning() && runId.equals(this.runId);
	}

	/**
	 * Returns whether the given turn still runs on the session: this status is the one
	 * {@link TurnService#start} or {@link TurnService#resume} returned for it. A turn is
	 * identified by the status event that started it, so a later turn under the same run
	 * id is another turn.
	 * @param turn the status that {@code start} or {@code resume} returned
	 */
	public boolean stillRuns(TurnStatus turn) {
		Assert.notNull(turn, "turn must not be null");
		return isRunning() && this.eventId != null && this.eventId.equals(turn.eventId());
	}

	/**
	 * Returns whether the session is parked on a question: idle with
	 * {@link StopReason#REQUIRES_ACTION}. The status says no more than that;
	 * {@link TurnService#pendingQuestion} returns the question, which says what it is
	 * about, and {@link TurnService#resume} answers it.
	 */
	public boolean isParked() {
		return this.status == SessionStatus.IDLE && this.stopReason == StopReason.REQUIRES_ACTION;
	}

}
