package az.fitnest.gateway.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class PrimaryGymV5RoutesTest {
  private static final String CATALOG = "http://catalog-backend:8080";

  @Test
  void v5GymAndCategoryAdminPathsGoToCatalog() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          PathRoutePredicateFactory.class,
          () ->
              new PathRoutePredicateFactory(
                  new org.springframework.boot.webflux.autoconfigure.WebFluxProperties()));
      context.refresh();
      var routes =
          new PrimaryGymV5Routes()
              .primaryGymV5RouteLocator(new RouteLocatorBuilder(context))
              .getRoutes()
              .collectList()
              .block();
      assertEquals(CATALOG, owner(routes, "POST", "/api/v5/gyms/entrance/eligibility"));
      assertEquals(CATALOG, owner(routes, "POST", "/api/v5/gyms/entrance/scan"));
      assertEquals(CATALOG, owner(routes, "POST", "/api/v5/gyms/entrance/primary-scan"));
      assertEquals(CATALOG, owner(routes, "GET", "/api/v5/gyms/42"));
      assertEquals(CATALOG, owner(routes, "GET", "/api/v1/admin/gyms/7/categories/3/prices"));
      assertEquals(CATALOG, owner(routes, "PUT", "/api/v1/admin/gyms/7/categories/3/work-hours"));
      assertEquals(CATALOG, owner(routes, "PUT", "/api/v1/admin/gyms/7/categories/3/services"));
      assertEquals(CATALOG, owner(routes, "GET", "/api/v2/admin/gyms/7/analytics/by-category"));
      // Must not swallow the frozen older contracts.
      assertNull(owner(routes, "POST", "/api/v1/gyms/entrance/scan"));
      assertNull(owner(routes, "POST", "/api/v4/gyms/entrance/scan"));
    }
  }

  private String owner(List<Route> routes, String method, String path) {
    var request =
        method.equals("GET")
            ? MockServerHttpRequest.get(path)
            : MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path);
    var exchange = MockServerWebExchange.from(request);
    return routes.stream()
        .filter(r -> Boolean.TRUE.equals(Mono.from(r.getPredicate().apply(exchange)).block()))
        .map(r -> r.getUri().toString())
        .findFirst()
        .orElse(null);
  }
}
