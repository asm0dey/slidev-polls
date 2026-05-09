# Slidev Polls

Audience polling for Slidev presentations. A Spring Boot service owns the API,
the database schema, and serves the two SPAs (voter at `/`, backoffice at
`/admin/`) as its own static resources, plus a Vue-based Slidev addon that
renders live results on-slide.

## Requirements

- JDK 25
- Docker (for local Postgres, Testcontainers, and `task up`)
- bun (only frontend toolchain expected on `PATH`)
- [task](https://taskfile.dev) — `brew install go-task/tap/go-task` or
  `go install github.com/go-task/task/v3/cmd/task@latest`

## Quickstart

All orchestration lives in `Taskfile.yml`. Run `task` with no args to list
the entrypoints.

### Run everything in Docker (closest to prod)

```bash
task up
```

Builds a multi-stage image (bun → mvn → JRE), brings up Postgres on `:5432`,
and serves the single-JAR backend on `:8080` with the voter + backoffice
SPAs baked in as same-origin static assets. The task runs `task codegen`
first to regenerate jOOQ sources; after that the image is cached and
subsequent `task up` runs are fast. `task down` tears everything down.

Open `http://localhost:8080/admin/` to create the first presenter account.
The setup screen appears when `admin_user` is empty; once you've completed
it, sign in with the credentials you chose, create a poll, and have your
audience load the join link on their phones. Add additional presenters
from **Presenters** in the sidebar.

### Inner-loop dev (Vite HMR + host spring-boot:run)

```bash
task dev
```

Starts Postgres, runs the backend on `:8080`, and spins up the voter
(`:5173`) + backoffice (`:5174`) Vite dev servers. Ctrl-C tears everything
down. Good for frontend iteration; `task up` is the better check before
pushing.

### Test suite

```bash
task test          # full suite — backend verify + every frontend runner
task test:backend  # just ./mvnw verify
task test:voter    # vitest only
```

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
