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

package org.springaicommunity.session.turn.advisor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springaicommunity.session.turn.Answer;
import org.springaicommunity.session.turn.Question;
import org.springaicommunity.session.turn.SessionStatus;
import org.springaicommunity.session.turn.StopReason;
import org.springaicommunity.session.turn.ToolConfirmationQuestion;
import org.springaicommunity.session.turn.TurnEventTypes;
import org.springaicommunity.session.turn.TurnInProgressException;
import org.springaicommunity.session.turn.TurnParkedException;
import org.springaicommunity.session.turn.TurnService;
import org.springaicommunity.session.turn.TurnStatus;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Runs the {@link TurnStatusAdvisor} around the Spring AI session advisors, with a
 * scripted model.
 *
 * @author Christian Tzolov
 */
class TurnStatusAdvisorTests {

	private static final String SESSION_ID = "s1";

	private final SessionService sessionService = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final TurnService turns = new TurnService(this.sessionService);

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final List<String> ran = new CopyOnWriteArrayList<>();

	private final ChatClient client = client(weather(), booking(), stopButton());

	// --- a turn

	@Test
	void callRunsAsTurnThatEnds() {
		this.model.thenAnswer("Hello.");

		ChatClientResponse response = this.client.prompt()
			.user("Hi")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.call()
			.chatClientResponse();

		TurnStatus status = this.turns.status(SESSION_ID);
		assertThat(status.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(status.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(response.context().get(TurnStatusAdvisor.RUN_ID_CONTEXT_KEY)).isEqualTo(status.runId());
		assertThat(types()).containsExactly("TURN.STATUS", "MESSAGE", "MESSAGE", "TURN.STATUS");
	}

	@Test
	void callerChoosesRunId() {
		this.model.thenAnswer("Hello.");

		this.client.prompt()
			.user("Hi")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.advisors(TurnStatusAdvisor.runId("run-7"))
			.call()
			.content();

		assertThat(this.turns.status(SESSION_ID).runId()).isEqualTo("run-7");
	}

	@Test
	void resumeKeepsRunIdOfPausedCallWhenPassedBack() {
		this.model.thenCall("c1", "Paris");
		ChatClientResponse paused = this.client.prompt()
			.user("Weather in Paris?")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.call()
			.chatClientResponse();
		String runId = (String) paused.context().get(TurnStatusAdvisor.RUN_ID_CONTEXT_KEY);
		assertThat(runId).isNotBlank();
		this.model.thenAnswer("It is sunny.");

		ChatClientResponse resumed = decide(ToolCallDecisions.of(ToolCallDecision.allow("c1")))
			.advisors(TurnStatusAdvisor.runId(runId))
			.call()
			.chatClientResponse();

		assertThat(resumed.context().get(TurnStatusAdvisor.RUN_ID_CONTEXT_KEY)).isEqualTo(runId);
		assertThat(this.turns.status(SESSION_ID).runId()).isEqualTo(runId);
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void resumeWithoutRunIdRunsUnderNewOne() {
		pause();
		String pausedRunId = this.turns.status(SESSION_ID).runId();
		this.model.thenAnswer("It is sunny.");

		ChatClientResponse resumed = decide(ToolCallDecisions.of(ToolCallDecision.allow("c1"))).call()
			.chatClientResponse();

		Object resumedRunId = resumed.context().get(TurnStatusAdvisor.RUN_ID_CONTEXT_KEY);
		assertThat(resumedRunId).isNotNull().isNotEqualTo(pausedRunId);
		assertThat(this.turns.status(SESSION_ID).runId()).isEqualTo(resumedRunId);
	}

	@Test
	void busySessionRefusesCallAndStoresNothing() {
		this.sessionService.create(CreateSessionRequest.builder().id(SESSION_ID).build());
		this.turns.start(SESSION_ID, "other");

		assertThatThrownBy(
				() -> this.client.prompt().user("Hi").advisors(SessionAdvisors.session(SESSION_ID)).call().content())
			.isInstanceOfSatisfying(TurnInProgressException.class, ex -> assertThat(ex.getRunId()).isEqualTo("other"));

		assertThat(log()).isEmpty();
		assertThat(this.turns.status(SESSION_ID).isRunning("other")).isTrue();
	}

	@Test
	void failedCallEndsTurnWithError() {
		this.model.then(prompt -> {
			throw new IllegalStateException("model down");
		});

		assertThatThrownBy(
				() -> this.client.prompt().user("Hi").advisors(SessionAdvisors.session(SESSION_ID)).call().content())
			.hasMessageContaining("model down");

		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.ERROR);
	}

	@Test
	void interruptDuringCallStands() {
		this.model.then(prompt -> {
			this.turns.interrupt(SESSION_ID, "user pressed stop");
			return answer("Too late.");
		});

		this.client.prompt().user("Hi").advisors(SessionAdvisors.session(SESSION_ID)).call().content();

		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.INTERRUPTED);
	}

	// --- an interrupt stops the tool loop

	@Test
	void interruptStopsToolLoopBeforeNextModelRound() {
		this.model.thenCall("s1", "press_stop", "{\"city\":\"Paris\"}").thenAnswer("Never asked for.");

		assertThatThrownBy(
				() -> this.client.prompt().user("Go").advisors(SessionAdvisors.session(SESSION_ID)).call().content())
			.isInstanceOfSatisfying(TurnInterruptedException.class,
					ex -> assertThat(ex.getStatus().stopReason()).isEqualTo(StopReason.INTERRUPTED));

		// The second model round never ran, and the round that ran is whole in the log
		assertThat(this.model.prompts()).hasSize(1);
		assertThat(log()).containsExactly("Go", "call:s1", "result:s1");
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.INTERRUPTED);
	}

	@Test
	void interruptStopsStreamedToolLoop() {
		this.model.thenCall("s1", "press_stop", "{\"city\":\"Paris\"}").thenAnswer("Never asked for.");

		assertThatThrownBy(() -> this.client.prompt()
			.user("Go")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.stream()
			.content()
			.collectList()
			.block()).isInstanceOf(TurnInterruptedException.class);

		assertThat(this.model.prompts()).hasSize(1);
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.INTERRUPTED);
	}

	@Test
	void interruptedCallLeavesLaterTurnUnderSameRunIdAlone() {
		// While the first call's tool runs, it is interrupted and a second turn starts
		// under the same run id
		ToolCallback stopAndRestart = FunctionToolCallback.builder("press_stop", (City city) -> {
			this.turns.interrupt(SESSION_ID, "user pressed stop");
			this.turns.start(SESSION_ID, "run-1");
			return "stopped";
		}).description("Presses stop").inputType(City.class).build();
		ChatClient client = client(stopAndRestart);
		this.model.thenCall("s1", "press_stop", "{\"city\":\"Paris\"}").thenAnswer("Never asked for.");

		assertThatThrownBy(() -> client.prompt()
			.user("Go")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.advisors(TurnStatusAdvisor.runId("run-1"))
			.call()
			.content()).isInstanceOf(TurnInterruptedException.class);

		// The first call's own end did not end the second turn
		assertThat(this.turns.status(SESSION_ID).isRunning("run-1")).isTrue();
		assertThat(this.model.prompts()).hasSize(1);
	}

	@Test
	void withoutInterruptCheckInterruptedLoopGoesOn() {
		ChatClient client = ChatClient.builder(this.model)
			.defaultAdvisors(TurnAdvisors.builder(this.sessionService).interruptible(false).build())
			.defaultTools(stopButton())
			.build();
		this.model.thenCall("s1", "press_stop", "{\"city\":\"Paris\"}").thenAnswer("Done anyway.");

		String reply = client.prompt().user("Go").advisors(SessionAdvisors.session(SESSION_ID)).call().content();

		assertThat(reply).isEqualTo("Done anyway.");
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.INTERRUPTED);
	}

	@Test
	void interruptCheckNeedsTurnStatusAdvisor() {
		List<Advisor> advisors = new ArrayList<>(SessionAdvisors.builder(this.sessionService).build());
		advisors.add(TurnInterruptAdvisor.builder(this.sessionService).build());
		ChatClient client = ChatClient.builder(this.model).defaultAdvisors(advisors).build();
		this.model.thenAnswer("Hello.");

		assertThatThrownBy(
				() -> client.prompt().user("Hi").advisors(SessionAdvisors.session(SESSION_ID)).call().content())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TurnStatusAdvisor");
	}

	@Test
	void turnAdvisorsBuildsTheChainInOrder() {
		assertThat(TurnAdvisors.builder(this.sessionService).build()).extracting(Advisor::getName)
			.containsExactly("TurnStatusAdvisor", "SessionTurnAdvisor", "SessionMemoryAdvisor", "TurnInterruptAdvisor");
		assertThat(TurnAdvisors.builder(this.sessionService).build()).extracting(Advisor::getOrder).isSorted();
		assertThat(TurnAdvisors.builder(this.sessionService).interruptible(false).build()).extracting(Advisor::getName)
			.doesNotContain("TurnInterruptAdvisor");
	}

	// --- a pause parks the turn

	@Test
	void pausedCallParksTurnOnToolConfirmation() {
		ChatResponse paused = pause();

		assertThat(ToolCallDecisions.awaitingDecision(paused)).containsExactly("c1");
		assertThat(this.turns.status(SESSION_ID).isParked()).isTrue();
		SessionEvent pending = Objects.requireNonNull(this.turns.pendingQuestion(SESSION_ID));
		ToolConfirmationQuestion question = ToolConfirmationQuestion.from(pending);
		assertThat(question.replyEventId()).isEqualTo(newestMessage().getId());
		assertThat(question.toolCalls()).singleElement().satisfies(call -> {
			assertThat(call.name()).isEqualTo("get_weather");
			assertThat(call.awaitingDecision()).isTrue();
		});
		assertThat(log()).containsExactly("Weather in Paris?", "call:c1");
		// A plain start on the parked session is refused
		assertThatThrownBy(() -> this.turns.start(SESSION_ID, null)).isInstanceOf(TurnParkedException.class);
	}

	@Test
	void streamedPauseParksTurn() {
		this.model.thenCall("c1", "Paris");

		List<ChatClientResponse> chunks = this.client.prompt()
			.user("Weather in Paris?")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.stream()
			.chatClientResponse()
			.collectList()
			.block();

		assertThat(chunks).isNotEmpty();
		assertThat(ToolCallDecisions.awaitingDecision(chunks.get(chunks.size() - 1).chatResponse()))
			.containsExactly("c1");
		assertThat(this.turns.status(SESSION_ID).isParked()).isTrue();
		assertThat(this.turns.pendingQuestion(SESSION_ID)).isNotNull();
	}

	// --- decisions resume the parked turn

	@Test
	void allowedCallRunsAndTurnGoesOn() {
		pause();
		this.model.thenAnswer("It is sunny.");
		ToolCallDecisions decisions = ToolCallDecisions.of(ToolCallDecision.allow("c1"));

		String reply = decide(decisions).call().content();

		assertThat(reply).isEqualTo("It is sunny.");
		assertThat(this.ran).containsExactly("get_weather");
		assertThat(log()).containsExactly("Weather in Paris?", "call:c1", "result:c1", "It is sunny.");
		assertThat(toolResults()).containsExactly("\"Sunny in Paris\"");
		// The model saw the whole turn
		assertThat(ScriptedChatModel.describe(this.model.prompts().get(1))).containsExactly("Weather in Paris?",
				"call:c1", "result:c1");
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(this.turns.pendingQuestion(SESSION_ID)).isNull();
		Answer answer = Answer.from(events(TurnEventTypes.ANSWER).get(0));
		assertThat(ToolConfirmationQuestion.decisions(answer)).isEqualTo(decisions);
	}

	@Test
	void streamedDenialResumesTurn() {
		pause();
		this.model.thenAnswer("Fine.");

		String reply = decide(ToolCallDecisions.of(ToolCallDecision.deny("c1", "No."))).stream()
			.content()
			.collectList()
			.map(parts -> String.join("", parts))
			.block();

		assertThat(reply).isEqualTo("Fine.");
		assertThat(this.ran).isEmpty();
		assertThat(toolResults()).containsExactly("No.");
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void allowedReturnDirectCallAnswersWithoutModel() {
		this.model.thenCall("b1", "book", "{\"flight\":\"VX205\"}");
		this.client.prompt().user("Book VX205").advisors(SessionAdvisors.session(SESSION_ID)).call().content();

		String reply = decide(ToolCallDecisions.of(ToolCallDecision.allow("b1"))).call().content();

		assertThat(reply).isEqualTo("\"Booked VX205\"");
		assertThat(this.model.prompts()).hasSize(1);
		assertThat(log()).containsExactly("Book VX205", "call:b1", "result:b1");
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void newTextOnParkedSessionDeniesOpenCallsFirst() {
		pause();
		this.model.thenAnswer("Sure.");

		String reply = this.client.prompt()
			.user("Never mind, tell me a joke")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.call()
			.content();

		assertThat(reply).isEqualTo("Sure.");
		assertThat(this.ran).isEmpty();
		assertThat(log()).containsExactly("Weather in Paris?", "call:c1", "result:c1", "Never mind, tell me a joke",
				"Sure.");
		assertThat(toolResults()).containsExactly(TurnStatusAdvisor.ABANDONED_DENY_MESSAGE);
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	// --- refused decisions

	@Test
	void decisionsOnSessionNotParkedAreRefused() {
		this.model.thenAnswer("Hello.");
		this.client.prompt().user("Hi").advisors(SessionAdvisors.session(SESSION_ID)).call().content();
		List<SessionEvent> before = this.sessionService.getEvents(SESSION_ID);

		assertThatThrownBy(() -> decide(ToolCallDecisions.of(ToolCallDecision.allow("c1"))).call().content())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("not parked");

		assertThat(this.sessionService.getEvents(SESSION_ID)).hasSameSizeAs(before);
	}

	@Test
	void decisionsWithUserMessageAreRefused() {
		pause();

		assertThatThrownBy(() -> this.client.prompt()
			.user("Yes")
			.advisors(TurnStatusAdvisor.decisions(SESSION_ID, ToolCallDecisions.of(ToolCallDecision.allow("c1"))))
			.call()
			.content()).isInstanceOf(IllegalArgumentException.class);

		assertThat(this.turns.status(SESSION_ID).isParked()).isTrue();
	}

	@Test
	void decisionsThatDoNotFitThePausedReplyLeaveQuestionOpen() {
		pause();

		assertThatThrownBy(() -> decide(ToolCallDecisions.of(ToolCallDecision.allow("other"))).call().content())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("other");

		assertThat(this.turns.status(SESSION_ID).isParked()).isTrue();
		assertThat(this.turns.pendingQuestion(SESSION_ID)).isNotNull();
		assertThat(log()).containsExactly("Weather in Paris?", "call:c1");
	}

	@Test
	void allowedCallWhoseToolTheRequestLacksIsRefused() {
		pause();
		ChatClient withoutTools = client();

		assertThatThrownBy(() -> withoutTools.prompt()
			.advisors(TurnStatusAdvisor.decisions(SESSION_ID, ToolCallDecisions.of(ToolCallDecision.allow("c1"))))
			.call()
			.content()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("get_weather");

		assertThat(this.turns.status(SESSION_ID).isParked()).isTrue();
	}

	@Test
	void newTextMovesOnFromQuestionOfAnotherKind() {
		this.sessionService.create(CreateSessionRequest.builder().id(SESSION_ID).build());
		this.turns.park(SESSION_ID, this.turns.start(SESSION_ID, "other"), new Question("approval", "Ship it?"));
		this.model.thenAnswer("Sure.");

		String reply = this.client.prompt()
			.user("Tell me a joke")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.call()
			.content();

		assertThat(reply).isEqualTo("Sure.");
		assertThat(this.turns.pendingQuestion(SESSION_ID)).isNull();
		assertThat(Answer.from(events(TurnEventTypes.ANSWER).get(0)).value()).isNull();
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void decisionsForQuestionOfAnotherKindAreRefused() {
		this.sessionService.create(CreateSessionRequest.builder().id(SESSION_ID).build());
		this.turns.park(SESSION_ID, this.turns.start(SESSION_ID, "other"), new Question("approval", "Ship it?"));

		assertThatThrownBy(() -> decide(ToolCallDecisions.of(ToolCallDecision.allow("c1"))).call().content())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("approval");

		assertThat(this.turns.pendingQuestion(SESSION_ID)).isNotNull();
	}

	@Test
	void systemMessageAfterPausedReplyDoesNotHideIt() {
		// Only a service that allows system messages stores one
		SessionService withSystem = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.allowSystemMessages(true)
			.build();
		List<Advisor> advisors = new ArrayList<>();
		advisors.add(TurnStatusAdvisor.builder(withSystem).build());
		advisors.addAll(SessionAdvisors.builder(withSystem).build());
		ChatClient client = ChatClient.builder(this.model).defaultAdvisors(advisors).defaultTools(weather()).build();
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny.");
		client.prompt().user("Weather in Paris?").advisors(SessionAdvisors.session(SESSION_ID)).call().content();
		withSystem.appendMessage(SESSION_ID, new SystemMessage("Be brief."));

		String reply = client.prompt()
			.advisors(TurnStatusAdvisor.decisions(SESSION_ID, ToolCallDecisions.of(ToolCallDecision.allow("c1"))))
			.call()
			.content();

		assertThat(reply).isEqualTo("Sunny.");
		assertThat(this.ran).containsExactly("get_weather");
		assertThat(new TurnService(withSystem).status(SESSION_ID).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void decisionsForUnknownSessionCreateNothing() {
		assertThatThrownBy(() -> this.client.prompt()
			.advisors(TurnStatusAdvisor.decisions("unknown", ToolCallDecisions.of(ToolCallDecision.allow("c1"))))
			.call()
			.content()).isInstanceOf(RuntimeException.class);

		assertThat(this.sessionService.findById("unknown")).isNull();
	}

	@Test
	void resumedToolGetsSessionIdInToolContextWhenSetUp() {
		List<Object> seen = new CopyOnWriteArrayList<>();
		ToolCallback weather = FunctionToolCallback.builder("get_weather", (City city, ToolContext toolContext) -> {
			seen.add(toolContext.getContext().get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY));
			return "Sunny in " + city.city();
		})
			.description("The weather in a city")
			.inputType(City.class)
			.toolMetadata(ToolMetadata.builder().requiresConfirmation(true).build())
			.build();
		List<Advisor> advisors = new ArrayList<>();
		advisors.addAll(TurnAdvisors.builder(this.sessionService).sessionIdInToolContext(true).build());
		ChatClient client = ChatClient.builder(this.model).defaultAdvisors(advisors).defaultTools(weather).build();
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny.");
		client.prompt().user("Weather in Paris?").advisors(SessionAdvisors.session(SESSION_ID)).call().content();

		client.prompt()
			.advisors(TurnStatusAdvisor.decisions(SESSION_ID, ToolCallDecisions.of(ToolCallDecision.allow("c1"))))
			.call()
			.content();

		assertThat(seen).containsExactly(SESSION_ID);
	}

	// --- a cancelled stream

	@Test
	void streamCancelledWhileTurnBeginsEndsTurnAsCancelled() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// The first task, the turn's begin, waits until the caller has left
			AtomicBoolean first = new AtomicBoolean(true);
			Scheduler gated = Schedulers.fromExecutor(task -> pool.execute(() -> {
				if (first.getAndSet(false)) {
					awaitQuietly(release);
				}
				task.run();
			}));
			List<Advisor> advisors = new ArrayList<>();
			advisors.add(TurnStatusAdvisor.builder(this.sessionService).scheduler(gated).build());
			advisors.addAll(SessionAdvisors.builder(this.sessionService).build());
			ChatClient client = ChatClient.builder(this.model).defaultAdvisors(advisors).build();
			this.model.thenAnswer("Hello.");

			Disposable subscription = client.prompt()
				.user("Hi")
				.advisors(SessionAdvisors.session(SESSION_ID))
				.stream()
				.content()
				.subscribe();
			subscription.dispose();
			release.countDown();

			await().atMost(Duration.ofSeconds(5))
				.untilAsserted(
						() -> assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.CANCELLED));
			assertThat(this.model.prompts()).isEmpty();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void cancelledStreamEndsTurnAsCancelled() {
		this.model.thenAnswer("Hello.");

		this.client.prompt()
			.user("Hi")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.stream()
			.chatClientResponse()
			.take(1)
			.blockLast();

		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(this.turns.status(SESSION_ID).isRunning()).isFalse());
		assertThat(this.turns.status(SESSION_ID).stopReason()).isEqualTo(StopReason.CANCELLED);
	}

	// --- helpers

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private ChatResponse pause() {
		this.model.thenCall("c1", "Paris");
		ChatResponse paused = this.client.prompt()
			.user("Weather in Paris?")
			.advisors(SessionAdvisors.session(SESSION_ID))
			.call()
			.chatResponse();
		assertThat(ToolCallDecisions.isAwaitingDecision(paused)).isTrue();
		return Objects.requireNonNull(paused);
	}

	private ChatClient.ChatClientRequestSpec decide(ToolCallDecisions decisions) {
		return this.client.prompt().advisors(TurnStatusAdvisor.decisions(SESSION_ID, decisions));
	}

	private ChatClient client(ToolCallback... tools) {
		ChatClient.Builder builder = ChatClient.builder(this.model)
			.defaultAdvisors(TurnAdvisors.builder(this.sessionService).build());
		if (tools.length > 0) {
			builder.defaultTools((Object[]) tools);
		}
		return builder.build();
	}

	/** A tool that presses the stop button while it runs. */
	private ToolCallback stopButton() {
		return FunctionToolCallback.builder("press_stop", (City city) -> {
			this.ran.add("press_stop");
			this.turns.interrupt(SESSION_ID, "user pressed stop");
			return "stopped";
		}).description("Presses stop").inputType(City.class).build();
	}

	private ToolCallback weather() {
		return FunctionToolCallback.builder("get_weather", (City city) -> {
			this.ran.add("get_weather");
			return "Sunny in " + city.city();
		})
			.description("The weather in a city")
			.inputType(City.class)
			.toolMetadata(ToolMetadata.builder().requiresConfirmation(true).build())
			.build();
	}

	private ToolCallback booking() {
		return FunctionToolCallback.builder("book", (Flight flight) -> {
			this.ran.add("book");
			return "Booked " + flight.flight();
		})
			.description("Books a flight")
			.inputType(Flight.class)
			.toolMetadata(ToolMetadata.builder().requiresConfirmation(true).returnDirect(true).build())
			.build();
	}

	private static ChatResponse answer(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private List<String> log() {
		return ScriptedChatModel.describe(new Prompt(this.sessionService.getActiveMessages(SESSION_ID)));
	}

	private SessionEvent newestMessage() {
		List<SessionEvent> messages = this.sessionService.getEvents(SESSION_ID, EventFilter.active());
		return messages.get(messages.size() - 1);
	}

	private List<SessionEvent> events(EventType type) {
		return this.sessionService.getEvents(SESSION_ID, EventFilter.builder().eventTypes(Set.of(type)).build());
	}

	private List<String> types() {
		return this.sessionService.getEvents(SESSION_ID).stream().map(e -> e.getType().name()).toList();
	}

	private List<String> toolResults() {
		return this.sessionService.getActiveMessages(SESSION_ID)
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(results -> results.getResponses().stream())
			.map(ToolResponseMessage.ToolResponse::responseData)
			.toList();
	}

	record City(String city) {
	}

	record Flight(String flight) {
	}

}
