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

/**
 * Why a turn ended. The {@link TurnEventTypes#STATUS} event that ends the turn records
 * it, and {@link TurnStatus#stopReason()} reports it while the session is idle.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public enum StopReason {

	/** The harness ran the turn to its end: the model answered. */
	END_TURN,

	/** The turn failed: the call threw, and the harness ended the turn with the error. */
	ERROR,

	/**
	 * The turn parked on a question and waits for an answer from outside: a person's
	 * decision on a tool call, for example. The {@link TurnEventTypes#QUESTION} event
	 * sits right before the {@code STATUS} event that records this reason and says what
	 * the turn waits on. Written by {@link TurnService#park}; {@link TurnService#resume}
	 * starts the turn again with the answer.
	 */
	REQUIRES_ACTION,

	/**
	 * An {@link TurnService#interrupt interrupt} ended the turn before it ran to its end.
	 * The {@link TurnEventTypes#INTERRUPT} event that asked for the stop sits right
	 * before the {@code STATUS} event that records this reason.
	 */
	INTERRUPTED,

	/**
	 * The client stopped listening before the turn ran to its end: it cancelled the
	 * stream, closed the connection or timed out. Nobody asked the session to stop, so no
	 * {@link TurnEventTypes#INTERRUPT} event goes with this reason.
	 */
	CANCELLED

}
