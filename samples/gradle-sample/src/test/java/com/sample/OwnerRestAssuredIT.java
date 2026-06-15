package com.sample;

import static io.restassured.RestAssured.given;

import io.pjacoco.testkit.restassured.PjacocoRestAssured;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.pjacoco.testkit.junit5.PjacocoExtension;

/**
 * AC1: a parallel REST Assured black-box suite. {@link PjacocoExtension} opens/closes the per-test
 * boundary; {@link PjacocoRestAssured} stamps the baggage header. Each test ends up with its own
 * {@code <Class#method>.exec} even though the two tests run concurrently against the same server.
 */
@ExtendWith(PjacocoExtension.class)
class OwnerRestAssuredIT {

    static final CalcServer app = new CalcServer();

    @BeforeAll
    static void up() throws Exception {
        app.start();
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.port;
        PjacocoRestAssured.enable();
    }

    @AfterAll
    static void down() throws Exception {
        app.stop();
    }

    @Test
    void negativeBranch() {
        given().when().get("/run?n=-5").then().statusCode(200);
    }

    @Test
    void positiveBranch() {
        given().when().get("/run?n=5").then().statusCode(200);
    }
}
