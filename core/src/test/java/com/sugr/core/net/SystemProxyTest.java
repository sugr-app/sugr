package com.sugr.core.net;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parsing and matching behind {@link SystemProxy} - no network, no registry. */
class SystemProxyTest {

    private static String hostPort(Proxy proxy) {
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        return address.getHostString() + ":" + address.getPort();
    }

    // ---- ProxyServer ----

    @Test
    void aSingleProxyServerAppliesToEveryScheme() {
        Proxy[] proxies = SystemProxy.parseProxyServer("proxy.corp:8080");
        assertEquals("proxy.corp:8080", hostPort(proxies[0]));
        assertEquals("proxy.corp:8080", hostPort(proxies[1]));
    }

    @Test
    void perSchemeProxyServerEntriesAreSplit() {
        Proxy[] proxies = SystemProxy.parseProxyServer("http=h1:3128;https=h2:8443;socks=s:1080");
        assertEquals("h1:3128", hostPort(proxies[0]));
        assertEquals("h2:8443", hostPort(proxies[1]));
    }

    @Test
    void aSchemeLeftOutOfProxyServerGoesDirect() {
        Proxy[] proxies = SystemProxy.parseProxyServer("http=h1:3128");
        assertEquals("h1:3128", hostPort(proxies[0]));
        assertNull(proxies[1]);
        assertNull(SystemProxy.parseProxyServer("socks=s:1080"));
        assertNull(SystemProxy.parseProxyServer(""));
    }

    @Test
    void proxyUrlsAcceptSchemesCredentialsAndDefaultPorts() {
        assertEquals("p:3128", hostPort(SystemProxy.parseProxyUrl("http://user:pw@p:3128/")));
        assertEquals("p:80", hostPort(SystemProxy.parseProxyUrl("p")));
        assertNull(SystemProxy.parseProxyUrl("   "));
    }

    // ---- NO_PROXY ----

    @Test
    void noProxyMatchesDomainsAndSubdomains() {
        List<String> entries = List.of("example.com", ".corp.test", "*.wild.test", "10.0.0.1:8080");
        assertTrue(SystemProxy.matchesNoProxy("example.com", entries));
        assertTrue(SystemProxy.matchesNoProxy("updates.example.com", entries));
        assertTrue(SystemProxy.matchesNoProxy("a.corp.test", entries));
        assertTrue(SystemProxy.matchesNoProxy("x.wild.test", entries));
        assertTrue(SystemProxy.matchesNoProxy("10.0.0.1", entries));
        assertFalse(SystemProxy.matchesNoProxy("notexample.com", entries));
        assertFalse(SystemProxy.matchesNoProxy("github.com", entries));
        assertTrue(SystemProxy.matchesNoProxy("anything", List.of("*")));
    }

    @Test
    void environmentVariablesBuildASelector() {
        SystemProxy.Config config = SystemProxy.fromEnvironment(Map.of(
                "https_proxy", "http://p:3128", "NO_PROXY", "internal.test"));
        assertNotNull(config);
        assertEquals("p:3128", hostPort(config.select(URI.create("https://updates.example.com/x")).get(0)));
        assertEquals(Proxy.NO_PROXY, config.select(URI.create("https://a.internal.test/x")).get(0));
        assertEquals(Proxy.NO_PROXY, config.select(URI.create("http://localhost:5173/")).get(0));
        assertNull(SystemProxy.fromEnvironment(Map.of()));
    }

    // ---- ProxyOverride ----

    @Test
    void proxyOverrideUsesGlobsAndLocal() {
        List<String> entries = List.of("*.corp.test", "10.*", "<local>");
        assertTrue(SystemProxy.matchesProxyOverride("a.corp.test", entries));
        assertTrue(SystemProxy.matchesProxyOverride("10.1.2.3", entries));
        assertTrue(SystemProxy.matchesProxyOverride("intranet", entries));
        assertFalse(SystemProxy.matchesProxyOverride("corp.test.evil.com", entries));
        assertFalse(SystemProxy.matchesProxyOverride("github.com", entries));
    }

    @Test
    void internetSettingsAreOnlyUsedWhenEnabled() {
        String output = """

                HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings
                    ProxyEnable    REG_DWORD    0x1
                    ProxyServer    REG_SZ    proxy.corp:8080
                    ProxyOverride    REG_SZ    *.corp.test;<local>
                """;
        SystemProxy.Config config = SystemProxy.fromInternetSettings(SystemProxy.parseRegQuery(output));
        assertNotNull(config);
        assertEquals("proxy.corp:8080", hostPort(config.select(URI.create("https://e.test/")).get(0)));
        assertEquals(Proxy.NO_PROXY, config.select(URI.create("https://x.corp.test/")).get(0));

        assertNull(SystemProxy.fromInternetSettings(SystemProxy.parseRegQuery(
                output.replace("0x1", "0x0"))));
    }
}
