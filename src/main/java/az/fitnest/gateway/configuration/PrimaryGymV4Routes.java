package az.fitnest.gateway.configuration;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;

@Configuration
public class PrimaryGymV4Routes {
  @Bean
  public RouteLocator primaryGymV4RouteLocator(RouteLocatorBuilder builder) {
    return builder
        .routes()
        .route(
            "primary-gym-v4-catalog",
            r ->
                r.path(
                        "/api/v4/admin/gyms/*/primary-subscribers",
                        "/api/v4/admin/gyms/*/membership-policy",
                        "/api/v4/gyms/*/membership-quote",
                        "/api/v4/gyms/entrance/scan")
                    .uri("http://catalog-backend:8080"))
        .route(
            "primary-gym-v4-order",
            r ->
                r.path(
                        "/api/v4/me/subscriptions/primary-gym",
                        "/api/v4/admin/users/*/primary-gym",
                        "/api/v4/admin/gyms/*/settlements/*")
                    .uri("http://order-backend:8080"))
        .build();
  }
}
