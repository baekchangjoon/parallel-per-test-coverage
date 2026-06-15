package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void writesFlatObjectWithEscapingAndNullOmission() {
        String s = new Json()
                .put("testId", "T\"1")
                .put("classCount", 42)
                .put("shardId", (String) null)        // null omitted
                .toString();
        assertEquals("{\"testId\":\"T\\\"1\",\"classCount\":42}", s);
    }

    @Test
    void emptyObject() {
        assertEquals("{}", new Json().toString());
    }
}
