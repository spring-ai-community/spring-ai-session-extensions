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

import org.springaicommunity.session.turn.TurnStatus;

/**
 * Thrown by the {@link TurnInterruptAdvisor} before a model round when the call's turn no
 * longer runs, because an {@link org.springaicommunity.session.turn.TurnService#interrupt
 * interrupt} ended it. The exception unwinds the tool-calling loop. The
 * {@link TurnStatusAdvisor} outside the loop finds the turn already ended and leaves the
 * status alone, so the interrupt stands.
 * <p>
 * Catch it around {@code ChatClient.call()} to decide what an interrupted turn means for
 * the application. With {@code stream()} it arrives as the error signal of the
 * {@code Flux}. <pre>{@code
 * try {
 *     chatClient.prompt().user("...").call().content();
 * }
 * catch (TurnInterruptedException ex) {
 *     // ex.getStatus().stopReason() says why the turn ended
 * }
 * }</pre>
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public class TurnInterruptedException extends RuntimeException {

	private final String sessionId;

	private final transient TurnStatus status;

	/**
	 * Creates the exception.
	 * @param sessionId the session the turn ran on
	 * @param status the status of the session as read when the loop was stopped
	 */
	public TurnInterruptedException(String sessionId, TurnStatus status) {
		super("The turn of session '" + sessionId + "' no longer runs; the status is " + status.status()
				+ ((status.stopReason() != null) ? " (" + status.stopReason() + ")" : ""));
		this.sessionId = sessionId;
		this.status = status;
	}

	/** Returns the id of the session the turn ran on. */
	public String getSessionId() {
		return this.sessionId;
	}

	/** Returns the status of the session as read when the loop was stopped. */
	public TurnStatus getStatus() {
		return this.status;
	}

}
