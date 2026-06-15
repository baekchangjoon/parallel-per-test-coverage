package com.sample;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class CalcServlet extends HttpServlet {
    private final Calc calc = new Calc();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        calc.classify(Integer.parseInt(req.getParameter("n")));
        resp.setStatus(200);
        resp.getWriter().write("ok");
    }
}
