# Four-stage build:
#   1. frontends-builder — bun install + build the voter / backoffice SPAs
#      (the Slidev addon is consumed in-deck; its dist is irrelevant for the
#      single-JAR runtime).
#   2. backend-builder   — mvnw package against the unified project, with
#      the frontend dists staged under src/main/resources/static. jOOQ
#      generated sources must already exist in the context — `task codegen`
#      runs the codegen profile against a running Postgres before the compose
#      build is invoked by `task up`.
#   3. aot-trainer       — distroless JRE that extracts the fat JAR into the
#      Spring Boot layered form and performs the AOT cache training run.
#      Must share the runtime stage's base image so the cached `lib/modules`
#      hash matches at load time.
#   4. runtime           — distroless JRE serving the extracted app + cache
#      on :8080. Per-layer COPY keeps `lib/` (big, rarely changes) in its
#      own Docker layer separate from the app jar and AOT cache.

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

FROM bellsoft/hardened-liberica-runtime-container:jdk-25.0.3_11-glibc AS backend-builder
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

FROM bellsoft/hardened-liberica-runtime-container:jre-25.0.3_11-distroless-glibc AS aot-trainer
WORKDIR /app
COPY --from=backend-builder /src/target/slidev-polls-0.0.1-SNAPSHOT.jar /tmp/slidev-polls-0.0.1-SNAPSHOT.jar

# Extract the fat JAR into Spring Boot 4 layered form (/app/<jar> + /app/lib/).
# The output jar takes its name from the input jar's filename, so the input
# must keep its release-style name to match the runtime ENTRYPOINT.
RUN ["java", "-Djarmode=tools", "-jar", "/tmp/slidev-polls-0.0.1-SNAPSHOT.jar", "extract", "--destination", "/app"]

# AOT training run. A dummy DataSource URL plus Hikari fail-fast disabled and
# Flyway off lets the context reach onRefresh without a live Postgres — JOOQ
# DSLContext still wires up, so user beans depending on it are AOT-cached.
RUN ["java", \
     "-Dspring.aot.enabled=true", \
     "-Dspring.flyway.enabled=false", \
     "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/aot-training", \
     "-Dspring.datasource.hikari.initialization-fail-timeout=-1", \
     "-XX:AOTCacheOutput=/app/app.aot", \
     "-Dspring.context.exit=onRefresh", \
     "-jar", "/app/slidev-polls-0.0.1-SNAPSHOT.jar"]

# ---------------------------------------------------------------------------

FROM bellsoft/hardened-liberica-runtime-container:jre-25.0.3_11-distroless-glibc AS runtime
WORKDIR /app
COPY --from=aot-trainer /app/lib/ /app/lib/
COPY --from=aot-trainer /app/slidev-polls-0.0.1-SNAPSHOT.jar /app/
COPY --from=aot-trainer /app/app.aot /app/
EXPOSE 8080
ENV JDK_JAVA_OPTIONS=""
ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-XX:AOTCache=app.aot", "-jar", "slidev-polls-0.0.1-SNAPSHOT.jar"]
