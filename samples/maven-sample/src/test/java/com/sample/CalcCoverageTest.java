package com.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.testkit.Pjacoco;
import java.net.HttpURLConnection;
import java.net.URL;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CalcCoverageTest {
    static Server server;
    static int appPort;

    @BeforeAll
    static void up() throws Exception {
        server = new Server(0);
        ServletHandler h = new ServletHandler();
        h.addServletWithMapping(CalcServlet.class, "/run");
        server.setHandler(h);
        server.start();
        appPort = server.getURI().getPort();
    }

    @AfterAll
    static void down() throws Exception { server.stop(); }

    @Test
    void routesCoverage() throws Exception {
        assertTrue(Pjacoco.enabled(), "the maven plugin must set -Dpjacoco.control-url");
        Pjacoco.start("T1", null);
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + appPort + "/run?n=5").openConnection();
        c.setRequestProperty("baggage", "test.id=T1");
        assertEquals(200, c.getResponseCode());
        Pjacoco.stop("T1", "passed");
    }
}
