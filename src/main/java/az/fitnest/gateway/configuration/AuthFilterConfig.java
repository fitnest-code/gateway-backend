package az.fitnest.gateway.configuration;

import az.fitnest.gateway.service.BlockManager;
import az.fitnest.gateway.service.JwtProcessor;
import az.fitnest.gateway.service.RateLimiter;
import az.fitnest.gateway.security.CsrfValidator;
import az.fitnest.gateway.security.LandingKeyValidator;
import az.fitnest.gateway.security.PathValidator;
import az.fitnest.gateway.security.SecurityHeaders;
import az.fitnest.gateway.util.RequestUtils;
import az.fitnest.gateway.util.ResponseUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class AuthFilterConfig {

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private BlockManager blockManager;

    @Autowired
    private JwtProcessor jwtProcessor;

    @Autowired
    private LandingKeyValidator landingKeyValidator;

    @Bean
    public GlobalFilter authFilter() {
        return (exchange, chain) -> {
            ServerWebExchange sanitizedExchange = sanitizeHeaders(exchange);
            String path = sanitizedExchange.getRequest().getPath().value();
            String method = sanitizedExchange.getRequest().getMethod().name();
            String clientIP = RequestUtils.extractClientIP(sanitizedExchange.getRequest());

            if (path.contains("/internal/")) {
                return ResponseUtils.respondWithForbidden(sanitizedExchange);
            }

            if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui") || path.startsWith("/swagger")) {
                return chain.filter(addAnonymousHeaders(sanitizedExchange, clientIP));
            }

            String token = RequestUtils.extractToken(sanitizedExchange);
            Mono<JwtProcessor.TokenValidationResult> validationMono = (token != null && !token.isEmpty())
                    ? jwtProcessor.validateToken(token)
                    : Mono.just(new JwtProcessor.TokenValidationResult(false, null, null, null, null, null));

            return validationMono.flatMap(validation -> {
                String identifier = clientIP;
                if (validation.valid && validation.userId != null) {
                    identifier = "user:" + validation.userId;
                }

                return rateLimiter.checkRateLimit(identifier, path, method)
                        .flatMap(result -> {
                            if (result == -1) {
                                return ResponseUtils.respondWithTooManyRequests(sanitizedExchange);
                            }

                            if (!landingKeyValidator.isAllowed(sanitizedExchange)) {
                                return ResponseUtils.respondWithAccessDenied(sanitizedExchange);
                            }

                            ServerWebExchange forwarded = landingKeyValidator.stripKey(sanitizedExchange);
                            return proceedWithValidationResult(forwarded, chain, path, clientIP, token, validation);
                        });
            });
        };
    }

    private ServerWebExchange sanitizeHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(h -> {
                            h.remove("X-User-Id");
                            h.remove("X-Tenant-Id");
                            h.remove("X-Scopes");
                            h.remove("X-Roles");
                            h.remove("X-Service-Name");
                            h.remove("X-From-Gateway");
                            h.remove("X-User-Email");
                            h.remove("X-User-Roles");
                        })
                        .build())
                .build();
    }

    private Mono<Void> proceedWithValidationResult(ServerWebExchange exchange, GatewayFilterChain chain, String path, String clientIP, String token, JwtProcessor.TokenValidationResult validation) {
        boolean requiresAuth = PathValidator.requiresAuth(path);

        if (requiresAuth && (token == null || token.isEmpty() || !validation.valid)) {
            return handleAuthFailure(exchange, clientIP, path);
        }

        if (token != null && !token.isEmpty()) {
            return handleTokenValidation(exchange, chain, validation, requiresAuth, clientIP, path, token);
        }

        return chain.filter(addAnonymousHeaders(exchange, clientIP));
    }

    private Mono<Void> handleAuthFailure(ServerWebExchange exchange, String clientIP, String path) {
        Mono<Void> recordMono = path.equals("/api/v1/auth/verify-otp") ?
                Mono.empty() : blockManager.recordFailedAttempt(clientIP, null).then();
        return recordMono.then(ResponseUtils.respondWithUnauthorized(exchange));
    }

    private Mono<Void> handleTokenValidation(ServerWebExchange exchange, GatewayFilterChain chain,
                                             JwtProcessor.TokenValidationResult validation, boolean requiresAuth,
                                             String clientIP, String path, String token) {
        if (!validation.valid) {
            if (requiresAuth) {
                return handleAuthFailure(exchange, clientIP, path);
            } else {
                return chain.filter(addAnonymousHeaders(exchange, clientIP));
            }
        }

        return blockManager.isBlocked(clientIP, validation.email)
                .flatMap(blocked -> {
                    if (blocked) {
                        return ResponseUtils.respondWithForbidden(exchange);
                    }

                    ServerWebExchange modifiedExchange = addUserHeaders(exchange, validation, clientIP);
                    return chain.filter(modifiedExchange);
                });
    }

    private ServerWebExchange addUserHeaders(ServerWebExchange exchange, JwtProcessor.TokenValidationResult validation, String clientIP) {
        String scopes = validation.roles != null ? String.join(" ", validation.roles) : "";
        return exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .header("X-Client-IP", clientIP)
                        .header("X-Request-Id", UUID.randomUUID().toString())
                        .header("X-User-Id", validation.userId != null ? validation.userId.toString() : "")
                        .header("X-Tenant-Id", "")
                        .header("X-Scopes", scopes)
                        .header("X-Service-Name", "gateway-backend")
                        .header("X-From-Gateway", "1")
                        .header("Accept-Language", RequestUtils.extractLanguage(exchange, validation.language))
                        .build())
                .build();
    }

    private ServerWebExchange addAnonymousHeaders(ServerWebExchange exchange, String clientIP) {
        return exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .header("X-Client-IP", clientIP)
                        .header("X-Request-Id", UUID.randomUUID().toString())
                        .header("X-Service-Name", "gateway-backend")
                        .header("X-From-Gateway", "1")
                        .header("Accept-Language", RequestUtils.extractLanguage(exchange, null))
                        .build())
                .build();
    }

    @Bean
    public GlobalFilter securityHeadersFilter() {
        return (exchange, chain) -> {
            SecurityHeaders.addSecurityHeaders(exchange.getResponse());

            String path = exchange.getRequest().getPath().value();
            String method = exchange.getRequest().getMethod().name();

            return CsrfValidator.validateCsrfToken(exchange, path, method)
                    .then(chain.filter(exchange));
        };
    }

}
