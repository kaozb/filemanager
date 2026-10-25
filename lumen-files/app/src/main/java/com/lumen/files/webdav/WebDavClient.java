package com.lumen.files.webdav;

import android.util.Base64;
import android.util.Xml;

import com.lumen.files.Entry;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 极简 WebDAV 客户端：仅 PROPFIND / GET / PUT / MKCOL / DELETE / MOVE / COPY。
 * 使用 HttpURLConnection 与流式读写，避免整文件进入内存。
 */
public final class WebDavClient {

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/>"
                    + "</d:prop></d:propfind>";

    private final String base;
    private final String authorization;

    public WebDavClient(String base, String user, String password) {
        String normalized = base == null ? "" : base.trim();
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        this.base = normalized;
        String raw = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        this.authorization = "Basic " + Base64.encodeToString(
                raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public List<Entry> list(String path, int depth) throws IOException {
        return request(path, depth, depth > 0);
    }

    public Entry stat(String path) throws IOException {
        List<Entry> entries = request(path, 0, false);
        String wanted = normalize(path);
        for (Entry entry : entries) {
            if (normalize(entry.path).equals(wanted)) {
                return entry;
            }
        }
        return entries.isEmpty() ? null : entries.get(0);
    }

    public boolean exists(String path) throws IOException {
        int code = propfindCode(path, 0);
        if (code == 207 || code == 200) {
            return true;
        }
        if (code == 404) {
            return false;
        }
        checkCode(null, code);
        return false;
    }

    public InputStream get(String path) throws IOException {
        final HttpURLConnection connection = open("GET", path);
        int code = connection.getResponseCode();
        check(connection, code, 200, 206);
        InputStream stream = connection.getInputStream();
        return new FilterInputStream(stream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    connection.disconnect();
                }
            }
        };
    }

    public OutputStream put(String path) throws IOException {
        final HttpURLConnection connection = open("PUT", path);
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        final OutputStream raw = connection.getOutputStream();
        return new FilterOutputStream(new BufferedOutputStream(raw, 64 * 1024)) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    super.close();
                } catch (IOException error) {
                    connection.disconnect();
                    throw error;
                }
                int code;
                try {
                    code = connection.getResponseCode();
                } catch (IOException error) {
                    connection.disconnect();
                    throw error;
                }
                try {
                    check(connection, code, 200, 201, 204);
                } finally {
                    connection.disconnect();
                }
            }
        };
    }

    public void mkcol(String path) throws IOException {
        HttpURLConnection connection = open("MKCOL", path);
        try {
            int code = connection.getResponseCode();
            check(connection, code, 200, 201, 204);
        } finally {
            connection.disconnect();
        }
    }

    public void delete(String path) throws IOException {
        HttpURLConnection connection = open("DELETE", path);
        try {
            int code = connection.getResponseCode();
            if (code == 404) {
                return;
            }
            check(connection, code, 200, 204);
        } finally {
            connection.disconnect();
        }
    }

    public void move(String from, String to) throws IOException {
        transfer("MOVE", from, to);
    }

    public void copy(String from, String to) throws IOException {
        transfer("COPY", from, to);
    }

    private void transfer(String method, String from, String to) throws IOException {
        HttpURLConnection connection = open(method, from);
        try {
            connection.setRequestProperty("Destination", url(to));
            connection.setRequestProperty("Overwrite", "T");
            int code = connection.getResponseCode();
            check(connection, code, 200, 201, 204);
        } finally {
            connection.disconnect();
        }
    }

    private List<Entry> request(String path, int depth, boolean skipSelf) throws IOException {
        HttpURLConnection connection = open("PROPFIND", path);
        try {
            connection.setRequestProperty("Depth", String.valueOf(depth));
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            sendBody(connection);
            int code = connection.getResponseCode();
            check(connection, code, 200, 207);
            ArrayList<Entry> entries = new ArrayList<>();
            InputStream input = connection.getInputStream();
            try {
                parse(input, path, skipSelf, entries);
            } finally {
                input.close();
            }
            return entries;
        } finally {
            connection.disconnect();
        }
    }

    private int propfindCode(String path, int depth) throws IOException {
        HttpURLConnection connection = open("PROPFIND", path);
        try {
            connection.setRequestProperty("Depth", String.valueOf(depth));
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            sendBody(connection);
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            return code;
        } finally {
            connection.disconnect();
        }
    }

    private static void sendBody(HttpURLConnection connection) throws IOException {
        byte[] body = PROPFIND_BODY.getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream out = connection.getOutputStream();
        out.write(body);
        out.close();
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        URL url = new URL(url(path));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", authorization);
        connection.setRequestProperty("User-Agent", "LumenFiles/1.0 (Android)");
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private String url(String path) {
        String value = path == null ? "" : path;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        StringBuilder builder = new StringBuilder(base);
        String[] parts = value.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(encode(parts[i]));
        }
        return builder.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException error) {
            return value;
        }
    }

    private static String percentDecode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    private void parse(InputStream input, String requestPath, boolean skipSelf,
                       List<Entry> entries) throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            String href = null;
            String display = null;
            long size = -1;
            long modified = 0;
            boolean collection = false;
            boolean inResponse = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("response".equals(tag)) {
                        inResponse = true;
                        href = null;
                        display = null;
                        size = -1;
                        modified = 0;
                        collection = false;
                    } else if (inResponse) {
                        if ("href".equals(tag)) {
                            href = readText(parser);
                        } else if ("displayname".equals(tag)) {
                            display = readText(parser);
                        } else if ("getcontentlength".equals(tag)) {
                            String text = readText(parser);
                            try {
                                size = Long.parseLong(text.trim());
                            } catch (Exception ignored) {
                            }
                        } else if ("getlastmodified".equals(tag)) {
                            modified = parseDate(readText(parser));
                        } else if ("collection".equals(tag)) {
                            collection = true;
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && "response".equals(parser.getName())) {
                    if (!inResponse) {
                        continue;
                    }
                    inResponse = false;
                    if (href == null) {
                        continue;
                    }
                    String entryPath = pathOf(href);
                    if (skipSelf && normalize(entryPath).equals(normalize(requestPath))) {
                        continue;
                    }
                    String name = display == null ? "" : display.trim();
                    if (name.isEmpty()) {
                        name = lastName(entryPath);
                    }
                    if (name.isEmpty()) {
                        continue;
                    }
                    String storedPath = normalize(entryPath);
                    if (collection && !"/".equals(storedPath)) {
                        storedPath += "/";
                    }
                    entries.add(new Entry(name, storedPath, collection,
                            collection ? 0 : size, modified));
                }
            }
        } catch (XmlPullParserException error) {
            throw new IOException("无法解析服务器返回内容");
        }
    }

    private static String readText(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        StringBuilder builder = new StringBuilder();
        int event = parser.next();
        while (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT
                || event == XmlPullParser.ENTITY_REF) {
            if (parser.getText() != null) {
                builder.append(parser.getText());
            }
            event = parser.next();
        }
        return builder.toString();
    }

    private String pathOf(String href) {
        String path = href;
        try {
            path = new URL(new URL(base), href).getPath();
        } catch (MalformedURLException ignored) {
        }
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String basePath;
        try {
            basePath = new URL(base).getPath();
        } catch (MalformedURLException error) {
            basePath = "/";
        }
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        String relative;
        if (path.startsWith(basePath)) {
            relative = path.substring(basePath.length());
        } else {
            int slash = path.lastIndexOf('/');
            relative = slash >= 0 ? path.substring(slash + 1) : path;
        }
        return "/" + percentDecode(relative);
    }

    private static String lastName(String path) {
        String value = path;
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String value = path;
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static long parseDate(String value) {
        if (value == null) {
            return 0;
        }
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(true);
                Date date = format.parse(value.trim());
                if (date != null) {
                    return date.getTime();
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static void check(HttpURLConnection connection, int code, int... allowed)
            throws IOException {
        for (int value : allowed) {
            if (code == value) {
                return;
            }
        }
        checkCode(connection, code);
    }

    private static void checkCode(HttpURLConnection connection, int code) throws IOException {
        String message;
        switch (code) {
            case 401:
                message = "认证失败，请检查用户名与密码";
                break;
            case 403:
                message = "没有访问权限";
                break;
            case 404:
                message = "路径不存在";
                break;
            case 405:
                message = "服务器不允许此操作";
                break;
            case 409:
                message = "目标目录不存在";
                break;
            case 423:
                message = "文件被服务器锁定";
                break;
            case 507:
                message = "服务器存储空间不足";
                break;
            default:
                if (code >= 300 && code < 400 && connection != null) {
                    String location = connection.getHeaderField("Location");
                    message = "服务器要求跳转" + (location == null ? "" : "：" + location)
                            + "，请修改服务器地址";
                } else {
                    message = "服务器返回错误 " + code;
                }
                break;
        }
        throw new IOException(message);
    }
}
