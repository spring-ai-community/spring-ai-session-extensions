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
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
import org.springframework.util.Assert;

/**
 * The newest message of a session's conversation, read once. The advisor reads the end of
 * the log through it, and writes the tool results of an open reply through it, so a reply
 * never stays in the log without results.
 *
 * @author Christian Tzolov
 */
final class ConversationTail {

	// The advisor's category, so its logging configuration applies
	private static final Logger logger = LoggerFactory.getLogger(TurnStatusAdvisor.class);

	/**
	 * The conversation messages of the active view's newest turn, system messages left
	 * out. A {@code lastN} window keeps turns whole, so it returns the newest turn from
	 * its user message on, oldest first.
	 */
	private static final EventFilter NEWEST = EventFilter.active()
		.toBuilder()
		.messageTypes(Set.of(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL))
		.lastN(1)
		.build();

	/** The suffix of the id of the event that holds the results of a reply's calls. */
	static final String RESULTS_ID_SUFFIX = "-results";

	private final SessionService sessionService;

	private final String sessionId;

	private final @Nullable SessionEvent newest;

	private ConversationTail(SessionService sessionService, String sessionId, @Nullable SessionEvent newest) {
		this.sessionService = sessionService;
		this.sessionId = sessionId;
		this.newest = newest;
	}

	/**
	 * Reads the newest message of the session's active view that is not a system one: one
	 * indexed read.
	 */
	static ConversationTail of(SessionService sessionService, String sessionId) {
		List<SessionEvent> events = sessionService.getEvents(sessionId, NEWEST);
		return new ConversationTail(sessionService, sessionId, events.isEmpty() ? null : events.get(events.size() - 1));
	}

	/** Whether the conversation ends with a reply whose tool calls have no results. */
	boolean isOpenReply() {
		return this.newest != null && this.newest.hasToolCalls();
	}

	/** The newest message event. */
	SessionEvent event() {
		Assert.state(this.newest != null, "the conversation is empty");
		return this.newest;
	}

	/** The open reply. */
	AssistantMessage reply() {
		Assert.state(isOpenReply(), "the conversation does not end with a reply that has open tool calls");
		return (AssistantMessage) event().getMessage();
	}

	/**
	 * Stores the results of the open reply's tool calls right after it. The event id
	 * derives from the reply's, so the same results are stored once.
	 */
	void storeResults(ToolResponseMessage results) {
		SessionEvent reply = event();
		Assert.state(isOpenReply(), "no open reply to store results for");
		this.sessionService.appendEvent(SessionEvent.builder()
			.id(reply.getId() + RESULTS_ID_SUFFIX)
			.sessionId(this.sessionId)
			.message(results)
			.build());
	}

	/**
	 * Answers every tool call of the open reply with
	 * {@link SessionTurnAdvisor#UNANSWERED_TOOL_RESULT}, for a resume that failed before
	 * it stored results. Best-effort: a failure is logged.
	 */
	void answerUnresolved() {
		try {
			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			for (AssistantMessage.ToolCall call : reply().getToolCalls()) {
				responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
						SessionTurnAdvisor.UNANSWERED_TOOL_RESULT));
			}
			storeResults(ToolResponseMessage.builder().responses(responses).build());
		}
		catch (RuntimeException ex) {
			logger.warn("Could not answer the tool calls of session [{}] left without a result: {}", this.sessionId,
					ex.getMessage());
		}
	}

}
