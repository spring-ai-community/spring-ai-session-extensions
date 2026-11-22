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

package org.springaicommunity.session.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.LongStream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Concurrent writers of one session.
 *
 * @author Dominik Kovács
 */
class DynamoDbSessionRepositoryConcurrencyTests {

	private static final int THREADS = 8;

	private final DynamoDbClient client = DynamoDbTestSupport.client();

	private final String tableName = DynamoDbTestSupport.newTableName();

	private final DynamoDbSessionRepository repository = DynamoDbSessionRepository.builder()
		.dynamoDbClient(this.client)
		.tableName(this.tableName)
		.build();

	@Test
	void concurrentSavesLeaveIndexEntriesForTheFinalPrincipalOnly() throws Exception {
		String id = UUID.randomUUID().toString();
		String owner = "owner-" + UUID.randomUUID() + "-";
		this.repository.save(principal(id, owner + "start"));

		run(i -> this.repository.save(principal(id, owner + i)));

		String finalOwner = SessionAttributes.principalName(Objects.requireNonNull(this.repository.findById(id)));
		for (int i = 0; i < THREADS; i++) {
			String candidate = owner + i;
			int expected = candidate.equals(finalOwner) ? 1 : 0;
			assertThat(this.repository.findByMetadata(SessionAttributes.PRINCIPAL_NAME, candidate)).as(candidate)
				.hasSize(expected);
			assertThat(indexEntries(candidate)).as("index entries of %s", candidate).hasSize(expected);
		}
		assertThat(indexEntries(owner + "start")).isEmpty();
	}

	@Test
	void concurrentAppendsAllLandWithDistinctSequenceNumbers() throws Exception {
		Session session = this.repository.save(principal(UUID.randomUUID().toString(), "appender"));
		int perThread = 5;

		run(i -> {
			for (int j = 0; j < perThread; j++) {
				this.repository.appendEvent(DynamoDbTestSupport.userEvent(session.id(), i + "-" + j));
			}
		});

		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(THREADS * perThread);
		List<Map<String, AttributeValue>> events = this.client
			.query(request -> request.tableName(this.tableName)
				.indexName(SessionTable.SEQ_INDEX)
				.keyConditionExpression("pk = :pk")
				.expressionAttributeValues(Map.of(":pk", SessionItems.sessionPk(session.id())))
				.consistentRead(true))
			.items();
		assertThat(events).extracting(SessionItems::seq)
			.containsExactlyElementsOf(LongStream.range(0, THREADS * perThread).boxed().toList());
		// Each writer's events keep its own order
		List<String> texts = this.repository.findEvents(session.id(), EventFilter.all())
			.stream()
			.map(DynamoDbTestSupport::text)
			.toList();
		for (int i = 0; i < THREADS; i++) {
			String writer = i + "-";
			assertThat(texts.stream().filter(text -> text.startsWith(writer)).toList()).containsExactly(writer + 0,
					writer + 1, writer + 2, writer + 3, writer + 4);
		}
	}

	@Test
	void writerThatKeepsLosingGivesUpAfterSixteenAttempts() {
		RecordingDynamoDbClient recording = DynamoDbTestSupport.recordingClient();
		DynamoDbSessionRepository contended = DynamoDbSessionRepository.builder()
			.dynamoDbClient(recording)
			.tableName(this.tableName)
			.initializeTable(false)
			.build();
		String id = UUID.randomUUID().toString();
		contended.save(principal(id, "first"));
		AtomicInteger attempts = new AtomicInteger();
		// Right before every attempt's transaction, another writer moves the revision on
		recording.before(request -> {
			if (request instanceof TransactWriteItemsRequest) {
				attempts.incrementAndGet();
				this.client.updateItem(update -> update.tableName(this.tableName)
					.key(SessionItems.sessionKey(id))
					.updateExpression("SET revision = revision + :one")
					.expressionAttributeValues(Map.of(":one", SessionItems.number(1))));
			}
		});

		Session second = principal(id, "second");

		assertThatIllegalStateException().isThrownBy(() -> contended.save(second))
			.withMessage("Session '" + id + "' stayed contended after 16 attempts");
		assertThat(attempts).hasValue(16);
		assertThat(SessionAttributes.principalName(Objects.requireNonNull(this.repository.findById(id))))
			.isEqualTo("first");
	}

	private List<Map<String, AttributeValue>> indexEntries(String principal) {
		return this.client
			.query(request -> request.tableName(this.tableName)
				.keyConditionExpression("pk = :pk")
				.expressionAttributeValues(
						Map.of(":pk", SessionItems.indexPk(SessionAttributes.PRINCIPAL_NAME, principal)))
				.consistentRead(true))
			.items();
	}

	private static Session principal(String id, String principal) {
		return Session.builder().id(id).metadata(SessionAttributes.PRINCIPAL_NAME, principal).build();
	}

	/**
	 * Runs the task once on each thread, all at the same moment, and fails if a run
	 * fails.
	 */
	static void run(IntConsumer task) throws Exception {
		try (Workers workers = new Workers()) {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> runs = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				int index = i;
				runs.add(workers.submit(() -> {
					start.await();
					task.accept(index);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> run : runs) {
				run.get(60, TimeUnit.SECONDS);
			}
		}
	}

	/** The test's threads; closing interrupts the ones still running. */
	private static final class Workers implements AutoCloseable {

		private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		<T extends @Nullable Object> Future<T> submit(Callable<T> task) {
			return this.executor.submit(task);
		}

		@Override
		public void close() {
			this.executor.shutdownNow();
		}

	}

}
