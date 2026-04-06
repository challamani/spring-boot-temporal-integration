# Spring Boot + Temporal — Saga Order Processing

A multi-service order processing system demonstrating the **Saga pattern** orchestrated by [Temporal](https://temporal.io), with human-in-the-loop approval, workflow versioning, and Gatling load testing.

## Architecture — Saga Pattern with Temporal

In a microservices architecture, a single business operation (placing an order) spans multiple services. The **Saga pattern** manages this as a sequence of local transactions, each with a **compensating action** that undoes its effect on failure.

Temporal orchestrates the saga — the `OrderWorkflow` drives each step and automatically runs compensations in reverse order if any step fails:

```
                          ┌─────────────────────────────────────────────────────────────────────┐
                          │              OrderWorkflow  (Temporal Saga)                         │
                          │                                                                     │
  POST /orders ──▶        │   ┌───────────┐     ┌────────────┐    ┌─────────┐    ┌─────────────┐
                          │   │ Inventory  │──▶ │ Fraud Check│──▶ │ Payment │──▶ │  Shipment   │──▶ SUCCESS
                          │   │ (reserve)  │    │ + Approval │    │ (charge)│    │  (create)   │
                          │   └─────┬──────┘    └─────┬──────┘    └────┬────┘    └─────────────┘
                          │         │                 │                │                        │
                          │   On failure, compensations run in reverse:                         │
                          │         │                 │                │                        │
                          │    cancel reservation     │          refund payment                 │
                          └─────────────────────────────────────────────────────────────────────┘
```

**Why Temporal instead of event-driven choreography?**

| Concern | Choreography (events) | Temporal Orchestration |
|---|---|---|
| Compensation logic | Scattered across services | Centralized in `OrderWorkflowImpl` via `Saga` class |
| Failure visibility | Requires tracing across event logs | Single workflow history in Temporal UI |
| Retry / timeout | Each service implements its own | Declarative via `ActivityOptions` |
| Human-in-the-loop | Complex — external state + polling | Built-in — `Workflow.await()` + Signals |

## Modules

| Module | Port | Role |
|---|---|---|
| `common` | — | Shared workflow/activity interfaces, DTOs, task queue constants |
| `order-service` | 8080 | REST API + `OrderWorkflow` saga orchestrator + fraud check activity |
| `inventory-service` | 8082 | `InventoryActivity` — reserve / cancel stock |
| `payment-service` | 8081 | `PaymentActivity` — charge / refund |
| `logistics-service` | 8083 | `ShippingActivity` — create shipment |
| `load-testing` | — | Gatling performance tests (smoke / default / stress profiles) |
| `temporal-local/` | 7233, 8088 | Docker Compose: Temporal Server + PostgreSQL + Temporal UI |

## Quick Start

**Prerequisites:** Java 21, Maven 3.9+, Docker

```bash
# 1. Start Temporal
cd temporal-local && docker compose up -d

# 2. Bootstrap (once after cloning)
mvn -N install && mvn -pl common install -DskipTests

# 3. Start services (each in a separate terminal)
mvn -pl order-service spring-boot:run
mvn -pl inventory-service spring-boot:run
mvn -pl payment-service spring-boot:run
mvn -pl logistics-service spring-boot:run

# 4. Place an order
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","customerId":"CUST-1","productId":"PROD-001","quantity":2,"amount":500,"shippingAddress":"123 Main St"}'

# 5. Get result
curl http://localhost:8080/orders/order-ORD-001?timeout=10
```

**Temporal UI:** http://localhost:8088

## Saga Compensation in Action

Force failures to see compensations:

| Scenario | Payload tweak | What happens |
|---|---|---|
| Payment failure | `"amount": 0` | Inventory reservation is cancelled |
| Shipment failure | `"shippingAddress": ""` | Payment is refunded → reservation is cancelled |
| Fraud rejection | `"amount": 9999` | Auto-rejected by fraud check → reservation is cancelled |

## Human-in-the-Loop

Orders between $1,001–$5,000 trigger a `NEEDS_REVIEW` fraud decision. The workflow **durably pauses** — no thread, no memory consumed — and waits for a human signal:

```bash
# Place order requiring review
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-R1","customerId":"CUST-1","productId":"PROD-001","quantity":1,"amount":2500,"shippingAddress":"123 Main St"}'
```

```bash
# Check status (WAITING_FOR_APPROVAL)
curl http://localhost:8080/orders/order-ORD-R1/approval-status
```

```bash
# Approve
curl -X POST http://localhost:8080/orders/order-ORD-R1/approve \
  -H "Content-Type: application/json" \
  -d '{"approved": true, "reviewerNote": "Verified"}'
```

```bash
# Or reject
curl -X POST http://localhost:8080/orders/order-ORD-R1/approve \
  -H "Content-Type: application/json" \
  -d '{"approved": false, "reviewerNote": "Suspicious"}'
```

If no signal arrives within 24 hours, the order auto-rejects and compensations run.

| Amount | Fraud Decision | Behavior |
|---|---|---|
| ≤ $1,000 | `APPROVED` | Auto-approved → payment proceeds |
| $1,001–$5,000 | `NEEDS_REVIEW` | Workflow pauses for human signal |
| > $5,000 | `REJECTED` | Auto-rejected → compensations run |

## Workflow Versioning

New workflow steps are introduced safely using `Workflow.getVersion()` — already-running workflows replay the old path, new workflows execute the new path:

```java
int version = Workflow.getVersion("FraudCheckStep", Workflow.DEFAULT_VERSION, 1);
if (version >= 1) { /* V1: fraud check + human approval */ }
else               { /* V0: skip — legacy workflows */   }
```

This enables zero-downtime deployments without breaking in-flight workflows.

## Load Testing

The `load-testing` module uses Gatling with three Maven profiles for parallel load:

```bash
mvn -pl load-testing gatling:test -Psmoke     # ~25 workflows, quick sanity check
mvn -pl load-testing gatling:test              # ~350 workflows, 50+ concurrent
mvn -pl load-testing gatling:test -Pstress     # ~1,250 workflows, 150+ concurrent
```

Three injection phases per run: **ramp-up** → **sustained constant rate** → **spike burst**.

Reports: `open load-testing/target/gatling/orderprocessingsimulation-*/index.html`

## Teardown

```bash
cd temporal-local && docker compose down
```
