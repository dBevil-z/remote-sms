package dev.dbevil.remotesms;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
            StringBuilder builder = new StringBuilder();
            File old = new File(context.getFilesDir(), FILE_NAME + ".1");
            if (old.exists()) builder.append(readTail(old, 12 * 1024));
            File current = file(context);
            if (current.exists()) builder.append(readTail(current, 48 * 1024));
            String logs = builder.toString().trim();
            return logs.isEmpty() ? "暂无日志" : logs;
        } catch (Exception ignored) {
            return "读取日志失败";
        }
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
