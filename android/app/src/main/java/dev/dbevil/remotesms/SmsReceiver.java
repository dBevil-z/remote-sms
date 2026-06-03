package dev.dbevil.remotesms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(action)
                && !"android.provider.Telephony.SMS_DELIVER".equals(action)) {
            return;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) return;

        Object[] pdus = (Object[]) extras.get("pdus");
        if (pdus == null || pdus.length == 0) return;

        String format = extras.getString("format");
        StringBuilder body = new StringBuilder();
        String sender = "";
        long receivedAt = System.currentTimeMillis();
        int simSlot = extras.containsKey("slot") ? extras.getInt("slot") : -1;

        for (Object pdu : pdus) {
            SmsMessage message;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                message = SmsMessage.createFromPdu((byte[]) pdu, format);
            } else {
                message = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (message == null) continue;
            if (sender.isEmpty()) sender = message.getDisplayOriginatingAddress();
            receivedAt = message.getTimestampMillis();
            body.append(message.getMessageBody());
        }

        if (body.length() == 0) return;

        SmsPayload payload = new SmsPayload(sender, body.toString(), receivedAt, simSlot);
        LocalMessageStore.add(context.getApplicationContext(), payload);
    }
}
