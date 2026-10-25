package com.lumen.files;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import com.lumen.files.fs.Fs;
import com.lumen.files.fs.FsUtil;
import com.lumen.files.fs.LocalFs;
import com.lumen.files.webdav.WebDavFs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 文件浏览主界面：本地与 WebDAV 完全统一。 */
public final class BrowserActivity extends BaseActivity {

    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_PATH = "path";

    private static final int REQ_PICK = 41;

    private Toolbar toolbar;
    private CrumbBar crumb;
    private ListView listView;
    private PullRefreshLayout refreshLayout;
    private FileAdapter adapter;
    private View loadingView;
    private View emptyView;
    private TextView emptyText;
    private View pasteBar;
    private TextView pasteText;
    private TextView pasteSource;

    private Fs fs;
    private int serverId;
    private String path;
    private String startRoot;
    private String importDir;
    private String errorMessage;

    private boolean showHidden;
    private int sortMode;
    private boolean sortAsc;

    private final ArrayList<Entry> all = new ArrayList<>();
    private int loadGeneration;
    private ActionMode actionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        serverId = getIntent().getIntExtra(EXTRA_SERVER, -1);
        String requested = getIntent().getStringExtra(EXTRA_PATH);
        if (serverId >= 0) {
            Server server = Prefs.server(this, serverId);
            if (server == null) {
                Toast.makeText(this, "服务器不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            fs = new WebDavFs(server);
            path = requested == null || requested.isEmpty() ? "/" : requested;
            startRoot = "/";
        } else {
            fs = new LocalFs();
            path = requested == null || requested.isEmpty()
                    ? Environment.getExternalStorageDirectory().getAbsolutePath() : requested;
            startRoot = StorageUtil.volumeRoot(path);
        }
        if (savedInstanceState != null) {
            path = savedInstanceState.getString("path", path);
            startRoot = savedInstanceState.getString("startRoot", startRoot);
        }

        showHidden = Prefs.showHidden(this);
        sortMode = Prefs.sortMode(this);
        sortAsc = Prefs.sortAsc(this);

        toolbar = findViewById(R.id.toolbar);
        styleToolbar(toolbar);
        toolbar.setTitle(null);
        toolbar.inflateMenu(R.menu.menu_browser);
        tintMenu(toolbar.getMenu());
        toolbar.setOnMenuItemClickListener(this::onMenuSelected);

        crumb = findViewById(R.id.crumb);

        listView = findViewById(R.id.list);
        refreshLayout = findViewById(R.id.refresh);
        refreshLayout.setOnRefreshListener(() -> load(path, true));
        loadingView = findViewById(R.id.loading);
        emptyView = findViewById(R.id.empty);
        emptyText = emptyView.findViewById(R.id.emptyText);
        pasteBar = findViewById(R.id.pasteBar);
        pasteText = findViewById(R.id.pasteText);
        pasteSource = findViewById(R.id.pasteSource);
        ImageButton fab = findViewById(R.id.fab);
        View pasteButton = findViewById(R.id.pasteBtn);
        ImageButton pasteClose = findViewById(R.id.pasteClose);

        adapter = new FileAdapter(this);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (actionMode != null) {
                toggleSelection(position);
                return;
            }
            Entry entry = adapter.getItem(position);
            if (entry.dir) {
                load(entry.path);
            } else {
                showOpenDialog(entry);
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (actionMode == null) {
                showItemMenu(adapter.getItem(position), position);
            } else {
                listView.setItemChecked(position, true);
                updateSelectionTitle();
            }
            return true;
        });

        fab.setOnClickListener(v -> newFolder());
        pasteButton.setOnClickListener(v -> paste());
        pasteClose.setOnClickListener(v -> {
            Clipboard.clear();
            updatePasteBar();
        });

        updateMenuState();
        load(path);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("path", path);
        outState.putString("startRoot", startRoot);
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (actionMode != null) {
            actionMode.finish();
            return;
        }
        goUp();
    }

    private void goUp() {
        if (path.equals(startRoot) || "/".equals(trimTrailing(path))) {
            finish();
            return;
        }
        load(fs.parent(path));
    }

    private void load(final String target) {
        load(target, false);
    }

    /** keepPosition=true 时刷新当前目录不回滚到顶部。 */
    private void load(final String target, final boolean keepPosition) {
        final int generation = ++loadGeneration;
        if (actionMode != null) {
            actionMode.finish();
        }
        loadingView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        Bg.pool().execute(() -> {
            List<Entry> loaded = null;
            String failure = null;
            try {
                loaded = fs.list(target);
            } catch (IOException error) {
                failure = error.getMessage();
            }
            final List<Entry> result = loaded;
            final String message = failure;
            Bg.post(() -> {
                if (generation != loadGeneration || isFinishing()) {
                    return;
                }
                refreshLayout.setRefreshing(false);
                loadingView.setVisibility(View.GONE);
                path = target;
                all.clear();
                if (message != null) {
                    errorMessage = message;
                    adapter.setItems(new ArrayList<>());
                    emptyText.setText("无法打开\n" + message);
                    emptyView.setVisibility(View.VISIBLE);
                    updateChrome();
                    return;
                }
                errorMessage = null;
                if (result != null) {
                    all.addAll(result);
                }
                applySort();
                updateChrome();
                if (!keepPosition && adapter.getCount() > 0) {
                    listView.setSelection(0);
                }
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
        boolean empty = visible.isEmpty() && errorMessage == null;
        emptyText.setText("这个文件夹是空的");
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
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
        toolbar.setTitle(null);
        toolbar.setSubtitle(null);
        crumb.setSegments(crumbSegments(), target -> load(target));
        updatePasteBar();
        updateMenuState();
    }

    /** 从存储根到当前目录的路径节，每节可点击跳转。 */
    private List<CrumbBar.Segment> crumbSegments() {
        ArrayList<CrumbBar.Segment> segments = new ArrayList<>();
        String base = trimTrailing(startRoot);
        String rootLabel = fs.isLocal() ? StorageUtil.label(new File(startRoot)) : fs.label();
        segments.add(new CrumbBar.Segment(rootLabel, startRoot));
        String current = trimTrailing(path);
        if (!current.equals(base)) {
            String rest = current.startsWith(base) ? current.substring(base.length()) : current;
            for (String part : rest.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                segments.add(new CrumbBar.Segment(part,
                        fs.child(segments.get(segments.size() - 1).path, part)));
            }
        }
        return segments;
    }

    private String folderName() {
        if ("/".equals(trimTrailing(path))) {
            return fs.isLocal() ? StorageUtil.label(new File(path)) : fs.label();
        }
        if (fs.isLocal() && path.equals(startRoot)) {
            return StorageUtil.label(new File(path));
        }
        String value = trimTrailing(path);
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private void updatePasteBar() {
        boolean active = Clipboard.active();
        pasteBar.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            pasteText.setText((Clipboard.move() ? "已剪切 " : "已复制 ")
                    + Clipboard.items().size() + " 项");
            String from = Clipboard.sourceParent();
            pasteSource.setText(from == null ? "" : "来自 " + from);
            pasteSource.setVisibility(from == null ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void updateMenuState() {
        Menu menu = toolbar.getMenu();
        MenuItem hidden = menu.findItem(R.id.action_hidden);
        if (hidden != null) {
            hidden.setChecked(showHidden);
        }
        MenuItem importItem = menu.findItem(R.id.action_import);
        if (importItem != null) {
            importItem.setTitle(fs != null && fs.isLocal()
                    ? R.string.menu_import : R.string.menu_upload);
        }
        tintMenu(menu);
    }

    private boolean onMenuSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search) {
            Intent intent = new Intent(this, SearchActivity.class);
            intent.putExtra(SearchActivity.EXTRA_SERVER, serverId);
            intent.putExtra(SearchActivity.EXTRA_PATH, path);
            startActivity(intent);
            return true;
        }
        if (id == R.id.action_new_folder) {
            newFolder();
            return true;
        }
        if (id == R.id.action_new_file) {
            newFile();
            return true;
        }
        if (id == R.id.action_sort) {
            showSortDialog();
            return true;
        }
        if (id == R.id.action_hidden) {
            showHidden = !showHidden;
            Prefs.setShowHidden(this, showHidden);
            updateMenuState();
            applySort();
            return true;
        }
        if (id == R.id.action_refresh) {
            load(path, true);
            return true;
        }
        if (id == R.id.action_import) {
            pickFiles();
            return true;
        }
        if (id == R.id.action_select_all) {
            selectAll();
            return true;
        }
        return false;
    }

    private void showSortDialog() {
        final String[] labels = {
                "名称 升序", "名称 降序", "大小 升序", "大小 降序",
                "时间 新→旧", "时间 旧→新", "类型"
        };
        int checked;
        if (sortMode == 0) {
            checked = sortAsc ? 0 : 1;
        } else if (sortMode == 1) {
            checked = sortAsc ? 2 : 3;
        } else if (sortMode == 2) {
            checked = sortAsc ? 5 : 4;
        } else {
            checked = 6;
        }
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            sortMode = 0;
                            sortAsc = true;
                            break;
                        case 1:
                            sortMode = 0;
                            sortAsc = false;
                            break;
                        case 2:
                            sortMode = 1;
                            sortAsc = true;
                            break;
                        case 3:
                            sortMode = 1;
                            sortAsc = false;
                            break;
                        case 4:
                            sortMode = 2;
                            sortAsc = false;
                            break;
                        case 5:
                            sortMode = 2;
                            sortAsc = true;
                            break;
                        default:
                            sortMode = 3;
                            sortAsc = true;
                            break;
                    }
                    Prefs.setSort(this, sortMode, sortAsc);
                    applySort();
                    dialog.dismiss();
                })
                .show();
    }

    private interface InputCallback {
        void onInput(String value);
    }

    private void inputDialog(String title, String initial, InputCallback callback) {
        View content = getLayoutInflater().inflate(R.layout.dialog_input, null);
        final EditText input = content.findViewById(R.id.input);
        input.setText(initial);
        if (initial != null && !initial.isEmpty()) {
            input.setSelection(initial.length());
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        Bg.toast(this, "名称不能为空");
                        return;
                    }
                    if (value.contains("/")) {
                        Bg.toast(this, "名称不能包含 /");
                        return;
                    }
                    callback.onInput(value);
                })
                .show();
    }

    private void newFolder() {
        final String dir = path;
        inputDialog("新建文件夹", "", value -> Bg.run(this, "正在创建…",
                () -> fs.mkdir(fs.child(dir, value)),
                () -> {
                    load(path);
                    Bg.toast(this, "已创建文件夹");
                }));
    }

    private void newFile() {
        final String dir = path;
        inputDialog("新建文件", "未命名.txt", value -> Bg.run(this, "正在创建…", () -> {
            OutputStream out = fs.create(fs.child(dir, value));
            out.close();
        }, () -> {
            load(path);
            Bg.toast(this, "已创建文件");
        }));
    }

    private void pickFiles() {
        importDir = path;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Exception error) {
            Bg.toast(this, "没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        final String dir = importDir != null ? importDir : path;
        Bg.run(this, "正在导入…", () -> {
            for (Uri uri : uris) {
                String name = displayName(uri);
                String targetName = FsUtil.uniqueName(fs, dir, name);
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) {
                    throw new IOException("无法读取所选文件");
                }
                OutputStream output = fs.create(fs.child(dir, targetName));
                FsUtil.pipe(input, output);
            }
        }, () -> {
            load(path);
            Bg.toast(this, "导入完成");
        });
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "file" : fallback;
    }

    // ------------------------------------------------------------------ 选择模式

    /** 长按文件/文件夹弹出的居中菜单，选项保持简约。 */
    private void showItemMenu(final Entry entry, final int position) {
        final String[] options = {"多选", "重命名", "复制", "剪切", "删除"};
        showCenterMenu(entry.name, options, 4, which -> {
            if (which == 0) {
                enterSelection(position);
            } else if (which == 1) {
                rename(entry);
            } else if (which == 2) {
                Clipboard.set(fs, Collections.singletonList(entry), false);
                updatePasteBar();
                Bg.toast(this, "已复制 1 项");
            } else if (which == 3) {
                Clipboard.set(fs, Collections.singletonList(entry), true);
                updatePasteBar();
                Bg.toast(this, "已剪切 1 项");
            } else {
                confirmDelete(Collections.singletonList(entry));
            }
        });
    }

    private void enterSelection(int position) {
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setItemChecked(position, true);
        actionMode = startActionMode(new SelectionCallback());
        if (actionMode != null) {
            actionMode.setTitle(selectedEntries().size() + " 项");
        }
    }

    private void toggleSelection(int position) {
        listView.setItemChecked(position, !listView.isItemChecked(position));
        if (actionMode == null) {
            return;
        }
        int count = selectedEntries().size();
        if (count == 0) {
            actionMode.finish();
        } else {
            actionMode.setTitle(count + " 项");
        }
    }

    private void updateSelectionTitle() {
        if (actionMode != null) {
            actionMode.setTitle(selectedEntries().size() + " 项");
        }
    }

    private void selectAll() {
        if (adapter.getCount() == 0) {
            return;
        }
        if (actionMode == null) {
            listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            listView.setItemChecked(0, true);
            actionMode = startActionMode(new SelectionCallback());
        }
        for (int i = 0; i < adapter.getCount(); i++) {
            listView.setItemChecked(i, true);
        }
        updateSelectionTitle();
    }

    private List<Entry> selectedEntries() {
        ArrayList<Entry> selected = new ArrayList<>();
        android.util.SparseBooleanArray checked = listView.getCheckedItemPositions();
        if (checked != null) {
            for (int i = 0; i < checked.size(); i++) {
                if (!checked.valueAt(i)) {
                    continue;
                }
                int position = checked.keyAt(i);
                if (position >= 0 && position < adapter.getCount()) {
                    selected.add(adapter.getItem(position));
                }
            }
        }
        return selected;
    }

    final class SelectionCallback implements ActionMode.Callback {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getMenuInflater().inflate(R.menu.menu_multi, menu);
            tintMenu(menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            List<Entry> selected = selectedEntries();
            int count = selected.size();
            boolean single = count == 1;
            menu.findItem(R.id.action_rename).setVisible(single);
            menu.findItem(R.id.action_info).setVisible(single);
            menu.findItem(R.id.action_share).setVisible(fs.isLocal());
            menu.findItem(R.id.action_zip).setVisible(fs.isLocal());
            boolean canUnzip = fs.isLocal() && single && !selected.get(0).dir
                    && selected.get(0).name.toLowerCase(Locale.ROOT).endsWith(".zip");
            menu.findItem(R.id.action_unzip).setVisible(canUnzip);
            tintMenu(menu);
            mode.setTitle(count + " 项");
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            List<Entry> selected = selectedEntries();
            if (selected.isEmpty()) {
                return false;
            }
            int id = item.getItemId();
            if (id == R.id.action_select_all) {
                int count = adapter.getCount();
                boolean allSelected = selectedEntries().size() >= count;
                for (int i = 0; i < count; i++) {
                    listView.setItemChecked(i, !allSelected);
                }
                if (allSelected) {
                    mode.finish();
                } else {
                    updateSelectionTitle();
                }
                return true;
            }
            if (id == R.id.action_copy) {
                Clipboard.set(fs, selected, false);
                mode.finish();
                updatePasteBar();
                Bg.toast(BrowserActivity.this, "已复制 " + selected.size() + " 项");
                return true;
            }
            if (id == R.id.action_cut) {
                Clipboard.set(fs, selected, true);
                mode.finish();
                updatePasteBar();
                Bg.toast(BrowserActivity.this, "已剪切 " + selected.size() + " 项");
                return true;
            }
            if (id == R.id.action_rename) {
                rename(selected.get(0));
                mode.finish();
                return true;
            }
            if (id == R.id.action_delete) {
                confirmDelete(selected);
                return true;
            }
            if (id == R.id.action_share) {
                share(selected);
                return true;
            }
            if (id == R.id.action_info) {
                details(selected.get(0));
                return true;
            }
            if (id == R.id.action_zip) {
                zipSelected(selected);
                return true;
            }
            if (id == R.id.action_unzip) {
                unzip(selected.get(0));
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            for (int i = 0; i < adapter.getCount(); i++) {
                listView.setItemChecked(i, false);
            }
            listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            actionMode = null;
        }
    }

    // ------------------------------------------------------------------ 文件操作

    private void rename(final Entry entry) {
        final String dir = path;
        inputDialog("重命名", entry.name, value -> {
            if (value.equals(entry.name)) {
                return;
            }
            String target = fs.child(dir, value);
            Bg.run(this, "正在重命名…", () -> fs.rename(entry.path, target), () -> {
                load(path);
                Bg.toast(this, "已重命名");
            });
        });
    }

    private void confirmDelete(final List<Entry> entries) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除这 " + entries.size() + " 个项目吗？此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (actionMode != null) {
                        actionMode.finish();
                    }
                    Bg.run(this, "正在删除…", () -> {
                        for (Entry entry : entries) {
                            fs.delete(entry.path);
                        }
                    }, () -> {
                        load(path);
                        Bg.toast(this, "已删除");
                    });
                })
                .show();
    }

    private void paste() {
        if (!Clipboard.active()) {
            return;
        }
        final Fs sourceFs = Clipboard.fs();
        final List<Entry> items = new ArrayList<>(Clipboard.items());
        final boolean move = Clipboard.move();
        final String targetDir = path;
        final boolean[] conflict = new boolean[1];
        Bg.run(this, null, () -> {
            for (Entry entry : items) {
                boolean sameParent = sameDirectory(sourceFs,
                        sourceFs.parent(entry.path), targetDir);
                if (move && sameParent) {
                    continue;
                }
                if (!move && sameParent && sourceFs.id().equals(fs.id())) {
                    continue;
                }
                if (fs.exists(fs.child(targetDir, entry.name))) {
                    conflict[0] = true;
                    break;
                }
            }
        }, () -> {
            if (conflict[0]) {
                new AlertDialog.Builder(this)
                        .setTitle("目标已存在")
                        .setMessage("当前目录已有同名项目，继续将覆盖它们。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("覆盖", (dialog, which) ->
                                doPaste(sourceFs, items, move, targetDir, true))
                        .show();
            } else {
                doPaste(sourceFs, items, move, targetDir, false);
            }
        });
    }

    private void doPaste(final Fs sourceFs, final List<Entry> items,
                         final boolean move, final String targetDir, final boolean overwrite) {
        Bg.run(this, move ? "正在移动…" : "正在复制…", () -> {
            for (Entry entry : items) {
                boolean sameParent = sameDirectory(sourceFs,
                        sourceFs.parent(entry.path), targetDir);
                if (move && sameParent) {
                    continue;
                }
                String target = fs.child(targetDir, entry.name);
                if (!move && sameParent && sourceFs.id().equals(fs.id())) {
                    target = fs.child(targetDir, FsUtil.uniqueName(fs, targetDir, entry.name));
                }
                if (overwrite && fs.exists(target)) {
                    fs.delete(target);
                }
                if (sourceFs.id().equals(fs.id())) {
                    if (move) {
                        sourceFs.move(entry.path, target);
                    } else {
                        sourceFs.copy(entry.path, target);
                    }
                } else {
                    FsUtil.copyTree(sourceFs, entry.path, fs, target);
                    if (move) {
                        sourceFs.delete(entry.path);
                    }
                }
            }
        }, () -> {
            if (move) {
                Clipboard.clear();
            }
            updatePasteBar();
            load(path);
            Bg.toast(this, move ? "移动完成" : "复制完成");
        });
    }

    private static boolean sameDirectory(Fs fileSystem, String first, String second) {
        return trimTrailing(first).equals(trimTrailing(second));
    }

    private void zipSelected(final List<Entry> entries) {
        final String dir = path;
        String suggestion = folderName() + ".zip";
        inputDialog("压缩为", suggestion, value -> {
            String name = value.toLowerCase(Locale.ROOT).endsWith(".zip") ? value : value + ".zip";
            final String destination = fs.child(dir, name);
            if (actionMode != null) {
                actionMode.finish();
            }
            Bg.run(this, "正在压缩…", () -> FsUtil.zip(entries, destination), () -> {
                load(path);
                Bg.toast(this, "压缩完成");
            });
        });
    }

    private void unzip(final Entry entry) {
        if (actionMode != null) {
            actionMode.finish();
        }
        String base = entry.name.substring(0, entry.name.length() - 4);
        final String destination = fs.child(path, base);
        Bg.run(this, "正在解压…", () -> FsUtil.unzip(
                new File(entry.path), new File(destination)), () -> {
            load(path);
            Bg.toast(this, "解压完成");
        });
    }

    private void share(List<Entry> entries) {
        if (!fs.isLocal()) {
            Bg.toast(this, "WebDAV 文件请先下载到本机再分享");
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (Entry entry : entries) {
            uris.add(FileContentProvider.uriFor(entry.path));
        }
        Intent intent;
        if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType(Format.mime(entries.get(0)));
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("*/*");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "分享"));
        } catch (Exception error) {
            Bg.toast(this, "没有可用的分享应用");
        }
    }

    private void details(Entry entry) {
        String message = "名称：" + entry.name
                + "\n\n路径：" + entry.path
                + "\n\n类型：" + Format.type(entry)
                + ("文件夹".equals(Format.type(entry)) ? "" : " · " + Format.mime(entry))
                + "\n\n大小：" + (entry.dir ? "—" : Format.size(entry.size))
                + "\n\n修改时间：" + Format.date(entry.modified);
        new AlertDialog.Builder(this)
                .setTitle("详情")
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------------ 打开 / 预览

    private void showOpenDialog(final Entry entry) {
        final ArrayList<String> options = new ArrayList<>();
        if (Format.previewable(entry)) {
            options.add("预览");
        }
        options.add("用其他应用打开");
        if (!fs.isLocal()) {
            options.add("下载到本机");
        }
        options.add("详情");
        showActionSheet(entry.name, options.toArray(new String[0]), -1, which -> {
            String option = options.get(which);
            if ("预览".equals(option)) {
                preview(entry);
            } else if ("用其他应用打开".equals(option)) {
                openExternal(entry);
            } else if ("下载到本机".equals(option)) {
                download(entry);
            } else {
                details(entry);
            }
        });
    }

    private void preview(final Entry entry) {
        final boolean image = Format.isImage(entry);
        final Object[] holder = new Object[1];
        Bg.run(this, "正在加载预览…", () -> {
            if (image) {
                holder[0] = loadBitmap(entry);
            } else {
                holder[0] = loadText(entry);
            }
        }, () -> {
            if (holder[0] == null) {
                Bg.toast(this, "无法预览此文件");
            } else if (holder[0] instanceof Bitmap) {
                showImage((Bitmap) holder[0], entry.name);
            } else {
                showText((String) holder[0], entry.name);
            }
        });
    }

    private Bitmap loadBitmap(Entry entry) throws IOException {
        if (fs.isLocal()) {
            return decodeSampled(new File(entry.path), 1800);
        }
        File temp = new File(getCacheDir(), "preview_" + System.currentTimeMillis());
        FsUtil.pipe(fs.open(entry.path), new FileOutputStream(temp));
        try {
            return decodeSampled(temp, 1800);
        } finally {
            temp.delete();
        }
    }

    private static Bitmap decodeSampled(File file, int max) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private String loadText(Entry entry) throws IOException {
        InputStream input = fs.open(entry.path);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[16 * 1024];
            int read;
            int total = 0;
            while (total < 512 * 1024 && (read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                total += read;
            }
        } finally {
            input.close();
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }

    private void showImage(Bitmap bitmap, String title) {
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.66f);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFF000000);
        image.setImageBitmap(bitmap);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(frame)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showText(String content, String title) {
        TextView text = new TextView(this);
        text.setTextSize(13);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextColor(textColor);
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(12), dp(16), dp(12));
        text.setText(content);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void openExternal(final Entry entry) {
        if (fs.isLocal()) {
            viewFile(entry.path, Format.mime(entry));
            return;
        }
        final File[] holder = new File[1];
        Bg.run(this, "正在准备文件…", () -> {
            File dir = new File(getCacheDir(), "open");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建临时目录");
            }
            File target = new File(dir, entry.name);
            FsUtil.pipe(fs.open(entry.path), new FileOutputStream(target));
            holder[0] = target;
        }, () -> viewFile(holder[0].getAbsolutePath(), Format.mime(entry)));
    }

    private void viewFile(String filePath, String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(FileContentProvider.uriFor(filePath), mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "打开方式"));
        } catch (Exception error) {
            Bg.toast(this, "没有可用的应用打开此文件");
        }
    }

    private void download(final Entry entry) {
        final File[] holder = new File[1];
        Bg.run(this, "正在下载…", () -> holder[0] = saveToDownloads(entry), () ->
                Bg.toast(this, "已保存到 " + holder[0].getAbsolutePath()));
    }

    private File saveToDownloads(Entry entry) throws IOException {
        File directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (directory != null) {
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("无法创建下载目录");
                }
                File target = new File(directory, entry.name);
                FsUtil.pipe(fs.open(entry.path), new FileOutputStream(target));
                return target;
            } catch (IOException ignored) {
            }
        }
        File fallback = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (fallback == null) {
            fallback = new File(getCacheDir(), "downloads");
        }
        if (!fallback.exists() && !fallback.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File target = new File(fallback, entry.name);
        FsUtil.pipe(fs.open(entry.path), new FileOutputStream(target));
        return target;
    }

    private static String trimTrailing(String value) {
        if (value == null) {
            return "/";
        }
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
