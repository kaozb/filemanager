package com.lumen.files.webdav;

import com.lumen.files.Entry;
import com.lumen.files.Server;
import com.lumen.files.fs.Fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 把 WebDAV 服务器映射为统一的 Fs。目录路径统一以 "/" 结尾。 */
public final class WebDavFs implements Fs {

    private final Server server;
    private final WebDavClient client;

    public WebDavFs(Server server) {
        this.server = server;
        this.client = new WebDavClient(server.url, server.user, server.pass);
    }

    @Override
    public String id() {
        return "dav:" + server.id;
    }

    @Override
    public String label() {
        return server.name;
    }

    @Override
    public String homePath() {
        return "/";
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public List<Entry> list(String path) throws IOException {
        return client.list(directory(path), 1);
    }

    @Override
    public InputStream open(String path) throws IOException {
        return client.get(path);
    }

    @Override
    public OutputStream create(String path) throws IOException {
        return client.put(path);
    }

    @Override
    public boolean exists(String path) throws IOException {
        return client.exists(path);
    }

    @Override
    public boolean isDir(String path) throws IOException {
        Entry entry = client.stat(path);
        return entry != null && entry.dir;
    }

    @Override
    public void mkdir(String path) throws IOException {
        client.mkcol(directory(path));
    }

    @Override
    public void delete(String path) throws IOException {
        client.delete(path);
    }

    @Override
    public void rename(String from, String to) throws IOException {
        client.move(from, to);
    }

    @Override
    public void move(String from, String to) throws IOException {
        client.move(from, to);
    }

    @Override
    public void copy(String from, String to) throws IOException {
        client.copy(from, to);
    }

    @Override
    public String parent(String path) {
        String value = path;
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        if (slash <= 0) {
            return "/";
        }
        return value.substring(0, slash + 1);
    }

    @Override
    public String child(String dir, String name) {
        if (dir == null || dir.isEmpty()) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static String directory(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.endsWith("/") ? path : path + "/";
    }
}
