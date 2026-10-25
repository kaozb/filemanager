package com.lumen.files;

import android.content.Context;
import android.graphics.drawable.Drawable;
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

/** ListView 适配器：ViewHolder + 复用图标，保证长列表滚动流畅。 */
public final class FileAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final int textColor;
    private final int secondaryColor;
    private final int accent;
    private final HashMap<Integer, Drawable> iconCache = new HashMap<>();
    private final ArrayList<Entry> items = new ArrayList<>();
    private boolean pathMode;

    public FileAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.textColor = color(context, R.attr.lumenText);
        this.secondaryColor = color(context, R.attr.lumenSecondary);
        this.accent = color(context, R.attr.lumenAccent);
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
        holder.icon.setImageDrawable(iconFor(entry));
        holder.chevron.setVisibility(entry.dir ? View.VISIBLE : View.INVISIBLE);
        return view;
    }

    private Drawable iconFor(Entry entry) {
        int resource = Format.icon(entry);
        Drawable cached = iconCache.get(resource);
        if (cached == null) {
            cached = context.getDrawable(resource).mutate();
            cached.setTint(tintFor(entry));
            iconCache.put(resource, cached);
        }
        return cached;
    }

    private int tintFor(Entry entry) {
        int resource = Format.icon(entry);
        if (resource == R.drawable.ic_folder) return accent;
        if (resource == R.drawable.ic_image) return 0xFF57A7FF;
        if (resource == R.drawable.ic_video) return 0xFFB083FF;
        if (resource == R.drawable.ic_audio) return 0xFFFF7AA8;
        if (resource == R.drawable.ic_archive) return 0xFFFFB35C;
        if (resource == R.drawable.ic_apk) return 0xFF48CF8A;
        if (resource == R.drawable.ic_doc) return 0xFF5BB3FF;
        if (resource == R.drawable.ic_code) return 0xFF4FD1C5;
        if (resource == R.drawable.ic_text) return 0xFF93A4BD;
        return secondaryColor;
    }

    static final class Holder {
        ImageView icon;
        TextView name;
        TextView meta;
        ImageView chevron;
    }
}
