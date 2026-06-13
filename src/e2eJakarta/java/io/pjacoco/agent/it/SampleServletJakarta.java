package io.pjacoco.agent.it;

import com.example.app.TargetService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Jakarta-namespace twin of {@link SampleServlet}. Same {@link TargetService} branches, but extends
 * {@code jakarta.servlet.http.HttpServlet} so the e2e exercises the agent's jakarta servlet hook.
 * {@code ?mode=negative|positive|zero} drives different TargetService branches.
 */
public class SampleServletJakarta extends HttpServlet {

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
