package dev.dbevil.remotesms;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

public class ComposeSmsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Toast.makeText(this, "请通过网页发送短信", Toast.LENGTH_SHORT).show();
        finish();
    }
}
