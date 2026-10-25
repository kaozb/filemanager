package com.lumen.files.webdav;

import android.util.Base64;
import android.util.Xml;

import com.lumen.files.Entry;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 WebDAV 客户端：PROPFIND / GET / PUT / MKCOL / DELETE / MOVE / COPY。
 * 走自研 HttpWire（Socket），因此不受 Android HttpURLConnection 的方法限制。
 */
public final class WebDavClient {

    private static final byte[] PROPFIND_BODY = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/>"
                    + "</d:prop></d:propfind>").getBytes(StandardCharsets.UTF_8);

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
        checkCode(code, null);
        return false;
    }

    public InputStream get(String path) throws IOException {
        final HttpWire.Reply reply = execute("GET", path, null, null);
        try {
            check(reply);
            InputStream stream = reply.body;
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    return stream.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return stream.read(buffer, offset, length);
                }

                @Override
                public int available() throws IOException {
                    return stream.available();
                }

                @Override
                public void close() {
                    reply.close();
                }
            };
        } catch (IOException error) {
            reply.close();
            throw error;
        }
    }

    public OutputStream put(String path) throws IOException {
        URL target = new URL(url(path));
        final HttpWire.Upload upload = HttpWire.upload(target, authorization,
                "application/octet-stream");
        return new OutputStream() {
            private boolean closed;

            @Override
            public void write(int value) throws IOException {
                upload.stream().write(value);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                upload.stream().write(buffer, offset, length);
            }

            @Override
            public void flush() throws IOException {
                upload.stream().flush();
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                HttpWire.Reply reply = null;
                try {
                    upload.finishBody();
                    reply = upload.reply();
                    check(reply);
                } finally {
                    if (reply != null) {
                        reply.close();
                    }
                    upload.close();
                }
            }
        };
    }

    public void mkcol(String path) throws IOException {
        HttpWire.Reply reply = execute("MKCOL", path, null, null);
        try {
            check(reply);
        } finally {
            reply.close();
        }
    }

    public void delete(String path) throws IOException {
        HttpWire.Reply reply = execute("DELETE", path, null, null);
        try {
            if (reply.code == 404) {
                return;
            }
            check(reply);
        } finally {
            reply.close();
        }
    }

    public void move(String from, String to) throws IOException {
        transfer("MOVE", from, to);
    }

    public void copy(String from, String to) throws IOException {
        transfer("COPY", from, to);
    }

    private void transfer(String method, String from, String to) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Destination", url(to));
        headers.put("Overwrite", "T");
        HttpWire.Reply reply = execute(method, from, headers, null);
        try {
            check(reply);
        } finally {
            reply.close();
        }
    }

    private List<Entry> request(String path, int depth, boolean skipSelf) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Depth", String.valueOf(depth));
        headers.put("Content-Type", "application/xml; charset=utf-8");
        HttpWire.Reply reply = execute("PROPFIND", path, headers, PROPFIND_BODY);
        try {
            check(reply);
            ArrayList<Entry> entries = new ArrayList<>();
            parse(reply.body, path, skipSelf, entries);
            return entries;
        } finally {
            reply.close();
        }
    }

    private int propfindCode(String path, int depth) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Depth", String.valueOf(depth));
        headers.put("Content-Type", "application/xml; charset=utf-8");
        HttpWire.Reply reply = execute("PROPFIND", path, headers, PROPFIND_BODY);
        try {
            return reply.code;
        } finally {
            reply.close();
        }
    }

    private HttpWire.Reply execute(String method, String path, Map<String, String> extra,
                                   byte[] body) throws IOException {
        URL target = new URL(url(path));
        for (int hop = 0; hop < 4; hop++) {
            HttpWire.Request request = HttpWire.request(method, target, authorization);
            if (extra != null) {
                for (Map.Entry<String, String> entry : extra.entrySet()) {
                    request.header(entry.getKey(), entry.getValue());
                }
            }
            if (body != null) {
                request.body(body);
            }
            HttpWire.Reply reply = request.execute();
            if (reply.redirect()) {
                String location = reply.header("Location");
                reply.close();
                if (location == null || location.isEmpty()) {
                    throw new IOException("服务器要求跳转但未提供地址");
                }
                target = new URL(target, location);
                continue;
            }
            return reply;
        }
        throw new IOException("服务器跳转次数过多");
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

    private static void check(HttpWire.Reply reply) throws IOException {
        if (reply.code >= 200 && reply.code < 300) {
            return;
        }
        checkCode(reply.code, reply.header("Location"));
    }

    private static void checkCode(int code, String location) throws IOException {
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
                if (code >= 300 && code < 400) {
                    message = "服务器要求跳转" + (location == null ? "" : "：" + location)
                            + "，请检查服务器地址";
                } else {
                    message = "服务器返回错误 " + code;
                }
                break;
        }
        throw new IOException(message);
    }
}
