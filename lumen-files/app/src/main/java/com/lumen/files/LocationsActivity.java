package com.lumen.files;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toolbar;

import com.lumen.files.webdav.WebDavClient;

import java.io.File;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** 首页：本地存储、快捷入口、WebDAV 服务器管理。 */
public final class LocationsActivity extends BaseActivity {

    private Toolbar toolbar;
    private LinearLayout rootsBox;
    private LinearLayout serversBox;
    private GridLayout quickGrid;
    private View permissionCard;
    private TextView permissionTitle;
    private TextView permissionText;
    private TextView heroSub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_locations);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("流光文件");
        toolbar.setSubtitle("本地与 WebDAV");
        styleToolbar(toolbar);
        toolbar.inflateMenu(R.menu.menu_locations);
        tintMenu(toolbar.getMenu());
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_theme) {
                Prefs.setDark(this, !Prefs.dark(this));
                recreate();
                return true;
            }
            if (id == R.id.action_permission) {
                if (StorageUtil.hasAccess(this)) {
                    Bg.toast(this, "已拥有全部文件访问权限");
                } else {
                    requestPermission();
                }
                return true;
            }
            if (id == R.id.action_about) {
                showAbout();
                return true;
            }
            return false;
        });

        permissionCard = findViewById(R.id.permissionCard);
        permissionTitle = findViewById(R.id.permissionTitle);
        permissionText = findViewById(R.id.permissionText);
        heroSub = findViewById(R.id.heroSub);
        rootsBox = findViewById(R.id.rootsBox);
        serversBox = findViewById(R.id.serversBox);
        quickGrid = findViewById(R.id.quickGrid);

        Button grant = findViewById(R.id.grantBtn);
        grant.setOnClickListener(v -> requestPermission());

        View hero = findViewById(R.id.heroCard);
        hero.setOnClickListener(v -> openLocal(
                android.os.Environment.getExternalStorageDirectory().getAbsolutePath()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 77 && Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 30
                && !StorageUtil.hasAccess(this)
                && !shouldShowRequestPermissionRationale(
                        Manifest.permission.READ_EXTERNAL_STORAGE)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("权限已被拒绝。请到应用设置中手动开启“存储”权限后重试。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("应用设置", (dialog, which) ->
                            StorageUtil.openAppDetails(this))
                    .show();
        }
        render();
    }

    private void requestPermission() {
        if (StorageUtil.hasAccess(this)) {
            Bg.toast(this, "已拥有全部文件访问权限");
            return;
        }
        if (StorageUtil.isHuaweiLike() && Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this)
                    .setTitle("鸿蒙 / 荣耀存储授权")
                    .setMessage("鸿蒙 4.0：设置 → 应用和服务 → 应用管理 → 流光文件"
                            + " → 权限 → 所有文件访问。\n\n"
                            + "荣耀 Magic 2（Android 10 及以下）：在权限中开启“存储”即可。\n\n"
                            + "如果系统没有自动跳到对应页面，可点“应用设置”手动开启。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("应用设置", (dialog, which) ->
                            StorageUtil.openAppDetails(this))
                    .setPositiveButton("去授权", (dialog, which) ->
                            StorageUtil.requestAccess(this))
                    .show();
            return;
        }
        StorageUtil.requestAccess(this);
    }

    private void render() {
        permissionCard.setVisibility(StorageUtil.hasAccess(this) ? View.GONE : View.VISIBLE);
        permissionTitle.setText(Build.VERSION.SDK_INT >= 30
                ? "需要所有文件访问权限" : "需要存储访问权限");
        permissionText.setText(StorageUtil.permissionHint());

        long[] usage = StorageUtil.usage(
                android.os.Environment.getExternalStorageDirectory());
        heroSub.setText(String.format(Locale.US, "可用 %s · 共 %s",
                Format.size(usage[1]), Format.size(usage[0])));

        renderRoots();
        renderQuick();
        renderServers();
    }

    private void renderRoots() {
        rootsBox.removeAllViews();
        List<File> roots = StorageUtil.roots();
        if (roots.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("未发现可访问的存储卷");
            empty.setTextColor(secondaryColor);
            empty.setTextSize(13);
            empty.setPadding(0, dp(4), 0, dp(8));
            rootsBox.addView(empty);
            return;
        }
        for (File root : roots) {
            long[] usage = StorageUtil.usage(root);
            long used = Math.max(0, usage[0] - usage[1]);
            int percent = usage[0] <= 0 ? 0 : (int) (used * 1000 / usage[0]);
            String subtitle = String.format(Locale.US, "%s 可用 · 共 %s",
                    Format.size(usage[1]), Format.size(usage[0]));
            View card = addCard(R.drawable.ic_storage, accent,
                    StorageUtil.label(root), subtitle, percent);
            final String path = root.getAbsolutePath();
            card.setOnClickListener(v -> openLocal(path));
        }
    }

    private void renderQuick() {
        quickGrid.removeAllViews();
        String[][] shortcuts = StorageUtil.quickShortcuts();
        for (int i = 0; i < shortcuts.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(shortcuts[i][0]);
            chip.setTextSize(13);
            chip.setTextColor(accent);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(dp(6), dp(11), dp(6), dp(11));

            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 3),
                    GridLayout.spec(i % 3, 1f));
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.setMargins(0, 0, dp(8), dp(8));
            chip.setLayoutParams(params);

            final String path = shortcuts[i][1];
            chip.setOnClickListener(v -> {
                if (new File(path).isDirectory()) {
                    openLocal(path);
                } else {
                    Bg.toast(LocationsActivity.this, "该文件夹不存在");
                }
            });
            quickGrid.addView(chip);
        }
    }

    private void renderServers() {
        serversBox.removeAllViews();
        List<Server> servers = Prefs.servers(this);
        for (final Server server : servers) {
            View card = addCard(R.drawable.ic_cloud, color(R.attr.lumenAccent2),
                    server.name, server.url, -1);
            card.setOnClickListener(v -> openServer(server));
            card.setOnLongClickListener(v -> {
                showServerMenu(server);
                return true;
            });
        }
        View add = addCard(R.drawable.ic_add, accent,
                "添加 WebDAV 服务器", "支持 HTTPS 与 Basic 认证", -1);
        add.setOnClickListener(v -> showServerDialog(null));
        TextView chevron = add.findViewById(R.id.chevron);
        chevron.setVisibility(View.INVISIBLE);
    }

    private View addCard(int iconResource, int tintColor, String title,
                         String subtitle, int percent) {
        View card = getLayoutInflater().inflate(R.layout.item_location, rootsBox, false);
        ImageView icon = card.findViewById(R.id.icon);
        Drawable drawable = getDrawable(iconResource).mutate();
        drawable.setTint(tintColor);
        icon.setImageDrawable(drawable);
        ((TextView) card.findViewById(R.id.title)).setText(title);
        ((TextView) card.findViewById(R.id.subtitle)).setText(subtitle);
        ProgressBar bar = card.findViewById(R.id.bar);
        if (percent >= 0) {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(Math.min(1000, Math.max(0, percent)));
        }
        return card;
    }

    private void openLocal(String path) {
        Intent intent = new Intent(this, BrowserActivity.class);
        intent.putExtra(BrowserActivity.EXTRA_SERVER, -1);
        intent.putExtra(BrowserActivity.EXTRA_PATH, path);
        startActivity(intent);
    }

    private void openServer(Server server) {
        Intent intent = new Intent(this, BrowserActivity.class);
        intent.putExtra(BrowserActivity.EXTRA_SERVER, server.id);
        intent.putExtra(BrowserActivity.EXTRA_PATH, "/");
        startActivity(intent);
    }

    private void showServerMenu(final Server server) {
        new AlertDialog.Builder(this)
                .setTitle(server.name)
                .setItems(new String[]{"编辑", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        showServerDialog(server);
                    } else {
                        confirmDeleteServer(server);
                    }
                })
                .show();
    }

    private void confirmDeleteServer(final Server server) {
        new AlertDialog.Builder(this)
                .setTitle("删除服务器")
                .setMessage("确定删除「" + server.name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    List<Server> servers = Prefs.servers(this);
                    Iterator<Server> iterator = servers.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().id == server.id) {
                            iterator.remove();
                        }
                    }
                    Prefs.saveServers(this, servers);
                    render();
                    Bg.toast(this, "已删除");
                })
                .show();
    }

    private void showServerDialog(final Server editing) {
        View view = getLayoutInflater().inflate(R.layout.dialog_server, null);
        final EditText name = view.findViewById(R.id.name);
        final EditText url = view.findViewById(R.id.url);
        final EditText user = view.findViewById(R.id.user);
        final EditText pass = view.findViewById(R.id.pass);
        if (editing != null) {
            name.setText(editing.name);
            url.setText(editing.url);
            user.setText(editing.user);
            pass.setText(editing.pass);
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing == null ? "添加 WebDAV 服务器" : "编辑服务器")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String serverName = name.getText().toString().trim();
                    String address = url.getText().toString().trim();
                    String username = user.getText().toString().trim();
                    String password = pass.getText().toString();
                    if (address.isEmpty()) {
                        url.setError("请输入服务器地址");
                        return;
                    }
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        address = "https://" + address;
                    }
                    if (!address.endsWith("/")) {
                        address += "/";
                    }
                    if (serverName.isEmpty()) {
                        try {
                            serverName = new URL(address).getHost();
                        } catch (Exception error) {
                            serverName = "WebDAV";
                        }
                    }
                    final String finalName = serverName;
                    final String finalAddress = address;
                    final String finalUser = username;
                    final String finalPass = password;
                    Bg.run(LocationsActivity.this, "正在连接服务器…", () -> {
                        WebDavClient client = new WebDavClient(finalAddress, finalUser, finalPass);
                        client.list("/", 0);
                    }, () -> {
                        List<Server> servers = Prefs.servers(LocationsActivity.this);
                        if (editing == null) {
                            int nextId = 1;
                            for (Server server : servers) {
                                nextId = Math.max(nextId, server.id + 1);
                            }
                            servers.add(new Server(nextId, finalName, finalAddress,
                                    finalUser, finalPass));
                        } else {
                            editing.name = finalName;
                            editing.url = finalAddress;
                            editing.user = finalUser;
                            editing.pass = finalPass;
                        }
                        Prefs.saveServers(LocationsActivity.this, servers);
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        render();
                        Bg.toast(LocationsActivity.this, "已保存");
                    });
                }));
        dialog.show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("流光文件")
                .setMessage("版本 1.0\n\n"
                        + "纯 Java · 零第三方依赖 · 单 Activity 栈\n"
                        + "本地全盘管理 + WebDAV 客户端\n\n"
                        + "特性：多选复制/剪切/粘贴、搜索、压缩解压、"
                        + "图片与文本预览、系统打开方式、明暗主题。")
                .setPositiveButton("知道了", null)
                .show();
    }
}
