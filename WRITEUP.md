# Write-up

## 1. What I asked the AI to do, and what I decided myself

I used Claude Code as the implementer and kept the architectural decisions for
myself. Concretely, I directed it to scaffold the Vert.x + MySQL + Docker
project, write the Flyway schema, the reactive repositories, the service layer,
the HTTP handlers, and the end-to-end tests — reviewing each layer before moving
on and committing in small steps myself.

The decisions I owned:

- **Data model.** I started from a minimal 1:1 design, then deliberately changed
  it to a `conversations` + `conversation_participants` model so the service can
  grow to **group chats** (the follow-up session is about extending this project,
  so I wanted a clean seam rather than a design I'd have to tear up).
- **I made the DB schema extendable for the future.** A `type` column on
  `conversations` plus a separate membership table means new conversation kinds
  and features (group management, read receipts, roles) can be added *without
  reshaping existing tables*. Crucially, `messages` references only
  `conversation_id`, so message storage and pagination are untouched as the
  membership model evolves — the extension points are isolated.
- **Race-safe DM dedup** via a `dm_key` unique column (canonical `"loId-hiId"`,
  `NULL` for groups) instead of a read-then-write that can create duplicate
  conversations under concurrency.
- **Keyset pagination** on the monotonic message id (not `OFFSET`), which is what
  makes paging stable as new messages arrive.
- **Auth model.** The brief doesn't ask for login, only membership enforcement,
  so I used an `X-User-Id` header as an explicit stand-in and spent the time on
  the real authorization check instead.
- Dropping the `username` UNIQUE constraint, since the API identifies users by id.

## 2. Where I overrode or corrected the AI

- **Schema generality.** The AI's first cut baked the two participants into
  `user_lo`/`user_hi` columns. That's the tightest 1:1 design, but it can't
  represent groups, so I had it replace that with a participants table — while
  insisting we *keep* `dm_key` so 1:1 dedup stayed race-safe. I pushed back on a
  suggestion to go further (deterministic conversation ids / Snowflake) as
  over-scope for a single-node exercise, and noted the evolution instead.
- **Tooling that didn't work.** I'd chosen Testcontainers, and the AI wired it in,
  but the local Docker Engine (29.x, API 1.54) returned a malformed `/info` that
  Testcontainers' bundled docker-java rejected with HTTP 400 — over the npipe,
  via `DOCKER_HOST`, with a pinned API version, and even over the mounted socket
  inside a container. Rather than keep fighting it, I overrode that choice and
  drove a **real MySQL through docker-compose** instead. The tests are identical;
  only the database provisioning changed.
- **Process.** I kept all `git` commits in my own hands (not the tool's) so the
  history reflects my checkpoints, and removed redundant scaffolding (a committed
  Maven wrapper / `.gitattributes`) once we committed to a Docker-only workflow.
- **Test coverage.** I wasn't satisfied with only the three required cases, so I
  raised this and had it add edge-case tests until the suite documents the real
  behavior of the API:
  - send a message to yourself → `400`
  - missing `recipientId` → `400`
  - missing / non-numeric `X-User-Id` → `401`
  - non-numeric conversation id in the path → `400`
  - create a group with an unknown participant → `400`
  - create a group with no other participants → `400`
  - duplicate group participants are de-duplicated
  - `limit` is clamped to a max of 100
  - an out-of-range pagination cursor returns an empty page

## 3. Biggest trade-offs and the alternatives I considered

- **Participants table + `dm_key` vs. a two-column pair.** The pair model is
  simpler and dedupes 1:1 for free, but is a dead end for groups. I accepted a
  little extra machinery (`dm_key`, an `EXISTS` membership check) to buy
  extensibility, because that's exactly what the next round will exercise.
- **Keyset vs. offset pagination.** Offset is trivial but drifts when rows are
  inserted mid-paging (duplicates/skips). Keyset on an immutable, monotonic id is
  slightly more code (a cursor in the response) but is correct under concurrent
  writes — the property the brief specifically calls out.
- **`X-User-Id` stand-in vs. real auth.** A JWT/login flow would be more
  realistic but would eat a large share of a 3–4h budget for plumbing the brief
  doesn't ask for. I made the trade explicit in code and docs and kept the
  authorization itself real.

## 4. What's missing / what I'd do with another day

- **Real authentication** (JWT/session) in place of the `X-User-Id` header.
- **Send idempotency** — a client-supplied key so retries don't duplicate
  messages (the one real source of dupes the current design doesn't cover).
- **Real-time delivery** (WebSockets/SSE) and read receipts / unread counts.
- **Conversation-list paging edge cases** — conversations with no messages sort
  last and aren't cleanly cursor-paged; I'd give them a stable sort key.
- **Group management** (add/remove members, naming, leave) now that the model
  supports it, plus authorization tests around membership changes.
- **Scale shaping** — Snowflake/ULID message ids instead of auto-increment for
  sharding, and more concurrency tests around `dm_key` dedup.
- **Operational polish** — request validation limits, rate limiting, structured
  logging/metrics, and a CI workflow running `docker compose run --rm test`.
