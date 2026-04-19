# Slidev Polls

Audience polling for Slidev presentations. A Spring Boot service owns the API,
the database schema, and serves the two SPAs (voter at `/`, backoffice at
`/admin/`) as its own static resources, plus a Vue-based Slidev addon that
renders live results on-slide.

## Requirements

- JDK 25
- Docker (for local Postgres and Testcontainers)
- bun (only frontend toolchain expected on `PATH`)

## Quickstart

### Single-JAR production run

```bash
docker compose up -d postgres
./scripts/build-frontends.sh    # bun install + build, copy dist → backend static
./mvnw -pl backend/poll-api -am spring-boot:run
```

Open `http://localhost:8080/` (voter) or `http://localhost:8080/admin/`
(backoffice). Sign in as `alice` / `correct-horse`, create a poll, copy the
join link, and have an audience load it on their phones.

### Inner-loop dev

```bash
./scripts/dev.sh
```

Starts Postgres, runs the backend on `:8080`, and spins up the two Vite dev
servers (voter on `:5173`, backoffice on `:5174`). Ctrl-C tears everything
down.

### Slidev deck integration

The Slidev addon at `frontends/slidev-component` is consumed as a local
workspace dep. Embed `<PollResults slug="my-talk" />` on a slide. Supply
`questionId`, `deckToken`, and `pollId` together to have the slide
auto-activate its question on mount — mint the token under *Deck tokens* on
the backoffice poll-edit page.

See [`specs/001-polling-foundation/`](specs/001-polling-foundation/) for the
full feature specification, plan, data model, and HTTP contract.

## Repository layout

```
pom.xml                  # reactor parent (spring-boot-starter-parent 4.0.5)
backend/
  poll-core/             # domain + services, no web, no JDBC
  poll-persistence/      # jOOQ + Flyway migrations
  poll-realtime/         # SSE hub + tally broadcaster
  poll-api/              # Spring Boot entrypoint, controllers, SPA hosting
frontends/               # bun workspace
  shared/                # @polls/shared — DTOs, api-client, sse-client
  voter/                 # @polls/voter — public SPA at /
  backoffice/            # @polls/backoffice — SPA at /admin/
  slidev-component/      # @polls/slidev-addon — Slidev addon
```
