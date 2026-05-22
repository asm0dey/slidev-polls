# Three-stage build for the Quarkus GraalVM native image:
#   1. frontends-builder — bun install + build the voter / backoffice SPAs
#      (the Slidev addon is consumed in-deck; its dist is irrelevant for the
#      single-binary runtime).
#   2. native-builder    — the Quarkus Mandrel builder image (GraalVM + JDK 21).
#      Runs `mvnw package -Dnative` against the unified project with the frontend
#      dists staged under src/main/resources/META-INF/resources. jOOQ generated
#      sources must already exist in the build context — `task codegen` runs the
#      codegen profile against a Postgres on the host before the compose build is
#      invoked by `task up` (codegen needs Docker, which this stage doesn't have).
#      Produces target/slidev-polls-*-runner, a dynamically-linked glibc binary.
#   3. runtime           — quarkus-micro-image (glibc, NOT Alpine/musl) serving
#      the native binary on :8080 as a non-root user.
#
# Env overrides honoured by the runtime container (Quarkus maps env vars to
# config automatically):
#   QUARKUS_DATASOURCE_POSTGRES_JDBC_URL  — overrides the prod default localhost URL
#   QUARKUS_DATASOURCE_POSTGRES_USERNAME  — DB user
#   QUARKUS_DATASOURCE_POSTGRES_PASSWORD  — DB password
#   SP_SESSION_KEY                        — session cookie encryption key
#   APP_DATABASE_VENDOR                   — postgres (default) or h2

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

FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS native-builder
USER root
WORKDIR /src

COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
COPY src ./src
COPY target/generated-sources ./target/generated-sources
RUN chmod +x mvnw

# Stage the built SPAs into the locations the static-resource server expects
# (voter shell + assets/ at the resource root, backoffice under admin/).
COPY --from=frontends-builder /build/voter/dist/      src/main/resources/META-INF/resources/
COPY --from=frontends-builder /build/backoffice/dist/ src/main/resources/META-INF/resources/admin/

# Build the native binary. This stage already IS the GraalVM environment, so we
# do NOT pass -Dquarkus.native.container-build=true. Native build args (e.g. the
# initialize-at-run-time list) live in application.properties so they apply here.
# Skip tests and spotless — CI and `./mvnw verify` cover those on the host.
# -Ddb.codegen.skip=true: jOOQ codegen needs a Docker daemon (Testcontainers),
# which is unavailable inside `docker build`; the generated sources are COPYed
# in above, so codegen is skipped here.
RUN ./mvnw package -Dnative -Ddb.codegen.skip=true -Dmaven.test.skip=true -Dspotless.check.skip=true

# ---------------------------------------------------------------------------

FROM quay.io/quarkus/quarkus-micro-image:2.0 AS runtime
WORKDIR /work/
RUN chown 1001 /work \
    && chmod "g+rwX" /work \
    && chown 1001:root /work
COPY --from=native-builder --chown=1001:root /src/target/*-runner /work/application
RUN chmod 775 /work/application

EXPOSE 8080
USER 1001

ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0", "-Dquarkus.http.port=8080"]
