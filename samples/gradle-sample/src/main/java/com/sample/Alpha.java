package com.sample;

/** One of two distinct SUT classes — lets a per-test .exec prove it recorded ONLY its own class. */
public class Alpha {
    public int hit(int n) {
        if (n < 0) {
            return -1;
        }
        return 1;
    }
}
