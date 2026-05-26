# Architecture — Order Management Service

---

## System Diagram

```
  Client (Postman / curl)
          │
          ▼
  ┌───────────────┐
  │  API Gateway  │  :8085  — routes by path prefix
  └───────┬───────┘
          │
  ┌───────┴──────────────────────────────┐
  │              Eureka                  │
  │  lb://USER-SERVICE                   │
  │  lb://PRODUCT-SERVICE                │
  │  lb://ORDER-SERVICE                  │
  │  lb://PAYMENT-SERVICE                │
  └─────────────────────────────────────-┘
          │
  ┌───────┴──────────────────────────────────────┐
  │                                              │
  ▼                    ▼              ▼          ▼
user-service    product-service  order-service  payment-service
  :8081            :8082           :8083          :8088
  H2:userdb        H2:productdb    H2:orderdb     H2:paymentdb
                                    │   ▲              │
                                    │   │ Kafka event  │
                      REST call ────┘   └──────────────┘
                   (user + product)     topic: payment-events


  ┌──────────────────┐      ┌──────────────────┐
  │  Discovery Server│      │  Config Server   │
  │  (Eureka) :8084  │      │  :8086           │
  │  All services    │      │  GitHub-backed   │
  │  register here   │      │  config repo     │
  └──────────────────┘      └──────────────────┘
```

---

## Communication Patterns

### Synchronous (REST)

| Caller | Callee | When | How |
|---|---|---|---|
| `api-gateway` | All services | Every inbound request | Spring Cloud Gateway + Eureka lb:// |
| `order-service` | `user-service` | Order placement — validate user | `RestTemplate` + `@LoadBalanced` |
| `order-service` | `product-service` | Order placement — get price/stock | `RestTemplate` + `@LoadBalanced` |
| `order-service` | `payment-service` | Order placement — trigger payment | `RestTemplate` + JWT header |

### Asynchronous (Kafka)

| Producer | Consumer | Topic | Event format | Trigger |
|---|---|---|---|---|
| `payment-service` | `order-service` | `payment-events` | `{"orderId":"...","status":"SUCCESS\|FAILED"}` | After payment processed |

The Kafka event allows order-service to update order status without blocking the payment call response chain.

---

## Service Details

### Discovery Server (Eureka)
- **Port:** 8084
- **Role:** Registry. Every other service registers here on startup and
  resolves `lb://SERVICE-NAME` URLs through it.
- **Not a Eureka client itself** (`register-with-eureka=false`, `fetch-registry=false`).

### Config Server
- **Port:** 8086
- **Role:** Serves `application.yml` / `application.properties` files to other services
  at startup, pulled from a dedicated GitHub config repository.
- **Config repo:** `github.com/Rishav30194/order-management-config`
- **Per-service files:** `user-service.yml`, `payment-service.yml`, etc.
- Registers with Eureka so services can discover it.

### API Gateway
- **Port:** 8085
- **Role:** Single inbound entry point. Clients hit the gateway; it resolves the
  downstream service via Eureka and proxies the request.
- **Routes (path → service):**

| Path prefix | Downstream service |
|---|---|
| `/api/users/**` | `lb://USER-SERVICE` |
| `/api/products/**` | `lb://PRODUCT-SERVICE` |
| `/api/orders/**` | `lb://ORDER-SERVICE` |
| `/api/payments/**` | `lb://PAYMENT-SERVICE` |

### User Service
- **Port:** 8081
- **DB:** `H2:userdb`
- **Model:** `User { id, name, email, password }`
- **Auth role:** Issues JWT tokens on `/api/user/login`. All downstream
  services that require auth validate tokens signed with the same shared secret
  (via config-server).
- **Security:** Registration and login are public. All other user endpoints
  are public for now (learning scope).

### Product Service
- **Port:** 8082
- **DB:** `H2:productdb`
- **Model:** `Product { id, name, description, price, stock, category }`
- **Security:** All endpoints public.
- **Called by:** `order-service` to fetch product price before calculating total.

### Order Service
- **Port:** 8083
- **DB:** `H2:orderdb`
- **Model:** `Order { id, userId, productId, quantity, totalPrice, status, createdAt }`
- **Order status lifecycle:**
  ```
  PLACED → PAYMENT_INITIATED → PAYMENT_SUCCESS | PAYMENT_FAILED
  ```
- **On order placement:**
  1. Validate user exists (call user-service)
  2. Fetch product and price (call product-service)
  3. Calculate `totalPrice = product.price × quantity`
  4. Persist order with status `PLACED`
  5. Call payment-service with order details + JWT
  6. Update status to `PAYMENT_INITIATED`
- **On Kafka event from payment-service:**
  - Update order status to `PAYMENT_SUCCESS` or `PAYMENT_FAILED`
- **Circuit breaker:** Resilience4j wraps the payment-service call.
  If payment-service is down, order is saved with status `PAYMENT_FAILED`.

### Payment Service
- **Port:** 8088
- **DB:** `H2:paymentdb`
- **Model:** `PaymentResponse { id, transactionId, orderId, status, message, createdAt }`
- **Payment flow:**
  1. Receive `PaymentRequest { orderId, amount, customerId, paymentMethod }`
  2. Validate JWT from incoming `Authorization` header
  3. `MockPaymentProvider` simulates processing: 80% SUCCESS, 20% FAILED
  4. Persist `PaymentResponse` to DB
  5. Publish Kafka event to `payment-events`
  6. Return `PaymentResponseDto` to caller
- **Security:** `/api/payments/**` requires a valid JWT. All other paths are public.

---

## Security Design

```
  User logs in → user-service issues JWT (HS256, shared secret from config-server)
                                │
                                ▼
  Client sends JWT in Authorization: Bearer <token>
                                │
                    ┌───────────┴────────────┐
                    │                        │
              api-gateway            payment-service
           (validates JWT           (validates JWT in
            before routing)          JwtAuthenticationFilter)
```

- JWT secret is stored in config-server and shared across services.
- Service-to-service calls (order-service → payment-service) use a
  service-level JWT generated internally — not a user token.

---

## Startup Order

Services must start in this order due to hard dependencies:

```
1. discovery-server   — must be up before anything registers
2. config-server      — must be up before services pull config
3. user-service       — no service deps
4. product-service    — no service deps
5. payment-service    — no service deps (Kafka must be running)
6. order-service      — depends on user, product, payment (via Eureka)
7. api-gateway        — should start last so all routes resolve
```

Kafka must be running before payment-service and order-service start.
Run `docker-compose up -d` before starting any service.

---

## Data Flow: Place an Order

```
Client
  │  POST /api/orders/  { userId, productId, quantity }
  ▼
api-gateway
  │  route to lb://ORDER-SERVICE
  ▼
order-service
  ├─ GET lb://USER-SERVICE/api/user/{userId}       → validate user
  ├─ GET lb://PRODUCT-SERVICE/api/products/{id}    → get price
  ├─ save Order { status: PLACED }
  ├─ POST lb://PAYMENT-SERVICE/api/payments/       → trigger payment (JWT)
  │     update Order { status: PAYMENT_INITIATED }
  │
  ▼
payment-service
  ├─ validate JWT
  ├─ MockPaymentProvider → 80% SUCCESS / 20% FAILED
  ├─ save PaymentResponse
  ├─ publish → Kafka: payment-events { orderId, status }
  └─ return PaymentResponseDto
  │
  ▼
order-service (Kafka consumer)
  └─ update Order { status: PAYMENT_SUCCESS | PAYMENT_FAILED }
```
