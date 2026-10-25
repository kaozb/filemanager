package com.lumen.files.fs;

import com.lumen.files.Entry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 本地与 WebDAV 共用的文件系统接口，UI 层不感知协议差异。 */
public interface Fs {

    String id();

    String label();

    String homePath();

    boolean isLocal();

    List<Entry> list(String path) throws IOException;

    InputStream open(String path) throws IOException;

    OutputStream create(String path) throws IOException;

    boolean exists(String path) throws IOException;

    boolean isDir(String path) throws IOException;

    void mkdir(String path) throws IOException;

    void delete(String path) throws IOException;

    void rename(String from, String to) throws IOException;

    void move(String from, String to) throws IOException;

    void copy(String from, String to) throws IOException;

    String parent(String path);

    String child(String dir, String name);
}
