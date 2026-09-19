# ---- build stage -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached until the pom changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/seatsurge-*.jar extract --layers --launcher --destination extracted

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system seatsurge && useradd --system --gid seatsurge seatsurge

# Layered copy: dependencies change rarely, application classes often.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER seatsurge
EXPOSE 8080

# The container relies on external Postgres/Redis (see the "app" profile in compose.yaml).
ENV SPRING_DOCKER_COMPOSE_ENABLED=false \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Health: GET /actuator/health (with liveness/readiness groups). The slim JRE image has no curl,
# so probe it from the orchestrator (compose healthcheck, Kubernetes probes) instead of a HEALTHCHECK.

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
