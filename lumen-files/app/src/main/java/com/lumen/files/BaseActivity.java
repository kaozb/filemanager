package com.lumen.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import java.io.File;

/** 统一处理主题、颜色、Toolbar 外观与底部操作面板。 */
public abstract class BaseActivity extends Activity {

    /** 底部操作面板的选项回调。 */
    protected interface SheetPick {
        void onPick(int which);
    }

    protected int accent;
    protected int textColor;
    protected int secondaryColor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Prefs.dark(this) ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        accent = color(R.attr.lumenAccent);
        textColor = color(R.attr.lumenText);
        secondaryColor = color(R.attr.lumenSecondary);
    }

    protected int color(int attribute) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    protected int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    protected void styleToolbar(Toolbar toolbar) {
        if (toolbar == null) {
            return;
        }
        toolbar.setTitleTextColor(textColor);
        toolbar.setSubtitleTextColor(secondaryColor);
        toolbar.setTitleTextAppearance(this, R.style.ToolbarTitle);
        Drawable overflow = getDrawable(R.drawable.ic_more).mutate();
        overflow.setTint(textColor);
        toolbar.setOverflowIcon(overflow);
    }

    protected void tintMenu(Menu menu) {
        if (menu == null) {
            return;
        }
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                icon.mutate().setTint(textColor);
            }
        }
    }

    protected void tint(Drawable drawable, int color) {
        if (drawable != null) {
            drawable.mutate().setTint(color);
        }
    }

    /**
     * 统一的底部操作面板：拖拽条 + 标题 + 操作项列表，视觉与设置悬浮面板一致。
     * dangerIndex 对应的行用警示色（如「删除」），传 -1 表示没有危险项。
     */
    protected void showActionSheet(String title, String[] options, int dangerIndex,
                                   final SheetPick pick) {
        final Dialog dialog = new Dialog(this, R.style.SheetDialog);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_sheet);
        box.setPadding(dp(8), dp(10), dp(8), dp(14));

        View grip = new View(this);
        LinearLayout.LayoutParams gripParams = new LinearLayout.LayoutParams(dp(38), dp(4));
        gripParams.gravity = Gravity.CENTER_HORIZONTAL;
        grip.setLayoutParams(gripParams);
        grip.setBackgroundResource(R.drawable.bg_grip);
        box.addView(grip);

        if (title != null && !title.isEmpty()) {
            TextView caption = new TextView(this);
            caption.setText(title);
            caption.setTextSize(16);
            caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            caption.setTextColor(textColor);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            captionParams.setMargins(dp(14), dp(12), dp(14), dp(6));
            caption.setLayoutParams(captionParams);
            box.addView(caption);
        }

        int danger = color(R.attr.lumenDanger);
        TypedValue feedback = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
        for (int i = 0; i < options.length; i++) {
            TextView row = new TextView(this);
            row.setText(options[i]);
            row.setTextSize(15.5f);
            row.setTextColor(i == dangerIndex ? danger : textColor);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackgroundResource(feedback.resourceId);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
            row.setLayoutParams(rowParams);
            final int index = i;
            row.setOnClickListener(v -> {
                dialog.dismiss();
                pick.onPick(index);
            });
            box.addView(row);
        }

        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
    }

    /**
     * 居中弹出菜单：长按文件/文件夹时从屏幕中间直接出现，
     * 视觉与底部操作面板、对话框保持同一主题（配色、圆角、警示色）。
     */
    protected void showCenterMenu(String title, String[] options, int dangerIndex,
                                  final SheetPick pick) {
        final Dialog dialog = new Dialog(this, R.style.CenterMenuDialog);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(Prefs.dark(this)
                ? R.drawable.bg_dialog_dark : R.drawable.bg_dialog);
        box.setPadding(dp(7), dp(8), dp(7), dp(8));

        if (title != null && !title.isEmpty()) {
            TextView caption = new TextView(this);
            caption.setText(title);
            caption.setTextSize(15);
            caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            caption.setTextColor(secondaryColor);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            captionParams.setMargins(dp(12), dp(4), dp(12), dp(4));
            caption.setLayoutParams(captionParams);
            box.addView(caption);
        }

        int danger = color(R.attr.lumenDanger);
        TypedValue feedback = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
        for (int i = 0; i < options.length; i++) {
            TextView row = new TextView(this);
            row.setText(options[i]);
            row.setTextSize(15);
            row.setTextColor(i == dangerIndex ? danger : textColor);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), 0, dp(18), 0);
            row.setBackgroundResource(feedback.resourceId);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
            row.setLayoutParams(rowParams);
            final int index = i;
            row.setOnClickListener(v -> {
                dialog.dismiss();
                pick.onPick(index);
            });
            box.addView(row);
        }

        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            int width = Math.min(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.8f), dp(290));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    // ------------------------------------------------------------------ 远程管理

    /** 远程管理入口：已有实例时显示状态，否则弹端口/密码输入。dir 为要共享的目录。 */
    protected void showRemoteMenu(File dir) {
        HttpShareServer running = HttpShareServer.current();
        if (running != null) {
            showRemoteStatus(running);
            return;
        }
        showRemoteStartDialog(dir);
    }

    private void showRemoteStartDialog(final File dir) {
        View content = getLayoutInflater().inflate(R.layout.dialog_remote, null);
        final EditText portInput = content.findViewById(R.id.input_port);
        final EditText passwordInput = content.findViewById(R.id.input_password);
        new AlertDialog.Builder(this)
                .setTitle("开启远程管理")
                .setMessage("把当前目录共享到局域网，同一 Wi-Fi 下的设备"
                        + "用浏览器即可浏览、下载和上传。")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("开启", (dialog, which) -> {
                    int port;
                    try {
                        port = Integer.parseInt(portInput.getText().toString().trim());
                    } catch (NumberFormatException error) {
                        Bg.toast(this, "端口必须是数字");
                        return;
                    }
                    if (port < 1024 || port > 65535) {
                        Bg.toast(this, "端口范围 1024 - 65535");
                        return;
                    }
                    startRemote(dir, port, passwordInput.getText().toString());
                })
                .show();
    }

    private void startRemote(final File dir, final int port, final String password) {
        ensureNotifyPermission();
        Bg.run(this, "正在开启远程管理…", () -> HttpShareServer.start(dir, port, password), () -> {
            HttpShareServer server = HttpShareServer.current();
            if (server != null) {
                // 前台服务保活：否则切后台进程被冻结，Web 立刻不可用
                ShareService.start(this);
                showRemoteStatus(server);
            }
        });
    }

    /** Android 13 起通知需要运行时授权；不授权服务照常运行，只是通知不显示。 */
    private void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            String permission = "android.permission.POST_NOTIFICATIONS";
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{permission}, 6001);
            }
        }
    }

    private void showRemoteStatus(HttpShareServer server) {
        String address = HttpShareServer.lanAddress();
        String url = "http://" + (address == null ? "<手机IP>" : address) + ":" + server.port();
        new AlertDialog.Builder(this)
                .setTitle("远程管理进行中")
                .setMessage("目录：" + server.root().getAbsolutePath()
                        + "\n\n在浏览器打开：\n" + url
                        + "\n\n需要与手机在同一 Wi-Fi。"
                        + (server.hasPassword() ? "\n已启用访问密码。" : ""))
                .setPositiveButton("复制地址", (dialog, which) -> {
                    ClipboardManager manager =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    manager.setPrimaryClip(ClipData.newPlainText("url", url));
                    Bg.toast(this, "地址已复制");
                })
                .setNegativeButton("继续", null)
                .setNeutralButton("停止共享", (dialog, which) -> {
                    ShareService.stop(this);
                    HttpShareServer.stopActive();
                    Bg.toast(this, "远程管理已关闭");
                })
                .show();
    }
}
