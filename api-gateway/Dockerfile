FROM maven:3.10-eclipse-temurin-25-noble AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,id=maven-repo,target=/root/.m2,sharing=locked mvn -B clean package -pl api-gateway -am -DskipTests
RUN mv api-gateway/target/*.jar app.jar && java -Djarmode=tools -jar app.jar extract --destination extracted

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/extracted .
# Training runs without the config server or Eureka, so it uses sample settings that apply to this step only.
RUN JWT_SECRET=dHJhaW5pbmctb25seS1zYW1wbGUta2V5LW5vdC11c2VkLWF0LXJ1bnRpbWU= \
    JWT_EXPIRATION_MS=1000 \
    KAFKA_TOPIC_NAME=training \
    EUREKA_CLIENT_ENABLED=false \
    java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar app.jar
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
