package com.example.temporal.inventory.activity;

import com.example.temporal.common.activity.InventoryActivity;
import com.example.temporal.common.model.InventoryRequest;
import com.example.temporal.common.model.InventoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory activity implementation.
 *
 * <p>Uses an in-memory stock map for demonstration purposes.
 * Replace with JPA / Redis / external inventory API in production.
 */
@Service
public class InventoryActivityImpl implements InventoryActivity {

    private static final Logger log = LoggerFactory.getLogger(InventoryActivityImpl.class);

    /**
     * Simulated stock levels: productId → available units.
     */
    private final Map<String, Integer> stockLevels = new ConcurrentHashMap<>(Map.of(
            "PROD-001", 100,
            "PROD-002", 50,
            "PROD-003", 200
    ));

    /** Active reservations: reservationId → request (for rollback context) */
    private final Map<String, InventoryRequest> activeReservations = new ConcurrentHashMap<>();

    @Override
    public InventoryResult reserveInventory(InventoryRequest request) {
        log.info("[INVENTORY] Reserve request: orderId={}, productId={}, qty={}",
                request.orderId(), request.productId(), request.quantity());

        int available = stockLevels.getOrDefault(request.productId(), 0);

        if (available < request.quantity()) {
            log.warn("[INVENTORY] Insufficient stock for productId={}: available={}, requested={}",
                    request.productId(), available, request.quantity());
            return new InventoryResult(null, false,
                    "Insufficient stock: available=" + available + ", requested=" + request.quantity());
        }

        stockLevels.merge(request.productId(), -request.quantity(), Integer::sum);
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        activeReservations.put(reservationId, request);

        log.info("[INVENTORY] Stock reserved: reservationId={}, remaining={}",
                reservationId, stockLevels.get(request.productId()));

        return new InventoryResult(reservationId, true, "Inventory reserved successfully");
    }

    @Override
    public void cancelReservation(InventoryRequest request) {
        log.info("[INVENTORY][COMPENSATION] Cancelling reservation for orderId={}, productId={}",
                request.orderId(), request.productId());

        stockLevels.merge(request.productId(), request.quantity(), Integer::sum);

        log.info("[INVENTORY][COMPENSATION] Stock restored for productId={}. New level={}",
                request.productId(), stockLevels.get(request.productId()));
    }
}
