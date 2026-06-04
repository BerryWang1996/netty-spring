# Multi-node cluster demo image — runs the netty-spring demo app as one cluster node.
# Build context MUST be the repo root so the Maven-built jar is COPYable. Build the jar first:
#   ./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# target/ holds exactly one *.jar (the Spring Boot fat jar; the thin jar is *.jar.original),
# so this glob is version-agnostic — no RC string to maintain here.
COPY demo-netty-web-spring-boot-starter/target/*.jar app.jar

EXPOSE 8080

# NODE_ID and REDIS_HOST are injected per-service by docker-compose and read by
# application-cluster.properties. busybox `wget` (present in alpine) backs the compose healthcheck.
ENTRYPOINT ["sh", "-c", "java -jar app.jar --spring.profiles.active=cluster --logging.level.root=info"]
