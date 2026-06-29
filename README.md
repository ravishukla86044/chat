# Direct Messaging Service

A backend for one-to-one (and group) messaging: send messages, read paginated
conversation history, and list a user's conversations — with stable pagination
and conversation-membership authorization.

Built with **Java 21 + Vert.x** (reactive web + MySQL client), **MySQL 8**
(schema via Flyway), and **Docker**.

## What it does

| Capability | Endpoint |
| --- | --- |
| Send a direct message between two users (auto-creates the 1:1 conversation) | `POST /messages` |
| Create a group conversation | `POST /conversations` |
| Send a message to an existing conversation | `POST /conversations/{id}/messages` |
| Fetch a conversation's history, paginated | `GET /conversations/{id}/messages` |
| List the conversations a user is part of | `GET /conversations` |
| Create a user / health check | `POST /users`, `GET /health` |

Two properties are enforced as core requirements:

- **Stable pagination** — history paging uses a keyset cursor on the monotonic
  message id, so new messages never cause duplicates or skips in already-fetched
  pages.
- **Authorization** — a user can only read/post in conversations they belong to;
  a non-participant read returns `403`.

## Prerequisites

- **Docker Desktop**, running. This is the only hard requirement — the app, the
  database, and the test suite all run in containers.
- (Optional) JDK 21 if you want to build/run on the host instead of Docker.

## Run it

```bash
docker compose up --build
```

This starts MySQL and the API; Flyway applies the schema on startup. The API is
then available at `http://localhost:8080`.

```bash
curl http://localhost:8080/health      # {"status":"ok"}
```

Stop and remove everything (including the database volume):

```bash
docker compose down -v
```

### Live-reload during development (Compose watch)

`docker compose up --watch` launches the stack and watches your files: when you
edit and save anything under `src/` or `pom.xml`, Compose automatically rebuilds
and restarts the `app` service. Because this is a compiled (fat-jar) app, the
watch action is `rebuild` (see the `develop.watch` block in
[`docker-compose.yml`](docker-compose.yml)).

```bash
docker compose up --watch
```

Then just edit source files in your editor — the running service updates itself.

## Run the tests

The suite spins up the API against a real MySQL and exercises every endpoint
over HTTP. Run it entirely in Docker — no local Java/Maven needed:

```bash
docker compose run --rm test
```

It covers the three required cases — **send → fetch**, **pagination stability
under a concurrent insert**, and a **non-participant `403` read** — plus edge
cases: empty body `400`, unknown recipient `404`, send-to-self `400`, missing
`recipientId` `400`, missing/non-numeric `X-User-Id` `401`, non-numeric path id
`400`, DM dedup, group create/send/read with an outsider `403`, group with an
unknown participant `400`, group participant de-duplication, `limit` capping at
100, and an out-of-range cursor returning an empty page.

> Running on the host instead: start the DB with `docker compose up -d mysql`,
> then `./mvnw test` (connects to `localhost:3306` by default).

## Authentication

There is no login system (none is required for this exercise). The caller's
identity is supplied via an **`X-User-Id`** header — a deliberate, documented
stand-in for real auth. The membership **authorization** it gates is real: every
read/post checks that `X-User-Id` is a participant of the conversation.

## API reference

All request/response bodies are JSON. Protected routes require `X-User-Id`.

### `POST /users`
`email` is required and must be unique (case-insensitive); a duplicate returns `409`.
```bash
curl -XPOST localhost:8080/users -H 'Content-Type: application/json' \
  -d '{"username":"dave","email":"dave@example.com"}'
# 201 {"id":4,"username":"dave","email":"dave@example.com","createdAt":"..."}
```

### `POST /messages` — send a direct message
Sender is `X-User-Id`; the 1:1 conversation with `recipientId` is found-or-created.
```bash
curl -XPOST localhost:8080/messages -H 'X-User-Id: 1' \
  -H 'Content-Type: application/json' -d '{"recipientId":2,"body":"Hey Bob!"}'
# 201 {"id":14,"conversationId":14,"senderId":1,"body":"Hey Bob!","createdAt":"..."}
```

### `GET /conversations/{id}/messages` — paginated history (newest first)
`limit` (default 50, max 100) and `before` (cursor) are optional.
```bash
curl "localhost:8080/conversations/14/messages?limit=2" -H 'X-User-Id: 1'
# 200 {"messages":[{"id":17,...},{"id":16,...}],"nextCursor":16}

curl "localhost:8080/conversations/14/messages?limit=2&before=16" -H 'X-User-Id: 1'
# 200 {"messages":[{"id":15,...},{"id":14,...}],"nextCursor":14}
```
`nextCursor` is `null` when there are no older messages. A non-participant gets:
```bash
curl "localhost:8080/conversations/14/messages" -H 'X-User-Id: 3'   # 403
```

### `GET /conversations` — list the caller's conversations
Most-recent-activity first, with a preview of the latest message.
```bash
curl "localhost:8080/conversations" -H 'X-User-Id: 2'
# 200 {"conversations":[{"conversationId":14,"type":"direct","name":null,
#       "participantIds":[1,2],"lastMessage":{"id":17,"body":"Ping me",...}}],
#      "nextCursor":null}
```

### `POST /conversations` — create a group
The caller is always added as a participant.
```bash
curl -XPOST localhost:8080/conversations -H 'X-User-Id: 1' \
  -H 'Content-Type: application/json' -d '{"name":"team","participantIds":[2,3]}'
# 201 {"id":18,"type":"group","name":"team","participantIds":[1,2,3],"createdAt":"..."}
```

### `POST /conversations/{id}/messages` — send to a conversation
```bash
curl -XPOST localhost:8080/conversations/18/messages -H 'X-User-Id: 2' \
  -H 'Content-Type: application/json' -d '{"body":"hi team"}'   # 201
```

### Errors
JSON `{"error": "..."}` with status `400` (validation), `401` (missing/invalid
`X-User-Id`), `403` (not a participant), `404` (unknown user/conversation),
`500` (unexpected).

## Data model

- **`users`** — `id`, `username`, `email` (required, `UNIQUE`), `created_at`.
- **`conversations`** — `id`, `type` (`direct` | `group`), `name`, `dm_key`,
  `created_at`. `dm_key` is the canonical `"loId-hiId"` for direct conversations
  (`NULL` for groups) with a `UNIQUE` constraint, giving **race-safe 1:1 dedup**:
  concurrent first-messages between the same pair can't create two conversations.
- **`conversation_participants`** — `(conversation_id, user_id)`. Membership and
  authorization live here; it generalizes cleanly to groups.
- **`messages`** — `id` (auto-increment, the **pagination cursor**),
  `conversation_id`, `sender_id`, `body`, `created_at`.

Migrations are in [`src/main/resources/db/migration`](src/main/resources/db/migration);
`V2__seed.sql` seeds users `1=alice, 2=bob, 3=carol` for convenience.

## Configuration

Environment variables (defaults suit local docker-compose):

| Var | Default | |
| --- | --- | --- |
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `chat` | database |
| `DB_USER` / `DB_PASSWORD` | `chat` / `chatpass` | credentials |
| `SERVER_PORT` | `8080` | HTTP port |

## Project layout

```
src/main/java/com/ravi/chat
  MainVerticle.java        bootstrap: Flyway, pool, HTTP server
  config/                  env-based AppConfig
  db/                      Flyway runner
  model/                   User, Conversation, Message, Page, ...
  repo/                    SQL via the reactive MySQL client
  service/                 validation + authorization rules
  web/                     router, handlers, X-User-Id, JSON errors
src/main/resources/db/migration   V1 schema, V2 seed
src/test/java/com/ravi/chat       end-to-end API tests
```

## Notes

The tests originally used Testcontainers; the local Docker Engine (29.x) returned
a malformed `/info` to Testcontainers' bundled docker-java, so the suite now
drives a real MySQL provided by docker-compose instead — same end-to-end
coverage. See [`WRITEUP.md`](WRITEUP.md) for the reasoning and other trade-offs.
