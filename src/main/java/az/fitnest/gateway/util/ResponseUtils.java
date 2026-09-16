package az.fitnest.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import az.fitnest.gateway.exception.ApiError;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class ResponseUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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

    public static Mono<Void> respondWithTooManyRequests(org.springframework.web.server.ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("X-RateLimit-Remaining", "0");
        response.getHeaders().add("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() + 60000));
        response.getHeaders().add("Retry-After", "60");

        ApiError error = ApiError.builder()
                .code("TOO_MANY_REQUESTS")
                .message(resolveMessage("error.too_many_requests"))
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .path(exchange.getRequest().getPath().value())
                .timestamp(java.time.OffsetDateTime.now())
                .build();

        try {
            String jsonResponse = OBJECT_MAPPER.writeValueAsString(error);
            byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }

    public static Mono<Void> respondWithForbidden(org.springframework.web.server.ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiError error = ApiError.builder()
                .code("UNAUTHORIZED")
                .message(resolveMessage("error.unauthorized"))
                .status(HttpStatus.UNAUTHORIZED.value())
                .path(exchange.getRequest().getPath().value())
                .timestamp(java.time.OffsetDateTime.now())
                .build();
        try {
            String json = OBJECT_MAPPER.writeValueAsString(error);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }

    public static Mono<Void> respondWithAccessDenied(org.springframework.web.server.ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiError error = ApiError.builder()
                .code("FORBIDDEN")
                .message(resolveMessage("error.forbidden"))
                .status(HttpStatus.FORBIDDEN.value())
                .path(exchange.getRequest().getPath().value())
                .timestamp(java.time.OffsetDateTime.now())
                .build();
        try {
            String json = OBJECT_MAPPER.writeValueAsString(error);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }

    public static Mono<Void> respondWithUnauthorized(org.springframework.web.server.ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiError error = ApiError.builder()
                .code("UNAUTHORIZED")
                .message(resolveMessage("error.unauthorized"))
                .status(HttpStatus.UNAUTHORIZED.value())
                .path(exchange.getRequest().getPath().value())
                .timestamp(java.time.OffsetDateTime.now())
                .build();
        try {
            String json = OBJECT_MAPPER.writeValueAsString(error);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }
}
