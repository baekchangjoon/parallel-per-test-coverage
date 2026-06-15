package com.sample;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

/** Tiny embedded servlet app under test, shared by the sample's test classes. */
final class CalcServer {
    private Server server;
    int port;

    public static final class CalcServlet extends HttpServlet {
        private final Calc calc = new Calc();
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            calc.classify(Integer.parseInt(req.getParameter("n")));
            resp.setStatus(200);
            resp.getWriter().write("ok");
        }
    }

    void start() throws Exception {
        server = new Server(0);
        ServletHandler h = new ServletHandler();
        h.addServletWithMapping(CalcServlet.class, "/run");
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
