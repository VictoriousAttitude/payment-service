# payment-service

A payment processing service implementing the 3-layer safety model inspired by [Stripe's money movement validation architecture](https://stripe.com/blog/payment-api-design).

Built with Kotlin, Spring Boot 3, PostgreSQL — focused on financial correctness, not feature breadth.

## architecture

```
┌──────────────────────────────────────────────────────┐
│                    API layer                          │
│  POST /payments  POST /capture  POST /refund  GET /  │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│  Layer 1: GATE                                       │
│  - idempotency (DB unique constraint)                │
│  - request validation (Bean Validation)              │
│  - merchant verification (active status check)       │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│  Layer 2: CORE                                       │
│  - state machine (enum with transition rules)        │
│  - double-entry ledger (immutable, append-only)      │
│  - atomic writes (@Transactional: status + ledger)   │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│  Layer 3: GUARD                                      │
│  - stuck transaction detection                       │
│  - missing ledger entry detection                    │
│  - per-transaction balance verification              │
│  - global ledger balance verification                │
└──────────────────────────────────────────────────────┘
```

## payment state machine

```
CREATED ──→ PENDING ──→ AUTHORIZED ──→ CAPTURED ──→ SETTLED
   │           │            │              │
   └→ FAILED   └→ FAILED    └→ FAILED      └→ REFUNDED
```

Every transition is enforced by `PaymentStatus.transitionTo()`. Invalid transitions throw `InvalidStateTransitionException` (HTTP 409). Terminal states (`SETTLED`, `FAILED`, `REFUNDED`) have no outgoing edges.

## double-entry ledger

Every money movement creates balanced entries. Debits always equal credits.

**Capture** (10000 cents, 2% fee):
| entry | account | type | amount |
|-------|---------|------|--------|
| 1 | INCOMING | DEBIT | 10000 |
| 2 | MERCHANT | CREDIT | 9800 |
| 3 | PLATFORM | CREDIT | 200 |

**Refund** reverses with opposite entries. `LedgerService.validateBalance()` asserts `sum(debits) == sum(credits)` before persisting — if math is wrong, the transaction rolls back.

Amounts are stored as `BIGINT` cents (never floats). Fees calculated in basis points: `200 bps = 2.00%`, integer arithmetic only.

## key design decisions

| decision | why |
|----------|-----|
| BIGINT cents, not DECIMAL | IEEE 754: `0.1 + 0.2 ≠ 0.3`. Stripe, Adyen, Square all use integer cents |
| basis points for fees | `200 bps = 2.00%`. avoids floating-point division until final display |
| `@Transactional` on capture/refund | status change + ledger entries are atomic. both succeed or both rollback |
| idempotency via DB constraint | `UNIQUE (merchant_id, idempotency_key)`. no distributed locks needed |
| ledger entries are immutable | no `updated_at` column. append-only — financial audit trail |
| `hibernate.ddl-auto: validate` | flyway owns the schema. hibernate only validates — no surprise DDL |
| Testcontainers, not H2 | H2 has different JSONB, constraint, and index behavior vs PostgreSQL |
| state machine in enum | compile-time exhaustive checks. impossible to add a state without defining transitions |

## api

| method | endpoint | description |
|--------|----------|-------------|
| POST | `/api/v1/payments` | create payment (requires `Idempotency-Key` header) |
| GET | `/api/v1/payments/{id}` | get payment status |
| POST | `/api/v1/payments/{id}/capture` | capture authorized payment (atomic: status + ledger) |
| POST | `/api/v1/payments/{id}/refund` | refund captured payment (atomic: status + ledger) |
| POST | `/api/v1/webhooks/provider-callback` | simulate provider authorization callback |
| GET | `/api/v1/merchants/{id}/balance` | computed merchant balance from ledger |
| GET | `/api/v1/reconciliation` | full reconciliation report (layer 3 guard) |
| GET | `/api/v1/api-docs` | OpenAPI 3.0 spec |
| GET | `/swagger-ui.html` | Swagger UI |

## tech stack

| component | version | why |
|-----------|---------|-----|
| Kotlin | 1.9.25 | Spring Boot 3.5 managed version, null safety, concise JPA entities |
| Spring Boot | 3.5.0 | latest stable, modulith-ready, native Testcontainers support |
| PostgreSQL | 17 | JSONB for payment methods, CHECK constraints, production-grade |
| Flyway | managed | versioned schema migrations, repeatable builds |
| Testcontainers | 1.21.4 | real PostgreSQL in tests, not H2 |
| SpringDoc OpenAPI | 2.8.6 | auto-generated API docs from controllers |

## project structure

```
src/main/kotlin/com/paymentservice/
├── config/
│   └── GlobalExceptionHandler.kt       # centralized error responses
├── ledger/
│   ├── LedgerEntry.kt                  # immutable entity, EntryType, AccountType
│   ├── LedgerRepository.kt             # balance computation, imbalance detection
│   └── LedgerService.kt                # capture/refund entries, fee calculation
├── merchant/
│   ├── Merchant.kt                     # JPA entity
│   ├── MerchantController.kt           # balance endpoint
│   └── MerchantRepository.kt
├── payment/
│   ├── dto/
│   │   ├── CreatePaymentRequest.kt     # validated request DTO
│   │   └── PaymentResponse.kt          # response DTO with factory method
│   ├── PaymentController.kt            # REST endpoints
│   ├── PaymentProviderSimulator.kt     # async authorization simulation
│   ├── PaymentService.kt               # core orchestrator (gate + core layers)
│   ├── PaymentStatus.kt                # state machine enum
│   ├── Transaction.kt                  # JPA entity with JSONB payment method
│   ├── TransactionRepository.kt        # custom queries for stuck/orphaned txns
│   └── WebhookController.kt            # provider callback endpoint
└── reconciliation/
    ├── ReconciliationController.kt      # guard layer endpoint
    └── ReconciliationService.kt         # stuck, missing, imbalanced detection
```

## testing

37 tests total. all green.

**unit tests** (26 parameterized, no DB):
- state machine: all valid transitions, all invalid transitions, terminal state enforcement

**integration tests** (10, real PostgreSQL via Testcontainers):
- full payment lifecycle: create → authorize → capture → verify ledger entries + balance
- full lifecycle with refund: 6 ledger entries, balance math verified
- idempotency: same key returns same transaction
- state enforcement: capture on FAILED (409), capture on PENDING (409), double capture (409)
- reconciliation: healthy report after valid lifecycle, global balance after multi-capture + refund
- edge cases: non-existent merchant (404), provider decline sets failure reason

```bash
./gradlew test
```

## running locally

```bash
# start postgresql
docker compose up -d

# run the service
./gradlew bootRun

# or with docker
docker build -t payment-service .
docker run --network host payment-service
```

## quick test

```bash
# create payment
curl -s -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-001" \
  -d '{"merchantId":"a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11","amount":10000,"currency":"EUR"}' | jq .

# authorize (simulate provider callback)
curl -s -X POST http://localhost:8080/api/v1/webhooks/provider-callback \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"<ID_FROM_ABOVE>","authorized":true,"providerReference":"prov_123"}'

# capture (atomic: status + ledger)
curl -s -X POST http://localhost:8080/api/v1/payments/<ID>/capture | jq .

# check balance (10000 - 2% fee = 9800)
curl -s http://localhost:8080/api/v1/merchants/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11/balance | jq .

# reconciliation (layer 3 guard)
curl -s http://localhost:8080/api/v1/reconciliation | jq .
```

## database schema

3 flyway migrations:

- `V1` — `merchants` table + test merchant seed
- `V2` — `transactions` table with `UNIQUE(merchant_id, idempotency_key)`, BIGINT amounts, JSONB payment_method
- `V3` — `ledger_entries` table, immutable (no `updated_at`), CHECK constraints on amounts
