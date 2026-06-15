package com.sample;

/** SUT (compiled to Java 11+ bytecode on JDK 11/17 → Condy path). */
public class Calc {
    public int classify(int n) {
        if (n < 0) {
            return -1;
        }
        if (n == 0) {
            return 0;
        }
        return 1;
    }
}
