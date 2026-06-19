package com.example.otelsut;

/**
 * SUT class instrumented on the REQUEST thread under OTel + pjacoco.
 *
 * <p>Kept in its own class so the E2E test can assert that coverage for <em>this</em> class is
 * recorded in the per-trace exec (request-thread attribution, REQ-004).
 */
public class RequestHandler {

    public String handle(String input) {
        if (input == null || input.isEmpty()) {
            return "empty";
        }
        return "handled:" + input;
    }
}
