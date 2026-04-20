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
**Status**: reported
**GitHub Issue**: _(none)_

**Description**: Submitting the "create poll" form in the backoffice SPA fails with the user-facing error "You don't own this poll." — a fresh admin cannot create any poll.

**Reproduction Steps**:
1. From the repo root, run `task up` (or `docker compose -f compose.dev.yml up -d --build`).
2. Wait for `slidev-polls-postgres` to be healthy and `slidev-polls-backend` to bind to `:8080`.
3. Open `http://localhost:8080/admin/` in a browser and log in as `alice` / `correct-horse` (fresh admin, no pre-existing polls for this session).
4. Navigate to the "new poll" flow and fill in a title, one question with at least two options, and submit.
5. Expected: the poll is created and the UI navigates to the editor/detail view for the new poll. Observed: the form shows the error "You don't own this poll." and the poll is not usable (inline `describeError` branch for `err.code === "FORBIDDEN"` in `frontends/backoffice/src/pages/PollEditorPage.vue:118-119`). The backend `POST /api/admin/polls` either returns HTTP 403 on the create itself, or the follow-up read-after-create call returns 403 — investigation must determine which call is emitting `FORBIDDEN` since `PollController#create` does not perform an ownership check against an existing poll and should not reject the authenticated creator.

**Root Cause**: _(empty until investigation)_

**Fix Reference**: _(empty until implementation)_
