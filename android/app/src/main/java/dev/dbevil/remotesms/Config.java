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
    private static final String KEY_SMTP_HOST = "smtp_host";
    private static final String KEY_SMTP_PORT = "smtp_port";
    private static final String KEY_SMTP_SECURITY = "smtp_security";
    private static final String KEY_SMTP_USERNAME = "smtp_username";
    private static final String KEY_SMTP_PASSWORD = "smtp_password";
    private static final String KEY_EMAIL_SENDER = "email_sender";
    private static final String KEY_EMAIL_RECIPIENT = "email_recipient";

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

    static EmailConfig emailConfig(Context context) {
        SharedPreferences prefs = prefs(context);
        return new EmailConfig(
                prefs.getString(KEY_SMTP_HOST, ""),
                prefs.getString(KEY_SMTP_PORT, ""),
                prefs.getString(KEY_SMTP_SECURITY, ""),
                prefs.getString(KEY_SMTP_USERNAME, ""),
                prefs.getString(KEY_SMTP_PASSWORD, ""),
                prefs.getString(KEY_EMAIL_SENDER, ""),
                prefs.getString(KEY_EMAIL_RECIPIENT, "")
        );
    }

    static void setEmailConfig(Context context, EmailConfig config) {
        prefs(context).edit()
                .putString(KEY_SMTP_HOST, clean(config.smtpHost))
                .putString(KEY_SMTP_PORT, clean(config.smtpPort))
                .putString(KEY_SMTP_SECURITY, clean(config.smtpSecurity))
                .putString(KEY_SMTP_USERNAME, clean(config.smtpUsername))
                .putString(KEY_SMTP_PASSWORD, clean(config.smtpPassword))
                .putString(KEY_EMAIL_SENDER, clean(config.senderEmail))
                .putString(KEY_EMAIL_RECIPIENT, clean(config.recipientEmail))
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
        EmailConfig email = emailConfig(context);
        JSONObject json = new JSONObject();
        json.put("publicUrl", frp.publicUrl);
        json.put("frpServerAddr", frp.serverAddr);
        json.put("frpServerPort", frp.serverPort);
        json.put("frpRemotePort", frp.remotePort);
        json.put("hasFrpAuthToken", !frp.authToken.isEmpty());
        json.put("smtpHost", email.smtpHost);
        json.put("smtpPort", email.smtpPort);
        json.put("smtpSecurity", email.smtpSecurity);
        json.put("smtpUsername", email.smtpUsername);
        json.put("senderEmail", email.senderEmail);
        json.put("recipientEmail", email.recipientEmail);
        json.put("hasSmtpPassword", !email.smtpPassword.isEmpty());
        json.put("emailConfigured", email.isConfigured());
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

    static final class EmailConfig {
        final String smtpHost;
        final String smtpPort;
        final String smtpSecurity;
        final String smtpUsername;
        final String smtpPassword;
        final String senderEmail;
        final String recipientEmail;

        EmailConfig(String smtpHost, String smtpPort, String smtpSecurity,
                    String smtpUsername, String smtpPassword,
                    String senderEmail, String recipientEmail) {
            this.smtpHost = clean(smtpHost);
            this.smtpPort = clean(smtpPort);
            this.smtpSecurity = normalizeSecurity(smtpSecurity);
            this.smtpUsername = clean(smtpUsername);
            this.smtpPassword = clean(smtpPassword);
            this.senderEmail = clean(senderEmail);
            this.recipientEmail = clean(recipientEmail);
        }

        boolean isConfigured() {
            return !smtpHost.isEmpty()
                    && !smtpPassword.isEmpty()
                    && !senderEmail.isEmpty()
                    && !recipientEmail.isEmpty();
        }

        String effectiveUsername() {
            return smtpUsername.isEmpty() ? senderEmail : smtpUsername;
        }

        int portValue() {
            try {
                if (!smtpPort.isEmpty()) return Integer.parseInt(smtpPort);
            } catch (Exception ignored) {
            }
            if ("starttls".equals(smtpSecurity)) return 587;
            if ("none".equals(smtpSecurity)) return 25;
            return 465;
        }

        private static String normalizeSecurity(String value) {
            String lower = clean(value).toLowerCase(Locale.US);
            if ("starttls".equals(lower) || "tls".equals(lower)) return "starttls";
            if ("none".equals(lower) || "plain".equals(lower)) return "none";
            return "ssl";
        }
    }
}
