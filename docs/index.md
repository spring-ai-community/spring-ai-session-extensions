<!-- ![Spring AI](assets/images/spring-ai-title-logo-light.svg#only-light){ width="320" }
![Spring AI](assets/images/spring-ai-title-logo-dark.svg#only-dark){ width="320" } -->

# Spring AI Session Extensions

Add-ons for the session support in [Spring AI](https://docs.spring.io/spring-ai/reference/) 2.1.

Spring AI 2.1 has the session itself: the event log, the stores, the `ChatClient` advisors, compaction and recall.
This project adds features on top of it and changes nothing in it.
It uses only the public extension points of the session: event types of its own, version-checked appends, and advisors around the session advisors.

!!! note
    Experimental. Nothing is released yet. The API can change between versions.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **Session Turn** | `spring-ai-session-turn` | The `TurnService`: it starts, ends, interrupts, parks and resumes the turns of a session, and records each change as a `TURN.*` event in the session's log. It works with any Spring AI session store and does not depend on the `ChatClient`. |
| **Session Turn Advisor** | `spring-ai-session-turn-advisor` | The `ChatClient` binding of the turn. The `TurnStatusAdvisor` runs every call as a turn, parks a turn whose tool call waits for a person's confirmation, and resumes it from the decisions. The `TurnInterruptAdvisor` stops the tool loop of an interrupted call. `TurnAdvisors` builds the whole advisor chain. |
| **BOM** | `spring-ai-session-extensions-bom` | Bill of Materials for the module versions. |

[Turn Status](turn/turn-status.md) explains the turn service, and [Turns in the ChatClient](turn/chat-client.md) the `ChatClient` binding.

Stores beyond the ones in Spring AI go into the `session-repositories` folder of the project.
A store is built here first and moves into Spring AI when it is stable.
