package dev.dbevil.remotesms;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DeviceStatus {
    private DeviceStatus() {
    }

    static JSONObject snapshot(Context context) throws Exception {
        JSONObject json = new JSONObject();
        json.put("device", device());
        json.put("battery", battery(context));
        json.put("memory", memory(context));
        json.put("storage", storage());
        json.put("network", network(context));
        json.put("services", services(context));
        json.put("sms", sms(context));
        json.put("sampledAt", System.currentTimeMillis());
        return json;
    }

    private static JSONObject device() throws Exception {
        JSONObject json = new JSONObject();
        json.put("manufacturer", Build.MANUFACTURER);
        json.put("brand", Build.BRAND);
        json.put("model", Build.MODEL);
        json.put("device", Build.DEVICE);
        json.put("android", Build.VERSION.RELEASE);
        json.put("sdk", Build.VERSION.SDK_INT);
        json.put("securityPatch", Build.VERSION.SECURITY_PATCH);
        json.put("uptimeMs", SystemClock.elapsedRealtime());
        return json;
    }

    private static JSONObject battery(Context context) throws Exception {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int temperature = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int voltage = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);

        JSONObject json = new JSONObject();
        json.put("level", level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : JSONObject.NULL);
        json.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        json.put("plugged", plugged != 0);
        json.put("temperatureC", temperature >= 0 ? temperature / 10.0 : JSONObject.NULL);
        json.put("voltageMv", voltage >= 0 ? voltage : JSONObject.NULL);
        return json;
    }

    private static JSONObject memory(Context context) throws Exception {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(info);
        long used = Math.max(info.totalMem - info.availMem, 0);

        JSONObject json = new JSONObject();
        json.put("totalBytes", info.totalMem);
        json.put("availableBytes", info.availMem);
        json.put("usedBytes", used);
        json.put("usedPercent", percent(used, info.totalMem));
        json.put("lowMemory", info.lowMemory);
        return json;
    }

    private static JSONObject storage() throws Exception {
        StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long total = stat.getTotalBytes();
        long available = stat.getAvailableBytes();
        long used = Math.max(total - available, 0);

        JSONObject json = new JSONObject();
        json.put("totalBytes", total);
        json.put("availableBytes", available);
        json.put("usedBytes", used);
        json.put("usedPercent", percent(used, total));
        return json;
    }

    private static JSONObject network(Context context) throws Exception {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();

        String type = info == null ? "" : String.valueOf(info.getTypeName());
        String ssid = wifiSsid(context);

        JSONObject json = new JSONObject();
        json.put("connected", info != null && info.isConnected());
        json.put("type", typeDisplay(type, ssid));
        json.put("subtype", info == null ? "" : String.valueOf(info.getSubtypeName()));
        json.put("wifi", isWifi(manager, info));
        json.put("ssid", ssid);
        return json;
    }

    private static String typeDisplay(String type, String ssid) {
        String cleanType = type == null ? "" : type.trim();
        String cleanSsid = ssid == null ? "" : ssid.trim();
        if (!cleanSsid.isEmpty() && "WIFI".equalsIgnoreCase(cleanType)) {
            return cleanType + " " + cleanSsid;
        }
        return cleanType;
    }

    private static boolean isWifi(ConnectivityManager manager, NetworkInfo info) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && manager != null) {
                Network network = manager.getActiveNetwork();
                NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
                return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        } catch (Exception ignored) {
        }
        return info != null && info.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private static String wifiSsid(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager == null ? null : wifiManager.getConnectionInfo();
            return pickSsid(
                    wifiInfo == null ? "" : wifiInfo.getSSID(),
                    wifiInfo == null ? -1 : wifiInfo.getNetworkId(),
                    configuredSsids(wifiManager)
            );
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Map<Integer, String> configuredSsids(WifiManager wifiManager) {
        Map<Integer, String> ssids = new HashMap<>();
        try {
            if (wifiManager == null) return ssids;
            List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
            if (configs == null) return ssids;
            for (WifiConfiguration config : configs) {
                if (config == null) continue;
                ssids.put(config.networkId, config.SSID);
            }
        } catch (Exception ignored) {
        }
        return ssids;
    }

    static String pickSsid(String directSsid, int networkId, Map<Integer, String> configuredSsids) {
        String direct = cleanSsid(directSsid);
        if (!direct.isEmpty()) return direct;
        if (configuredSsids == null || networkId < 0) return "";
        return cleanSsid(configuredSsids.get(networkId));
    }

    private static String cleanSsid(String ssid) {
        if (ssid == null) return "";
        String value = ssid.trim();
        if (value.isEmpty() || "<unknown ssid>".equalsIgnoreCase(value) || "0x".equalsIgnoreCase(value)) {
            return "";
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static JSONObject services(Context context) throws Exception {
        JSONObject json = new JSONObject();
        json.put("webPort", 8787);
        json.put("sendBridge", SmsSendService.isShellBridgeAvailable());
        json.put("requiresSendBridge", SmsSendService.requiresShellBridge());
        json.put("sendBridgeHint", SmsSendService.requiresShellBridge() ? SmsSendService.shellBridgeStartHint() : "");
        json.put("batteryOptimizationsIgnored", ignoresBatteryOptimizations(context));
        json.put("service", SmsSyncService.stateSnapshot());
        json.put("frpTunnel", FrpClient.snapshot(context));
        return json;
    }

    private static JSONObject sms(Context context) throws Exception {
        JSONObject json = new JSONObject();
        json.put("storedMessages", LocalMessageStore.count(context));
        json.put("sims", SmsSendService.listSims(context));
        return json;
    }

    private static int percent(long used, long total) {
        if (total <= 0) return 0;
        return Math.max(0, Math.min(100, Math.round(used * 100f / total)));
    }

    private static boolean ignoresBatteryOptimizations(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }
}
