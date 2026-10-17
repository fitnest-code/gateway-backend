package az.fitnest.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeoutException;

/**
 * Central error translation for the reactive gateway.
 *
 * <p>Every handler must accept {@link ServerWebExchange} (WebFlux); servlet abstractions such as
 * {@code WebRequest} cannot be resolved in a reactive application and would turn any handled
 * exception into an unhandled {@code IllegalStateException}.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Keeps framework-raised statuses intact, most importantly the 404 produced when no route
     * matches the requested path.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.warn("{} {} rejected with {} ({})", method(exchange), path(exchange), status.value(), ex.getReason());
        return buildResponse(status, status.name(), messageFor(status), path(exchange));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiResponse<Void>> handleTimeoutException(TimeoutException ex, ServerWebExchange exchange) {
        log.error("Downstream timeout for {} {}: {}", method(exchange), path(exchange), ex.toString());
        return buildResponse(HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT",
                getMessage("error.gateway_timeout"), path(exchange));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleDownstreamIOException(IOException ex, ServerWebExchange exchange) {
        log.error("Downstream request failed for {} {}: {}", method(exchange), path(exchange), ex.toString());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                getMessage("error.service_unavailable"), path(exchange));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex, ServerWebExchange exchange) {
        log.warn("Invalid request for {} {}: {}", method(exchange), path(exchange), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                safeMessage(ex.getMessage(), "error.invalid_request"), path(exchange));
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebClientResponseException(WebClientResponseException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        String message = status == HttpStatus.TOO_MANY_REQUESTS
                ? getMessage("error.rate_limit_exceeded")
                : getMessage("error.unexpected");
        return buildResponse(status, status.name(), message, path(exchange));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex, ServerWebExchange exchange) {
        log.error("Unhandled runtime exception for {} {}", method(exchange), path(exchange), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "RUNTIME_EXCEPTION",
                getMessage("error.unexpected"), path(exchange));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled exception for {} {}", method(exchange), path(exchange), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                getMessage("error.internal_server_error"), path(exchange));
    }

    private ResponseEntity<ApiResponse<Void>> buildResponse(HttpStatus status, String code, String message, String path) {
        ApiError apiError = ApiError.builder()
                .status(status.value())
                .code(code)
                .message(message)
                .path(path)
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.status(status).body(ApiResponse.error(apiError));
    }

    private String messageFor(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return getMessage("error.endpoint_not_found");
        }
        if (status == HttpStatus.METHOD_NOT_ALLOWED) {
            return getMessage("error.method_not_allowed");
        }
        return status.getReasonPhrase();
    }

    private String safeMessage(String detail, String fallbackKey) {
        if (detail == null || detail.isBlank()) {
            return getMessage(fallbackKey);
        }
        if (detail.startsWith("error.")) {
            String resolved = getMessage(detail);
            if (!resolved.equals(detail)) {
                return resolved;
            }
        }
        return detail;
    }

    private String getMessage(String code) {
        try {
            return messageSource.getMessage(code, null, LocaleContextHolder.getLocale());
        } catch (org.springframework.context.NoSuchMessageException e) {
            return code;
        }
    }

    private static String method(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod().name();
    }

    private static String path(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }
}
