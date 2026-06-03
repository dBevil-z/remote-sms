package dev.dbevil.remotesms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // MMS is not shown in the remote page yet. This receiver lets Android accept
        // the app as a default SMS application on older systems.
    }
}
