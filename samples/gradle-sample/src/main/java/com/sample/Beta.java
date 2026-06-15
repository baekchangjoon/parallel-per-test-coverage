package com.sample;

/** The other distinct SUT class. If parallel routing leaked, a test's .exec would show classCount=2. */
public class Beta {
    public String hit(boolean b) {
        if (b) {
            return "x";
        }
        return "y";
    }
}
