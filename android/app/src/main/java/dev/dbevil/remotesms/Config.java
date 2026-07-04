package dev.dbevil.remotesms;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

final class Config {
    static final String PREFS = "remote_sms";
    static final String KEY_TOKEN = "token";
    static final String KEY_LAST_SMS_DATE = "last_sms_date";
    private static final String KEY_PUBLIC_URL = "public_url";
    private static final String KEY_FRP_SERVER_ADDR = "frp_server_addr";
    private static final String KEY_FRP_SERVER_PORT = "frp_server_port";
    private static final String KEY_FRP_AUTH_TOKEN = "frp_auth_token";
    private static final String KEY_FRP_REMOTE_PORT = "frp_remote_port";

    private Config() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String token(Context context) {
        SharedPreferences prefs = prefs(context);
        String token = prefs.getString(KEY_TOKEN, "");
        if (token == null || token.isEmpty()) {
            token = "sms-" + UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }

    static void setToken(Context context, String token) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply();
    }

    static FrpConfig frpConfig(Context context) {
        SharedPreferences prefs = prefs(context);
        return new FrpConfig(
                prefs.getString(KEY_PUBLIC_URL, ""),
                prefs.getString(KEY_FRP_SERVER_ADDR, ""),
                prefs.getString(KEY_FRP_SERVER_PORT, ""),
                prefs.getString(KEY_FRP_AUTH_TOKEN, ""),
                prefs.getString(KEY_FRP_REMOTE_PORT, "")
        );
    }

    static void setFrpConfig(Context context, FrpConfig config) {
        prefs(context).edit()
                .putString(KEY_PUBLIC_URL, clean(config.publicUrl))
                .putString(KEY_FRP_SERVER_ADDR, clean(config.serverAddr))
                .putString(KEY_FRP_SERVER_PORT, clean(config.serverPort))
                .putString(KEY_FRP_AUTH_TOKEN, clean(config.authToken))
                .putString(KEY_FRP_REMOTE_PORT, clean(config.remotePort))
                .apply();
    }

    static boolean rememberPublicUrl(Context context, String publicUrl) {
        String value = clean(publicUrl);
        if (value.isEmpty() || isLocalUrl(value)) return false;
        SharedPreferences prefs = prefs(context);
        String current = clean(prefs.getString(KEY_PUBLIC_URL, ""));
        if (value.equals(current)) return false;
        prefs.edit().putString(KEY_PUBLIC_URL, value).apply();
        return true;
    }

    static JSONObject configJson(Context context) throws Exception {
        FrpConfig frp = frpConfig(context);
        JSONObject json = new JSONObject();
        json.put("publicUrl", frp.publicUrl);
        json.put("frpServerAddr", frp.serverAddr);
        json.put("frpServerPort", frp.serverPort);
        json.put("frpRemotePort", frp.remotePort);
        json.put("hasFrpAuthToken", !frp.authToken.isEmpty());
        return json;
    }

    static String deviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isLocalUrl(String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("://127.")
                || lower.contains("://localhost")
                || lower.contains("://0.0.0.0")
                || lower.contains("://10.")
                || lower.contains("://192.168.")
                || lower.matches(".*://172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    static final class FrpConfig {
        final String publicUrl;
        final String serverAddr;
        final String serverPort;
        final String authToken;
        final String remotePort;

        FrpConfig(String publicUrl, String serverAddr, String serverPort, String authToken, String remotePort) {
            this.publicUrl = clean(publicUrl);
            this.serverAddr = clean(serverAddr);
            this.serverPort = clean(serverPort);
            this.authToken = clean(authToken);
            this.remotePort = clean(remotePort);
        }
    }
}
