package io.pjacoco.agent.inbound.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REQ-MM-004/005/007 단위 계약. 스텁 request는 Map&lt;String,String&gt; 기반 getHeader(String) 제공. */
class BaggageHeadersTest {

    @AfterEach void clear() {
        CoverageContext.clear();
        ServletAdvice.registry = null;
        ServletAdvice.metrics = null;
    }

    private TestStoreRegistry reg(Path dir, boolean lenient) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                lenient, 100, new java.util.function.LongSupplier() {
                    public long getAsLong() { return clock.get(); }
                });
    }

    private static String resolve(java.util.Map<String, String> headers) throws Exception {
        javax.servlet.http.HttpServletRequest req = org.mockito.Mockito.mock(javax.servlet.http.HttpServletRequest.class);
        for (java.util.Map.Entry<String, String> e : headers.entrySet())
            org.mockito.Mockito.when(req.getHeader(e.getKey())).thenReturn(e.getValue());
        java.lang.reflect.Method m = ServletAdvice.class.getDeclaredMethod("fallbackTestId", Object.class);
        m.setAccessible(true);
        return (String) m.invoke(null, req);
    }

    @Test void priority3Way() throws Exception {          // REQ-MM-004
        assertEquals("W", resolve(map("baggage","test.id=W","test.id","F","baggage-test.id","L")));
    }
    @Test void pairwiseAll() throws Exception {           // REQ-MM-004 pairwise 전수
        assertEquals("W", resolve(map("baggage","test.id=W","test.id","F")));
        assertEquals("F", resolve(map("test.id","F","baggage-test.id","L")));
        assertEquals("W", resolve(map("baggage","test.id=W","baggage-test.id","L")));
    }
    @Test void malformedBaggageFallsThrough() throws Exception {   // REQ-MM-004: 유효 값 추출 기준
        assertEquals("F", resolve(map("baggage","other=x","test.id","F")));
    }
    @Test void w3cParserOnlyForBaggageHeader() throws Exception {  // REQ-MM-003
        assertEquals("a=b", resolve(map("test.id"," a=b ")));      // trim + 값 전체
    }
    @Test void emptyValueFallsThrough() throws Exception {         // REQ-MM-005
        assertEquals("T3", resolve(map("test.id","  ","baggage-test.id","T3")));
    }
    @Test void allEmptyGoesMissingPath() throws Exception {        // REQ-MM-005
        assertNull(resolve(map("test.id","","baggage-test.id","  ")));
    }

    @Test void countersPartitionFallbackActivations(@TempDir Path dir) {   // REQ-MM-007
        Metrics metrics = new Metrics();
        ServletAdvice.registry = reg(dir, true);   // lenient: auto-create so activate() completes
        ServletAdvice.metrics = metrics;

        javax.servlet.http.HttpServletRequest w3c = org.mockito.Mockito.mock(javax.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(w3c.getHeader("baggage")).thenReturn("test.id=W");
        javax.servlet.http.HttpServletRequest field = org.mockito.Mockito.mock(javax.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(field.getHeader("test.id")).thenReturn("F");
        javax.servlet.http.HttpServletRequest legacy = org.mockito.Mockito.mock(javax.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(legacy.getHeader("baggage-test.id")).thenReturn("L");

        ServletAdvice.activate(w3c);
        ServletAdvice.deactivate();
        ServletAdvice.activate(field);
        ServletAdvice.deactivate();
        ServletAdvice.activate(legacy);
        ServletAdvice.deactivate();

        assertEquals(1L, metrics.testIdFromW3cBaggage.get());
        assertEquals(1L, metrics.testIdFromFieldHeader.get());
        assertEquals(1L, metrics.testIdFromLegacyFieldHeader.get());
        assertEquals(3L, metrics.fallbackActivations.get());
    }

    @Test void countersAppearInSummary() {                         // REQ-MM-007
        String s = new io.pjacoco.agent.observability.Metrics().summary();
        assertTrue(s.contains("testIdFromW3cBaggage=") && s.contains("testIdFromFieldHeader=")
                && s.contains("testIdFromLegacyFieldHeader="));
    }
    private static java.util.Map<String,String> map(String... kv) {
        java.util.Map<String,String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i+1]);
        return m;
    }
}
