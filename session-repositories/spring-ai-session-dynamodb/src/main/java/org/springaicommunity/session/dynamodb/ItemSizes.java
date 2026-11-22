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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Measures items as DynamoDB counts them against its limits, before they are sent: the
 * UTF-8 bytes of every attribute name and value, a number at its maximum of 21 bytes, a
 * boolean as 1 byte, and a string set as the UTF-8 bytes of its elements.
 *
 * @author Dominik Kovács
 */
final class ItemSizes {

	/** DynamoDB's limit per item: 400 KB. */
	static final int MAX_ITEM_BYTES = 400 * 1024;

	/**
	 * The limit of an event item, half of {@link #MAX_ITEM_BYTES}: the {@code seq} index
	 * projects every attribute, and an item shares the 400 KB limit with its entry in a
	 * local secondary index.
	 */
	static final int MAX_EVENT_ITEM_BYTES = MAX_ITEM_BYTES / 2;

	/** DynamoDB's limit on the items of one transaction: 4 MB. */
	static final int MAX_TRANSACTION_BYTES = 4 * 1024 * 1024;

	private static final int MAX_NUMBER_BYTES = 21;

	private ItemSizes() {
	}

	static int size(Map<String, AttributeValue> item) {
		int size = 0;
		for (Map.Entry<String, AttributeValue> attribute : item.entrySet()) {
			size += utf8Length(attribute.getKey()) + valueSize(attribute.getValue());
		}
		return size;
	}

	private static int valueSize(AttributeValue value) {
		return switch (value.type()) {
			case S -> utf8Length(value.s());
			case N -> MAX_NUMBER_BYTES;
			case SS -> value.ss().stream().mapToInt(ItemSizes::utf8Length).sum();
			case BOOL, NUL -> 1;
			default -> throw new IllegalArgumentException("The repository stores no " + value.type() + " attributes");
		};
	}

	private static int utf8Length(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

}
