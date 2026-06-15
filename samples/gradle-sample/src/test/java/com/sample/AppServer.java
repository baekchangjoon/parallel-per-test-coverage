package com.sample;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

/** Embedded app with two endpoints, each exercising a DIFFERENT SUT class (Alpha vs Beta). */
final class AppServer {
    private Server server;
    int port;

    public static final class AlphaServlet extends HttpServlet {
        private final Alpha alpha = new Alpha();
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            alpha.hit(5);
            resp.setStatus(200);
            resp.getWriter().write("ok");
        }
    }

    public static final class BetaServlet extends HttpServlet {
        private final Beta beta = new Beta();
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            beta.hit(true);
            resp.setStatus(200);
            resp.getWriter().write("ok");
        }
    }

    void start() throws Exception {
        server = new Server(0);
        ServletHandler h = new ServletHandler();
        h.addServletWithMapping(AlphaServlet.class, "/alpha");
        h.addServletWithMapping(BetaServlet.class, "/beta");
        server.setHandler(h);
        server.start();
        port = server.getURI().getPort();
    }

    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }
}
