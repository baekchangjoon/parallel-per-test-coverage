package com.sample;

import static io.restassured.RestAssured.given;

import io.pjacoco.testkit.junit5.PjacocoExtension;
import io.pjacoco.testkit.restassured.PjacocoRestAssured;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * AC1: a parallel REST Assured black-box suite proving per-test ISOLATION. The two tests run
 * concurrently against the same server but hit different SUT classes (Alpha vs Beta). Each ends up
 * with its own {@code <Class#method>.exec} recording ONLY its class (classCount=1) — if routing leaked
 * across the concurrent tests, the absorbing test's .exec would show classCount=2.
 */
@ExtendWith(PjacocoExtension.class)
class OwnerRestAssuredIT {

    static final AppServer app = new AppServer();

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
    void alphaOnly() {
        given().when().get("/alpha").then().statusCode(200);
    }

    @Test
    void betaOnly() {
        given().when().get("/beta").then().statusCode(200);
    }
}
