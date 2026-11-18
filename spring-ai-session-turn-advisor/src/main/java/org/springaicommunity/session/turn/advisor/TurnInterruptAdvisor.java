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

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.springaicommunity.session.turn.TurnService;
import org.springaicommunity.session.turn.TurnStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionContexts;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.util.Assert;

/**
 * Stops the tool-calling loop after an interrupt. Before each model round the advisor
 * reads the session's turn status. When the call's turn no longer runs, because
 * {@link TurnService#interrupt} ended it, the advisor throws
 * {@link TurnInterruptedException} and the loop unwinds. The rounds that already ran stay
 * in the log, the tool results of the last one included.
 * <p>
 * Without this advisor an interrupt frees the session while the interrupted call goes on:
 * its loop keeps running tools and storing messages, in between those of the next turn.
 * <p>
 * The advisor sits just inside the {@link SessionMemoryAdvisor} (order
 * {@link #DEFAULT_ORDER}), so the memory advisor has stored the round's request by the
 * time the check throws. The check is one indexed read per model round.
 * <p>
 * It reads the turn's id from {@link TurnStatusAdvisor#TURN_ID_CONTEXT_KEY}, which the
 * {@link TurnStatusAdvisor} sets on the request it forwards. So it needs that advisor
 * outside the loop, and fails the call when the key is missing. {@link TurnAdvisors}
 * builds both at their orders.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public final class TurnInterruptAdvisor implements CallAdvisor, StreamAdvisor {

	/**
	 * Just inside the {@link SessionMemoryAdvisor}, and so inside the tool-calling loop.
	 */
	public static final int DEFAULT_ORDER = SessionMemoryAdvisor.DEFAULT_ORDER + 100;

	private static final ContextSnapshotFactory CONTEXT_SNAPSHOTS = ContextSnapshotFactory.builder().build();

	private final TurnService turns;

	private final int order;

	private final Scheduler scheduler;

	private TurnInterruptAdvisor(SessionService sessionService, int order, Scheduler scheduler) {
		this.turns = new TurnService(sessionService);
		this.order = order;
		this.scheduler = scheduler;
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	@Override
	public String getName() {
		return "TurnInterruptAdvisor";
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		check(request);
		return chain.nextCall(request);
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		// The status read runs on the scheduler with the caller's context restored
		return Flux.deferContextual(contextView -> {
			ContextSnapshot context = CONTEXT_SNAPSHOTS.captureAll(contextView);
			return Mono.fromCallable(() -> {
				try (ContextSnapshot.Scope ignored = context.setThreadLocals()) {
					check(request);
					return request;
				}
			}).subscribeOn(this.scheduler).flatMapMany(chain::nextStream);
		});
	}

	/**
	 * Checks that the call's turn still runs.
	 * @throws TurnInterruptedException if it does not
	 */
	private void check(ChatClientRequest request) {
		if (!(request.context().get(TurnStatusAdvisor.TURN_ID_CONTEXT_KEY) instanceof String turnId)) {
			throw new IllegalStateException("No turn id found in the advisor context. TurnInterruptAdvisor runs "
					+ "inside the tool-calling loop and needs the TurnStatusAdvisor outside it, which starts the turn "
					+ "and sets TurnStatusAdvisor.TURN_ID_CONTEXT_KEY on the request it forwards");
		}

		String sessionId = SessionContexts.sessionId(request.context());
		TurnStatus status = this.turns.status(sessionId);
		if (!status.isRunning() || !turnId.equals(status.eventId())) {
			throw new TurnInterruptedException(sessionId, status);
		}
	}

	public static final class Builder {

		private final SessionService sessionService;

		private int order = DEFAULT_ORDER;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		/**
		 * The advisor order; {@link #DEFAULT_ORDER} by default. Keep it inside the
		 * tool-calling advisor and the {@link SessionMemoryAdvisor}.
		 */
		public Builder order(int order) {
			this.order = order;
			return this;
		}

		/** The scheduler that runs the status read of a streamed call. */
		public Builder scheduler(Scheduler scheduler) {
			Assert.notNull(scheduler, "scheduler must not be null");
			this.scheduler = scheduler;
			return this;
		}

		public TurnInterruptAdvisor build() {
			return new TurnInterruptAdvisor(this.sessionService, this.order, this.scheduler);
		}

	}

}
