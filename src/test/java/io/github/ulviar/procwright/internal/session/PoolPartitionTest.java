/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PoolPartitionTest {

    @Test
    void membershipIsTheOnlyWorkerStateAcrossTheFullLifecycle() {
        PoolPartition<Object> partition = new PoolPartition<>(1);
        Object worker = new Object();

        partition.addStarting(worker);
        assertCounts(partition, 1, 0, 0, 1, 0);
        assertEquals(PoolPartition.State.STARTING, partition.stateOf(worker));

        partition.startingToLeased(worker);
        assertCounts(partition, 1, 0, 1, 0, 0);

        partition.leasedToIdle(worker);
        assertCounts(partition, 1, 1, 0, 0, 0);

        assertSame(worker, partition.leaseIdle());
        assertCounts(partition, 1, 0, 1, 0, 0);
    }

    @Test
    void leasedWorkerCanRetireWithoutEnteringIdle() {
        PoolPartition<Object> partition = new PoolPartition<>(1);
        Object worker = new Object();
        partition.addStarting(worker);
        partition.startingToLeased(worker);

        partition.leasedToRetiring(worker);
        assertCounts(partition, 1, 0, 0, 0, 1);

        partition.removeRetiring(worker);
        assertCounts(partition, 0, 0, 0, 0, 0);
        assertNull(partition.stateOf(worker));
    }

    @Test
    void idleWorkerCanRetireAndKeepsCapacityUntilRetirementCompletes() {
        PoolPartition<Object> partition = new PoolPartition<>(1);
        Object worker = new Object();
        partition.addStarting(worker);
        partition.startingToLeased(worker);
        partition.leasedToIdle(worker);

        partition.idleToRetiring(worker);
        assertThrows(IllegalStateException.class, () -> partition.addStarting(new Object()));

        partition.removeRetiring(worker);
        partition.addStarting(new Object());
    }

    @Test
    void failedStartupReleasesItsSlot() {
        PoolPartition<Object> partition = new PoolPartition<>(1);
        Object reservation = new Object();
        partition.addStarting(reservation);

        partition.removeStarting(reservation);

        assertCounts(partition, 0, 0, 0, 0, 0);
    }

    @Test
    void idleSelectionIsFifo() {
        PoolPartition<Object> partition = new PoolPartition<>(2);
        Object first = new Object();
        Object second = new Object();
        partition.addStarting(first);
        partition.addStarting(second);
        partition.startingToLeased(first);
        partition.startingToLeased(second);
        partition.leasedToIdle(first);
        partition.leasedToIdle(second);

        assertSame(first, partition.leaseIdle());
        partition.leasedToRetiring(first);
        partition.removeRetiring(first);
        assertSame(second, partition.leaseIdle());
    }

    @Test
    void ownershipUsesIdentityEvenWhenWorkersAreEqual() {
        PoolPartition<EqualWorker> partition = new PoolPartition<>(2);
        EqualWorker first = new EqualWorker(1);
        EqualWorker second = new EqualWorker(1);

        partition.addStarting(first);
        partition.addStarting(second);

        assertCounts(partition, 2, 0, 0, 2, 0);
    }

    @Test
    void invalidAndRepeatedTransitionsAreRejectedLocally() {
        PoolPartition<Object> partition = new PoolPartition<>(1);
        Object worker = new Object();
        Object foreign = new Object();
        partition.addStarting(worker);

        assertThrows(IllegalStateException.class, () -> partition.addStarting(worker));
        assertThrows(IllegalStateException.class, () -> partition.startingToLeased(foreign));
        partition.startingToLeased(worker);
        assertThrows(IllegalStateException.class, () -> partition.startingToLeased(worker));
        assertThrows(IllegalStateException.class, () -> partition.leasedToIdle(foreign));
    }

    private static void assertCounts(
            PoolPartition<?> partition, int size, int idle, int leased, int starting, int retiring) {
        assertEquals(new PoolPartition.Counts(size, idle, leased, starting, retiring), partition.counts());
    }

    private record EqualWorker(int value) {}
}
