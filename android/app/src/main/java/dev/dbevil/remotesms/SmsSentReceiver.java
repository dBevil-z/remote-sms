package dev.dbevil.remotesms;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SmsSentReceiver extends BroadcastReceiver {
    private static final String TAG = "RemoteSms";

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean sent = SmsSendService.ACTION_SMS_SENT.equals(intent.getAction());
        boolean delivered = SmsSendService.ACTION_SMS_DELIVERED.equals(intent.getAction());
        if (!sent && !delivered) {
            Log.i(TAG, "sms callback ignored action=" + intent.getAction());
            return;
        }
        String messageId = intent.getStringExtra(SmsSendService.EXTRA_MESSAGE_ID);
        if (messageId == null || messageId.isEmpty()) {
            Log.w(TAG, "sms callback missing message id action=" + intent.getAction());
            return;
        }

        int resultCode = getResultCode();
        int errorCode = intent.getIntExtra("errorCode", -1);
        int partIndex = intent.getIntExtra(SmsSendService.EXTRA_PART_INDEX, -1);
        int partCount = intent.getIntExtra(SmsSendService.EXTRA_PART_COUNT, -1);
        Log.i(TAG, "sms callback action=" + intent.getAction()
                + " id=" + messageId
                + " resultCode=" + resultCode
                + " errorCode=" + errorCode
                + " part=" + partIndex + "/" + partCount
                + " text=" + (delivered ? SmsSendService.deliveryText(resultCode) : SmsSendService.resultText(resultCode)));
        if (delivered) {
            String status = resultCode == Activity.RESULT_OK ? "delivered" : "delivery_failed";
            LocalMessageStore.updateStatus(context.getApplicationContext(), messageId, status, SmsSendService.deliveryText(resultCode));
            return;
        }
        String status = resultCode == Activity.RESULT_OK ? "sent" : "failed";
        LocalMessageStore.updateStatus(context.getApplicationContext(), messageId, status, SmsSendService.resultText(resultCode));
    }
}
