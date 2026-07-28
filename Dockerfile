# --- Stage 1: Build Frontend ---
FROM node:20.11.1-alpine AS frontend-builder
WORKDIR /app

# Copier uniquement les fichiers nécessaires pour installer les dépendances (cache optimization)
COPY src/main/webapp/package.json src/main/webapp/package-lock.json* ./
RUN npm ci

# Copier le reste du code frontend et build
COPY src/main/webapp/ ./
RUN npm run build

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
RUN mvn clean package -DskipTests

# --- Stage 3: Runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copie du JAR généré
COPY --from=backend-builder /app/target/kafka-sql-explorer-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
