# Java 25 migration tracker

Read this file first when you resume the migration. Update it at the end of every phase.

## Status

| Phase | Scope | Branch | State |
|---|---|---|---|
| 1 | Java 25 on Spring Boot 3.5.16 | `feature/java25-phase-1-jdk25` | Verified, PR open |
| 2 | Spring Boot 4.1.2 and Spring Cloud 2025.1.4 | `feature/java25-phase-2-boot4` | Not started |
| 3 | Typed Kafka event and record DTOs | `feature/java25-phase-3-records` | Not started |
| 4 | Virtual threads | `feature/java25-phase-4-virtual-threads` | Not started |
| 5 | Ahead-of-time (AOT) cache in Docker images | `feature/java25-phase-5-aot-cache` | Not started |

## Working rules

- Each phase has one branch and one pull request (PR). Each branch starts from the previous phase, and its PR targets that branch.
- The owner approved opening PRs. Merging needs a separate approval.
- Commit messages don't include a Claude co-author line.
- `INTERVIEW_PREP.md` stays untracked. Stage files by explicit path.
- A phase is complete only after both checks in [Verification](#verification) pass and the docs match the code.

## Target versions

| Component | From | To |
|---|---|---|
| Java | 17 | 25 |
| Spring Boot | 3.5.4 | 4.1.2 (3.5.16 in phase 1) |
| Spring Cloud | 2025.0.0 | 2025.1.4 (2025.0.3 in phase 1) |
| Lombok | 1.18.38 | 1.18.48 |
| Docker build image | `maven:3.9-eclipse-temurin-17` | `maven:3.10-eclipse-temurin-25-noble` |
| Docker runtime image | `eclipse-temurin:17-jre` | `eclipse-temurin:25-jre` |

JJWT 0.12.6 and the `apache/kafka:3.9.0` broker image don't change.

## Phase details

### Phase 1: Java 25 on Boot 3.5.16

- In `pom.xml`, set the target versions and `maven.compiler.proc=full`. Lombok doesn't run on JDK 23 or later without that property.
- Update the images in all seven Dockerfiles.
- Share one BuildKit cache mount, `maven-repo`, across the Dockerfiles so dependencies download once.

### Phase 2: Boot 4.1.2 and Cloud 2025.1.4

- Read the 6 `payment-service` log lines containing "Exception" that `scripts/verify_e2e.py` prints, and record what they are in [Findings](#findings).
- Replace `spring-boot-starter-web` with `spring-boot-starter-webmvc`, and `spring-kafka` with `spring-boot-starter-kafka`.
- Add `spring-boot-starter-webmvc-test` to the four business services.
- In `api-gateway`, use `spring-cloud-starter-gateway-server-webflux` and move routes under `spring.cloud.gateway.server.webflux.routes`. A wrong path ignores the routes while unit tests still pass.
- Change Jackson imports to `tools.jackson`, and remove the `throws` clauses that the unchecked `JacksonException` makes unnecessary.
- Replace `@MockBean` with `@MockitoBean`, and update the `AutoConfigureMockMvc` import.

### Phase 3: Typed Kafka event and record DTOs

- Add `PaymentEvent(Long orderId, PaymentResult status)` to `order-service`, with null checks in its compact constructor.
- Map the status with an exhaustive `switch`. An unknown status is logged and skipped, where it used to become `PAYMENT_FAILED`.
- Publish a matching record from `PaymentServiceImpl`. The JSON format stays the same.
- Convert the six request and response DTOs to records. JPA entities stay classes.

### Phase 4: Virtual threads

- Set `spring.threads.virtual.enabled=true` in the four business services.
- Add a `Customizer<Resilience4JCircuitBreakerFactory>` so the payment call also runs on a virtual thread.

### Phase 5: AOT cache

- Add a training run to each Dockerfile, and start the app with `-XX:AOTCache=app.aot`.
- Training has no config server or Eureka, so it needs placeholder JWT settings and `EUREKA_CLIENT_ENABLED=false`.

## Features considered and skipped

| Feature | Reason |
|---|---|
| Compact object headers | Memory was higher in one trial run |
| Structured concurrency, primitive patterns, stable values | Preview features in Java 25 |
| Sealed types | `PaymentProvider` is meant to stay open |
| Scoped values, stream gatherers, sequenced collections | No matching code |
| Flexible constructors, module imports, text blocks | Cosmetic in this codebase |

## Verification

Run both checks from the repository root on JDK 25.

1. Run the unit and integration tests:

   ```bash
   mvn clean test
   ```

2. Build and run the full stack, exercise the order flow, and stop it:

   ```bash
   python3 scripts/verify_e2e.py
   ```

The script exits with a nonzero status if any check fails.

## Findings

- A trial of Boot 4.1.2 on JDK 25 passed all 25 tests and the full Docker run before phase 1 started.
- In a trial, the AOT cache reduced `user-service` startup from about 3.0 seconds to 0.9 seconds and added about 132 MB to the image.
- Virtual threads passed a functional run, but no load test measured their benefit.
- Spring Boot 3.5 open-source support ended on 2026-06-30, and 4.1 support runs to 2027-07-31.

## Phase log

Add one entry per completed phase: date, PR, test count, and verification result.

- **Phase 1 (2026-11-09):** 25 tests pass on JDK 25, and `scripts/verify_e2e.py` passes all checks. The first run took 617 seconds while it filled the Maven cache.
