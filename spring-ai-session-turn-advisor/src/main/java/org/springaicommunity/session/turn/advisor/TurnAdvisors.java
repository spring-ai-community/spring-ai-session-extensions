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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.util.Assert;

/**
 * Builds the advisors that run a {@code ChatClient} call as a turn of a session, at their
 * orders: the {@link TurnStatusAdvisor}, the Spring AI session advisors, and the
 * {@link TurnInterruptAdvisor}. The settings the advisors share are set once here, so
 * they cannot differ. <pre>{@code
 * ChatClient chatClient = ChatClient.builder(chatModel)
 *     .defaultAdvisors(TurnAdvisors.builder(sessionService).build())
 *     .build();
 * }</pre>
 * <p>
 * The Spring AI session advisors keep their own settings, compaction for example:
 * <pre>{@code
 * TurnAdvisors.builder(sessionService)
 *     .sessionAdvisors(session -> session.compaction(trigger, strategy))
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public final class TurnAdvisors {

	private TurnAdvisors() {
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	public static final class Builder {

		private final SessionService sessionService;

		private @Nullable ToolCallingManager toolCallingManager;

		private @Nullable Scheduler scheduler;

		private boolean sessionIdInToolContext;

		private boolean interruptible = true;

		private Consumer<SessionAdvisors.Builder> sessionAdvisors = session -> {
		};

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
		 * The scheduler that runs the session calls of a streamed call, for every
		 * advisor; the advisors' default scheduler by default.
		 */
		public Builder scheduler(Scheduler scheduler) {
			Assert.notNull(scheduler, "scheduler must not be null");
			this.scheduler = scheduler;
			return this;
		}

		/**
		 * Whether the tools of a call get the session id in their tool context;
		 * {@code false} by default. It applies to the calls of the loop and to the
		 * allowed calls of a resumed turn alike. Set it here, not on the Spring AI
		 * session advisors: the value set here wins.
		 */
		public Builder sessionIdInToolContext(boolean sessionIdInToolContext) {
			this.sessionIdInToolContext = sessionIdInToolContext;
			return this;
		}

		/**
		 * Whether an interrupt stops the tool-calling loop of the running call, with the
		 * {@link TurnInterruptAdvisor}; {@code true} by default. It costs one indexed
		 * read per model round. Without it an interrupted call goes on storing messages
		 * while the next turn runs.
		 */
		public Builder interruptible(boolean interruptible) {
			this.interruptible = interruptible;
			return this;
		}

		/**
		 * Settings of the Spring AI session advisors: compaction, the memory advisor. The
		 * scheduler and the session id in the tool context are set on this builder.
		 */
		public Builder sessionAdvisors(Consumer<SessionAdvisors.Builder> sessionAdvisors) {
			Assert.notNull(sessionAdvisors, "sessionAdvisors must not be null");
			this.sessionAdvisors = sessionAdvisors;
			return this;
		}

		/**
		 * Builds the advisors, outermost first.
		 * @return the advisors for {@code ChatClient.Builder.defaultAdvisors}
		 */
		public List<Advisor> build() {
			TurnStatusAdvisor.Builder turnStatus = TurnStatusAdvisor.builder(this.sessionService)
				.sessionIdInToolContext(this.sessionIdInToolContext);
			SessionAdvisors.Builder session = SessionAdvisors.builder(this.sessionService);
			this.sessionAdvisors.accept(session);
			session.sessionIdInToolContext(this.sessionIdInToolContext);
			TurnInterruptAdvisor.Builder interrupt = TurnInterruptAdvisor.builder(this.sessionService);
			if (this.toolCallingManager != null) {
				turnStatus.toolCallingManager(this.toolCallingManager);
			}
			if (this.scheduler != null) {
				turnStatus.scheduler(this.scheduler);
				session.scheduler(this.scheduler);
				interrupt.scheduler(this.scheduler);
			}
			List<Advisor> advisors = new ArrayList<>();
			advisors.add(turnStatus.build());
			advisors.addAll(session.build());
			if (this.interruptible) {
				advisors.add(interrupt.build());
			}
			return List.copyOf(advisors);
		}

	}

}
