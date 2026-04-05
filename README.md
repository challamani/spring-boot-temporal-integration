# Spring Boot + Temporal Demo

This project is a **demo application** to explore core Temporal features with a saga-style order flow across multiple Spring Boot services.

## What this demo shows

- Orchestrating a multi-step business process with a Temporal workflow.
- Distributed task routing with dedicated Temporal task queues per service.
- Saga compensation behavior on failures (rollback in reverse order).
- Async workflow start from REST (`POST /orders`) and result retrieval (`GET /orders/{workflowId}`).

## Project modules

- `common`: shared workflow/activity contracts, DTOs, and task queue constants.
- `order-service` (port `8080`): starts workflows and hosts the `OrderWorkflow` implementation.
- `payment-service` (port `8081`): payment activity worker.
- `inventory-service` (port `8082`): inventory reserve/cancel activity worker.
- `logistics-service` (port `8083`): shipment creation activity worker.
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
  -d '{"orderId":"ORD-001","customerId":"CUST-1","productId":"PROD-001","quantity":2,"amount":99.99,"shippingAddress":"123 Main St"}'
```

The response returns workflow identifiers immediately (async start), for example:

```json
{"workflowId":"order-ORD-001","runId":"...","status":"STARTED"}
```

### 5) Fetch workflow result

```bash
curl http://localhost:8080/orders/order-ORD-001
```

## Explore failure and compensation behavior

Try these payload tweaks to observe saga rollback paths:

- Set `amount` to `0` or negative to force payment failure.
- Set `shippingAddress` to blank to force shipment failure.

In those cases, `OrderWorkflowImpl` triggers compensations (for example refund payment and/or cancel inventory reservation).

## Stop local Temporal stack

```bash
cd temporal-local
docker compose down
```
