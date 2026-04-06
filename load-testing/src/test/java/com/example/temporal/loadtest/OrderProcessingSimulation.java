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
 * Gatling load test — measures Temporal workflow throughput under parallel load.
 *
 * <h3>Three load phases run back-to-back</h3>
 * <ol>
 *   <li><b>Ramp-up</b>  — linearly add users from 0 → {@code rampUsers} over {@code rampSecs}</li>
 *   <li><b>Sustained</b> — inject at a constant {@code steadyRate} users/sec for {@code steadySecs}</li>
 *   <li><b>Spike</b>     — fire {@code spikeUsers} all at once (true parallel burst)</li>
 * </ol>
 *
 * <p>Every order uses {@code amount = 100} (below the $1,000 fraud-review threshold),
 * so the full saga runs fully automated — no human signal required.
 *
 * <h3>Profiles</h3>
 * <pre>
 *   # Smoke (quick sanity check — ~25 workflows)
 *   mvn -pl load-testing gatling:test \
 *       -DrampUsers=5 -DrampSecs=5 -DsteadyRate=2 -DsteadySecs=5 -DspikeUsers=5
 *
 *   # Default (moderate — ~350 workflows, up to 50+ concurrent)
 *   mvn -pl load-testing gatling:test
 *
 *   # Stress (heavy — ~1,250 workflows, up to 100+ concurrent)
 *   mvn -pl load-testing gatling:test \
 *       -DrampUsers=50 -DrampSecs=10 -DsteadyRate=20 -DsteadySecs=60 -DspikeUsers=50
 * </pre>
 *
 * <h3>Prerequisites</h3>
 * Temporal + all four services must be running.
 */
public class OrderProcessingSimulation extends Simulation {

    // ── Tunables (all overridable via -D) ───────────────────────────────────

    private static final String BASE_URL    = System.getProperty("baseUrl", "http://localhost:8080");

    // Phase 1 — Ramp-up
    private static final int RAMP_USERS     = Integer.getInteger("rampUsers",  30);
    private static final int RAMP_SECS      = Integer.getInteger("rampSecs",   15);

    // Phase 2 — Sustained constant rate
    private static final int STEADY_RATE    = Integer.getInteger("steadyRate", 10);   // users/sec
    private static final int STEADY_SECS    = Integer.getInteger("steadySecs", 30);

    // Phase 3 — Spike burst
    private static final int SPIKE_USERS    = Integer.getInteger("spikeUsers", 20);

    // Server-side result wait
    private static final int GET_TIMEOUT    = Integer.getInteger("getTimeout", 30);

    // ── HTTP protocol ───────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    // ── Feeder — infinite unique order payloads ─────────────────────────────

    private static Iterator<Map<String, Object>> orderFeeder() {
        return Stream.generate((Supplier<Map<String, Object>>) () -> {
            String uid = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            return Map.of(
                    "orderId",         "LOAD-" + uid,
                    "customerId",      "CUST-LOAD-" + uid,
                    "productId",       "PROD-001",
                    "quantity",        1,
                    "amount",          100,
                    "shippingAddress", "123 Load Test Ave"
            );
        }).iterator();
    }

    // ── Scenario ────────────────────────────────────────────────────────────

    private final ScenarioBuilder placeAndVerifyOrder = scenario("Place & Verify Order")
            .feed(orderFeeder())

            // 1. Start the workflow (returns 202 immediately)
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

            // 2. Random short pause (2–5 s) — keeps users overlapping
            .pause(Duration.ofSeconds(2), Duration.ofSeconds(5))

            // 3. Verify workflow completed (server blocks up to getTimeout seconds)
            .exec(
                    http("GET /orders/{workflowId}")
                            .get("/orders/#{workflowId}?timeout=" + GET_TIMEOUT)
                            .check(status().is(200))
                            .check(jsonPath("$.status").is("SUCCESS"))
            );

    // ── Load profile & assertions ───────────────────────────────────────────

    {
        setUp(
                placeAndVerifyOrder.injectOpen(

                        // Phase 1 — Ramp-up: linearly add users (warm-up)
                        rampUsers(RAMP_USERS).during(Duration.ofSeconds(RAMP_SECS)),

                        // Phase 2 — Sustained: constant parallel injection rate
                        constantUsersPerSec(STEADY_RATE).during(Duration.ofSeconds(STEADY_SECS)),

                        // Phase 3 — Spike: all users start at the same instant
                        atOnceUsers(SPIKE_USERS)
                )
        )
        .protocols(httpProtocol)
        .assertions(
                global().successfulRequests().percent().gt(95.0),
                details("POST /orders").responseTime().percentile(99.0).lt(5000)
        );
    }
}
