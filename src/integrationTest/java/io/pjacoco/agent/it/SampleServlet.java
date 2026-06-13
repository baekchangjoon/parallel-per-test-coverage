package io.pjacoco.agent.it;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Trivial servlet under test. ?mode=negative|positive|zero drives different TargetService branches. */
public class SampleServlet extends HttpServlet {
    private final TargetService svc = new TargetService();

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
