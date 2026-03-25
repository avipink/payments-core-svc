# payments-core-svc

## Role in Product
Authoritative payment processing service. Validates source and destination accounts via
`accounts-core-svc`, enforces a $10,000 daily outbound limit per source account, and persists
payment records. Serves as the payment ledger for the platform.

## Key Entry Points
| Class | Path | Methods |
|---|---|---|
| `PaymentController` | `/api/v1/payments` | `POST` — create payment (validates accounts, enforces daily limit) |
| `PaymentController` | `/api/v1/payments/{id}` | `GET` — payment detail by ID |
| `PaymentController` | `/api/v1/payments/account/{accountId}` | `GET` — all payments for an account (source or destination) |

No Kafka consumers. No scheduled jobs.

## Domain Model
| Class | Kind | Notes |
|---|---|---|
| `Payment` | internal `data class` | Full payment record — **never exposed at API boundary**; always projected to `PaymentResponse` |
| `PaymentService` | `@Service` | Core business logic: account validation, daily limit enforcement, persistence |
| `PaymentRepository` | `@Repository` | In-memory `MutableMap<String, Payment>`; 3 pre-seeded payments (PAY-001 to PAY-003) |
| `AccountClient` | `@Component` | WebClient adapter for `accounts-core-svc`; returns null on 404 or connectivity failure |
| `PaymentDomainException` | `RuntimeException` | Wraps `PaymentError` sealed class for Spring MVC propagation |
| `GlobalExceptionHandler` | `@RestControllerAdvice` | Maps `PaymentError` variants → HTTP 404 / 422 + `ApiError` response |

**Business rules enforced in `PaymentService`:**
1. `fromAccountId` must resolve to an ACTIVE account in `accounts-core-svc`
2. `toAccountId` must resolve to an ACTIVE account in `accounts-core-svc`
3. Daily outbound total for `fromAccountId` must not exceed `$10,000` (constant `DAILY_LIMIT` in `PaymentService.kt`)

**Known gap**: `PaymentError.InsufficientFunds` is defined and handled but never thrown —
balance is not checked against payment amount.

## Depends On
- `banking-contracts` — Gradle composite build (`includeBuild("../banking-contracts")`)
  - Types used: `PaymentRequest`, `PaymentResponse`, `PaymentError`, `PaymentStatus`, `PaymentType`, `AccountResponse`, `AccountStatus`, `MonetaryAmount`, `ApiError`
- `accounts-core-svc` (:8081) — HTTP via `AccountClient` (Spring WebClient + `.block()`)
  - `GET /api/v1/accounts/{id}` — called **twice** per `createPayment()`: once for source, once for destination
  - Config: `accounts-service.base-url` (default: `http://localhost:8081`)
  - No timeout, no circuit breaker, no retry configured

## Events Published and Consumed
None. No Kafka, no message broker, no scheduled jobs.

## Database
None. In-memory `MutableMap<String, Payment>` mock store only. Data is lost on restart.
No JPA, no Flyway/Liquibase. Target: relational DB with JPA (noted in code comments).

Daily limit uses string-prefix date matching on `createdAt` (`"YYYY-MM-DD"`) — UTC-only assumption.
`nextId()` is not thread-safe under concurrent requests.

Pre-seeded payments: PAY-001 (COMPLETED), PAY-002 (COMPLETED), PAY-003 (PENDING).

## External Integrations
None. No payment processor, no fraud detection, no third-party APIs.

## Known Complexity and Patterns
- **Sequential account validation**: Two blocking HTTP calls to `accounts-core-svc` on every payment
  creation — latency is additive; no parallel execution.
- **accounts-svc downtime = silent 404**: If `accounts-core-svc` is unreachable, `AccountClient`
  returns null, which triggers `PaymentError.InvalidAccount` — indistinguishable from account-not-found.
- **Typed sealed errors**: `PaymentError` sealed class with exhaustive `when` in handler.
- **Daily limit race condition**: `getDailyTotal()` + `save()` are not atomic — concurrent requests
  can exceed the $10,000 limit.
