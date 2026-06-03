package dev.dbevil.remotesms;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;
import android.util.Log;

public class SmsSyncService extends Service {
    private static final String TAG = "RemoteSms";
    private static final String CHANNEL_ID = "remote_sms_sync";
    private static final int NOTIFICATION_ID = 8601;
    private static final long POLL_INTERVAL_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            syncRecentInbox();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, SmsSyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EmbeddedHttpServer.start(this);
        startForeground(NOTIFICATION_ID, notification());
        handler.post(poll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        syncRecentInbox();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
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
            return;
        }

        if (newest > lastDate) {
            Config.prefs(this).edit().putLong(Config.KEY_LAST_SMS_DATE, newest).apply();
        }
    }
}
