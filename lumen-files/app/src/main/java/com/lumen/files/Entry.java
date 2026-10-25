package com.lumen.files;

import java.util.Locale;

/** 一个文件或文件夹的轻量快照，避免在列表中持有 File 对象。 */
public final class Entry {

    public final String name;
    public final String path;
    public final boolean dir;
    public final long size;
    public final long modified;

    public Entry(String name, String path, boolean dir, long size, long modified) {
        this.name = name;
        this.path = path;
        this.dir = dir;
        this.size = size;
        this.modified = modified;
    }

    public String ext() {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
