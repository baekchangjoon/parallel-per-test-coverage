package io.pjacoco.agent.probe;

import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.util.Collection;

/**
 * Attributes a no-context probe drop to the currently-active in-process test store(s).
 * <ul>
 *   <li>exactly 1 active -> exact attribution (that store's droppedProbes++);</li>
 *   <li>≥2 active -> <b>ambiguous</b>: counted globally ({@code Metrics.ambiguousDrops}) only, NOT
 *       per-test — a context-less drop cannot be blamed on one of several concurrently-running tests
 *       without trace context, and doing so over-flagged every parallel test (CLS-REQ-005, revised P2-4);</li>
 *   <li>0 active -> unattributed (global {@code Metrics.unattributedDrops}).</li>
 * </ul>
 * Invoked only on the already-broken {@code store==null} path, so its cost is never paid by a correct
 * collection. To attribute background-thread coverage to its test, give the SUT trace context
 * (OTel/Brave + {@code traceKeyAutoCreate=true}) or scope {@code includes} to your production packages.
 */
public final class DropAttributor {
    private final TestStoreRegistry registry;
    private final Metrics metrics;

    public DropAttributor(TestStoreRegistry registry, Metrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    public void attribute() {
        Collection<TestStore> active = registry.activeSnapshot();
        int n = active.size();
        if (n == 0) {
            metrics.unattributedDrops.incrementAndGet();
            return;
        }
        if (n > 1) {
            metrics.ambiguousDrops.incrementAndGet();
            return;
        }
        // Exactly one active test: unambiguous, attribute exactly.
        for (TestStore s : active) {
            s.recordDrop();
        }
    }
}
