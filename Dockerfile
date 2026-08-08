# syntax=docker/dockerfile:1.7
# BuildKit is required (Docker 23+ uses it by default) for the `RUN --mount=type=cache`
# lines below. They are what makes a rebuild cheap: the Maven repository and the npm
# cache live in build caches shared across builds instead of inside image layers, so
# editing one Java file no longer re-downloads the Flink / Kafka / Spring dependency
# tree — several hundred MB that used to be fetched on every single build, because the
# sources were copied in before any dependency resolution had happened.

# --- Stage 1: Build Frontend ---
FROM node:24.0.0-alpine AS frontend-builder
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
FROM maven:3.9-eclipse-temurin-21 AS backend-builder
WORKDIR /app

# Copier le pom.xml
COPY pom.xml ./

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
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B package -P '!build-frontend' -DskipTests

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
FROM eclipse-temurin:21-jre-alpine
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

# `management.endpoints.web.exposure.include` already exposes health. The start period
# is generous on purpose: the embedded Flink runtime takes its time on a cold start,
# and a container reported unhealthy while it is still legitimately booting is worse
# than no healthcheck at all.
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
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
