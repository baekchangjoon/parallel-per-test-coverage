package io.pjacoco.agent.probe;

import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.util.Collection;

/**
 * Attributes a no-context probe drop to the currently-active in-process test store(s).
 * size 1 -> exact (that store); size >1 -> conservative (every concurrent store); size 0 -> unattributed
 * (metric only, no per-test sidecar). Invoked only on the already-broken store==null path, so its cost
 * is never paid by a correct collection.
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
        boolean conservative = n > 1;
        for (TestStore s : active) {
            s.recordDrop();
            if (conservative) s.markConservative();
        }
    }
}
