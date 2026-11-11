# Retry and failure handling fixes

Read this file first when you resume this work. Update the status table at the end of every PR.

## Status

| PR | Scope | Branch | State |
|---|---|---|---|
| 1 | Payment call retry, HTTP time limits, and 4xx ignored by the circuit breaker | `fix/payment-call-retry` | Built and verified (50 tests, `verify_e2e.py` passed), not committed |
| 2 | 503 for unavailable downstream services, and checked total price | `fix/downstream-error-codes` | Not started |
| 3 | Consumer retries only database errors; other failures go to a dead-letter topic | `fix/consumer-dead-letter` | Not started |
| 4 | `Idempotency-Key` header on `POST /api/orders` | `feature/order-idempotency-key` | Not started |
| 5 | Gateway time limits and `GET` retry | `fix/gateway-timeouts` | Not started |
| 6 | Payment events keyed by order ID | `fix/keyed-payment-events` | Not started |

## Working rules

- One branch and one pull request (PR) for each row in the status table, each starting from `main`.
- Commit messages don't include a Claude co-author line.
- `INTERVIEW_PREP.md` stays untracked. Stage files by explicit path.
- No build file changes. Retries use `org.springframework.core.retry.RetryTemplate` from `spring-core` 7.
- A PR is complete when `mvn test` passes for the whole repository and the README matches the code.
  PRs 1, 3, and 5 also need `scripts/verify_e2e.py` to pass against `docker compose up`.

## Findings this work fixes

| Step | Problem before the fix |
|---|---|
| `order-service` → `payment-service` | One connection reset or 5xx response fails the order for good. Nothing retries it. |
| `order-service` → any service | `new RestTemplate()` has no connect or read time limit. |
| Circuit breaker | Counts 4xx responses as failures, so a bad `JWT_SECRET` opens it for every order. |
| Kafka consumer | Retries every error every 5 seconds forever, so one bad event blocks its partition. |
| `POST /api/orders` | A client retry creates a second order. |
| Downstream errors | User or product service outages return 500 with the raw exception message. |
| Total price | `price * quantity` on `Long` can overflow silently. |
| Gateway | No time limits and no retry for reads. |
| Payment events | Sent without a key, so one order's events can land on different partitions. |

## Decisions

- **Retry inside the circuit breaker.** The breaker records one result per order, and an open circuit isn't retried.
  The 12-second time limit then covers all tries, so each try has a 1-second connect and 2.5-second read limit:
  3 tries × 3.5 s + 0.6 s of waits ≈ 11.1 s.
- **Retry only `ResourceAccessException` and `HttpServerErrorException`.** A 4xx or a declined payment is final.
  Retrying is safe because `payment-service` handles each `orderId` once.
- **Dead-letter topic, not log and skip.** A skipped `SUCCESS` event leaves a paid order that the sweeper marks failed.
  The topic keeps the event for replay.
- **Retry list for the consumer names `DataAccessResourceFailureException`.** Spring classes it as non-transient,
  so retrying only `TransientDataAccessException` would stop the database-down retry.
- **The gateway retries `GET` only.** `POST /api/orders` isn't safe to repeat until PR 4 is merged, and still needs a key after it.
- **Not retrying user and product calls in `order-service`.** It adds about 7 seconds to the worst case.
  The gateway's `GET` retry covers direct reads.

## PR details

### PR 1: payment call retry

- `RestTemplateConfig`: `SimpleClientHttpRequestFactory` with `order.http.connect-timeout` and `order.http.read-timeout`.
- `PaymentRetryConfig`: `paymentRetryTemplate` bean, 2 retries, 200 ms first wait, doubling.
- `OrderServiceImpl.placeOrder`: the payment call runs through `paymentRetryTemplate` inside the breaker.
- `application.properties`: `ignore-exceptions=org.springframework.web.client.HttpClientErrorException` on the breaker.
- Tests in `PaymentCallRetryTest`: retry then success, 3 tries then failure, 4xx not retried, 4xx doesn't open the breaker.

### PR 2: downstream error codes

- `DownstreamUnavailableException` wraps `ResourceAccessException`, `HttpServerErrorException`, and the load balancer's
  `IllegalStateException` from the user and product calls. The handler returns 503.
- `Math.multiplyExact` for the total price. `ArithmeticException` returns 400.

### PR 3: consumer dead-letter topic

- `KafkaConsumerConfig`: `DeadLetterPublishingRecoverer` and `setClassifications(..., false)` that retry only
  `TransientDataAccessException`, `DataAccessResourceFailureException`, and `CannotCreateTransactionException`.
- `NewTopic` bean for `payment-events.DLT`. README lists the topic and how to replay it.

### PR 4: order idempotency key

- `Order.idempotencyKey`, unique and hidden from JSON. `OrderController` reads an optional `Idempotency-Key` header.
- A repeat with the same body returns the first order. A repeat with a different body returns 409.
  A concurrent repeat loses on the unique constraint and returns the first order.

### PR 5: gateway time limits

- `connect-timeout: 2000` and `response-timeout: 30s`. An order's worst case is about 19 seconds.
- Default `Retry` filter: `GET` only, 2 retries, on 502 and 503.

### PR 6: keyed payment events

- `OutboxEvent.orderId`. `PaymentEventPublisher.publishPaymentEvent(String key, String payload)`.
