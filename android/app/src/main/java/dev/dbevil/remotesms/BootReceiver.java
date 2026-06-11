package dev.dbevil.remotesms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || SmsSyncService.ACTION_WATCHDOG.equals(action)) {
            AppLog.add(context, "watchdog", "收到唤醒广播：" + action);
            EmbeddedHttpServer.start(context.getApplicationContext());
            SmsSyncService.start(context.getApplicationContext());
        }
    }
}
