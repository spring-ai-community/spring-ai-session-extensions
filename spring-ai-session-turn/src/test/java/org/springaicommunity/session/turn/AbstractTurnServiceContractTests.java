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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the {@link TurnService} on a store. The turn status is read by event type with a
 * window of one, and written with the version-checked append, so a store that passes
 * Spring AI's repository contract tests is expected to pass these too. A subclass returns
 * the repository under test.
 *
 * @author Christian Tzolov
 */
abstract class AbstractTurnServiceContractTests {

	private SessionRepository repository;

	private SessionService sessionService;

	private TurnService turns;

	/**
	 * Returns the repository under test, empty or holding other sessions only.
	 * @return the repository
	 */
	protected abstract SessionRepository createRepository();

	@BeforeEach
	void setUpService() {
		this.repository = createRepository();
		this.sessionService = DefaultSessionService.builder().sessionRepository(this.repository).build();
		this.turns = new TurnService(this.sessionService);
	}

	@Test
	void turnStartsEndsAndIsInterruptedThroughTheStore() {
		String id = newSession();

		TurnStatus started = this.turns.start(id, "run-1");
		append(id, new UserMessage("u1"), new AssistantMessage("a1"));

		TurnStatus running = this.turns.status(id);
		assertThat(running.isRunning("run-1")).isTrue();
		assertThat(running.eventId()).isEqualTo(started.eventId());
		assertThatThrownBy(() -> this.turns.start(id, "run-2")).isInstanceOfSatisfying(TurnInProgressException.class,
				ex -> assertThat(ex.getRunId()).isEqualTo("run-1"));

		this.turns.end(id, turn(id, "run-1"), StopReason.END_TURN);
		TurnStatus ended = this.turns.status(id);
		assertThat(ended.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(ended.runId()).isEqualTo("run-1");
		assertThat(ended.stopReason()).isEqualTo(StopReason.END_TURN);

		this.turns.start(id, "run-2");
		append(id, new UserMessage("u2"));
		assertThat(this.turns.interrupt(id, "user pressed stop").stopReason()).isEqualTo(StopReason.INTERRUPTED);
		// The interrupted run's own end is a no-op: the interrupt stands
		assertThat(this.turns.end(id, turn(id, "run-2"), StopReason.END_TURN).stopReason())
			.isEqualTo(StopReason.INTERRUPTED);
		assertThat(this.turns.status(id).runId()).isEqualTo("run-2");
		assertThat(this.repository.findEvents(id, ofType(TurnEventTypes.INTERRUPT))).singleElement()
			.satisfies(event -> assertThat(SessionStatusEvents.reason(event)).isEqualTo("user pressed stop"));
		// The status never reaches the view
		assertThat(this.sessionService.getActiveMessages(id)).extracting(message -> message.getText())
			.containsExactly("u1", "a1", "u2");
	}

	@Test
	void parkedTurnRoundTripsItsQuestionAndAnswerThroughTheStore() {
		String id = newSession();
		this.turns.start(id, "run-1");
		append(id, new UserMessage("Book flight VX205 and pay"));
		AssistantMessage reply = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "bookFlight", "{\"flight\":\"VX205\"}"),
					new AssistantMessage.ToolCall("c2", "function", "pay", "{\"amount\":120.5,\"currency\":\"EUR\"}")))
			.build();
		SessionEvent replyEvent = SessionEvent.builder().sessionId(id).message(reply).build();
		this.repository.appendEvent(replyEvent);
		ToolConfirmationQuestion asked = ToolConfirmationQuestion.of(replyEvent.getId(), reply, List.of("c1", "c2"));

		assertThat(this.turns.park(id, turn(id, "run-1"), asked.toQuestion()).isParked()).isTrue();

		assertThat(this.turns.status(id).isParked()).isTrue();
		assertThatThrownBy(() -> this.turns.start(id, "run-2")).isInstanceOf(TurnParkedException.class);
		SessionEvent pending = Objects.requireNonNull(this.turns.pendingQuestion(id));
		assertThat(ToolConfirmationQuestion.from(pending)).isEqualTo(asked);

		ToolCallDecisions decisions = ToolCallDecisions.of(ToolCallDecision.allow("c1"),
				ToolCallDecision.deny("c2", "Too expensive"));
		TurnStatus resumed = this.turns.resume(id, "run-1", ToolConfirmationQuestion.answer(decisions));

		assertThat(resumed.isRunning("run-1")).isTrue();
		assertThat(this.turns.status(id).isRunning("run-1")).isTrue();
		assertThat(this.turns.pendingQuestion(id)).isNull();
		Answer answer = Answer.from(this.repository.findEvents(id, ofType(TurnEventTypes.ANSWER)).get(0));
		assertThat(answer.questionEventId()).isEqualTo(pending.getId());
		assertThat(ToolConfirmationQuestion.decisions(answer)).isEqualTo(decisions);
		// The same answer sent twice resumes once
		assertThatThrownBy(() -> this.turns.resume(id, null, ToolConfirmationQuestion.answer(decisions)))
			.isInstanceOf(TurnInProgressException.class);

		this.turns.end(id, turn(id, "run-1"), StopReason.END_TURN);
		assertThat(labels(this.repository.findEvents(id, EventFilter.all()))).containsExactly("RUNNING",
				"Book flight VX205 and pay", "A[bookFlight, pay]", "TURN.QUESTION", "IDLE", "TURN.ANSWER", "RUNNING",
				"IDLE");
		assertThat(this.repository.findEvents(id, EventFilter.active())).hasSize(2);
	}

	@Test
	void concurrentStartsOnOneSessionLeaveExactlyOneTurn() throws Exception {
		String id = newSession();
		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String runId = "run-" + i;
				results.add(executor.submit(() -> {
					start.await();
					try {
						this.turns.start(id, runId);
						return true;
					}
					catch (TurnInProgressException ex) {
						return false;
					}
				}));
			}
			start.countDown();
			int winners = 0;
			for (Future<Boolean> result : results) {
				if (result.get(30, TimeUnit.SECONDS)) {
					winners++;
				}
			}
			assertThat(winners).isEqualTo(1);
			assertThat(this.turns.status(id).isRunning()).isTrue();
			assertThat(this.repository.findEvents(id, ofType(TurnEventTypes.STATUS))).hasSize(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private String newSession() {
		return this.sessionService.create(CreateSessionRequest.builder().id(UUID.randomUUID().toString()).build()).id();
	}

	private void append(String sessionId, Message... messages) {
		for (Message message : messages) {
			this.sessionService.appendMessage(sessionId, message);
		}
	}

	private static EventFilter ofType(EventType type) {
		return EventFilter.builder().eventTypes(Set.of(type)).build();
	}

	/**
	 * One label per event: the status of a status event, the text of a message, the tool
	 * names of a reply with tool calls, the type name otherwise.
	 */
	private static List<String> labels(List<SessionEvent> events) {
		return events.stream().map(event -> {
			if (TurnEventTypes.STATUS.equals(event.getType())) {
				return TurnStatus.from(event).status().name();
			}
			if (event.hasMessage()) {
				if (event.getMessage() instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
					return "A" + assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList();
				}
				return event.getMessage().getText();
			}
			return event.getType().name();
		}).toList();
	}

	/** The turn that started last under the run id, whether it still runs or not. */
	private TurnStatus turn(String sessionId, String runId) {
		List<SessionEvent> events = this.sessionService.getEvents(sessionId,
				EventFilter.builder().eventTypes(Set.of(TurnEventTypes.STATUS)).build());
		for (int i = events.size() - 1; i >= 0; i--) {
			TurnStatus status = TurnStatus.from(events.get(i));
			if (status.isRunning(runId)) {
				return status;
			}
		}
		// A turn that never started
		return new TurnStatus(SessionStatus.RUNNING, runId, null, null, "never-started");
	}

}
