package io.pjacoco.testkit.restassured;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.testkit.Pjacoco;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PjacocoRestAssuredTest {

    HttpServer server;
    int port;
    final AtomicReference<String> lastBaggage = new AtomicReference<String>("__none__");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", new HttpHandler() {
            public void handle(HttpExchange ex) throws java.io.IOException {
                String b = ex.getRequestHeaders().getFirst("baggage");
                lastBaggage.set(b);
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
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stampsBaggageHeaderWhenTestActive() {
        Pjacoco.setCurrentTestId("OwnerIT#getById");
        given().filter(PjacocoRestAssured.baggageFilter())
                .when().get("http://127.0.0.1:" + port + "/api")
                .then().statusCode(200);
        assertEquals("test.id=OwnerIT#getById", lastBaggage.get(),
                "baggage header must carry the active test id (raw '#' is valid in a header value)");
    }

    @Test
    void noBaggageHeaderWhenNoTestActive() {
        // no setCurrentTestId
        given().filter(PjacocoRestAssured.baggageFilter())
                .when().get("http://127.0.0.1:" + port + "/api")
                .then().statusCode(200);
        assertNull(lastBaggage.get(), "no baggage header should be sent when no test is active");
    }
}
