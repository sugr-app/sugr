package com.sugr.core.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The proxy an end user's machine is actually configured to use, as a
 * {@link ProxySelector} for {@link java.net.http.HttpClient}.
 *
 * <p>The JDK's default selector only looks at the {@code http(s).proxyHost}
 * system properties, which nobody sets for a desktop app, so behind a
 * corporate proxy every update check would simply time out. This resolves,
 * in order:
 *
 * <ol>
 *   <li>explicit JVM system properties ({@code https.proxyHost},
 *       {@code http.proxyHost}) - honoured as-is through the JDK default;</li>
 *   <li>the {@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code NO_PROXY}
 *       environment variables (either case), as curl and most CLIs read them;</li>
 *   <li>on Windows, the user's Internet Settings ({@code ProxyEnable},
 *       {@code ProxyServer}, {@code ProxyOverride} under
 *       {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings})
 *       - what the Settings app and most enterprise policies write;</li>
 *   <li>otherwise a direct connection.</li>
 * </ol>
 *
 * <p>Not covered: PAC / WPAD auto-config scripts and proxies that require
 * authentication. Loopback addresses always bypass the proxy.
 */
public final class SystemProxy {

    private static final String INTERNET_SETTINGS =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private static final long REG_TIMEOUT_MS = 3_000;

    private SystemProxy() {
    }

    /** The resolved selector for this machine. Cheap to call more than once, but not free (may run reg.exe). */
    public static ProxySelector selector() {
        if (System.getProperty("https.proxyHost") != null || System.getProperty("http.proxyHost") != null) {
            return ProxySelector.getDefault();
        }
        Config fromEnv = fromEnvironment(System.getenv());
        if (fromEnv != null) {
            return fromEnv;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Config fromRegistry = fromInternetSettings(readInternetSettings());
            if (fromRegistry != null) {
                return fromRegistry;
            }
        }
        return ProxySelector.getDefault();
    }

    /**
     * A fixed proxy configuration: one proxy per scheme (either may be null for
     * "direct") plus a bypass list.
     */
    static final class Config extends ProxySelector {
        final Proxy http;
        final Proxy https;
        final List<String> bypass;
        /** True for Windows {@code ProxyOverride} glob semantics, false for {@code NO_PROXY} suffix semantics. */
        final boolean windowsBypass;

        Config(Proxy http, Proxy https, List<String> bypass, boolean windowsBypass) {
            this.http = http;
            this.https = https;
            this.bypass = List.copyOf(bypass);
            this.windowsBypass = windowsBypass;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            if (host == null || isLoopback(host)
                    || (windowsBypass ? matchesProxyOverride(host, bypass) : matchesNoProxy(host, bypass))) {
                return List.of(Proxy.NO_PROXY);
            }
            Proxy proxy = "https".equalsIgnoreCase(uri.getScheme()) ? https : http;
            return List.of(proxy != null ? proxy : Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {
            // Nothing to fall back to - the caller's retry/backoff handles it.
        }
    }

    // ---- environment variables ----

    /** Visible for testing. Null when no proxy variable is set. */
    static Config fromEnvironment(Map<String, String> env) {
        Proxy https = parseProxyUrl(firstSet(env, "HTTPS_PROXY", "https_proxy"));
        Proxy http = parseProxyUrl(firstSet(env, "HTTP_PROXY", "http_proxy"));
        if (https == null && http == null) {
            return null;
        }
        // HTTPS_PROXY alone is the common case for an update client; reuse it
        // for plain http rather than going direct for one scheme.
        return new Config(http != null ? http : https, https != null ? https : http,
                splitList(firstSet(env, "NO_PROXY", "no_proxy"), ","), false);
    }

    /**
     * Visible for testing. Parses {@code http://user:pw@host:port/},
     * {@code host:port} or {@code host}; credentials are ignored. Null when
     * blank or unparseable.
     */
    static Proxy parseProxyUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Visible for testing - {@code NO_PROXY} matching as curl does it: {@code *}
     * matches everything; {@code example.com} and {@code .example.com} both
     * match the domain and its subdomains; a {@code :port} suffix is ignored.
     */
    static boolean matchesNoProxy(String host, List<String> entries) {
        String h = normalizeHost(host);
        for (String raw : entries) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.equals("*")) {
                return true;
            }
            entry = stripPort(entry);
            if (entry.startsWith("*.")) {
                entry = entry.substring(1);
            }
            if (entry.startsWith(".")) {
                entry = entry.substring(1);
            }
            if (h.equals(entry) || h.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    // ---- Windows Internet Settings ----

    /**
     * Visible for testing. {@code values} are the raw {@code reg query} data
     * strings keyed by lowercase value name; null when the proxy is off or unset.
     */
    static Config fromInternetSettings(Map<String, String> values) {
        String enable = values.get("proxyenable");
        if (enable == null || !isNonZero(enable)) {
            return null;
        }
        Proxy[] proxies = parseProxyServer(values.get("proxyserver"));
        if (proxies == null) {
            return null;
        }
        return new Config(proxies[0], proxies[1], splitList(values.get("proxyoverride"), ";"), true);
    }

    /**
     * Visible for testing. Parses {@code ProxyServer}: either one
     * {@code host:port} for every scheme, or per-scheme
     * {@code http=h:p;https=h2:p2} (other schemes, like {@code socks=}, are
     * ignored). Returns {@code {http, https}} with null for "direct", or null
     * when nothing usable is configured.
     */
    static Proxy[] parseProxyServer(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Proxy http = null;
        Proxy https = null;
        if (!raw.contains("=")) {
            Proxy all = parseProxyUrl(raw);
            http = all;
            https = all;
        } else {
            for (String part : raw.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String scheme = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                Proxy proxy = parseProxyUrl(part.substring(eq + 1));
                if (scheme.equals("http")) {
                    http = proxy;
                } else if (scheme.equals("https")) {
                    https = proxy;
                }
            }
        }
        return http == null && https == null ? null : new Proxy[]{http, https};
    }

    /**
     * Visible for testing - {@code ProxyOverride} matching as WinINet does it:
     * {@code ;}-separated case-insensitive globs where {@code *} is any run of
     * characters, plus {@code <local>} for any host name without a dot.
     */
    static boolean matchesProxyOverride(String host, List<String> entries) {
        String h = normalizeHost(host);
        for (String raw : entries) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.equals("<local>")) {
                if (!h.contains(".") && !h.contains(":")) {
                    return true;
                }
                continue;
            }
            if (glob(entry, h)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> readInternetSettings() {
        try {
            Process process = new ProcessBuilder("reg", "query", INTERNET_SETTINGS)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), Charset.defaultCharset());
            }
            if (!process.waitFor(REG_TIMEOUT_MS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return Map.of();
            }
            return parseRegQuery(output);
        } catch (IOException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    /** Visible for testing - value name (lowercased) to data, from {@code reg query} output. */
    static Map<String, String> parseRegQuery(String output) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("^\\s+(.+?)\\s+REG_[A-Z0-9_]+(?:\\s+(.*))?$");
        for (String l : output.split("\\R")) {
            java.util.regex.Matcher m = line.matcher(l);
            if (m.matches()) {
                values.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) == null ? "" : m.group(2).trim());
            }
        }
        return values;
    }

    // ---- helpers ----

    private static boolean glob(String pattern, String text) {
        // Iterative wildcard match - * only, which is all ProxyOverride uses.
        int p = 0;
        int t = 0;
        int star = -1;
        int mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    private static boolean isLoopback(String host) {
        String h = normalizeHost(host);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1");
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    private static String stripPort(String entry) {
        int colon = entry.lastIndexOf(':');
        // Leave bare IPv6 addresses alone - more than one colon.
        return colon > 0 && entry.indexOf(':') == colon ? entry.substring(0, colon) : entry;
    }

    private static boolean isNonZero(String dword) {
        try {
            return Long.decode(dword.trim()) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String firstSet(Map<String, String> env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> splitList(String raw, String separator) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(java.util.regex.Pattern.quote(separator))) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }
}
