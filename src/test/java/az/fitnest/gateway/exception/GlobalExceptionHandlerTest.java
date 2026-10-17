package az.fitnest.gateway.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the gateway's error translation.
 *
 * <p>Two regressions are covered here:
 * <ol>
 *   <li>Handlers declaring servlet types ({@code WebRequest}) cannot be resolved by WebFlux —
 *       the handler itself threw and every such error surfaced as a bare 500.</li>
 *   <li>Unmatched routes are reported by Spring as a 404 {@code ResponseStatusException},
 *       which the catch-all {@code RuntimeException} handler used to rewrite to a 500.</li>
 * </ol>
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        handler = new GlobalExceptionHandler(messageSource);
    }

    @Test
    void unmatchedRouteKeepsIts404() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No matching handler"),
                exchange("/api/v1/definitely-unknown-route"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiError error = errorOf(response);
        assertThat(error.code()).isEqualTo("NOT_FOUND");
        assertThat(error.message()).isEqualTo("The requested endpoint was not found");
        assertThat(error.path()).isEqualTo("/api/v1/definitely-unknown-route");
        assertThat(error.timestamp()).isNotNull();
    }

    @Test
    void unsupportedMethodKeepsIts405() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED),
                exchange("/api/v1/checkout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(errorOf(response).message()).isEqualTo("This method is not supported");
    }

    @Test
    void downstreamConnectionFailureBecomes503Not500() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDownstreamIOException(
                new ConnectException("Connection refused: storage-backend"),
                exchange("/api/v1/media/stream/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorOf(response).code()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void downstreamTimeoutBecomes504() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleTimeoutException(
                new TimeoutException("read timeout"),
                exchange("/api/v1/checkout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(errorOf(response).code()).isEqualTo("GATEWAY_TIMEOUT");
    }

    @Test
    void illegalArgumentBecomes400WithTheDetail() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("packageId must not be null"),
                exchange("/api/v1/checkout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response).message()).isEqualTo("packageId must not be null");
    }

    @Test
    void blankIllegalArgumentFallsBackToALocalizedMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("   "),
                exchange("/api/v1/checkout"));

        assertThat(errorOf(response).message()).isEqualTo("Invalid request");
    }

    @Test
    void unexpectedRuntimeExceptionStaysA500() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleRuntimeException(
                new IllegalStateException("boom"),
                exchange("/api/v1/checkout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(errorOf(response).code()).isEqualTo("RUNTIME_EXCEPTION");
    }

    @Test
    void downstreamErrorStatusIsPassedThrough() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleWebClientResponseException(
                WebClientResponseException.create(429, "Too Many Requests", null, null, null),
                exchange("/api/v1/auth/otp/verify"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(errorOf(response).message()).isEqualTo("Request limit exceeded");
    }

    /**
     * The root cause of the "every error body was an empty 500" bug: WebFlux cannot resolve
     * servlet argument types, so any handler declaring one blew up while being invoked.
     */
    @Test
    void everyExceptionHandlerIsInvocableInAReactiveApplication() {
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(ExceptionHandler.class)) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            assertThat(parameters).as("@ExceptionHandler %s must accept the exception", method.getName())
                    .isNotEmpty();
            for (int i = 1; i < parameters.length; i++) {
                assertThat(parameters[i])
                        .as("@ExceptionHandler %s may only take ServerWebExchange beyond the exception", method.getName())
                        .isEqualTo(ServerWebExchange.class);
            }
        }
    }

    private static ApiError errorOf(ResponseEntity<ApiResponse<Void>> response) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isNotNull();
        return response.getBody().error();
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
