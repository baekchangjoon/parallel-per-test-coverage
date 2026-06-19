package com.example.otelsut;

/**
 * SUT class instrumented on the ASYNC WORKER thread under OTel + pjacoco.
 *
 * <p>Kept in its own class (separate from {@link RequestHandler}) so the E2E test can assert that
 * coverage for <em>this</em> class is also recorded in the same per-trace exec — proving async
 * attribution (REQ-006): work handed off to a worker thread is attributed to the same trace/test.
 */
public class AsyncWorker {

    public int compute(int value) {
        if (value > 0) {
            return value * 2;
        }
        return 0;
    }
}
