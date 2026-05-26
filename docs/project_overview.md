# Project Overview — Order Management Service

A learning project to understand Spring Cloud microservices. It simulates a simplified
e-commerce backend: users browse products, place orders, and payments are processed
asynchronously via Kafka.

There is no frontend. All interaction is via REST APIs (Postman / curl).

---

## Services

| Service | Port | Responsibility |
|---|---|---|
| `discovery-server` | 8084 | Eureka — service registry for all other services |
| `config-server` | 8086 | Centralized config pulled from a GitHub repo |
| `api-gateway` | 8085 | Single entry point; routes requests to downstream services |
| `user-service` | 8081 | User CRUD; issues JWT tokens on login |
| `product-service` | 8082 | Product catalogue CRUD |
| `order-service` | 8083 | Places orders; calls user and product service; triggers payment |
| `payment-service` | 8088 | Processes payments (mock provider); publishes Kafka events |

---

## Tech Stack

- **Java 17**, Spring Boot 3.5.4, Spring Cloud 2025.0.0
- **Service discovery:** Netflix Eureka
- **API gateway:** Spring Cloud Gateway
- **Async messaging:** Apache Kafka (KRaft mode, single broker)
- **Inter-service HTTP:** RestTemplate with Eureka client-side load balancing
- **Security:** Spring Security + JWT (HS256)
- **Database:** H2 in-memory (one DB per service)
- **Build:** Maven multi-module (parent pom at repo root)
- **Config:** Spring Cloud Config Server (GitHub-backed)

---

## API Endpoints

### User Service — `localhost:8081`

| Method | Path | Description |
|---|---|---|
| POST | `/api/user/register` | Create a new user |
| POST | `/api/user/login` | Authenticate and receive a JWT |
| GET | `/api/user/` | List all users |
| GET | `/api/user/{id}` | Get user by ID |
| PUT | `/api/user/{id}` | Update user |
| DELETE | `/api/user/{id}` | Delete user |

### Product Service — `localhost:8082`

| Method | Path | Description |
|---|---|---|
| POST | `/api/products/` | Create product |
| GET | `/api/products/` | List all products |
| GET | `/api/products/{id}` | Get product by ID |
| PUT | `/api/products/{id}` | Update product |
| DELETE | `/api/products/{id}` | Delete product |

### Order Service — `localhost:8083`

| Method | Path | Description |
|---|---|---|
| POST | `/api/orders/` | Place an order (triggers payment) |
| GET | `/api/orders/` | List all orders |
| GET | `/api/orders/{id}` | Get order by ID |

### Payment Service — `localhost:8088` (requires JWT)

| Method | Path | Description |
|---|---|---|
| POST | `/api/payments/` | Process a payment (called by order-service) |

---

## Local Development Setup

### Prerequisites
- Java 17
- Maven 3.9+
- Docker (for Kafka)

### Start infrastructure

```bash
# From repo root — starts Kafka
docker-compose up -d
```

### Start services (in order)

Start each service from its subdirectory or run all from the parent:

```bash
# Start in this order — each depends on the previous
1. discovery-server   (Eureka must be up first)
2. config-server      (other services pull config from here)
3. user-service
4. product-service
5. payment-service
6. order-service
7. api-gateway        (start last, after all upstream services register)
```

```bash
# From repo root, build everything
mvn clean install -DskipTests

# Run individual service
cd <service-directory>
mvn spring-boot:run
```

### Verify all services registered
Open Eureka dashboard: `http://localhost:8084`

---

## Key Design Decisions

- **H2 in-memory DB per service** — keeps local dev simple; no external DB to manage.
- **Mock payment provider** — 80% success / 20% failure rate, random transaction ID.
- **Async status update** — payment-service publishes a Kafka event after processing;
  order-service consumes it and updates order status.
- **Synchronous payment trigger** — order-service calls payment-service directly when
  an order is placed (no frontend involvement).
