package com.lumen.files;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toolbar;

import com.lumen.files.webdav.WebDavClient;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 首页：两个文件面板（本地 / WebDAV 独立切换），左右滑动跟手切换面板；
 * 顶栏显示当前面板的路径导航（每一节可点击），设置与服务器管理收进底部悬浮面板。
 */
public final class LocationsActivity extends BaseActivity implements PaneController.Host {

    private Toolbar toolbar;
    private CrumbBar crumb;
    private SelectionBar selectionBar;
    private PanePager pager;
    private View dotFirst;
    private View dotSecond;

    private PaneController left;
    private PaneController right;
    private PaneController selectionPane;

    // 悬浮设置面板内的视图（打开时绑定，关闭时置空）
    private TextView storageSummary;
    private ProgressBar storageBar;
    private View permissionCard;
    private LinearLayout serversBox;
    private TextView rowSizeValue;
    private TextView sortValue;

    private int page;
    private boolean firstResume;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_locations);

        toolbar = findViewById(R.id.toolbar);
        styleToolbar(toolbar);
        toolbar.inflateMenu(R.menu.menu_locations);
        tintMenu(toolbar.getMenu());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_switch) {
                onChooseSource(currentPane());
                return true;
            }
            return false;
        });
        crumb = findViewById(R.id.crumb);
        crumb.setOnSegmentLongClickListener(v -> {
            currentPane().showPaneMenu();
            return true;
        });
        selectionBar = findViewById(R.id.selectionBar);
        selectionBar.setCallback(new SelectionBar.Callback() {
            @Override
            public void onSelectAll() {
                if (selectionPane != null) {
                    selectionPane.toggleSelectAll();
                }
            }

            @Override
            public void onCopy() {
                if (selectionPane != null) {
                    selectionPane.copySelected();
                }
            }

            @Override
            public void onMove() {
                if (selectionPane != null) {
                    selectionPane.moveSelected();
                }
            }

            @Override
            public void onZip() {
                if (selectionPane != null) {
                    selectionPane.zipSelected();
                }
            }

            @Override
            public void onDelete() {
                if (selectionPane != null) {
                    selectionPane.deleteSelected();
                }
            }

            @Override
            public void onClose() {
                if (selectionPane != null) {
                    selectionPane.exitSelection();
                }
            }
        });
        // 路径节之外的区域：点按切换数据源，长按弹面板菜单
        bindToolbarGestures();
        // 底部手势条：上滑打开设置面板
        bindSettingsGesture();

        pager = findViewById(R.id.pager);
        dotFirst = findViewById(R.id.dotFirst);
        dotSecond = findViewById(R.id.dotSecond);
        pager.setCallback(new PanePager.Callback() {
            @Override
            public void onPageSettled(int settled) {
                if (settled != page) {
                    left.exitSelection();
                    right.exitSelection();
                    page = settled;
                    updateToolbar();
                }
                paintDots(page);
            }

            @Override
            public void onDragProgress(float fraction) {
                paintDots(fraction);
            }
        });

        left = new PaneController(this, this, 0, findViewById(R.id.paneLeft));
        right = new PaneController(this, this, 1, findViewById(R.id.paneRight));

        page = savedInstanceState == null ? 0 : savedInstanceState.getInt("page", 0);
        pager.setPage(page, false);
        initDots();
        paintDots(page);
        updateToolbar();

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
        if (selectionPane != null) {
            selectionPane.exitSelection();
            return;
        }
        if (page == 1) {
            showPage(0);
            return;
        }
        if (currentPane().canGoUp()) {
            currentPane().goUp();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 左右滑动切面板

    private void showPage(int target) {
        pager.setPage(target, true);
    }

    private PaneController currentPane() {
        return page == 0 ? left : right;
    }

    /** 顶栏跟随当前面板：显示可点击的路径导航。 */
    private void updateToolbar() {
        PaneController pane = currentPane();
        crumb.setSegments(pane.crumbSegments(), target -> pane.load(target));
    }

    @Override
    public void onPaneChanged(PaneController pane) {
        if (pane == currentPane()) {
            updateToolbar();
        }
    }

    /** 路径节之外的区域：点按切换数据源，长按弹面板菜单。 */
    private void bindToolbarGestures() {
        toolbar.setOnClickListener(v -> onChooseSource(currentPane()));
        toolbar.setOnLongClickListener(v -> {
            currentPane().showPaneMenu();
            return true;
        });
    }

    /** 底部手势条：向上滑动即打开设置面板。 */
    private void bindSettingsGesture() {
        View bar = findViewById(R.id.gestureBar);
        final float[] down = new float[2];
        final boolean[] triggered = {false};
        bar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getX();
                    down[1] = event.getY();
                    triggered[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float up = down[1] - event.getY();
                    float sideways = Math.abs(event.getX() - down[0]);
                    if (!triggered[0] && up > dp(48) && up > sideways * 1.5f) {
                        triggered[0] = true;
                        showSettingsSheet();
                    }
                    return true;
                }
                default:
                    return true;
            }
        });
    }

    /** 面板进入/退出多选时，顶栏在路径导航与操作栏之间切换。 */
    @Override
    public void onSelectionChanged(PaneController pane) {
        if (pane.selectionActive()) {
            selectionPane = pane;
            crumb.setVisibility(View.GONE);
            selectionBar.setVisibility(View.VISIBLE);
            toolbar.setOnClickListener(null);
            toolbar.setOnLongClickListener(null);
            toolbar.getMenu().findItem(R.id.action_switch).setVisible(false);
        } else if (selectionPane == pane) {
            selectionPane = null;
            selectionBar.setVisibility(View.GONE);
            crumb.setVisibility(View.VISIBLE);
            bindToolbarGestures();
            toolbar.getMenu().findItem(R.id.action_switch).setVisible(true);
        }
        refreshSelectionBar();
    }

    private void refreshSelectionBar() {
        if (selectionPane == null) {
            return;
        }
        selectionBar.setCount(selectionPane.selectedCount());
        selectionBar.setAllSelected(selectionPane.allSelected());
    }

    /** 指示点跟随拖动进度联动：两枚圆点同色，用透明度区分当前面板。 */
    private void paintDots(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        dotFirst.setAlpha(1f - 0.6f * clamped);
        dotSecond.setAlpha(0.4f + 0.6f * clamped);
    }

    // 初始绘制两枚指示点（强调色，透明度联动）
    private void initDots() {
        dotFirst.setBackgroundResource(R.drawable.bg_dot_accent);
        dotSecond.setBackgroundResource(R.drawable.bg_dot_accent);
    }

    // ------------------------------------------------------------------ 数据源

    @Override
    public void onChooseSource(final PaneController pane) {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();

        List<File> roots = StorageUtil.roots();
        for (final File root : roots) {
            labels.add(StorageUtil.label(root));
            actions.add(() -> pane.setLocal(root.getAbsolutePath()));
        }
        if (roots.isEmpty()) {
            labels.add("开启所有文件访问权限");
            actions.add(() -> StorageUtil.requestAccess(this));
        }
        for (final Server server : Prefs.servers(this)) {
            labels.add(server.name);
            actions.add(() -> pane.setServer(server));
        }
        labels.add("＋ 添加 WebDAV 服务器");
        actions.add(() -> showServerDialog(null));

        showActionSheet("切换数据源", labels.toArray(new String[0]), -1,
                which -> actions.get(which).run());
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
        rowSizeValue = sheet.findViewById(R.id.rowSizeValue);
        sortValue = sheet.findViewById(R.id.sortValue);

        // 在面板任意位置按住下拉即可关闭：列表在顶部且向下拖动时接管手势
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] down = new float[2];
        final boolean[] dragging = {false};
        sheet.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawY();
                    down[1] = event.getRawX();
                    dragging[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - down[0];
                    float dx = event.getRawX() - down[1];
                    if (!dragging[0] && dy > slop && dy > Math.abs(dx)
                            && ((android.widget.ScrollView) sheet).getScrollY() <= 0) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        sheet.setTranslationY(Math.max(0f, dy - slop));
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging[0]) {
                        return false;
                    }
                    dragging[0] = false;
                    if (sheet.getTranslationY() > dp(110)) {
                        sheet.animate().translationY(sheet.getHeight())
                                .alpha(0f).setDuration(160)
                                .withEndAction(dialog::dismiss).start();
                    } else {
                        sheet.animate().translationY(0f).alpha(1f)
                                .setDuration(160).start();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });

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

        renderServers();
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
        showActionSheet(server.name, new String[]{"编辑", "删除"}, 1, which -> {
            if (which == 0) {
                showServerDialog(server);
            } else {
                confirmDeleteServer(server);
            }
        });
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
                        Server updated = editing;
                        if (editing == null) {
                            int nextId = 1;
                            for (Server server : servers) {
                                nextId = Math.max(nextId, server.id + 1);
                            }
                            updated = new Server(nextId, finalName, finalAddress,
                                    finalUser, finalPass);
                            servers.add(updated);
                        } else {
                            // Prefs.servers() 每次返回反序列化的新对象，
                            // 必须按 id 找到列表内的实例修改，否则保存不生效
                            for (Server server : servers) {
                                if (server.id == editing.id) {
                                    server.name = finalName;
                                    server.url = finalAddress;
                                    server.user = finalUser;
                                    server.pass = finalPass;
                                    updated = server;
                                    break;
                                }
                            }
                        }
                        Prefs.saveServers(LocationsActivity.this, servers);
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        if (editing != null) {
                            if (left.serverId() == editing.id) {
                                left.setServer(updated);
                            }
                            if (right.serverId() == editing.id) {
                                right.setServer(updated);
                            }
                        }
                        renderSettings();
                        Bg.toast(LocationsActivity.this, "已保存");
                    });
                }));
        dialog.show();
    }
}
