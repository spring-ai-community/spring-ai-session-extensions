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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.session.turn.Question;
import org.springaicommunity.session.turn.StopReason;
import org.springaicommunity.session.turn.ToolConfirmationQuestion;
import org.springaicommunity.session.turn.TurnService;
import org.springaicommunity.session.turn.TurnStatus;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.util.Assert;

/**
 * Resumes a session parked on a tool confirmation. It records the decisions as the
 * answer, runs the turn again, runs the allowed calls through the tool calling manager,
 * and stores the results right after the paused reply. Without decisions every call is
 * denied with {@link TurnStatusAdvisor#ABANDONED_DENY_MESSAGE}: the caller moved on with
 * new text.
 *
 * @author Christian Tzolov
 */
final class ToolConfirmationResume {

	// The advisor's category, so its logging configuration applies
	private static final Logger logger = LoggerFactory.getLogger(TurnStatusAdvisor.class);

	private final SessionService sessionService;

	private final TurnService turns;

	private final ToolCallingManager toolCallingManager;

	private final boolean sessionIdInToolContext;

	ToolConfirmationResume(SessionService sessionService, TurnService turns, ToolCallingManager toolCallingManager,
			boolean sessionIdInToolContext) {
		this.sessionService = sessionService;
		this.turns = turns;
		this.toolCallingManager = toolCallingManager;
		this.sessionIdInToolContext = sessionIdInToolContext;
	}

	/**
	 * Resumes the parked session with the decisions, or, when there are none because the
	 * caller moved on with new text, with every call denied.
	 * <p>
	 * New text always moves the session on. When the session is not parked on the tool
	 * confirmation of its newest reply, because it waits on a question of another kind or
	 * the log changed since it parked, the question is closed with an empty answer and no
	 * tool runs.
	 * @param sessionId the parked session
	 * @param runId the run id to resume under; {@code null} for a new one
	 * @param request the ChatClient request, whose options carry the tools of the allowed
	 * calls
	 * @param decisions the decisions; {@code null} when the caller moved on
	 * @return the resumed turn's run id, and the response to return without a model call
	 * when an allowed call's tool returns directly
	 * @throws IllegalStateException if decisions arrive for a session that is not parked
	 * on the tool confirmation of its newest reply, or if another caller answered the
	 * question first
	 * @throws IllegalArgumentException if the decisions do not fit the paused reply;
	 * nothing is written and the question stays open
	 */
	Resumed resume(String sessionId, @Nullable String runId, ChatClientRequest request,
			@Nullable ToolCallDecisions decisions) {
		SessionEvent pending = this.turns.pendingQuestion(sessionId);
		ConversationTail tail = ConversationTail.of(this.sessionService, sessionId);
		String refusal = refusal(sessionId, pending, tail);
		if (refusal != null) {
			if (decisions != null || pending == null) {
				throw new IllegalStateException(refusal);
			}
			logger.debug("{}; closing the question with an empty answer for the new text", refusal);
			return new Resumed(this.turns.resume(sessionId, runId, pending.getId(), null), null);
		}
		AssistantMessage paused = tail.reply();
		ToolCallDecisions effective = (decisions != null) ? decisions : abandoned(paused);
		ChatOptions options = withSessionIdInToolContext(request.prompt().getOptions(), sessionId);
		// Before anything is written: a rejected answer leaves the question open
		checkDecisions(effective, paused, options);
		// The question is checked again inside the write: an answer another caller
		// recorded first refuses this one, so the paused calls run once
		TurnStatus resumed = this.turns.resume(sessionId, runId, pending.getId(),
				ToolConfirmationQuestion.answer(effective));
		boolean stored = false;
		try {
			ToolExecutionResult result = run(sessionId, paused, effective, options);
			tail.storeResults(results(result));
			stored = true;
			if (result.returnDirect()) {
				// As a loop that ends on a returnDirect tool: the results are the
				// response
				return new Resumed(resumed,
						ChatResponse.builder().generations(ToolExecutionResult.buildGenerations(result)).build());
			}
		}
		catch (RuntimeException ex) {
			if (!stored) {
				// The answer is recorded; the calls get a result that says none was
				// recorded, so the log never keeps a call without one
				tail.answerUnresolved();
			}
			endOnFailure(sessionId, resumed);
			throw ex;
		}
		return new Resumed(resumed, null);
	}

	/**
	 * Why the session cannot resume through its tool confirmation, or {@code null} when
	 * it can: it is parked on the tool confirmation of its newest message.
	 */
	private static @Nullable String refusal(String sessionId, @Nullable SessionEvent pending, ConversationTail tail) {
		if (pending == null) {
			return "Session '" + sessionId + "' is not parked on a question; there is nothing to decide on";
		}
		Question question = Question.from(pending);
		if (!Question.KIND_TOOL_CONFIRMATION.equals(question.kind())) {
			return "Session '" + sessionId + "' is parked on a question of kind '" + question.kind()
					+ "', which tool call decisions do not answer";
		}
		ToolConfirmationQuestion asked = ToolConfirmationQuestion.from(question);
		if (!tail.isOpenReply() || !tail.event().getId().equals(asked.replyEventId())) {
			return "The reply session '" + sessionId
					+ "' parked on is not its newest message; the log changed since the turn parked";
		}
		return null;
	}

	/**
	 * The options with the session id in their tool context, when the advisor is set to
	 * put it there, as the Spring AI turn advisor does for the calls of the loop. A value
	 * the caller set under the key stands.
	 */
	private @Nullable ChatOptions withSessionIdInToolContext(@Nullable ChatOptions options, String sessionId) {
		if (!this.sessionIdInToolContext || !(options instanceof ToolCallingChatOptions toolCallingOptions)) {
			return options;
		}
		Map<String, Object> toolContext = toolCallingOptions.getToolContext();
		if (toolContext != null && toolContext.containsKey(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY)) {
			return options;
		}
		Map<String, Object> withSessionId = (toolContext != null) ? new HashMap<>(toolContext) : new HashMap<>();
		withSessionId.put(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId);
		return toolCallingOptions.mutate().toolContext(withSessionId).build();
	}

	/**
	 * Checks the decisions against the paused reply, with the rules the tool calling
	 * manager applies: every call needs a decision, every decision names a call, and an
	 * allowed call needs its tool among the request's tools.
	 */
	private static void checkDecisions(ToolCallDecisions decisions, AssistantMessage paused,
			@Nullable ChatOptions options) {
		List<String> keys = ToolCallDecisions.toolCallKeys(paused);
		Set<String> undecided = new LinkedHashSet<>(keys);
		for (ToolCallDecision decision : decisions.decisions()) {
			Assert.isTrue(keys.contains(decision.toolCallId()), () -> "No tool call of the paused reply has the key '"
					+ decision.toolCallId() + "'; the reply's tool call keys: " + keys);
			undecided.remove(decision.toolCallId());
		}
		Assert.isTrue(undecided.isEmpty(),
				() -> "Every tool call of the paused reply needs a decision; undecided: " + undecided);
		Set<String> tools = new HashSet<>();
		if (options instanceof ToolCallingChatOptions toolCallingOptions
				&& toolCallingOptions.getToolCallbacks() != null) {
			toolCallingOptions.getToolCallbacks().forEach(callback -> tools.add(callback.getToolDefinition().name()));
		}
		List<AssistantMessage.ToolCall> calls = paused.getToolCalls();
		for (int i = 0; i < calls.size(); i++) {
			ToolCallDecision decision = decisions.decisionOn(keys.get(i));
			if (decision != null && decision.allowed() && !tools.contains(calls.get(i).name())) {
				throw new IllegalArgumentException(
						"The request does not carry the tool '" + calls.get(i).name() + "' of the allowed call '"
								+ keys.get(i) + "'; pass the tools of the paused call with the decisions");
			}
		}
	}

	/**
	 * Runs the paused reply's calls as decided. The manager sees the turn so far, so its
	 * tool call limits count the rounds before the pause.
	 */
	private ToolExecutionResult run(String sessionId, AssistantMessage paused, ToolCallDecisions decisions,
			@Nullable ChatOptions options) {
		ChatResponse reply = new ChatResponse(List.of(new Generation(paused)));
		Prompt turnSoFar = new Prompt(this.sessionService.getActiveMessages(sessionId), options);
		return this.toolCallingManager.executeToolCalls(turnSoFar, reply, decisions);
	}

	/** The results of the run, one per call, in the order the model asked for them. */
	private static ToolResponseMessage results(ToolExecutionResult result) {
		List<Message> history = result.conversationHistory();
		if (history.isEmpty() || !(history.get(history.size() - 1) instanceof ToolResponseMessage results)) {
			throw new IllegalStateException("The tool calling manager returned no results for the decided tool calls");
		}
		return results;
	}

	/** Every call of the reply denied: the caller moved on without deciding. */
	private static ToolCallDecisions abandoned(AssistantMessage paused) {
		return new ToolCallDecisions(ToolCallDecisions.toolCallKeys(paused)
			.stream()
			.map(key -> ToolCallDecision.deny(key, TurnStatusAdvisor.ABANDONED_DENY_MESSAGE))
			.toList());
	}

	/** Ends the resumed turn with an error after the run failed; best-effort. */
	private void endOnFailure(String sessionId, TurnStatus turn) {
		try {
			this.turns.end(sessionId, turn, StopReason.ERROR);
		}
		catch (RuntimeException ex) {
			logger.warn("Could not end the resumed turn of session [{}] under run [{}] with {}: {}", sessionId,
					turn.runId(), StopReason.ERROR, ex.getMessage());
		}
	}

	/**
	 * A resumed turn.
	 *
	 * @param turn the resumed turn: the status its resume returned
	 * @param directResponse the response to return without a model call, when an allowed
	 * call's tool returned directly; {@code null} otherwise
	 */
	record Resumed(TurnStatus turn, @Nullable ChatResponse directResponse) {
	}

}
