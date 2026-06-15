package io.pjacoco.testkit.restassured;

import io.pjacoco.testkit.Pjacoco;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * REST Assured adapter: stamps {@code baggage: test.id=<currentTestId>} onto every request while a
 * test is active (see {@link Pjacoco}), so the agent attributes server-side coverage to the right
 * test case. When no test id is active the request is left untouched.
 *
 * <p>Register once for the whole suite with {@link #enable()} (typically in a {@code @BeforeAll} or a
 * static initializer), or attach {@link #baggageFilter()} to individual requests.
 */
public final class PjacocoRestAssured {

    private PjacocoRestAssured() {}

    /** @return a REST Assured {@link Filter} that adds the baggage header while a test is active. */
    public static Filter baggageFilter() {
        return new BaggageFilter();
    }

    /** Register {@link #baggageFilter()} globally via {@link RestAssured#filters(Filter, Filter...)}. */
    public static void enable() {
        RestAssured.filters(baggageFilter());
    }

    static final class BaggageFilter implements Filter {
        @Override
        public Response filter(FilterableRequestSpecification requestSpec,
                               FilterableResponseSpecification responseSpec, FilterContext ctx) {
            String baggage = Pjacoco.baggageHeaderValue();
            if (baggage != null) {
                requestSpec.header("baggage", baggage);
            }
            return ctx.next(requestSpec, responseSpec);
        }
    }
}
