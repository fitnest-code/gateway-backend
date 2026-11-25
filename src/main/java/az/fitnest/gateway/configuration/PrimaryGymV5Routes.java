package az.fitnest.gateway.configuration;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;

/**
 * Gym subcategory offerings (v5). Kept in its own locator, parallel to {@link PrimaryGymV4Routes}, so the
 * v1/v4 route lists stay untouched. Everything here is owned by catalog-backend.
 */
@Configuration
public class PrimaryGymV5Routes {
  @Bean
  public RouteLocator primaryGymV5RouteLocator(RouteLocatorBuilder builder) {
    return builder
        .routes()
        .route(
            "primary-gym-v5-catalog",
            r ->
                r.path(
                        // Gym detail with main+sub offerings.
                        "/api/v5/gyms/*",
                        // eligibility, scan, primary-scan (single-segment wildcard, no /** on purpose).
                        "/api/v5/gyms/entrance/eligibility",
                        "/api/v5/gyms/entrance/scan",
                        "/api/v5/gyms/entrance/primary-scan",
                        // Admin: per-category prices / work-hours / services.
                        "/api/v1/admin/gyms/*/categories/*/prices",
                        "/api/v1/admin/gyms/*/categories/*/work-hours",
                        "/api/v1/admin/gyms/*/categories/*/services",
                        // Admin analytics grouped by entrance category.
                        "/api/v2/admin/gyms/*/analytics/by-category")
                    .uri("http://catalog-backend:8080"))
        .build();
  }
}
