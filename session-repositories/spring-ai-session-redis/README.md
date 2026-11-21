# Spring AI Session Redis

A Redis-based `SessionRepository` for the Spring AI session: sessions and their events are kept in Redis.
It lets a team that already runs Redis keep agent memory there, with no extra database.
It works with `DefaultSessionService` and the session advisors unchanged, and passes the same contract suite as the JDBC store.

Experimental: tested on a single standalone Redis container, and the data layout is not versioned yet.

[Redis Session Repository](../../docs/session-repositories/redis.md) explains how it works, how to use it, and its guarantees and limits.

```shell
./mvnw verify -Pintegration-tests -pl session-repositories/spring-ai-session-redis -am
```
