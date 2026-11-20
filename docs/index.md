<!-- ![Spring AI](assets/images/spring-ai-title-logo-light.svg#only-light){ width="320" }
![Spring AI](assets/images/spring-ai-title-logo-dark.svg#only-dark){ width="320" } -->

# Spring AI Session Extensions

Add-ons for the session support in [Spring AI](https://docs.spring.io/spring-ai/reference/) 2.1.

Spring AI 2.1 has the session itself: the event log, the stores, the `ChatClient` advisors, compaction and recall.
This project adds features on top of it and changes nothing in it.
It uses only the public extension points of the session: event types of its own, version-checked appends, and advisors around the session advisors.

!!! note
    Experimental. The API can change between versions.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **Session Turn** | `spring-ai-session-turn` | The `TurnService`: it starts, ends, interrupts, parks and resumes the turns of a session, and records each change as a `TURN.*` event in the session's log. It works with any Spring AI session store and does not depend on the `ChatClient`. |
| **Session Turn Advisor** | `spring-ai-session-turn-advisor` | The `ChatClient` binding of the turn. The `TurnStatusAdvisor` runs every call as a turn, parks a turn whose tool call waits for a person's confirmation, and resumes it from the decisions. The `TurnInterruptAdvisor` stops the tool loop of an interrupted call. `TurnAdvisors` builds the whole advisor chain. |
| **Session Kafka** | `spring-ai-session-kafka` | A `SessionRepository` that keeps sessions in Kafka, for teams that already run Kafka and want agent memory there, with no extra database. Each session has one owner that handles its calls one at a time, and the state is backed by compacted topics. It passes the Spring AI session repository contract tests. |
| **BOM** | `spring-ai-session-extensions-bom` | Bill of Materials for the module versions. |

[Turn Status](turn/turn-status.md) explains the turn service, and [Turns in the ChatClient](turn/chat-client.md) the `ChatClient` binding.
[Kafka Session Repository](session-repositories/kafka.md) explains the Kafka store.

## Installation

The artifacts are on Maven Central.
Import the BOM, so that the modules need no version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session-extensions-bom</artifactId>
            <version>0.11.1-SNAPSHOT</version>
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
| [Turns in the `ChatClient`](turn/chat-client.md) | `spring-ai-session-turn-advisor` | `spring-ai-session-turn` and Spring AI's `spring-ai-session-advisor` |
| [The turn service](turn/turn-status.md) in a harness of your own | `spring-ai-session-turn` | Spring AI's `spring-ai-session` |
| [The Kafka session store](session-repositories/kafka.md) | `spring-ai-session-kafka` | Spring AI's `spring-ai-session`, Spring for Apache Kafka and Kafka Streams |

The BOM sets the versions of this project's modules only.
Spring AI comes from its own BOM, `spring-ai-bom`, in version 2.1.0 or later.

## More stores

Stores beyond the ones in Spring AI go into the `session-repositories` folder of the project.
A store is built here first and moves into Spring AI when it is stable.
