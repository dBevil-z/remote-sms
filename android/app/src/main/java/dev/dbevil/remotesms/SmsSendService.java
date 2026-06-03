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
        if (recipient == null || recipient.trim().isEmpty()) {
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
        JSONObject record = LocalMessageStore.addOutgoing(context, recipient.trim(), body, subscriptionId, simSlot, parts.size());
        String messageId = record.optString("id");
        Log.i(TAG, "send requested id=" + messageId
                + " recipient=" + maskRecipient(recipient)
                + " subscriptionId=" + subscriptionId
                + " simSlot=" + simSlot
                + " parts=" + parts.size());
        if (sendViaShellBridge(context, messageId, subscriptionId, recipient.trim(), body)) {
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
                manager.sendTextMessage(recipient.trim(), null, body, null, null);
                Log.i(TAG, "sendTextMessage submitted id=" + messageId);
            } else {
                manager.sendMultipartTextMessage(recipient.trim(), null, parts, null, null);
                Log.i(TAG, "sendMultipartTextMessage submitted id=" + messageId + " parts=" + parts.size());
            }
            LocalMessageStore.updateStatus(context, messageId, "submitted", "已提交到系统发送");
        } catch (Exception error) {
            Log.e(TAG, "send submit failed id=" + messageId, error);
            LocalMessageStore.updateStatus(context, messageId, "failed", "提交失败：" + error.getMessage());
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
        try {
            JSONObject payload = new JSONObject();
            payload.put("subscriptionId", subscriptionId);
            payload.put("recipient", recipient);
            payload.put("body", body);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL("http://127.0.0.1:8790/send").openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                Log.i(TAG, "shell bridge submitted id=" + messageId);
                LocalMessageStore.updateStatus(context, messageId, "submitted", "已通过发送桥提交到系统");
                return true;
            }
            Log.w(TAG, "shell bridge failed id=" + messageId + " status=" + status);
        } catch (Exception error) {
            Log.w(TAG, "shell bridge unavailable id=" + messageId, error);
        } finally {
            if (connection != null) connection.disconnect();
        }
        if (requiresShellBridge()) {
            LocalMessageStore.updateStatus(context, messageId, "failed", "发送桥未启动，无法发送");
            throw new IllegalStateException("发送桥未启动，请先启动手机上的 shell 发送桥");
        }
        return false;
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
}
