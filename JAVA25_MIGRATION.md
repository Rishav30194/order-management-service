# Java 25 migration tracker

Read this file first when you resume the migration. Update it at the end of every phase.

## Status

| Phase | Scope | Branch | State |
|---|---|---|---|
| 1 | Java 25 on Spring Boot 3.5.16 | `feature/java25-phase-1-jdk25` | Verified, [PR #3](https://github.com/Rishav30194/order-management-service/pull/3) open |
| 2 | Spring Boot 4.1.2 and Spring Cloud 2025.1.4 | `feature/java25-phase-2-boot4` | Verified, [PR #4](https://github.com/Rishav30194/order-management-service/pull/4) open |
| 3 | Typed Kafka event and record DTOs | `feature/java25-phase-3-records` | Verified, [PR #5](https://github.com/Rishav30194/order-management-service/pull/5) open |
| 4 | Virtual threads | `feature/java25-phase-4-virtual-threads` | Verified, [PR #6](https://github.com/Rishav30194/order-management-service/pull/6) open |
| 5 | Ahead-of-time (AOT) cache in Docker images | `feature/java25-phase-5-aot-cache` | Verified, PR open |

All five phases are implemented and verified. The remaining step is to merge the PRs in phase order, which needs the owner's approval. After merging a PR, retarget the next PR to `main`.

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

- Extract the jar in the build stage, add a training run to the runtime stage, and start the app with `-XX:AOTCache=app.aot`.
- Training has no config server or Eureka, so it needs sample JWT and Kafka topic settings and `EUREKA_CLIENT_ENABLED=false`. `discovery-server` omits that last setting, because it's the Eureka server.

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
- In the full Docker run, the AOT cache reduced startup from 8.2 to 3.6 seconds for `user-service`, 9.6 to 4.9 for `order-service`, and 8.2 to 3.5 for `payment-service`. Each image grew by about 100 to 170 MB.
- The shared Maven cache reduced a full verification run from 635 seconds to 140 seconds when dependencies don't change.
- Virtual threads passed a functional run, but no load test measured their benefit.
- The `payment-service` log lines containing "Exception" are INFO messages from the packaged `spring.config.import`, which points to `http://localhost:8086`. That address doesn't exist inside a container, and the services still receive their config, so the lines are harmless.
- Spring Boot 3.5 open-source support ended on 2026-06-30, and 4.1 support runs to 2027-07-31.

## Phase log

Add one entry per completed phase: date, PR, test count, and verification result.

- **Phase 1 (2026-11-09):** 25 tests pass on JDK 25, and `scripts/verify_e2e.py` passes all checks. The first run took 617 seconds while it filled the Maven cache.
- **Phase 2 (2026-11-09):** 25 tests pass on Boot 4.1.2, and `scripts/verify_e2e.py` passes all checks in 635 seconds, with Boot 4 dependencies downloading into the cache for the first time.
- **Phase 3 (2026-11-09):** 26 tests pass, and `scripts/verify_e2e.py` passes all checks in 140 seconds with the Maven cache filled.
- **Phase 4 (2026-11-09):** 27 tests pass, and `scripts/verify_e2e.py` passes all checks in 142 seconds. Payment requests run on `tomcat-handler` virtual threads, which the default log pattern shortens to their last 15 characters.
- **Phase 5 (2026-11-09):** 27 tests pass, and `scripts/verify_e2e.py` passes all checks in 167 seconds, with all seven services starting from the AOT cache.
