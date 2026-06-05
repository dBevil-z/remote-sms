package dev.dbevil.remotesms;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LocalMessageStore {
    private static final String FILE_NAME = "messages.jsonl";
    private static final int MAX_MESSAGES = 500;
    private static final long INCOMING_DUPLICATE_WINDOW_MS = 10_000L;

    private LocalMessageStore() {
    }

    static synchronized void add(Context context, SmsPayload payload) {
        try {
            List<JSONObject> messages = readObjects(context);
            Set<String> ids = new HashSet<>();
            for (JSONObject message : messages) {
                ids.add(message.optString("id"));
            }
            if (ids.contains(payload.id)) return;
            if (hasDuplicateIncoming(messages, payload)) return;

            JSONObject json = toJson(context, payload);
            json.put("direction", "incoming");
            json.put("status", "received");
            messages.add(json);
            messages.sort((a, b) -> Long.compare(b.optLong("receivedAt"), a.optLong("receivedAt")));
            if (messages.size() > MAX_MESSAGES) {
                messages = new ArrayList<>(messages.subList(0, MAX_MESSAGES));
            }
            writeObjects(context, messages);
        } catch (Exception ignored) {
        }
    }

    static synchronized JSONObject addOutgoing(Context context, String recipient, String body, int subscriptionId, int simSlot, int parts) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", java.util.UUID.randomUUID().toString());
        json.put("deviceId", Config.deviceId(context));
        json.put("sender", recipient == null ? "" : recipient);
        json.put("recipient", recipient == null ? "" : recipient);
        json.put("body", body == null ? "" : body);
        json.put("receivedAt", System.currentTimeMillis());
        json.put("simSlot", simSlot >= 0 ? simSlot : JSONObject.NULL);
        json.put("subscriptionId", subscriptionId);
        json.put("parts", parts);
        json.put("direction", "outgoing");
        json.put("status", "sending");
        json.put("statusText", "发送中");
        json.put("syncedAt", System.currentTimeMillis());
        addObject(context, json);
        return json;
    }

    static synchronized void updateStatus(Context context, String id, String status, String statusText) {
        try {
            List<JSONObject> messages = readObjects(context);
            for (JSONObject message : messages) {
                if (id.equals(message.optString("id"))) {
                    message.put("status", status);
                    message.put("statusText", statusText);
                    message.put("updatedAt", System.currentTimeMillis());
                    break;
                }
            }
            writeObjects(context, messages);
        } catch (Exception ignored) {
        }
    }

    static synchronized JSONArray list(Context context, int limit) {
        return list(context, 0, limit);
    }

    static synchronized JSONArray list(Context context, int offset, int limit) {
        return list(context, offset, limit, "", "all", "all", false);
    }

    static synchronized JSONArray list(Context context, int offset, int limit, String query,
                                       String direction, String simSlot, boolean codeOnly) {
        JSONArray array = new JSONArray();
        try {
            List<JSONObject> messages = filter(normalizedObjects(context), query, direction, simSlot, codeOnly);
            int start = Math.max(offset, 0);
            int count = Math.min(Math.max(limit, 1), MAX_MESSAGES);
            int end = Math.min(start + count, messages.size());
            for (int i = start; i < end; i++) {
                array.put(messages.get(i));
            }
        } catch (Exception ignored) {
        }
        return array;
    }

    static synchronized int count(Context context) {
        return count(context, "", "all", "all", false);
    }

    static synchronized int count(Context context, String query, String direction, String simSlot, boolean codeOnly) {
        try {
            return filter(normalizedObjects(context), query, direction, simSlot, codeOnly).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static List<JSONObject> filter(List<JSONObject> messages, String query,
                                           String direction, String simSlot, boolean codeOnly) {
        String q = query == null ? "" : query.trim().toLowerCase();
        String dir = direction == null ? "all" : direction.trim();
        String sim = simSlot == null ? "all" : simSlot.trim();
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject message : messages) {
            if (!"all".equals(dir) && !dir.equals(message.optString("direction"))) continue;
            if (!"all".equals(sim)) {
                String current = message.isNull("simSlot") ? "" : String.valueOf(message.optInt("simSlot"));
                if (!sim.equals(current)) continue;
            }
            if (codeOnly && verificationCode(message.optString("body")).isEmpty()) continue;
            if (!q.isEmpty()) {
                String haystack = (message.optString("sender") + " "
                        + message.optString("recipient") + " "
                        + message.optString("body") + " "
                        + message.optString("statusText")).toLowerCase();
                if (!haystack.contains(q)) continue;
            }
            try {
                JSONObject copy = new JSONObject(message.toString());
                String code = verificationCode(copy.optString("body"));
                if (!code.isEmpty()) copy.put("code", code);
                filtered.add(copy);
            } catch (Exception ignored) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    private static String verificationCode(String body) {
        if (body == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:验证码|校验码|动态码|一次性|code|otp|verification)[^0-9]{0,16}(\\d{4,8})",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(body);
        if (matcher.find()) return matcher.group(1);
        return "";
    }

    private static List<JSONObject> normalizedObjects(Context context) throws Exception {
        List<JSONObject> messages = readObjects(context);
        long now = System.currentTimeMillis();
        boolean changed = false;
        List<JSONObject> deduped = new ArrayList<>();
        for (JSONObject message : messages) {
            if ("incoming".equals(message.optString("direction")) && hasDuplicateIncoming(deduped, message)) {
                changed = true;
                continue;
            }
            deduped.add(message);
        }
        messages = deduped;
        for (JSONObject message : messages) {
            if ("sending".equals(message.optString("status"))
                    && now - message.optLong("receivedAt") > 10 * 60 * 1000L) {
                message.put("status", "submitted");
                message.put("statusText", "已提交发送，状态未回传");
                changed = true;
            } else if ("submitted".equals(message.optString("status"))
                    && now - message.optLong("receivedAt") > 2 * 60 * 1000L
                    && "已提交发送".equals(message.optString("statusText"))) {
                message.put("statusText", "已提交发送，未收到系统回执");
                changed = true;
            }
        }
        if (changed) writeObjects(context, messages);
        messages.sort((a, b) -> Long.compare(b.optLong("receivedAt"), a.optLong("receivedAt")));
        return messages;
    }

    private static boolean hasDuplicateIncoming(List<JSONObject> messages, SmsPayload payload) {
        for (JSONObject message : messages) {
            if (!"incoming".equals(message.optString("direction"))) continue;
            if (!sameIncoming(message.optString("sender"), message.optString("body"), message.optLong("receivedAt"),
                    payload.sender, payload.body, payload.receivedAt)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean hasDuplicateIncoming(List<JSONObject> messages, JSONObject candidate) {
        for (JSONObject message : messages) {
            if (!"incoming".equals(message.optString("direction"))) continue;
            if (!sameIncoming(message.optString("sender"), message.optString("body"), message.optLong("receivedAt"),
                    candidate.optString("sender"), candidate.optString("body"), candidate.optLong("receivedAt"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean sameIncoming(String leftSender, String leftBody, long leftDate,
                                        String rightSender, String rightBody, long rightDate) {
        return normalizeSender(leftSender).equals(normalizeSender(rightSender))
                && String.valueOf(leftBody).equals(String.valueOf(rightBody))
                && Math.abs(leftDate - rightDate) <= INCOMING_DUPLICATE_WINDOW_MS;
    }

    private static String normalizeSender(String sender) {
        if (sender == null) return "";
        String value = sender.trim();
        if (value.startsWith("+86") && value.length() > 3) return value.substring(3);
        return value;
    }

    private static JSONObject toJson(Context context, SmsPayload payload) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", payload.id);
        json.put("deviceId", Config.deviceId(context));
        json.put("sender", payload.sender);
        json.put("body", payload.body);
        json.put("receivedAt", payload.receivedAt);
        json.put("simSlot", payload.simSlot >= 0 ? payload.simSlot : JSONObject.NULL);
        json.put("syncedAt", System.currentTimeMillis());
        return json;
    }

    private static void addObject(Context context, JSONObject json) throws Exception {
        List<JSONObject> messages = readObjects(context);
        messages.add(json);
        messages.sort((a, b) -> Long.compare(b.optLong("receivedAt"), a.optLong("receivedAt")));
        if (messages.size() > MAX_MESSAGES) {
            messages = new ArrayList<>(messages.subList(0, MAX_MESSAGES));
        }
        writeObjects(context, messages);
    }

    private static List<JSONObject> readObjects(Context context) throws Exception {
        File file = file(context);
        if (!file.exists()) return new ArrayList<>();
        List<JSONObject> objects = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                objects.add(new JSONObject(line));
            }
        }
        return objects;
    }

    private static void writeObjects(Context context, List<JSONObject> objects) throws Exception {
        File file = file(context);
        try (FileWriter writer = new FileWriter(file, false)) {
            for (JSONObject object : objects) {
                writer.write(object.toString());
                writer.write('\n');
            }
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}
