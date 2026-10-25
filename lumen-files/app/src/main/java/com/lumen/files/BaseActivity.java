package com.lumen.files;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

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
}
