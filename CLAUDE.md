# Claude Agent Instructions — Order Management Service

Read this file first, then `README.md` for full architecture and API reference.

---

## Project Context

Learning project — Spring Cloud microservices simulating an e-commerce order backend.
All phases are complete. Read `README.md` for current architecture, endpoints, and design notes.

---

## Service Map (quick reference)

| Service | Port | Key files |
|---|---|---|
| `discovery-server` | 8084 | `DiscoveryServerApplication.java` |
| `config-server` | 8086 | `application.yml`, `src/main/resources/config/` |
| `api-gateway` | 8085 | `application.yml` (routes) |
| `user-service` | 8081 | `UserController`, `UserServiceImpl`, `SecurityConfig` |
| `product-service` | 8082 | `ProductController`, `ProductServiceImpl` |
| `order-service` | 8083 | `OrderServiceImpl`, `PaymentEventConsumer`, `RestTemplateConfig` |
| `payment-service` | 8088 | `PaymentServiceImpl`, `JwtAuthenticationFilter`, `PaymentEventPublisher` |

---

## Git Workflow

- Never commit directly to `main`.
- Branch: `feature/`, `fix/`, `chore/` prefix.
- One PR per logical change. Do not bundle unrelated changes.

---

## How to Work With Me

### Plan before code
For any non-trivial change, state:
- Which file(s) will change and why
- The new method/class signatures
- Any risk to the existing flow

Wait for confirmation before writing code. For single-line fixes, proceed directly.

### Read before edit
Always read the target file before editing it. Do not assume what's there
matches any documentation — the code is the source of truth.

### Minimal diffs
Change only what the current task requires. Do not refactor surrounding code,
rename variables for style, or add unrelated features.

---

## Coding Standards

- Java 17. Use records for DTOs where appropriate.
- No raw `String` status values — use enums or constants.
- `@Transactional` on all service methods that write to the DB.
- No `System.out.println` — use `slf4j` (`@Slf4j` + `log.info/warn/error`).
- Validate request bodies with `@Valid` and annotated DTO fields.
- Match the existing package structure in each service:
  `controller / service / service/impl / repository / model / dto / config / exception`

## Inter-service Communication

- HTTP calls use `RestTemplate` with `@LoadBalanced` — always use Eureka service names
  (`lb://USER-SERVICE`, `lb://PRODUCT-SERVICE`, etc.), never hardcode `localhost` URLs.
- Service-to-service calls that require auth must include a JWT in the
  `Authorization: Bearer <token>` header.
- Kafka topic name: `payment-events`. Do not create new topics without updating `README.md`.

## Security

- JWT secret comes from config-server (`classpath:/config/application.yml`) — never hardcode it.
- `payment-service` requires a valid JWT on `/api/payments/**`.
- Do not bypass the JWT filter or add `permitAll()` to secured endpoints without explicit instruction.

---

## What Not to Build (outside this project's scope)

- Frontend or UI of any kind
- Real payment gateway integration (Stripe, Razorpay, etc.)
- Replacing H2 with MySQL/PostgreSQL
- Multi-tenant or multi-user auth flows
- Message schemas beyond the current `payment-events` JSON format
