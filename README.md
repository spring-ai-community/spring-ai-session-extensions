# Spring AI Session Extensions

Add-ons for the session support in [Spring AI](https://docs.spring.io/spring-ai/reference/) 2.1.

Spring AI 2.1 has the session itself: the event log, the stores, the `ChatClient` advisors, compaction and recall.
This project adds features on top of it and changes nothing in it.
It uses only the public extension points of the session: event types of its own, version-checked appends, and advisors around the session advisors.

> **Status: experimental.** Nothing is released yet. The API can change between versions.

## Requirements

- Java 17 or later
- Spring AI 2.1

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **Session Turn** | `spring-ai-session-turn` | The `TurnService`: it starts, ends, interrupts, parks and resumes the turns of a session, and records each change as a `TURN.*` event in the session's log. It works with any Spring AI session store and does not depend on the `ChatClient`. |
| **Session Turn Advisor** | `spring-ai-session-turn-advisor` | The `ChatClient` binding of the turn. The `TurnStatusAdvisor` runs every call as a turn, parks a turn whose tool call waits for a person's confirmation, and resumes it from the decisions. The `TurnInterruptAdvisor` stops the tool loop of an interrupted call. `TurnAdvisors` builds the whole advisor chain. |
| **BOM** | `spring-ai-session-extensions-bom` | Bill of Materials for the module versions. |

Stores beyond the ones in Spring AI go into [`session-repositories`](session-repositories/README.md).

The group id is `org.springaicommunity`. The Java packages start with `org.springaicommunity.session`.

[Turn Status](docs/turn/turn-status.md) explains the turn service, and [Turns in the ChatClient](docs/turn/chat-client.md) the `ChatClient` binding.

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
