package com.lumen.files;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toolbar;
import android.widget.ViewFlipper;

import com.lumen.files.webdav.WebDavClient;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 首页：左边双栏文件浏览（本地 / WebDAV 可各自切换），右滑进入 WebDAV 与设置页。
 */
public final class LocationsActivity extends BaseActivity implements PaneController.Host {

    private Toolbar toolbar;
    private ViewFlipper flipper;
    private TextView tabFiles;
    private TextView tabSettings;
    private TextView storageSummary;
    private LinearLayout serversBox;
    private GridLayout quickGrid;
    private View permissionCard;

    private PaneController left;
    private PaneController right;

    private int page;
    private boolean firstResume;
    private float downX;
    private float downY;
    private boolean dragging;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_locations);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("流光文件");
        styleToolbar(toolbar);
        toolbar.inflateMenu(R.menu.menu_locations);
        tintMenu(toolbar.getMenu());
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_servers) {
                showPage(1);
                return true;
            }
            if (id == R.id.action_theme) {
                Prefs.setDark(this, !Prefs.dark(this));
                recreate();
                return true;
            }
            if (id == R.id.action_permission) {
                if (StorageUtil.hasAccess(this)) {
                    Bg.toast(this, "已拥有全部文件访问权限");
                } else {
                    StorageUtil.requestAccess(this);
                }
                return true;
            }
            if (id == R.id.action_about) {
                showAbout();
                return true;
            }
            return false;
        });

        flipper = findViewById(R.id.flipper);
        tabFiles = findViewById(R.id.tabFiles);
        tabSettings = findViewById(R.id.tabSettings);
        storageSummary = findViewById(R.id.storageSummary);
        serversBox = findViewById(R.id.serversBox);
        quickGrid = findViewById(R.id.quickGrid);
        permissionCard = findViewById(R.id.permissionCard);

        tabFiles.setOnClickListener(v -> showPage(0));
        tabSettings.setOnClickListener(v -> showPage(1));

        findViewById(R.id.grantBtn).setOnClickListener(v -> StorageUtil.requestAccess(this));
        findViewById(R.id.addServerBtn).setOnClickListener(v -> showServerDialog(null));

        left = new PaneController(this, this, 0, findViewById(R.id.paneLeft));
        right = new PaneController(this, this, 1, findViewById(R.id.paneRight));

        page = savedInstanceState == null ? 0 : savedInstanceState.getInt("page", 0);
        if (page == 1) {
            flipper.setDisplayedChild(1);
        }
        firstResume = true;
        left.restore();
        right.restore();
        updateChrome();
        renderSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderSettings();
        if (firstResume) {
            firstResume = false;
        } else {
            left.applyPrefs();
            right.applyPrefs();
            left.reload();
            right.reload();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("page", page);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        renderSettings();
        left.reload();
        right.reload();
    }

    @Override
    public void onBackPressed() {
        if (page == 1) {
            showPage(0);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 左右滑动

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > dp(28) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    dragging = true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    float moved = event.getX() - downX;
                    if (moved < -dp(64) && page == 0) {
                        showPage(1);
                    } else if (moved > dp(64) && page == 1) {
                        showPage(0);
                    }
                }
                dragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void showPage(int target) {
        if (target == page) {
            updateChrome();
            return;
        }
        if (target == 1) {
            flipper.setInAnimation(this, R.anim.page_in_right);
            flipper.setOutAnimation(this, R.anim.page_out_left);
            flipper.showNext();
        } else {
            flipper.setInAnimation(this, R.anim.page_in_left);
            flipper.setOutAnimation(this, R.anim.page_out_right);
            flipper.showPrevious();
        }
        page = target;
        updateChrome();
        if (target == 1) {
            renderSettings();
        }
    }

    private void updateChrome() {
        toolbar.setSubtitle(page == 0 ? "本地 · WebDAV · 左右滑动切换" : "服务器管理与设置");
        tabFiles.setTextColor(page == 0 ? accent : secondaryColor);
        tabSettings.setTextColor(page == 1 ? accent : secondaryColor);
        tabFiles.setTypeface(page == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabSettings.setTypeface(page == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    // ------------------------------------------------------------------ 数据源

    @Override
    public void onChooseSource(final PaneController pane) {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();

        List<File> roots = StorageUtil.roots();
        for (final File root : roots) {
            labels.add(StorageUtil.label(root) + " · " + root.getAbsolutePath());
            actions.add(() -> pane.setLocal(root.getAbsolutePath()));
        }
        if (roots.isEmpty()) {
            labels.add("开启所有文件访问权限");
            actions.add(() -> StorageUtil.requestAccess(this));
        }
        for (final Server server : Prefs.servers(this)) {
            labels.add("WebDAV · " + server.name + " · " + server.url);
            actions.add(() -> pane.setServer(server));
        }
        labels.add("＋ 添加 WebDAV 服务器");
        actions.add(() -> {
            showPage(1);
            showServerDialog(null);
        });

        new AlertDialog.Builder(this)
                .setTitle("选择数据源")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        actions.get(which).run())
                .show();
    }

    @Override
    public void onOpenSettings() {
        showPage(1);
    }

    @Override
    public PaneController other(PaneController pane) {
        return pane == left ? right : left;
    }

    // ------------------------------------------------------------------ 设置页

    private void renderSettings() {
        permissionCard.setVisibility(StorageUtil.hasAccess(this) ? View.GONE : View.VISIBLE);

        long[] usage = StorageUtil.usage(Environment.getExternalStorageDirectory());
        storageSummary.setText(String.format(Locale.US, "内部存储 · 可用 %s / 共 %s",
                Format.size(usage[1]), Format.size(usage[0])));

        renderQuick();
        renderServers();
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
                    left.setLocal(path);
                    showPage(0);
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
        if (servers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有服务器，点击下方按钮添加");
            empty.setTextColor(secondaryColor);
            empty.setTextSize(13);
            empty.setPadding(0, dp(4), 0, dp(10));
            serversBox.addView(empty);
            return;
        }
        for (final Server server : servers) {
            View card = addCard(serversBox, R.drawable.ic_cloud,
                    color(R.attr.lumenAccent2), server.name, server.url, -1);
            card.setOnClickListener(v -> {
                left.setServer(server);
                showPage(0);
            });
            card.setOnLongClickListener(v -> {
                showServerMenu(server);
                return true;
            });
            serversBox.addView(card);
        }
    }

    private View addCard(ViewGroup parent, int iconResource, int tintColor, String title,
                         String subtitle, int percent) {
        View card = getLayoutInflater().inflate(R.layout.item_location, parent, false);
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

    // ------------------------------------------------------------------ 服务器管理

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
                    String home = Environment.getExternalStorageDirectory().getAbsolutePath();
                    if (left.serverId() == server.id) {
                        left.setLocal(home);
                    }
                    if (right.serverId() == server.id) {
                        right.setLocal(home);
                    }
                    renderSettings();
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
                        if (editing != null) {
                            if (left.serverId() == editing.id) {
                                left.setServer(editing);
                            }
                            if (right.serverId() == editing.id) {
                                right.setServer(editing);
                            }
                        }
                        renderSettings();
                        Bg.toast(LocationsActivity.this, "已保存");
                    });
                }));
        dialog.show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("流光文件")
                .setMessage("版本 1.0\n\n"
                        + "纯 Java · 零第三方依赖 · 双栏浏览\n"
                        + "本地全盘管理 + WebDAV 客户端\n\n"
                        + "特性：双栏独立数据源、跨栏复制、多选复制/剪切/粘贴、"
                        + "搜索、压缩解压、图片与文本预览、明暗主题。")
                .setPositiveButton("知道了", null)
                .show();
    }
}
