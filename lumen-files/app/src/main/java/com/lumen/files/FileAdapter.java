package com.lumen.files;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * ListView 适配器：ViewHolder + 复用图标，保证长列表滚动流畅。
 * 行高 / 图标容器 / 字号随 rowScale（0.7–1.7）整体缩放，由双指捏合或设置面板驱动。
 */
public final class FileAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final int textColor;
    private final int secondaryColor;
    private final int accent;
    private final int baseColor;
    private final HashMap<Integer, Drawable> iconCache = new HashMap<>();
    private final ArrayList<Entry> items = new ArrayList<>();
    private boolean pathMode;
    private float rowScale;

    public FileAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.textColor = color(context, R.attr.lumenText);
        this.secondaryColor = color(context, R.attr.lumenSecondary);
        this.accent = color(context, R.attr.lumenAccent);
        this.baseColor = color(context, R.attr.lumenBg);
        this.rowScale = Prefs.rowScale(context);
    }

    private static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    public void setItems(List<Entry> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    public void setPathMode(boolean pathMode) {
        this.pathMode = pathMode;
    }

    public void setRowScale(float scale) {
        if (Math.abs(scale - rowScale) < 0.005f) {
            return;
        }
        rowScale = scale;
        notifyDataSetChanged();
    }

    public float rowScale() {
        return rowScale;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Entry getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= items.size()) {
            return position;
        }
        return items.get(position).path.hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        Holder holder;
        if (view == null) {
            view = inflater.inflate(R.layout.item_file, parent, false);
            holder = new Holder();
            holder.icon = view.findViewById(R.id.icon);
            holder.name = view.findViewById(R.id.name);
            holder.meta = view.findViewById(R.id.meta);
            holder.chevron = view.findViewById(R.id.chevron);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }
        Entry entry = items.get(position);

        // 按缩放因子调整：图标容器、字号、行内边距
        int iconSize = dp(44 * rowScale);
        ViewGroup.LayoutParams lp = holder.icon.getLayoutParams();
        if (lp.width != iconSize) {
            lp.width = iconSize;
            lp.height = iconSize;
            holder.icon.setLayoutParams(lp);
        }
        int pad = dp(11 * rowScale);
        holder.icon.setPadding(pad, pad, pad, pad);
        holder.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(15 * rowScale));
        holder.meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(12 * rowScale));
        view.setPadding(dp(10), dp(11 * rowScale), dp(10), dp(11 * rowScale));

        holder.name.setText(entry.name);
        holder.name.setTextColor(entry.name.startsWith(".") ? secondaryColor : textColor);
        if (pathMode) {
            String value = entry.path;
            if (entry.dir && value.length() > 1 && value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            holder.meta.setText(value);
        } else {
            String date = Format.date(entry.modified);
            if (entry.dir) {
                holder.meta.setText("--".equals(date) ? "文件夹" : "文件夹 · " + date);
            } else {
                holder.meta.setText(Format.size(entry.size) + " · " + date);
            }
        }
        int tint = tintFor(entry);
        holder.icon.setImageDrawable(iconFor(entry, tint));
        holder.icon.setBackground(containerBackground(tint));
        holder.chevron.setVisibility(entry.dir ? View.VISIBLE : View.INVISIBLE);
        return view;
    }

    /** 图标容器底：类型色 14% 混入页面底色，圆角随缩放。 */
    private GradientDrawable containerBackground(int tint) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(14 * rowScale));
        drawable.setColor(Format.blend(tint, baseColor, 0.14f));
        return drawable;
    }

    private Drawable iconFor(Entry entry, int tint) {
        int resource = Format.icon(entry);
        Drawable cached = iconCache.get(resource);
        if (cached == null) {
            cached = context.getDrawable(resource).mutate();
            cached.setTint(tint);
            iconCache.put(resource, cached);
        }
        return cached;
    }

    /** 类型色唯一来源：colors.xml 的 type_* 色板。 */
    private int tintFor(Entry entry) {
        int resource = Format.icon(entry);
        if (resource == R.drawable.ic_folder) {
            return accent;
        }
        if (resource == R.drawable.ic_image) {
            return resColor(R.color.type_image);
        }
        if (resource == R.drawable.ic_video) {
            return resColor(R.color.type_video);
        }
        if (resource == R.drawable.ic_audio) {
            return resColor(R.color.type_audio);
        }
        if (resource == R.drawable.ic_archive) {
            return resColor(R.color.type_archive);
        }
        if (resource == R.drawable.ic_apk) {
            return resColor(R.color.type_apk);
        }
        if (resource == R.drawable.ic_doc) {
            return resColor(R.color.type_doc);
        }
        if (resource == R.drawable.ic_code) {
            return resColor(R.color.type_code);
        }
        if (resource == R.drawable.ic_text) {
            return resColor(R.color.type_text);
        }
        return resColor(R.color.type_text);
    }

    @SuppressWarnings("deprecation")
    private int resColor(int id) {
        return context.getResources().getColor(id);
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static final class Holder {
        ImageView icon;
        TextView name;
        TextView meta;
        ImageView chevron;
    }
}
