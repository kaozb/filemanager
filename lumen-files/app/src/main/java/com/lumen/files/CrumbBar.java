package com.lumen.files;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** 顶部路径导航：路径的每一节都可点击，点击后直接切换到对应目录。 */
public final class CrumbBar extends HorizontalScrollView {

    public interface OnCrumbListener {
        void onCrumb(String path);
    }

    /** 一节路径：显示名 + 对应目录绝对路径。 */
    public static final class Segment {
        public final String label;
        public final String path;

        public Segment(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    private final LinearLayout box;
    private final int textColor;
    private final int secondaryColor;
    private final int accentColor;
    private View.OnLongClickListener segmentLongClick;

    public CrumbBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        textColor = color(context, R.attr.lumenText);
        secondaryColor = color(context, R.attr.lumenSecondary);
        accentColor = color(context, R.attr.lumenAccent);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        addView(box, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** 长按任意一节的回调（首页用于弹出面板菜单）。 */
    public void setOnSegmentLongClickListener(View.OnLongClickListener listener) {
        segmentLongClick = listener;
    }

    public void setSegments(List<Segment> segments, OnCrumbListener listener) {
        box.removeAllViews();
        TypedValue feedback = new TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, feedback, true);
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (i > 0) {
                box.addView(separator());
            }
            boolean last = i == segments.size() - 1;
            TextView view = new TextView(getContext());
            view.setText(segment.label);
            view.setTextSize(15);
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.setTextColor(last ? accentColor : textColor);
            view.setSingleLine(true);
            view.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            view.setMaxWidth(dp(180));
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(6), 0, dp(6), 0);
            view.setBackgroundResource(feedback.resourceId);
            view.setOnClickListener(v -> listener.onCrumb(segment.path));
            if (segmentLongClick != null) {
                view.setOnLongClickListener(segmentLongClick);
            }
            box.addView(view, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        }
        // 自动滚动到末尾，露出当前目录
        post(() -> fullScroll(View.FOCUS_RIGHT));
    }

    private TextView separator() {
        TextView view = new TextView(getContext());
        view.setText("›");
        view.setTextSize(14);
        view.setTextColor(secondaryColor);
        view.setPadding(dp(1), 0, dp(1), 0);
        view.setClickable(false);
        view.setFocusable(false);
        return view;
    }

    private static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
