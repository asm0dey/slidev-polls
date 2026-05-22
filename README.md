# Slidev Polls

[![npm](https://img.shields.io/npm/v/@slidev-polls/component?label=%40slidev-polls%2Fcomponent&logo=npm)](https://www.npmjs.com/package/@slidev-polls/component)
[![GitHub release](https://img.shields.io/github/v/release/asm0dey/slidev-polls?logo=github)](https://github.com/asm0dey/slidev-polls/releases)
[![GHCR image](https://ghcr-badge.egpl.dev/asm0dey/slidev-polls-backend/latest_tag?label=ghcr&trim=major)](https://github.com/asm0dey/slidev-polls/pkgs/container/slidev-polls-backend)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Live audience polling for [Slidev](https://sli.dev) presentations. One service
runs the API, the database, the voter page (`/`), and the admin UI (`/admin/`).
A companion Slidev addon renders the results on a slide while the audience is
voting.

## Demo

https://github.com/user-attachments/assets/03e127df-656d-4e82-85a4-489e8cafc9b0

## Features

### For your audience

- No install, no sign-up. Voters open the join link or scan a QR on any phone
  or laptop and start answering. The voter is remembered across reloads, so
  refreshing the page mid-talk doesn't lose their ballot.
- Voters can change their mind until the question closes. Submitting again
  replaces the previous ballot.
- When no question is open yet, voters see a waiting screen. When the active
  question changes, every connected voter rolls to it live, no refresh.
- After submitting (or abstaining), the voter sees the same running tally that
  the slide does, updating in real time as others answer.

### For presenters (inside the deck)

- Add a `<PollResults …>` component on a slide and the live tally renders
  there, animated, in your deck's theme. No tab-switching to a poll dashboard.
- When you advance to a slide that has a poll on it, that question is opened
  for voting automatically. Stepping back or forward through the deck flips
  the audience's voter UI with you. Anonymous viewers replaying the deck see
  the same tallies but never re-open a closed question.
- A button in the Slidev toolbar takes your admin credentials and mints a
  short-lived deck token automatically. No admin-UI side trip to pre-create
  one.
- While signed in, every on-slide result panel shows a QR toggle. Clicking it
  opens a fullscreen QR with the printed join URL so the audience can scan
  to join without typing. The QR is rendered locally; nothing leaves your
  browser.
- Tag a `<PollResults>` with a short `name` and read its tally from any later
  slide. Handy for summary slides or "compared to question 1…" callbacks.
  Last-known tallies are cached, so navigating back to an earlier poll
  renders instantly.
- The admin UI exposes a *Copy snippet* button per question. The snippet is
  pre-filled with the right slug, poll ID, and question ID.

### For administrators (`/admin/`)

- First time you open the admin URL, a setup wizard creates the initial
  presenter account. No env vars or SQL involved.
- Add and remove additional presenter accounts from the **Presenters**
  sidebar. Useful when a colleague is co-presenting.
- Poll authoring:
    - Create a poll, give it a human-readable slug (with live availability
      check), and add an ordered list of questions.
    - Per question: single-choice, multi-choice with a minimum and maximum
      number of picks, or optional voting (abstain allowed).
    - Reorder questions, rename, edit options at any time before the
      question goes live.
- Each question goes *Draft → Active → Closed*. Activating a new question
  automatically closes the previous one, so the audience is never voting on
  two things at once.
- The deck-token page lists every token issued for a poll (each one minted
  the moment a presenter signs in from a deck) and lets you revoke any of
  them. Revoking instantly logs that deck out.
- The poll-edit screen previews the QR your audience will scan and the join
  URL they'd see, with one-click copy.
- A per-poll origin allowlist controls which website origins are allowed to
  talk to a given poll. A leaked or forked copy of your deck served from a
  different domain can't drive your poll.

### Deployment

- Self-host a single talk in one container, one volume. The image can run
  with embedded file storage and persist results across restarts.
- For recurring use or a team, run the same image with a sibling PostgreSQL
  service.
- Releases publish to a public registry, so deployment is: drop the compose
  snippet, set three env vars, `docker compose up -d`.
- The voter page, admin UI, and API all live behind a single port. No
  separate web server or reverse proxy needed to get started.

## Requirements

Docker (or any OCI runtime that speaks Compose). That's it.

## Quickstart

1. Bring the container up (see one of the runtime options below).
2. Open `http://localhost:8080/admin/` and follow the first-run wizard to
   create the initial presenter account.
3. Sign in, create a poll, add at least one question.
4. On the question editor, click **Copy snippet** — this yields a
   `<PollResults slug="…" pollId="…" questionId="…" />` tag pre-filled
   with the right IDs. Paste it onto the slide where you want the live
   tally to appear.
5. Share the join link or QR with your audience.

See [Slidev deck integration](#slidev-deck-integration) for how to wire the
addon into your deck once.

### Run from the published image (no build)

CI pushes a backend image to GHCR after every successful `main` build:
`ghcr.io/asm0dey/slidev-polls-backend` (tags: `latest`, `sha-<commit>`).

The image supports two storage backends, both compiled into the native binary
and picked at runtime by `APP_DATABASE_VENDOR` (`postgres` default, or `h2`):

- PostgreSQL is the production default. Needs a sibling `postgres` service and
  a `QUARKUS_DATASOURCE_POSTGRES_JDBC_URL` pointing at it.
- H2 in file mode lets you run a single container, with the database
  persisted to a volume. Good when you're self-hosting one talk. Set
  `APP_DATABASE_VENDOR=h2` and a `QUARKUS_DATASOURCE_H2_JDBC_URL`.

GHCR images are public when the repo is public. If the package is private,
run `docker login ghcr.io` with a PAT that has `read:packages` first.

#### PostgreSQL stack (recommended for prod)

Put the snippet into `compose.yml`, fill in the env vars (or supply them via
`.env` / `--env-file`), and run `docker compose up -d`:

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
      QUARKUS_DATASOURCE_POSTGRES_JDBC_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      QUARKUS_DATASOURCE_POSTGRES_USERNAME: ${POSTGRES_USER}
      QUARKUS_DATASOURCE_POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      # >= 32-byte key used to encrypt the SP_SESSION cookie. Set a real value.
      SP_SESSION_KEY: ${SP_SESSION_KEY}
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
SP_SESSION_KEY=please-generate-a-32-byte-or-longer-secret
```

The DB user and password are shared between the two services on purpose.
Postgres provisions the role from `POSTGRES_USER` / `POSTGRES_PASSWORD`, and
the backend authenticates with the same pair via
`QUARKUS_DATASOURCE_POSTGRES_USERNAME` and
`QUARKUS_DATASOURCE_POSTGRES_PASSWORD`. The packaged image runs the `prod`
profile by default, so the `SP_SESSION` cookie carries the `Secure` flag —
front the container with TLS. Application-level credentials (the first
presenter account) are created interactively at `http://localhost:8080/admin/`
on first run.

#### H2 single-container (no Postgres needed)

One container, one volume. The DB file lives at `/data/polls.mv.db` inside the
container, and the named volume keeps it across restarts and image upgrades:

```yaml
services:
  backend:
    image: ghcr.io/asm0dey/slidev-polls-backend:latest
    environment:
      APP_DATABASE_VENDOR: h2
      QUARKUS_DATASOURCE_H2_JDBC_URL: 'jdbc:h2:file:/data/polls;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE'
      QUARKUS_DATASOURCE_H2_USERNAME: sa
      QUARKUS_DATASOURCE_H2_PASSWORD: ''
      SP_SESSION_KEY: please-generate-a-32-byte-or-longer-secret
    volumes:
      - polls-data:/data
    ports:
      - "8080:8080"
    restart: unless-stopped

volumes:
  polls-data:
```

Back up the volume before upgrading to a major release. H2 file-format changes
between minor versions are rare but they do happen:

```bash
docker run --rm -v polls-data:/data -v $PWD:/out alpine \
  tar czf /out/polls-backup.tgz -C /data .
```

### Running on H2 from a JVM jar (no Docker)

Same env vars, but pointed at a host path. The JVM jar lives under
`target/quarkus-app/` after `./mvnw package`:

```bash
APP_DATABASE_VENDOR=h2 \
QUARKUS_DATASOURCE_H2_JDBC_URL='jdbc:h2:file:./data/polls;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE' \
QUARKUS_DATASOURCE_H2_USERNAME=sa \
QUARKUS_DATASOURCE_H2_PASSWORD= \
java -jar target/quarkus-app/quarkus-run.jar
```

The DB lands at `./data/polls.mv.db`. Override the path inside
`QUARKUS_DATASOURCE_H2_JDBC_URL` for a different location (e.g.
`jdbc:h2:file:/var/lib/slidev-polls/polls;...`). The standalone native binary
(`target/*-runner`) takes the same env vars.

## Slidev deck integration

`@slidev-polls/component` is published on npm. Add it to your deck's
`addons:` frontmatter, then place `<PollResults …>` on any slide. The admin
UI's snippet generator fills in the IDs for you. See
[`frontends/slidev-component/README.md`](frontends/slidev-component/README.md)
for the addon's own docs.

## Upgrade notes

### V8 / V9 schema change

This release applies two migrations to existing PostgreSQL installs:

- V8 drops `polls.style`. The column was unused, and any data in it is gone.
- V9 rewrites the schema for cross-engine portability. `polls.allowed_origins`
  (text[]) becomes a `poll_allowed_origins` child table, with data migrated
  automatically. The partial unique on `poll_questions(status='ACTIVE')` and
  the functional unique on `lower(polls.slug)` become generated columns.
  Outside of V8 there's no data loss.

Take a snapshot before upgrading: `pg_dump -Fc -f slidev-polls-pre-V9.dump`.
Flyway then runs both migrations on boot.

V1–V7 also move from `db/migration/` to `db/migration/postgresql/`. Flyway's
history table records the bare filename (`V1__core_tables.sql`), so validation
passes fine across the move. If your deployment
customised Flyway to record full classpath paths, run `flyway repair` once
before the next boot.

## Development

### Stack

- Backend: Quarkus 3.15.7 on Java 21 bytecode (built with JDK 25), jOOQ for
  type-safe SQL, Flyway for migrations, Server-Sent Events for live tally
  fan-out. Ships both as a JVM jar and as a GraalVM/Mandrel native binary; the
  voter and admin SPAs are served from `META-INF/resources`, baked into the
  artifact.
- Storage: PostgreSQL (prod) and H2 in file mode (single-container) are both
  compiled in; the active engine is picked at runtime by `app.database.vendor`
  (env `APP_DATABASE_VENDOR`, default `postgres`). Each vendor has its own
  Quarkus named datasource; Flyway runs the matching vendor's migrations on
  startup.
- Auth: a Quarkus session cookie (`SP_SESSION`) for presenters, an HttpOnly
  `SP_VOTER` cookie for voters, and a separate bearer-token filter for the
  Slidev addon (deck tokens).
- Frontends: Vue 3 + Vite in a `bun` workspace with four packages.
  `shared` holds DTOs, the api-client, and the sse-client; `voter` is the
  SPA at `/`; `backoffice` is the SPA at `/admin/`; `slidev-component` is
  the Slidev addon.
- Tests: vitest for every frontend, JUnit + Testcontainers for the backend
  so integration tests exercise jOOQ against a real Postgres.
- CI: every successful `main` build pushes
  `ghcr.io/asm0dey/slidev-polls-backend:{latest,sha-<commit>}`.

### Tooling prerequisites

- JDK 25 (use `JAVA_HOME=/path/to/jdk25 ./mvnw …` if your system JVM is older)
- bun (npm works too, but the workspace and lockfile are bun)
- Docker (for local Postgres and Testcontainers)
- [task](https://taskfile.dev) — `brew install go-task/tap/go-task`, or
  `go install github.com/go-task/task/v3/cmd/task@latest`

### Common tasks

All orchestration lives in `Taskfile.yml`. Run `task` with no args to list
entrypoints.

```bash
task up            # prod-like: multi-stage build, Postgres + native binary in Docker, :8080
task dev           # inner loop: Postgres + quarkus:dev + Vite HMR (:5173 voter, :5174 backoffice)
task build         # JVM jar on the host (target/quarkus-app)
task build-native  # GraalVM native binary via the Mandrel builder container
task down          # tear down compose stack
task codegen       # regenerate jOOQ sources from the live schema
task test          # full suite (backend verify + every frontend runner)
task test:backend  # ./mvnw verify
task test:voter    # vitest only
```

`task up` runs `task codegen` first; subsequent runs are cached and fast.
`task dev` is the better loop for frontend work, and `task up` is the better
sanity check before pushing.

### Repository layout

```
pom.xml                  # single Quarkus application (quarkus-bom 3.15.7)
src/main/java/site/asm0dey/slidev/polls/
  core/                  # domain + services, no web, no JDBC
  persistence/           # jOOQ + Flyway migrations
  realtime/              # SSE hub + tally broadcaster
  api/                   # entrypoint, REST resources, SPA hosting
src/main/resources/
  application.properties # Quarkus config (multi-datasource, native build args)
  db/migration/          # Flyway migrations (common + per-vendor)
  META-INF/resources/    # built voter + backoffice SPAs (gitignored)
src/test/java/...        # unit + integration tests (mirrors main packages)
frontends/               # bun workspace
  shared/                # @slidev-polls/shared — DTOs, api-client, sse-client
  voter/                 # @slidev-polls/voter — public SPA at /
  backoffice/            # @slidev-polls/backoffice — SPA at /admin/
  slidev-component/      # @slidev-polls/component — Slidev addon
```
