# syntax=docker/dockerfile:1.7
# BuildKit is required (Docker 23+ uses it by default) for the `RUN --mount=type=cache`
# line in the frontend stage.
#
# What makes a rebuild cheap is that dependencies are resolved *before* the sources are
# copied in — originally they were not, so editing one Java file re-downloaded the whole
# Flink / Kafka / Spring tree, several hundred megabytes, on every build. The two stages
# reach that differently on purpose: npm uses a cache mount, Maven uses a layer keyed on
# pom.xml (see the backend stage — a cache mount is invisible to `cache-to: type=gha`,
# which is what CI reuses between runs).
#
# Base images are pinned by digest so the same commit always builds on the same JRE and
# Node; Dependabot's `docker` ecosystem proposes the bumps (.github/dependabot.yml).
# Keep the human-readable tag in front of the digest — it is the only thing that says
# what the digest is.

# --- Stage 1: Build Frontend ---
FROM node:26.7.0-alpine@sha256:aadf416b2cdce311a8811ba3f0608a61b77dbf997500e2eafe781b51f6a0b019 AS frontend-builder
WORKDIR /app

# Manifest first: this layer is reused as long as the dependencies do not move.
COPY src/main/webapp/package.json src/main/webapp/package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

# Copier le reste du code frontend et build
COPY src/main/webapp/ ./
# The output directory is pinned here, not taken from vite.config.ts. That config sets
# `build.outDir: '../resources/static'` — the path Maven wants when the SPA is built in
# place — which from /app resolves to /resources/static, while the next stage copies
# /app/dist. The image build had been failing on exactly that ("/app/dist": not found)
# since the outDir was introduced, so every tag published a Release JAR but no GHCR
# image.
#
# The two binaries are invoked directly rather than through `npm run build -- <args>`:
# where npm forwards those extra arguments depends on the npm version. With the npm in
# node:24 they reached `tsc`, which does not know `--emptyOutDir`, printed its help and
# exited 1; with npm 10 they reached `vite build` as intended. Calling the binaries
# leaves nothing to interpret. This must stay equivalent to the `build` script in
# package.json (`tsc && vite build`) — the type check is not optional.
RUN ./node_modules/.bin/tsc \
 && ./node_modules/.bin/vite build --outDir /app/dist --emptyOutDir

# --- Stage 2: Build Backend ---
FROM maven:3-eclipse-temurin-26@sha256:6206ae5e460fbc803743b53addc31c5caca04582cf6a99f0f91df29c54954b52 AS backend-builder
WORKDIR /app

# The dependency tree resolved in its own layer, keyed on pom.xml alone, so it is
# re-fetched only when the pom actually moves.
#
# This used to be a `RUN --mount=type=cache,target=/root/.m2` on the package step. That
# makes a *local* rebuild cheap but does nothing for CI: BuildKit cache mounts are not
# exported with the image layers, so `cache-to: type=gha` carried none of it and the
# docker job of ci.yml re-downloaded the whole Flink/Kafka/Spring tree on every single
# run. A layer is the one form of cache that survives between runners — hence the
# in-image repository (`-Dmaven.repo.local`) rather than a mount. This stage is
# discarded, so its size costs nothing in the published image.
#
# `|| true` is deliberate: go-offline is advisory here. It misses plugin dependencies
# that only resolve later and fails outright on some plugin combinations, while the
# `package` below runs online and simply fetches whatever was missed. A cache-warming
# step must never be able to fail a build.
COPY pom.xml ./
RUN mvn -B -Dmaven.repo.local=/app/.m2repo -P '!build-frontend' dependency:go-offline || true

# Copier les sources backend
COPY src/main/java ./src/main/java
COPY src/main/resources ./src/main/resources

# Récupérer les assets frontend construits dans l'étape précédente
# L'application Spring Boot sert le contenu de src/main/resources/static
COPY --from=frontend-builder /app/dist ./src/main/resources/static

# `clean` is dead weight here — the stage starts from an empty /app, there is nothing
# to clean, and it cannot help correctness. The frontend profile is off because the SPA
# was already built in stage 1; leaving it on would download a second Node toolchain
# and rebuild the very same bundle.
RUN mvn -B -Dmaven.repo.local=/app/.m2repo package -P '!build-frontend' -DskipTests

# Split the fat JAR into Spring Boot's four standard layers, ordered from the most stable
# to the least: `dependencies` (Flink, Kafka, Spring — hundreds of megabytes that move
# only when the pom does), `spring-boot-loader`, `snapshot-dependencies`, `application`
# (our own classes and the SPA, a few megabytes). Copied as four separate COPY lines
# below, they become four image layers, so a code change re-pushes and re-pulls only the
# last one instead of the whole JAR again.
#
# `-Djarmode=tools` is the Spring Boot 3.3+ entry point; the older `layertools` no longer
# exists. `--launcher` extracts the runnable layout, which JarLauncher starts.
RUN cp target/kafka-sql-explorer-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Stage 3: Runtime ---
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app

# The app writes two things under its working directory: logs/kafkaexplorer.log
# (logging.file.name) and data/flink-jobs.json (explorer.flink-job-store-path). They are
# created and owned here so the process does not need root to write them — a named volume
# mounted on either path inherits this ownership, which a bind-mounted host file would not.
RUN addgroup -g 10001 -S app \
 && adduser -u 10001 -S -G app -h /app app \
 && mkdir -p /app/logs /app/data \
 && chown -R 10001:10001 /app

# The four layers, most stable first — the order is the whole point, a COPY invalidates
# every layer after it. `snapshot-dependencies` is empty on a release build; COPY of an
# empty directory is a no-op, and keeping the line means a SNAPSHOT dependency lands in
# its own layer rather than in the application one.
COPY --from=backend-builder --chown=10001:10001 /app/extracted/dependencies/ ./
COPY --from=backend-builder --chown=10001:10001 /app/extracted/spring-boot-loader/ ./
COPY --from=backend-builder --chown=10001:10001 /app/extracted/snapshot-dependencies/ ./
COPY --from=backend-builder --chown=10001:10001 /app/extracted/application/ ./

EXPOSE 8080

# Kept in step with Dockerfile.release, which is what a tag actually publishes — the two
# runtime surfaces drifting apart is how you end up debugging an image that behaves
# unlike the one you built locally.
#
# Without this the JVM sizes its heap from the *host* memory it can see, which on a
# container with a memory limit means being OOM-killed rather than running a GC.
# JAVA_TOOL_OPTIONS is picked up by the JVM itself, so the entrypoint stays exec-form.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# --- Class Data Sharing ---------------------------------------------------------------
# A training run at build time, so the first boot of every container does not re-parse and
# re-verify the same classes. Measured on this application against the layout above — the
# extracted layers behind `JarLauncher`, i.e. exactly what runs here — with no broker
# listening: **7.74 s without the archive, 6.39 s with it**, and 12 911 of the 24 343
# classes loaded come from it, application classes included.
#
# `-Dspring.context.exit=onRefresh` is what makes this affordable: the context is built
# and the JVM exits at the refresh event, which lands after Tomcat is *initialized* and
# before it starts listening — so the run costs a few seconds and binds nothing. It does
# reach the two startup state restores, which find no broker here and give up on their
# shared budget — a few seconds of build, and the reason that budget is now shared.
#
# Three things are deliberate. The archive is written in the *runtime* stage and not in a
# `--platform=$BUILDPLATFORM` one like the extractor: a CDS archive is architecture-
# specific, so the arm64 variant has to produce its own, under emulation, which is the
# real cost of this (a slower multi-arch release build) alongside ~90 MB of image. The
# flag goes on the ENTRYPOINT and not into JAVA_TOOL_OPTIONS: that variable is documented
# as replaced wholesale when an operator sets it, so a container started with a tuned
# `-XX:MaxRAMPercentage` would have silently dropped the archive and paid for it anyway.
# It also keeps the flag away from this training run, where `-XX:SharedArchiveFile` and
# `-XX:ArchiveClassesAtExit` together are an error. And it stays `-Xshare:auto`, the
# default: a JVM that cannot map the archive must start anyway rather than refuse to boot
# over an optimisation — which is precisely why ci.yml asserts the archive really is
# mapped, or a mis-wiring would ship ~90 MB of dead weight in silence.
#
# Output is deliberately not swallowed: a training run that fails fails the build, and a
# build that fails with no reason on screen is the defect this whole audit removed.
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa \
         -Dspring.context.exit=onRefresh \
         org.springframework.boot.loader.launch.JarLauncher \
 && rm -f /app/logs/*.log \
 && chown 10001:10001 /app/application.jsa

# `management.endpoints.web.exposure.include` already exposes health, groups included.
# The start period is generous on purpose: the embedded Flink runtime takes its time on
# a cold start, and a container reported unhealthy while it is still legitimately booting
# is worse than no healthcheck at all.
#
# Liveness, not the aggregate `/actuator/health`. The aggregate carries the Kafka
# readiness indicator, so a broker restart would flip the container to `unhealthy` — and
# the app is still perfectly alive then, still serving its UI, still able to be repointed
# at another cluster from the Settings page. Liveness answers "is this process able to
# serve", which is the only question Docker acts on.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=10 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

# Non-root, by numeric id so a Kubernetes `runAsNonRoot` admission check can see it. What
# used to keep this image on root was docker-compose.yml bind-mounting a host file onto
# /app/logs/kafkaexplorer.log; that mount was broken anyway (the host file does not exist,
# so Docker created a directory and Logback could not open its log) and is now a named
# volume, which inherits the ownership set above.
USER 10001:10001

# The extracted layout, not `-jar app.jar` — there is no fat JAR in this image any more.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/application.jsa", "org.springframework.boot.loader.launch.JarLauncher"]
