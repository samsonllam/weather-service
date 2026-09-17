# Build stage: compile and package with the same Maven and JDK the project is developed on.
# Tests are not run here; run ./mvnw verify (or CI) before building an image.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home app
COPY --from=build /workspace/target/weather-service-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
