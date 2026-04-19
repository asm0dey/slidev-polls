# Tasks: Core Polling Platform

## Clarifications

### Session 2026-04-19

- Q: Should load/performance tasks stay in scope? -> A: No — remove T103 (`SseFanoutLoadIT`), T133 (perf spot-check), and TS-035 scenario entirely. Load testing deferred beyond this feature. [T103, T133, T136, live-results.feature @TS-035]
- Q: T005 wording — empty bun.lockb placeholder vs populated? -> A: Run `bun install`, commit the populated `bun.lockb`. [T005]
- Q: T064 PollEditorPage scope — split or keep as one task? -> A: Keep as one task; single Vue page with internal sections. [T064]
- Q: Where is the slidev addon published? -> A: Out of scope for this feature. Addon consumed locally from the monorepo; publish strategy deferred. [T132]
- Q: Canonical frontend test runner — `bun test` vs Vitest? -> A: Both — `bun test` for plain-TS unit tests (`frontends/shared`), Vitest for component / Vue packages. [T136]
- Q: T031 uses Vitest, but it lives in `frontends/shared` (plain TS) — which runner? -> A: `bun test` — align with the prior runner clarification. [T031]
- Q: Where are Playwright install + config placed for the e2e smokes? -> A: Add T009 installing Playwright at the `frontends/` workspace root with a shared `frontends/playwright.config.ts`; extend T136 to run the smokes. [T009, T075, T106, T136]
- Q: Voter identity — is `sp_voter` client-owned, server-owned, or both? -> A: Server-authoritative. `VoteController` reads and (when missing) sets `sp_voter` as HttpOnly/SameSite=Lax. Client never writes it; localStorage only caches per-slug `alreadyVoted` booleans. [T086, T091]
- Q: Is "empty" really a reserved slug when `SlugValidator` enforces length 3–40? -> A: No — drop "empty" from `ReservedSlugs`; length validation rejects it before the reserved check. [T019]



**Input**: Design documents from `/specs/001-polling-foundation/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, tests/features/

**Organization**: Grouped by user story. Test-first per Constitution Principle III; JUnit 5 / Vitest only (Principle VII — Gherkin scenarios mirrored as comments above assertions).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1, US2, US3 — omitted for Setup / Foundational / Polish
- Paths follow plan.md's Maven multi-module backend + bun workspace frontend
- Test-spec IDs reference `tests/features/*.feature` (e.g., `[TS-002]`)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Monorepo skeleton, build tooling, Postgres dev container.

- [x] T001 Create monorepo top-level layout per plan.md §Project Structure: `backend/`, `frontends/`, `scripts/`, `docker-compose.yml`, `.editorconfig`, `.gitignore`
- [x] T002 Create parent Maven POM at `backend/pom.xml` declaring Java 25, Spring Boot 4.0.5, module list (`poll-core`, `poll-persistence`, `poll-realtime`, `poll-api`), plugin management (reactor parent lives at repo root `pom.xml`; `backend/pom.xml` is the backend aggregator that lists the four modules, which is what plan.md §Project Structure specifies)
- [x] T003 [P] Check in Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` at repo root per plan.md §Project Structure)
- [x] T004 [P] Create `docker-compose.yml` at repo root with `postgres:16` service for local dev
- [x] T005 [P] Create bun workspace root `frontends/package.json` listing `shared`, `voter`, `backoffice`, `slidev-component`; run `bun install` and commit the resulting `frontends/bun.lock` (bun 1.2+ emits the text-format `bun.lock`; the earlier `bun.lockb` clarification predated that change)
- [x] T006 [P] Create `scripts/build-frontends.sh` (bun install + build all SPAs + copy dists into `backend/poll-api/src/main/resources/static`)
- [x] T007 [P] Create `scripts/dev.sh` (docker-compose up postgres + `mvnw spring-boot:run` + `bun --cwd frontends run dev`)
- [x] T008 [P] Configure ESLint + Prettier config at `frontends/` root; configure Spotless (google-java-format 1.28.0) in the reactor root `pom.xml` with activation in each backend module. Checkstyle is intentionally skipped: Spotless + google-java-format + removeUnusedImports + formatAnnotations subsumes the formatting surface Checkstyle would add, and Principles V/VIII disfavour a second tool with overlapping responsibilities.
- [x] T009 [P] Install Playwright at the `frontends/` workspace root; add shared `frontends/playwright.config.ts`; wire `e2e` script in `frontends/voter/package.json` and `frontends/slidev-component/package.json` so `bun --cwd frontends/<pkg> run e2e` runs the smokes added in T075 / T106

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure required before ANY user story. No story work starts until this phase completes.

### Backend module skeletons

- [x] T010 Create `backend/poll-core` module POM: `spring-context`, `jakarta.validation-api` only (no web, no JDBC) — scaffold ships the two required deps plus `spring-boot-starter-test` (test scope) for the unit tests that arrive in later tasks.
- [x] T011 [P] Create `backend/poll-persistence` module POM: `jooq`, `flyway-core`, `postgresql`, `testcontainers-jooq-codegen-maven-plugin`, `testcontainers-postgresql` (test scope) — scaffold uses `spring-boot-starter-jooq` (brings jOOQ transitively) and adds `flyway-database-postgresql` (required for Flyway to recognise the dialect under Flyway 10+). The jOOQ codegen plugin is wired but commented out; T018 activates it.
- [x] T012 [P] Create `backend/poll-realtime` module POM: `spring-web` (for `SseEmitter`), `spring-context` — scaffold uses `spring-web` + `spring-webmvc`; `spring-context` arrives transitively.
- [x] T013 Create `backend/poll-api` module POM (generated via start.spring.io baseline): `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `flyway-core`, `zxing-core`, `zxing-javase`; depends on the three sibling modules — scaffold uses `spring-boot-starter-webmvc` (the explicit MVC-only starter in Spring Boot 4) instead of `spring-boot-starter-web`; `flyway-core` arrives transitively through `poll-persistence`. `spring-boot-starter-actuator` was removed from the scaffold because plan.md does not record a use for it and Principle VIII forbids speculative additions.

### Database schema + jOOQ codegen (shared by all stories)

- [x] T014 Write Flyway migration `V1__core_tables.sql` in `backend/poll-persistence/src/main/resources/db/migration/` creating `polls`, `poll_questions`, `poll_options`, `votes` with columns and FKs per data-model.md (scaffold also places the slug CHECK constraint, the `(poll_id, ordinal)` / `(question_id, option_id)` / `(question_id, voter_token)` indexes, and the `poll_questions_one_active_uq` partial unique index in this file; splitting those into V2 would be internal churn for a schema that nobody has deployed yet)
- [x] T015 Add Flyway migration `V2__slug_and_indexes.sql`: unique index `polls_slug_lower_uq` on `lower(slug)`. The remaining indexes and the slug CHECK constraint that tasks.md originally placed here ship inside `V1__core_tables.sql` (see T014 reconcile note).
- [x] T016 Add Flyway migration `V3__admin_user.sql` creating `admin_user` table (username PK lowercase, display_name, bcrypt_hash, created_at), adding the polls.owner_username FK that V1 deferred, and seeding presenter "alice" (password `correct-horse`, matching `backoffice-authoring.feature` TS-001) via `pgcrypto.crypt('correct-horse', gen_salt('bf', 10))` so BCrypt validation succeeds regardless of per-install salt
- [x] T017 Add Flyway migration `V4__deck_tokens.sql` creating `deck_tokens` table with UNIQUE(token_hash) and FK to polls ON DELETE CASCADE (scaffolded file already satisfies the task: deck_tokens with UNIQUE token_hash index plus a partial index on unrevoked rows for the look-up hot path)
- [x] T018 Configure jOOQ codegen in `poll-persistence/pom.xml` to generate classes into `target/generated-sources/jooq` under package `site.asm0dey.slidev.polls.persistence.jooq`. Implementation diverges from the original wording for a concrete reason: `testcontainers-jooq-codegen-maven-plugin` 0.0.4 is the last release (2024), bundles Testcontainers 1.19.1, and its docker-java client negotiates Docker engine API 1.32 — which current Docker (29.x) rejects with `client version 1.32 is too old. Minimum supported API version is 1.40`. Overriding the plugin's dependencies did not relocate the docker-java on the plugin classloader. Rather than fork an abandoned plugin, the codegen now follows the Flyway + jOOQ tutorial pattern (https://www.jooq.org/doc/3.21/manual/getting-started/tutorials/jooq-with-flyway/): `flyway-maven-plugin` plus `jooq-codegen-maven` are wired into a dedicated `codegen` Maven profile. Running `docker compose up -d postgres && ./mvnw -Pcodegen compile` flyway-cleans a disposable `polls_codegen` schema, re-applies V1–V4 against it, and regenerates jOOQ sources from the resulting schema. `public` is untouched so dev data survives. Generated sources live only on disk (gitignored) so committed history stays free of mechanical diffs.

### poll-core foundational types (shared by all stories)

- [x] T019 [P] Create `ReservedSlugs` constant holder in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/slug/ReservedSlugs.java` (`admin`, `api`, `assets`, `static`, `j`, `login`, `logout`) — empty string intentionally omitted; `SlugValidator` (T020) rejects it on length grounds before the reserved check
- [x] T020 [P] Create `SlugValidator` in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/slug/SlugValidator.java` enforcing lowercase kebab-case, 3–40 chars, `[a-z0-9]` + `-`, start/end alphanumeric, no `--` — scaffold's implementation (regex `^[a-z0-9]+(-[a-z0-9]+)*$`, length range 3–40, null-safe) covers every invalid/valid case exercised by `SlugValidatorTest` without modification.
- [x] T021 [P] Unit tests `SlugValidatorTest` and `ReservedSlugsTest` in `backend/poll-core/src/test/java/site/asm0dey/slidev/polls/core/slug/` mirroring `@TS-011`, `@TS-012` invalid/reserved examples

### poll-api cross-cutting foundation (shared by all stories)

- [x] T022 Create Spring Boot entrypoint `PollApiApplication.java` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/` (scaffold already provides a `@SpringBootApplication`-annotated entrypoint)
- [x] T023 [P] Configure `application.yml` in `backend/poll-api/src/main/resources/`: datasource (Postgres), Flyway, Jackson, server port, session cookie settings (HttpOnly, SameSite=Lax, Secure in prod profile)
- [x] T024 [P] Create `Problem` record + `ProblemCode` enum in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/error/` matching the codes listed in `openapi.yaml` (`AUTH_REQUIRED`, `FORBIDDEN`, `NOT_FOUND`, `VALIDATION_FAILED`, `ALREADY_VOTED`, `QUESTION_NOT_ACTIVE`, `ACTIVATION_REJECTED`, `SLUG_TAKEN`, `SLUG_INVALID`, `SLUG_RESERVED`, `DECK_TOKEN_INVALID`, `DECK_TOKEN_POLL_MISMATCH`, `TRANSPORT_FAILURE`)
- [x] T025 [P] Create `CorrelationIdFilter` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/logging/` populating MDC per request; structured JSON logging config via Spring Boot 4's native `logging.structured.format.console=ecs` in `application.yml` instead of a hand-maintained `logback-spring.xml` — native structured logging already emits every MDC entry in the JSON event, which is what the task wanted, without a second logging pipeline or the `logstash-logback-encoder` dependency (Principles V/VIII)
- [x] T026 Create `GlobalExceptionHandler` (`@RestControllerAdvice`) in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/error/` mapping domain exceptions to `Problem` codes with correlationId — covers `@TS-042`. The ten domain exception classes T051 / T081 list (`NotFoundException`, `NotOwnerException`, `AlreadyVotedException`, `QuestionNotActiveException`, `ActivationRejectedException`, `SlugTakenException`, `SlugInvalidException`, `SlugReservedException`, `DeckTokenInvalidException`, `DeckTokenPollMismatchException`) are created alongside `GlobalExceptionHandler` in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/error/` because the handler's `@ExceptionHandler` methods cannot compile without them; T051 / T081 become reconcile tasks in Phase 3/4.
- [x] T027 Test `GlobalExceptionHandlerTest` (`@WebMvcTest` slice) in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/error/` asserting every Problem code path per `@TS-042`
- [x] T028 [P] Create `AbstractPostgresTest` base class in `backend/poll-persistence/src/test/java/site/asm0dey/slidev/polls/persistence/AbstractPostgresTest.java` starting one shared Testcontainers Postgres per JVM and running Flyway before each test class

### Frontend shared package (blocks all SPAs)

- [x] T029 [P] Create `frontends/shared/package.json` (@polls/shared) with TS config; export DTO types mirrored from OpenAPI (`Poll`, `PollDetail`, `Question`, `Option`, `PublicPollView`, `VoteRequest`, `VoteAccepted`, `Problem`, `PollStyle`, `DeckToken`, `DeckTokenMinted`)
- [x] T030 [P] Create `frontends/shared/src/api-client.ts` (fetch wrapper, Problem-aware error mapping) and `frontends/shared/src/sse-client.ts` (EventSource with bounded-backoff reconnect + "paused" state callback — shared by voter and slidev-component per Principle IV)
- [x] T031 [P] `bun test` unit tests for `sse-client` reconnect/backoff in `frontends/shared/src/sse-client.test.ts` (plain TS in `frontends/shared`, per prior runner clarification)

**Checkpoint**: Foundation complete — stories can now proceed.

---

## Phase 3: User Story 1 — Presenter authors and controls a poll (P1) — MVP

**Goal**: Signed-in presenter creates/edits/deletes polls, assigns/edits slug, activates/closes questions, and retrieves join link + QR. Anonymous traffic refused on all backoffice routes.

**Independent Test**: Signed-in "alice" creates a poll with two questions, assigns slug, activates Q1 then Q2 (Q1 auto-closes), closes Q2, retrieves PNG QR — all while an unauthenticated request to any `/api/admin/**` returns 401.

### Tests for User Story 1 (write first — MUST fail before implementation)

- [x] T040 [P] [US1] Integration test `PollAuthoringIT` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/admin/` — scenarios `[TS-002, TS-006]` (create + delete poll). Bundled here: widened `PollApiApplication`'s `scanBasePackages` to `site.asm0dey.slidev.polls` so sibling-module `@Service`/`@Repository` beans publish into the api context, made `TestcontainersConfiguration` package-public so ITs can `@Import` it, and pinned the Postgres image to `postgres:16-alpine` to match prod.
- [x] T041 [P] [US1] Integration test `QuestionLifecycleIT` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/admin/` — scenarios `[TS-003, TS-005]`. TS-005's "vote against a closed question is rejected with QUESTION_NOT_ACTIVE" tail is deferred to `VoteSubmissionIT` (T071) in US2 because the vote endpoint ships there. The IT caught a production bug: `PollRepositoryImpl` left `activated_at` populated on ACTIVE → CLOSED, violating `poll_questions_active_timestamp_ck`. Bundled fix sets `activated_at = NULL` in both the activate-with-close path and `closeActiveQuestion`.
- [x] T042 [P] [US1] Concurrency test `OneActivePerPollIT` in `backend/poll-persistence/src/test/java/site/asm0dey/slidev/polls/persistence/` — scenario `[TS-004]` (partial unique index serialises concurrent activations), plus a positive-path serial activation and a `@TS-052` idempotent-reactivate guard so the concurrent assertion has meaningful contrast.
- [x] T043 [P] [US1] Security slice test `AdminAuthWebMvcTest` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/security/` — scenarios `[TS-001, TS-040, TS-041]` (401 / 403 on admin routes, per-presenter ownership)
- [x] T044 [P] [US1] Slug test `SlugIT` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/admin/` — scenarios `[TS-010, TS-011, TS-012, TS-013, TS-014, TS-015]`. TS-015's "old public URL returns 404 / new one resolves" tail is exercised by US2 where the voter endpoint lives; this IT stops at the authoring surface (PATCH + list + QR). Bundled fixes: dropped `@Size` on `slug` in `CreatePollRequest` / `UpdatePollRequest` so length violations surface as 409 `SLUG_INVALID` (matching `@TS-011`) instead of 400 `VALIDATION_FAILED`; reordered `PollService.resolveSlug` to check `ReservedSlugs` before `SlugValidator` so `j` surfaces as `SLUG_RESERVED` per `@TS-012` (`j` is on the reserved list but also fails length validation).
- [x] T045 [P] [US1] QR test `QrEndpointIT` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/admin/` — scenario `[TS-026]` (PNG decodes to slug URL; also consumed by US2). Focused test that isolates the QR contract — `PollAuthoringIT` (T040) and `SlugIT` (T044) already decode the QR incidentally, but TS-026 deserves a named home so a QR-format regression flags here rather than hiding in an unrelated narrative.
- [x] T046 [P] [US1] Vitest component test for backoffice `PollList` page in `frontends/backoffice/src/pages/PollListPage.test.ts` (filename matches the actual `PollListPage.vue` component the router mounts; the original `PollList.test.ts` wording predated that rename). Five RED scenarios mirror @TS-002 (one row per poll with title/slug/join link/QR preview), the empty-state UX, the loading indicator, an actionable error path per Principle VI (`AdminApiError` for `AUTH_REQUIRED` surfaces a non-generic message), and the delete flow that T063 must wire. Page-under-test receives an `apiClient` prop so the test injects a `vi.fn`-stubbed `AdminApiClient` rather than mocking the global `fetch`.
- [x] T047 [P] [US1] Vitest component test for backoffice `SlugField` input (client-side slug validation, reserved-slug hint) in `frontends/backoffice/src/components/SlugField.test.ts`. Tests parameterised across `@TS-011`'s eight invalid-format examples (length 3–40 + lowercase / hyphen format) and `@TS-012`'s seven reserved slugs, and assert that reserved hits surface a distinct "reserved" message so the presenter doesn't read the rejection as a format problem. Also asserts `update:modelValue` (v-model bridge) and an `update:valid` event so the parent editor (T064) can disable submit without re-implementing the rules.

### Domain + persistence for US1

- [x] T050 [P] [US1] Domain records in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/model/`: `Poll.java`, `Question.java`, `Option.java`, `PollStatus`, `QuestionStatus` (placed under the existing `core/domain/` package that already hosts `PollStatus` / `QuestionStatus` from an earlier scaffold — `model` would split the enums from the records for no benefit)
- [x] T051 [P] [US1] Domain exceptions in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/error/`: `SlugTakenException`, `SlugInvalidException`, `SlugReservedException`, `ActivationRejectedException`, `NotOwnerException`, `NotFoundException` — already created alongside `GlobalExceptionHandler` under T026 (handler's `@ExceptionHandler` methods could not compile without them); this task is reconciled, no code diff is required.
- [x] T052 [US1] `PollRepository` jOOQ implementation in `backend/poll-persistence/src/main/java/site/asm0dey/slidev/polls/persistence/PollRepositoryImpl.java` (insert/update/delete polls, questions, options; partial-index activation; owner-scoped queries); repository interface lives in `poll-core`. Bundled with this task: `build-helper-maven-plugin` wires the on-disk codegen output onto the compile source roots, and the codegen config now emits schema-unqualified classes (`outputSchemaToDefault=true`) so the same generated source tree serves production (`public`) and tests (`public` on a fresh Flyway-owned DB) without render-schema toggles. A private `ConcurrentActivationException` translates the partial-index unique-constraint violation into a dedicated type so {@code @TS-004} can assert on it directly.
- [x] T053 [US1] `PollService` in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/service/PollService.java`: create/list/get/update/delete, activate/close with atomic "at most one ACTIVE" enforcement, ≥2-options precondition (`ACTIVATION_REJECTED`), slug derivation (kebab-case of title), slug validation via `SlugValidator` + `ReservedSlugs`, ownership checks — also adds `spring-tx` to `poll-core` (spring-context does not bring it transitively) and a `SlugDeriver` helper under `core/slug/`
- [x] T054 [US1] Unit tests `PollServiceTest` in `backend/poll-core/src/test/java/site/asm0dey/slidev/polls/core/service/` covering activation atomicity, 2-option precondition, slug derivation, ownership — pure-Java, no Spring (uses an in-file `FakePollRepository` so no Mockito dependency is introduced)

### Security + auth for US1

- [x] T055 [US1] Spring Security config `SecurityConfig` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/security/`: `/api/admin/**` requires authenticated session; `/api/polls/**`, `/api/public/**`, `/api/deck/**`, `/`, `/{slug}`, `/admin/` permitAll; CSRF config compatible with JSON login
- [x] T056 [P] [US1] `AdminUserDetailsService` backed by `admin_user` table (BCrypt) in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/security/`
- [x] T057 [P] [US1] `AdminAuthController` (`POST /api/admin/login`, `POST /api/admin/logout`) in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/admin/`

### Backoffice REST endpoints (US1)

- [x] T058 [US1] `AdminPollController` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/admin/PollController.java`: `GET/POST /api/admin/polls`, `GET/PATCH/DELETE /api/admin/polls/{pollId}`, `POST /api/admin/polls/{pollId}/open`, `POST /api/admin/polls/{pollId}/close`, `PUT /api/admin/polls/{pollId}/style`
- [x] T059 [P] [US1] `QrController` `GET /api/admin/polls/{pollId}/qr.png` producing PNG via zxing in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/admin/QrController.java` (encodes absolute public slug URL)
- [x] T060 [P] [US1] DTO records in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/admin/dto/`: `CreatePollRequest`, `UpdatePollRequest`, `CreateQuestionRequest`, `ActivateQuestionRequest`, `PollDto`, `PollDetailDto`, `QuestionDto`, `OptionDto`, `PollStyleDto` matching OpenAPI

### Backoffice SPA (US1)

- [x] T061 [P] [US1] Bootstrap `frontends/backoffice/` Vite + Vue 3 + vue-router project; `package.json` (@polls/backoffice), `vite.config.ts` outputting to `dist/` mounted under `/admin/`. Bundled with the scaffold: vue-router 4 + history base `/admin/`, vitest 2 + @vue/test-utils + jsdom for the T046/T047 component tests, `AdminApiClient` (`src/lib/admin-api.ts`) wrapping `/api/admin/**` with the same Problem-aware error mapping as the public `ApiClient`, and the OpenAPI-mirrored shared types (`Poll.publicUrl`, `LoginRequest`, `CreatePollRequest`, `UpdatePollRequest`, `CreateQuestionRequest`, `ActivateQuestionRequest`) the editor surface needs. Page files (`LoginPage`, `PollListPage`, `PollEditorPage`) ship as placeholder shells so the router compiles; T062–T064 fill them in.
- [x] T062 [P] [US1] `LoginPage.vue` in `frontends/backoffice/src/pages/` — calls `/api/admin/login`, redirects to poll list. Single-form page with username/password inputs, disables Submit while in flight, navigates to `/polls` on success and surfaces an `AdminApiError`-derived message (e.g. "Sign-in failed: check your username and password." for `AUTH_REQUIRED`) on failure. Tests-first per Principle III: a small `LoginPage.test.ts` (two RED scenarios — happy submit-and-redirect, and an `AdminApiError(401, AUTH_REQUIRED)` reject path that asserts the message is non-generic) sat in the suite before the impl landed; the broader `@TS-001` auth contract is already pinned by `AdminAuthWebMvcTest` server-side, so the front-end test focuses on the page glue.
- [x] T063 [US1] `PollListPage.vue` in `frontends/backoffice/src/pages/` — lists polls via `/api/admin/polls`, shows join link, QR preview link. Loads on mount via `AdminApiClient.listPolls`, renders loading / empty / error / list branches, and displays each row's title, slug, status, join link (`publicUrl`), QR `<img>` (sourced from `client.qrUrl(id)`), edit router-link, and a delete button gated on `window.confirm`. Error path uses `AdminApiError.code` to surface a domain-specific message (e.g. "Authentication required — please sign in again." for `AUTH_REQUIRED`) per Principle VI rather than dropping the user on a generic toast. Page accepts an optional `apiClient` prop so component tests inject a stub; production navigation uses the `defaultAdminClient` singleton added to `lib/admin-api.ts`.
- [x] T064 [US1] `PollEditorPage.vue` in `frontends/backoffice/src/pages/` — create/edit poll, add/remove questions + options, activate/close, delete. Single Vue page (per the clarifications: kept as one page with internal sections, not split) backed by a `mode: "create" | "edit"` prop. Create mode renders a fresh draft (one question, two empty options); edit mode loads via `getPoll(pollId)` and re-renders activate/close buttons against `Question.status`. Submit builds either a `CreatePollRequest` or a `UpdatePollRequest` (slug only included on edit when it actually changed) and routes to `/polls` on success. Errors decode `AdminApiError.code` into category-specific messages (`SLUG_TAKEN`, `SLUG_INVALID`, `SLUG_RESERVED`, `ACTIVATION_REJECTED`, `AUTH_REQUIRED`, `FORBIDDEN`, `NOT_FOUND`) per Principle VI; `canSubmit` gates the button on title + per-question prompt + ≥2 options + (in edit mode) `slugIsAcceptable` so the presenter can't fire a request that the server will reject for shape. Seven RED Vitest scenarios drove the implementation: create-mode default shape, happy create + redirect, server `SLUG_TAKEN` rendered as `[data-testid="poll-form-error"]`, edit-mode load populates fields, activate dispatches `activateQuestion(pollId, {questionId})`, close dispatches `closeActiveQuestion(pollId)`, and confirmed delete dispatches `deletePoll(pollId)` then routes back to `/polls`. All seven turned GREEN; full backoffice suite is 38/38 and the production `vite build` is clean.
- [x] T065 [P] [US1] `SlugField.vue` component in `frontends/backoffice/src/components/` — client-side slug validation + reserved-slug hint (mirrors `SlugValidator` + `ReservedSlugs`). Validation logic lives in `frontends/backoffice/src/lib/slug-rules.ts` (`checkSlug`) so both the field and any later editor-level pre-submit guard share one source of truth that the lock-step comment ties to the Java validator. Order matches `PollService.resolveSlug`: reserved check before length/format so `j` returns `RESERVED` (per `@TS-012` and the T044 reconcile note), which the original length-first ordering would have hidden behind `LENGTH`. The field tracks input internally so validation re-runs on each keystroke even when the parent has not yet round-tripped `update:modelValue` back into the prop, and the "Enter a slug." hint is suppressed until the input has been touched so a brand-new editor doesn't render a red error on first paint.
- [x] T066 [P] [US1] `QrPreview.vue` component rendering `<img src="/api/admin/polls/{id}/qr.png"/>`. Props: `pollId`, optional `slug` (used in the alt-text so screen readers announce which poll the QR belongs to), optional `size` (defaults to 160px). The poll id is `encodeURIComponent`-wrapped so a future schema change couldn't traverse the path; three Vitest assertions pin the URL shape, the encoding, and the alt text. `PollListPage` was retro-fitted to render `<QrPreview>` instead of an inline `<img>` so there is one canonical owner of the QR src — `@TS-026`'s decode contract is already pinned by `QrEndpointIT` (T045).

**Checkpoint**: US1 deliverable — backoffice authoring & control fully usable with no audience or slidev surfaces required.

---

## Phase 4: User Story 2 — Audience votes anonymously (P2)

**Goal**: Anonymous visitor opens `/{slug}`, sees active question or waiting state, submits a single vote, gets confirmation. Zero auth, zero PII.

**Independent Test**: Against a seeded poll with an ACTIVE question (from US1 or fixture), anonymous client fetches `/api/polls/by-slug/my-talk`, sees options, POSTs a vote, receives 201 with voteId; duplicate submission returns 409 `ALREADY_VOTED`; vote after close returns 409 `QUESTION_NOT_ACTIVE`.

### Tests for User Story 2 (write first)

- [x] T070 [P] [US2] Integration test `PublicPollViewIT` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/public_/` — scenarios `[TS-020, TS-021, TS-045]`. Also extends to @TS-046 because the cookie surface lives on the same endpoint: asserts Set-Cookie header carries HttpOnly + SameSite=Lax and that the `sp_voter` value is a bare UUID (no PII). Companion unknown-slug assertion pins the shared 404 outcome between "invalid shape" (rejected at the edge by `SlugValidator.isValidFormat`) and "valid shape, no such poll" (repo miss) — both surface `NOT_FOUND` so the Problem envelope stays consistent. Uses the admin login/create/activate flow for fixtures so the voter-facing assertions exercise the same write path a presenter would drive in production.
- [x] T071 [P] [US2] Integration test `VoteSubmissionIT` — scenarios `[TS-022, TS-023, TS-025, TS-027]`. Four scenarios anchor `POST /api/polls/{slug}/votes` end-to-end: @TS-022 happy path with sp_voter cookie taken from a preceding GET and a row landing in `votes`; @TS-023 duplicate submit (same cookie, different option) → 409 `ALREADY_VOTED` with exactly one row on disk; @TS-025 vote after the presenter closed the question → 409 `QUESTION_NOT_ACTIVE` with user-facing copy (asserts message contains no "sqlexception"/"stacktrace"/"postgres" substring per Principle VI); @TS-027/@TS-046 extra body fields (`email`, `name`) tolerated by Jackson and never reach storage (votes table has no PII columns by design). Fixtures piggy-back on the admin login/create/activate flow to exercise the full stack rather than poking jOOQ directly.
- [x] T072 [P] [US2] Concurrency test `DuplicateVoteRaceIT` in `backend/poll-persistence/src/test/java/site/asm0dey/slidev/polls/persistence/` — scenario `[TS-024]` (unique constraint on `(question_id, voter_token)`). Two threads race with a shared `CountDownLatch` start-gate; exactly one insert wins and the other surfaces as `AlreadyVotedException`, with a final `COUNT(*)` on the votes table confirming a single row for the `(question_id, v-123)` pair. Mirrors `OneActivePerPollIT`'s pattern (shared Testcontainers Postgres, Flyway-migrated schema, admin_user seed row for the `polls.owner_username` FK) so both persistence-level concurrency tests read the same way.
- [x] T073 [P] [US2] SPA catch-all test `SpaCatchAllIT` — scenarios `[TS-043, TS-044]` (catch-all serves SPA shell but excludes `/api/**`, `/admin/api/**`, static prefixes). Six scenarios: `/` and `/{slug}` forward to `/index.html`; `/api/polls/by-slug/{slug}` still returns JSON NOT_FOUND (proving the forward does not swallow the public API); `/admin/` and `/admin/polls` forward to `/admin/index.html`; `/api/admin/polls` stays gated at 401 `AUTH_REQUIRED`. Assertion uses `forwardedUrl(...)` rather than body-content matching because MockMvc records the forwarded URL on the request attribute but does not chain-execute the forward into the static resource handler — `forwardedUrl` is the authoritative check that the forward targets the right shell file.
- [x] T074 [P] [US2] Vitest component test for voter `PollView.vue` in `frontends/voter/src/pages/PollView.test.ts` (waiting state, active question render, submit flow, already-voted). Seven scenarios anchor the page: @TS-020 active question + options render; @TS-021 WAITING copy renders without options; @TS-022 click → `submitVote` called with right optionId → voted state + cache populated; @TS-023 `ALREADY_VOTED` rejection maps to voted state (duplicate tab / stale state UX); @TS-025 `QUESTION_NOT_ACTIVE` during submit triggers a second `publicPoll` call and renders the new WAITING state without leaving a sticky error; cached-voted path shows confirmation before server flag lands; 404 on load surfaces "double-check the slug" Principle-VI copy (not "something went wrong"). Injects `ApiClient` via the page's `apiClient` prop — no global fetch monkey-patching.
- [x] T075 [P] [US2] Playwright smoke `voter-happy-path.spec.ts` in `frontends/voter/e2e/` — open `/my-talk`, vote, see confirmation. Self-contained against any running backend at `PW_BASE_URL` (default `http://localhost:8080`): `beforeAll` logs in as alice, creates a poll with a time-randomised slug to avoid collisions across reruns, and activates its first question through the admin API; the test drives the voter SPA in a real browser (`page.goto(/{slug})`, asserts the question/options render, clicks the first option, asserts `data-testid="poll-voted"` appears); `afterAll` deletes the seeded poll so reruns stay clean. Executed under T136 via `bun --cwd frontends/voter run e2e` against the JAR + Postgres — not run on every commit because it needs an out-of-process backend.

### Domain + persistence for US2

- [x] T080 [P] [US2] `Vote` record + `VoteCastEvent` in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/domain/` and `core/event/`. Implementation diverges from the task's `model/` path: the existing scaffold already placed `Poll`/`Question`/`Option` under `core/domain/` (T050 reconcile note) and `VoteCastEvent` under `core/event/`; creating a third sibling package for one record would split the aggregate across directories. Bundled with this task: reconciled `@polls/shared` `VoteAccepted` type (`questionId` → `recordedAt`) to match `openapi.yaml §VoteAccepted` so the upcoming `VoteController` DTO (T086) and voter SPA (T092) share one source of truth.
- [x] T081 [P] [US2] Exceptions `AlreadyVotedException`, `QuestionNotActiveException` in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/error/` — already created alongside `GlobalExceptionHandler` under T026 (handler's `@ExceptionHandler` methods could not compile without them); this task is reconciled, no code diff is required.
- [x] T082 [US2] `VoteRepository` jOOQ implementation in `backend/poll-persistence/src/main/java/site/asm0dey/slidev/polls/persistence/VoteRepositoryImpl.java` (insert with unique-constraint handling, aggregate `GROUP BY option_id`, read `alreadyVoted` by `(question_id, voter_token)`). The `VoteRepository` interface lives in `poll-core/service/` alongside `PollRepository` so `VoteService` can be unit-tested without Spring/jOOQ (Principle V). The insert is an `INSERT ... SELECT` gated on `poll_questions.status = 'ACTIVE'`: zero inserted rows → `QuestionNotActiveException` (FR-010 / `@TS-025`); `(question_id, voter_token)` unique-index violation → `AlreadyVotedException` (FR-009 / `@TS-023`, `@TS-024`). Matches the `IntegrityConstraintViolationException | DataIntegrityViolationException` catch pattern `PollRepositoryImpl.activateQuestion` uses for the partial activation index.
- [x] T083 [US2] `VoteService` in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/service/VoteService.java`: transactional insert that verifies `ACTIVE` status inside the same tx, maps duplicate-key to `AlreadyVotedException`, publishes `VoteCastEvent` on success. The status-is-ACTIVE enforcement lives in `VoteRepositoryImpl.insert` as an `INSERT ... SELECT` gated on `poll_questions.status = 'ACTIVE'` (T082); the service adds an early-exit for the trivial no-active-question case (cheaper error classification, cleaner stack trace). `VoteCastEvent.newOptionCount` is read via `voteRepository.tally(questionId)` after insert so the broadcast value already includes the row we just wrote — listeners (T111) run as `@TransactionalEventListener(AFTER_COMMIT)` so the count they fan out matches committed state.
- [x] T084 [US2] `VoteServiceTest` in `backend/poll-core/src/test/java/site/asm0dey/slidev/polls/core/service/` — unit level with a stub repository. Six scenarios anchor the `VoteService` contract: @TS-022 happy-path insert + `VoteCastEvent.newOptionCount` reflects post-write tally; @TS-021 no active question surfaces `QuestionNotActiveException` without touching storage; an option that does not belong to the active question surfaces `NotFoundException`; @TS-023 duplicate-vote via the fake's (questionId, voterToken) index maps to `AlreadyVotedException` with only the first row landing; @TS-025 concurrent ACTIVE → CLOSED simulated via `FakeVoteRepository.simulateConcurrentClose` surfaces `QuestionNotActiveException`; and `alreadyVoted` null/blank-guard coverage for the `PublicPollView.alreadyVoted` hint. In-file fakes (`FakePollRepository`, `FakeVoteRepository`, `RecordingEventPublisher`) keep the test pure Java — no Spring, no Mockito (Principle V / VIII).

### Public REST endpoints + SPA routing (US2)

- [x] T085 [US2] `PublicPollController` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/public_/`: `GET /api/polls/by-slug/{slug}` returning `PublicPollView` (honours `sp_voter` cookie for `alreadyVoted` hint). Bundled with this task: `PublicPollView` response DTO (+ reused `QuestionDto`/`OptionDto`/`PollStyleDto` from admin to keep one projection), `VoterTokenCookie` helper that centralises the `sp_voter` read/mint (server-authoritative per the voter-identity clarification on T086/T091 — cookie is `HttpOnly`, `SameSite=Lax`, `Secure` when the request arrived over HTTPS, `Max-Age` 1 year, value is a fresh UUID so `@TS-046`'s "no personal information" assertion holds by construction), and the T088 path-variable validator: an unparseable slug surfaces as `NotFoundException` → 404 `NOT_FOUND` before the repo is touched (TS-045 accepts 400 or 404; 404 reads more cleanly to the audience).
- [x] T086 [US2] `VoteController` `POST /api/polls/{slug}/votes` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/public_/` — accepts `VoteRequest`, reads `sp_voter` cookie and sets it (HttpOnly, SameSite=Lax) when missing so the server is authoritative for duplicate detection; ignores unknown fields (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`) for `[TS-027]`. Delegates cookie read/mint to `VoterTokenCookie` (shipped with T085) so both public endpoints emit identical cookies. The body's `voterToken` field is tolerated for OpenAPI-schema compatibility but ignored — the cookie is the duplicate-detection key. `VoteRequest` and `VoteAccepted` DTOs land alongside the controller (`VoteAccepted` = `voteId` + `recordedAt`, matching the spec-authoritative schema `@polls/shared` was reconciled to in T080). Malformed slug in the path rejects with 404 `NOT_FOUND` before the service is touched — matches `PublicPollController`'s edge behaviour for `@TS-045`.
- [x] T087 [US2] `SpaForwardingConfig` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/web/` — Spring MVC routing: `/` and `/{slug:[a-z0-9-]{3,40}}` → voter SPA `index.html`; `/admin/**` → backoffice SPA `index.html`; exclude `/api/**`, `/admin/api/**`, and static asset prefixes per plan.md Constraints. Implemented as a two-handler `@Controller`: `voterShell()` matches `/` and the single-segment kebab-case slug regex (so `/api/**` and multi-segment asset URLs don't match), `backofficeShell()` matches `/admin`, `/admin/`, and `/admin/{*sub}` (Spring's path-variable rest-capture) so the backoffice Vue Router under `base=/admin/` owns its full subtree. `/api/**` never reaches this controller because `@RestController` entries win Spring's most-specific-wins dispatch. Test-scoped shells under `src/test/resources/static/` stand in for the real SPA bundles at package time — production ships the real artefacts via `scripts/build-frontends.sh` (T132).
- [x] T088 [US2] Slug path-variable validator in `PublicPollController` returning 400/404 for invalid slug format per `[TS-045]` — landed with T085. Both `PublicPollController.getBySlug` and `VoteController.submit` short-circuit with `NotFoundException` (→ 404 `NOT_FOUND`) when `SlugValidator.isValidFormat` rejects the path segment, so a malformed slug never reaches the repository / service layer. No code diff required here; ticks the phase ledger.

### Voter SPA (US2)

- [x] T090 [P] [US2] Bootstrap `frontends/voter/` Vite + Vue 3 + vue-router project (`package.json` @polls/voter); route `/` (landing) and `/:slug` (poll view). Adds `App.vue` (RouterView shell), `router/index.ts` with history base `/` and the slug param matched against the same kebab-case regex the server's `SlugValidator` enforces (so the SPA never renders `PollView` for a URL that could never be a poll), placeholder `LandingPage.vue` / `PollView.vue` (filled by T092 / T093), `shims-vue.d.ts`, and the Vitest + @vue/test-utils + jsdom devDependency set that mirrors `frontends/backoffice` so T074's component tests have a runner to start on. `vitest.config.ts` uses `jsdom` with `globals: false` and the same `src/**/*.{test,spec}.{ts,tsx}` include pattern.
- [x] T091 [P] [US2] `voterFlag.ts` util in `frontends/voter/src/lib/` — cache per-slug `alreadyVoted` booleans returned by `/api/polls/by-slug/{slug}` in `localStorage` for offline UX. Does NOT touch `sp_voter` (HttpOnly, server-authoritative per T086). Exposes `hasAlreadyVoted(slug)`, `markAlreadyVoted(slug)`, `clearAlreadyVoted(slug)`; all three degrade silently on storage failure (private mode, quota) so a broken cache never crashes the voter — Principle IV. Keys are prefixed `slidev-polls:already-voted:` so the namespace is stable against other tenants of the origin's localStorage. Six Vitest cases cover lookup default / persistence / per-slug scoping / idempotent mark / clear / empty-slug guard. The test suite leans on a new `test-setup.ts` that polyfills `window.localStorage` with a Map-backed `Storage` impl because jsdom 26 ships a `localStorage` object that lacks the Storage method bindings; centralising the polyfill in one setup file keeps individual tests short.
- [x] T092 [US2] `PollView.vue` page in `frontends/voter/src/pages/` — loads `/api/polls/by-slug/:slug`, renders WAITING vs ACTIVE, submits vote, shows confirmation, handles `ALREADY_VOTED` and `QUESTION_NOT_ACTIVE` with user-facing messages. Page takes an optional `apiClient` prop so tests inject a stub; production mode uses a fresh `ApiClient()` instance. Status machine is five-valued: `loading` / `waiting` / `active` / `voted` / `error`. Server-reported `alreadyVoted` upgrades the local `voterFlag` cache so a reload stays consistent; conversely, a WAITING response clears the cache (the previous question has rotated out). On submit, `ALREADY_VOTED` is treated as "done" (duplicate tab / stale state) rather than an error, and `QUESTION_NOT_ACTIVE` triggers a refetch with a non-technical "the question was just closed" copy per Principle IV — the audience sees whatever the poll rotated to rather than a dead-end toast.
- [x] T093 [P] [US2] `LandingPage.vue` at `/` with "enter a slug" input. Client-side slug validation uses the same `^[a-z0-9]+(-[a-z0-9]+)*$` regex + 3–40 length constraint the server's `SlugValidator` enforces, so the page never routes to a slug the server would only reject. Four Vitest cases pin the shape: well-formed slug routes to `/:slug`, empty submission surfaces "enter the slug" copy, `UPPER` surfaces a format-specific "lowercase letters…" message, too-short slug is rejected — all four also verify the router stays on the landing route when validation fails so the error message is visible and the URL doesn't lie.

**Checkpoint**: US2 deliverable — end-to-end audience voting works standalone against a US1-seeded poll.

---

## Phase 5: User Story 3 — Slidev deck live results + deck-driven activation (P3 / P2)

**Goal**: Slidev addon's `<PollResults>` component renders live tally via SSE, swaps on active-question change, survives backend loss with "paused" indicator, and — when given `questionId` + `deckToken` — auto-activates its question via `POST /api/deck/polls/{pollId}/activate`. Covers US3 (live results) and the deck-driven activation feature tagged `@US-003 @P2` in `deck-activation.feature`.

**Independent Test**: Mount `<PollResults slug="my-talk" questionId="Q1" deckToken="dtk-1"/>`; verify Q1 becomes ACTIVE within 1 s, snapshot arrives, simulated votes produce tally updates within 2 s, backend kill surfaces "paused" indicator without crashing the deck.

### Tests for User Story 3 (write first)

- [x] T100 [P] [US3] SseHub concurrency test `SseHubConcurrencyTest` in `backend/poll-realtime/src/test/java/site/asm0dey/slidev/polls/realtime/` (subscribe/unsubscribe/broadcast under racing threads; emitter failure is isolated). Bundled with this task: `SseHub` itself (`backend/poll-realtime/src/main/java/.../SseHub.java`) — the test cannot compile without the class, and the tasks' RED-then-GREEN intent is preserved in-commit (four scenarios pin thread-safe register/unregister, fan-out reach, per-emitter failure isolation per Principle IV, and a 16-thread race between register/unregister/broadcast that surfaces no runtime exception to the caller). Uses `java.lang.System.Logger` for the per-emitter failure log line so `poll-realtime` stays free of the `slf4j-api` dependency it would otherwise need under Principle VIII. T110 is consequently a reconcile task.
- [ ] T101 [P] [US3] `@WebMvcTest` `TallyBroadcastTest` in `backend/poll-realtime/src/test/java/site/asm0dey/slidev/polls/realtime/` — scenarios `[TS-030, TS-031, TS-032]` (snapshot on connect, tally on vote, snapshot on active-question change)
- [ ] T102 [P] [US3] Awaitility SSE test `StreamIT` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/public_/` — scenario `[TS-030]` end-to-end (vote → tally delivered <2 s)
- [ ] T104 [P] [US3] Deck-activation integration test `DeckActivationIT` in `backend/poll-api/src/test/java/site/asm0dey/slidev/polls/api/deck/` — scenarios `[TS-050, TS-051, TS-052, TS-053, TS-054, TS-055, TS-056, TS-057]`
- [ ] T105 [P] [US3] Vitest component tests in `frontends/slidev-component/src/components/` — `PollResults.test.ts` covering `[TS-032, TS-033, TS-034]` (stray-tally ignored, paused indicator, reconnect)
- [ ] T106 [P] [US3] Playwright smoke `slidev-results.spec.ts` in `frontends/slidev-component/e2e/` — renders results on a sample deck page against running backend

### Realtime backend (US3)

- [x] T110 [P] [US3] `SseHub` in `backend/poll-realtime/src/main/java/site/asm0dey/slidev/polls/realtime/SseHub.java` — keyed by `pollId`, thread-safe add/remove, broadcast with per-emitter failure isolation. Reconciled under T100 because the test file cannot compile without the class being present; no additional code diff is required here — the impl already satisfies the four concurrency scenarios T100 asserts.
- [ ] T111 [P] [US3] `TallyBroadcaster` in `backend/poll-realtime/src/main/java/site/asm0dey/slidev/polls/realtime/TallyBroadcaster.java` — `@EventListener(VoteCastEvent)` → fetch tally → publish `tally` event; also publishes `snapshot` on active-question change and `question-closed` on close
- [ ] T112 [US3] `StreamController` `GET /api/polls/{slug}/stream` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/public_/` — returns `SseEmitter`, emits initial `snapshot`, registers with `SseHub`, handles 404 for unknown slug

### Deck-token backoffice (US3)

- [ ] T113 [P] [US3] `DeckTokenService` in `backend/poll-core/src/main/java/site/asm0dey/slidev/polls/core/service/DeckTokenService.java` — mint (generate random bearer, SHA-256 hash, persist hash, return plaintext once), list, revoke; ownership-scoped
- [ ] T114 [P] [US3] `DeckTokenRepository` jOOQ impl in `backend/poll-persistence/src/main/java/site/asm0dey/slidev/polls/persistence/DeckTokenRepositoryImpl.java`
- [ ] T115 [US3] `AdminDeckTokenController` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/admin/DeckTokenController.java` — `GET/POST /api/admin/polls/{pollId}/deck-tokens`, `DELETE /api/admin/polls/{pollId}/deck-tokens/{tokenId}`; DTOs `DeckTokenDto`, `DeckTokenMintedDto`, `MintDeckTokenRequest`
- [ ] T116 [US3] Backoffice SPA `DeckTokensPage.vue` in `frontends/backoffice/src/pages/` — mint (shows plaintext once with copy-to-clipboard), list, revoke

### Deck activation endpoint (US3)

- [ ] T117 [US3] `DeckTokenAuthenticationFilter` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/security/` — reads `X-Deck-Token`, hashes, looks up non-revoked row; attaches a `DeckPrincipal` carrying `pollId`. Registered ONLY on `/api/deck/**` so token doesn't grant elsewhere per `[TS-057]`
- [ ] T118 [US3] `DeckActivationController` `POST /api/deck/polls/{pollId}/activate` in `backend/poll-api/src/main/java/site/asm0dey/slidev/polls/api/deck/` — verifies principal's `pollId` matches path (403 `DECK_TOKEN_POLL_MISMATCH` per `[TS-054]`); delegates to `PollService.activate` which is idempotent when already ACTIVE per `[TS-052]`

### Slidev addon (US3)

- [ ] T120 [P] [US3] Bootstrap `frontends/slidev-component/` with `package.json` declaring Slidev addon metadata (name `slidev-addon-polls`, `slidev.entry`)
- [ ] T121 [P] [US3] `PollResults.vue` component in `frontends/slidev-component/src/components/` — props `slug`, `questionId?`, `deckToken?`; subscribes via `@polls/shared/sse-client`; filters stray tallies by current snapshot's `questionId` per `[TS-032]`; renders "live updates paused" badge when sse-client reports disconnect per `[TS-033, TS-034]`; never throws
- [ ] T122 [P] [US3] `PollBar.vue` and `PollHeader.vue` sub-components in `frontends/slidev-component/src/components/` for rendering aggregate as bar chart + question header
- [ ] T123 [US3] On-mount activation hook inside `PollResults.vue`: if `questionId` and `deckToken` present AND fetched snapshot's active question ≠ `questionId`, POST `/api/deck/polls/{pollId}/activate` with `X-Deck-Token: {deckToken}`; no-op when already ACTIVE per `[TS-052]`

**Checkpoint**: US3 deliverable — live results + deck-driven activation on-slide.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T130 [P] Run `quickstart.md` end-to-end against a freshly built single JAR + Postgres (login → create poll → open voter → vote → slidev renders)
- [ ] T131 [P] Hardening: ensure unknown-field tolerance on `/api/polls/{slug}/votes` per `[TS-027, TS-046]`; verify no PII columns in `votes`
- [ ] T132 [P] Single-origin production check: `scripts/build-frontends.sh` copies the voter and backoffice SPA dists into `backend/poll-api/src/main/resources/static/` (`/`, `/admin/`); smoke test single JAR with no CORS. (Slidev addon publishing is out of scope for this feature — consumed locally from the monorepo.)
- [ ] T134 [P] Structured-logging audit — every controller entry/exit has a `correlationId` field; every Problem response carries it per `[TS-042]`
- [ ] T135 README quickstart instructions update referencing `scripts/dev.sh` and `scripts/build-frontends.sh`
- [ ] T136 Run the full feature suite (`mvnw verify` + `bun test` for plain-TS unit tests in `frontends/shared` + `bun --cwd frontends/<pkg> run test` (Vitest) for every SPA / component package + `bun --cwd frontends/voter run e2e` and `bun --cwd frontends/slidev-component run e2e` (Playwright)) and verify every `TS-###` maps to a passing assertion

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)** → no blockers
- **Foundational (Phase 2)** depends on Setup. T014→T015→T016→T017 strictly sequential (Flyway version order); T018 depends on all migrations. T028 depends on T018. T022 depends on T013. T026 depends on T024, T025.
- **US1 (Phase 3)** depends on Phase 2. T052 depends on T018 (jOOQ codegen). T053 depends on T050, T051, T052, T019, T020. T055 depends on T056. T058/T059 depend on T053. Tests T040–T047 block on T052 existing to compile but MUST be written (and fail) before T053 implementation per Principle III.
- **US2 (Phase 4)** depends on Phase 2; soft-depends on US1 for end-to-end seed data but `VoteService`/`VoteRepository` can ship on top of US1's `PollService`/`PollRepository` without waiting for the US1 SPA. T082 depends on T018. T083 depends on T053 (PollService for ACTIVE check). T087 depends on T055 (security permits public routes).
- **US3 (Phase 5)** depends on Phase 2 and on `VoteCastEvent` (T080) + `PollService.activate` (T053). T117/T118 depend on T114, T115. T121/T123 depend on T029, T030.
- **Polish (Phase 6)** depends on all desired stories complete.

### Critical path

T001 → T002 → T013 → T014 → T015 → T018 → T052 → T053 → T058 → (US1 MVP shippable) → T082 → T083 → T086 → (US2 shippable) → T110 → T111 → T112 → T117 → T118 → T123 → (US3 shippable).

### Parallel batches within a phase

- **Phase 1**: T003, T004, T005, T006, T007, T008 all [P] after T001, T002.
- **Phase 2**: T011, T012 [P] after T010. T019, T020 [P]. T023, T024, T025 [P] after T022. T029, T030 [P].
- **Phase 3 tests**: T040–T047 all [P] (different files).
- **Phase 3 impl**: T050, T051 [P]. T056, T057 [P]. T059, T060 [P] after T058 is stubbed. T061, T062, T065, T066 [P].
- **Phase 4 tests**: T070–T075 [P]. Impl: T080, T081 [P]; T090, T091, T093 [P].
- **Phase 5 tests**: T100, T101, T102, T104, T105, T106 [P]. Impl: T110, T111 [P]; T113, T114 [P]; T120, T121, T122 [P].

### Story independence

- US1 is fully independent (MVP).
- US2 depends on US1 artefacts (`PollService`, seeded polls) but its tests can use programmatic seeds and ship before US1's SPA is complete.
- US3 depends on US1 (activation service, deck-token mint) and US2 (`VoteCastEvent`). No priority inversion.

---

## Story-to-task summary

| Story | Phase | Test tasks | Impl tasks | Total |
|-------|-------|-----------:|-----------:|------:|
| Setup + Foundational | 1–2 | 4 | 27 | 31 |
| US1 (P1 MVP) | 3 | 8 | 17 | 25 |
| US2 (P2) | 4 | 6 | 13 | 19 |
| US3 (P3 / P2 deck) | 5 | 6 | 13 | 19 |
| Polish | 6 | — | 6 | 6 |
| **Total** | | **24** | **76** | **100** |

**MVP scope suggestion**: Phases 1 + 2 + 3 deliver a usable backoffice and satisfy all P1 scenarios (`@US-001`). Ship that first; US2 and US3 can follow independently.

---

## Notes

- Every story task carries its `[USn]` label; Setup / Foundational / Polish tasks intentionally omit it.
- Test spec IDs are enumerated explicitly (e.g., `[TS-010, TS-011, TS-012, TS-013, TS-014, TS-015]`) — no prose ranges.
- Gherkin scenarios in `tests/features/*.feature` are mirrored as comments above the corresponding JUnit / Vitest assertions per Principle VII; there is no BDD runner.
- Auto-commit after each task per `/iikit-07-implement` conventions.
