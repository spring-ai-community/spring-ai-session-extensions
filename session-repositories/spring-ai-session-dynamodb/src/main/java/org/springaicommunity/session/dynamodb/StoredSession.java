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

import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.Session;

/**
 * A session as its item stores it: the session itself, and the counters only the
 * repository sees.
 *
 * @param eventVersion the version of its log
 * @param nextSeq the sequence number its next event gets
 * @param boundarySeq the sequence number of its newest compaction boundary, or
 * {@code null} if it was never compacted
 * @param revision the optimistic lock of saves and deletions
 * @param generation a random token of this incarnation of the session; its events' keys
 * carry it, so that a deletion finishing late never touches a session re-created under
 * the same id
 * @param indexEntries the index entries the session owns, as recorded on its item; a
 * deletion or a save removes exactly these
 * @param deleting whether a deletion has started; such a session counts as absent
 * @author Dominik Kovács
 */
record StoredSession(Session session, long eventVersion, long nextSeq, @Nullable Long boundarySeq, long revision,
		String generation, Set<String> indexEntries, boolean deleting) {
}
