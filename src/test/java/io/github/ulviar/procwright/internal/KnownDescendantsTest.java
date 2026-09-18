/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

final class KnownDescendantsTest {

    @Test
    void snapshotDefensivelyCopiesItsOrderedIdentityMap() {
        List<ProcessHandle> handles = handles(3);
        LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> source = indexed(handles);

        KnownDescendants snapshot = KnownDescendants.copyOf(source, false, true);
        source.clear();

        assertEquals(handles, List.copyOf(snapshot.handles()));
        assertEquals(handles, List.copyOf(snapshot.handlesByIdentity().values()));
        assertTrue(snapshot.discoveryUnavailable());
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.handles().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.handlesByIdentity().clear());
    }

    @Test
    void oversizedIdentityMapIsRejected() {
        int limit = ProcessTreeScanner.shared().descendantLimit();

        LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> oversized = indexed(handles(limit + 1));

        assertThrows(IllegalArgumentException.class, () -> KnownDescendants.copyOf(oversized, false, false));
    }

    @Test
    void nullIdentityOrHandleIsRejected() {
        MutableProcessHandle handle = new MutableProcessHandle(100_000L);
        ProcessTreeScanner.HandleIdentity identity = ProcessTreeScanner.identity(handle);
        LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> nullIdentity = new LinkedHashMap<>();
        nullIdentity.put(null, handle);

        assertThrows(NullPointerException.class, () -> KnownDescendants.copyOf(nullIdentity, false, false));
        LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> nullHandle = new LinkedHashMap<>();
        nullHandle.put(identity, null);
        assertThrows(NullPointerException.class, () -> KnownDescendants.copyOf(nullHandle, false, false));
    }

    private static LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> indexed(
            Iterable<? extends ProcessHandle> handles) {
        LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> indexed = new LinkedHashMap<>();
        handles.forEach(handle -> indexed.put(ProcessTreeScanner.identity(handle), handle));
        return indexed;
    }

    private static List<ProcessHandle> handles(int count) {
        List<ProcessHandle> handles = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            handles.add(new MutableProcessHandle(100_000L + index));
        }
        return handles;
    }
}
