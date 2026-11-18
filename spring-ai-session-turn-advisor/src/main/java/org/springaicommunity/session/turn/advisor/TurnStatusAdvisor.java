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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.session.turn.StopReason;
import org.springaicommunity.session.turn.ToolConfirmationQuestion;
import org.springaicommunity.session.turn.TurnInProgressException;
import org.springaicommunity.session.turn.TurnParkedException;
import org.springaicommunity.session.turn.TurnService;
import org.springaicommunity.session.turn.TurnStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionContexts;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Runs each {@code ChatClient} call as a turn of the session, with the
 * {@link TurnService}. The advisor sits outside the Spring AI session advisors (order
 * {@link #DEFAULT_ORDER}) and leaves them as they are.
 * <ul>
 * <li>Before the call it starts the turn. A session on which a turn runs refuses the call
 * with {@link TurnInProgressException}.</li>
 * <li>After the call it ends the turn: {@link StopReason#END_TURN}, or
 * {@link StopReason#ERROR} when the call failed, or {@link StopReason#CANCELLED} when a
 * stream was cancelled. An interrupt that ended the turn first stands.</li>
 * <li>When the tool-calling loop pauses on a tool call that needs a person's
 * confirmation, it parks the turn on a {@link ToolConfirmationQuestion} instead of ending
 * it.</li>
 * <li>A later call on the parked session with the person's decisions under
 * {@link #TOOL_CALL_DECISIONS_CONTEXT_KEY} and no user message resumes the turn, on any
 * instance: the allowed calls run, the results are stored after the paused reply, and the
 * model goes on from there.</li>
 * <li>A call with new text on a parked session denies the open calls with
 * {@link #ABANDONED_DENY_MESSAGE} first, so the log says they were denied, and then runs
 * the text as the turn.</li>
 * </ul>
 * The run id of the turn comes from {@link #RUN_ID_CONTEXT_KEY} in the request context,
 * or is generated. The advisor puts it into the context of the forwarded request and of
 * the response. <pre>{@code
 * ChatClient chatClient = ChatClient.builder(chatModel)
 *     .defaultAdvisors(TurnAdvisors.builder(sessionService).build())
 *     .build();
 *
 * // a paused call returns a response awaiting a decision; later, on any instance:
 * chatClient.prompt()
 *     .advisors(TurnStatusAdvisor.decisions(sessionId, decisions))
 *     .call()
 *     .content();
 * }</pre>
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public final class TurnStatusAdvisor implements CallAdvisor, StreamAdvisor {

	/**
	 * Outside the Spring AI {@link SessionTurnAdvisor}, so the advisor sees the whole
	 * call and the response after the Spring AI advisors stored it.
	 */
	public static final int DEFAULT_ORDER = SessionTurnAdvisor.DEFAULT_ORDER - 100;

	/**
	 * The context key of the run id the turn runs under. Set it on the request to choose
	 * the run id; the advisor sets it on the forwarded request and on the response.
	 */
	public static final String RUN_ID_CONTEXT_KEY = "session_run_id";

	/**
	 * The context key of the turn's id, the id of the status event that started it. The
	 * advisor sets it on the request it forwards, and the {@link TurnInterruptAdvisor}
	 * inside the tool-calling loop reads it.
	 */
	public static final String TURN_ID_CONTEXT_KEY = "session_turn_id";

	/**
	 * The context key of the {@link ToolCallDecisions} that answer the tool calls a
	 * session parked on. A request that carries them carries no user message.
	 */
	public static final String TOOL_CALL_DECISIONS_CONTEXT_KEY = "session_tool_call_decisions";

	/**
	 * The result stored for a parked tool call when the caller moved on with new text.
	 */
	public static final String ABANDONED_DENY_MESSAGE = "The tool call was not run: the user moved on without deciding on it.";

	private static final Logger logger = LoggerFactory.getLogger(TurnStatusAdvisor.class);

	private static final ContextSnapshotFactory CONTEXT_SNAPSHOTS = ContextSnapshotFactory.builder().build();

	private final SessionService sessionService;

	private final TurnService turns;

	private final ToolConfirmationResume toolConfirmationResume;

	private final int order;

	private final Scheduler scheduler;

	private TurnStatusAdvisor(SessionService sessionService, ToolCallingManager toolCallingManager, int order,
			Scheduler scheduler, boolean sessionIdInToolContext) {
		this.sessionService = sessionService;
		this.turns = new TurnService(sessionService);
		this.toolConfirmationResume = new ToolConfirmationResume(sessionService, this.turns, toolCallingManager,
				sessionIdInToolContext);
		this.order = order;
		this.scheduler = scheduler;
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	/**
	 * The request answers the tool calls the session parked on:
	 * {@code .advisors(TurnStatusAdvisor.decisions(sessionId, decisions))}. It sets the
	 * session id, as {@link SessionAdvisors#session(String)} does, and the decisions
	 * under {@link #TOOL_CALL_DECISIONS_CONTEXT_KEY}.
	 * @param sessionId the parked session
	 * @param decisions the decisions on the parked tool calls
	 * @return the advisor parameters of the request
	 */
	public static Consumer<ChatClient.AdvisorSpec> decisions(String sessionId, ToolCallDecisions decisions) {
		Assert.notNull(decisions, "decisions must not be null");
		Consumer<ChatClient.AdvisorSpec> session = SessionAdvisors.session(sessionId);
		return advisors -> {
			session.accept(advisors);
			advisors.param(TOOL_CALL_DECISIONS_CONTEXT_KEY, decisions);
		};
	}

	/**
	 * The request's turn runs under the run id:
	 * {@code .advisors(TurnStatusAdvisor.runId(runId))}. It sets the run id under
	 * {@link #RUN_ID_CONTEXT_KEY}, for a call that starts a turn and for one that resumes
	 * it. Without it the advisor generates a run id.
	 * @param runId the run id of the turn
	 * @return the advisor parameters of the request
	 */
	public static Consumer<ChatClient.AdvisorSpec> runId(String runId) {
		Assert.hasText(runId, "runId must not be empty");
		return advisors -> advisors.param(RUN_ID_CONTEXT_KEY, runId);
	}

	@Override
	public String getName() {
		return "TurnStatusAdvisor";
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		String sessionId = SessionContexts.sessionId(request.context());
		Turn turn = begin(sessionId, request);
		if (turn.directResponse() != null) {
			end(sessionId, turn.status(), StopReason.END_TURN);
			return response(turn.directResponse(), turn);
		}
		ChatClientResponse response;
		try {
			response = chain.nextCall(turn.request());
		}
		catch (RuntimeException ex) {
			// A no-op when an interrupt ended the turn first: the interrupt stands
			end(sessionId, turn.status(), StopReason.ERROR);
			throw ex;
		}
		complete(sessionId, turn.status(), ToolCallDecisions.awaitingDecision(response.chatResponse()));
		return response.mutate().context(RUN_ID_CONTEXT_KEY, turn.runId()).build();
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		String sessionId = SessionContexts.sessionId(request.context());
		// The session calls run on the scheduler, never on the model's thread, with the
		// caller's context (the security context, the current observation) restored.
		// They are not tied to the subscription: a cancel never interrupts a write.
		return Flux.deferContextual(contextView -> {
			StreamedTurn streamed = new StreamedTurn(sessionId, CONTEXT_SNAPSHOTS.captureAll(contextView));
			return streamed.onScheduler(() -> streamed.begun(begin(sessionId, request))).flatMapMany(turn -> {
				ChatResponse direct = turn.directResponse();
				if (direct != null) {
					streamed.streamDone();
					return streamed.settle().thenReturn(response(direct, turn));
				}
				return chain.nextStream(turn.request())
					.doOnNext(response -> streamed.awaiting(response.chatResponse()))
					.doOnComplete(streamed::streamDone)
					.map(response -> response.mutate().context(RUN_ID_CONTEXT_KEY, turn.runId()).build())
					// Only a failed stream ends with ERROR; a failed park ends the turn
					// itself
					.onErrorResume(ex -> streamed.settleWith(StopReason.ERROR).then(Mono.error(ex)))
					.concatWith(streamed.settle().then(Mono.empty()));
			}).doOnCancel(streamed::cancelled);
		});
	}

	/**
	 * Begins the call's turn: starts it, or resumes the parked session with the request's
	 * decisions, or with every call denied when the request brings new text instead. The
	 * request is checked before anything is written.
	 */
	private Turn begin(String sessionId, ChatClientRequest request) {
		ToolCallDecisions decisions = decisions(sessionId, request);
		String runId = runId(request);
		if (decisions != null) {
			// Decisions are for a session that exists and is parked: nothing is created
			TurnStatus status = this.turns.status(sessionId);
			if (!status.isParked()) {
				String runningRunId = status.runningRunId();
				if (runningRunId != null) {
					throw new TurnInProgressException(sessionId, runningRunId);
				}
				throw new IllegalStateException(
						"Session '" + sessionId + "' is not parked on a question; there is nothing to decide on");
			}
			return resumed(request, this.toolConfirmationResume.resume(sessionId, runId, request, decisions));
		}

		// Makes sure the session exists before the advisor writes the turn's first status
		// event.
		this.sessionService.findOrCreate(SessionContexts.createRequest(sessionId, request.context()));

		try {
			TurnStatus started = this.turns.start(sessionId, runId);
			return new Turn(started, forwarded(request, started), null);
		}
		catch (TurnParkedException ex) {
			// New text on a parked session: the open tool calls are denied, the text goes
			// on
			return resumed(request, this.toolConfirmationResume.resume(sessionId, runId, request, null));
		}

	}

	private static Turn resumed(ChatClientRequest request, ToolConfirmationResume.Resumed resumed) {
		return new Turn(resumed.turn(), forwarded(request, resumed.turn()), resumed.directResponse());
	}

	/** Parks the turn on the calls awaiting a decision, or ends it. */
	private void complete(String sessionId, TurnStatus turn, List<String> awaiting) {
		if (awaiting.isEmpty()) {
			end(sessionId, turn, StopReason.END_TURN);
			return;
		}
		try {
			ConversationTail tail = ConversationTail.of(this.sessionService, sessionId);
			if (!tail.isOpenReply()) {
				throw new IllegalStateException("The paused reply of session '" + sessionId
						+ "' is not the newest message of its log; the Spring AI session advisors must run inside "
						+ "this advisor and store it");
			}
			ToolConfirmationQuestion question = ToolConfirmationQuestion.of(tail.event().getId(), tail.reply(),
					awaiting);
			this.turns.park(sessionId, turn, question.toQuestion());
		}
		catch (RuntimeException ex) {
			end(sessionId, turn, StopReason.ERROR);
			throw ex;
		}
	}

	/**
	 * Ends the turn; best-effort, since the call's outcome stands. A turn that an
	 * interrupt ended first stays as the interrupt left it.
	 */
	private void end(String sessionId, TurnStatus turn, StopReason stopReason) {
		try {
			this.turns.end(sessionId, turn, stopReason);
		}
		catch (RuntimeException ex) {
			logger.warn("Could not end the turn of session [{}] under run [{}] with {}: {}", sessionId, turn.runId(),
					stopReason, ex.getMessage());
		}
	}

	/**
	 * The decisions the request carries.
	 * @throws IllegalArgumentException if the request carries decisions of another type,
	 * or decisions together with a user message
	 */
	private static @Nullable ToolCallDecisions decisions(String sessionId, ChatClientRequest request) {
		Object value = request.context().get(TOOL_CALL_DECISIONS_CONTEXT_KEY);
		if (value == null) {
			return null;
		}
		if (!(value instanceof ToolCallDecisions decisions)) {
			throw new IllegalArgumentException("The context key '" + TOOL_CALL_DECISIONS_CONTEXT_KEY + "' holds a "
					+ value.getClass().getName() + ", not ToolCallDecisions");
		}
		if (request.prompt().getInstructions().stream().anyMatch(UserMessage.class::isInstance)) {
			throw new IllegalArgumentException("A request that answers the tool calls of session '" + sessionId
					+ "' carries no user message; send the text in the next call");
		}
		return decisions;
	}

	private static @Nullable String runId(ChatClientRequest request) {
		Object value = request.context().get(RUN_ID_CONTEXT_KEY);
		return (value instanceof String runId && StringUtils.hasText(runId)) ? runId : null;
	}

	/** The request to forward, with the turn's run id and id in its context. */
	private static ChatClientRequest forwarded(ChatClientRequest request, TurnStatus turn) {
		return request.mutate()
			.context(RUN_ID_CONTEXT_KEY, turn.runId())
			.context(TURN_ID_CONTEXT_KEY, turn.eventId())
			.build();
	}

	private static ChatClientResponse response(ChatResponse chatResponse, Turn turn) {
		return ChatClientResponse.builder().chatResponse(chatResponse).context(turn.request().context()).build();
	}

	/**
	 * A call's turn.
	 *
	 * @param status the turn: the status its start or resume returned
	 * @param request the request to forward, with the turn's run id and id in its context
	 * @param directResponse the response to return without running the chain
	 */
	private record Turn(TurnStatus status, ChatClientRequest request, @Nullable ChatResponse directResponse) {

		String runId() {
			return Objects.requireNonNull(this.status.runId(), "a running turn has a run id");
		}

	}

	/**
	 * The settlement of a streamed turn. Exactly one of the stream's ends settles the
	 * turn: the completion parks or ends it, a failure ends it with
	 * {@link StopReason#ERROR}, a cancel ends it with {@link StopReason#CANCELLED}, or
	 * completes it when the stream had already finished. A cancel that arrives while the
	 * turn begins settles it as soon as the turn has begun.
	 */
	private final class StreamedTurn {

		private final String sessionId;

		private final ContextSnapshot context;

		private final AtomicReference<@Nullable Turn> turn = new AtomicReference<>();

		private final AtomicReference<List<String>> awaiting = new AtomicReference<>(List.of());

		private final AtomicBoolean settled = new AtomicBoolean();

		private final AtomicBoolean cancelled = new AtomicBoolean();

		private volatile boolean streamDone;

		StreamedTurn(String sessionId, ContextSnapshot context) {
			this.sessionId = sessionId;
			this.context = context;
		}

		/** Records the begun turn; settles it at once when the caller already left. */
		Turn begun(Turn begun) {
			this.turn.set(begun);
			if (this.cancelled.get()) {
				settleNow(null);
			}
			return begun;
		}

		void awaiting(@Nullable ChatResponse response) {
			List<String> ids = ToolCallDecisions.awaitingDecision(response);
			if (!ids.isEmpty()) {
				this.awaiting.set(ids);
			}
		}

		void streamDone() {
			this.streamDone = true;
		}

		void cancelled() {
			this.cancelled.set(true);
			if (this.turn.get() != null) {
				TurnStatusAdvisor.this.scheduler.schedule(this.context.wrap(() -> settleNow(null)));
			}
		}

		/** Parks or ends the turn after the stream completed. */
		Mono<Void> settle() {
			return onScheduler(() -> {
				settleNow(null);
				return Boolean.TRUE;
			}).then();
		}

		/** Ends the turn with the stop reason. */
		Mono<Void> settleWith(StopReason stopReason) {
			return onScheduler(() -> {
				settleNow(stopReason);
				return Boolean.TRUE;
			}).then();
		}

		/**
		 * Settles the turn once: with the stop reason when one is given, otherwise as the
		 * stream's state says.
		 */
		private void settleNow(@Nullable StopReason stopReason) {
			Turn begun = this.turn.get();
			if (begun == null || !this.settled.compareAndSet(false, true)) {
				return;
			}
			if (stopReason != null) {
				end(this.sessionId, begun.status(), stopReason);
			}
			else if (this.streamDone) {
				complete(this.sessionId, begun.status(), this.awaiting.get());
			}
			else {
				end(this.sessionId, begun.status(), StopReason.CANCELLED);
			}
		}

		/**
		 * Runs the action on the scheduler with the caller's context. The task is not
		 * disposed when the subscriber cancels, so a session write always finishes.
		 */
		<T> Mono<T> onScheduler(Callable<T> action) {
			return Mono.create(sink -> TurnStatusAdvisor.this.scheduler.schedule(() -> {
				try (ContextSnapshot.Scope ignored = this.context.setThreadLocals()) {
					sink.success(action.call());
				}
				catch (Throwable ex) {
					sink.error(ex);
				}
			}));
		}

	}

	public static final class Builder {

		private final SessionService sessionService;

		private ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

		private int order = DEFAULT_ORDER;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		private boolean sessionIdInToolContext;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		/**
		 * The tool calling manager that runs the allowed calls of a resumed turn; a
		 * default one by default. Use the one the {@code ChatClient}'s tool-calling
		 * advisor uses, so the calls run alike.
		 */
		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		/**
		 * The advisor order; {@link #DEFAULT_ORDER} by default. Keep it outside the
		 * Spring AI session advisors.
		 */
		public Builder order(int order) {
			this.order = order;
			return this;
		}

		/** The scheduler that runs the session calls of a streamed call. */
		public Builder scheduler(Scheduler scheduler) {
			Assert.notNull(scheduler, "scheduler must not be null");
			this.scheduler = scheduler;
			return this;
		}

		/**
		 * Whether the allowed calls of a resumed turn get the session id in their tool
		 * context, under {@link SessionMemoryAdvisor#SESSION_ID_CONTEXT_KEY};
		 * {@code false} by default. Set it as the Spring AI {@code SessionTurnAdvisor} is
		 * set, so a tool finds the session id after a confirmation as it does without
		 * one.
		 */
		public Builder sessionIdInToolContext(boolean sessionIdInToolContext) {
			this.sessionIdInToolContext = sessionIdInToolContext;
			return this;
		}

		public TurnStatusAdvisor build() {
			return new TurnStatusAdvisor(this.sessionService, this.toolCallingManager, this.order, this.scheduler,
					this.sessionIdInToolContext);
		}

	}

}
