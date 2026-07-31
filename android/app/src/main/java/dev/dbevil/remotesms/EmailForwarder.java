package dev.dbevil.remotesms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

final class EmailForwarder {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final String ACTION_EMAIL_RETRY = "dev.dbevil.remotesms.EMAIL_RETRY";
    private static final String KEY_PENDING_EMAILS = "pending_email_jobs";
    private static final int MAX_SENDS_PER_DRAIN = 3;

    private EmailForwarder() {
    }

    static void forwardIncoming(Context context, SmsPayload payload, String source) {
        final Context appContext = context.getApplicationContext();
        final Config.EmailConfig config = Config.emailConfig(appContext);
        if (!config.isConfigured()) {
            return;
        }
        enqueue(appContext, EmailJob.incoming(payload, source));
    }

    static void forwardLowBattery(Context context, int level, boolean charging) {
        final Context appContext = context.getApplicationContext();
        final Config.EmailConfig config = Config.emailConfig(appContext);
        if (!config.isConfigured()) {
            return;
        }
        enqueue(appContext, EmailJob.lowBattery(level, charging));
    }

    static void drainPending(Context context) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> drainDue(appContext));
    }

    private static void enqueue(Context context, EmailJob job) {
        synchronized (EmailForwarder.class) {
            JSONArray queue = readQueue(context);
            queue.put(job.toJson());
            writeQueue(context, queue);
        }
        drainPending(context);
    }

    private static void drainDue(Context context) {
        Config.EmailConfig config = Config.emailConfig(context);
        if (!config.isConfigured()) return;

        boolean hasRemaining;
        long retryAt;
        synchronized (EmailForwarder.class) {
            long now = System.currentTimeMillis();
            JSONArray queue = readQueue(context);
            JSONArray remaining = new JSONArray();
            int sentOrAttempted = 0;
            long nextRetryAt = 0;

            for (int i = 0; i < queue.length(); i++) {
                JSONObject json = queue.optJSONObject(i);
                if (json == null) continue;
                EmailJob job = EmailJob.fromJson(json);
                if (job.nextAttemptAt > now || sentOrAttempted >= MAX_SENDS_PER_DRAIN) {
                    remaining.put(job.toJson());
                    nextRetryAt = earliest(nextRetryAt, job.nextAttemptAt);
                    continue;
                }
                sentOrAttempted++;
                try {
                    send(config, job);
                    AppLog.add(context, "email", "邮件已发送 attempts=" + (job.attempts + 1)
                            + " source=" + clean(job.source)
                            + " to=" + clean(config.recipientEmail)
                            + " smtp=" + clean(config.smtpHost)
                            + (job.sender.isEmpty() ? "" : " smsSender=" + mask(job.sender)));
                } catch (Exception error) {
                    job.attempts++;
                    job.nextAttemptAt = now + EmailRetryPolicy.nextDelayMs(job.attempts);
                    remaining.put(job.toJson());
                    nextRetryAt = earliest(nextRetryAt, job.nextAttemptAt);
                    AppLog.add(context, "email", "邮件发送失败，已加入重试队列 attempts=" + job.attempts
                            + " source=" + clean(job.source)
                            + " to=" + clean(config.recipientEmail)
                            + " smtp=" + clean(config.smtpHost)
                            + " nextRetryAt=" + job.nextAttemptAt
                            + " error=" + errorText(error));
                }
            }
            writeQueue(context, remaining);
            hasRemaining = remaining.length() > 0;
            retryAt = nextRetryAt <= 0 ? now + EmailRetryPolicy.nextDelayMs(1) : nextRetryAt;
        }
        if (hasRemaining) scheduleRetry(context, retryAt);
    }

    private static void send(Config.EmailConfig config, EmailJob job) throws Exception {
        final String username = config.effectiveUsername();
        Properties properties = new Properties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.host", config.smtpHost);
        properties.put("mail.smtp.port", String.valueOf(config.portValue()));
        properties.put("mail.smtp.connectiontimeout", "15000");
        properties.put("mail.smtp.timeout", "20000");
        properties.put("mail.smtp.writetimeout", "20000");
        properties.put("mail.smtp.auth", String.valueOf(!username.isEmpty() || !config.smtpPassword.isEmpty()));
        if ("starttls".equals(config.smtpSecurity)) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        } else if ("ssl".equals(config.smtpSecurity)) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.ssl.trust", config.smtpHost);
        }

        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, config.smtpPassword);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.senderEmail));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(config.recipientEmail));
        message.setSubject(job.subject, "UTF-8");
        message.setSentDate(new Date());
        message.setText(job.body, "UTF-8");

        Transport.send(message);
    }

    private static void scheduleRetry(Context context, long triggerAt) {
        try {
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) return;
            Intent intent = new Intent(context, BootReceiver.class);
            intent.setAction(ACTION_EMAIL_RETRY);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 8603, intent, flags);
            long at = Math.max(System.currentTimeMillis() + 5_000L, triggerAt);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent);
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, at, pendingIntent);
            }
        } catch (Exception error) {
            AppLog.add(context, "email", "邮件重试闹钟设置失败：" + errorText(error));
        }
    }

    private static JSONArray readQueue(Context context) {
        try {
            return new JSONArray(Config.prefs(context).getString(KEY_PENDING_EMAILS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void writeQueue(Context context, JSONArray queue) {
        Config.prefs(context).edit().putString(KEY_PENDING_EMAILS, queue.toString()).apply();
    }

    private static long earliest(long current, long candidate) {
        if (candidate <= 0) return current;
        if (current <= 0) return candidate;
        return Math.min(current, candidate);
    }

    private static String subjectForSender(String senderValue) {
        String sender = senderValue == null || senderValue.trim().isEmpty() ? "未知号码" : senderValue.trim();
        return "短信转发 - " + sender;
    }

    private static String errorText(Exception error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return clean(message == null || message.trim().isEmpty() ? error.toString() : message);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) return String.valueOf(value);
        return "***" + value.substring(value.length() - 4);
    }

    private static final class EmailJob {
        final String id;
        final String source;
        final String sender;
        final String subject;
        final String body;
        final long createdAt;
        int attempts;
        long nextAttemptAt;

        private EmailJob(String id, String source, String sender, String subject, String body,
                         long createdAt, int attempts, long nextAttemptAt) {
            this.id = clean(id);
            this.source = clean(source);
            this.sender = clean(sender);
            this.subject = clean(subject);
            this.body = body == null ? "" : body;
            this.createdAt = createdAt;
            this.attempts = Math.max(0, attempts);
            this.nextAttemptAt = Math.max(0, nextAttemptAt);
        }

        static EmailJob incoming(SmsPayload payload, String source) {
            String sender = payload.sender == null ? "" : payload.sender;
            String body = payload.body == null ? "" : payload.body;
            String id = "sms:" + payload.id;
            return new EmailJob(id, source, sender, subjectForSender(sender), body,
                    System.currentTimeMillis(), 0, 0);
        }

        static EmailJob lowBattery(int level, boolean charging) {
            String body = "设备电量低于 20%。\n\n当前电量：" + level + "%\n充电状态："
                    + (charging ? "充电中" : "未充电") + "\n时间：" + new Date();
            return new EmailJob("battery:" + System.currentTimeMillis(), "battery", "",
                    "短信接收助手电量低：" + level + "%", body, System.currentTimeMillis(), 0, 0);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("source", source);
                json.put("sender", sender);
                json.put("subject", subject);
                json.put("body", body);
                json.put("createdAt", createdAt);
                json.put("attempts", attempts);
                json.put("nextAttemptAt", nextAttemptAt);
            } catch (Exception ignored) {
            }
            return json;
        }

        static EmailJob fromJson(JSONObject json) {
            return new EmailJob(
                    json.optString("id", ""),
                    json.optString("source", ""),
                    json.optString("sender", ""),
                    json.optString("subject", "短信接收助手通知"),
                    json.optString("body", ""),
                    json.optLong("createdAt", System.currentTimeMillis()),
                    json.optInt("attempts", 0),
                    json.optLong("nextAttemptAt", 0)
            );
        }
    }
}
