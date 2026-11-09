FROM maven:3.10-eclipse-temurin-25-noble AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,id=maven-repo,target=/root/.m2,sharing=locked mvn -B clean package -pl config-server -am -DskipTests

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/config-server/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
