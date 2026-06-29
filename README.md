# Direct Messaging Service

Backend for one-to-one messaging — send messages, read paginated conversation
history, and list a user's conversations. Built with **Java 21 + Vert.x +
MySQL**, packaged with **Docker**.

> 🚧 Work in progress — full install/run/test instructions and API reference are
> added in the final commit. See [the implementation plan](#) for scope.

## Stack

- Java 21, [Vert.x](https://vertx.io/) (reactive web + MySQL client)
- MySQL 8, schema managed by Flyway
- Tests with JUnit 5 + Testcontainers (real MySQL)
- Docker / Docker Compose for local run

## Prerequisites

- JDK 21
- Docker Desktop (running) — required for `docker compose` and for the test suite
  (Testcontainers).
