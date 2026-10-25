package com.lumen.files;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
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
 * 首页：两个文件面板（本地 / WebDAV 独立切换），左右滑动切换面板；
 * 设置与服务器管理收进底部悬浮面板，入口为顶栏齿轮图标。
 */
public final class LocationsActivity extends BaseActivity implements PaneController.Host {

    private Toolbar toolbar;
    private ViewFlipper flipper;
    private View dotFirst;
    private View dotSecond;

    private PaneController left;
    private PaneController right;

    // 悬浮设置面板内的视图（打开时绑定，关闭时置空）
    private TextView storageSummary;
    private ProgressBar storageBar;
    private View permissionCard;
    private LinearLayout serversBox;
    private GridLayout quickGrid;
    private TextView rowSizeValue;
    private TextView sortValue;

    private int page;
    private boolean firstResume;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean multiTouch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_locations);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("超级文件");
        styleToolbar(toolbar);
        toolbar.inflateMenu(R.menu.menu_locations);
        tintMenu(toolbar.getMenu());
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_settings) {
                showSettingsSheet();
                return true;
            }
            if (id == R.id.action_about) {
                showAbout();
                return true;
            }
            return false;
        });

        flipper = findViewById(R.id.flipper);
        dotFirst = findViewById(R.id.dotFirst);
        dotSecond = findViewById(R.id.dotSecond);

        left = new PaneController(this, this, 0, findViewById(R.id.paneLeft));
        right = new PaneController(this, this, 1, findViewById(R.id.paneRight));

        page = savedInstanceState == null ? 0 : savedInstanceState.getInt("page", 0);
        if (page == 1) {
            flipper.setDisplayedChild(1);
        }
        updateDots();

        firstResume = true;
        left.restore();
        right.restore();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        left.reload();
        right.reload();
        renderSettings();
    }

    @Override
    public void onBackPressed() {
        if (page == 1) {
            showPage(0);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 左右滑动切面板

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                multiTouch = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 双指（捏合缩放行高）不触发翻页
                multiTouch = true;
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!multiTouch) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > dp(28) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging && !multiTouch) {
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
            return;
        }
        if (target == 1) {
            flipper.setInAnimation(AnimationUtils.loadAnimation(this, R.anim.page_in_right));
            flipper.setOutAnimation(AnimationUtils.loadAnimation(this, R.anim.page_out_left));
            flipper.showNext();
        } else {
            flipper.setInAnimation(AnimationUtils.loadAnimation(this, R.anim.page_in_left));
            flipper.setOutAnimation(AnimationUtils.loadAnimation(this, R.anim.page_out_right));
            flipper.showPrevious();
        }
        page = target;
        updateDots();
    }

    private void updateDots() {
        paintDot(dotFirst, page == 0);
        paintDot(dotSecond, page == 1);
    }

    private void paintDot(View dot, boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(active ? accent : secondaryColor);
        dot.setBackground(drawable);
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
        actions.add(() -> showServerDialog(null));

        new AlertDialog.Builder(this)
                .setTitle("选择数据源")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        actions.get(which).run())
                .show();
    }

    @Override
    public void onOpenSettings() {
        showSettingsSheet();
    }

    @Override
    public PaneController other(PaneController pane) {
        return pane == left ? right : left;
    }

    // ------------------------------------------------------------------ 悬浮设置面板

    private void showSettingsSheet() {
        View sheet = getLayoutInflater().inflate(R.layout.sheet_settings, null);
        final Dialog dialog = new Dialog(this, R.style.SheetDialog);
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }

        storageSummary = sheet.findViewById(R.id.storageSummary);
        storageBar = sheet.findViewById(R.id.storageBar);
        permissionCard = sheet.findViewById(R.id.permissionCard);
        serversBox = sheet.findViewById(R.id.serversBox);
        quickGrid = sheet.findViewById(R.id.quickGrid);
        rowSizeValue = sheet.findViewById(R.id.rowSizeValue);
        sortValue = sheet.findViewById(R.id.sortValue);

        sheet.findViewById(R.id.sheetClose).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.grantBtn).setOnClickListener(v ->
                StorageUtil.requestAccess(this));
        sheet.findViewById(R.id.addServerBtn).setOnClickListener(v ->
                showServerDialog(null));

        Switch prefDark = sheet.findViewById(R.id.prefDark);
        Switch prefHidden = sheet.findViewById(R.id.prefHidden);
        prefDark.setChecked(Prefs.dark(this));
        prefHidden.setChecked(Prefs.showHidden(this));
        prefDark.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setDark(this, checked);
            recreate();
        });
        prefHidden.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setShowHidden(this, checked);
            left.applyPrefs();
            right.applyPrefs();
        });

        sheet.findViewById(R.id.rowSizeRow).setOnClickListener(v -> showRowSizeDialog());
        sheet.findViewById(R.id.sortRow).setOnClickListener(v -> showSortPrefDialog());
        rowSizeValue.setText(rowSizeLabel());
        sortValue.setText(sortLabel());

        dialog.setOnDismissListener(d -> {
            storageSummary = null;
            storageBar = null;
            permissionCard = null;
            serversBox = null;
            quickGrid = null;
            rowSizeValue = null;
            sortValue = null;
        });

        renderSettings();
        dialog.show();
    }

    private void renderSettings() {
        if (storageSummary == null) {
            return;
        }
        boolean granted = StorageUtil.hasAccess(this);
        permissionCard.setVisibility(granted ? View.GONE : View.VISIBLE);

        long[] usage = StorageUtil.usage(Environment.getExternalStorageDirectory());
        storageSummary.setText(String.format(Locale.US, "可用 %s / 共 %s",
                Format.size(usage[1]), Format.size(usage[0])));
        if (usage[0] > 0) {
            long used = usage[0] - usage[1];
            storageBar.setProgress((int) Math.min(1000, used * 1000 / usage[0]));
        }

        renderQuick();
        renderServers();
    }

    private void renderQuick() {
        if (quickGrid == null) {
            return;
        }
        quickGrid.removeAllViews();
        String[][] shortcuts = StorageUtil.quickShortcuts();
        for (int i = 0; i < shortcuts.length; i++) {
            final String label = shortcuts[i][0];
            final String path = shortcuts[i][1];
            View card = getLayoutInflater().inflate(R.layout.item_quick, quickGrid, false);
            ImageView icon = card.findViewById(R.id.icon);
            icon.setImageResource(quickIcon(label));
            icon.setColorFilter(quickTint(label));
            ((TextView) card.findViewById(R.id.label)).setText(label);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f));
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.setMargins(0, 0, dp(8), dp(8));
            card.setLayoutParams(params);

            card.setOnClickListener(v -> {
                if (new File(path).isDirectory()) {
                    left.setLocal(path);
                    showPage(0);
                } else {
                    Bg.toast(LocationsActivity.this, "该文件夹不存在");
                }
            });
            quickGrid.addView(card);
        }
    }

    private int quickIcon(String label) {
        switch (label) {
            case "下载":
                return R.drawable.ic_download;
            case "音乐":
                return R.drawable.ic_audio;
            case "视频":
                return R.drawable.ic_video;
            case "文档":
                return R.drawable.ic_doc;
            default:
                return R.drawable.ic_image;
        }
    }

    private int quickTint(String label) {
        switch (label) {
            case "图片":
                return resColor(R.color.type_image);
            case "相机":
            case "视频":
                return resColor(R.color.type_video);
            case "音乐":
                return resColor(R.color.type_audio);
            case "文档":
                return resColor(R.color.type_doc);
            default:
                return accent;
        }
    }

    @SuppressWarnings("deprecation")
    private int resColor(int id) {
        return getResources().getColor(id);
    }

    private void renderServers() {
        if (serversBox == null) {
            return;
        }
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

    // ------------------------------------------------------------------ 行大小 / 排序

    private String rowSizeLabel() {
        int percent = Prefs.rowScalePercent(this);
        if (percent == 80) {
            return "小";
        }
        if (percent == 100) {
            return "标准";
        }
        if (percent == 125) {
            return "大";
        }
        if (percent == 150) {
            return "特大";
        }
        return percent + "%";
    }

    private String sortLabel() {
        int mode = Prefs.sortMode(this);
        boolean asc = Prefs.sortAsc(this);
        if (mode == 1) {
            return asc ? "大小 小→大" : "大小 大→小";
        }
        if (mode == 2) {
            return asc ? "时间 旧→新" : "时间 新→旧";
        }
        if (mode == 3) {
            return "类型";
        }
        return asc ? "名称 A→Z" : "名称 Z→A";
    }

    private void showRowSizeDialog() {
        final int[] values = {80, 100, 125, 150};
        final String[] labels = {"小 · 80%", "标准 · 100%", "大 · 125%", "特大 · 150%"};
        int current = Prefs.rowScalePercent(this);
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("列表行大小")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.setRowScalePercent(this, values[which]);
                    left.applyRowScale();
                    right.applyRowScale();
                    if (rowSizeValue != null) {
                        rowSizeValue.setText(rowSizeLabel());
                    }
                    dialog.dismiss();
                })
                .show();
    }

    private void showSortPrefDialog() {
        final String[] labels = {
                "名称 A→Z", "名称 Z→A", "大小 小→大", "大小 大→小",
                "时间 旧→新", "时间 新→旧", "类型"
        };
        int mode = Prefs.sortMode(this);
        boolean asc = Prefs.sortAsc(this);
        int checked;
        if (mode == 1) {
            checked = asc ? 2 : 3;
        } else if (mode == 2) {
            checked = asc ? 4 : 5;
        } else if (mode == 3) {
            checked = 6;
        } else {
            checked = asc ? 0 : 1;
        }
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            Prefs.setSort(this, 0, true);
                            break;
                        case 1:
                            Prefs.setSort(this, 0, false);
                            break;
                        case 2:
                            Prefs.setSort(this, 1, true);
                            break;
                        case 3:
                            Prefs.setSort(this, 1, false);
                            break;
                        case 4:
                            Prefs.setSort(this, 2, true);
                            break;
                        case 5:
                            Prefs.setSort(this, 2, false);
                            break;
                        default:
                            Prefs.setSort(this, 3, true);
                            break;
                    }
                    left.applyPrefs();
                    right.applyPrefs();
                    if (sortValue != null) {
                        sortValue.setText(sortLabel());
                    }
                    dialog.dismiss();
                })
                .show();
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
                .setTitle("超级文件")
                .setMessage("版本 1.0\n\n"
                        + "纯 Java · 零第三方依赖 · 双面板左右滑动\n"
                        + "本地全盘管理 + WebDAV 客户端\n\n"
                        + "特性：双面板独立数据源、跨面板复制、多选复制/剪切/粘贴、"
                        + "搜索、压缩解压、图片与文本预览、双指缩放列表行、明暗主题。")
                .setPositiveButton("知道了", null)
                .show();
    }
}
