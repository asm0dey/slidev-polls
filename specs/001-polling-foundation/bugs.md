# Bug Reports: 001-polling-foundation

## BUG-001

**Reported**: 2026-04-20
**Severity**: critical
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Backend fails to start under `compose.dev.yml` because Flyway migration V3 cannot resolve `gen_salt('bf', 10)`.

**Reproduction Steps**:
1. From the repo root, run `docker compose -f compose.dev.yml up -d` (or `task up`).
2. Wait for `slidev-polls-postgres` to report healthy and `slidev-polls-backend` to start.
3. Tail `docker compose -f compose.dev.yml logs backend`.
4. Observe the container exits after Flyway logs `Migration of schema "public" to version "3 - admin user" failed! Changes successfully rolled back.` with root cause `ERROR: function gen_salt(unknown, integer) does not exist` at `db/migration/V3__admin_user.sql` line 25. Spring then fails to start the `deckTokenAuthenticationFilter` bean because the datasource initialization aborted, and the JVM exits non-zero.

**Root Cause**: `V3__admin_user.sql` ran `CREATE EXTENSION IF NOT EXISTS pgcrypto;` followed immediately by `INSERT ... crypt('correct-horse', gen_salt('bf', 10))`. In some Postgres 16 environments Flyway's migration session could not resolve `gen_salt(unknown, integer)` even after the extension create had logged success, which aborted V3 and left the application context unable to initialize the DeckTokenAuthenticationFilter chain. The dependency on a contrib extension being resolvable in the migration's session made V3 fragile across environments.

**Fix Reference**: T-B001 in `tasks.md`. Dropped the `CREATE EXTENSION pgcrypto` + `crypt(..., gen_salt(...))` dance in `V3__admin_user.sql` and seeded alice with a precomputed BCrypt hash (`$2a$10$9CvgAtcz7/XAUwDSz/7Uuu7K85lbQAGe8EqYH8Wvh4auT.ZO1Siai`). Spring Security's `BCryptPasswordEncoder` validates any `$2a$` hash regardless of per-install salt, so the seeded-login contract is unchanged and the migration no longer depends on pgcrypto being resolvable at Flyway time.

---

## BUG-002

**Reported**: 2026-04-20
**Severity**: high
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Starting the `compose.dev.yml` stack leaves both frontends unreachable — the backoffice returns HTTP 500 and the voter shell loads but cannot render anything.

**Reproduction Steps**:
1. From the repo root, run `task up` (or `docker compose -f compose.dev.yml up -d --build`).
2. Wait for `slidev-polls-postgres` to report healthy and `slidev-polls-backend` to finish Flyway migrations and bind to `:8080`.
3. Open `http://localhost:8080/admin/` in a browser (or `curl -i http://localhost:8080/admin/`). Expected: the backoffice SPA shell. Observed: HTTP 500 with body `{"error":"Internal Server Error","path":"/admin/"}`. Backend logs show `java.lang.StackOverflowError` originating in `ServletRequestWrapper.getRemoteAddr` during dispatcher-servlet handling, with thousands of stack frames — the request is being forwarded to itself.
4. Open `http://localhost:8080/` in a browser. Expected: either a landing/voter view or a clear 404 when no poll slug is supplied. Observed: HTTP 200 serving `static/index.html`, but the voter SPA renders a blank app because `/` is not a `{slug}` route and there is no content for the root. End result: no frontend is actually usable from the compose stack.

**Root Cause**: `SpaForwardingConfig` used `@GetMapping("/admin/{*sub}")` to forward every `/admin/**` request to the shell at `/admin/index.html`. Spring's `forward:` prefix re-dispatches through the DispatcherServlet, so the forwarded URL matched the same `{*sub}` pattern (with `sub=index.html`) and forwarded again ad infinitum, exhausting the stack in `ServletRequestWrapper.getRemoteAddr`. Spring's `PathPattern` has no way to exclude dotted segments from a `{*name}` multi-segment capture, so no pattern tweak could stop the loop while keeping the controller shape. The voter half at `/` looked broken as a side-effect because the admin shell never rendered — the voter SPA itself already has a `LandingPage` route for `/` and was fine.

**Fix Reference**: T-B003 in `tasks.md`. Replaced the `/admin/{*sub}` controller mapping with a `WebMvcConfigurer` resource handler under `classpath:/static/admin/` whose `PathResourceResolver` serves the real file when it exists (shell, hashed assets) and falls back to `index.html` for dot-less deep links (e.g. `/admin/polls/42`). Dotted missing paths (e.g. `/admin/assets/gone.js`) now surface a 404 so the browser never tries to execute HTML as JavaScript; a new `NoResourceFoundException` handler in `GlobalExceptionHandler` maps that to a proper 404 Problem envelope instead of the generic 500 catch-all. Added `/admin` (no trailing slash) → `/admin/` redirect so Vue Router's base resolves correctly. `SpaCatchAllIT` was extended with five new assertions locking the new direct-serve behaviour (shell body contents, shell content-type, literal `/admin/index.html`, missing-asset 404, trailing-slash redirect).

---

## BUG-003

**Reported**: 2026-04-20
**Severity**: high
**Status**: reported
**GitHub Issue**: _(none)_

**Description**: Admin SPA renders an "unauthenticated" state without a login form, leaving users no way to authenticate.

**Reproduction Steps**:
1. From the repo root, run `task up` (or `docker compose -f compose.dev.yml up -d --build`).
2. Wait for `slidev-polls-postgres` to report healthy and `slidev-polls-backend` to finish migrations and bind to `:8080`.
3. With no existing admin session, open `http://localhost:8080/admin/` in a browser.
4. Expected: a login form is rendered so the user can authenticate. Observed: the admin shell reports the user is not authenticated but shows no login form — there is no way to sign in from the UI.

**Root Cause**: _(empty until investigation)_

**Fix Reference**: _(empty until implementation)_

---

## BUG-004

**Reported**: 2026-04-20
**Severity**: high
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Submitting the "create poll" form in the backoffice SPA fails with the user-facing error "You don't own this poll." — a fresh admin cannot create any poll.

**Reproduction Steps**:
1. From the repo root, run `task up` (or `docker compose -f compose.dev.yml up -d --build`).
2. Wait for `slidev-polls-postgres` to be healthy and `slidev-polls-backend` to bind to `:8080`.
3. Open `http://localhost:8080/admin/` in a browser and log in as `alice` / `correct-horse` (fresh admin, no pre-existing polls for this session).
4. Navigate to the "new poll" flow and fill in a title, one question with at least two options, and submit.
5. Expected: the poll is created and the UI navigates to the editor/detail view for the new poll. Observed: the form shows the error "You don't own this poll." and the poll is not usable (inline `describeError` branch for `err.code === "FORBIDDEN"` in `frontends/backoffice/src/pages/PollEditorPage.vue:118-119`). The backend `POST /api/admin/polls` either returns HTTP 403 on the create itself, or the follow-up read-after-create call returns 403 — investigation must determine which call is emitting `FORBIDDEN` since `PollController#create` does not perform an ownership check against an existing poll and should not reject the authenticated creator.

**Root Cause**: `POST /api/admin/polls` is CSRF-protected (`SecurityConfig` enables `CookieCsrfTokenRepository` for everything under `/api/admin/**` except `/api/admin/login`), but `AdminApiClient.send` never reads the `XSRF-TOKEN` cookie nor attaches an `X-XSRF-TOKEN` request header on state-changing methods. Spring's `CsrfFilter` therefore rejects the create call with `AccessDeniedException` from `InvalidCsrfTokenException`; `ProblemAccessDeniedHandler` translates that to HTTP 403 with body `{"code":"FORBIDDEN","message":"access denied"}`, and `describeError` in `PollEditorPage.vue` maps `err.code === "FORBIDDEN"` to "You don't own this poll." — the misleading text since the same code is also used for legitimate ownership rejections elsewhere. The 403 originated from the create POST itself, not from any follow-up call. `PollController#create` is never reached, so no ownership logic was involved.

**Fix Reference**: T-B007 in `tasks.md`. Taught `AdminApiClient.send` to look up the `XSRF-TOKEN` cookie via `document.cookie` and attach `X-XSRF-TOKEN` on every state-changing call to `/api/admin/**` except `/api/admin/login`. Spring's `CookieCsrfTokenRepository.withHttpOnlyFalse()` plus `CsrfTokenRequestAttributeHandler.setCsrfRequestAttributeName(null)` already eagerly writes the cookie on every response (including the 401 the SPA gets before login and the 204 from the login endpoint), so the cookie is always present when the SPA needs to echo it. `AdminApiClient` is now wired with a `cookieReader` option (defaulting to `() => document.cookie`) so unit tests can supply a deterministic cookie string when exercising the CSRF header path.

---

## BUG-005

**Reported**: 2026-04-20
**Severity**: high
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Opening the voter-facing page for a created poll (e.g. `http://localhost:8080/bug-004-regression-e2e`) renders an empty shell — no poll content is shown. The URL is taken directly from the polls list in the backoffice SPA, so a link the admin UI advertises as valid leads to a blank page for voters.

**Reproduction Steps**:
1. From the repo root, run `task up` (or `docker compose -f compose.dev.yml up -d --build`).
2. Wait for `slidev-polls-postgres` to be healthy and `slidev-polls-backend` to bind to `:8080`.
3. Open `http://localhost:8080/admin/` and log in as `alice` / `correct-horse`.
4. Create at least one poll (or reuse an existing one such as the BUG-004 regression poll with slug `bug-004-regression-e2e`).
5. From the admin polls view, copy the voter link surfaced for that poll — e.g. `http://localhost:8080/bug-004-regression-e2e`.
6. Open that URL in a fresh browser tab (no admin session assumed).
7. Expected: the voter SPA renders the poll (question, options, vote controls) for the slug. Observed: the shell loads but the page is empty — no poll content is rendered, and the user has no way to vote.

**Root Cause**: Two stacked faults kept the voter SPA from rendering any content for `/{slug}` in production.

1. Vue Router could not build its matcher for the poll route. The route was declared `/:slug([a-z0-9]+(-[a-z0-9]+)*)`, but vue-router's path parser does not support nested capture groups combined with a `*` quantifier — it synthesised the broken regex `/^/((?:[a-z0-9]+(-[a-z0-9]+)(?:/(?:[a-z0-9]+(-[a-z0-9]+))*)?\)/?$/i` (note the unbalanced parentheses) and the browser threw `SyntaxError: Invalid regular expression … Unterminated group` at app boot. `createApp(App).use(router).mount("#app")` rejected before any view rendered, so the browser kept `<div id="app"></div>` as-is — the "empty shell" the bug report described. The component tests in `PollView.test.ts` and `LandingPage.test.ts` mount each page directly, bypassing the router wiring, so they did not catch the parse failure.
2. Once the router parse was fixed, the next render failed with `TypeError: Failed to execute 'fetch' on 'Window': Illegal invocation`. `ApiClient` captured the global `fetch` as `this.fetchImpl = opts.fetchImpl ?? fetch` and later invoked it as `this.fetchImpl(…)`. Browsers require `window.fetch` to be called with `this === window` (or `undefined`); storing the function on an instance shifted the receiver to the ApiClient and Chrome rejected the call. `AdminApiClient` had already learned this lesson (`fetch.bind(globalThis)`), but `ApiClient` in `@polls/shared` had not. Vitest injects a stub `fetchImpl` in every test so the prod-only path was invisible until now.

**Fix Reference**: T-B009 in `tasks.md`. Two edits, both surgical:

1. `frontends/voter/src/router/index.ts` — replaced the `:slug` regex with the same character-class-only form the server's `SpaForwardingConfig` uses, `[a-z0-9-]{3,40}`. Stricter shape checks (no leading/trailing hyphen, no `--`) stay server-side in `SlugValidator` where they already live; the backend returns 404 for any slug that makes it through the router but fails full validation, and `PollView` renders the existing "No poll with that link" copy for that case. Added `frontends/voter/src/router/router.test.ts` booting the real router so the regression surfaces at the path-parse layer a component test could not reach.
2. `frontends/shared/src/api-client.ts` — `ApiClient` now captures `fetch.bind(globalThis)` as its default, matching the existing `AdminApiClient` pattern and eliminating the `Illegal invocation` when the voter SPA runs in a real browser.

Verified end-to-end against `compose.dev.yml`: `GET http://localhost:8080/bug-004-regression-e2e` now renders the poll title + WAITING copy for a freshly-created poll, and after activating the question shows the prompt + both option buttons (`data-testid="poll-active"`, `option-{id}`). Full test suite green — backend `./mvnw verify -pl backend/poll-api -am` (all 53 api ITs), `frontends/shared` `bun test` (6/6), `frontends/voter` vitest (20/20 — was 17, +3 new router cases), `frontends/backoffice` vitest (50/50), `frontends/slidev-component` vitest (9/9).

---

## BUG-006

**Reported**: 2026-04-21
**Severity**: medium
**Status**: reported
**GitHub Issue**: _(pending outbound creation)_

**Description**: `go-task test:e2e:voter` fails with `ECONNREFUSED ::1:8080` because the Taskfile target assumes a running backend at `PW_BASE_URL` (default `http://localhost:8080`) but does not start one. A developer running the canonical e2e command from a clean checkout hits an opaque socket error instead of a green suite.

**Reproduction Steps**:
1. From the repo root, on a host with no backend listening on port 8080 and no `compose.dev.yml` stack up.
2. Run `go-task test:e2e:voter`.
3. Observe Playwright fails the single voter spec at `frontends/voter/e2e/voter-happy-path.spec.ts:82:3` with `apiRequestContext.post: connect ECONNREFUSED ::1:8080` on the first `POST http://localhost:8080/api/admin/login` inside `loginAsAlice`.
4. Expected: the task provisions (or launches) a backend on the configured `PW_BASE_URL` for the duration of the spec, runs the voter happy-path against it, and tears it down — mirroring how `test:e2e:slidev` and CI wire the stack.

**Root Cause**: _(empty until investigation)_

**Fix Reference**: _(empty until implementation)_
