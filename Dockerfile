# --- Stage 1: Build Frontend ---
FROM node:24.0.0-alpine AS frontend-builder
WORKDIR /app

# Copier uniquement les fichiers nécessaires pour installer les dépendances (cache optimization)
COPY src/main/webapp/package.json src/main/webapp/package-lock.json* ./
RUN npm ci

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

# Build du backend
RUN mvn clean package -P !build-frontend -DskipTests

# --- Stage 3: Runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copie du JAR généré
COPY --from=backend-builder /app/target/kafka-sql-explorer-*.jar app.jar

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
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=10 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# Deliberately still root. Dropping to a non-root user is the right thing to do, but not
# in a release-pipeline fix: `logging.file.name: logs/kafkaexplorer.log` makes the app
# write under /app, and docker-compose.yml bind-mounts a host path onto
# /app/logs/kafkaexplorer.log — a host file Docker creates root-owned.
ENTRYPOINT ["java", "-jar", "app.jar"]
