package dev.dbevil.remotesms;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SmsSendService {
    private static final String TAG = "RemoteSms";
    static final String ACTION_SMS_SENT = "dev.dbevil.remotesms.SMS_SENT";
    static final String ACTION_SMS_DELIVERED = "dev.dbevil.remotesms.SMS_DELIVERED";
    static final String EXTRA_MESSAGE_ID = "message_id";
    static final String EXTRA_PART_INDEX = "part_index";
    static final String EXTRA_PART_COUNT = "part_count";

    private SmsSendService() {
    }

    static JSONArray listSims(Context context) {
        JSONArray array = new JSONArray();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return array;
            }
            SubscriptionManager manager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (manager == null) return array;
            List<SubscriptionInfo> infos = manager.getActiveSubscriptionInfoList();
            if (infos == null) return array;
            int defaultSmsSubId = SubscriptionManager.getDefaultSmsSubscriptionId();
            for (SubscriptionInfo info : infos) {
                JSONObject item = new JSONObject();
                item.put("subscriptionId", info.getSubscriptionId());
                item.put("slotIndex", info.getSimSlotIndex());
                item.put("displayName", String.valueOf(info.getDisplayName()));
                item.put("carrierName", String.valueOf(info.getCarrierName()));
                item.put("isDefault", info.getSubscriptionId() == defaultSmsSubId);
                array.put(item);
            }
        } catch (Exception ignored) {
        }
        return array;
    }

    static JSONObject send(Context context, int subscriptionId, String recipient, String body) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("缺少发送短信权限");
        }
        String normalizedRecipient = normalizeRecipient(recipient);
        if (normalizedRecipient.isEmpty()) {
            throw new IllegalArgumentException("收件人不能为空");
        }
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("短信内容不能为空");
        }

        SmsManager manager = subscriptionId > 0
                ? SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                : SmsManager.getDefault();
        ArrayList<String> parts = manager.divideMessage(body);
        int simSlot = simSlotForSubscription(context, subscriptionId);
        JSONObject record = LocalMessageStore.addOutgoing(context, normalizedRecipient, body, subscriptionId, simSlot, parts.size());
        String messageId = record.optString("id");
        Log.i(TAG, "send requested id=" + messageId
                + " recipient=" + maskRecipient(normalizedRecipient)
                + " subscriptionId=" + subscriptionId
                + " simSlot=" + simSlot
                + " parts=" + parts.size());
        AppLog.add(context, "send", "请求发送 id=" + messageId
                + " recipient=" + maskRecipient(normalizedRecipient)
                + " subId=" + subscriptionId
                + " simSlot=" + simSlot
                + " parts=" + parts.size());
        if (sendViaShellBridge(context, messageId, subscriptionId, normalizedRecipient, body)) {
            JSONObject response = new JSONObject();
            response.put("ok", true);
            response.put("parts", parts.size());
            response.put("subscriptionId", subscriptionId);
            response.put("messageId", messageId);
            response.put("status", "submitted");
            response.put("statusText", "已通过发送桥提交到系统");
            return response;
        }

        try {
            if (parts.size() == 1) {
                manager.sendTextMessage(normalizedRecipient, null, body, null, null);
                Log.i(TAG, "sendTextMessage submitted id=" + messageId);
            } else {
                manager.sendMultipartTextMessage(normalizedRecipient, null, parts, null, null);
                Log.i(TAG, "sendMultipartTextMessage submitted id=" + messageId + " parts=" + parts.size());
            }
            LocalMessageStore.updateStatus(context, messageId, "submitted", "已提交到系统发送");
            AppLog.add(context, "send", "系统 SmsManager 已提交 id=" + messageId);
        } catch (Exception error) {
            Log.e(TAG, "send submit failed id=" + messageId, error);
            LocalMessageStore.updateStatus(context, messageId, "failed", "提交失败：" + error.getMessage());
            AppLog.add(context, "send", "提交失败 id=" + messageId + " error=" + error);
            throw error;
        }

        JSONObject response = new JSONObject();
        response.put("ok", true);
        response.put("parts", parts.size());
        response.put("subscriptionId", subscriptionId);
        response.put("messageId", messageId);
        response.put("status", "submitted");
        response.put("statusText", "已提交到系统发送");
        return response;
    }

    static String resultText(int resultCode) {
        if (resultCode == Activity.RESULT_OK) return "已发出，等待送达";
        if (resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE) return "发送失败：通用错误";
        if (resultCode == SmsManager.RESULT_ERROR_NO_SERVICE) return "发送失败：无服务";
        if (resultCode == SmsManager.RESULT_ERROR_NULL_PDU) return "发送失败：PDU 为空";
        if (resultCode == SmsManager.RESULT_ERROR_RADIO_OFF) return "发送失败：飞行模式或射频关闭";
        return "发送失败：错误码 " + resultCode;
    }

    static String deliveryText(int resultCode) {
        if (resultCode == Activity.RESULT_OK) return "已送达";
        return "送达状态异常：错误码 " + resultCode;
    }

    private static int simSlotForSubscription(Context context, int subscriptionId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return -1;
            }
            SubscriptionManager manager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (manager == null) return -1;
            List<SubscriptionInfo> infos = manager.getActiveSubscriptionInfoList();
            if (infos == null) return -1;
            for (SubscriptionInfo info : infos) {
                if (info.getSubscriptionId() == subscriptionId) return info.getSimSlotIndex();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static boolean sendViaShellBridge(Context context, String messageId, int subscriptionId, String recipient, String body) {
        HttpURLConnection connection = null;
        String bridgeError = "";
        boolean bridgeResponded = false;
        try {
            JSONObject payload = new JSONObject();
            payload.put("subscriptionId", subscriptionId);
            payload.put("recipient", recipient);
            payload.put("body", body);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL("http://127.0.0.1:8790/send").openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            bridgeResponded = true;
            String responseBody = readResponse(connection, status);
            if (status >= 200 && status < 300) {
                Log.i(TAG, "shell bridge submitted id=" + messageId);
                LocalMessageStore.updateStatus(context, messageId, "submitted", "已通过发送桥提交到系统");
                AppLog.add(context, "bridge", "发送桥已提交 id=" + messageId);
                return true;
            }
            bridgeError = bridgeErrorText(status, responseBody);
            Log.w(TAG, "shell bridge failed id=" + messageId + " status=" + status + " body=" + responseBody);
            AppLog.add(context, "bridge", "发送桥提交失败 id=" + messageId + " status=" + status + " error=" + bridgeError);
        } catch (Exception error) {
            bridgeError = error.getMessage() == null ? error.toString() : error.getMessage();
            Log.w(TAG, "shell bridge unavailable id=" + messageId, error);
            AppLog.add(context, "bridge", "发送桥连接/等待失败 id=" + messageId + " error=" + bridgeError);
        } finally {
            if (connection != null) connection.disconnect();
        }
        if (requiresShellBridge()) {
            boolean health = bridgeResponded || isShellBridgeAvailable();
            String statusText = health
                    ? "发送桥已启动，但提交失败" + (bridgeError.isEmpty() ? "" : "：" + bridgeError)
                    : "发送桥未启动，无法发送";
            LocalMessageStore.updateStatus(context, messageId, "failed", statusText);
            AppLog.add(context, "send", statusText + " id=" + messageId);
            throw new IllegalStateException(health
                    ? statusText
                    : "发送桥未启动，请先启动手机上的 shell 发送桥");
        }
        return false;
    }

    private static String readResponse(HttpURLConnection connection, int status) {
        InputStream stream = null;
        try {
            stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) stream = connection.getInputStream();
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (builder.length() > 0) builder.append('\n');
                    builder.append(line);
                    if (builder.length() > 500) break;
                }
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String bridgeErrorText(int status, String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody == null ? "" : responseBody);
            String error = json.optString("error", "");
            String output = json.optString("output", "");
            int exitCode = json.optInt("exitCode", -1);
            if (!error.isEmpty()) return error;
            if (!output.isEmpty()) return "命令退出 " + exitCode + "，" + output;
            if (exitCode >= 0) return "命令退出 " + exitCode;
        } catch (Exception ignored) {
        }
        return "HTTP " + status + (responseBody == null || responseBody.isEmpty() ? "" : "，" + responseBody);
    }

    static boolean isShellBridgeAvailable() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8790/health").openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(1200);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() >= 200 && connection.getResponseCode() < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean requiresShellBridge() {
        String brand = String.valueOf(Build.BRAND).toLowerCase();
        String manufacturer = String.valueOf(Build.MANUFACTURER).toLowerCase();
        String model = String.valueOf(Build.MODEL).toLowerCase();
        return brand.contains("smartisan")
                || manufacturer.contains("smartisan")
                || model.contains("oe106");
    }

    private static String maskRecipient(String recipient) {
        if (recipient == null) return "";
        String trimmed = recipient.trim();
        if (trimmed.length() <= 4) return trimmed;
        return "***" + trimmed.substring(trimmed.length() - 4);
    }

    private static String normalizeRecipient(String recipient) {
        if (recipient == null) return "";
        String value = recipient.trim()
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        if (value.startsWith("00") && value.length() > 2) {
            value = "+" + value.substring(2);
        }
        return value;
    }
}
