package az.fitnest.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PathValidator} — the class {@code AuthFilterConfig}
 * consults to decide whether a request must carry a valid JWT.
 *
 * <p>These tests PIN THE CURRENT BEHAVIOUR (including known over-broad and
 * under-broad matches). Where behaviour looks like a bug it is documented with
 * a comment rather than "fixed" — fixing belongs in {@code src/main}, which
 * these tests deliberately do not touch.
 *
 * <p>Pure unit tests: no Spring context, no network, no mocks.
 */
class PathValidatorTest {

    // ---------------------------------------------------------------------
    // requiresAuth — TRUE matrix (must be authenticated)
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "requiresAuth({0}) is TRUE")
    @CsvSource({
            // --- campaign routes (routed to order-backend) ---
            "/api/v1/campaigns",
            "/api/v1/campaigns/active",
            "/api/v1/campaigns/1",
            "/api/v1/campaigns/1/impressions",
            "/api/v1/admin/campaigns",
            "/api/v1/admin/campaigns/1/offers",
            // campaign banner dismissal lives under the /me namespace
            "/api/v1/me/subscriptions/campaign-banner/dismiss",

            // --- personal endpoints ---
            "/api/v1/me",
            "/api/v2/me",
            "/api/v3/me",

            // --- freezes / subscriptions ---
            "/api/v1/freezes",
            "/api/v1/subscriptions/1/freezes",
            "/api/v1/subscriptions/1/freeze",
            "/api/v1/me/freezes",

            // --- admin namespace (contains "/admin/") ---
            "/api/v1/admin/anything",
            "/admin/subscription/x",

            // --- versioned subscription packages ---
            "/api/v3/subscription-packages",
            "/api/v3/subscription-packages/1",

            // --- internal ---
            "/api/v1/internal/x",
            "/api/v1/internal/",

            // --- payment callback routes (AUTH_REQUIRED_PATHS exact set) ---
            "/payment/abb/init",
            "/payment/abb/installment",
            "/payment/bob/init",
            "/payment/bob/pay-with-card",

            // --- media upload/delete/move ---
            "/api/v1/media/upload",
            "/api/v1/media/delete/1",
            "/api/v1/media/move/1",

            // --- any path ending in /admin ---
            "/some/legacy/admin",
    })
    void requiresAuth_returnsTrue_forProtectedPaths(String path) {
        assertThat(PathValidator.requiresAuth(path)).isTrue();
    }

    // ---------------------------------------------------------------------
    // requiresAuth — FALSE matrix (public / exempt)
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "requiresAuth({0}) is FALSE")
    @CsvSource({
            // --- explicitly public subscription package endpoints ---
            "/api/v1/subscription-packages",
            "/api/v1/subscription-packages/1/options",

            // --- auth & misc ---
            "/api/v1/auth/login",
            "/api/v1/health",
            "/",
            "''", // empty path must not throw and stays public

            // --- AUTH_EXEMPT_PATHS: each entry of the exempt list ---
            // (note: the exempt check runs BEFORE the "/admin/" check, so the
            //  two admin-flavoured exempt paths below stay public on purpose)
            "/api/v1/auth/password-recovery/admin/forgot-password",
            "/api/v1/auth/password-recovery/admin/reset-password",
            "/.well-known/jwks.json",
            "/payment/.well-known/jwks.json",

            // --- explicit goals-image exemption ---
            "/api/v1/goals/images/123",

            // --- subscriptions without a freeze component ---
            "/api/v1/subscriptions/1/refunds",
            "/api/v1/orders/1",

            // --- v2 campaigns are NOT covered (see edge-case test) ---
            "/api/v2/campaigns",

            // --- near-miss of the admin rule: "/admins" has no trailing slash
            //     and therefore does not contain "/admin/" nor end in "/admin"
            "/api/v1/admins",
    })
    void requiresAuth_returnsFalse_forPublicAndExemptPaths(String path) {
        assertThat(PathValidator.requiresAuth(path)).isFalse();
    }

    // ---------------------------------------------------------------------
    // requiresAuth — documented edge behaviour (pinned on purpose)
    // ---------------------------------------------------------------------

    @Test
    void requiresAuth_campaignPrefixMatchIsOverBroad() {
        // KNOWN OVER-BROAD MATCH: the rule is startsWith("/api/v1/campaigns"),
        // so "/api/v1/campaignsXYZ" — which is NOT a campaign route — also
        // demands authentication. Pinned so a future tightening of the prefix
        // (e.g. to "/api/v1/campaigns/" + exact "/api/v1/campaigns") is a
        // conscious, test-visible decision.
        assertThat(PathValidator.requiresAuth("/api/v1/campaignsXYZ")).isTrue();
    }

    @Test
    void requiresAuth_campaignsAreOnlyProtectedOnV1() {
        // KNOWN GAP: only /api/v1/campaigns is guarded; the gateway route
        // config also only declares v1 campaign paths, so today nothing can
        // reach /api/v2/campaigns — but if such a route is ever added it will
        // be public until PathValidator is updated too.
        assertThat(PathValidator.requiresAuth("/api/v2/campaigns")).isFalse();
        assertThat(PathValidator.requiresAuth("/api/v1/campaigns")).isTrue();
    }

    @Test
    void requiresAuth_meEndpointsAreCoveredOnlyUpToV3() {
        // /api/v1/me, /api/v2/me and /api/v3/me are guarded; a hypothetical
        // /api/v4/me would silently be public.
        assertThat(PathValidator.requiresAuth("/api/v4/me")).isFalse();
        assertThat(PathValidator.requiresAuth("/api/v3/me")).isTrue();
    }

    @Test
    void requiresAuth_mePrefixMatchIsOverBroad() {
        // KNOWN OVER-BROAD MATCH: startsWith("/api/v1/me") also matches
        // "/api/v1/meeting" (no route exists for it today).
        assertThat(PathValidator.requiresAuth("/api/v1/meetings")).isTrue();
    }

    // ---------------------------------------------------------------------
    // requiresAuthForContent — admin-only content rule
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "requiresAuthForContent({0}) is TRUE")
    @CsvSource({
            "/api/v1/admin/campaigns",
            "/api/v1/admin/anything",
            "/some/path/admin/deep",     // contains "/admin/"
            "/foo/admin",                // ends with "/admin"
            // contains "/admin/" so TRUE here even though requiresAuth()
            // would return FALSE because of the exempt-list short-circuit:
            "/api/v1/auth/password-recovery/admin/reset-password",
    })
    void requiresAuthForContent_returnsTrue_forAdminPaths(String path) {
        assertThat(PathValidator.requiresAuthForContent(path)).isTrue();
    }

    @ParameterizedTest(name = "requiresAuthForContent({0}) is FALSE")
    @CsvSource({
            "/api/v1/campaigns",
            "/api/v1/me",
            "/api/v1/auth/login",
            "/administration",  // "admin" present but no "/admin/" and no "/admin" suffix
            "/api/v1/admins",   // same: trailing "s" defeats both rules
            "/",
    })
    void requiresAuthForContent_returnsFalse_forNonAdminPaths(String path) {
        assertThat(PathValidator.requiresAuthForContent(path)).isFalse();
    }

    // ---------------------------------------------------------------------
    // isAdminRoute — note: unlike requiresAuth() it is NOT exemption-aware
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "isAdminRoute({0}) is TRUE")
    @CsvSource({
            "/api/v1/admin/campaigns",
            "/api/v1/admin",
            "/admin/subscription/x",
            // KNOWN OVER-BROAD: contains("/admin") matches any path merely
            // holding the substring "admin", e.g. "/api/v1/admins":
            "/api/v1/admins",
            // also TRUE even though requiresAuth() exempts it:
            "/api/v1/auth/password-recovery/admin/forgot-password",
    })
    void isAdminRoute_returnsTrue_forAdminishPaths(String path) {
        assertThat(PathValidator.isAdminRoute(path)).isTrue();
    }

    @ParameterizedTest(name = "isAdminRoute({0}) is FALSE")
    @CsvSource({
            "/api/v1/campaigns",
            "/api/v1/me",
            "/api/v1/auth/login",
            "/api/v1/subscription-packages",
            "/",
    })
    void isAdminRoute_returnsFalse_forNonAdminPaths(String path) {
        assertThat(PathValidator.isAdminRoute(path)).isFalse();
    }

    // ---------------------------------------------------------------------
    // Small helpers whose behaviour is relied on by CsrfValidator
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "isCsrfExempted({0}) is FALSE (list is empty)")
    @ValueSource(strings = {
            "/api/v1/auth/password-recovery/admin/forgot-password",
            "/api/v1/auth/login",
            "/",
    })
    void csrfExemptListIsCurrentlyEmpty(String path) {
        // CSRF_EXEMPTED_PATHS is declared as Set.of() in PathValidator, so
        // nothing can ever be CSRF-exempt today. If the set is populated
        // later this test must be updated deliberately.
        assertThat(PathValidator.isCsrfExempted(path)).isFalse();
    }

    @ParameterizedTest(name = "startsWithApiV1({0}) is {1}")
    @CsvSource({"/api/v1/orders, true", "/api/v1/, true", "/api/v2/orders, false", "/payment/abb/init, false"})
    void startsWithApiV1(String path, boolean expected) {
        assertThat(PathValidator.startsWithApiV1(path)).isEqualTo(expected);
    }
}
