package dev.dbevil.remotesms;

import android.app.Activity;
import android.os.Bundle;

public class DebugSendActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SmsPayload payload = new SmsPayload(
                "短信接收助手测试",
                "调试入口写入：" + Config.deviceId(this),
                System.currentTimeMillis(),
                -1
        );
        LocalMessageStore.add(getApplicationContext(), payload);
        finish();
    }
}
