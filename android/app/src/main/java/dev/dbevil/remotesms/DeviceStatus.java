package dev.dbevil.remotesms;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONObject;

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
        json.put("services", services());
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

        JSONObject json = new JSONObject();
        json.put("connected", info != null && info.isConnected());
        json.put("type", info == null ? "" : String.valueOf(info.getTypeName()));
        json.put("subtype", info == null ? "" : String.valueOf(info.getSubtypeName()));
        return json;
    }

    private static JSONObject services() throws Exception {
        JSONObject json = new JSONObject();
        json.put("webPort", 8787);
        json.put("sendBridge", SmsSendService.isShellBridgeAvailable());
        json.put("requiresSendBridge", SmsSendService.requiresShellBridge());
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
}
