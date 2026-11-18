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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The turns of a session: starts, ends, interrupts, parks and resumes them, and reads
 * their status. Every harness runs its turns through the same methods, so a turn started
 * on one instance can be interrupted from another, and two harnesses never run one
 * session at once.
 * <p>
 * The service keeps no state of its own, so one instance may be kept or a new one created
 * for every call. The status is a projection of the session's log, the newest
 * {@link TurnEventTypes#STATUS} event, and every transition is a version-checked append
 * through the {@link SessionService}: the version and the newest status are read, the
 * events are written against that version, and a write another append overtook is
 * re-evaluated after a fresh read, five times in all, and then given up with an
 * {@link IllegalStateException}. A decorated service, an authorizing one for example,
 * guards these reads and writes like any other. <pre>{@code
 * TurnService turns = new TurnService(sessionService);
 * TurnStatus turn = turns.start(sessionId, runId);
 * // ... run the turn
 * turns.end(sessionId, turn, StopReason.END_TURN);
 * }</pre>
 * <p>
 * A turn is identified by the status that {@link #start} or {@link #resume} returned for
 * it, not by its run id: {@link #end} and {@link #park} take that status. A run id may
 * repeat, a run may hold successive turns of a session, so only the status tells one turn
 * from a later one under the same run id.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 * @see TurnStatus
 */
public final class TurnService {

	private static final int ATTEMPTS = 5;

	private final SessionService sessionService;

	/**
	 * Creates the turn service on the session service, whose log holds the status.
	 * @param sessionService the session service the turns run on
	 */
	public TurnService(SessionService sessionService) {
		Assert.notNull(sessionService, "sessionService must not be null");
		this.sessionService = sessionService;
	}

	/**
	 * Returns the turn status of the session: whether a turn runs on it, under which run
	 * id, and why the last one ended. A session without a {@link TurnEventTypes#STATUS}
	 * event, because no harness has run a turn on it yet or because it does not exist, is
	 * {@link TurnStatus#idle()}. One indexed read.
	 */
	public TurnStatus status(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		List<SessionEvent> newest = this.sessionService.getEvents(sessionId, SessionStatusEvents.NEWEST_STATUS);
		return newest.isEmpty() ? TurnStatus.idle() : TurnStatus.from(newest.get(newest.size() - 1));
	}

	/**
	 * Starts a turn on the session and returns the new status, {@code RUNNING} under the
	 * run id. The write is version-checked, so when two harnesses start a turn at the
	 * same time exactly one of them succeeds.
	 * <p>
	 * A session on which a turn already runs is busy. The call throws
	 * {@link TurnInProgressException} with the run id of that turn; nothing is written
	 * and nothing is queued. The caller decides what a busy session means for it: fail,
	 * retry later, or {@link #interrupt} the running turn and start again. <pre>{@code
	 * try {
	 *     TurnStatus turn = turns.start(sessionId, runId);
	 * }
	 * catch (TurnInProgressException ex) {
	 *     // a turn runs under ex.getRunId()
	 * }
	 * }</pre>
	 * <p>
	 * The turn runs until {@link #end} or {@link #interrupt} ends it, however long that
	 * takes. A harness that dies in between leaves the session busy until someone
	 * interrupts it.
	 * <p>
	 * Starting a turn is optional. A harness that never calls this method leaves the
	 * session idle and appends events freely: an append never requires a running turn.
	 * @param sessionId the session
	 * @param runId the harness's run, recorded with the turn. The session does not
	 * interpret it: one run may hold a turn in each of several sessions, and successive
	 * turns in one. When {@code null} or blank a random UUID is generated; the returned
	 * status carries it.
	 * @return the new status, {@code RUNNING} under the effective run id
	 * @throws TurnInProgressException if a turn runs on the session
	 * @throws TurnParkedException if the session is parked on a question; {@link #resume}
	 * answers it and starts the turn
	 * @throws IllegalStateException if the write kept losing to other appends; the
	 * session is left as it was and the call can be retried
	 * @throws IllegalArgumentException if the session does not exist
	 */
	public TurnStatus start(String sessionId, @Nullable String runId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String requestRunId = StringUtils.hasText(runId) ? runId : UUID.randomUUID().toString();
		return writeStatus(sessionId, (version, current) -> {
			String runningRunId = current.runningRunId();
			if (runningRunId != null) {
				throw new TurnInProgressException(sessionId, runningRunId);
			}
			if (current.isParked()) {
				// A new turn would leave the question open: the caller resumes instead
				throw new TurnParkedException(sessionId);
			}
			SessionEvent running = SessionStatusEvents.running(sessionId, requestRunId);
			return this.sessionService.appendEvents(sessionId, List.of(running), version) ? TurnStatus.from(running)
					: null;
		}, () -> "A turn could not be started on session '" + sessionId + "': the log kept changing");
	}

	/**
	 * Ends the turn with the given reason and returns the status after the call.
	 * <p>
	 * Only the holder of the turn can end it. When the turn no longer runs, nothing is
	 * written and the status as it stands is returned: another writer ended the turn
	 * first, an {@link #interrupt} for example, and that end stands. This holds also when
	 * a later turn runs under the same run id: a late end never ends a later turn. A
	 * harness therefore calls this method unconditionally when its turn is over and reads
	 * the result to learn whether its own end was recorded: <pre>{@code
	 * TurnStatus ended = turns.end(sessionId, turn, StopReason.END_TURN);
	 * if (ended.stopReason() != StopReason.END_TURN) {
	 *     // another writer ended the turn first; ended says how it stands now
	 * }
	 * }</pre>
	 * @param sessionId the session
	 * @param turn the turn: the status that {@link #start} or {@link #resume} returned
	 * @param stopReason why the turn ends: {@link StopReason#END_TURN} when the model
	 * answered, {@link StopReason#ERROR} when the call failed
	 * @return the status after the call: {@code IDLE} with this reason when this call
	 * ended the turn, otherwise the status another writer left
	 * @throws IllegalStateException if the write kept losing to other appends; the turn
	 * still runs, so the harness retries the call or leaves the turn to an
	 * {@link #interrupt}
	 */
	public TurnStatus end(String sessionId, TurnStatus turn, StopReason stopReason) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String runId = runIdOf(turn);
		Assert.notNull(stopReason, "stopReason must not be null");
		return writeStatus(sessionId, (version, current) -> {
			if (!current.stillRuns(turn)) {
				return current;
			}
			SessionEvent idle = SessionStatusEvents.idle(sessionId, runId, stopReason);
			return this.sessionService.appendEvents(sessionId, List.of(idle), version) ? TurnStatus.from(idle) : null;
		}, () -> "The turn of session '" + sessionId + "' under run '" + runId
				+ "' could not be ended: the log kept changing");
	}

	/**
	 * Asks the turn that runs on the session to stop, and ends it. Appends an
	 * {@link TurnEventTypes#INTERRUPT} event that records the request and, when a turn
	 * runs, a {@link TurnEventTypes#STATUS} event that ends it with
	 * {@link StopReason#INTERRUPTED}, both in one append so that the request and the
	 * transition land together. When no turn runs only the request is recorded.
	 * <p>
	 * Callable from anywhere: a UI's stop button, another instance, a scheduler. The
	 * status moves to {@code IDLE} at once, so the next {@link #start} on the session
	 * succeeds. This is also how a session is freed whose harness died during a turn. The
	 * harness that runs the turn does not stop by itself, though: it learns of the
	 * interrupt when it reads the status inside its loop, and its own {@link #end} is
	 * then a no-op, because the interrupt stands.
	 * <p>
	 * A parked session has no turn to stop. The request is recorded and the pause stays
	 * as it is: the question remains open, and the session resumes with an answer, not
	 * with a start. A harness that wants the question closed resumes it with an answer
	 * that says the person moved on.
	 * @param sessionId the session
	 * @param reason why the turn is to stop, recorded with the request; may be
	 * {@code null}
	 * @return the status after the call: {@code IDLE} with {@link StopReason#INTERRUPTED}
	 * when a turn was ended, otherwise the status as it stands
	 * @throws IllegalStateException if the write kept losing to other appends; nothing
	 * was written and the call can be retried
	 * @throws IllegalArgumentException if the session does not exist
	 */
	public TurnStatus interrupt(String sessionId, @Nullable String reason) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		return writeStatus(sessionId, (version, current) -> {
			List<SessionEvent> events = new ArrayList<>(2);
			events.add(SessionStatusEvents.interrupt(sessionId, reason));
			SessionEvent idle = null;
			String runningRunId = current.runningRunId();
			if (runningRunId != null) {
				idle = SessionStatusEvents.idle(sessionId, runningRunId, StopReason.INTERRUPTED);
				events.add(idle);
			}
			if (!this.sessionService.appendEvents(sessionId, events, version)) {
				return null;
			}
			return (idle != null) ? TurnStatus.from(idle) : current;
		}, () -> "Session '" + sessionId + "' could not be interrupted: the log kept changing");
	}

	/**
	 * Parks the turn on a question and returns the new status, {@code IDLE} with
	 * {@link StopReason#REQUIRES_ACTION}. The turn stops to wait for an answer from
	 * outside: a person's decision on a tool call, for example. Two events land in one
	 * append: a {@link TurnEventTypes#QUESTION} event with the question, and the status
	 * event that ends the turn. The status says only that the session is parked; the
	 * question says what it is about, the reply with the tool calls for example, in its
	 * payload. A parked session holds no turn while the answer takes its time;
	 * {@link #resume} starts the turn again.
	 * <p>
	 * Keyed by the turn, like {@link #end}: when the turn no longer runs, nothing is
	 * written and the status as it stands is returned, and {@link TurnStatus#isParked()}
	 * tells whether the question was recorded.
	 * @param sessionId the session
	 * @param turn the turn: the status that {@link #start} or {@link #resume} returned
	 * @param question what the turn waits on
	 * @return the status after the call: parked when this call parked the turn, otherwise
	 * the status another writer left
	 * @throws IllegalStateException if the write kept losing to other appends; the turn
	 * still runs
	 */
	public TurnStatus park(String sessionId, TurnStatus turn, Question question) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String runId = runIdOf(turn);
		Assert.notNull(question, "question must not be null");
		return writeStatus(sessionId, (version, current) -> {
			if (!current.stillRuns(turn)) {
				return current;
			}
			SessionEvent asked = QuestionEvents.ask(sessionId, question);
			SessionEvent idle = SessionStatusEvents.idle(sessionId, runId, StopReason.REQUIRES_ACTION);
			return this.sessionService.appendEvents(sessionId, List.of(asked, idle), version) ? TurnStatus.from(idle)
					: null;
		}, () -> "The turn of session '" + sessionId + "' under run '" + runId
				+ "' could not be parked: the log kept changing");
	}

	/**
	 * Returns the question the session waits on: the newest
	 * {@link TurnEventTypes#QUESTION} event when no {@link TurnEventTypes#ANSWER} event
	 * follows it, or {@code null} when every question has its answer. Read it with
	 * {@link Question#from}. A parked session ({@link TurnStatus#isParked()}) has one,
	 * and {@link #resume} answers it. One indexed read.
	 */
	public @Nullable SessionEvent pendingQuestion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		return QuestionEvents.pending(this.sessionService.getEvents(sessionId, QuestionEvents.QUESTIONS_AND_ANSWERS));
	}

	/**
	 * Resumes a parked session with the answer to its question and returns the new
	 * status, {@code RUNNING} under the run id. Two events land in one append: an
	 * {@link TurnEventTypes#ANSWER} event that points at the pending question and carries
	 * the answer, and the status event that starts the turn again. The run id follows the
	 * rule of {@link #start}: the caller's, or a fresh one. A run spans the pause when it
	 * passes the id it parked under.
	 * <p>
	 * Only a parked session resumes. A session that runs a turn, because another resume
	 * landed first, is busy: {@link TurnInProgressException}, since the question already
	 * has its answer and there is nothing left to resume. A session that is idle for
	 * another reason, or parked without an open question, cannot resume:
	 * {@link IllegalStateException}. Nothing is written in either case, so the same
	 * answer sent twice resumes once, and the tools it allows run once.
	 * @param sessionId the session
	 * @param runId the run the resumed turn runs under; {@code null} for a fresh one
	 * @param answer the answer, stored as JSON; {@code null} for an acknowledgement
	 * @return the status after the call: {@code RUNNING} under the effective run id
	 * @throws TurnInProgressException if a turn runs on the session
	 * @throws IllegalStateException if the session is not parked or has no open question,
	 * or if the write kept losing to other appends
	 */
	public TurnStatus resume(String sessionId, @Nullable String runId, @Nullable Object answer) {
		return resume(sessionId, runId, null, answer);
	}

	/**
	 * Resumes a parked session with the answer to the question the caller read, and
	 * returns the new status. Works as {@link #resume(String, String, Object)}, and
	 * refuses when the pending question is no longer the one the caller read: another
	 * caller answered it, and the turn parked again on a new question. The check runs
	 * inside the version-checked write, so an answer meant for one question never answers
	 * another.
	 * @param sessionId the session
	 * @param runId the run the resumed turn runs under; {@code null} for a fresh one
	 * @param questionEventId the event id of the question the answer is for; {@code null}
	 * for whatever question is pending
	 * @param answer the answer, stored as JSON; {@code null} for an acknowledgement
	 * @return the status after the call: {@code RUNNING} under the effective run id
	 * @throws TurnInProgressException if a turn runs on the session
	 * @throws IllegalStateException if the session is not parked, has no open question,
	 * or its open question is not the given one, or if the write kept losing to other
	 * appends
	 */
	public TurnStatus resume(String sessionId, @Nullable String runId, @Nullable String questionEventId,
			@Nullable Object answer) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String requestRunId = StringUtils.hasText(runId) ? runId : UUID.randomUUID().toString();
		return writeStatus(sessionId, (version, current) -> {
			String runningRunId = current.runningRunId();
			if (runningRunId != null) {
				throw new TurnInProgressException(sessionId, runningRunId);
			}
			if (!current.isParked()) {
				throw new IllegalStateException("Session '" + sessionId + "' is not parked on a question; it is "
						+ current.status() + ((current.stopReason() != null) ? " with " + current.stopReason() : ""));
			}
			SessionEvent pending = pendingQuestion(sessionId);
			if (pending == null) {
				throw new IllegalStateException(
						"Session '" + sessionId + "' is parked but has no open question to answer");
			}
			if (questionEventId != null && !questionEventId.equals(pending.getId())) {
				throw new IllegalStateException("The question '" + questionEventId + "' of session '" + sessionId
						+ "' already has an answer; the open question is '" + pending.getId() + "'");
			}
			SessionEvent answered = QuestionEvents.answer(sessionId, pending.getId(), answer);
			SessionEvent running = SessionStatusEvents.running(sessionId, requestRunId);
			return this.sessionService.appendEvents(sessionId, List.of(answered, running), version)
					? TurnStatus.from(running) : null;
		}, () -> "Session '" + sessionId + "' could not be resumed: the log kept changing");
	}

	/**
	 * The run id of a turn handed to {@link #end} or {@link #park}.
	 * @throws IllegalArgumentException if the status is not one that {@link #start} or
	 * {@link #resume} returned
	 */
	private static String runIdOf(TurnStatus turn) {
		Assert.notNull(turn, "turn must not be null");
		String runId = turn.runId();
		Assert.isTrue(turn.isRunning() && turn.eventId() != null && runId != null,
				"turn must be the RUNNING status that start or resume returned");
		return runId;
	}

	/**
	 * Runs one status write as the class description says: reads the log's version, then
	 * the status, and hands both to the attempt, which decides and writes against that
	 * version. An attempt that returns {@code null} lost the write to another append and
	 * is repeated after a fresh read. Every attempt costs two reads and one write, so the
	 * retries are spaced by the store's latency without a pause of their own.
	 * @param attempt given the version and the status as read; returns the status after
	 * the attempt, or {@code null} when its write was refused
	 * @param failure the message of the exception thrown when every attempt lost
	 */
	private TurnStatus writeStatus(String sessionId, BiFunction<Long, TurnStatus, @Nullable TurnStatus> attempt,
			Supplier<String> failure) {
		for (int i = 1;; i++) {
			long version = this.sessionService.getEventVersion(sessionId);
			TurnStatus current = status(sessionId);
			TurnStatus result = attempt.apply(version, current);
			if (result != null) {
				return result;
			}
			if (i == ATTEMPTS) {
				throw new IllegalStateException(failure.get());
			}
		}
	}

}
