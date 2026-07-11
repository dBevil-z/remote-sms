package dev.dbevil.remotesms;

import java.util.Locale;

final class FrpRuntimeSupport {
    private static final long BASE_RETRY_DELAY_MS = 5_000L;
    private static final long MAX_RETRY_DELAY_MS = 60_000L;

    private FrpRuntimeSupport() {
    }

    static String toml(String serverAddr, int serverPort, int remotePort, String authToken,
                       String dnsServer, String proxyName, int localPort) {
        StringBuilder text = new StringBuilder();
        appendString(text, "serverAddr", serverAddr);
        appendInt(text, "serverPort", serverPort);
        text.append("loginFailExit = false\n");
        if (!clean(dnsServer).isEmpty()) appendString(text, "dnsServer", dnsServer);
        if (!clean(authToken).isEmpty()) {
            appendString(text, "auth.method", "token");
            appendString(text, "auth.token", authToken);
        }
        text.append('\n');
        text.append("[[proxies]]\n");
        appendString(text, "name", proxyName);
        appendString(text, "type", "tcp");
        appendString(text, "localIP", "127.0.0.1");
        appendInt(text, "localPort", localPort);
        appendInt(text, "remotePort", remotePort);
        return text.toString();
    }

    static boolean isTransientNetworkError(String line) {
        String lower = clean(line).toLowerCase(Locale.US);
        return lower.contains("network is unreachable")
                || lower.contains("no route to host")
                || lower.contains("no such host")
                || lower.contains("temporary failure in name resolution")
                || lower.contains("connection refused")
                || lower.contains("connection reset")
                || lower.contains("i/o timeout")
                || lower.contains("timed out")
                || lower.contains("timeout");
    }

    static long retryDelayMs(int failureCount) {
        int exponent = Math.max(0, Math.min(failureCount, 4));
        return Math.min(MAX_RETRY_DELAY_MS, BASE_RETRY_DELAY_MS << exponent);
    }

    static boolean shouldRestartForNetwork(String activeDns, String currentDns, boolean networkAvailable) {
        if (!networkAvailable || clean(currentDns).isEmpty()) return false;
        return !clean(currentDns).equals(clean(activeDns));
    }

    private static void appendString(StringBuilder text, String key, String value) {
        text.append(key).append(" = \"").append(escapeToml(value)).append("\"\n");
    }

    private static void appendInt(StringBuilder text, String key, int value) {
        text.append(key).append(" = ").append(value).append('\n');
    }

    private static String escapeToml(String value) {
        return clean(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
