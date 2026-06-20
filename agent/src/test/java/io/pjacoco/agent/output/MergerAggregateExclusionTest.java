package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** REQ-U02 downstream guard: the report-time merger must skip the whole-run aggregate dump under the
 *  {@code %p}-namespaced convention ({@code aggregate-<pid>.exec}), not just the literal default name —
 *  otherwise a namespaced dump is mis-merged as a per-test {@code .exec}. */
class MergerAggregateExclusionTest {

    @Test
    void defaultAggregateNameIsExcluded() {
        assertTrue(TraceCoverageMerger.isAggregateDump("aggregate.exec"));
    }

    @Test
    void pidNamespacedAggregateIsExcluded() {
        assertTrue(TraceCoverageMerger.isAggregateDump("aggregate-12345.exec"));
    }

    @Test
    void perTestExecIsNotExcluded() {
        assertFalse(TraceCoverageMerger.isAggregateDump("com.foo.BarTest#baz.exec"));
        assertFalse(TraceCoverageMerger.isAggregateDump("aggregatorTest#run.exec"),
                "a real test id that merely starts with 'aggregat' must not be excluded");
    }
}
