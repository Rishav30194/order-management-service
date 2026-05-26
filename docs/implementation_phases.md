# Implementation Phases

Track progress with checkboxes. Read this file at the start of every session
to find the next unchecked task before writing any code.

---

## Phase 1 — Foundation (Complete)

- [x] Discovery Server (Eureka) — fully working
- [x] Config Server — basic setup done (needs new GitHub config repo, see Phase 5)
- [x] API Gateway — basic routing
- [x] User Service — full CRUD
- [x] Product Service — full CRUD
- [x] Payment Service — mock provider + Kafka producer
- [x] Order Service — order placement with user + product validation
- [x] Maven multi-module parent pom — versions unified
- [x] docker-compose.yml — Kafka (KRaft)

---

## Pre-Phase 2 — Service Audit (New findings from full code review)

Go service by service. Fix everything here before touching Phase 2 tasks.

---

### discovery-server
- [x] **No issues found** — pom.xml, config, and main class are clean.

---

### config-server
- [x] **Missing `spring-cloud-starter-netflix-eureka-client` in pom.xml**
  `application.yml` has `eureka.client.service-url.defaultZone` configured but the
  Eureka client dependency is absent. Config-server will never register with Eureka.
  Add `spring-cloud-starter-netflix-eureka-client` to `config-server/pom.xml`
  and annotate `ConfigServerApplication` with `@EnableDiscoveryClient`.

---

### api-gateway
- [x] **Missing `spring-cloud-starter-loadbalancer` in pom.xml**
  The gateway routes use `lb://` URIs, which require the load balancer on the classpath.
  It may arrive as a transitive dependency from `spring-cloud-starter-gateway`, but it
  should be declared explicitly to be safe.
  Add `spring-cloud-starter-loadbalancer` to `api-gateway/pom.xml`.

---

### user-service
- [x] **API base path is `/api/user` (singular) — all callers expect `/api/users` (plural)**
  `UserController` is mapped to `@RequestMapping("/api/user")`.
  `OrderServiceImpl` calls `http://USER-SERVICE/api/users/{id}` — every order placement
  will get a 404 when validating the user. **Critical cross-service bug.**
  Change `@RequestMapping("/api/user")` to `@RequestMapping("/api/users")` in
  `UserController.java`.

---

### product-service
- [x] **Unnecessary `spring-cloud-starter-loadbalancer` dependency**
  product-service makes no outbound load-balanced HTTP calls. Remove this dependency
  from `product-service/pom.xml`.

- [x] **`ProductDTO.price` in order-service is `Double`; `Product.price` is `Long`**
  Type mismatch across the service boundary. Jackson converts `Long` → `Double` silently,
  but `Double` → `Long` can lose precision on large values. Align them: change
  `ProductDTO.price` in order-service to `Long`.
  File: `order-service/src/main/java/com/rishav/order/dto/ProductDTO.java`

---

### order-service
- [x] **`PaymentEventConsumer` imports an annotation-processor internal class for JSON parsing**
  `org.springframework.boot.configurationprocessor.json.JSONObject` is an internal class
  from the annotation-processor artifact — not intended for runtime use. Replace with
  Jackson's `ObjectMapper` for parsing the Kafka event payload.
  File: `order-service/src/main/java/com/rishav/order/kafka/PaymentEventConsumer.java`

---

### payment-service
- [x] **Kafka config missing `spring.` prefix — producer is misconfigured**
  `application.yml` has `kafka.bootstrap-servers`, `kafka.producer.*`, and
  `kafka.consumer.*` as top-level keys. Spring Boot's `KafkaAutoConfiguration`
  only reads `spring.kafka.*`. Move all Kafka properties under `spring:` in
  `payment-service/src/main/resources/application.yml`.
  Keep `kafka.topic.name` as a custom key (it is injected via `@Value` and is correct).

- [x] **`SecurityConfig` secures `/api/payments` (exact) but not `/api/payments/**`**
  `.requestMatchers("/api/payments")` only matches the exact path. A trailing slash
  or any sub-path bypasses the JWT filter. Change to `.requestMatchers("/api/payments/**")`.
  File: `payment-service/src/main/java/com/rishav/payment/config/SecurityConfig.java`

- [x] **`PaymentServiceImpl`: `ObjectMapper` instantiated inline as instance field**
  `ObjectMapper mapper = new ObjectMapper()` is created per-instance. Inject it as a
  Spring bean (`@Autowired` or constructor injection) so it is shared and thread-safe.
  File: `payment-service/src/main/java/com/rishav/payment/service/impl/PaymentServiceImpl.java`

- [x] **`PaymentResponse.createdAt` / `updatedAt` are never set**
  The builder call in `PaymentServiceImpl.handlePayment()` does not populate these fields —
  they are always `null` in the DB. Add `@CreationTimestamp` / `@UpdateTimestamp`
  (Hibernate annotations) to the `PaymentResponse` entity, or set them manually.
  File: `payment-service/src/main/java/com/rishav/payment/entity/PaymentResponse.java`

- [x] **`jjwt-impl` and `jjwt-jackson` runtime deps missing from payment-service pom.xml**
  `jjwt-api` is listed but the runtime implementations are absent. The current mock-token
  code doesn't use JJWT at all, so `jjwt-api` can be removed now and re-added properly
  in Phase 4 when real JWT validation is implemented.
  File: `payment-service/pom.xml`

---

## Phase 2 — Fix Broken Pieces

These are bugs and stubs found in the existing code. Fix these before adding new features.

### Bugs

- [x] **UserController: wrong HTTP method on GET /{id}**
  `UserController.getUserById()` is mapped with `@PostMapping("/{id}")`.
  Change to `@GetMapping("/{id}")`.
  File: `user-service/src/main/java/com/rishav/user/controller/UserController.java`

- [x] **order-service config: missing `spring.` prefix on JPA properties**
  `application.properties` has `jpa.hibernate.ddl-auto` and `jpa.show-sql`
  — these are silently ignored. Change to `spring.jpa.hibernate.ddl-auto`
  and `spring.jpa.show-sql`.
  File: `order-service/src/main/resources/application.properties`

- [x] **product-service config: same missing `spring.` prefix**
  Same issue as order-service above.
  File: `product-service/src/main/resources/application.properties`

### Stubs to implement

- [x] **PaymentEventConsumer: implement `updateOrderStatus()`**
  Currently only logs the Kafka event. Implement the actual DB update:
  parse the `orderId` and `status` from the event JSON, load the
  `Order` from the repository, and set its `status` field to
  `PAYMENT_SUCCESS` or `PAYMENT_FAILED`.
  File: `order-service/src/main/java/com/rishav/order/kafka/PaymentEventConsumer.java`

### Design fixes

- [x] **PaymentRequest: remove `@Entity`, make it a plain DTO**
  `PaymentRequest` is annotated `@Entity` but is a request DTO — it
  should never be a persistent entity. Remove `@Entity`, `@Id`, and
  `@GeneratedValue`. If the intent was to log incoming requests, create
  a separate `PaymentRequestLog` entity.
  File: `payment-service/src/main/java/com/rishav/payment/dto/PaymentRequest.java`

- [x] **ProductRepository: remove unused `existsByStock()`**
  `existsByStock(int stock)` is declared but never called anywhere.
  File: `product-service/src/main/java/com/rishav/product/repository/ProductRepository.java`

### API Gateway

- [x] **Uncomment routes for user, product, and order services**
  Only the payment-service route is active. Uncomment the commented-out
  routes for USER-SERVICE, PRODUCT-SERVICE, and ORDER-SERVICE.
  File: `api-gateway/src/main/resources/application.yml`

---

## Phase 3 — Order → Payment Integration

Right now order-service has no connection to payment-service after saving the order.

- [x] **Add `PaymentRequest` DTO to order-service**
  Create `order-service/.../dto/PaymentRequest.java` with fields:
  `orderId`, `amount`, `customerId`, `paymentMethod`.

- [x] **Call payment-service from `OrderServiceImpl.placeOrder()`**
  After saving the order with status `PLACED`:
  1. Build a `PaymentRequest` from the order details
  2. Call `POST lb://PAYMENT-SERVICE/api/payments/` via `RestTemplate`
     with `Authorization: Bearer <service-token>` header
  3. Update order status to `PAYMENT_INITIATED`
  Wrap the call in the existing Resilience4j circuit breaker.
  If payment-service is unreachable, set order status to `PAYMENT_FAILED`.
  File: `order-service/src/main/java/com/rishav/order/service/impl/OrderServiceImpl.java`

- [x] **Add Order status constants**
  Replace the raw `"PLACED"` string with an enum or constants class:
  `PLACED`, `PAYMENT_INITIATED`, `PAYMENT_SUCCESS`, `PAYMENT_FAILED`.

- [x] **Verify end-to-end flow**
  Place an order and confirm:
  1. Order saved with status `PLACED` then `PAYMENT_INITIATED`
  2. Payment processed (check payment-service DB)
  3. Kafka event published (`payment-events` topic)
  4. Order status updated to `PAYMENT_SUCCESS` or `PAYMENT_FAILED`

---

## Phase 4 — Real JWT Authentication

Replace the hardcoded mock token in payment-service with real JWT validation.

- [x] **Add login endpoint to user-service**
  `POST /api/user/login` — accepts `{ email, password }`, validates
  against DB, returns a signed JWT (HS256). Use `io.jsonwebtoken:jjwt`.
  Add `jjwt-impl` and `jjwt-jackson` runtime dependencies to user-service pom.

- [x] **Store JWT secret in config-server** *(completed in Phase 5)*
  Add `jwt.secret` and `jwt.expiration-ms` to the config-server GitHub
  repo so all services share the same signing key without hardcoding it.

- [x] **Add JWT utility class**
  Create a shared `JwtUtil.java` in both user-service (for signing) and
  payment-service (for validation). Same logic — generate and validate
  HS256 tokens using the secret from config-server.

- [x] **Replace hardcoded token in payment-service**
  `JwtAuthenticationFilter` currently accepts only `"valid-mock-token"`.
  Replace with actual JWT signature verification using `JwtUtil`.
  File: `payment-service/src/main/java/com/rishav/payment/config/JwtAuthenticationFilter.java`

- [x] **Generate a service token in order-service**
  order-service calls payment-service with a JWT. This should be a
  service-level token (not a user token). Either:
  - Generate a short-lived internal token signed with the same secret, or
  - Create a dedicated `/api/user/service-token` endpoint in user-service
  The simpler option: order-service generates its own signed token using
  the shared secret from config-server.

- [x] **Test the full auth flow**
  1. `POST /api/user/login` → receive JWT
  2. `POST /api/orders/` → order-service generates service token, calls
     payment-service, which validates it
  3. Confirm payment processes and order status updates

---

## Phase 5 — Config Server

The config-server GitHub repo was deleted. Create a new one and wire everything up.

- [x] **Create GitHub repo `order-management-config`**
  Public repo. This will serve as the config source for config-server.

- [x] **Add per-service config files to the repo**
  At minimum:
  - `application.yml` — shared properties (jwt.secret, kafka bootstrap)
  - `payment-service.yml` — payment-specific overrides
  - `user-service.yml` — user-specific overrides

- [x] **Update config-server `application.yml` to point to new repo**
  Change `spring.cloud.config.server.git.uri` to the new repo URL.
  File: `config-server/src/main/resources/application.yml`

- [x] **Add `spring-cloud-starter-config` dependency to each service**
  Enable config client and add `bootstrap.yml` (or use `spring.config.import`)
  in each service to pull from config-server on startup.

- [x] **Move shared properties out of local `application.properties`**
  Move `jwt.secret`, `spring.kafka.bootstrap-servers`, and Eureka URL
  into the config-server repo so they're managed in one place.

---

## Phase 6 — Docker Support

Containerize all services so the entire system can be started with one command.

- [x] **Write `Dockerfile` for each service**
  Use multi-stage build: Maven build stage → slim JRE runtime stage.
  Template:
  ```dockerfile
  FROM maven:3.9-eclipse-temurin-17 AS build
  WORKDIR /app
  COPY . .
  RUN mvn clean package -DskipTests

  FROM eclipse-temurin:17-jre
  WORKDIR /app
  COPY --from=build /app/target/*.jar app.jar
  ENTRYPOINT ["java", "-jar", "app.jar"]
  ```
  Add a `Dockerfile` to each of the 7 service directories.

- [x] **Update `docker-compose.yml` at repo root**
  Add all 7 services to docker-compose. Set `depends_on` to enforce
  startup order. Update Eureka and Kafka URLs from `localhost` to
  Docker service names (e.g., `http://discovery-server:8084/eureka`).

- [x] **Update application configs for Docker networking**
  When running in Docker, services can't use `localhost` to reach each other.
  Use Spring profiles: `local` profile uses `localhost`, `docker` profile
  uses Docker service names.

- [x] **Test full stack with `docker-compose up`**
  All 7 services + Kafka should start, register with Eureka, and the
  place-order flow should work end-to-end.

---

## Phase 7 — Polish & Quality

- [x] **Add `@Transactional` to service methods**
  `OrderServiceImpl.placeOrder()`, `PaymentServiceImpl.handlePayment()`,
  and all write operations should be transactional.

- [x] **Add input validation**
  Add `@Valid` + `@NotNull`, `@NotBlank`, `@Positive` annotations to
  request DTOs. Add `MethodArgumentNotValidException` handler to
  `GlobalExceptionHandler` in each service.

- [x] **Consistent exception handling across all services**
  Only user-service has a `GlobalExceptionHandler`. Add one to
  order-service, product-service, and payment-service with at minimum:
  - `RuntimeException` → 500 with message
  - `EntityNotFoundException` → 404

- [x] **Resilience4j: wire up circuit breaker properly in order-service**
  The dependency is declared but the circuit breaker is not configured.
  Add `application.properties` entries for the payment-service circuit
  breaker (failure threshold, wait duration, fallback method).

- [x] **Add integration tests**
  At minimum one test per service that starts the Spring context and
  exercises the main happy path using `MockMvc` and `@SpringBootTest`.
