# Spring AI Session Extensions

Add-ons for the session support in [Spring AI](https://docs.spring.io/spring-ai/reference/) 2.1.

Spring AI 2.1 has the session itself: the event log, the stores, the `ChatClient` advisors, compaction and recall.
This project adds features on top of it and changes nothing in it.
It uses only the public extension points of the session: event types of its own, version-checked appends, and advisors around the session advisors.

> **Status: experimental.** The API can change between versions.

## Requirements

- Java 17 or later
- Spring AI 2.1

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **Session Turn** | `spring-ai-session-turn` | The `TurnService`: it starts, ends, interrupts, parks and resumes the turns of a session, and records each change as a `TURN.*` event in the session's log. It works with any Spring AI session store and does not depend on the `ChatClient`. |
| **Session Turn Advisor** | `spring-ai-session-turn-advisor` | The `ChatClient` binding of the turn. The `TurnStatusAdvisor` runs every call as a turn, parks a turn whose tool call waits for a person's confirmation, and resumes it from the decisions. The `TurnInterruptAdvisor` stops the tool loop of an interrupted call. `TurnAdvisors` builds the whole advisor chain. |
| **Session Kafka** | `spring-ai-session-kafka` | A `SessionRepository` that keeps sessions in Kafka, for teams that already run Kafka and want agent memory there, with no extra database. Each session has one owner that handles its calls one at a time, and the state is backed by compacted topics. It passes the Spring AI session repository contract tests. |
| **Session Redis** | `spring-ai-session-redis` | A `SessionRepository` that keeps sessions in Redis, for teams that already run Redis and want agent memory there, with no extra database. Each session is one key, and every write to it is atomic, so a session lives on one shard. It passes the Spring AI session repository contract tests. |
| **BOM** | `spring-ai-session-extensions-bom` | Bill of Materials for the module versions. |

Stores beyond the ones in Spring AI go into [`session-repositories`](session-repositories/README.md).

The group id is `org.springaicommunity`. The Java packages start with `org.springaicommunity.session`.

[Turn Status](docs/turn/turn-status.md) explains the turn service, and [Turns in the ChatClient](docs/turn/chat-client.md) the `ChatClient` binding.
[Kafka Session Repository](docs/session-repositories/kafka.md) explains the Kafka store.
[Redis Session Repository](docs/session-repositories/redis.md) explains the Redis store.

## Installation

The artifacts are on Maven Central.
Import the BOM, so that the modules need no version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session-extensions-bom</artifactId>
            <version>0.12.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then add the modules you use:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-turn-advisor</artifactId>
</dependency>
```

| To use | Add | It brings |
|---|---|---|
| [Turns in the `ChatClient`](docs/turn/chat-client.md) | `spring-ai-session-turn-advisor` | `spring-ai-session-turn` and Spring AI's `spring-ai-session-advisor` |
| [The turn service](docs/turn/turn-status.md) in a harness of your own | `spring-ai-session-turn` | Spring AI's `spring-ai-session` |
| [The Kafka session store](docs/session-repositories/kafka.md) | `spring-ai-session-kafka` | Spring AI's `spring-ai-session`, Spring for Apache Kafka and Kafka Streams |
| [The Redis session store](docs/session-repositories/redis.md) | `spring-ai-session-redis` | Spring AI's `spring-ai-session` and Spring Data Redis; add a driver, `lettuce-core` or `jedis` |

The BOM sets the versions of this project's modules only.
Spring AI comes from its own BOM, `spring-ai-bom`, in version 2.1.0 or later.

## Building

```bash
./mvnw verify
```

The build checks the source format. To fix a violation, run:

```bash
./mvnw spring-javaformat:apply
```

## Relation to spring-ai-session

The [spring-ai-session](https://github.com/spring-ai-community/spring-ai-session) community project was the first home of the session.
Its core, its JDBC store and its advisors are now part of Spring AI 2.1, under the same package, `org.springframework.ai.session`.
Use the session in Spring AI, and add this project for what Spring AI does not have.

## License

[Apache License 2.0](LICENSE.txt)
