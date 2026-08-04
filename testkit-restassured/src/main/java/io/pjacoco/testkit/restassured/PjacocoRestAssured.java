package io.pjacoco.testkit.restassured;

import io.pjacoco.testkit.HeaderStyle;
import io.pjacoco.testkit.Pjacoco;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * REST Assured adapter: stamps the active test id (see {@link Pjacoco}) onto every request as a
 * {@link HeaderStyle} wire format, so the agent attributes server-side coverage to the right test
 * case. When no test id is active the request is left untouched.
 *
 * <p>Register once for the whole suite with {@link #enable()} (typically in a {@code @BeforeAll} or a
 * static initializer), or attach {@link #baggageFilter()} to individual requests. The no-arg
 * overloads keep their original signatures and delegate to {@link HeaderStyle#W3C_BAGGAGE}.
 */
public final class PjacocoRestAssured {

    private PjacocoRestAssured() {}

    /**
     * @return a REST Assured {@link Filter} that adds the {@link HeaderStyle#W3C_BAGGAGE} baggage
     *     header while a test is active. Equivalent to {@code baggageFilter(HeaderStyle.W3C_BAGGAGE)}.
     */
    public static Filter baggageFilter() {
        return baggageFilter(HeaderStyle.W3C_BAGGAGE);
    }

    /**
     * @param style which wire format(s) to emit while a test is active
     * @return a REST Assured {@link Filter} that stamps the active test id in the given
     *     {@link HeaderStyle}
     */
    public static Filter baggageFilter(HeaderStyle style) {
        return new BaggageFilter(style);
    }

    /**
     * Register {@link #baggageFilter()} globally via {@link RestAssured#filters(Filter, Filter...)}.
     * Equivalent to {@code enable(HeaderStyle.W3C_BAGGAGE)}.
     */
    public static void enable() {
        enable(HeaderStyle.W3C_BAGGAGE);
    }

    /**
     * Register {@link #baggageFilter(HeaderStyle)} globally via
     * {@link RestAssured#filters(Filter, Filter...)}.
     *
     * @param style which wire format(s) to emit while a test is active
     */
    public static void enable(HeaderStyle style) {
        RestAssured.filters(baggageFilter(style));
    }

    static final class BaggageFilter implements Filter {
        private final HeaderStyle style;

        BaggageFilter(HeaderStyle style) {
            this.style = style;
        }

        @Override
        public Response filter(FilterableRequestSpecification requestSpec,
                               FilterableResponseSpecification responseSpec, FilterContext ctx) {
            if (style != HeaderStyle.FIELD) {
                String baggage = Pjacoco.baggageHeaderValue();
                if (baggage != null) {
                    requestSpec.header("baggage", baggage);
                }
            }
            if (style != HeaderStyle.W3C_BAGGAGE) {
                String value = Pjacoco.fieldHeaderValue();
                if (value != null) {
                    requestSpec.header(Pjacoco.fieldHeaderName(), value);
                }
            }
            return ctx.next(requestSpec, responseSpec);
        }
    }
}
