# ---- build stage ----
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Warm the dependency cache (best-effort) before copying sources.
COPY pom.xml .
RUN mvn -B -DskipTests dependency:resolve-plugins dependency:resolve || true

COPY src ./src
RUN mvn -B -DskipTests clean package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN useradd -r -u 1001 appuser
COPY --from=build /workspace/target/*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
