package az.fitnest.gateway.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the address handling used by rate limiting and blocking.
 *
 * <p>The blocking rules are the reason this matters: the gateway sits behind
 * ingress-nginx (fronted by Cloudflare), so a request can legitimately appear to
 * originate from a cluster/node address. Blocking such a shared address locks
 * out every user behind it, which is exactly the incident this guards against.
 */
class RequestUtilsTest {

    // ---------------------------------------------------------------------
    // extractClientIP
    // ---------------------------------------------------------------------

    @Test
    void usesFirstForwardedAddress() {
        var request = MockServerHttpRequest.get("/api/v1/me/promo/active")
                .header("X-Forwarded-For", "203.0.113.7, 10.30.0.5")
                .build();

        assertThat(RequestUtils.extractClientIP(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void skipsEntriesThatAreNotIpLiterals() {
        var request = MockServerHttpRequest.get("/api/v1/me/promo/active")
                .header("X-Forwarded-For", "unknown, not-an-ip, 203.0.113.7")
                .build();

        assertThat(RequestUtils.extractClientIP(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToRealIpHeader() {
        var request = MockServerHttpRequest.get("/api/v1/me/promo/active")
                .header("X-Real-IP", "203.0.113.9")
                .build();

        assertThat(RequestUtils.extractClientIP(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToRemoteAddress() {
        var request = MockServerHttpRequest.get("/api/v1/me/promo/active")
                .remoteAddress(new InetSocketAddress("203.0.113.11", 50000))
                .build();

        assertThat(RequestUtils.extractClientIP(request)).isEqualTo("203.0.113.11");
    }

    @Test
    void reportsUnknownWhenNothingIsAvailable() {
        assertThat(RequestUtils.extractClientIP(
                MockServerHttpRequest.get("/").build())).isEqualTo("unknown");
    }

    @Test
    void exposesRawForwardedHeaderForDiagnostics() {
        var request = MockServerHttpRequest.get("/").header("X-Forwarded-For", "junk, 203.0.113.7").build();
        assertThat(RequestUtils.forwardedForHeader(request)).isEqualTo("junk, 203.0.113.7");

        assertThat(RequestUtils.forwardedForHeader(MockServerHttpRequest.get("/").build())).isEqualTo("none");
    }

    // ---------------------------------------------------------------------
    // isBlockableAddress
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "shared/routable-less address [{0}] is never blocked")
    @ValueSource(strings = {
            "10.30.0.5",        // the node addresses seen during the outage
            "172.16.4.1",
            "192.168.1.10",
            "127.0.0.1",
            "169.254.1.1",
            "0.0.0.0",
            "224.0.0.1",
            "::1",
            "fe80::1",
            "fc00::1",
            "fd12:3456::1"
    })
    void sharedAddressesAreNotBlockable(String ip) {
        assertThat(RequestUtils.isBlockableAddress(ip)).isFalse();
    }

    @ParameterizedTest(name = "public address [{0}] is blockable")
    @ValueSource(strings = {"203.0.113.7", "8.8.8.8", "2a06:98c0:3600::1"})
    void publicAddressesAreBlockable(String ip) {
        assertThat(RequestUtils.isBlockableAddress(ip)).isTrue();
    }

    @ParameterizedTest(name = "non-address [{0}] is not blockable")
    @NullSource
    @ValueSource(strings = {"", "  ", "unknown", "not-an-ip", "999.1.1.1", "dead:beef"})
    void nonAddressesAreNotBlockable(String ip) {
        assertThat(RequestUtils.isBlockableAddress(ip)).isFalse();
    }
}
