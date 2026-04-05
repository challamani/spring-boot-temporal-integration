# Spring Boot + Temporal Demo

This project is a **demo application** to explore core Temporal features with a saga-style order flow across multiple Spring Boot services.

## What this demo shows

- Orchestrating a multi-step business process with a Temporal workflow.
- Distributed task routing with dedicated Temporal task queues per service.
- Saga compensation behavior on failures (rollback in reverse order).
- Async workflow start from REST (`POST /orders`) and result retrieval (`GET /orders/{workflowId}`).
- **Human-in-the-Loop** – workflow pauses for manual approval via Temporal Signals & Queries.
- **Workflow Versioning** – safe evolution of running workflows using `Workflow.getVersion()`.

## Project modules

- `common`: shared workflow/activity contracts, DTOs, and task queue constants.
- `order-service` (port `8080`): starts workflows and hosts the `OrderWorkflow` implementation.
- `payment-service` (port `8081`): payment activity worker.
- `inventory-service` (port `8082`): inventory reserve/cancel activity worker.
- `logistics-service` (port `8083`): shipment creation activity worker.
- `load-testing`: Gatling load tests for capacity/throughput testing of the workflow pipeline.
- `temporal-local/docker-compose.yml`: local Temporal + Postgres + Temporal UI setup.

## Prerequisites

- Java `21`
- Maven `3.9+`
- Docker Desktop (or Docker Engine) with Compose support
- Open ports: `7233` (Temporal gRPC), `8088` (Temporal UI), `8080-8083` (services)

## Run locally

### 1) Start Temporal locally (Docker Compose)

```bash
cd temporal-local
docker compose up -d
```

Optional check:

```bash
docker compose ps
```

Temporal endpoints:

- gRPC: `127.0.0.1:7233`
- UI: `http://localhost:8088`

### 2) Bootstrap Maven (run once after cloning)

The parent POM and `common` module must be installed into your local Maven repository before running any individual service. From the repository root:

```bash
# Step 1 — install the root/parent POM only (no child modules)
mvn -N install

# Step 2 — install the shared common module
mvn -pl common install -DskipTests
```

You only need to repeat this if you change `common` or the parent `pom.xml`.

### 3) Start each service (separate terminals)

From the repository root:

```bash
mvn -pl order-service spring-boot:run
```

```bash
mvn -pl payment-service spring-boot:run
```

```bash
mvn -pl inventory-service spring-boot:run
```

```bash
mvn -pl logistics-service spring-boot:run
```

### 4) Start an order workflow

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","customerId":"CUST-1","productId":"PROD-001","quantity":2,"amount":500.99,"shippingAddress":"123 Main St"}'
```

The response returns workflow identifiers immediately (async start), for example:

```json
{"workflowId":"order-ORD-001","runId":"...","status":"STARTED"}
```

### 5) Fetch workflow result

```bash
curl http://localhost:8080/orders/ORD-001
```

## Explore failure and compensation behavior

Try these payload tweaks to observe saga rollback paths:

- Set `amount` to `0` or negative to force payment failure.
- Set `shippingAddress` to blank to force shipment failure.

In those cases, `OrderWorkflowImpl` triggers compensations (for example refund payment and/or cancel inventory reservation).

---

## Human-in-the-Loop (Fraud Review)

The workflow now includes an automated **fraud check** step after inventory reservation. Depending on the order amount, the fraud check returns one of three decisions:

| Order Amount       | Fraud Decision   | Behavior                                           |
|--------------------|------------------|----------------------------------------------------|
| ≤ $1,000           | `APPROVED`       | Auto-approved, proceeds to payment immediately     |
| $1,001 – $5,000    | `NEEDS_REVIEW`   | **Workflow pauses** – waits for human signal        |
| > $5,000           | `REJECTED`       | Auto-rejected, saga compensations run              |

### How it works

1. **Workflow pauses** – When fraud check returns `NEEDS_REVIEW`, the workflow calls `Workflow.await(Duration.ofHours(24), () -> approvalDecision != null)`. This is a **durable sleep** — the workflow survives server restarts and worker downtime.

2. **Human sends a Signal** – A reviewer calls `POST /orders/{workflowId}/approve` with an approval decision. Temporal delivers the signal to the paused workflow, which resumes execution.

3. **Timeout protection** – If no signal arrives within 24 hours, the order is auto-rejected and saga compensations run.

4. **Query status** – At any time, `GET /orders/{workflowId}/approval-status` returns the current state via a Temporal Query.

### Try the human-in-the-loop flow

**Step 1 — Place a high-value order (triggers NEEDS_REVIEW):**

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-REVIEW-001","customerId":"CUST-1","productId":"PROD-001","quantity":2,"amount":2500.00,"shippingAddress":"123 Main St"}'
```

**Step 2 — Check the approval status (should be WAITING_FOR_APPROVAL):**

```bash
curl http://localhost:8080/orders/order-ORD-REVIEW-001/approval-status
```

```json
{"workflowId":"order-ORD-REVIEW-001","approvalStatus":"WAITING_FOR_APPROVAL"}
```

**Step 3a — Approve the order:**

```bash
curl -X POST http://localhost:8080/orders/order-ORD-REVIEW-001/approve \
  -H "Content-Type: application/json" \
  -d '{"approved": true, "reviewerNote": "Verified customer identity"}'
```

**Step 3b — Or reject the order:**

```bash
curl -X POST http://localhost:8080/orders/order-ORD-REVIEW-001/approve \
  -H "Content-Type: application/json" \
  -d '{"approved": false, "reviewerNote": "Suspected fraudulent activity"}'
```

**Step 4 — Fetch the final result:**

```bash
curl http://localhost:8080/orders/order-ORD-REVIEW-001
```

### Try automatic approval (low-value order):

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-AUTO-001","customerId":"CUST-1","productId":"PROD-001","quantity":1,"amount":99.99,"shippingAddress":"123 Main St"}'
```

This order passes fraud check automatically — no human intervention needed.

### Try automatic rejection (very high-value order):

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-REJECT-001","customerId":"CUST-1","productId":"PROD-001","quantity":1,"amount":9999.99,"shippingAddress":"123 Main St"}'
```

This order is auto-rejected by the fraud engine and inventory reservation is compensated.

---

## Workflow Versioning with `Workflow.getVersion()`

The fraud-check step was introduced using Temporal's **workflow versioning** mechanism to ensure **zero-downtime deployment** without breaking already-running workflows.

### How it works

```java
    int version = Workflow.getVersion("FraudCheckStep", Workflow.DEFAULT_VERSION, 1);
    
    if (version >= 1) {
        // V1: new workflows execute fraud check + human approval
    } else {
        // V0: already-running workflows skip this block entirely
    }
```

**Key concepts:**

| Concept | Description |
|---------|-------------|
| `changeId` | A unique string (`"FraudCheckStep"`) that identifies this specific change |
| `minSupported` | `Workflow.DEFAULT_VERSION` – old workflows that have never seen this version marker |
| `maxSupported` | `1` – the newest version of this code path |

**What happens during deployment:**

1. **Already-running V0 workflows** — Temporal replays their history and finds no version marker recorded. `getVersion()` returns `DEFAULT_VERSION` (-1), so the fraud-check block is skipped. These workflows complete exactly as they did before.

2. **New V1 workflows** — Temporal records version `1` in the workflow history. `getVersion()` returns `1`, so the fraud-check + human-approval block executes.

3. **Future V2 changes** — When you need another change, add a new `Workflow.getVersion("AnotherChange", ...)` call. Version markers compose — each workflow execution records its own set of version decisions.

### Adding future versions (convention)

```java
    // Future example: adding a loyalty-points step
    int loyaltyVersion = Workflow.getVersion("LoyaltyPointsStep", Workflow.DEFAULT_VERSION, 1);
    if (loyaltyVersion >= 1) {
        // award loyalty points after successful payment
    }
```

Each `getVersion()` call is independent. You can have multiple versioned blocks in the same workflow, and they don't interfere with each other.

---

## Workflow sequence diagram (V1)

```
Client            OrderWorkflow         Inventory   FraudCheck   [Human]     Payment    Shipping
  │                    │                    │           │            │           │          │
  │── POST /orders ──▶ │                    │           │            │           │          │
  │                    │── reserveInventory ▶│          │            │           │          │
  │                    │◀── reserved ───────│           │            │           │          │
  │                    │                    │           │            │           │          │
  │                    │── checkFraud ─────────────────▶│            │           │          │
  │                    │◀── NEEDS_REVIEW ──────────────│             │           │          │
  │                    │                    │           │            │           │          │
  │                    │══ Workflow.await() (paused, durable) ═════▶ │           │          │
  │                    │                    │           │            │           │          │
  │── POST /approve ──▶│◀── signal ───────────────────────────────── │           │          │
  │                    │                    │           │            │           │          │
  │                    │── processPayment ──────────────────────────────────────▶│          │
  │                    │◀── paid ───────────────────────────────────────────────│           │
  │                    │── createShipment ──────────────────────────────────────────────── ▶│
  │                    │◀── shipped ──────────────────────────────────────────────────────  │
  │                    │                    │           │            │           │          │
  │◀── SUCCESS ───────│                    │           │            │           │           │
```

## Stop local Temporal stack

```bash
cd temporal-local
docker compose down
```

---

## Load Testing with Gatling

The `load-testing` module contains a Gatling simulation that fires bulk orders through the
**fully-automated** happy path to stress-test the Temporal cluster and all four microservices.

Every order uses `amount = 100` (below the $1,000 fraud-review threshold), so the
entire saga runs automatically with **no human-in-the-loop signals required**:

```
Inventory → Fraud Check (auto-APPROVED) → Payment → Shipment → SUCCESS
```

### What the simulation does

| Step | HTTP Request | Validates |
|------|-------------|-----------|
| 1 | `POST /orders` — start workflow (unique UUID orderId) | HTTP 202, `status: STARTED`, captures `workflowId` |
| 2 | _pause 5 seconds_ — let the workflow complete | — |
| 3 | `GET /orders/{workflowId}` — fetch final result | HTTP 200, `status: SUCCESS`, `orderId` matches |

### Default load profile

- **30 virtual users** injected at a constant rate over **60 seconds** (0.5 req/sec)
- Built-in assertions: ≥ 95% success rate, p99 latency for POST < 5 seconds

### Prerequisites

1. **Temporal** must be running:
    ```bash
    cd temporal-local && docker compose up -d
    ```

2. **All four services** must be running (in separate terminals):
    ```bash
    mvn -pl order-service spring-boot:run
    mvn -pl payment-service spring-boot:run
    mvn -pl inventory-service spring-boot:run
    mvn -pl logistics-service spring-boot:run
    ```

### Run the load test

```bash
mvn -pl load-testing gatling:test
```

### Override defaults via system properties

```bash
mvn -pl load-testing gatling:test \
    -DbaseUrl=http://localhost:8080 \
    -DtotalRequests=1 \
    -DdurationSecs=10 \
    -DpollPauseSecs=10
```

| Property | Default | Description |
|----------|---------|-------------|
| `baseUrl` | `http://localhost:8080` | Order-service base URL |
| `totalRequests` | `30` | Total number of orders to fire |
| `durationSecs` | `60` | Time window to spread requests over (seconds) |
| `pollPauseSecs` | `5` | Seconds to wait before polling for the result |

### View the HTML report

After the run completes, open the Gatling report:

```bash
open load-testing/target/gatling/orderprocessingsimulation-*/index.html
```

The report includes response time distributions, throughput graphs, and assertion results.

