# syntax=docker/dockerfile:1

# ---- Build: compile and package the Spring Boot jar ------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/ticket-booking-system-*.jar extract --destination /build/app

# ---- Runtime: JRE only, non-root, with an AppCDS archive for faster cold starts ---------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Commit shown at /actuator/info. Pass --build-arg GIT_COMMIT=$(git rev-parse --short HEAD);
# Railway supplies RAILWAY_GIT_COMMIT_SHA automatically.
ARG GIT_COMMIT
ARG RAILWAY_GIT_COMMIT_SHA
ENV APP_COMMIT=${GIT_COMMIT:-${RAILWAY_GIT_COMMIT_SHA:-unknown}}

RUN groupadd --system app && useradd --system --gid app --no-create-home app && chown app:app /app

# Owned by app at copy time (a later chown -R would duplicate every file into a new layer).
COPY --from=build --chown=app:app /build/app/lib lib
COPY --from=build --chown=app:app /build/app/ticket-booking-system-*.jar app.jar
USER app

# AppCDS training run: start the context and exit as soon as it is refreshed, recording the
# loaded classes. Nothing connects to a database here: Flyway is off and connection pools only
# open on first use. The archive must be built by the same JVM that runs it, hence this stage.
RUN java -XX:ArchiveClassesAtExit=app.jsa \
        -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false \
        -Dspring.datasource.url=jdbc:postgresql://localhost:1/training \
        -Dspring.datasource.username=training \
        -Dspring.datasource.password=training \
        -Dapp.jwt.secret=cds-training-secret-not-used-at-runtime \
        -Dlogging.level.root=WARN \
        -jar app.jar

EXPOSE 8085

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]
