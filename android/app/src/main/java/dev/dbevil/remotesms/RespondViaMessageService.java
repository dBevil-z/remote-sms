package dev.dbevil.remotesms;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class RespondViaMessageService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
