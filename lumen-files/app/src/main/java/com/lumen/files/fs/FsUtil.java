package com.lumen.files.fs;

import com.lumen.files.Entry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 跨文件系统的流式工具：复制、压缩、解压、搜索。 */
public final class FsUtil {

    private static final int BUFFER = 64 * 1024;

    private FsUtil() {
    }

    public static void pipe(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            out.close();
        }
    }

    /** 跨文件系统递归复制（本地 ↔ WebDAV、WebDAV ↔ WebDAV）。 */
    public static void copyTree(Fs source, String sourcePath, Fs target, String targetPath)
            throws IOException {
        if (source.isDir(sourcePath)) {
            if (!target.exists(targetPath)) {
                target.mkdir(targetPath);
            }
            for (Entry child : source.list(sourcePath)) {
                copyTree(source, child.path, target, target.child(targetPath, child.name));
            }
        } else {
            pipe(source.open(sourcePath), target.create(targetPath));
        }
    }

    public static void zip(List<Entry> items, String destination) throws IOException {
        ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destination), BUFFER));
        try {
            for (Entry item : items) {
                addZipEntry(new File(item.path), item.name, out);
            }
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void addZipEntry(File file, String name, ZipOutputStream out)
            throws IOException {
        if (file.isDirectory()) {
            out.putNextEntry(new ZipEntry(name + "/"));
            out.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addZipEntry(child, name + "/" + child.getName(), out);
                }
            }
        } else {
            out.putNextEntry(new ZipEntry(name));
            InputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            out.closeEntry();
        }
    }

    public static void unzip(File archive, File destination) throws IOException {
        File root = destination.getCanonicalFile();
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("无法创建解压目录");
        }
        String rootPath = root.getPath();
        ZipInputStream in = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive), BUFFER));
        try {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER];
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                File out = new File(root, name).getCanonicalFile();
                if (!out.getPath().equals(rootPath)
                        && !out.getPath().startsWith(rootPath + File.separator)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw new IOException("无法创建目录：" + out.getName());
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建目录");
                    }
                    OutputStream stream = new BufferedOutputStream(
                            new FileOutputStream(out), BUFFER);
                    try {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            stream.write(buffer, 0, read);
                        }
                    } finally {
                        stream.close();
                    }
                }
                in.closeEntry();
            }
        } finally {
            in.close();
        }
    }

    /** 广度优先搜索，限制目录数与结果数以保护远程服务器。 */
    public static List<Entry> search(Fs fs, String start, String query,
                                     int maxDirs, int maxResults, boolean includeHidden,
                                     AtomicBoolean cancelled) throws IOException {
        ArrayList<Entry> results = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        String needle = query.toLowerCase(Locale.ROOT);
        int dirs = 0;
        while (!queue.isEmpty() && dirs < maxDirs && results.size() < maxResults) {
            if (cancelled != null && cancelled.get()) {
                break;
            }
            String dir = queue.poll();
            if (dir == null || !visited.add(dir)) {
                continue;
            }
            dirs++;
            List<Entry> children;
            try {
                children = fs.list(dir);
            } catch (IOException error) {
                continue;
            }
            for (Entry child : children) {
                if (cancelled != null && cancelled.get()) {
                    break;
                }
                if (!includeHidden && child.name.startsWith(".")) {
                    continue;
                }
                if (child.name.toLowerCase(Locale.ROOT).contains(needle)) {
                    results.add(child);
                    if (results.size() >= maxResults) {
                        break;
                    }
                }
                if (child.dir) {
                    queue.add(child.path);
                }
            }
        }
        Collections.sort(results, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return results;
    }

    /** 粘贴时生成不冲突的名称，例如 "报告 (1).pdf"。 */
    public static String uniqueName(Fs fs, String dir, String name) throws IOException {
        if (!fs.exists(fs.child(dir, name))) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index < 1000; index++) {
            String candidate = base + " (" + index + ")" + ext;
            if (!fs.exists(fs.child(dir, candidate))) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }
}
