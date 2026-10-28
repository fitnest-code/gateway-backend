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

class PrimaryGymV4RoutesTest {
  @Test
  void publicV4PathsReachTheirOwnerAndInternalPathsAreNotRouted() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          PathRoutePredicateFactory.class,
          () ->
              new PathRoutePredicateFactory(
                  new org.springframework.boot.webflux.autoconfigure.WebFluxProperties()));
      context.refresh();
      var routes =
          new PrimaryGymV4Routes()
              .primaryGymV4RouteLocator(new RouteLocatorBuilder(context))
              .getRoutes()
              .collectList()
              .block();
      assertEquals(
          "http://catalog-backend:8080",
          owner(routes, "/api/v4/admin/gyms/10/primary-subscribers"));
      assertEquals("http://catalog-backend:8080", owner(routes, "/api/v4/gyms/entrance/scan"));
      assertEquals(
          "http://order-backend:8080", owner(routes, "/api/v4/me/subscriptions/primary-gym"));
      assertNull(owner(routes, "/api/v4/internal/primary-gym/check-ins"));
      assertNull(owner(routes, "/api/v1/gyms/entrance/scan"));
    }
  }

  private String owner(List<Route> routes, String path) {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
    return routes.stream()
        .filter(r -> Boolean.TRUE.equals(Mono.from(r.getPredicate().apply(exchange)).block()))
        .map(r -> r.getUri().toString())
        .findFirst()
        .orElse(null);
  }
}
