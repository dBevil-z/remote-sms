package dev.dbevil.remotesms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_SMS_PERMISSIONS = 1001;

    private TextView status;
    private boolean bridgeChecked;
    private boolean bridgeAvailable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildView());

        updateStatus();
        if (hasSmsPermissions()) {
            SmsSyncService.start(this);
        }
        refreshBridgeStatus();
    }

    private View buildView() {
        int padding = dp(18);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(238, 242, 241));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("短信接收助手");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(28, 42, 45));
        root.addView(title, matchWrap(0, 0, 0, 4));

        TextView subtitle = new TextView(this);
        subtitle.setText("本机接收短信，并通过 8787 端口提供网页查看");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.rgb(91, 106, 110));
        root.addView(subtitle, matchWrap(0, 0, 0, 16));

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.rgb(35, 48, 51));
        status.setLineSpacing(dp(2), 1.0f);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        status.setBackground(cardBackground());
        root.addView(status, matchWrap(0, 0, 0, 16));

        LinearLayout primaryActions = new LinearLayout(this);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(primaryActions, matchWrap(0, 0, 0, 10));

        Button start = actionButton("启动服务", true);
        start.setOnClickListener(v -> {
            SmsSyncService.start(this);
            updateStatus();
            Toast.makeText(this, "短信服务已启动", Toast.LENGTH_SHORT).show();
        });
        primaryActions.addView(start, rowButtonParams(0, dp(5)));

        Button settings = actionButton("访问设置", false);
        settings.setOnClickListener(v -> showConfigDialog());
        primaryActions.addView(settings, rowButtonParams(dp(5), 0));

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(secondaryActions, matchWrap(0, 0, 0, 10));

        Button permissions = actionButton("短信权限", false);
        permissions.setOnClickListener(v -> requestSmsPermissions());
        secondaryActions.addView(permissions, rowButtonParams(0, dp(5)));

        Button battery = actionButton("省电设置", false);
        battery.setOnClickListener(v -> openBatterySettings());
        secondaryActions.addView(battery, rowButtonParams(dp(5), 0));

        Button defaultSms = actionButton("设为默认短信应用", false);
        defaultSms.setOnClickListener(v -> requestDefaultSmsApp());
        root.addView(defaultSms, matchWrap(0, 0, 0, 10));

        Button bridge = actionButton("检查发送桥", false);
        bridge.setOnClickListener(v -> refreshBridgeStatus());
        root.addView(bridge, matchWrap(0, 0, 0, 10));

        Button logs = actionButton("查看日志", false);
        logs.setOnClickListener(v -> showLogsDialog());
        root.addView(logs, matchWrap(0, 0, 0, 10));

        Button test = actionButton("写入测试短信", false);
        test.setOnClickListener(v -> {
            SmsPayload payload = new SmsPayload(
                    "短信接收助手测试",
                    "来自本机的测试短信：" + Config.deviceId(this),
                    System.currentTimeMillis(),
                    -1
            );
            LocalMessageStore.add(getApplicationContext(), payload);
            Toast.makeText(this, "测试短信已保存", Toast.LENGTH_SHORT).show();
        });
        root.addView(test, matchWrap(0, 0, 0, 0));

        return scrollView;
    }

    private LinearLayout.LayoutParams matchWrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams rowButtonParams(int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(left, 0, right, 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(218, 255, 255, 255));
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), Color.argb(120, 255, 255, 255));
        return drawable;
    }

    private GradientDrawable buttonBackground(boolean primary) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(12));
        drawable.setColor(primary ? Color.rgb(23, 107, 135) : Color.argb(228, 255, 255, 255));
        drawable.setStroke(dp(1), primary ? Color.rgb(23, 107, 135) : Color.argb(160, 208, 216, 217));
        return drawable;
    }

    private Button actionButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(32, 52, 56));
        button.setBackground(buttonBackground(primary));
        return button;
    }

    private void showConfigDialog() {
        Config.FrpConfig frp = Config.frpConfig(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), 0, dp(4), 0);

        EditText token = field("网页访问密码", Config.token(this), true);
        EditText publicUrl = field("公网访问地址，例如 http://example.com:65439", frp.publicUrl, false);
        EditText serverAddr = field("frp 服务器地址", frp.serverAddr, false);
        EditText serverPort = field("frp 服务器端口", frp.serverPort, false);
        serverPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText remotePort = field("frp 远端端口", frp.remotePort, false);
        remotePort.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText authToken = field("frp 认证 token", frp.authToken, true);

        form.addView(label("网页鉴权"));
        form.addView(token, matchWrap(0, 0, 0, 10));
        form.addView(label("frp 配置"));
        form.addView(publicUrl, matchWrap(0, 0, 0, 8));
        form.addView(serverAddr, matchWrap(0, 0, 0, 8));
        form.addView(serverPort, matchWrap(0, 0, 0, 8));
        form.addView(remotePort, matchWrap(0, 0, 0, 8));
        form.addView(authToken, matchWrap(0, 0, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        new AlertDialog.Builder(this)
                .setTitle("访问设置")
                .setMessage("这些信息只保存在手机本机，不写入仓库。")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = token.getText().toString().trim();
                    if (value.length() < 8) {
                        Toast.makeText(this, "密码至少需要 8 位", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Config.setToken(this, value);
                    Config.setFrpConfig(this, new Config.FrpConfig(
                            publicUrl.getText().toString(),
                            serverAddr.getText().toString(),
                            serverPort.getText().toString(),
                            authToken.getText().toString(),
                            remotePort.getText().toString()
                    ));
                    AppLog.add(this, "config", "访问和 frp 配置已保存 remotePort=" + remotePort.getText().toString().trim());
                    SmsSyncService.start(this);
                    updateStatus();
                    Toast.makeText(this, "访问和 frp 配置已保存", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(91, 106, 110));
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        return label;
    }

    private EditText field(String hint, String value, boolean password) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        return input;
    }

    private void requestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE
            }, REQUEST_SMS_PERMISSIONS);
        }
    }

    private void showLogsDialog() {
        TextView content = new TextView(this);
        content.setText(AppLog.recent(this));
        content.setTextSize(12);
        content.setTextColor(Color.rgb(35, 48, 51));
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setPositiveButton("刷新", (dialog, which) -> showLogsDialog())
                .show();
    }

    private boolean hasSmsPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void updateStatus() {
        Config.FrpConfig frp = Config.frpConfig(this);
        String publicUrl = frp.publicUrl.isEmpty() ? "未配置" : frp.publicUrl;
        String frpStatus = frp.serverAddr.isEmpty()
                ? "未配置"
                : frp.serverAddr + (frp.remotePort.isEmpty() ? "" : ":" + frp.remotePort);
        status.setText("设备 ID：" + Config.deviceId(this)
                + "\n短信权限：" + (hasSmsPermissions() ? "已授权，可以接收短信" : "未授权，请先授权")
                + "\n默认短信应用：" + (isDefaultSmsApp() ? "是" : "否，发送可能被系统拦截")
                + "\n网页服务：打开软件后自动启动"
                + "\n发送桥：" + bridgeStatusText()
                + "\nfrp 配置：" + frpStatus
                + "\n网页端口：8787"
                + "\n本地记录：" + LocalMessageStore.list(this, 500).length() + " 条"
                + "\n公开入口：" + publicUrl);
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean isDefaultSmsApp() {
        String current = Settings.Secure.getString(getContentResolver(), "sms_default_application");
        return getPackageName().equals(current);
    }

    private void requestDefaultSmsApp() {
        if (isDefaultSmsApp()) {
            Toast.makeText(this, "已经是默认短信应用", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent("android.provider.Telephony.ACTION_CHANGE_DEFAULT");
        intent.putExtra("package", getPackageName());
        try {
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "无法打开默认短信应用设置", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private String bridgeStatusText() {
        if (!SmsSendService.requiresShellBridge()) return "不需要";
        if (!bridgeChecked) return "正在检查";
        return bridgeAvailable ? "已连接，可以发送短信" : "未启动，发送短信会失败";
    }

    private void refreshBridgeStatus() {
        bridgeChecked = false;
        updateStatus();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean available = SmsSendService.isShellBridgeAvailable();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bridgeChecked = true;
                        bridgeAvailable = available;
                        updateStatus();
                    }
                });
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateStatus();
        if (hasSmsPermissions()) {
            SmsSyncService.start(this);
        }
    }
}
