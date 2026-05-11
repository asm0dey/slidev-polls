# Three-stage build:
#   1. frontends-builder — bun install + build the voter / backoffice SPAs
#      (the Slidev addon is consumed in-deck; its dist is irrelevant for the
#      single-JAR runtime).
#   2. backend-builder   — mvnw package against the unified project, with
#      the frontend dists staged under src/main/resources/static. jOOQ
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
COPY frontends/slidev-demo ./slidev-demo
RUN bun install
RUN bun run --filter '@slidev-polls/shared'     build \
 && bun run --filter '@slidev-polls/voter'      build \
 && bun run --filter '@slidev-polls/backoffice' build

# ---------------------------------------------------------------------------

FROM bellsoft/liberica-runtime-container:jdk-25.0.3_11-glibc AS backend-builder
WORKDIR /src

COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
COPY src ./src
COPY target/generated-sources ./target/generated-sources
RUN chmod +x mvnw

# Stage the built SPAs into the locations SpaForwardingConfig expects.
COPY --from=frontends-builder /build/voter/dist/      src/main/resources/static/
COPY --from=frontends-builder /build/backoffice/dist/ src/main/resources/static/admin/

# Package the fat JAR. Skip tests and spotless — CI and `./mvnw verify` cover
# those on the host.
RUN ./mvnw package -DskipTests -Dspotless.check.skip=true

# ---------------------------------------------------------------------------

FROM bellsoft/liberica-runtime-container:jre-25.0.3_11-glibc AS runtime
WORKDIR /app
COPY --from=backend-builder /src/target/slidev-polls-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
