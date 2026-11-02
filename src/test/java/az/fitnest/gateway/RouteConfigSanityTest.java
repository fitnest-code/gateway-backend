package az.fitnest.gateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity guard for the route definitions in {@code ApiGatewayApplication}.
 *
 * <p>Why this test reads main source text: booting a Spring context to
 * introspect routes would require service discovery/consul and could never run
 * in CI. Reading the source file instead is deterministic, offline and costs
 * milliseconds — a cheap regression guard against merge accidents (the
 * campaign routes were merged in from the october-campaign branch, so a bad
 * merge could silently drop or duplicate them).
 */
class RouteConfigSanityTest {

    private static final String RELATIVE_SOURCE =
            "src/main/java/az/fitnest/gateway/ApiGatewayApplication.java";

    /** The four campaign paths the order-backend route must declare. */
    private static final List<String> CAMPAIGN_PATHS = List.of(
            "\"/api/v1/campaigns\"",
            "\"/api/v1/campaigns/**\"",
            "\"/api/v1/admin/campaigns\"",
            "\"/api/v1/admin/campaigns/**\"");

    /** Checkout paths the order-backend route must declare. */
    private static final List<String> CHECKOUT_PATHS = List.of(
            "\"/api/v1/checkout\"",
            "\"/api/v1/checkout/**\"");

    /**
     * Home-gym paths the catalog-backend route must declare: the customer gym list
     * and the admin configuration screens (spec 6.1-6.3).
     */
    private static final List<String> CATALOG_HOME_GYM_PATHS = List.of(
            "\"/api/v1/subscriptions/*/home-gym/gyms\"",
            "\"/api/v1/admin/gyms/{gymId}/home-gym\"",
            "\"/api/v1/admin/gyms/{gymId}/home-gym/prices\"",
            "\"/api/v1/admin/gyms/{gymId}/home-gym/time-slots\"",
            "\"/api/v1/admin/gyms/{gymId}/home-gym/time-slots/{ruleId}\"");

    /**
     * Home-gym paths the order-backend route must declare: selection/visits/history,
     * the catalog rules lookup and the admin subscriber list.
     */
    private static final List<String> ORDER_HOME_GYM_PATHS = List.of(
            "\"/api/v1/subscriptions/*/home-gym\"",
            "\"/api/v1/subscriptions/*/home-gym/visits\"",
            "\"/api/v1/subscriptions/*/home-gym/history\"",
            "\"/api/v1/home-gym/rules\"",
            "\"/api/v1/admin/gyms/*/home-gym/subscribers\"");

    /** All-subscriber QR visit history (spec A) served by order-backend. */
    private static final List<String> ORDER_VISIT_HISTORY_PATHS = List.of(
            "\"/api/v1/me/visits/history\"");

    /** Access hours per gym (spec P-01/P-02). */
    private static final List<String> GYM_ACCESS_HOURS_PATHS = List.of(
            "\"/api/v1/me/gyms/{gymId}/access-hours\"");

    private static String source;

    @BeforeAll
    static void readGatewaySource() throws IOException {
        source = Files.readString(locateSource());
    }

    @Test
    void orderBackendRoute_declaresEachCampaignPathExactlyOnce() {
        for (String path : CAMPAIGN_PATHS) {
            assertThat(occurrences(source, path))
                    .as("route path %s must appear exactly once", path)
                    .isEqualTo(1);
        }
    }

    @Test
    void orderBackendRoute_declaresCheckoutPathsExactlyOnce() {
        for (String path : CHECKOUT_PATHS) {
            assertThat(occurrences(source, path))
                    .as("route path %s must appear exactly once", path)
                    .isEqualTo(1);
        }
    }

    @Test
    void catalogBackendRoute_declaresEachHomeGymPathExactlyOnce() {
        for (String path : CATALOG_HOME_GYM_PATHS) {
            assertThat(occurrences(source, path))
                    .as("catalog route path %s must appear exactly once", path)
                    .isEqualTo(1);
        }
    }

    @Test
    void orderBackendRoute_declaresEachHomeGymPathExactlyOnce() {
        for (String path : ORDER_HOME_GYM_PATHS) {
            assertThat(occurrences(source, path))
                    .as("order route path %s must appear exactly once", path)
                    .isEqualTo(1);
        }
    }

    @Test
    void orderBackendRoute_declaresVisitHistoryExactlyOnce() {
        for (String path : ORDER_VISIT_HISTORY_PATHS) {
            assertThat(occurrences(source, path))
                    .as("order route path %s must appear exactly once", path)
                    .isEqualTo(1);
        }
    }

    @Test
    void gatewayRoute_declaresGymAccessHoursExactlyOnce() {
        for (String path : GYM_ACCESS_HOURS_PATHS) {
            assertThat(occurrences(source, path))
                    .as("gym access hours route path %s must appear exactly once", path)
                    .isEqualTo(1);
        }
    }

    @Test
    void customerHomeGymGymListIsDeclaredBeforeTheBroaderOrderRoute() {
        // Spring Cloud Gateway keeps declaration order as the tie-breaker for equally
        // ordered routes. The customer gym list is served by catalog while the rest of
        // /subscriptions/*/home-gym is served by order; keeping the catalog route first
        // makes the split explicit and survives an order route that widens its pattern.
        int catalogList = source.indexOf("\"/api/v1/subscriptions/*/home-gym/gyms\"");
        int orderSelection = source.indexOf("\"/api/v1/subscriptions/*/home-gym\"");
        int orderBlock = source.indexOf(".route(\"order-backend\"");

        assertThat(catalogList).isNotNegative();
        assertThat(orderSelection).isNotNegative();
        assertThat(catalogList).isLessThan(orderSelection);
        assertThat(catalogList).isLessThan(orderBlock);
    }

    @Test
    void gatewaySource_containsNoMergeConflictMarkers() {
        // Git conflict markers ("<<<<<<< HEAD", "=======", ">>>>>>> branch")
        // left behind by an unfinished merge would normally fail compilation;
        // this guard also catches markers parked in comments or strings.
        List<String> offenders = source.lines()
                .filter(line -> line.startsWith("<<<<<<<")
                        || line.startsWith(">>>>>>>")
                        || line.startsWith("======="))
                .toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void orderBackendRouteBlockExists() {
        assertThat(occurrences(source, ".route(\"order-backend\"")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static long occurrences(String content, String token) {
        long count = 0;
        int index = 0;
        while ((index = content.indexOf(token, index)) != -1) {
            count++;
            index += token.length();
        }
        return count;
    }

    /**
     * Resolves the source file relative to the test working directory
     * (the Gradle project dir by default), walking up as a fallback so the
     * test also works when launched from the repository root or an IDE.
     */
    private static Path locateSource() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(RELATIVE_SOURCE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate " + RELATIVE_SOURCE + " from " + Path.of("").toAbsolutePath());
    }
}
