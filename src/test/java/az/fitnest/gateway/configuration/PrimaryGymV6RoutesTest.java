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

class PrimaryGymV6RoutesTest {
  private static final String CATALOG = "http://catalog-backend:8080";
  private static final String ORDER = "http://order-backend:8080";

  @Test
  void v6PrimaryPathsRouteWithoutSwallowingOlderVersions() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          PathRoutePredicateFactory.class,
          () ->
              new PathRoutePredicateFactory(
                  new org.springframework.boot.webflux.autoconfigure.WebFluxProperties()));
      context.refresh();
      var routes =
          new PrimaryGymV6Routes()
              .primaryGymV6RouteLocator(new RouteLocatorBuilder(context))
              .getRoutes()
              .collectList()
              .block();

      assertEquals(CATALOG, owner(routes, "POST", "/api/v6/gyms/entrance/primary-scan"));
      assertEquals(ORDER, owner(routes, "GET", "/api/v6/admin/gyms/42/settlements/7"));
      // Frozen contracts stay on their own route locators.
      assertNull(owner(routes, "POST", "/api/v5/gyms/entrance/primary-scan"));
      assertNull(owner(routes, "POST", "/api/v4/gyms/entrance/primary-scan"));
      assertNull(owner(routes, "GET", "/api/v5/gyms/42"));
    }
  }

  private String owner(List<Route> routes, String method, String path) {
    var request =
        method.equals("GET")
            ? MockServerHttpRequest.get(path)
            : MockServerHttpRequest.method(
                org.springframework.http.HttpMethod.valueOf(method), path);
    var exchange = MockServerWebExchange.from(request);
    return routes.stream()
        .filter(r -> Boolean.TRUE.equals(Mono.from(r.getPredicate().apply(exchange)).block()))
        .map(r -> r.getUri().toString())
        .findFirst()
        .orElse(null);
  }
}
