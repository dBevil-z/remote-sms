package dev.dbevil.remotesms;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FrpClient {
    private static final String ASSET_ARM64 = "bin/frpc-arm64-v8a";
    private static final String FALLBACK_DNS = "114.114.114.114";
    private static final long RESTART_DELAY_MS = 5_000L;
    private static final int LOCAL_PORT = 8787;
    private static final String LOG_TAG = "frp";

    private static FrpClient instance;

    private final Context context;
    private Process process;
    private boolean stopRequested;
    private String activeSignature = "";
    private String activeProxyName = "";
    private String activeDnsServer = "";
    private String activeServerAddr = "";
    private int activeServerPort;
    private int activeRemotePort;
    private long lastStartAt;
    private long lastConnectedAt;
    private long lastExitAt;
    private int lastExitCode = Integer.MIN_VALUE;
    private String lastError = "";
    private String lastInfo = "未启动";
    private String binaryPath = "";

    private FrpClient(Context context) {
        this.context = context.getApplicationContext();
    }

    static synchronized void ensureRunning(Context context) {
        if (instance == null) instance = new FrpClient(context);
        instance.ensureRunningInternal();
    }

    static synchronized void restart(Context context, String reason) {
        if (instance == null) instance = new FrpClient(context);
        AppLog.add(context, LOG_TAG, "请求重启 frp：" + safeText(reason));
        instance.stopInternal("准备重启");
        instance.ensureRunningInternal();
    }

    static synchronized void stop(Context context, String reason) {
        if (instance == null) return;
        instance.stopInternal(reason);
    }

    static synchronized JSONObject snapshot(Context context) throws Exception {
        if (instance == null) instance = new FrpClient(context);
        return instance.snapshotInternal();
    }

    private void ensureRunningInternal() {
        RuntimeConfig runtime = desiredConfig();
        if (!runtime.enabled) {
            stopInternal(runtime.message);
            return;
        }
        if (process != null && process.isAlive() && runtime.signature.equals(activeSignature)) {
            return;
        }
        if (process != null && process.isAlive()) {
            stopInternal("配置已变更");
        }
        try {
            File binary = ensureBinary();
            List<String> command = new ArrayList<>();
            command.add(binary.getAbsolutePath());
            command.add("tcp");
            command.add("-s");
            command.add(runtime.serverAddr);
            command.add("-P");
            command.add(String.valueOf(runtime.serverPort));
            command.add("-n");
            command.add(runtime.proxyName);
            command.add("-i");
            command.add("127.0.0.1");
            command.add("-l");
            command.add(String.valueOf(LOCAL_PORT));
            command.add("-r");
            command.add(String.valueOf(runtime.remotePort));
            command.add("--dns-server");
            command.add(runtime.dnsServer);
            command.add("--log-file");
            command.add("console");
            command.add("--log-level");
            command.add("info");
            command.add("--disable-log-color");
            if (!runtime.authToken.isEmpty()) {
                command.add("-t");
                command.add(runtime.authToken);
            }

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(binary.getParentFile());
            builder.redirectErrorStream(true);
            stopRequested = false;
            process = builder.start();
            activeSignature = runtime.signature;
            activeProxyName = runtime.proxyName;
            activeDnsServer = runtime.dnsServer;
            activeServerAddr = runtime.serverAddr;
            activeServerPort = runtime.serverPort;
            activeRemotePort = runtime.remotePort;
            lastStartAt = System.currentTimeMillis();
            lastInfo = "frp 隧道启动中";
            lastError = "";
            AppLog.add(context, LOG_TAG, "启动 frp 隧道 " + runtime.serverAddr + ":" + runtime.serverPort
                    + " -> " + runtime.remotePort + " (dns " + runtime.dnsServer + ")");
            startLogReader(process);
            startExitWatcher(process);
        } catch (Exception error) {
            lastError = compactError(error);
            lastInfo = "frp 隧道启动失败";
            AppLog.add(context, LOG_TAG, "frp 启动失败：" + lastError);
        }
    }

    private void stopInternal(String reason) {
        stopRequested = true;
        if (process == null) return;
        Process current = process;
        process = null;
        activeSignature = "";
        activeProxyName = "";
        lastInfo = "frp 隧道已停止";
        AppLog.add(context, LOG_TAG, "停止 frp 隧道：" + safeText(reason));
        try {
            current.destroy();
        } catch (Exception ignored) {
        }
    }

    private void startLogReader(Process current) {
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                    current.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    String clean = sanitizeLine(line);
                    if (clean.isEmpty()) continue;
                    synchronized (FrpClient.this) {
                        if (current != process && process != null) return;
                        lastInfo = clean;
                        if (clean.contains("login to server success")) {
                            lastConnectedAt = System.currentTimeMillis();
                            lastError = "";
                            lastInfo = "frp 已连接，隧道运行中";
                        } else if (clean.contains("start proxy success")) {
                            lastConnectedAt = System.currentTimeMillis();
                            lastError = "";
                            lastInfo = "frp 已连接，隧道运行中";
                            requestFollowUpHealthCheck();
                        } else if (isErrorLine(clean)) {
                            lastError = clean;
                        }
                    }
                    AppLog.add(context, LOG_TAG, clean);
                }
            } catch (Exception error) {
                synchronized (FrpClient.this) {
                    if (current == process || process == null) {
                        lastError = compactError(error);
                        lastInfo = "读取 frp 日志失败";
                    }
                }
            }
        }, "remote-sms-frp-log");
        reader.setDaemon(true);
        reader.start();
    }

    private void startExitWatcher(Process current) {
        Thread waiter = new Thread(() -> {
            int exitCode = Integer.MIN_VALUE;
            try {
                exitCode = current.waitFor();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            boolean shouldRestart;
            synchronized (FrpClient.this) {
                if (current != process && process != null) return;
                if (current == process) process = null;
                lastExitAt = System.currentTimeMillis();
                lastExitCode = exitCode;
                shouldRestart = !stopRequested && desiredConfig().enabled;
                if (exitCode != Integer.MIN_VALUE) {
                    lastInfo = "frp 进程退出";
                    if (lastError.isEmpty()) lastError = "frp 进程退出 code=" + exitCode;
                    AppLog.add(context, LOG_TAG, "frp 进程退出 code=" + exitCode);
                }
            }
            if (!shouldRestart) return;
            try {
                Thread.sleep(RESTART_DELAY_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (FrpClient.class) {
                if (instance == null) return;
                if (!instance.stopRequested) {
                    AppLog.add(context, LOG_TAG, "frp 断开后自动重连");
                    instance.ensureRunningInternal();
                }
            }
        }, "remote-sms-frp-exit");
        waiter.setDaemon(true);
        waiter.start();
    }

    private JSONObject snapshotInternal() throws Exception {
        RuntimeConfig runtime = desiredConfig();
        JSONObject json = new JSONObject();
        json.put("supported", supportsCurrentAbi());
        json.put("enabled", runtime.enabled);
        json.put("running", process != null && process.isAlive());
        json.put("serverAddr", runtime.serverAddr);
        json.put("serverPort", runtime.serverPort > 0 ? runtime.serverPort : JSONObject.NULL);
        json.put("remotePort", runtime.remotePort > 0 ? runtime.remotePort : JSONObject.NULL);
        json.put("proxyName", activeProxyName.isEmpty() ? runtime.proxyName : activeProxyName);
        json.put("dnsServer", activeDnsServer.isEmpty() ? runtime.dnsServer : activeDnsServer);
        json.put("binaryPath", binaryPath.isEmpty() ? JSONObject.NULL : binaryPath);
        json.put("lastStartAt", lastStartAt > 0 ? lastStartAt : JSONObject.NULL);
        json.put("lastConnectedAt", lastConnectedAt > 0 ? lastConnectedAt : JSONObject.NULL);
        json.put("lastExitAt", lastExitAt > 0 ? lastExitAt : JSONObject.NULL);
        json.put("lastExitCode", lastExitCode == Integer.MIN_VALUE ? JSONObject.NULL : lastExitCode);
        json.put("lastError", lastError);
        json.put("lastInfo", lastInfo);
        json.put("publicUrl", runtime.publicUrl);
        json.put("message", runtime.message);
        return json;
    }

    private RuntimeConfig desiredConfig() {
        Config.FrpConfig frp = Config.frpConfig(context);
        String serverAddr = clean(frp.serverAddr);
        String publicUrl = clean(frp.publicUrl);
        if (serverAddr.isEmpty()) serverAddr = hostFromUrl(publicUrl);
        int serverPort = parseInt(frp.serverPort, 7000);
        int remotePort = parseInt(frp.remotePort, portFromUrl(publicUrl));
        if (!supportsCurrentAbi()) {
            return RuntimeConfig.disabled(publicUrl, "当前设备架构暂不支持内置 frpc");
        }
        if (serverAddr.isEmpty()) return RuntimeConfig.disabled(publicUrl, "未配置 frp 服务器地址");
        if (serverPort <= 0) return RuntimeConfig.disabled(publicUrl, "frp 服务端口无效");
        if (remotePort <= 0) return RuntimeConfig.disabled(publicUrl, "frp 远端端口无效");
        String resolvedPublicUrl = publicUrl.isEmpty() ? "http://" + serverAddr + ":" + remotePort : publicUrl;
        String dnsServer = pickDnsServer();
        String proxyName = "remote_sms_" + Integer.toHexString(Config.deviceId(context).hashCode()).replace("-", "x");
        String signature = serverAddr + ":" + serverPort + "|" + remotePort + "|" + frp.authToken + "|" + dnsServer;
        return RuntimeConfig.enabled(resolvedPublicUrl, serverAddr, serverPort, remotePort, frp.authToken, dnsServer, proxyName, signature);
    }

    private File ensureBinary() throws Exception {
        String assetName = assetName();
        if (assetName.isEmpty()) throw new IllegalStateException("没有找到匹配设备架构的 frpc 二进制");
        File dir = new File(context.getFilesDir(), "frp");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建 frp 目录");
        File binary = new File(dir, "frpc");
        if (!binary.exists() || binary.length() == 0) {
            try (InputStream input = context.getAssets().open(assetName);
                 FileOutputStream output = new FileOutputStream(binary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
        }
        if (!binary.setExecutable(true, true)) {
            throw new IllegalStateException("无法赋予 frpc 执行权限");
        }
        binaryPath = binary.getAbsolutePath();
        return binary;
    }

    private boolean supportsCurrentAbi() {
        return !assetName().isEmpty();
    }

    private String assetName() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null) return "";
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) return ASSET_ARM64;
        }
        return "";
    }

    private String pickDnsServer() {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = manager.getActiveNetwork();
                LinkProperties linkProperties = network == null ? null : manager.getLinkProperties(network);
                if (linkProperties != null) {
                    for (InetAddress dns : linkProperties.getDnsServers()) {
                        if (dns instanceof Inet4Address && !dns.isLoopbackAddress()) {
                            return dns.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return FALLBACK_DNS;
    }

    private String hostFromUrl(String value) {
        try {
            if (value == null || value.trim().isEmpty()) return "";
            URI uri = URI.create(value.trim());
            return clean(uri.getHost());
        } catch (Exception ignored) {
            return "";
        }
    }

    private int portFromUrl(String value) {
        try {
            if (value == null || value.trim().isEmpty()) return 0;
            URI uri = URI.create(value.trim());
            return uri.getPort();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(clean(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String sanitizeLine(String line) {
        if (line == null) return "";
        return line.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
    }

    private boolean isErrorLine(String line) {
        String lower = line.toLowerCase(Locale.US);
        return lower.contains(" error")
                || lower.contains("failed")
                || lower.contains("refused")
                || lower.contains("timeout")
                || lower.contains("closed");
    }

    private String compactError(Exception error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null || detail.trim().isEmpty() ? "" : ": " + detail.trim());
    }

    private void requestFollowUpHealthCheck() {
        Thread checker = new Thread(() -> {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            SmsSyncService.requestHealthCheck(context);
        }, "remote-sms-frp-health");
        checker.setDaemon(true);
        checker.start();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeText(String value) {
        String text = clean(value);
        return text.isEmpty() ? "未说明原因" : text;
    }

    private static final class RuntimeConfig {
        final boolean enabled;
        final String publicUrl;
        final String serverAddr;
        final int serverPort;
        final int remotePort;
        final String authToken;
        final String dnsServer;
        final String proxyName;
        final String signature;
        final String message;

        private RuntimeConfig(boolean enabled, String publicUrl, String serverAddr, int serverPort, int remotePort,
                              String authToken, String dnsServer, String proxyName, String signature, String message) {
            this.enabled = enabled;
            this.publicUrl = publicUrl == null ? "" : publicUrl;
            this.serverAddr = serverAddr == null ? "" : serverAddr;
            this.serverPort = serverPort;
            this.remotePort = remotePort;
            this.authToken = authToken == null ? "" : authToken;
            this.dnsServer = dnsServer == null ? FALLBACK_DNS : dnsServer;
            this.proxyName = proxyName == null ? "remote_sms" : proxyName;
            this.signature = signature == null ? "" : signature;
            this.message = message == null ? "" : message;
        }

        static RuntimeConfig enabled(String publicUrl, String serverAddr, int serverPort, int remotePort,
                                     String authToken, String dnsServer, String proxyName, String signature) {
            return new RuntimeConfig(true, publicUrl, serverAddr, serverPort, remotePort,
                    authToken, dnsServer, proxyName, signature, "已配置");
        }

        static RuntimeConfig disabled(String publicUrl, String message) {
            return new RuntimeConfig(false, publicUrl, "", 0, 0, "", FALLBACK_DNS, "remote_sms", "", message);
        }
    }
}
