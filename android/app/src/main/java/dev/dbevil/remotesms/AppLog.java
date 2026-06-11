package dev.dbevil.remotesms;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.LinkedHashSet;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

final class AppLog {
    private static final String FILE_NAME = "remote-sms.log";
    private static final long MAX_BYTES = 160 * 1024L;

    private AppLog() {
    }

    static synchronized void add(Context context, String event, String detail) {
        try {
            File file = file(context);
            if (file.exists() && file.length() > MAX_BYTES) {
                File old = new File(context.getFilesDir(), FILE_NAME + ".1");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(time);
                writer.write("  ");
                writer.write(event == null ? "event" : event);
                if (detail != null && !detail.trim().isEmpty()) {
                    writer.write("  ");
                    writer.write(detail.replace('\n', ' '));
                }
                writer.write('\n');
            }
        } catch (Exception ignored) {
        }
    }

    static synchronized String recent(Context context) {
        try {
            String logs = readRecent(context).trim();
            return logs.isEmpty() ? "暂无日志" : logs;
        } catch (Exception ignored) {
            return "读取日志失败";
        }
    }

    static synchronized JSONObject snapshot(Context context) {
        JSONObject response = new JSONObject();
        try {
            String logs = readRecent(context).trim();
            JSONArray entries = new JSONArray();
            Set<String> types = new LinkedHashSet<>();
            if (!logs.isEmpty()) {
                String[] lines = logs.split("\\r?\\n");
                int index = 0;
                for (String line : lines) {
                    JSONObject entry = parseLine(index++, line);
                    String type = entry.optString("type", "");
                    if (!type.isEmpty()) types.add(type);
                    entries.put(entry);
                }
            }
            JSONArray typeArray = new JSONArray();
            for (String type : types) typeArray.put(type);
            response.put("logs", logs.isEmpty() ? "暂无日志" : logs);
            response.put("entries", entries);
            response.put("types", typeArray);
            response.put("total", entries.length());
            response.put("sampledAt", System.currentTimeMillis());
        } catch (Exception error) {
            try {
                response.put("logs", "读取日志失败");
                response.put("entries", new JSONArray());
                response.put("types", new JSONArray());
                response.put("total", 0);
                response.put("error", error.getMessage() == null ? error.toString() : error.getMessage());
            } catch (Exception ignored) {
            }
        }
        return response;
    }

    private static String readRecent(Context context) throws Exception {
        StringBuilder builder = new StringBuilder();
        File old = new File(context.getFilesDir(), FILE_NAME + ".1");
        if (old.exists()) builder.append(readTail(old, 12 * 1024));
        File current = file(context);
        if (current.exists()) builder.append(readTail(current, 48 * 1024));
        return builder.toString();
    }

    private static JSONObject parseLine(int index, String line) throws Exception {
        JSONObject entry = new JSONObject();
        String time = "";
        String type = "event";
        String detail = line == null ? "" : line;
        if (line != null && line.length() >= 22) {
            time = line.substring(0, 19).trim();
            String rest = line.substring(19).trim();
            int split = rest.indexOf("  ");
            if (split >= 0) {
                type = rest.substring(0, split).trim();
                detail = rest.substring(split).trim();
            } else if (!rest.isEmpty()) {
                int space = rest.indexOf(' ');
                if (space > 0) {
                    type = rest.substring(0, space).trim();
                    detail = rest.substring(space).trim();
                } else {
                    type = rest;
                    detail = "";
                }
            }
        }
        entry.put("id", index);
        entry.put("time", time);
        entry.put("type", type.isEmpty() ? "event" : type);
        entry.put("detail", detail);
        entry.put("line", line == null ? "" : line);
        return entry;
    }

    private static String readTail(File file, int maxBytes) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
        try {
            long start = Math.max(0, raf.length() - maxBytes);
            raf.seek(start);
            byte[] bytes = new byte[(int) (raf.length() - start)];
            raf.readFully(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            raf.close();
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}
