# Order Management Service

A Spring Cloud microservices backend simulating an e-commerce order flow — users, products, orders, and async payment processing via Kafka.

No frontend. All interaction is via REST (Postman / curl / API gateway at `:8085`).

---

## Architecture

### Topology

```mermaid
flowchart TB
    client(["Client<br/>curl / Postman"])
    gateway["<b>API Gateway</b><br/>:8085<br/>routes by path prefix"]

    subgraph services["Business services"]
        direction LR
        user["<b>user-service</b><br/>:8081"]
        product["<b>product-service</b><br/>:8082"]
        order["<b>order-service</b><br/>:8083"]
        payment["<b>payment-service</b><br/>:8088"]
        userdb[("H2<br/>userdb")]
        productdb[("H2<br/>productdb")]
        orderdb[("H2<br/>orderdb")]
        paymentdb[("H2<br/>paymentdb")]
        user --- userdb
        product --- productdb
        order --- orderdb
        payment --- paymentdb
    end

    kafka{{"<b>Kafka</b><br/>topic: payment-events"}}

    subgraph platform["Platform services"]
        direction LR
        eureka["<b>Discovery (Eureka)</b><br/>:8084<br/>service registry"]
        config["<b>Config Server</b><br/>:8086<br/>shared props: Kafka, JWT secret"]
    end

    client -->|"HTTP :8085"| gateway
    gateway -->|"lb://SERVICE"| user & product & order & payment
    payment -->|publish| kafka
    kafka -->|consume| order
    services -.->|"register and pull config"| platform

    classDef entry fill:#fef3c7,stroke:#d97706,stroke-width:2px,color:#78350f
    classDef svc fill:#dbeafe,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef db fill:#f1f5f9,stroke:#64748b,color:#334155
    classDef msg fill:#fce7f3,stroke:#db2777,stroke-width:2px,color:#831843
    classDef infra fill:#dcfce7,stroke:#16a34a,stroke-width:2px,color:#14532d

    class client,gateway entry
    class user,product,order,payment svc
    class userdb,productdb,orderdb,paymentdb db
    class kafka msg
    class eureka,config infra

    style services fill:transparent,stroke:#2563eb,stroke-dasharray:5 5
    style platform fill:transparent,stroke:#16a34a,stroke-dasharray:5 5
```

Every service registers with Eureka on startup and pulls config from the config server. Clients use only the gateway port. Docker Compose also publishes each service's port for local debugging.

### Runtime interaction — order orchestration

```mermaid
flowchart LR
    order["<b>order-service</b><br/>orchestrates an order"]
    user["<b>user-service</b>"]
    product["<b>product-service</b>"]
    payment["<b>payment-service</b>"]
    kafka{{"<b>Kafka</b><br/>topic: payment-events<br/>{ orderId, status }"}}

    order -->|"REST GET /api/users/{id}<br/>validate user exists"| user
    order -->|"REST GET /api/products/{id}<br/>fetch price"| product
    order -->|"REST POST /api/payments + JWT<br/>charge"| payment
    payment -->|publish| kafka
    kafka -->|"consume<br/>update order status"| order

    classDef entry fill:#fef3c7,stroke:#d97706,stroke-width:2px,color:#78350f
    classDef svc fill:#dbeafe,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef msg fill:#fce7f3,stroke:#db2777,stroke-width:2px,color:#831843
    classDef secret fill:#f1f5f9,stroke:#64748b,color:#334155

    class order,user,product,payment svc
    class kafka msg
```

### Communication

**Synchronous (REST)**

| Caller | Callee | Purpose |
|---|---|---|
| `api-gateway` | All services | Route every inbound request via Eureka `lb://` |
| `order-service` | `user-service` | Validate user exists on order placement |
| `order-service` | `product-service` | Fetch product price on order placement |
| `order-service` | `payment-service` | Trigger payment (JWT auth header) |

**Asynchronous (Kafka)**

| Producer | Consumer | Topic | Payload |
|---|---|---|---|
| `payment-service` | `order-service` | `payment-events` | `{"orderId":"...","status":"SUCCESS\|FAILED"}` |
| `order-service` | None (replay by hand) | `payment-events.DLT` | A `payment-events` event that failed with an error other than a database outage |

### Order status lifecycle

```mermaid
stateDiagram-v2
    direction LR
    [*] --> PLACED: order saved
    PLACED --> PAYMENT_INITIATED: before the payment call
    PLACED --> PAYMENT_FAILED: 5-minute timeout
    PAYMENT_INITIATED --> PAYMENT_SUCCESS: SUCCESS event
    PAYMENT_INITIATED --> PAYMENT_FAILED: FAILED event, fallback, or 5-minute timeout
    PAYMENT_FAILED --> PAYMENT_SUCCESS: late SUCCESS event

    classDef progress fill:#dbeafe,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef success fill:#dcfce7,stroke:#16a34a,stroke-width:2px,color:#14532d
    classDef failed fill:#fee2e2,stroke:#dc2626,stroke-width:2px,color:#7f1d1d
    class PLACED,PAYMENT_INITIATED progress
    class PAYMENT_SUCCESS success
    class PAYMENT_FAILED failed
```

`order-service` commits `PLACED` then `PAYMENT_INITIATED` before it calls
`payment-service`; the `payment-events` Kafka consumer sets the terminal status. If
the payment call fails, the circuit breaker fallback sets `PAYMENT_FAILED`.

### Security

```mermaid
flowchart TB
    client(["Client"])
    secret[("Shared JWT_SECRET<br/>HS256 signing key")]
    user["<b>user-service</b><br/>issues an end-user JWT on login"]
    order["<b>order-service</b><br/>mints a service JWT<br/>subject: order-service"]
    payment["<b>payment-service</b><br/>validates the JWT and requires<br/>subject order-service"]

    client -->|"POST /api/users/login"| user
    user -->|end-user JWT| client
    secret -.-> user
    secret -.-> order
    order -->|"POST /api/payments<br/>Authorization: Bearer service JWT"| payment
    client -.->|"POST /api/payments with end-user JWT<br/>rejected: 403 Forbidden"| payment
    linkStyle 5 stroke:#dc2626,stroke-width:2px,color:#dc2626

    classDef entry fill:#fef3c7,stroke:#d97706,stroke-width:2px,color:#78350f
    classDef svc fill:#dbeafe,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef msg fill:#fce7f3,stroke:#db2777,stroke-width:2px,color:#831843
    classDef secret fill:#f1f5f9,stroke:#64748b,color:#334155

    class client entry
    class user,order,payment svc
    class secret secret
```

`/api/payments/**` requires a valid JWT whose subject is `order-service`, so end-user tokens are rejected. All other endpoints are public. The `id` field in request bodies is ignored.

`user-service` stores passwords as BCrypt hashes and never returns them in responses.

The config server serves `jwt.secret` as an unresolved `${JWT_SECRET:...}` placeholder,
so `user-service`, `order-service`, and `payment-service` each read `JWT_SECRET` from
their own environment (base64-encoded, decoded length ≥ 32 bytes). All three must use
the same value. A default is provided for local development. Docker Compose passes the
host's `JWT_SECRET` to those three services when it's set.

---

## Services

| Service | Port | Key files |
|---|---|---|
| `discovery-server` | 8084 | `DiscoveryServerApplication.java` |
| `config-server` | 8086 | `application.yml`, `src/main/resources/config/` |
| `api-gateway` | 8085 | `application.yml` (routes) |
| `user-service` | 8081 | `UserController`, `UserServiceImpl`, `JwtUtil` |
| `product-service` | 8082 | `ProductController`, `ProductServiceImpl` |
| `order-service` | 8083 | `OrderServiceImpl`, `PaymentEventConsumer`, `JwtUtil` |
| `payment-service` | 8088 | `PaymentServiceImpl`, `JwtAuthenticationFilter`, `PaymentEventPublisher` |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.2, Spring Cloud 2025.1.4 |
| Service discovery | Netflix Eureka |
| API gateway | Spring Cloud Gateway |
| Config management | Spring Cloud Config (native / classpath) |
| Messaging | Apache Kafka (KRaft, single broker) |
| Inter-service HTTP | `RestTemplate` + Eureka client-side load balancing |
| Security | Spring Security + JWT HS256 (JJWT 0.12.6) |
| Resilience | Resilience4j circuit breaker (payment-service call) |
| Database | H2 in-memory (one per service) |
| Build | Maven multi-module |
| Containers | Docker + Docker Compose |

---

## API Reference

All requests go through the gateway at `http://localhost:8085`.

### User Service

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/users` | public | Create a user |
| `GET` | `/api/users` | public | List all users |
| `GET` | `/api/users/{id}` | public | Get user by ID |
| `PUT` | `/api/users/{id}` | public | Update user (409 if the email is taken) |
| `DELETE` | `/api/users/{id}` | public | Delete user |
| `POST` | `/api/users/login` | public | Login — returns `{ "token": "..." }` (401 for invalid credentials) |

### Product Service

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/products` | public | Create product (409 if the name is taken) |
| `GET` | `/api/products` | public | List all products |
| `GET` | `/api/products/{id}` | public | Get product by ID (404 if not found) |
| `PUT` | `/api/products/{id}` | public | Update product |
| `DELETE` | `/api/products/{id}` | public | Delete product |

### Order Service

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/orders` | public | Place an order and trigger payment (404 if the user or product doesn't exist, 503 if user-service or product-service is unavailable, 400 if the total overflows) |
| `GET` | `/api/orders` | public | List all orders |
| `GET` | `/api/orders/{id}` | public | Get order by ID (404 if not found) |

### Payment Service

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/payments` | `order-service` JWT required | Process a payment once per order (rejects end-user tokens with 403, and a repeat with a different amount with 409) |

---

## Data Flow — Place an Order

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant G as api-gateway
    participant O as order-service
    participant U as user-service
    participant P as product-service
    participant Pay as payment-service
    participant K as Kafka payment-events

    C->>G: POST /api/orders { userId, productId, quantity }
    G->>O: route
    O->>U: GET /api/users/{userId}
    U-->>O: user exists
    O->>P: GET /api/products/{id}
    P-->>O: price
    Note over O: totalPrice = price × quantity<br/>save PLACED, then PAYMENT_INITIATED
    O->>Pay: POST /api/payments (+ JWT)
    Note over Pay: validate JWT<br/>MockPaymentProvider: 80% SUCCESS, 20% FAILED<br/>save PaymentResponse and outbox row in one transaction
    alt payment call succeeds
        Pay-->>O: payment response
        O-->>G: order (PAYMENT_INITIATED)
    else call fails or times out
        Note over O: circuit breaker fallback<br/>sets PAYMENT_FAILED
        O-->>G: order (PAYMENT_FAILED)
    end
    G-->>C: 200 order
    Pay-)K: OutboxRelay publishes { orderId, status } within about 1 second
    K-)O: consume { orderId, status }
    Note over O: set PAYMENT_SUCCESS or PAYMENT_FAILED
```

---

## Quick Start

### Docker (recommended — starts everything)

```bash
docker compose up -d
```

Waits for healthchecks in this order: Kafka → discovery-server → config-server → all business services.

The Dockerfiles use a BuildKit cache mount, `maven-repo`, so Maven downloads dependencies once across all images.

Check Eureka dashboard: `http://localhost:8084`

### Local Development

Prerequisites: Java 25, Maven 3.9+, Docker (for Kafka).

```bash
# Start Kafka only
docker compose up -d kafka

# Build all modules
mvn clean install -DskipTests

# Start services in order (each in its own terminal)
cd discovery-server && mvn spring-boot:run
cd config-server    && mvn spring-boot:run
cd user-service     && mvn spring-boot:run
cd product-service  && mvn spring-boot:run
cd payment-service  && mvn spring-boot:run
cd order-service    && mvn spring-boot:run
cd api-gateway      && mvn spring-boot:run
```

---

## Running Tests

```bash
# All services
mvn test

# Single service
mvn test -pl user-service
```

Service tests use `@SpringBootTest` + `MockMvc`, and `PaymentEventConsumerTest` uses Mockito. Config server and Eureka are disabled in the test profile via `src/test/resources/application.properties` in each service.

---

## Key Design Notes

- **H2 in-memory per service** — no external DB needed for local dev; data resets on restart.
- **Native config** — config-server serves YAML from `classpath:/config/` (no external Git repo).
- **Mock payment provider** — 80% SUCCESS / 20% FAILED, random transaction ID.
- **Circuit breaker** — Resilience4j wraps the order-service → payment-service call; on failure the order is saved as `PAYMENT_FAILED`. The call runs on a virtual thread with a 12-second time limit (`resilience4j.timelimiter.instances.payment-service.timeout-duration`). `payment-service` doesn't call Kafka while it handles the request, because `OutboxRelay` publishes events separately, so a slow Kafka doesn't slow the payment call. A 4xx response doesn't count as a breaker failure, so a misconfigured request can't open the circuit for every order.
- **Payment retry** — inside the circuit breaker, `order-service` retries the payment call twice, after 200 ms and then 400 ms (`order.payment-retry.*`), on connection errors, time-outs, and 5xx responses. A 4xx response or a declined payment isn't retried. Retrying can't charge twice, because `payment-service` processes each `orderId` once. The breaker records one result per order.
- **HTTP time limits** — every `order-service` call has a 1-second connect limit and a 2.5-second read limit (`order.http.*`). Three payment tries take at most about 11.1 seconds, inside the 12-second time limit.
- **No transaction around the payment call** — `placeOrder()` commits `PAYMENT_INITIATED` before calling `payment-service`, because the breaker's thread and the Kafka consumer can't see uncommitted rows and the event can arrive before the HTTP response.
- **Virtual threads** — the four business services handle requests on virtual threads, so blocking HTTP calls don't hold a platform thread.
- **AOT cache** — each Docker image trains a Java ahead-of-time cache during the build, which roughly halves service startup time.
- **Consumer failures** — malformed events and events with an unknown status are logged and skipped. Database failures retry every 5 seconds until the database recovers. Any other failure sends the event to `payment-events.DLT` at once, so it can't block later events on its partition. `order-service` creates that topic on startup.

  To read the dead-letter topic, and then replay an event by pasting it into the producer:

  ```bash
  docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic payment-events.DLT --from-beginning
  docker compose exec -it kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic payment-events
  ```
- **Publishing (transactional outbox)** — `payment-service` saves each payment event in an `outbox_events` row in the payment's own transaction, so an event exists only if its payment committed. `OutboxRelay` publishes rows every second (`payment.outbox.relay-interval`) and deletes each one after Kafka acknowledges it, waiting up to 5 seconds. When Kafka is down, payments still succeed, and their events wait in the outbox until Kafka returns. Delivery is at least once, and `order-service` ignores duplicates.
- **Idempotent payments** — `payment-service` processes each `orderId` at most once. A repeat request with the same amount gets the original response and writes no second payment or event. A repeat with a different amount gets `409 Conflict`. Amounts take at most two decimal places, so `500` and `500.00` match. A unique constraint on `payment_responses.order_id` covers concurrent first requests: the losing insert fails, and the controller retries once and returns the winner's payment. Both requests call the payment provider, and the loser's result is discarded. That's harmless with the mock provider; a real gateway needs a `PENDING` row written before the charge.
- **Stale payments** — once a minute, `order-service` marks orders that have been in `PLACED` or `PAYMENT_INITIATED` for 5 minutes as `PAYMENT_FAILED` (`order.payment-timeout`). `PLACED` covers an order whose placement stopped between its two saves. A valid event that arrives later still sets the correct final status.
- **Fallback writes** — the circuit breaker fallback only changes an order that's still in `PAYMENT_INITIATED`, so it can't overwrite a final status.
- **Event transitions** — the consumer changes an order's status only when the current status allows it. A `SUCCESS` event applies to `PAYMENT_INITIATED` or `PAYMENT_FAILED`, and a `FAILED` event applies only to `PAYMENT_INITIATED`, so `PAYMENT_SUCCESS` is final and a replayed or duplicate event can't undo it.
