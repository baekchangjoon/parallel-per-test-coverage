package io.pjacoco.agent.mapping;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestIdMappingRegistryTest {

    @Test
    void registeredLookupReturnsNormalizedTestId() {                 // REQ-011 + REQ-014
        TestIdMappingRegistry r = new TestIdMappingRegistry(100);
        r.register("4bf92f", "  com.x.T # m ");
        assertEquals("com.x.T#m", r.testIdFor("4bf92f"));
    }

    @Test
    void unregisteredLookupReturnsNull() {                           // REQ-012 contract
        assertNull(new TestIdMappingRegistry(100).testIdFor("nope"));
    }

    @Test
    void blankTestIdIsNotRegistered() {
        TestIdMappingRegistry r = new TestIdMappingRegistry(100);
        r.register("t", "   ");
        assertNull(r.testIdFor("t"));
    }

    @Test
    void boundedEvictionDropsEldest() {                             // REQ-011 bounded
        TestIdMappingRegistry r = new TestIdMappingRegistry(2);
        r.register("t1", "com.x.T#a");
        r.register("t2", "com.x.T#b");
        r.register("t3", "com.x.T#c");                              // evicts t1 (eldest)
        assertEquals(2, r.size());
        assertNull(r.testIdFor("t1"));
        assertEquals("com.x.T#c", r.testIdFor("t3"));
    }

    @Test
    void writeAndLoadRoundTrips(@TempDir Path dir) throws Exception {
        TestIdMappingRegistry r = new TestIdMappingRegistry(100);
        r.register("4bf92f", "com.x.T#m");
        Path f = dir.resolve("trace-map.properties");
        r.writeTo(f);
        TraceMapping loaded = TestIdMappingRegistry.loadFrom(f);
        assertEquals("com.x.T#m", loaded.testIdFor("4bf92f"));
        assertNull(loaded.testIdFor("absent"));
    }

    @Test
    void loadFromMissingFileYieldsAlwaysNullMapping(@TempDir Path dir) throws Exception {
        TraceMapping loaded = TestIdMappingRegistry.loadFrom(dir.resolve("nope.properties"));
        assertNull(loaded.testIdFor("anything"));
    }
}
