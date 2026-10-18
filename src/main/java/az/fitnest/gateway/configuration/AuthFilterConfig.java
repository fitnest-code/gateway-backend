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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AuthFilterConfig.class);

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
                    : Mono.just(JwtProcessor.TokenValidationResult.rejected(JwtProcessor.RejectionReason.MISSING_TOKEN));

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
                                return ResponseUtils.respondWithForbidden(sanitizedExchange);
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
            return handleAuthFailure(exchange, clientIP, path, rejectionReason(validation));
        }

        if (token != null && !token.isEmpty()) {
            return handleTokenValidation(exchange, chain, validation, requiresAuth, clientIP, path, token);
        }

        return chain.filter(addAnonymousHeaders(exchange, clientIP));
    }

    private Mono<Void> handleAuthFailure(ServerWebExchange exchange, String clientIP, String path,
                                        JwtProcessor.RejectionReason reason) {
        if (reason == JwtProcessor.RejectionReason.MISSING_TOKEN) {
            log.debug("Unauthenticated request to {} from {} (X-Forwarded-For: {})",
                    path, clientIP, RequestUtils.forwardedForHeader(exchange.getRequest()));
        } else {
            log.warn("Rejecting request to {} from {} (X-Forwarded-For: {}): token {}",
                    path, clientIP, RequestUtils.forwardedForHeader(exchange.getRequest()), reason);
        }

        Mono<Void> recordMono = path.equals("/api/v1/auth/verify-otp") ?
                Mono.empty() : blockManager.recordFailedAttempt(clientIP, null).then();
        return recordMono.then(ResponseUtils.respondWithUnauthorized(exchange));
    }

    private static JwtProcessor.RejectionReason rejectionReason(JwtProcessor.TokenValidationResult validation) {
        if (validation.rejectionReason != null) {
            return validation.rejectionReason;
        }
        return validation.valid ? null : JwtProcessor.RejectionReason.INVALID_TOKEN;
    }

    private Mono<Void> handleTokenValidation(ServerWebExchange exchange, GatewayFilterChain chain,
                                             JwtProcessor.TokenValidationResult validation, boolean requiresAuth,
                                             String clientIP, String path, String token) {
        if (!validation.valid) {
            if (requiresAuth) {
                return handleAuthFailure(exchange, clientIP, path, rejectionReason(validation));
            } else {
                return chain.filter(addAnonymousHeaders(exchange, clientIP));
            }
        }

        return blockManager.isBlocked(clientIP, validation.email)
                .flatMap(blocked -> {
                    if (blocked) {
                        log.warn("Blocked request to {} from {} (user {})", path, clientIP, validation.userId);
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
