package az.fitnest.gateway.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rejection responses written directly to the wire.
 *
 * <p>These bypass Spring's message converters, so the body used to come out empty:
 * the hand-built {@code ObjectMapper} had no {@code JavaTimeModule} and could not
 * serialise the {@code OffsetDateTime} timestamp. Every gateway auth error was
 * therefore {@code 401} with a zero-length body.
 */
class ResponseUtilsTest {

    @Test
    void unauthorizedResponseCarriesAJsonBody() {
        MockServerWebExchange exchange = exchange("/api/v1/me/promo/active");

        ResponseUtils.respondWithUnauthorized(exchange).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                .isEqualTo("application/json");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .startsWith("{\"error\":{")
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"path\":\"/api/v1/me/promo/active\"")
                .contains("\"timestamp\":");
    }

    @Test
    void forbiddenResponseUses403Not401() {
        MockServerWebExchange exchange = exchange("/api/v1/admin/promo-campaigns");

        ResponseUtils.respondWithForbidden(exchange).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .startsWith("{\"error\":{")
                .contains("\"code\":\"FORBIDDEN\"")
                .contains("\"status\":403");
    }

    @Test
    void tooManyRequestsResponseSendsRetryHints() {
        MockServerWebExchange exchange = exchange("/api/v1/checkout");

        ResponseUtils.respondWithTooManyRequests(exchange).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"TOO_MANY_REQUESTS\"");
    }

    @Test
    void statusOnlyResponseHasNoBody() {
        MockServerWebExchange exchange = exchange("/api/v1/checkout");

        ResponseUtils.respondWithStatus(exchange.getResponse(), org.springframework.http.HttpStatus.NO_CONTENT).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(204);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
