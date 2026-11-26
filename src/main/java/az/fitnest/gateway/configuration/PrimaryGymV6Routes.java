package az.fitnest.gateway.configuration;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * v6 primary entrance routes. Parallel to {@link PrimaryGymV5Routes}; does not alter v1–v5 paths.
 */
@Configuration
public class PrimaryGymV6Routes {
  @Bean
  public RouteLocator primaryGymV6RouteLocator(RouteLocatorBuilder builder) {
    return builder
        .routes()
        .route(
            "primary-gym-v6-catalog",
            r ->
                r.path("/api/v6/gyms/entrance/primary-scan")
                    .uri("http://catalog-backend:8080"))
        .route(
            "primary-gym-v6-order-admin",
            r ->
                r.path("/api/v6/admin/gyms/*/settlements/*")
                    .uri("http://order-backend:8080"))
        .build();
  }
}
