# Three-stage build:
#   1. frontends-builder — bun install + build the voter / backoffice SPAs
#      (the Slidev addon is consumed in-deck; its dist is irrelevant for the
#      single-JAR runtime).
#   2. backend-builder   — mvnw package against the Maven reactor, with the
#      frontend dists staged under poll-api/src/main/resources/static. jOOQ
#      generated sources must already exist in the context — `task codegen`
#      runs the codegen profile against a running Postgres before the compose
#      build is invoked by `task up`.
#   3. runtime            — JRE-only layer running the fat JAR on :8080.

FROM oven/bun:1 AS frontends-builder
WORKDIR /build
COPY frontends/package.json frontends/bun.lock frontends/tsconfig.base.json frontends/eslint.config.js frontends/playwright.config.ts ./
COPY frontends/shared ./shared
COPY frontends/voter ./voter
COPY frontends/backoffice ./backoffice
COPY frontends/slidev-component ./slidev-component
RUN bun install --frozen-lockfile
RUN bun run --filter '@polls/shared'     build \
 && bun run --filter '@polls/voter'      build \
 && bun run --filter '@polls/backoffice' build

# ---------------------------------------------------------------------------

FROM eclipse-temurin:25-jdk AS backend-builder
WORKDIR /src

# Maven wrapper + reactor POM must land before the modules so
# `./mvnw dependency:go-offline` could be wired in later without rearranging.
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
COPY backend ./backend
RUN chmod +x mvnw

# Stage the built SPAs into the locations SpaForwardingConfig expects.
COPY --from=frontends-builder /build/voter/dist/      backend/poll-api/src/main/resources/static/
COPY --from=frontends-builder /build/backoffice/dist/ backend/poll-api/src/main/resources/static/admin/

# Package the fat JAR. -am rebuilds sibling modules in-reactor; skip tests and
# spotless — CI and `./mvnw verify` cover those on the host.
RUN ./mvnw -pl backend/poll-api -am package -DskipTests -Dspotless.check.skip=true

# ---------------------------------------------------------------------------

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=backend-builder /src/backend/poll-api/target/poll-api-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
