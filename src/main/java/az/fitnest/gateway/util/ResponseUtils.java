package az.fitnest.gateway.util;

import az.fitnest.gateway.exception.ApiError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@Component
public class ResponseUtils {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static MessageSource messageSource;

    public ResponseUtils(MessageSource messageSource) {
        ResponseUtils.messageSource = messageSource;
    }

    private static String resolveMessage(String key) {
        if (messageSource == null) return key;
        try {
            return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
    }

    public static Mono<Void> respondWithStatus(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return response.setComplete();
    }

    public static Mono<Void> respondWithTooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
        exchange.getResponse().getHeaders().add("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() + 60000));
        exchange.getResponse().getHeaders().add("Retry-After", "60");
        return writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "error.too_many_requests");
    }

    public static Mono<Void> respondWithForbidden(ServerWebExchange exchange) {
        return writeError(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "error.forbidden");
    }

    public static Mono<Void> respondWithUnauthorized(ServerWebExchange exchange) {
        return writeError(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "error.unauthorized");
    }

    private static Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String messageKey) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiError error = ApiError.builder()
                .code(code)
                .message(resolveMessage(messageKey))
                .status(status.value())
                .path(exchange.getRequest().getPath().value())
                .timestamp(OffsetDateTime.now())
                .build();

        byte[] body;
        try {
            body = OBJECT_MAPPER.writeValueAsBytes(error);
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
