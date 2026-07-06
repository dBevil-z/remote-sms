package dev.dbevil.remotesms;

import android.content.Context;

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

    private EmailForwarder() {
    }

    static void forwardIncoming(Context context, SmsPayload payload, String source) {
        final Context appContext = context.getApplicationContext();
        final Config.EmailConfig config = Config.emailConfig(appContext);
        if (!config.isConfigured()) {
            return;
        }
        EXECUTOR.execute(() -> sendWithRetry(appContext, payload, source, config));
    }

    private static void sendWithRetry(Context context, SmsPayload payload, String source, Config.EmailConfig config) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                send(config, payload);
                AppLog.add(context, "email", "邮件已发送 attempt=" + attempt
                        + " source=" + clean(source)
                        + " to=" + clean(config.recipientEmail)
                        + " smtp=" + clean(config.smtpHost)
                        + " smsSender=" + mask(payload.sender));
                return;
            } catch (Exception error) {
                lastError = error;
                if (attempt < 2) {
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        lastError = interrupted;
                        break;
                    }
                }
            }
        }
        AppLog.add(context, "email", "邮件发送失败 source=" + clean(source)
                + " to=" + clean(config.recipientEmail)
                + " smtp=" + clean(config.smtpHost)
                + " error=" + errorText(lastError));
    }

    private static void send(Config.EmailConfig config, SmsPayload payload) throws Exception {
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
        message.setSubject(subjectFor(payload), "UTF-8");
        message.setSentDate(new Date());
        message.setText(payload.body, "UTF-8");

        Transport.send(message);
    }

    private static String subjectFor(SmsPayload payload) {
        String sender = payload.sender == null || payload.sender.trim().isEmpty() ? "未知号码" : payload.sender.trim();
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
}
