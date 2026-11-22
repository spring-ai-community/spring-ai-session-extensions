# Spring AI Session DynamoDB

A `SessionRepository` for the Spring AI session that keeps sessions and their events in Amazon DynamoDB.
It works with `DefaultSessionService` and the session advisors unchanged, and passes the same contract suite as the JDBC store.

Experimental: tested on DynamoDB Local and checked once against the real service; the stored format is not versioned yet.

[DynamoDB Session Repository](../../docs/session-repositories/dynamodb.md) explains how it works, how to use it, and its guarantees and limits.

```shell
./mvnw verify -pl session-repositories/spring-ai-session-dynamodb -am
```
