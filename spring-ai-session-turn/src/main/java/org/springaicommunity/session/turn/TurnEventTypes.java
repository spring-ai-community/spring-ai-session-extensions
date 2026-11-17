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

import org.springframework.ai.session.EventType;

/**
 * The event types of a session's turns. Each name starts with {@code TURN.}, so that it
 * never clashes with an event type Spring AI adds later.
 * <p>
 * None of them carries a message. The session's active view holds message events only, so
 * these events never reach a prompt and a compaction never covers them. They stay in the
 * log as the record of the session's turns.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public final class TurnEventTypes {

	/**
	 * A turn status transition: a turn started on the session, or ended with a
	 * {@link StopReason}. The newest one is the session's {@link TurnStatus}.
	 * {@link SessionStatusEvents} builds these events.
	 */
	public static final EventType STATUS = EventType.of("TURN.STATUS");

	/**
	 * A request to stop the turn that runs on the session, recorded by
	 * {@link TurnService#interrupt}. When a turn runs, a {@link #STATUS} event in the
	 * same append ends it.
	 */
	public static final EventType INTERRUPT = EventType.of("TURN.INTERRUPT");

	/**
	 * A question the turn parked on, waiting for an answer from outside: a person's
	 * decision on a tool call, for example. Recorded by {@link TurnService#park} with the
	 * {@link #STATUS} event that parks the turn. {@link QuestionEvents} builds these
	 * events and {@link Question} reads them.
	 */
	public static final EventType QUESTION = EventType.of("TURN.QUESTION");

	/**
	 * The answer to a {@link #QUESTION}, pointing at it by event id. Recorded by
	 * {@link TurnService#resume} with the {@link #STATUS} event that starts the turn
	 * again. {@link Answer} reads it.
	 */
	public static final EventType ANSWER = EventType.of("TURN.ANSWER");

	private TurnEventTypes() {
	}

}
