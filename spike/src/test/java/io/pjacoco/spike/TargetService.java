package io.pjacoco.spike;

/** Class under coverage. classify(5) exercises only the final "return 1" path. */
public class TargetService {

    public int classify(int n) {
        if (n < 0) {
            return -1;
        }
        if (n == 0) {
            return 0;
        }
        return 1;
    }

    public String greet(boolean formal) {
        if (formal) {
            return "Good day";
        }
        return "hi";
    }
}
