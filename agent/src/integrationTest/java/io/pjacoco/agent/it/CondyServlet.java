package io.pjacoco.agent.it;

import com.example.app.CondyTarget;
import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet under test for the Condy (Java 11+ bytecode) e2e. {@code ?mode=negative|positive|zero}
 * drives different {@link CondyTarget} branches so per-test isolation is observable on the real agent.
 */
public class CondyServlet extends HttpServlet {
    private final CondyTarget svc = new CondyTarget();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String mode = req.getParameter("mode");
        if ("negative".equals(mode)) {
            svc.classify(-5);
        } else if ("zero".equals(mode)) {
            svc.classify(0);
        } else {
            svc.classify(5);
        }
        resp.setStatus(200);
        resp.getWriter().write("ok");
    }
}
