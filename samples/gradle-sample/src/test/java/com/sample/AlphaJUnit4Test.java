package com.sample;

import static org.junit.Assert.assertEquals;

import io.pjacoco.testkit.Pjacoco;
import io.pjacoco.testkit.junit4.PjacocoRule;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/** AC4: a JUnit 4 test (run via the Vintage engine) using {@link PjacocoRule} to get per-test .exec. */
public class AlphaJUnit4Test {

    static final AppServer app = new AppServer();

    @Rule
    public final PjacocoRule pjacoco = new PjacocoRule();

    @BeforeClass
    public static void up() throws Exception {
        app.start();
    }

    @AfterClass
    public static void down() throws Exception {
        app.stop();
    }

    @Test
    public void hitsAlpha() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + app.port + "/alpha").openConnection();
        String baggage = Pjacoco.baggageHeaderValue();
        if (baggage != null) {
            c.setRequestProperty("baggage", baggage);
        }
        assertEquals(200, c.getResponseCode());
    }
}
