package com.lumen.files;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 仅使用 SharedPreferences + JSON，避免数据库依赖。 */
public final class Prefs {

    private static final String FILE = "lumen_files";

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean dark(Context context) {
        return sp(context).getBoolean("dark", true);
    }

    public static void setDark(Context context, boolean dark) {
        sp(context).edit().putBoolean("dark", dark).apply();
    }

    public static int sortMode(Context context) {
        return sp(context).getInt("sort_mode", 0);
    }

    public static boolean sortAsc(Context context) {
        return sp(context).getBoolean("sort_asc", true);
    }

    public static void setSort(Context context, int mode, boolean asc) {
        sp(context).edit().putInt("sort_mode", mode).putBoolean("sort_asc", asc).apply();
    }

    public static boolean showHidden(Context context) {
        return sp(context).getBoolean("show_hidden", false);
    }

    public static void setShowHidden(Context context, boolean show) {
        sp(context).edit().putBoolean("show_hidden", show).apply();
    }

    public static List<Server> servers(Context context) {
        ArrayList<Server> list = new ArrayList<>();
        String raw = sp(context).getString("servers", "");
        if (raw.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    list.add(Server.fromJson(object));
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveServers(Context context, List<Server> servers) {
        sp(context).edit().putString("servers", Server.toArray(servers).toString()).apply();
    }

    public static Server server(Context context, int id) {
        for (Server server : servers(context)) {
            if (server.id == id) {
                return server;
            }
        }
        return null;
    }
}
