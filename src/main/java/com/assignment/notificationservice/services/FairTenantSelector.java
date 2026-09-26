package com.assignment.notificationservice.services;

import com.assignment.notificationservice.dtos.TenantWithWeight;
import com.assignment.notificationservice.dtos.TenantWork;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Weighted round-robin. Every tenant with due work is served every tick, with a quantum of
 * {@code baseQuantum × weight}, so a tenant with 100k queued cannot starve one with 10: both
 * move forward each tick, the small one simply finishes first.
 *
 * <p>The starting tenant rotates each tick, so when a shared resource (channel bucket, pool
 * capacity) runs out mid-tick it is not always the same tenant left without. Unused quantum
 * is not banked — banking would create bursts that defeat the rate limits.
 */
@Component
public class FairTenantSelector {

    private final AtomicInteger cursorIndex = new AtomicInteger(0);

    /**
     * @param tenantsWithDueWork must be in a stable order across ticks (the claimer sorts by
     *                           tenant ID) for the rotation to be fair
     */
    public List<TenantWork> selectForTick(List<TenantWithWeight> tenantsWithDueWork, int baseQuantum) {
        if (tenantsWithDueWork.isEmpty()) {
            return List.of();
        }

        int n = tenantsWithDueWork.size();
        int start = Math.floorMod(cursorIndex.getAndIncrement(), n);

        List<TenantWork> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TenantWithWeight tenant = tenantsWithDueWork.get((start + i) % n);
            result.add(new TenantWork(tenant.tenantId(), baseQuantum * tenant.weight(), tenant.weight()));
        }
        return result;
    }

    public void resetCursor() {
        cursorIndex.set(0);
    }
}
