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

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_SMS_PERMISSIONS = 1001;
    private static final int LOG_PAGE_SIZE = 24;

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
        bridge.setOnClickListener(v -> {
            refreshBridgeStatus();
            showBridgeHelpDialog();
        });
        root.addView(bridge, matchWrap(0, 0, 0, 10));

        Button frpRestart = actionButton("重启 frp 隧道", false);
        frpRestart.setOnClickListener(v -> {
            FrpClient.restart(this, "手动重启");
            updateStatus();
            Toast.makeText(this, "正在重启 frp 隧道", Toast.LENGTH_SHORT).show();
        });
        root.addView(frpRestart, matchWrap(0, 0, 0, 10));

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
            boolean added = LocalMessageStore.add(getApplicationContext(), payload);
            if (added) {
                EmailForwarder.forwardIncoming(getApplicationContext(), payload, "manual-test");
            }
            Toast.makeText(this, added ? "测试短信已保存，并尝试转发邮件" : "测试短信重复，未重新写入", Toast.LENGTH_SHORT).show();
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
        Config.EmailConfig email = Config.emailConfig(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        content.setBackground(dialogBackground());

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        hero.setBackground(heroBackground());

        TextView title = new TextView(this);
        title.setText("访问设置");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        hero.addView(title, matchWrap(0, 0, 0, 4));

        TextView intro = new TextView(this);
        intro.setText("统一管理网页密码、公开入口和 frp 隧道。保存后会自动重启相关服务。");
        intro.setTextSize(13);
        intro.setTextColor(Color.argb(220, 255, 255, 255));
        intro.setLineSpacing(dp(2), 1.0f);
        hero.addView(intro, matchWrap(0, 0, 0, 12));

        LinearLayout heroTags = new LinearLayout(this);
        heroTags.setOrientation(LinearLayout.HORIZONTAL);
        heroTags.addView(infoChip("仅保存在本机"), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tagParams.leftMargin = dp(8);
        heroTags.addView(infoChip("自动拉起 frp"), tagParams);
        hero.addView(heroTags, matchWrap(0, 0, 0, 0));
        content.addView(hero, matchWrap(0, 0, 0, 12));

        EditText token = settingInput("请输入访问密码", Config.token(this), true);
        EditText publicUrl = settingInput("http://example.com:65439", frp.publicUrl, false);
        EditText serverAddr = settingInput("frp 服务器地址，可留空", frp.serverAddr, false);
        EditText serverPort = settingInput("frps 端口", frp.serverPort, false);
        serverPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText remotePort = settingInput("映射到 8787 的公网端口", frp.remotePort, false);
        remotePort.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText authToken = settingInput("frp 认证 token，可留空", frp.authToken, true);
        EditText smtpHost = settingInput("smtp.qq.com", email.smtpHost, false);
        EditText smtpPort = settingInput("465 / 587 / 25", email.smtpPort, false);
        smtpPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText smtpSecurity = settingInput("ssl / starttls / none", email.smtpSecurity, false);
        EditText smtpUsername = settingInput("SMTP 用户名，可留空默认发件邮箱", email.smtpUsername, false);
        EditText smtpPassword = settingInput("SMTP 密码或授权码", email.smtpPassword, true);
        EditText senderEmail = settingInput("发件邮箱", email.senderEmail, false);
        EditText recipientEmail = settingInput("收件邮箱", email.recipientEmail, false);

        LinearLayout authCard = settingCard("网页鉴权", "控制远程页面和 API 的访问");
        authCard.addView(settingItem("访问密码", "浏览器访问短信页面时使用，至少 8 位。", token), matchWrap(0, 0, 0, 0));
        content.addView(authCard, matchWrap(0, 0, 0, 10));

        LinearLayout frpCard = settingCard("frp 穿透", "保存公网入口和 frp 连接信息，便于状态展示和排查");
        frpCard.addView(settingItem("公网入口", "浏览器实际访问的地址，用于远程状态检测。", publicUrl), matchWrap(0, 0, 0, 10));
        frpCard.addView(settingItem("frp 服务器", "仅用于记录和排查，可留空。", serverAddr), matchWrap(0, 0, 0, 10));
        frpCard.addView(settingPairRow(
                "frp 服务端口", "frps 服务端口。", serverPort,
                "frp 远端端口", "映射到手机本机 8787 的公网端口。", remotePort
        ), matchWrap(0, 0, 0, 10));
        frpCard.addView(settingItem("frp 认证 token", "如 frp 服务需要认证可填写。", authToken), matchWrap(0, 0, 0, 0));
        content.addView(frpCard, matchWrap(0, 0, 0, 0));

        LinearLayout emailCard = settingCard("邮件转发", "收到短信后通过 SMTP 转发邮件，邮件正文保持和短信完全一致");
        emailCard.addView(settingItem("SMTP 主机", "例如 smtp.qq.com、smtp.gmail.com。", smtpHost), matchWrap(0, 0, 0, 10));
        emailCard.addView(settingPairRow(
                "SMTP 端口", "SSL 常见 465，STARTTLS 常见 587。", smtpPort,
                "安全方式", "填写 ssl、starttls 或 none。", smtpSecurity
        ), matchWrap(0, 0, 0, 10));
        emailCard.addView(settingItem("SMTP 用户名", "可留空，留空时默认使用发件邮箱。", smtpUsername), matchWrap(0, 0, 0, 10));
        emailCard.addView(settingItem("SMTP 密码/授权码", "QQ 邮箱请填写 SMTP 授权码。", smtpPassword), matchWrap(0, 0, 0, 10));
        emailCard.addView(settingPairRow(
                "发件邮箱", "用于邮件 From。", senderEmail,
                "收件邮箱", "短信要转发到的邮箱。", recipientEmail
        ), matchWrap(0, 0, 0, 0));
        content.addView(emailCard, matchWrap(0, 10, 0, 0));

        TextView summary = new TextView(this);
        summary.setText("当前公开入口：" + (frp.publicUrl.isEmpty() ? "未配置" : frp.publicUrl)
                + "\n目标映射：127.0.0.1:8787 -> " + (frp.remotePort.isEmpty() ? "未配置端口" : frp.remotePort)
                + "\n邮件转发：" + (email.isConfigured() ? (email.senderEmail + " -> " + email.recipientEmail) : "未完整配置")
                + "\n保存后会自动刷新网页服务和 frp 隧道状态。");
        summary.setTextSize(12);
        summary.setTextColor(Color.rgb(91, 106, 110));
        summary.setLineSpacing(dp(2), 1.0f);
        summary.setPadding(dp(12), dp(12), dp(12), dp(12));
        summary.setBackground(inputBackground());
        content.addView(summary, matchWrap(0, 12, 0, 0));

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setPadding(0, dp(14), 0, 0);

        Button cancel = actionButton("取消", false);
        Button save = actionButton("保存并应用", true);
        actionBar.addView(cancel, rowButtonParams(0, dp(6)));
        actionBar.addView(save, rowButtonParams(dp(6), 0));
        content.addView(actionBar, matchWrap(0, 0, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
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
            Config.setEmailConfig(this, new Config.EmailConfig(
                    smtpHost.getText().toString(),
                    smtpPort.getText().toString(),
                    smtpSecurity.getText().toString(),
                    smtpUsername.getText().toString(),
                    smtpPassword.getText().toString(),
                    senderEmail.getText().toString(),
                    recipientEmail.getText().toString()
            ));
            Config.EmailConfig updatedEmail = Config.emailConfig(this);
            AppLog.add(this, "config", "访问、frp 和邮件配置已保存 email="
                    + (updatedEmail.isConfigured() ? updatedEmail.senderEmail + "->" + updatedEmail.recipientEmail : "未完整配置"));
            FrpClient.restart(this, "配置已保存");
            SmsSyncService.start(this);
            updateStatus();
            Toast.makeText(this, "访问、frp 和邮件配置已保存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        dialog.show();
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

    private LinearLayout settingCard(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(cardBackground());

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.rgb(28, 42, 45));
        card.addView(titleView, matchWrap(0, 0, 0, 2));

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(Color.rgb(91, 106, 110));
        card.addView(subtitleView, matchWrap(0, 0, 0, 10));
        return card;
    }

    private EditText settingInput(String hint, String value, boolean password) {
        EditText input = field(hint, value, password);
        input.setTextSize(14);
        input.setBackground(inputBackground());
        return input;
    }

    private LinearLayout settingItem(String title, String helper, EditText input) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(13);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.rgb(35, 48, 51));
        item.addView(titleView, matchWrap(0, 0, 0, 3));

        TextView helperView = new TextView(this);
        helperView.setText(helper);
        helperView.setTextSize(12);
        helperView.setTextColor(Color.rgb(91, 106, 110));
        helperView.setLineSpacing(dp(1), 1.0f);
        item.addView(helperView, matchWrap(0, 0, 0, 6));

        item.addView(input, matchWrap(0, 0, 0, 0));
        return item;
    }

    private LinearLayout settingPairRow(String titleLeft, String helperLeft, EditText left,
                                        String titleRight, String helperRight, EditText right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        rightParams.leftMargin = dp(8);

        row.addView(settingItem(titleLeft, helperLeft, left), columnParams);
        row.addView(settingItem(titleRight, helperRight, right), rightParams);
        return row;
    }

    private TextView infoChip(String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12);
        chip.setTextColor(Color.WHITE);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(999));
        drawable.setColor(Color.argb(48, 255, 255, 255));
        drawable.setStroke(dp(1), Color.argb(70, 255, 255, 255));
        chip.setBackground(drawable);
        return chip;
    }

    private GradientDrawable inputBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(235, 255, 255, 255));
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), Color.argb(170, 208, 216, 217));
        return drawable;
    }

    private GradientDrawable dialogBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(241, 246, 245));
        drawable.setCornerRadius(dp(22));
        return drawable;
    }

    private GradientDrawable heroBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(23, 107, 135), Color.rgb(78, 171, 197)}
        );
        drawable.setCornerRadius(dp(18));
        return drawable;
    }

    private void requestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_SMS_PERMISSIONS);
        }
    }

    private void showLogsDialog() {
        showLogsDialog(0);
    }

    private void showLogsDialog(int page) {
        JSONObject snapshot = AppLog.snapshot(this);
        JSONArray entries = snapshot.optJSONArray("entries");
        int total = entries == null ? 0 : entries.length();
        int pages = Math.max((int) Math.ceil(total / (double) LOG_PAGE_SIZE), 1);
        int currentPage = Math.max(0, Math.min(page, pages - 1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView summary = new TextView(this);
        summary.setText("最新日志优先显示 · 第 " + (currentPage + 1) + " / " + pages + " 页 · 共 " + total + " 条");
        summary.setTextSize(12);
        summary.setTextColor(Color.rgb(91, 106, 110));
        content.addView(summary, matchWrap(0, 0, 0, 10));

        TextView body = new TextView(this);
        body.setText(logPageText(entries, currentPage));
        body.setTextSize(12);
        body.setTextColor(Color.rgb(35, 48, 51));
        body.setPadding(dp(10), dp(10), dp(10), dp(10));
        body.setBackground(inputBackground());
        body.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(content)
                .setNegativeButton("关闭", null)
                .setNeutralButton(currentPage > 0 ? "上一页" : "刷新", null)
                .setPositiveButton(currentPage < pages - 1 ? "下一页" : "刷新", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(91, 106, 110));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.rgb(23, 107, 135));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(23, 107, 135));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                if (currentPage > 0) {
                    showLogsDialog(currentPage - 1);
                } else {
                    showLogsDialog(currentPage);
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                dialog.dismiss();
                if (currentPage < pages - 1) {
                    showLogsDialog(currentPage + 1);
                } else {
                    showLogsDialog(currentPage);
                }
            });
        });
        dialog.show();
    }

    private String logPageText(JSONArray entries, int page) {
        if (entries == null || entries.length() == 0) return "暂无日志";
        StringBuilder builder = new StringBuilder();
        int start = Math.max(entries.length() - 1 - page * LOG_PAGE_SIZE, 0);
        int end = Math.max(start - LOG_PAGE_SIZE + 1, 0);
        for (int i = start; i >= end; i--) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            if (builder.length() > 0) builder.append("\n\n");
            String time = entry.optString("time", "");
            String type = entry.optString("type", "event");
            String detail = entry.optString("detail", entry.optString("line", ""));
            builder.append(time.isEmpty() ? "未知时间" : time)
                    .append("  ")
                    .append(type.isEmpty() ? "event" : type);
            if (!detail.trim().isEmpty()) {
                builder.append("\n").append(detail);
            }
        }
        return builder.length() == 0 ? "暂无日志" : builder.toString();
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
        Config.EmailConfig email = Config.emailConfig(this);
        String publicUrl = frp.publicUrl.isEmpty() ? "未配置" : frp.publicUrl;
        String frpStatus = frp.serverAddr.isEmpty()
                ? "未配置"
                : frp.serverAddr + (frp.remotePort.isEmpty() ? "" : ":" + frp.remotePort);
        String emailStatus = email.isConfigured()
                ? email.senderEmail + " -> " + email.recipientEmail + " (" + email.smtpHost + ":" + email.portValue() + ", " + email.smtpSecurity + ")"
                : "未配置完整";
        status.setText("设备 ID：" + Config.deviceId(this)
                + "\n短信权限：" + (hasSmsPermissions() ? "已授权，可以接收短信" : "未授权，请先授权")
                + "\n默认短信应用：" + (isDefaultSmsApp() ? "是" : "否，发送可能被系统拦截")
                + "\n网页服务：打开软件后自动启动"
                + "\n发送桥：" + bridgeStatusText()
                + "\n邮件转发：" + emailStatus
                + "\nfrp 配置：" + frpStatus
                + "\nfrp 隧道：" + frpTunnelStatusText()
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

    private void showBridgeHelpDialog() {
        String message = SmsSendService.requiresShellBridge()
                ? bridgeStatusText() + "\n\n" + SmsSendService.shellBridgeStartHint()
                : "当前机型不需要发送桥，App 会直接使用系统 SmsManager 发送。";
        new AlertDialog.Builder(this)
                .setTitle("发送桥状态")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private String frpTunnelStatusText() {
        try {
            JSONObject frp = FrpClient.snapshot(this);
            if (!frp.optBoolean("supported", false)) return "当前设备架构不支持";
            if (!frp.optBoolean("enabled", false)) return frp.optString("message", "未配置");
            if (frp.optBoolean("running", false)) {
                String dns = frp.optString("dnsServer", "");
                return "运行中" + (dns.isEmpty() ? "" : "，DNS " + dns);
            }
            String error = frp.optString("lastError", "");
            return error.isEmpty() ? "未运行" : "未运行，" + error;
        } catch (Exception ignored) {
            return "状态读取失败";
        }
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
