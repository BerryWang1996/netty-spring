# Multi-node cluster demo image — runs the netty-spring demo app as one cluster node.
# Build context MUST be the repo root so the Maven-built jar is COPYable. Build the jar first:
#   ./mvnw -pl demo-netty-web-spring-boot-starter -am package -DskipTests
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy versioned jars, then pick the NEWEST application jar at startup:
# after a version bump, target/ can still hold a stale jar from the previous version, and a
# plain `COPY .../*.jar app.jar` resolves nondeterministically to ONE of them — that silently
# ran the stale jar once (demo image shipped an old 1.9.0 build). The thin jar is
# *.jar.original and never matches *.jar. Release builds also create sources/javadoc jars;
# exclude those classifiers when selecting the executable jar. Still version-agnostic.
COPY demo-netty-web-spring-boot-starter/target/*.jar /app/target/

EXPOSE 8080

# NODE_ID and REDIS_HOST are injected per-service by docker-compose and read by
# application-cluster.properties. busybox `wget` (present in alpine) backs the compose healthcheck.
ENTRYPOINT ["sh", "-c", "exec java -jar \"$(ls -t /app/target/*.jar | grep -v -E -- '-(sources|javadoc)\\.jar$' | head -1)\" --spring.profiles.active=cluster --logging.level.root=info"]
