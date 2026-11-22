# DynamoDB Session Repository

`spring-ai-session-dynamodb` is a `SessionRepository` for the Spring AI session on Amazon DynamoDB: sessions and their events are kept in one DynamoDB table.
It works with `DefaultSessionService` and the session advisors unchanged, and passes the same contract suite as the JDBC store.
It lets a team on AWS keep agent memory in a managed, serverless store.

!!! note
    Experimental, tested on DynamoDB Local and checked once against the real service.
    The Boot starter is still to come.
    The stored format is not versioned yet, so data written by one 0.x release may not be readable by the next.

## How it works

One table holds three kinds of item:

| Item | `pk` | `sk` | Holds |
|---|---|---|---|
| Session | `S#<session id>` | `SESSION` | creation time, expiry and its shard, metadata (JSON), the log's version, the next sequence number, the newest compaction boundary, a revision for saves, its generation, the index entries it owns, and a deleting mark during a deletion |
| Event | `S#<session id>` | `E#<generation>#<event id>` | its sequence number, timestamp, type, message type and text, payload (JSON: a message's whole body, or the event's own payload), metadata (JSON) |
| Index entry | `I#<SHA-256 of name and value>` | `<creation time>#<session id>` | nothing: the key is the entry |

Two indexes serve the reads that don't go by key:

- **`seq`**, a local secondary index on (`pk`, `seq`), orders a session's events. Its reads are strongly consistent.
- **`expiry`**, a sparse global secondary index on (`expiryShard`, `expiresAt`), lists the sessions that expire. It covers only sessions with an expiry, and spreads them over 16 shards so that no key is hot.

Every session and event write is one `TransactWriteItems`, under a condition. `saveIfAbsent` commits first and reads the session item only if its condition fails; the other writes read the session item first.

- **`saveIfAbsent`** puts the session if no item has its id, and writes its index entries in the same transaction. The session item records the entries it owns.
- **`save`** updates the metadata and expiry under the session's revision. It deletes the recorded index entries the session no longer has, puts the new ones, and records the result. A `save` of an id that isn't stored inserts it, as `saveIfAbsent` does.
- **`appendEvent`** advances the version and the sequence number under the version it read, and puts the event if its id isn't in the log yet. If the put fails while the session's condition holds, the event is a replay, and the call is a no-op.
- **`appendEvents`** advances the version once and puts up to 99 events, under the version the caller passed. A different version returns `false`, and an event id already in the log fails the call with an `IllegalArgumentException`. In both cases nothing is written.

Event ids are unique within a session, not across sessions, because an event's key holds its session's id. An id that another session already uses is stored, where the JDBC store throws an `IllegalStateException`.

A write that loses a race reads again and retries after a random pause of at least 1 ms, whose upper bound doubles up to 100 ms. A transaction cancelled by a conflict with another one counts as a lost race too. After 16 lost races in a row the call fails with an `IllegalStateException`.

Reads:

- `findById`, `getEventVersion` and `findByMetadata` read strongly consistent. `findByMetadata` queries the index entries and then checks each session's own metadata.
- An active-view read queries the `seq` index from the newest boundary on, fetches the older events the boundary names by key, and lets `EventFilter.apply` build the view, as the JDBC store does. A `lastN` window on the active view, which is what `SessionMemoryAdvisor` reads, is cut from that view in memory.
- A `lastN` read of the whole log goes backwards through it until it has the window and its turn.

A deletion takes three steps:

1. One transaction marks the session as deleting, moves its expiry to now, and removes the index entries its item records. From this moment the session is gone for every reader and writer.
2. The events are removed with `BatchWriteItem`, 25 per call. Writes DynamoDB leaves unprocessed, or that conflict with a transaction, are sent again after a random pause of at least 16 ms, whose upper bound doubles up to a second, at most 16 attempts per call. After that the deletion fails with an `IllegalStateException` and stays halfway.
3. The session item is removed, in a transaction of one write.

The reads by key, in `findByMetadata` and the active view, send again the keys `BatchGetItem` leaves unprocessed in the same way.

Every session item carries a random generation, written when it is created, and its events' keys carry the same one. Steps 2 and 3 touch only that generation, so a caller that finishes a deletion late never touches a session re-created under the same id, even one that reuses an event id.

A deletion that stops halfway is finished by the next `delete`, by the next `deleteExpiredSessions`, or by a new `save` or `saveIfAbsent` of the same id.

## Using it

Add the dependency, with its version from the [BOM](../index.md#installation):

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-dynamodb</artifactId>
</dependency>
```

```java
DynamoDbSessionRepository repository = DynamoDbSessionRepository.builder()
    .dynamoDbClient(dynamoDbClient)   // the application's client
    .tableName("my-app-sessions")
    .build();                         // creates the table if it is missing

SessionService sessions = DefaultSessionService.builder()
    .sessionRepository(repository)
    .build();
```

| Option | Default | Meaning |
|---|---|---|
| `dynamoDbClient` | required | The application's `DynamoDbClient`, with its region, credentials, endpoint and retry policy. The repository never closes it. |
| `tableName` | `spring-ai-session` | The table. Sessions stay in the table they were saved to, so a new name starts on an empty table. |
| `indexedAttributes` | the principal | The metadata keys `findByMetadata` answers for, besides the principal, which is always indexed. At most 32 keys, the principal included. Any other key returns an empty list. A key added later finds only the sessions saved after it. |
| `initializeTable` | `true` | Whether `build()` creates a missing table (on demand billing) and checks an existing one. When off, `build()` makes no table calls. |
| `jsonMapper` | a default `JsonMapper` | The mapper of event payloads and metadata. |

Nothing calls `deleteExpiredSessions` on its own: schedule it, every hour for example. Its count includes the deletions that had stopped halfway and that it finished. When a session or a shard fails, the sweep goes on with the others and then throws the first failure, with the later ones suppressed. A failure while the thread is interrupted, or one caused by an interrupt, stops it at once, and the thread is left interrupted.

### Creating the table yourself

With `initializeTable(false)`, create the table with Terraform, CDK or CloudFormation. It needs:

- **Key:** `pk` (String, HASH), `sk` (String, RANGE).
- **Attributes:** `pk` S, `sk` S, `seq` N, `expiryShard` S, `expiresAt` N.
- **Local secondary index `seq`:** `pk` (HASH), `seq` (RANGE), projection `ALL`.
- **Global secondary index `expiry`:** `expiryShard` (HASH), `expiresAt` (RANGE), projection `KEYS_ONLY` or more.

A local secondary index can only be created with the table.

## Guarantees

- **Atomic writes.** A session and its index entries, and a batch of events with the version, change in one transaction or not at all. A read sees a batch whole once it has committed, but a read that overlaps the commit may see part of it: DynamoDB's `Query` is read-committed, not serializable.
- **Read your writes.** Every read the contract relies on is strongly consistent. Only the expiry sweep reads an eventually consistent index, and each deletion it makes re-checks the expiry in its condition.
- **Exactly one winner** of concurrent creations of an id, and of concurrent `appendEvents` on one version.
- **Deletions complete.** A deletion that stops halfway leaves the session invisible, and is finished by the next deletion, sweep, `save` or `saveIfAbsent` of the same id.

## Limits

- **Event size.** An event item can be at most 204,800 bytes. The `seq` index projects every attribute, and DynamoDB counts an item and its local index entry together against its 400 KB limit. The codec stores a message's text twice, so this is roughly 100 KB of text per event. A larger event fails with an `IllegalArgumentException`, and nothing is written.
- **Session size.** A session item, mostly its metadata and the index entries it records, can be at most 409,600 bytes, less 96 bytes kept for what it gains later. A larger session fails with an `IllegalArgumentException`.
- **Batch size.** `appendEvents` takes at most 99 events and about 3.6 MB of events: one transaction holds 100 items and 4 MB, and room is kept for the session item it also updates. A compaction appends one boundary and a few summary events, well within this.
- **Session size in total.** All items of one session together are limited to 10 GB, the limit of a table with a local secondary index. The `seq` index holds a copy of every event, and the copies count too, so this is about 5 GB of events. A write that would take a session past the limit fails with a `TransactionCanceledException` whose cancellation reason is `ItemCollectionSizeLimitExceeded`. The session can still be read. It can still be deleted too, unless it has less room left than the few bytes a deletion's first step adds to the session item.
- **Id length.** Event ids up to 985 bytes (UTF-8), the room their keys leave. Session ids up to 994 bytes, because the key of an index entry holds the id. A session that never has an indexed value, not even the principal, can have an id of up to 2,046 bytes. DynamoDB rejects longer ids.
- **One region.** Global tables replicate last-writer-wins, so the conditions don't hold across regions; write each session in one region.
- **Search reads the whole log.** `searchEvents` uses the default from `SessionRepository`, which filters the full history in the caller. DynamoDB can't match text case-insensitively in a query.
- **The sweep is sequential.** `deleteExpiredSessions` deletes one session at a time.
- **No JDBC transaction**, as with any store that isn't JDBC.

## Operations

- **IAM.** `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchGetItem` and `BatchWriteItem`, on the table's ARN and on `table/<name>/index/*`. `TransactWriteItems` needs no permission of its own: each write in a transaction is authorized as the action it contains, a put as `PutItem`, an update as `UpdateItem` and a deletion as `DeleteItem`. `initializeTable(true)` also needs `DescribeTable` and `CreateTable`.
- **Cost.** Appending an event is one strongly consistent read of the session item and one transaction of two writes: the session item's update and the event's put. A transactional write costs twice the write units of a normal one, and the event's put also writes its entry in the `seq` index. Whenever an event is appended, the session item is read at its full size and written at its full size, doubled, so large metadata makes every appended event dearer. Measured on the real service, with small items:

    | Call | Read units | Write units |
    |---|---|---|
    | Appending an event | 1 | 5: 4 for the transaction, 1 for the `seq` index entry |
    | Appending a turn's user message and reply | 2 | 10 |
    | A `save` of a new session with a principal and an expiry | 1 | 5: 4 for the transaction (the session item and the principal's index entry), 1 for the `expiry` index entry |
    | A `save` that moves the principal and extends the expiry | 1 | 8: 6 for the transaction (the session item, the old entry's deletion and the new entry's put), 2 for moving the `expiry` index entry |
    | Reading the active view or `lastN` | 2 | 0 |

    `SessionService.create` goes through `saveIfAbsent`, which writes the same as the `save` of a new session but skips its read. Through `SessionMemoryAdvisor`, a turn also reads the session and its active view: 5 read units or more.

- **Deleting a session costs up to about two thirds of what appending its events did.** The query of its event keys is billed for whole items, and deleting an event also deletes its `seq` index entry. A session with one 200 KB event cost 51 read units and 408 write units to delete.
- **Errors** from the AWS SDK (throttling after the client's retries, a missing table, denied access) pass through as `DynamoDbException` or `SdkClientException`. Work that DynamoDB keeps leaving unprocessed for 16 attempts, and a thread interrupted during a pause, fail with an `IllegalStateException`.

## Tests

| Suite | What it covers |
|---|---|
| `DynamoDbSessionRepositoryTests` | The shared contract suite |
| `SessionMemoryAdvisorDynamoDbTests` | `SessionMemoryAdvisor` on the repository, inside a tool-calling loop: history read back from DynamoDB is not sent again, earlier turns are prepended once, and a `lastN` window keeps a tool turn whole |
| `SessionTableTests`, `DynamoDbSessionRepositoryBuilderTests` | Creating and checking the table, the builder options |
| `SessionItemsTests`, `ItemSizesTests` | The item encoding, and item sizes as DynamoDB counts them |
| `DynamoDbSessionRepositorySessionTests` | A save that removes an expiry, `findByMetadata` over more than one batch, index entries of values that read back differently from JSON, of a changed configuration, and listed twice |
| `DynamoDbSessionRepositoryConcurrencyTests` | Concurrent saves and event appending on one session, a writer that gives up after 16 lost races |
| `DynamoDbSessionRepositoryReadsTests` | Reads of the active view and of `lastN` that fetch only what their view or window can contain, and a compaction that lands during a read |
| `DynamoDbSessionRepositoryDeleteTests` | Large deletions, deletions that stop halfway, the sweep racing an extension or another caller, a sweep that goes on past failures and stops on an interrupt, late finishers and writes racing a session re-created under the same id |
| `DynamoDbSessionRepositoryLimitsTests` | The event and session item limits at the limit and one byte over, the 99-event limit, and the batch byte limit under and over it |
| `DynamoDbSessionRepositoryFailureTests` | What only the real service does, injected: transactions cancelled by a conflict or an error, writes that conflict with a transaction, batch calls that leave work unprocessed |

All run on an embedded, in-memory DynamoDB Local; no Docker and no AWS account are needed.
They don't cover the real service's latency, throttling or eventual consistency of the expiry index.
A one-off check against the real service, which is not part of the build, confirmed the IAM list, the 200 KB event limit and the costs above.

```shell
./mvnw verify -pl session-repositories/spring-ai-session-dynamodb -am
```

## Design notes

Choices the design makes, and why:

- **No native TTL.** DynamoDB's TTL deletes items asynchronously, doesn't cascade from a session to its events, and extending an expiry would mean rewriting every event. The sweep and its sparse `expiry` index delete a session with its events, under a condition that keeps an extended session.
- **The `seq` index projects every attribute.** A read of the log is then served by the index alone; with fewer attributes projected, DynamoDB would fetch every event from the table as well, at extra read cost. The price is the 200 KB event limit: an item and its local index entry share the 400 KB item limit.
- **Index entries are keyed on a hash** of the metadata name and value, so a value of any length fits in a key. `findByMetadata` checks each session's own value, which settles a collision.
- **No `FilterExpression` search.** DynamoDB can't match text case-insensitively, and a filter bills for every item it reads. `searchEvents` filters in the caller, and `lastN` applies the `EventFilter` in Java, so its result agrees with the reference implementation.
- **The generation is in the event keys.** `BatchWriteItem` can't take conditions, so a deletion that finishes late deletes by key. With the generation in the key, it can only reach the events of its own generation.
- **The session item records its index entries.** Deletions and saves remove exactly the recorded entries instead of computing them again. A change of `indexedAttributes`, during a rolling deploy for example, or a value that reads back differently from JSON then leaves no entry behind.
- **The session item is removed in a transaction.** On the real service, a plain deletion of an item that another transaction holds fails with `TransactionConflictException`, which the SDK doesn't retry. In a transaction, the conflict is a cancellation reason, and the repository retries it as a lost race.

## Still open

- A Boot starter with auto-configuration.
- A parallel expiry sweep.
- A native `searchEvents`.
- Larger events, for example by keeping big payloads in S3.
- A versioning policy and compatibility fixtures for the stored format.
- Reads that never see part of a batch, for example by bounding event reads by the `nextSeq` read with the session.
- A minimum page size for small `lastN` windows on the whole log, which today read pages of twice the window.
