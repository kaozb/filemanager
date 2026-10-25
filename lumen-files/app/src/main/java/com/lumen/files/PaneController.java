package com.lumen.files;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lumen.files.fs.Fs;
import com.lumen.files.fs.FsUtil;
import com.lumen.files.fs.LocalFs;
import com.lumen.files.webdav.WebDavFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 首页左右两栏中的一栏：独立的数据源、路径与列表。
 * 本地卷与 WebDAV 都走统一的 Fs，切换数据源不影响另一栏。
 * 顶部 chip：点击切换数据源，长按弹出面板菜单；列表支持双指开合缩放行大小。
 */
public final class PaneController {

    public interface Host {
        void onChooseSource(PaneController pane);

        void onOpenSettings();

        PaneController other(PaneController pane);
    }

    private final LocationsActivity activity;
    private final Host host;
    private final int index;
    private final View root;
    private final TextView sourceLabel;
    private final TextView pathLabel;
    private final ImageView chipIcon;
    private final ListView listView;
    private final ProgressBar loadingView;
    private final View emptyView;
    private final TextView emptyText;
    private final FileAdapter adapter;
    private final ArrayList<Entry> all = new ArrayList<>();
    private final int accent;
    private final int accent2;
    private final ScaleGestureDetector scaleDetector;

    private Fs fs;
    private int serverId = -1;
    private String rootPath;
    private String path;

    private boolean showHidden;
    private int sortMode;
    private boolean sortAsc;
    private int generation;

    PaneController(LocationsActivity activity, Host host, int index, View container) {
        this.activity = activity;
        this.host = host;
        this.index = index;
        this.root = LayoutInflater.from(activity)
                .inflate(R.layout.pane_file, (android.view.ViewGroup) container, false);
        ((android.view.ViewGroup) container).addView(root);

        accent = activity.color(R.attr.lumenAccent);
        accent2 = activity.color(R.attr.lumenAccent2);

        View chip = root.findViewById(R.id.paneChip);
        sourceLabel = root.findViewById(R.id.paneSource);
        chipIcon = root.findViewById(R.id.chipIcon);
        pathLabel = root.findViewById(R.id.panePath);
        listView = root.findViewById(R.id.paneList);
        loadingView = root.findViewById(R.id.paneLoading);
        emptyView = root.findViewById(R.id.paneEmpty);
        emptyText = root.findViewById(R.id.paneEmptyText);
        ImageButton up = root.findViewById(R.id.paneUp);

        adapter = new FileAdapter(activity);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this::onItemClick);
        listView.setOnItemLongClickListener(this::onItemLongClick);

        chip.setOnClickListener(v -> host.onChooseSource(this));
        chip.setOnLongClickListener(v -> {
            showPaneMenu(chip);
            return true;
        });
        up.setOnClickListener(v -> goUp());

        // 双指开合：实时缩放行，松手后持久化到全局偏好
        scaleDetector = new ScaleGestureDetector(activity, new ScaleListener());
        listView.setOnTouchListener((view, event) -> {
            scaleDetector.onTouchEvent(event);
            return scaleDetector.isInProgress();
        });

        showHidden = Prefs.showHidden(activity);
        sortMode = Prefs.sortMode(activity);
        sortAsc = Prefs.sortAsc(activity);

        String home = Environment.getExternalStorageDirectory().getAbsolutePath();
        String source = Prefs.paneSource(activity, index);
        String stored = Prefs.panePath(activity, index, home);
        if (source.startsWith("dav:")) {
            Server server = null;
            try {
                server = Prefs.server(activity, Integer.parseInt(source.substring(4)));
            } catch (NumberFormatException ignored) {
            }
            if (server != null) {
                fs = new WebDavFs(server);
                serverId = server.id;
                rootPath = "/";
                path = stored == null || stored.isEmpty() ? "/" : stored;
                return;
            }
        }
        fs = new LocalFs();
        rootPath = StorageUtil.volumeRoot(stored == null || stored.isEmpty() ? home : stored);
        path = stored == null || stored.isEmpty() ? home : stored;
    }

    void restore() {
        load(path);
    }

    Fs fs() {
        return fs;
    }

    String path() {
        return path;
    }

    int serverId() {
        return serverId;
    }

    void applyPrefs() {
        showHidden = Prefs.showHidden(activity);
        sortMode = Prefs.sortMode(activity);
        sortAsc = Prefs.sortAsc(activity);
        adapter.setRowScale(Prefs.rowScale(activity));
        applySort();
    }

    /** 设置面板修改行大小档位后，立即应用到本栏。 */
    void applyRowScale() {
        adapter.setRowScale(Prefs.rowScale(activity));
    }

    void setLocal(String target) {
        fs = new LocalFs();
        serverId = -1;
        rootPath = StorageUtil.volumeRoot(target);
        Prefs.setPaneSource(activity, index, "local");
        load(target);
    }

    void setServer(Server server) {
        fs = new WebDavFs(server);
        serverId = server.id;
        rootPath = "/";
        Prefs.setPaneSource(activity, index, "dav:" + server.id);
        load("/");
    }

    void reload() {
        load(path);
    }

    private void load(final String target) {
        final int current = ++generation;
        final Fs source = fs;
        loadingView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        Bg.pool().execute(() -> {
            List<Entry> loaded = null;
            String failure = null;
            try {
                loaded = source.list(target);
            } catch (IOException error) {
                failure = error.getMessage();
            }
            final List<Entry> result = loaded;
            final String message = failure;
            Bg.post(() -> {
                if (current != generation || activity.isFinishing()) {
                    return;
                }
                loadingView.setVisibility(View.GONE);
                path = target;
                Prefs.setPanePath(activity, index, target);
                all.clear();
                if (message != null) {
                    adapter.setItems(new ArrayList<>());
                    emptyText.setText("无法打开\n" + message);
                    emptyView.setVisibility(View.VISIBLE);
                } else {
                    if (result != null) {
                        all.addAll(result);
                    }
                    applySort();
                }
                updateChrome();
            });
        });
    }

    private void applySort() {
        ArrayList<Entry> visible = new ArrayList<>(all.size());
        for (Entry entry : all) {
            if (showHidden || !entry.name.startsWith(".")) {
                visible.add(entry);
            }
        }
        Collections.sort(visible, comparator());
        adapter.setItems(visible);
        if (emptyView.getVisibility() != View.VISIBLE) {
            boolean empty = visible.isEmpty();
            emptyText.setText(empty ? "这个文件夹是空的" : "");
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
    }

    private Comparator<Entry> comparator() {
        return (a, b) -> {
            if (a.dir != b.dir) {
                return a.dir ? -1 : 1;
            }
            int result;
            switch (sortMode) {
                case 1:
                    result = Long.compare(a.size, b.size);
                    break;
                case 2:
                    result = Long.compare(a.modified, b.modified);
                    break;
                case 3:
                    result = a.ext().compareToIgnoreCase(b.ext());
                    if (result == 0) {
                        result = a.name.compareToIgnoreCase(b.name);
                    }
                    break;
                default:
                    result = a.name.compareToIgnoreCase(b.name);
                    break;
            }
            return sortAsc ? result : -result;
        };
    }

    private void updateChrome() {
        boolean local = fs.isLocal();
        sourceLabel.setText(local ? StorageUtil.label(new File(rootPath)) : fs.label());
        chipIcon.setImageResource(local ? R.drawable.ic_folder : R.drawable.ic_cloud);
        chipIcon.setColorFilter(local ? accent : accent2);
        pathLabel.setText(local ? trimTrailing(path) : path);
    }

    private void goUp() {
        String current = trimTrailing(path);
        if (current.equals(trimTrailing(rootPath)) || "/".equals(current)) {
            Bg.toast(activity, "已经到根目录了");
            return;
        }
        load(fs.parent(path));
    }

    private void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Entry entry = adapter.getItem(position);
        if (entry.dir) {
            load(entry.path);
        } else {
            openEntry(entry);
        }
    }

    private boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        showItemMenu(adapter.getItem(position));
        return true;
    }

    private void showItemMenu(final Entry entry) {
        final String[] options = {"打开", "重命名", "删除", "复制到另一侧", "详情"};
        new AlertDialog.Builder(activity)
                .setTitle(entry.name)
                .setItems(options, (dialog, which) -> {
                    String option = options[which];
                    if ("打开".equals(option)) {
                        openEntry(entry);
                    } else if ("重命名".equals(option)) {
                        renameDialog(entry);
                    } else if ("删除".equals(option)) {
                        deleteDialog(entry);
                    } else if ("复制到另一侧".equals(option)) {
                        copyToOther(entry);
                    } else {
                        details(entry);
                    }
                })
                .show();
    }

    private void showPaneMenu(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add("切换数据源");
        menu.getMenu().add("新建文件夹");
        menu.getMenu().add("刷新");
        menu.getMenu().add("在完整页面打开");
        menu.getMenu().add("WebDAV 设置");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if ("切换数据源".equals(title)) {
                host.onChooseSource(this);
            } else if ("新建文件夹".equals(title)) {
                newFolder();
            } else if ("刷新".equals(title)) {
                reload();
            } else if ("在完整页面打开".equals(title)) {
                openFull();
            } else {
                host.onOpenSettings();
            }
            return true;
        });
        menu.show();
    }

    private void openFull() {
        Intent intent = new Intent(activity, BrowserActivity.class);
        intent.putExtra(BrowserActivity.EXTRA_SERVER, serverId);
        intent.putExtra(BrowserActivity.EXTRA_PATH, path);
        activity.startActivity(intent);
    }

    private void newFolder() {
        View form = LayoutInflater.from(activity).inflate(R.layout.dialog_input, null);
        final EditText input = form.findViewById(R.id.input);
        input.setHint("文件夹名称");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("新建文件夹")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError("请输入名称");
                        return;
                    }
                    String target = fs.child(path, name);
                    dialog.dismiss();
                    Bg.run(activity, "正在创建…", () -> fs.mkdir(target), () -> {
                        Bg.toast(activity, "已创建");
                        reload();
                    });
                }));
        dialog.show();
    }

    private void renameDialog(final Entry entry) {
        final EditText input = (EditText) LayoutInflater.from(activity)
                .inflate(R.layout.dialog_input, null).findViewById(R.id.input);
        input.setText(entry.name);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("重命名")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError("请输入名称");
                        return;
                    }
                    if (name.equals(entry.name)) {
                        dialog.dismiss();
                        return;
                    }
                    String target = fs.child(parentOf(entry.path), name);
                    dialog.dismiss();
                    Bg.run(activity, "正在重命名…", () -> fs.rename(entry.path, target),
                            () -> {
                                Bg.toast(activity, "已重命名");
                                reload();
                            });
                }));
        dialog.show();
    }

    private void deleteDialog(final Entry entry) {
        new AlertDialog.Builder(activity)
                .setTitle("删除")
                .setMessage("确定删除「" + entry.name + "」？" + (entry.dir ? "文件夹会连同内部文件一起删除。" : ""))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) ->
                        Bg.run(activity, "正在删除…", () -> fs.delete(entry.path), () -> {
                            Bg.toast(activity, "已删除");
                            reload();
                        }))
                .show();
    }

    private void copyToOther(final Entry entry) {
        final PaneController target = host.other(this);
        if (target == null || target.fs() == null) {
            return;
        }
        final Fs targetFs = target.fs();
        final String targetDir = target.path();
        if (targetFs.id().equals(fs.id())
                && trimTrailing(targetDir).equals(trimTrailing(path))) {
            Bg.toast(activity, "两栏在同一目录");
            return;
        }
        final String destination = targetFs.child(targetDir, entry.name);
        Bg.run(activity, "正在复制…", () -> FsUtil.copyTree(fs, entry.path, targetFs, destination),
                () -> {
                    Bg.toast(activity, "已复制到另一侧");
                    target.reload();
                });
    }

    private void details(Entry entry) {
        String message = "名称：" + entry.name
                + "\n路径：" + entry.path
                + "\n类型：" + (entry.dir ? "文件夹" : "文件")
                + (entry.dir ? "" : "\n大小：" + Format.size(entry.size))
                + "\n修改时间：" + Format.date(entry.modified);
        new AlertDialog.Builder(activity)
                .setTitle("详情")
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void openEntry(final Entry entry) {
        if (entry.dir) {
            load(entry.path);
            return;
        }
        if (fs.isLocal()) {
            viewFile(entry.path, Format.mime(entry));
            return;
        }
        final File[] holder = new File[1];
        Bg.run(activity, "正在准备文件…", () -> {
            File dir = new File(activity.getCacheDir(), "open");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建临时目录");
            }
            File target = new File(dir, entry.name);
            FsUtil.pipe(fs.open(entry.path), new FileOutputStream(target));
            holder[0] = target;
        }, () -> {
            if (holder[0] != null) {
                viewFile(holder[0].getAbsolutePath(), Format.mime(entry));
            }
        });
    }

    private void viewFile(String filePath, String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(FileContentProvider.uriFor(filePath), mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(intent, "打开方式"));
        } catch (Exception error) {
            Bg.toast(activity, "没有可打开该文件的应用");
        }
    }

    /** 双指捏合手势：缩放范围 0.7–1.7，与设置面板的档位共享同一持久化偏好。 */
    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float next = adapter.rowScale() * detector.getScaleFactor();
            adapter.setRowScale(Math.max(0.7f, Math.min(1.7f, next)));
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            Prefs.setRowScalePercent(activity, Math.round(adapter.rowScale() * 100));
        }
    }

    private static String parentOf(String value) {
        String path = trimTrailing(value);
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return "/";
        }
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    private static String trimTrailing(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
