package dev.dbevil.remotesms;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmsSyncService extends Service {
    private static final String TAG = "RemoteSms";
    private static final String CHANNEL_ID = "remote_sms_sync";
    private static final int NOTIFICATION_ID = 8601;
    private static final long POLL_INTERVAL_MS = 10 * 60_000;
    private static final long WATCHDOG_INTERVAL_MS = 30 * 60_000;
    private static final long FRP_CHECK_INTERVAL_MS = 15 * 60_000;
    static final String ACTION_WATCHDOG = "dev.dbevil.remotesms.WATCHDOG";
    static final String ACTION_SMS_RECEIVED_CHECK = "dev.dbevil.remotesms.SMS_RECEIVED_CHECK";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService watchdogExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean watchdogRunning;
    private Boolean lastNetworkOk;
    private Boolean lastLocalWebOk;
    private Boolean lastFrpOk;
    private boolean frpMissingLogged;
    private long lastFrpCheckAt;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            requestWatchdog(false);
            syncRecentInbox();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, SmsSyncService.class);
        start(context, intent);
    }

    static void startAfterSms(Context context) {
        Intent intent = new Intent(context, SmsSyncService.class);
        intent.setAction(ACTION_SMS_RECEIVED_CHECK);
        start(context, intent);
    }

    private static void start(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        scheduleWatchdog(context);
    }

    static void scheduleWatchdog(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent pendingIntent = watchdogIntent(app);
        long triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } else {
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private static PendingIntent watchdogIntent(Context context) {
        Intent intent = new Intent(context, BootReceiver.class);
        intent.setAction(ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, 8602, intent, flags);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EmbeddedHttpServer.start(this);
        scheduleWatchdog(this);
        AppLog.add(this, "service", "短信服务启动，Web 端口 8787");
        startForeground(NOTIFICATION_ID, notification());
        handler.post(poll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleWatchdog(this);
        boolean fromSms = intent != null && ACTION_SMS_RECEIVED_CHECK.equals(intent.getAction());
        if (fromSms) {
            AppLog.add(this, "watchdog", "收到短信后触发服务健康检查");
        }
        requestWatchdog(fromSms);
        if (!fromSms) {
            syncRecentInbox();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        scheduleWatchdog(this);
        AppLog.add(this, "service", "短信服务销毁，等待系统自动拉起");
        watchdogExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "短信接收服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        Intent activity = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activity,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle("短信接收助手正在运行")
                .setContentText("正在监听短信并提供网页访问")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void requestWatchdog(boolean immediate) {
        if (watchdogRunning) return;
        watchdogRunning = true;
        watchdogExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runWatchdog(immediate);
                } finally {
                    watchdogRunning = false;
                }
            }
        });
    }

    private void runWatchdog(boolean immediate) {
        EmbeddedHttpServer.start(this);
        boolean networkOk = isNetworkConnected();
        if (lastNetworkOk == null || lastNetworkOk != networkOk) {
            AppLog.add(this, "watchdog", networkOk ? "网络已连接" : "网络不可用，frp 入口可能无法访问");
            lastNetworkOk = networkOk;
        }

        boolean localWebOk = checkHttpWithRetries("http://127.0.0.1:8787/health", 1500, 3, 350);
        if (!localWebOk) {
            AppLog.add(this, "watchdog", "本机 Web 健康检查失败，尝试重启 8787 服务");
            EmbeddedHttpServer.restart(this);
            localWebOk = checkHttpWithRetries("http://127.0.0.1:8787/health", 1800, 3, 500);
        }
        if (lastLocalWebOk == null || lastLocalWebOk != localWebOk) {
            AppLog.add(this, "watchdog", localWebOk ? "本机 Web 服务正常" : "本机 Web 服务仍不可用");
            lastLocalWebOk = localWebOk;
        }

        long now = System.currentTimeMillis();
        if (immediate || now - lastFrpCheckAt >= FRP_CHECK_INTERVAL_MS) {
            lastFrpCheckAt = now;
            checkFrpEntry();
        }
    }

    private void checkFrpEntry() {
        Config.FrpConfig frp = Config.frpConfig(this);
        if (frp.publicUrl.isEmpty()) {
            if (!frpMissingLogged) {
                AppLog.add(this, "frp", "未配置公网入口，跳过 frp 健康检查");
                frpMissingLogged = true;
            }
            lastFrpOk = null;
            return;
        }
        frpMissingLogged = false;
        String healthUrl = healthUrl(frp.publicUrl);
        boolean ok = checkHttp(healthUrl, 4500);
        if (lastFrpOk == null || lastFrpOk != ok) {
            AppLog.add(this, "frp", ok
                    ? "公网入口已恢复 " + safeUrl(frp.publicUrl)
                    : "公网入口检测失败，等待下次重试 " + safeUrl(frp.publicUrl));
            lastFrpOk = ok;
        }
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean checkHttp(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception error) {
            Log.w(TAG, "health check failed " + url, error);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean checkHttpWithRetries(String url, int timeoutMs, int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            if (checkHttp(url, timeoutMs)) return true;
            sleep(delayMs);
        }
        return false;
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private String healthUrl(String publicUrl) {
        String trimmed = publicUrl.trim();
        int query = trimmed.indexOf('?');
        if (query >= 0) trimmed = trimmed.substring(0, query);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/health";
    }

    private String safeUrl(String publicUrl) {
        int query = publicUrl.indexOf('?');
        return query >= 0 ? publicUrl.substring(0, query) : publicUrl;
    }

    private void syncRecentInbox() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        long lastDate = Config.prefs(this).getLong(Config.KEY_LAST_SMS_DATE, 0);
        Uri uri = Telephony.Sms.Inbox.CONTENT_URI;
        String[] projection = {
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
        };
        String selection = lastDate > 0 ? Telephony.Sms.DATE + " > ?" : null;
        String[] args = lastDate > 0 ? new String[]{String.valueOf(lastDate)} : null;
        String sort = Telephony.Sms.DATE + " ASC";
        long newest = lastDate;

        try (Cursor cursor = getContentResolver().query(uri, projection, selection, args, sort)) {
            if (cursor == null) return;
            int senderIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS);
            int bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY);
            int dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE);

            while (cursor.moveToNext()) {
                String sender = cursor.getString(senderIndex);
                String body = cursor.getString(bodyIndex);
                long date = cursor.getLong(dateIndex);
                if (date > newest) newest = date;
                LocalMessageStore.add(this, new SmsPayload(sender, body, date, -1));
            }
        } catch (Exception error) {
            Log.w(TAG, "inbox backfill failed", error);
            AppLog.add(this, "sync", "收件箱同步失败：" + error);
            return;
        }

        if (newest > lastDate) {
            Config.prefs(this).edit().putLong(Config.KEY_LAST_SMS_DATE, newest).apply();
            AppLog.add(this, "sync", "收件箱同步完成，最新时间 " + newest);
        }
    }
}
