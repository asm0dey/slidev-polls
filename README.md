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

### Run from the published image (no build)

After every successful `main` build, CI pushes a backend image to GHCR:
`ghcr.io/asm0dey/slidev-polls-backend` (tags: `latest`, `sha-<commit>`).

Drop the snippet below into a `compose.yml`, fill in the env vars (or supply
them via a `.env` file next to it / `--env-file`), and `docker compose up -d`:

```yaml
services:
  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 3s
      timeout: 3s
      retries: 20

  backend:
    image: ghcr.io/asm0dey/slidev-polls-backend:latest
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      # `prod` flips the SP_SESSION cookie to Secure — only enable when you
      # terminate TLS in front of the container.
      SPRING_PROFILES_ACTIVE: prod
    ports:
      - "8080:8080"
    restart: unless-stopped

volumes:
  postgres-data:
```

Example `.env`:

```env
POSTGRES_DB=polls
POSTGRES_USER=polls
POSTGRES_PASSWORD=change-me
```

The DB user/password are shared between the two services on purpose — Postgres
provisions the role from `POSTGRES_USER`/`POSTGRES_PASSWORD`, and the backend
authenticates with the same pair via `SPRING_DATASOURCE_USERNAME`/
`SPRING_DATASOURCE_PASSWORD`. Application-level credentials (the first
presenter account) are created interactively at `http://localhost:8080/admin/`
on first run.

GHCR images are public when the repo is public; if you've made the package
private, run `docker login ghcr.io` with a PAT that has `read:packages` first.

### Running on H2 (no Postgres required)

Single-binary deploy that does not need Docker. Set three env vars before starting the JAR:

```bash
SPRING_DATASOURCE_URL='jdbc:h2:file:./data/polls;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE'
SPRING_DATASOURCE_USERNAME=sa
SPRING_DATASOURCE_PASSWORD=
java -jar slidev-polls.jar
```

To put the DB somewhere other than `./data/polls.mv.db`, replace the path in `SPRING_DATASOURCE_URL` directly (e.g. `jdbc:h2:file:/var/lib/slidev-polls/polls;...`).

Spring Boot's Flyway autoconfig sees the `jdbc:h2:` prefix, expands the `{vendor}` placeholder in `spring.flyway.locations` to `h2`, and runs `db/migration/h2/V1__schema_baseline.sql` plus everything in `db/migration/common/`. jOOQ's H2 dialect is auto-detected from the same URL.

**Never** set `spring.h2.console.enabled=true` — that would expose a SQL shell at `/h2-console` and bypass every other auth surface in the app.

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
pom.xml                  # single Spring Boot application (spring-boot-starter-parent 4.0.6)
src/main/java/site/asm0dey/slidev/polls/
  core/                  # domain + services, no web, no JDBC
  persistence/           # jOOQ + Flyway migrations
  realtime/              # SSE hub + tally broadcaster
  api/                   # Spring Boot entrypoint, controllers, SPA hosting
src/main/resources/
  application.yml
  db/migration/          # Flyway V1..V6
  static/                # built voter + backoffice SPAs (gitignored)
src/test/java/...        # unit + integration tests (mirrors main packages)
frontends/               # bun workspace
  shared/                # @slidev-polls/shared — DTOs, api-client, sse-client
  voter/                 # @slidev-polls/voter — public SPA at /
  backoffice/            # @slidev-polls/backoffice — SPA at /admin/
  slidev-component/      # @slidev-polls/component — Slidev addon
```
