package az.fitnest.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminGymV3RoutesTest {

    private static final String CATALOG = "http://catalog-backend:8080";

    private List<Route> routes() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class,
                    () -> new PathRoutePredicateFactory(new WebFluxProperties()));
            context.registerBean(SetPathGatewayFilterFactory.class, SetPathGatewayFilterFactory::new);
            context.refresh();
            return new ApiGatewayApplication()
                    .customRouteLocator(new RouteLocatorBuilder(context))
                    .getRoutes()
                    .collectList()
                    .block();
        }
    }

    @Test
    void v3AdminGymWizardPathsGoToCatalogRoute() {
        var routes = routes();

        assertEquals(CATALOG, owner(routes, "POST", "/api/v3/admin/gyms/step1"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v3/admin/gyms/validate/step1"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v3/admin/gyms/42/step6"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v3/admin/gyms/validate/step6"));
        assertEquals(CATALOG, owner(routes, "PUT", "/api/v3/admin/gyms/42/subscriptions"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v3/admin/gyms/create-complete"));

        assertEquals("catalog-backend", ownerId(routes, "POST", "/api/v3/admin/gyms/step1"));
        assertEquals("catalog-backend", ownerId(routes, "POST", "/api/v3/admin/gyms/create-complete"));
    }

    @Test
    void v1AndV2AdminGymPathsKeepTheirExistingOwner() {
        var routes = routes();

        // v1 wizard + home-gym configuration
        assertEquals(CATALOG, owner(routes, "POST", "/api/v1/admin/gyms/step1"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v1/admin/gyms/1/home-gym/prices"));
        // v2 wizard (including the validate paths that share the {id} pattern shape with v3)
        assertEquals(CATALOG, owner(routes, "POST", "/api/v2/admin/gyms/step1"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v2/admin/gyms/validate/step1"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v2/admin/gyms/42/step6"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v2/admin/gyms/validate/step6"));
        assertEquals(CATALOG, owner(routes, "PUT", "/api/v2/admin/gyms/42/subscriptions"));
        assertEquals(CATALOG, owner(routes, "POST", "/api/v2/admin/gyms/create-complete"));
    }

    @Test
    void predicatesAreMethodAgnosticJustLikeExistingVersions() {
        var routes = routes();

        // Gateway routes use path predicates only; method selection is downstream.
        assertEquals(CATALOG, owner(routes, "GET", "/api/v3/admin/gyms/42/subscriptions"));
        assertEquals(CATALOG, owner(routes, "PUT", "/api/v3/admin/gyms/step1"));
        assertEquals(CATALOG, owner(routes, "GET", "/api/v2/admin/gyms/42/subscriptions"));
    }

    @Test
    void nearMissesDoNotGainAnAccidentalRoute() {
        var routes = routes();

        assertNull(owner(routes, "GET", "/api/v3/admin/gyms/42/details"));
        assertNull(owner(routes, "GET", "/api/v3/admin/gyms/42/trainers"));
        assertNull(owner(routes, "POST", "/api/v3/admin/gyms/42/step5"));
        assertNull(owner(routes, "POST", "/api/v3/admin/gyms/validate/step6/extra"));
        assertNull(owner(routes, "POST", "/api/v4/admin/gyms/step1"));

        // Public v3 gym reads are a different path shape but stay on catalog.
        assertTrue(CATALOG.equals(owner(routes, "GET", "/api/v3/gyms/42")));
    }

    private String owner(List<Route> routes, String method, String path) {
        return matching(routes, method, path).stream()
                .map(route -> route.getUri().toString())
                .findFirst()
                .orElse(null);
    }

    private String ownerId(List<Route> routes, String method, String path) {
        return matching(routes, method, path).stream()
                .map(Route::getId)
                .findFirst()
                .orElse(null);
    }

    private List<Route> matching(List<Route> routes, String method, String path) {
        var request = "GET".equals(method)
                ? MockServerHttpRequest.get(path)
                : MockServerHttpRequest.method(HttpMethod.valueOf(method), path);
        var exchange = MockServerWebExchange.from(request);
        return routes.stream()
                .filter(route -> Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block()))
                .toList();
    }
}
