# Redis Session Repository

`spring-ai-session-redis` is a Redis-based `SessionRepository` for the Spring AI session: sessions and their events are kept in Redis.
It works with `DefaultSessionService` and the session advisors unchanged, and passes the same contract suite as the JDBC store.
It lets a team that already runs Redis keep agent memory there, with no extra database.

!!! note
    Experimental, tested on a single standalone Redis 7.4 container.
    Redis Cluster and Sentinel testing, production hardening and the Boot starter are still to come.
    The data layout is not versioned yet, so data written by one 0.x release may not be readable by the next.

## How it works

Redis runs one command or script at a time on a shard.
Every write rule of the session contract is per session: create-if-absent, the version check, idempotent append, delete.
So each write that checks a rule runs as one Lua script on the one key that holds the session, and nothing else can run between its check and its write.
The script plays the part a row lock plays for JDBC.

```
 any instance                     Redis shard that owns the session's slot
+-----------------+  1 script   +-----------------------------------+
| RedisSession-   |------------>| 2 check the rules, then write     |
| Repository      |<------------|   ai:session:s:<id>  (one hash)   |
|                 |   result    +-----------------------------------+
|                 |
|                 |  3 hints    +-----------------------------------+
|                 |------------>| ai:session:idx:<hash>   user index|
|                 |             | ai:session:expiries     expiry set|
+-----------------+             +-----------------------------------+
```

1. The repository calls the script with `EVALSHA` through Spring Data Redis, which loads the script again if the server doesn't have it.
2. The script checks everything before it writes anything, because Redis doesn't roll a script back when it fails halfway.
3. The user index and the expiry set are separate keys, written before or after the script as hints.

Reads are plain `HMGET` calls, with no script, and so is `delete`, which has no rule to check.
The log only grows and its positions have no gaps, so reading the `next` position first and then the events below it is a consistent snapshot, as long as the session isn't deleted during the read.
An active-view read starts at the newest compaction boundary and adds the events it names, as JDBC does.

Any application instance talks to Redis directly.
No conversation state is kept on the caller, so which instance serves an HTTP request doesn't matter.

### Data layout

Each session is one hash, `ai:session:s:<id>`:

| Field | Content |
|---|---|
| `created`, `expires` | Times in epoch microseconds; no `expires` means the session never expires |
| `metadata` | The session metadata as JSON |
| `version` | The log version that `appendEvents` checks |
| `next` | The position the next event gets |
| `boundary` | The position of the newest `COMPACTION` event |
| `e:<seq>` | One event, encoded with `SessionEventCodec` |
| `i:<eventId>` | The position of that event, for spotting retries |

Because a session is one key, it lives in one cluster slot, one `UNLINK` erases it, and Redis can't evict half of it.
Event ids are scoped to their session, which the contract tests allow, though the `appendEvent` Javadoc asks for ids unique across sessions.

Two more keys sit outside the sessions:

| Key | Type | Content |
|---|---|---|
| `ai:session:idx:<hash of name and value>` | sorted set | The ids of the sessions with that metadata value, scored by creation time |
| `ai:session:expiries` | sorted set | The ids of the sessions that expire, scored by expiry time |

The `s:` in a session's key keeps it apart from these two, whatever the session id.

In a cluster these keys are in other slots, so they can't be changed in the same step as the session.
A write adds an entry before it changes the session and removes a stale one after, so a crash in between leaves an extra entry, never a missing one.
A read checks every entry against the session itself.
Spring Session's Redis repository keeps its principal index the same way.

### Why scripts

Five of the contract's rules check something and then write: create a session only once, update a session while keeping its creation time and log position, skip an event id that is already stored, append a batch only if the version still matches, and delete a session only if it is still expired.
Each check and its write must happen as one step.
Otherwise, for example, a crash between marking an event id as seen and storing the event would lose the event, and the retry would be skipped as a duplicate.

Redis has two ways to run several commands as one step: a Lua script, or a `WATCH`/`MULTI` transaction.
Spring Data Redis doesn't run `MULTI` on a cluster connection, and a transaction would also need a retry loop and a pinned connection, so the store uses scripts.

| Script | Rule it keeps |
|---|---|
| `save-if-absent.lua` | Create the session only if it doesn't exist |
| `save.lua` | Update the session, keeping its creation time, version and next position |
| `append-event.lua` | Append one event, unless its id is already stored |
| `append-events.lua` | Append a batch only if the version still matches |
| `delete-if-expired.lua` | Delete the session only if it is still expired |

The scripts are short and use only Redis commands, the same ones the Java code would otherwise send.
They run through Spring Data Redis: each is a `RedisScript`, called with `StringRedisTemplate.execute`, which sends `EVALSHA` and falls back to the full script when Redis doesn't have it cached.
Other Spring projects use scripts for the same reason, such as the Redis rate limiter of Spring Cloud Gateway and the `RedisLockRegistry` of Spring Integration.

## Using it

Add the dependency, with its version from the [BOM](../index.md#installation), and a Redis driver such as `lettuce-core` or `jedis`:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-redis</artifactId>
</dependency>
```

```java
RedisSessionRepository repository = RedisSessionRepository.builder()
    .connectionFactory(redisConnectionFactory)
    .build();

SessionService sessions = DefaultSessionService.builder()
    .sessionRepository(repository)
    .build();
```

Any `RedisConnectionFactory` works: Lettuce or Jedis, standalone, Sentinel or cluster.
In a Spring Boot app, the one Boot creates from `spring.data.redis.*` is the usual choice.
It must read from the primary: a replica lags behind, so the builder rejects a Lettuce factory set to read from replicas.

| Option | Default | Meaning |
|---|---|---|
| `connectionFactory` | required | The connection to Redis |
| `indexedAttributes` | the principal name | The metadata keys `findByMetadata` answers for; the principal name is always included, and a lookup on any other key finds nothing |
| `keyPrefix` | `ai:session:` | The prefix of every key; two repositories on one Redis need different prefixes, and neither may start with the other |

Sessions get no Redis TTL.
Call `deleteExpiredSessions(Instant.now())` on a schedule to delete expired sessions with their logs and index entries.

## Guarantees

- **Atomic writes.** A session is created only once, a retried append is skipped, and `appendEvents` appends its whole batch or nothing.
- **Consistent reads.** A read sees the log as it stood at one moment, never part of a batch, unless the session is deleted during the read.
- **One shard per session.** A session's data never spans shards, so its writes need no coordination between shards on Redis Cluster.
- **Erasure.** `delete` removes the session and its whole log in one step, then its index and expiry entries.

## Limits

- **Memory.** Everything stays in memory, and the log only grows: compaction appends a summary and never removes older events. For example, 100 events of 2 KB text take about 400 KB, so 100,000 such sessions take about 40 GB.
- **Durability.** Redis acknowledges a write from memory. With AOF `everysec`, a restart can lose up to a second of writes, and replication is asynchronous, so a failover can lose a write Redis already acknowledged.
- **Large sessions.** A long session is one large hash on one shard; it can't spread out and slows slot migration.
- **Whole-log reads.** Filters other than the active view read the whole log. `searchEvents` uses the default from `SessionRepository`, which reads the full history and filters it in the caller.
- **Index reads.** `findByMetadata` loads every session in the index entry, and the expiry sweep reads every entry up to the cut-off.
- **Concurrent saves of one session.** Two saves of the same session at once, one changing an indexed value or the expiry, can leave the index or the expiry set without its entry, or with an expiry later than the session's own. Then `findByMetadata` misses the session, or the sweep deletes it late or never. Avoiding this would need the indexes in the session's own script, which a cluster doesn't allow.
- **Retries after a reconnect.** Lettuce, by default, sends a command again when the connection drops before the reply arrives. So `saveIfAbsent` can return `false` for a session it created, and `appendEvents` can report a version conflict for a batch it appended. A retried `appendEvent` is safe, because the store skips an event id it already has.
- **Re-used ids.** If a session is deleted and created again with the same id while another call is still running, that call can remove the new session's index or expiry entries, or a long read can mix events of the old and the new session. `DefaultSessionService` creates random UUIDs, so this needs a caller that re-uses ids.
- **Stale entries stay.** Reads skip index and expiry entries that no longer match their session. Saves and deletes remove the entries they replace, but some entries left by failed or racing calls are never removed, and the sweep checks such an expiry entry on every run.
- **Microsecond times.** A session's creation and expiry times are stored to the microsecond; event times keep their full precision. Two sessions created within the same microsecond are listed by id, and a sweep whose cut-off is very close after an expiry may keep that session until the next sweep.
- **No JDBC transaction**, like any non-JDBC store.

## Operations

- Redis 7 or later, or a compatible server such as Valkey; no modules are needed.
- `maxmemory-policy noeviction`, or Redis may evict live sessions. When Redis is out of memory, new writes fail, but `delete` and the expiry sweep still run.
- AOF persistence (`appendonly yes`) if losing the last second of conversations on a restart matters.
- A scheduled call to `deleteExpiredSessions`, from any one instance.
- With Redis ACLs, a user allowed to run `EVAL`, `EVALSHA`, `EXISTS`, `UNLINK` and the hash and sorted set commands, for example `+@scripting +@hash +@sortedset +exists +unlink`. A script stopped halfway by a missing permission is not rolled back.

The store's keys are internal; nothing else should write to them.

## Tests

| Suite | What it covers |
|---|---|
| `RedisSessionRepositoryIT` | The shared contract suite, the data layout, and index entries a crash can leave behind |
| `SessionMemoryAdvisorRedisIT` | The session advisor's hooks across simulated tool rounds, with constructed model responses |

Both run on a plain Redis 7.4 container with Testcontainers, so they need Docker and run only with `-Pintegration-tests`.
CI doesn't run them yet.
They don't cover Redis Cluster, Sentinel, failover or production latency.

```shell
./mvnw verify -Pintegration-tests -pl session-repositories/spring-ai-session-redis -am
```

## Design notes

- Scripts only where a rule needs a check and a write in one step; see [Why scripts](#why-scripts).
- One hash per session rather than a key per event: one slot, one `UNLINK`, and nothing for eviction or slot migration to split.
- No native TTL: the contract counts sessions that had already expired when the sweep runs, and Redis would have removed them first.
- Index keys hash the name and value, so a long value makes a short key; reads compare the real value.
- Spring Data Redis rather than a Redis client directly, so the store picks up Boot's settings and runs on Lettuce or Jedis, standalone, Sentinel or cluster.

## Still open

- A Redis Cluster integration test.
- Hint-style indexes against a standalone-only mode that updates the indexes in the same script.
- A policy for trimming old events, since compaction doesn't free memory.
- A startup warning when the server's `maxmemory-policy` is not `noeviction`.
- Bounded reads for filters other than the active view, and a native `searchEvents`.
- Removing stale index and expiry entries.
- A versioning policy for the data layout.
- A Boot starter.
