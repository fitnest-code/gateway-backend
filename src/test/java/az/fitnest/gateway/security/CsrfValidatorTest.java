package az.fitnest.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CsrfValidator#validateCsrfToken}.
 *
 * <p>The validator is invoked by the {@code securityHeadersFilter} global
 * filter in {@code AuthFilterConfig}. Actual behaviour being pinned:
 *
 * <ul>
 *   <li>Mobile app user agents (fitnest-mobile / okhttp / retrofit / android /
 *       ios / mobile, case-insensitive) bypass CSRF entirely.</li>
 *   <li>A {@code csrfTokenHttpOnly} cookie must be present before anything is
 *       enforced — browsers without the cookie (e.g. first visit) skip the
 *       check.</li>
 *       <li>Enforcement (401 response) happens when the request is a
 *       state-changing method (POST/PUT/DELETE/PATCH) OR an
 *       {@code /api/v1/admin/} path, the path starts with {@code /api/v1/},
 *       and the {@code X-CSRF-Token} header is missing or differs from the
 *       cookie value.</li>
 *   <li>Any unexpected exception is swallowed and treated as "allow".</li>
 * </ul>
 *
 * <p>Pure unit tests with MockServerWebExchange: no Spring context, no network.
 */
class CsrfValidatorTest {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String CSRF_COOKIE = "csrfTokenHttpOnly";
    private static final String TOKEN = "server-token";

    // ---------------------------------------------------------------------
    // Mobile user agents bypass CSRF entirely
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "mobile UA [{0}] bypasses CSRF on POST")
    @ValueSource(strings = {
            "fitnest-mobile/2.3.1",
            "okhttp/4.12.0",
            "Retrofit/2.11.0",      // matching is case-insensitive
            "OKHTTP/5.0.0-alpha",   // matching is case-insensitive
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1",
            "MyApp/3.0 (iOS 17.2; iPad)", // contains "ios"
    })
    void mobileUserAgentsBypassCsrfEnforcement(String userAgent) {
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/api/v1/orders", userAgent, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/api/v1/orders", "POST");

        // No response was written -> the request proceeds untouched.
        assertThat(status).isNull();
    }

    // ---------------------------------------------------------------------
    // No CSRF cookie -> no enforcement
    // ---------------------------------------------------------------------

    @Test
    void desktopWithoutCsrfCookieIsNotEnforced() {
        // The whole check is gated on the csrfTokenHttpOnly cookie existing;
        // without a cookie there is nothing to compare the header against.
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/api/v1/orders", DESKTOP_UA, null, null);

        HttpStatusCode status = enforce(exchange, "/api/v1/orders", "POST");

        assertThat(status).isNull();
    }

    // ---------------------------------------------------------------------
    // Desktop UA + cookie -> enforced
    // ---------------------------------------------------------------------

    @Test
    void desktopPostWithCookieAndMissingHeaderIsRejected() {
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/api/v1/orders", DESKTOP_UA, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/api/v1/orders", "POST");

        // respondWithForbidden() actually sets 401 UNAUTHORIZED (name is misleading).
        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void desktopPostWithCookieAndMismatchingHeaderIsRejected() {
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/api/v1/orders", DESKTOP_UA, TOKEN, "wrong-token");

        HttpStatusCode status = enforce(exchange, "/api/v1/orders", "POST");

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void desktopPostWithCookieAndMatchingHeaderPasses() {
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/api/v1/orders", DESKTOP_UA, TOKEN, TOKEN);

        HttpStatusCode status = enforce(exchange, "/api/v1/orders", "POST");

        assertThat(status).isNull();
    }

    @ParameterizedTest(name = "{0} with cookie and no header is rejected")
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void everyStateChangingMethodIsEnforced(String method) {
        MockServerWebExchange exchange = exchange(
                HttpMethod.valueOf(method), "/api/v1/orders", DESKTOP_UA, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/api/v1/orders", method);

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Method / path gating
    // ---------------------------------------------------------------------

    @Test
    void getOnNonAdminPathIsNotEnforced() {
        // GET is not state-changing and /api/v1/orders is not an admin path,
        // so the cookie+header comparison never runs.
        MockServerWebExchange exchange = exchange(
                HttpMethod.GET, "/api/v1/orders/1", DESKTOP_UA, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/api/v1/orders/1", "GET");

        assertThat(status).isNull();
    }

    @Test
    void getOnAdminPathWithCookieIsEnforced() {
        // Admin paths are CSRF-checked for EVERY method (isAdminPath branch),
        // even safe GETs.
        MockServerWebExchange exchange = exchange(
                HttpMethod.GET, "/api/v1/admin/users", DESKTOP_UA, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/api/v1/admin/users", "GET");

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void nonApiV1PathIsNeverEnforced() {
        // PathValidator.startsWithApiV1 gates the check: a state-changing
        // POST to a payment path with a cookie but no header is allowed
        // through (payment callbacks do not carry the CSRF header).
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/payment/abb/init", DESKTOP_UA, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/payment/abb/init", "POST");

        assertThat(status).isNull();
    }

    @Test
    void adminLookingPathOutsideApiV1IsNotEnforced() {
        // Pinning current behaviour: "/admin/subscription/x" does not start
        // with "/api/v1/", so no CSRF check runs even though it looks like an
        // admin route (isCsrfExempted()/isAdminPath() use different prefixes).
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/admin/subscription/x", DESKTOP_UA, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/admin/subscription/x", "POST");

        assertThat(status).isNull();
    }

    // ---------------------------------------------------------------------
    // Missing user agent
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "UA [{0}] is treated as non-mobile")
    @NullSource
    @ValueSource(strings = {"", DESKTOP_UA})
    void missingOrNonMobileUserAgentIsEnforced(String userAgent) {
        // isMobileApp(null) == false and isMobileApp("") == false, so a
        // request with no/empty User-Agent does NOT get the mobile bypass.
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST, "/api/v1/orders", userAgent, TOKEN, null);

        HttpStatusCode status = enforce(exchange, "/api/v1/orders", "POST");

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static MockServerWebExchange exchange(HttpMethod method, String uri,
                                                  String userAgent, String cookieValue,
                                                  String csrfHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, uri);
        if (userAgent != null) {
            builder.header("User-Agent", userAgent);
        }
        if (cookieValue != null) {
            builder.cookie(new HttpCookie(CSRF_COOKIE, cookieValue));
        }
        if (csrfHeader != null) {
            builder.header("X-CSRF-Token", csrfHeader);
        }
        return MockServerWebExchange.from(builder.build());
    }

    /**
     * Runs the validator and returns the response status: {@code null} means
     * no response was written (request proceeds), 401 means rejected.
     */
    private static HttpStatusCode enforce(MockServerWebExchange exchange, String path, String method) {
        CsrfValidator.validateCsrfToken(exchange, path, method).block();
        return exchange.getResponse().getStatusCode();
    }
}
