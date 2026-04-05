package com.example.temporal.loadtest;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Gatling load test — fires orders through the fully-automated happy path.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li>{@code POST /orders} — starts a workflow with a unique UUID-based orderId</li>
 *   <li>Pauses to let workflows begin processing</li>
 *   <li>Polls {@code GET /orders/{workflowId}} in a retry loop until the workflow completes
 *       or max attempts are exhausted</li>
 * </ol>
 *
 * <p>Every order uses {@code amount = 100}, which is safely below the $1,000
 * fraud-review threshold, so the entire saga runs fully automated:
 * <pre>
 *   Inventory → Fraud Check (auto-APPROVED) → Payment → Shipment → SUCCESS
 * </pre>
 *
 * <h3>Default load profile</h3>
 * 30 requests spread evenly over 60 seconds (0.5 req/sec).
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Temporal must be running  ({@code cd temporal-local && docker compose up -d})</li>
 *   <li>All four services must be running (order, payment, inventory, logistics)</li>
 * </ul>
 *
 * <h3>Run</h3>
 * <pre>
 *   mvn -pl load-testing gatling:test
 * </pre>
 *
 * <h3>Override defaults</h3>
 * <pre>{@code
 *   mvn -pl load-testing gatling:test \
 *       -DbaseUrl=http://localhost:8080 \
 *       -DtotalRequests=50 \
 *       -DdurationSecs=120 \
 *       -DpollPauseSecs=10
 * }</pre>
 *
 * <h3>Reports</h3>
 * HTML report is written to {@code load-testing/target/gatling/<run-folder>/index.html}.
 */
public class OrderProcessingSimulation extends Simulation {

    // ── Tunables (overridable via -D system properties) ─────────────────────

    private static final String BASE_URL        = System.getProperty("baseUrl",       "http://localhost:8080");
    private static final int    TOTAL_REQUESTS  = Integer.getInteger("totalRequests",  30);
    private static final int    DURATION_SECS   = Integer.getInteger("durationSecs",   60);
    private static final int    POLL_PAUSE_SECS = Integer.getInteger("pollPauseSecs",  10);
    private static final int    MAX_POLLS       = Integer.getInteger("maxPolls",       6);
    private static final int    POLL_INTERVAL   = Integer.getInteger("pollInterval",   5);

    // ── HTTP protocol shared by all requests ────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    // ── Feeder: infinite stream of unique order payloads ────────────────────

    /**
     * Each call yields a fresh map with a UUID-based orderId so that every
     * virtual user gets its own unique Temporal workflow.
     */
    private static Iterator<Map<String, Object>> orderFeeder() {
        return Stream.generate((Supplier<Map<String, Object>>) () -> {
            String uid = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            return Map.of(
                    "orderId",         "LOAD-" + uid,
                    "customerId",      "CUST-LOAD-" + uid,
                    "productId",       "PROD-001",
                    "quantity",        1,
                    "amount",          100,          // below $1,000 → fraud auto-approve
                    "shippingAddress", "123 Load Test Ave"
            );
        }).iterator();
    }

    // ── Scenario ────────────────────────────────────────────────────────────

    private final ScenarioBuilder placeAndVerifyOrder = scenario("Place & Verify Order")
            .feed(orderFeeder())

            // ── 1. Start the order workflow (async — returns 202 immediately) ──
            .exec(
                    http("POST /orders")
                            .post("/orders")
                            .body(StringBody(
                                    """
                                    {
                                      "orderId":         "#{orderId}",
                                      "customerId":      "#{customerId}",
                                      "productId":       "#{productId}",
                                      "quantity":        #{quantity},
                                      "amount":          #{amount},
                                      "shippingAddress": "#{shippingAddress}"
                                    }
                                    """
                            ))
                            .check(status().is(202))
                            .check(jsonPath("$.workflowId").saveAs("workflowId"))
                            .check(jsonPath("$.status").is("STARTED"))
            )

            // ── 2. Initial pause — give workflows a head start ─────────────────
            .pause(Duration.ofSeconds(POLL_PAUSE_SECS))

            // ── 3. Poll for the result with retries ────────────────────────────
            //  - The GET endpoint returns 200 when the workflow is complete,
            //    or 202 if it's still running. We retry on 202 up to MAX_POLLS times.
            .exec(session -> session.set("pollCount", 0).set("orderDone", false))

            .asLongAs(session -> !session.getBoolean("orderDone")
                                 && session.getInt("pollCount") < MAX_POLLS)
            .on(
                    exec(
                            http("GET /orders/{workflowId}")
                                    .get("/orders/#{workflowId}?timeout=10")
                                    .check(
                                            status().saveAs("pollStatus")
                                    )
                                    // Only extract result fields when we get a 200
                                    .checkIf(
                                            (response, session) ->
                                                    session.getString("pollStatus").equals("200")
                                    ).then(
                                            jsonPath("$.status").is("SUCCESS"),
                                            jsonPath("$.orderId").is("#{orderId}")
                                    )
                    )
                    .exec(session -> {
                        String pollStatus = session.getString("pollStatus");
                        int count = session.getInt("pollCount") + 1;
                        boolean done = "200".equals(pollStatus);
                        return session.set("pollCount", count).set("orderDone", done);
                    })
                    // Pause between retries (only if not done yet)
                    .doIf(session -> !session.getBoolean("orderDone"))
                    .then(pause(Duration.ofSeconds(POLL_INTERVAL)))
            );

    // ── Load profile & assertions ───────────────────────────────────────────

    {
        setUp(
                placeAndVerifyOrder.injectOpen(
                        // e.g. 30 users / 60 s = 0.5 users-per-second, constant
                        constantUsersPerSec((double) TOTAL_REQUESTS / DURATION_SECS)
                                .during(Duration.ofSeconds(DURATION_SECS))
                )
        )
        .protocols(httpProtocol)
        .assertions(
                // ≥ 95 % of all HTTP requests must succeed
                global().successfulRequests().percent().gt(95.0),
                // p99 latency for the POST must stay under 5 s
                details("POST /orders").responseTime().percentile(99.0).lt(5000)
        );
    }
}

