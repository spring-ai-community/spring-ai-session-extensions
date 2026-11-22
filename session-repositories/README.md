# Session repositories

Stores for the Spring AI session: implementations of `org.springframework.ai.session.SessionRepository`.
Spring AI 2.1 has the in-memory store and the JDBC store. This folder is for the others.

The layout follows Spring AI, which keeps its JDBC store in `session-repositories/spring-ai-session-jdbc`.

## Stores

| Store | Module | Docs |
|---|---|---|
| Kafka | [`spring-ai-session-kafka`](spring-ai-session-kafka) | [Kafka Session Repository](../docs/session-repositories/kafka.md) |
| DynamoDB | [`spring-ai-session-dynamodb`](spring-ai-session-dynamodb) | [DynamoDB Session Repository](../docs/session-repositories/dynamodb.md) |

## Path to Spring AI

A store here is a candidate for Spring AI.
It is built and used here first, and moves into Spring AI when it is stable.
The move changes the package from `org.springaicommunity.session.<store>` to `org.springframework.ai.session.<store>` and nothing else.

Spring AI plans session stores for Cassandra, Neo4j, MongoDB and Redis before 3.0.0, when the deprecated chat memory stores are removed.
Those four are the priority here.

## A new store

- **Module:** `session-repositories/spring-ai-session-<store>`, for example `spring-ai-session-mongodb`.
- **Package:** `org.springaicommunity.session.<store>`.
- **Registration:** add the module to the `<modules>` of the root `pom.xml` and to the BOM.
- **Dependencies:** `org.springframework.ai:spring-ai-session` and the store's client library.
- **Contract tests:** subclass `AbstractSessionRepositoryContractTests` from `org.springframework.ai:spring-ai-test`, with test scope.
  These are the tests Spring AI runs against its own stores. A store passes all of them.
- **Integration tests:** run against the real store with Testcontainers, as Spring AI does for JDBC.
- **Spring Boot:** an auto-configuration and a starter come later, as their own modules.
