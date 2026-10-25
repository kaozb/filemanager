package com.lumen.files;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 多选操作栏：进入多选后占据顶栏路径的位置，提供
 * 全选 / 复制 / 移动 / 压缩 / 删除，点击 ✕ 或返回键退出多选。
 */
public final class SelectionBar extends LinearLayout {

    public interface Callback {
        void onSelectAll();

        void onCopy();

        void onMove();

        void onZip();

        void onDelete();

        void onClose();
    }

    private TextView countText;
    private TextView allButton;
    private Callback callback;
    private boolean allSelected;

    public SelectionBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        countText = findViewById(R.id.selectionCount);
        allButton = findViewById(R.id.selectionAll);
        findViewById(R.id.selectionClose).setOnClickListener(
                v -> { if (callback != null) callback.onClose(); });
        allButton.setOnClickListener(v -> {
            if (callback != null) {
                callback.onSelectAll();
            }
        });
        findViewById(R.id.selectionCopy).setOnClickListener(
                v -> { if (callback != null) callback.onCopy(); });
        findViewById(R.id.selectionMove).setOnClickListener(
                v -> { if (callback != null) callback.onMove(); });
        findViewById(R.id.selectionZip).setOnClickListener(
                v -> { if (callback != null) callback.onZip(); });
        findViewById(R.id.selectionDelete).setOnClickListener(
                v -> { if (callback != null) callback.onDelete(); });
        setCount(0);
        setAllSelected(false);
    }

    public void setCallback(Callback value) {
        callback = value;
    }

    public void setCount(int count) {
        countText.setText("已选 " + count + " 项");
    }

    public void setAllSelected(boolean value) {
        allSelected = value;
        allButton.setText(value ? "全不选" : "全选");
    }
}
