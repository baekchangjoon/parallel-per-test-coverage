package io.pjacoco.testkit.restassured;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.testkit.HeaderStyle;
import io.pjacoco.testkit.Pjacoco;
import io.restassured.RestAssured;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeaderStyleTest {

    HttpServer server;
    int port;
    final AtomicReference<String> lastBaggage = new AtomicReference<String>("__none__");
    final AtomicReference<String> lastFieldHeader = new AtomicReference<String>("__none__");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", new HttpHandler() {
            public void handle(HttpExchange ex) throws java.io.IOException {
                lastBaggage.set(ex.getRequestHeaders().getFirst("baggage"));
                lastFieldHeader.set(ex.getRequestHeaders().getFirst(Pjacoco.fieldHeaderName()));
                ex.sendResponseHeaders(200, -1);
                ex.close();
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        Pjacoco.clearCurrentTestId();
        RestAssured.reset();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fieldEmitsFieldHeaderOnly() {
        Pjacoco.setCurrentTestId("T1");
        given().filter(PjacocoRestAssured.baggageFilter(HeaderStyle.FIELD))
                .when().get("http://127.0.0.1:" + port + "/api")
                .then().statusCode(200);
        assertEquals("T1", lastFieldHeader.get(), "FIELD style must emit the test.id field header");
        assertNull(lastBaggage.get(), "FIELD style must not emit the baggage header");
    }

    @Test
    void bothEmitsBoth() {
        Pjacoco.setCurrentTestId("T2");
        given().filter(PjacocoRestAssured.baggageFilter(HeaderStyle.BOTH))
                .when().get("http://127.0.0.1:" + port + "/api")
                .then().statusCode(200);
        assertEquals("test.id=T2", lastBaggage.get(), "BOTH style must still emit the baggage header");
        assertEquals("T2", lastFieldHeader.get(), "BOTH style must also emit the test.id field header");
    }

    @Test
    void baggageFilterStyleOverload() {
        Pjacoco.setCurrentTestId("T3");
        given().filter(PjacocoRestAssured.baggageFilter(HeaderStyle.W3C_BAGGAGE))
                .when().get("http://127.0.0.1:" + port + "/api")
                .then().statusCode(200);
        assertEquals("test.id=T3", lastBaggage.get(), "W3C_BAGGAGE style must emit the baggage header");
        assertNull(lastFieldHeader.get(), "W3C_BAGGAGE style must not emit the test.id field header");
    }

    @Test
    void noArgEnableKeepsW3c() {
        // REQ-MM-009: existing no-arg enable() keeps its signature and still delegates to
        // W3C_BAGGAGE (compiling against the unchanged signature is itself part of the proof).
        Pjacoco.setCurrentTestId("T4");
        PjacocoRestAssured.enable();
        given().when().get("http://127.0.0.1:" + port + "/api")
                .then().statusCode(200);
        assertEquals("test.id=T4", lastBaggage.get(), "no-arg enable() must still emit the baggage header");
        assertNull(lastFieldHeader.get(), "no-arg enable() must not emit the test.id field header");
    }
}
