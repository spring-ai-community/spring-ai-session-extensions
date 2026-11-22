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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

/**
 * A {@link DynamoDbClient} that passes every call the repository makes on to another
 * client. It counts table calls and the items that queries and batch reads return, and
 * runs a hook before each call, which can fail it. An answer function can answer batch
 * calls in place of the other client. The SDK's query paginator calls {@link #query}, so
 * its pages are counted too.
 *
 * @author Dominik Kovács
 */
class RecordingDynamoDbClient implements DynamoDbClient {

	private static final Consumer<SdkRequest> NO_HOOK = request -> {
		// Nothing runs before the call
	};

	private static final Function<SdkRequest, @Nullable SdkResponse> NO_ANSWER = request -> null;

	private final DynamoDbClient delegate;

	private final AtomicInteger tableCalls = new AtomicInteger();

	private final AtomicLong itemsRead = new AtomicLong();

	private final AtomicReference<Consumer<SdkRequest>> hook = new AtomicReference<>(NO_HOOK);

	private final AtomicReference<Function<SdkRequest, @Nullable SdkResponse>> answer = new AtomicReference<>(
			NO_ANSWER);

	RecordingDynamoDbClient(DynamoDbClient delegate) {
		this.delegate = delegate;
	}

	/**
	 * A hook that fails every {@code BatchWriteItem} call with an
	 * {@link SdkClientException}.
	 */
	static Consumer<SdkRequest> failBatchWrites() {
		return request -> {
			if (request instanceof BatchWriteItemRequest) {
				throw SdkClientException.create("Injected failure of BatchWriteItemRequest");
			}
		};
	}

	/**
	 * What the real service throws for a plain write of an item that a transaction holds.
	 */
	static TransactionConflictException conflict() {
		return TransactionConflictException.builder().message("Injected transaction conflict").build();
	}

	/**
	 * What the real service throws for a cancelled transaction: one reason per write, in
	 * order, such as {@code None}, {@code ConditionalCheckFailed} or
	 * {@code TransactionConflict}.
	 */
	static TransactionCanceledException cancellation(String... reasons) {
		return TransactionCanceledException.builder()
			.message("Injected cancellation")
			.cancellationReasons(
					Stream.of(reasons).map(code -> CancellationReason.builder().code(code).build()).toList())
			.build();
	}

	/** Runs the hook before every call, on the calling thread, until it is replaced. */
	void before(Consumer<SdkRequest> hook) {
		this.hook.set(hook);
	}

	/**
	 * Lets the function answer batch calls in place of the other client, until it is
	 * replaced. Where it returns {@code null}, the other client answers.
	 */
	void answer(Function<SdkRequest, @Nullable SdkResponse> answer) {
		this.answer.set(answer);
	}

	/** Removes both the hook and the answer function. */
	void clearHook() {
		this.hook.set(NO_HOOK);
		this.answer.set(NO_ANSWER);
	}

	/** How many {@code DescribeTable} and {@code CreateTable} calls were made. */
	int tableCalls() {
		return this.tableCalls.get();
	}

	/** How many items the queries and batch reads returned since the last reset. */
	long itemsRead() {
		return this.itemsRead.get();
	}

	void resetItemsRead() {
		this.itemsRead.set(0);
	}

	private <T extends SdkRequest> T call(T request) {
		this.hook.get().accept(request);
		return request;
	}

	/**
	 * The answer function's response to the request, cast to the given type, or else the
	 * other client's response.
	 */
	private <T extends SdkRequest, R extends SdkResponse> R answered(T request, Class<R> type,
			Function<T, R> delegate) {
		SdkResponse response = this.answer.get().apply(request);
		return (response != null) ? type.cast(response) : delegate.apply(request);
	}

	@Override
	public String serviceName() {
		return this.delegate.serviceName();
	}

	@Override
	public void close() {
		// The delegate belongs to the test support
	}

	@Override
	public DescribeTableResponse describeTable(DescribeTableRequest request) {
		this.tableCalls.incrementAndGet();
		return this.delegate.describeTable(call(request));
	}

	@Override
	public CreateTableResponse createTable(CreateTableRequest request) {
		this.tableCalls.incrementAndGet();
		return this.delegate.createTable(call(request));
	}

	@Override
	public GetItemResponse getItem(GetItemRequest request) {
		return this.delegate.getItem(call(request));
	}

	@Override
	public QueryResponse query(QueryRequest request) {
		QueryResponse response = this.delegate.query(call(request));
		this.itemsRead.addAndGet(response.count());
		return response;
	}

	@Override
	public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
		BatchGetItemResponse response = answered(call(request), BatchGetItemResponse.class,
				this.delegate::batchGetItem);
		this.itemsRead.addAndGet(response.responses().values().stream().mapToLong(List::size).sum());
		return response;
	}

	@Override
	public BatchWriteItemResponse batchWriteItem(BatchWriteItemRequest request) {
		return answered(call(request), BatchWriteItemResponse.class, this.delegate::batchWriteItem);
	}

	@Override
	public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
		return this.delegate.transactWriteItems(call(request));
	}

}
