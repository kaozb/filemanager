package com.lumen.files;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toolbar;

/** 统一处理主题、颜色与 Toolbar 外观。 */
public abstract class BaseActivity extends Activity {

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
}
