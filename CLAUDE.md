# Claude Agent Instructions — Order Management Service

Read this file first, then `docs/implementation_phases.md` to find the current task.

---

## Project Context

Learning project — Spring Cloud microservices simulating an e-commerce order backend.
Read these docs in order:

1. `docs/project_overview.md` — what each service does, ports, endpoints, local setup
2. `docs/architecture.md` — how services communicate, data flow, security design
3. `docs/implementation_phases.md` — what is done, what is next (checked = done)

Before writing any code, open `implementation_phases.md`, find the first unchecked task
in the earliest incomplete phase, and confirm it with the user.

---

## Service Map (quick reference)

| Service | Port | Key files |
|---|---|---|
| `discovery-server` | 8084 | `DiscoveryServerApplication.java` |
| `config-server` | 8086 | `application.yml` (git URI) |
| `api-gateway` | 8085 | `application.yml` (routes) |
| `user-service` | 8081 | `UserController`, `UserServiceImpl`, `SecurityConfig` |
| `product-service` | 8082 | `ProductController`, `ProductServiceImpl` |
| `order-service` | 8083 | `OrderServiceImpl`, `PaymentEventConsumer`, `RestTemplateConfig` |
| `payment-service` | 8088 | `PaymentServiceImpl`, `JwtAuthenticationFilter`, `PaymentEventPublisher` |

---

## Git Workflow

- Never commit directly to `main`.
- Branch: `feature/`, `fix/`, `chore/` prefix.
- One PR per phase task. Do not bundle unrelated changes.

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
matches what was documented — the code is the source of truth.

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
- Kafka topic name: `payment-events`. Do not create new topics without updating
  `architecture.md`.

## Security

- JWT secret comes from config-server — never hardcode it in application files.
- `payment-service` requires a valid JWT on `/api/payments/**`.
- Do not bypass the JWT filter or add `permitAll()` to secured endpoints without
  explicit instruction.

---

## Confirmed Bugs (do not introduce workarounds — fix properly)

1. `UserController.getUserById()` — mapped to `@PostMapping`, should be `@GetMapping`.
2. `order-service` and `product-service` `application.properties` — JPA properties
   missing the `spring.` prefix, so Hibernate config is silently ignored.
3. `PaymentEventConsumer.updateOrderStatus()` — stub that only logs; does not update DB.
4. `PaymentRequest` — annotated `@Entity` but is a DTO; causes unintended DB table.
5. API Gateway — user/product/order routes are commented out; only payment is routed.

---

## What Not to Build (outside this project's scope)

- Frontend or UI of any kind
- Real payment gateway integration (Stripe, Razorpay, etc.)
- Replacing H2 with MySQL/PostgreSQL
- Multi-tenant or multi-user auth flows
- Message schemas beyond the current `payment-events` JSON format (unless in a new phase)
