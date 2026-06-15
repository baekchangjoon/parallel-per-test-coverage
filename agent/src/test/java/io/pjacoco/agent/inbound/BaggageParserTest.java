package io.pjacoco.agent.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BaggageParserTest {
    @Test void extractsTestId() { assertEquals("T1", BaggageParser.testId("test.id=T1")); }
    @Test void extractsAmongOthers() { assertEquals("T1", BaggageParser.testId("userId=u9,test.id=T1,region=eu")); }
    @Test void stripsProperties() { assertEquals("T1", BaggageParser.testId("test.id=T1;prop=meta")); }
    @Test void urlDecodesValue() { assertEquals("a b", BaggageParser.testId("test.id=a%20b")); }

    @Test
    void nullWhenAbsentOrNull() {
        assertNull(BaggageParser.testId("foo=bar"));
        assertNull(BaggageParser.testId(null));
        assertNull(BaggageParser.testId(""));
    }
}
