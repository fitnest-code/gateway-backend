package az.fitnest.gateway.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> allowedOrigins = new java.util.ArrayList<>();
        allowedOrigins.add("https://fitnest.vercel.app");
        allowedOrigins.add("https://fitnest.az");
        allowedOrigins.add("http://localhost:3000");
        allowedOrigins.add("http://127.0.0.1:3000");
        allowedOrigins.add("http://161.97.183.7");

        String environment = System.getenv("SPRING_PROFILES_ACTIVE");
        if ("dev".equals(environment) || "development".equals(environment)) {
            allowedOrigins.add("http://localhost:3001");
        }

        config.setAllowedOriginPatterns(allowedOrigins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-CSRF-Token",
                "Origin",
                "Accept",
                "Accept-Encoding",
                "Accept-Language",
                "X-User-Email",
                "X-User-Roles",
                "X-Request-ID",
                "X-FitNest-Landing-Key"
        ));

        config.setAllowCredentials(true);

        config.setMaxAge(3600L);

        config.setExposedHeaders(List.of("X-CSRF-Token", "X-User-Email", "X-User-Roles", "X-Request-ID"));

        CorsConfigurationSource source = new CorsConfigurationSource() {
            @Override
            public CorsConfiguration getCorsConfiguration(ServerWebExchange exchange) {
                return config;
            }
        };

        return new CorsWebFilter(source);
    }
}
