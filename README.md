# Order Management Service

A Spring Cloud microservices backend simulating an e-commerce order flow — users, products, orders, and async payment processing via Kafka.

No frontend. All interaction is via REST (Postman / curl / API gateway at `:8085`).

---

## Architecture

```
  Client (curl / Postman)
          │
          ▼
  ┌───────────────┐
  │  API Gateway  │  :8085  — routes by path prefix
  └───────┬───────┘
          │  lb:// via Eureka
  ┌───────┴──────────────────────────────────┐
  │                                          │
  ▼              ▼              ▼            ▼
user-service  product-service  order-service  payment-service
  :8081          :8082           :8083          :8088
  H2:userdb      H2:productdb    H2:orderdb     H2:paymentdb
                                  │    ▲              │
                         REST ────┘    └── Kafka ─────┘
                      (user+product)    payment-events


  ┌──────────────────┐      ┌─────────────────────────────┐
  │  Discovery Server│      │       Config Server         │
  │  (Eureka) :8084  │      │  :8086 — native config,     │
  │                  │      │  files in classpath:/config/ │
  └──────────────────┘      └─────────────────────────────┘
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

### Order Status Lifecycle

```
PLACED → PAYMENT_INITIATED → PAYMENT_SUCCESS
                           → PAYMENT_FAILED
```

### Security

```
POST /api/users/login  →  user-service issues JWT (HS256)
                                    │
                          stored in config-server
                          shared secret across services
                                    │
              order-service generates service-level JWT
                          to call payment-service
```

`/api/payments/**` requires a valid JWT. All other endpoints are public.

The signing key is configured via the `JWT_SECRET` environment variable on the
config-server (base64-encoded, decoded length ≥ 32 bytes). A default is provided
for local development.

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
| Language | Java 17 |
| Framework | Spring Boot 3.5.4, Spring Cloud 2025.0.0 |
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
| `PUT` | `/api/users/{id}` | public | Update user |
| `DELETE` | `/api/users/{id}` | public | Delete user |
| `POST` | `/api/users/login` | public | Login — returns `{ "token": "..." }` |

### Product Service

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/products` | public | Create product |
| `GET` | `/api/products` | public | List all products |
| `GET` | `/api/products/{id}` | public | Get product by ID |
| `PUT` | `/api/products/{id}` | public | Update product |
| `DELETE` | `/api/products/{id}` | public | Delete product |

### Order Service

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/orders` | public | Place an order (triggers payment) |
| `GET` | `/api/orders` | public | List all orders |
| `GET` | `/api/orders/{id}` | public | Get order by ID |

### Payment Service

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/payments` | JWT required | Process a payment (called by order-service) |

---

## Data Flow — Place an Order

```
POST /api/orders  { userId, productId, quantity }
  │
  ▼ api-gateway
  │
  ▼ order-service
    ├─ GET /api/users/{userId}      → validate user exists
    ├─ GET /api/products/{id}       → fetch price
    ├─ totalPrice = price × quantity
    ├─ save Order { status: PLACED }
    └─ POST /api/payments  (+ JWT)
         │
         ▼ payment-service
           ├─ validate JWT
           ├─ MockPaymentProvider  →  80% SUCCESS / 20% FAILED
           ├─ save PaymentResponse
           └─ publish → Kafka: payment-events { orderId, status }
                │
                ▼ order-service (Kafka consumer)
                  └─ update Order { status: PAYMENT_SUCCESS | PAYMENT_FAILED }
```

---

## Quick Start

### Docker (recommended — starts everything)

```bash
docker compose up -d
```

Waits for healthchecks in this order: Kafka → discovery-server → config-server → all business services.

Check Eureka dashboard: `http://localhost:8084`

### Local Development

Prerequisites: Java 17, Maven 3.9+, Docker (for Kafka).

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

Tests use `@SpringBootTest` + `MockMvc`. Config server and Eureka are disabled in the test profile via `src/test/resources/application.properties` in each service.

---

## Key Design Notes

- **H2 in-memory per service** — no external DB needed for local dev; data resets on restart.
- **Native config** — config-server serves YAML from `classpath:/config/` (no external Git repo).
- **Mock payment provider** — 80% SUCCESS / 20% FAILED, random transaction ID.
- **Circuit breaker** — Resilience4j wraps the order-service → payment-service call; on failure the order is saved as `PAYMENT_FAILED`.
- **Known race condition** — if Kafka delivery is very fast, the consumer can update order status before `placeOrder()` sets `PAYMENT_INITIATED`, causing a stale-entity conflict that triggers the circuit breaker fallback. The final status in DB is still correct because the Kafka consumer's write wins.
