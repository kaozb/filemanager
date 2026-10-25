package com.lumen.files;

import com.lumen.files.fs.Fs;

import java.util.ArrayList;
import java.util.List;

/** 全局剪贴板：记录来源文件系统与待粘贴项目。 */
public final class Clipboard {

    private static Fs source;
    private static List<Entry> items = new ArrayList<>();
    private static boolean move;

    private Clipboard() {
    }

    public static void set(Fs fs, List<Entry> selected, boolean isMove) {
        source = fs;
        items = new ArrayList<>(selected);
        move = isMove;
    }

    public static boolean active() {
        return source != null && !items.isEmpty();
    }

    public static Fs fs() {
        return source;
    }

    public static List<Entry> items() {
        return items;
    }

    public static boolean move() {
        return move;
    }

    /** 剪贴板内容的来源目录（用于粘贴浮岛显示），无内容时返回 null。 */
    public static String sourceParent() {
        if (source == null || items.isEmpty()) {
            return null;
        }
        return source.parent(items.get(0).path);
    }

    public static void clear() {
        source = null;
        items = new ArrayList<>();
        move = false;
    }
}
