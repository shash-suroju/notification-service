package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.dtos.TenantWithWeight;
import com.assignment.notificationservice.dtos.TenantWork;
import com.assignment.notificationservice.services.FairTenantSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FairTenantSelectorTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final FairTenantSelector selector = new FairTenantSelector();

    @Test
    void emptyList_returnsEmpty() {
        assertThat(selector.selectForTick(List.of(), 20)).isEmpty();
    }

    @Test
    void singleTenant_getsFullQuantum() {
        List<TenantWork> work = selector.selectForTick(List.of(new TenantWithWeight(A, 1)), 20);

        assertThat(work).containsExactly(new TenantWork(A, 20, 1));
    }

    @Test
    void weightMultipliesQuantum() {
        List<TenantWork> work = selector.selectForTick(
                List.of(new TenantWithWeight(A, 3), new TenantWithWeight(B, 1)), 20);

        assertThat(work).containsExactly(new TenantWork(A, 60, 3), new TenantWork(B, 20, 1));
    }

    @Test
    void everyTenantIsServedEveryTick() {
        List<TenantWithWeight> tenants = List.of(
                new TenantWithWeight(A, 1), new TenantWithWeight(B, 1), new TenantWithWeight(C, 1));
        for (int tick = 0; tick < 10; tick++) {
            assertThat(selector.selectForTick(tenants, 5)).extracting(TenantWork::tenantId)
                    .containsExactlyInAnyOrder(A, B, C);
        }
    }

    @Test
    void cursorRotates() {
        List<TenantWithWeight> tenants = List.of(
                new TenantWithWeight(A, 1), new TenantWithWeight(B, 1), new TenantWithWeight(C, 1));

        assertThat(firstOf(tenants)).isEqualTo(A);
        assertThat(firstOf(tenants)).isEqualTo(B);
        assertThat(firstOf(tenants)).isEqualTo(C);
        assertThat(firstOf(tenants)).isEqualTo(A);
    }

    @Test
    void rotationKeepsRelativeOrder() {
        List<TenantWithWeight> tenants = List.of(
                new TenantWithWeight(A, 1), new TenantWithWeight(B, 1), new TenantWithWeight(C, 1));
        selector.selectForTick(tenants, 5);   // tick 1 starts at A

        assertThat(selector.selectForTick(tenants, 5)).extracting(TenantWork::tenantId).containsExactly(B, C, A);
    }

    @Test
    void skipsEmptyTenantGracefully() {
        // Tick 1-3 with three tenants moves the cursor to 3; then the list shrinks to two.
        List<TenantWithWeight> three = List.of(
                new TenantWithWeight(A, 1), new TenantWithWeight(B, 1), new TenantWithWeight(C, 1));
        selector.selectForTick(three, 5);
        selector.selectForTick(three, 5);
        selector.selectForTick(three, 5);

        List<TenantWithWeight> two = List.of(new TenantWithWeight(A, 1), new TenantWithWeight(B, 1));
        List<TenantWork> work = selector.selectForTick(two, 5);

        assertThat(work).extracting(TenantWork::tenantId).containsExactlyInAnyOrder(A, B);
        assertThat(work).hasSize(2);
    }

    @Test
    void resetCursor_startsFromTheFirstTenantAgain() {
        List<TenantWithWeight> tenants = List.of(new TenantWithWeight(A, 1), new TenantWithWeight(B, 1));
        selector.selectForTick(tenants, 5);
        selector.resetCursor();

        assertThat(firstOf(tenants)).isEqualTo(A);
    }

    private UUID firstOf(List<TenantWithWeight> tenants) {
        return selector.selectForTick(tenants, 5).get(0).tenantId();
    }
}
