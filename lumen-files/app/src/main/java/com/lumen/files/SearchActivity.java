package com.lumen.files;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lumen.files.fs.Fs;
import com.lumen.files.fs.FsUtil;
import com.lumen.files.fs.LocalFs;
import com.lumen.files.webdav.WebDavFs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 当前目录内的快速搜索：本地深度搜索，WebDAV 限流搜索。 */
public final class SearchActivity extends BaseActivity {

    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_PATH = "path";

    private EditText input;
    private ImageButton clear;
    private ListView listView;
    private FileAdapter adapter;
    private ProgressBar loading;
    private TextView hint;

    private Fs fs;
    private int serverId;
    private String startPath;
    private AtomicBoolean cancelled;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        serverId = getIntent().getIntExtra(EXTRA_SERVER, -1);
        startPath = getIntent().getStringExtra(EXTRA_PATH);
        if (startPath == null || startPath.isEmpty()) {
            startPath = "/";
        }
        if (serverId >= 0) {
            Server server = Prefs.server(this, serverId);
            if (server == null) {
                Bg.toast(this, "服务器不存在");
                finish();
                return;
            }
            fs = new WebDavFs(server);
        } else {
            fs = new LocalFs();
        }

        input = findViewById(R.id.input);
        clear = findViewById(R.id.clear);
        listView = findViewById(R.id.list);
        loading = findViewById(R.id.loading);
        hint = findViewById(R.id.hint);

        adapter = new FileAdapter(this);
        adapter.setPathMode(true);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> open(adapter.getItem(position)));

        findViewById(R.id.back).setOnClickListener(v -> finish());
        clear.setOnClickListener(v -> reset());
        tint(clear.getDrawable(), secondaryColor);

        input.setHint(fs.isLocal() ? "搜索当前目录…" : "搜索服务器…");
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_NULL) {
                search(input.getText().toString());
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        input.requestFocus();
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void reset() {
        if (cancelled != null) {
            cancelled.set(true);
        }
        generation++;
        input.setText("");
        adapter.setItems(new ArrayList<>());
        loading.setVisibility(View.GONE);
        hint.setText("输入关键字，回车开始搜索");
        hint.setVisibility(View.VISIBLE);
    }

    private void search(String raw) {
        final String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) {
            Bg.toast(this, "请输入关键字");
            return;
        }
        if (cancelled != null) {
            cancelled.set(true);
        }
        final AtomicBoolean flag = new AtomicBoolean(false);
        cancelled = flag;
        final int current = ++generation;
        final int maxDirs = fs.isLocal() ? 4000 : 250;
        final boolean hidden = Prefs.showHidden(this);

        hideKeyboard();
        loading.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);
        adapter.setItems(new ArrayList<>());

        Bg.pool().execute(() -> {
            List<Entry> results = null;
            String failure = null;
            try {
                results = FsUtil.search(fs, startPath, query, maxDirs, 500, hidden, flag);
            } catch (Exception error) {
                failure = error.getMessage();
            }
            final List<Entry> found = results;
            final String message = failure;
            Bg.post(() -> {
                if (current != generation || isFinishing()) {
                    return;
                }
                loading.setVisibility(View.GONE);
                if (message != null) {
                    hint.setText("搜索失败\n" + message);
                    hint.setVisibility(View.VISIBLE);
                    return;
                }
                if (found == null || found.isEmpty()) {
                    hint.setText("没有找到匹配项");
                    hint.setVisibility(View.VISIBLE);
                    return;
                }
                hint.setVisibility(View.GONE);
                adapter.setItems(found);
                listView.setSelection(0);
                Bg.toast(SearchActivity.this, "找到 " + found.size() + " 项");
            });
        });
    }

    private void open(Entry entry) {
        final String target;
        if (entry.dir) {
            target = entry.path;
        } else {
            target = fs.parent(entry.path);
        }
        Intent intent = new Intent(this, BrowserActivity.class);
        intent.putExtra(BrowserActivity.EXTRA_SERVER, serverId);
        intent.putExtra(BrowserActivity.EXTRA_PATH, target);
        startActivity(intent);
        finish();
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        generation++;
        if (cancelled != null) {
            cancelled.set(true);
        }
        super.onDestroy();
    }
}
