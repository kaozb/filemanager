package com.lumen.files.fs;

import android.os.Environment;

import com.lumen.files.Entry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 本地文件系统。全部使用 java.io，无任何第三方依赖。 */
public final class LocalFs implements Fs {

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String label() {
        return "本地";
    }

    @Override
    public String homePath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public List<Entry> list(String path) throws IOException {
        File directory = new File(path);
        File[] files = directory.listFiles();
        if (files == null) {
            if (!directory.exists()) {
                throw new IOException("路径不存在");
            }
            if (!directory.isDirectory()) {
                throw new IOException("不是文件夹");
            }
            throw new IOException("无法读取文件夹，请检查存储权限");
        }
        ArrayList<Entry> result = new ArrayList<>(files.length);
        for (File file : files) {
            boolean dir = file.isDirectory();
            result.add(new Entry(file.getName(), file.getAbsolutePath(), dir,
                    dir ? 0 : file.length(), file.lastModified()));
        }
        return result;
    }

    @Override
    public InputStream open(String path) throws IOException {
        return new FileInputStream(path);
    }

    @Override
    public OutputStream create(String path) throws IOException {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录");
        }
        return new FileOutputStream(file);
    }

    @Override
    public boolean exists(String path) {
        return new File(path).exists();
    }

    @Override
    public boolean isDir(String path) {
        return new File(path).isDirectory();
    }

    @Override
    public void mkdir(String path) throws IOException {
        File file = new File(path);
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) {
            throw new IOException("无法创建文件夹");
        }
    }

    @Override
    public void delete(String path) throws IOException {
        deleteRecursive(new File(path));
    }

    private static void deleteRecursive(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("删除失败：" + file.getName());
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        if (!new File(from).renameTo(new File(to))) {
            throw new IOException("重命名失败");
        }
    }

    @Override
    public void move(String from, String to) throws IOException {
        File source = new File(from);
        File target = new File(to);
        if (target.exists()) {
            deleteRecursive(target);
        }
        if (!source.renameTo(target)) {
            copy(from, to);
            delete(from);
        }
    }

    @Override
    public void copy(String from, String to) throws IOException {
        File source = new File(from);
        File target = new File(to);
        if (target.getAbsolutePath().startsWith(source.getAbsolutePath() + File.separator)) {
            throw new IOException("不能复制到自身内部");
        }
        copyRecursive(source, target);
    }

    private static void copyRecursive(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs() && !target.isDirectory()) {
                throw new IOException("无法创建目录：" + target.getName());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(target, child.getName()));
                }
            }
        } else {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录");
            }
            InputStream in = new FileInputStream(source);
            OutputStream out = new FileOutputStream(target);
            FsUtil.pipe(in, out);
        }
    }

    @Override
    public String parent(String path) {
        String parent = new File(path).getParent();
        return parent == null ? "/" : parent;
    }

    @Override
    public String child(String dir, String name) {
        if (dir == null || dir.isEmpty()) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }
}
