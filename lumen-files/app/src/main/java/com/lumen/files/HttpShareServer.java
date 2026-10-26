package com.lumen.files;

import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网远程管理：把一个本地目录以极简 Web 页面共享出去。
 *
 * <p>与 HttpWire 同思路——不引第三方依赖，用 ServerSocket 手写 HTTP/1.1。
 * 支持目录浏览、下载（含 Range 分段，浏览器可在线播放音视频）与
 * multipart/form-data 上传。同一时刻最多运行一个实例。</p>
 */
public final class HttpShareServer {

    private static final int CHUNK = 64 * 1024;
    private static final int SOCKET_TIMEOUT = 30000;

    private static volatile HttpShareServer active;

    /** 当前运行中的服务器，没有则返回 null。 */
    public static HttpShareServer current() {
        return active;
    }

    /** 在指定端口共享目录；端口被占用等失败时抛 IOException。 */
    public static HttpShareServer start(File root, int port) throws IOException {
        stopActive();
        if (!root.isDirectory()) {
            throw new IOException("目录不存在：" + root.getAbsolutePath());
        }
        HttpShareServer server = new HttpShareServer(root, port);
        server.begin();
        active = server;
        return server;
    }

    /** 停止当前服务器（若有）。 */
    public static void stopActive() {
        HttpShareServer server = active;
        active = null;
        if (server != null) {
            server.shutdown();
        }
    }

    /** 第一个可用的局域网 IPv4 地址，找不到返回 null。 */
    public static String lanAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    private final File root;
    private final String rootPath;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    private HttpShareServer(File root, int port) {
        this.root = root;
        this.port = port;
        String canonical;
        try {
            canonical = root.getCanonicalPath();
        } catch (IOException error) {
            canonical = root.getAbsolutePath();
        }
        rootPath = canonical;
    }

    public File root() {
        return root;
    }

    public int port() {
        return port;
    }

    // ------------------------------------------------------------------ 生命周期

    private void begin() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        Thread accept = new Thread(this::acceptLoop, "lumen-http-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void shutdown() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> serve(socket));
            } catch (IOException error) {
                if (running) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 请求处理

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT);
            InputStream input = new BufferedInputStream(socket.getInputStream(), CHUNK);
            OutputStream output = socket.getOutputStream();
            String request = readLine(input);
            if (request == null || request.isEmpty()) {
                return;
            }
            String[] parts = request.trim().split("\\s+");
            if (parts.length < 2) {
                sendError(output, 400, "请求无效");
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            Map<String, String> headers = readHeaders(input);
            route(input, output, method, parts[1], headers);
        } catch (IOException ignored) {
            // 客户端提前断开等，直接关闭即可
        } catch (RuntimeException ignored) {
        } finally {
            closeQuietly(socket);
        }
    }

    private void route(InputStream input, OutputStream output, String method,
                       String rawPath, Map<String, String> headers) throws IOException {
        String decoded = decodePath(rawPath);
        if (!decoded.startsWith("/")) {
            decoded = "/" + decoded;
        }
        if (method.equals("POST")) {
            handleUpload(input, output, decoded, headers);
            return;
        }
        boolean head = method.equals("HEAD");
        if (!method.equals("GET") && !head) {
            sendError(output, 405, "只支持 GET 与 POST");
            return;
        }
        File target = file(decoded);
        if (target == null) {
            sendError(output, 403, "禁止访问");
            return;
        }
        if (target.isDirectory()) {
            byte[] body = directoryHtml(decoded, target).getBytes(StandardCharsets.UTF_8);
            writeHead(output, 200, "OK", "text/html; charset=utf-8", body.length);
            if (!head) {
                output.write(body);
            }
        } else if (target.isFile()) {
            sendFile(output, target, headers, head);
        } else {
            sendError(output, 404, "未找到 " + decoded);
        }
    }

    /** 把 URL 路径解析成共享目录内的文件；越界（.. 逃逸）返回 null。 */
    private File file(String decoded) throws IOException {
        String trimmed = decoded == null ? "/" : decoded;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty() || trimmed.contains("\u0000")) {
            return null;
        }
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        File target = new File(root, trimmed);
        String canonical = target.getCanonicalPath();
        if (canonical.equals(rootPath) || canonical.startsWith(rootPath + File.separator)) {
            return target;
        }
        return null;
    }

    private void sendFile(OutputStream output, File target,
                          Map<String, String> headers, boolean headOnly) throws IOException {
        long total = target.length();
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        String range = headers.get("range");
        if (range != null && range.startsWith("bytes=") && total > 0) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            long from = -1;
            long to = -1;
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1).trim());
                    from = Math.max(0, total - suffix);
                } else if (dash > 0) {
                    from = Long.parseLong(spec.substring(0, dash).trim());
                    if (dash < spec.length() - 1) {
                        to = Long.parseLong(spec.substring(dash + 1).trim());
                    }
                }
            } catch (NumberFormatException ignored) {
            }
            if (from >= 0) {
                if (from >= total) {
                    writeHead(output, 416, "Range Not Satisfiable",
                            "text/html; charset=utf-8", 0, "Content-Range: bytes */" + total);
                    return;
                }
                start = from;
                end = to >= from ? Math.min(to, total - 1) : total - 1;
                partial = start > 0 || end < total - 1;
            }
        }
        long length = end - start + 1;
        String mime = mimeOf(target);
        List<String> extra = new ArrayList<>();
        if (partial) {
            extra.add("Content-Range: bytes " + start + "-" + end + "/" + total);
        }
        if (mime.equals("application/octet-stream")
                || mime.equals("application/vnd.android.package-archive")
                || mime.equals("application/zip")) {
            extra.add("Content-Disposition: attachment; filename*=UTF-8''"
                    + encodeName(target.getName()));
        }
        writeHead(output, partial ? 206 : 200,
                partial ? "Partial Content" : "OK", mime, length,
                extra.toArray(new String[0]));
        if (headOnly) {
            return;
        }
        InputStream input = new FileInputStream(target);
        try {
            long skipped = 0;
            while (skipped < start) {
                long step = input.skip(start - skipped);
                if (step <= 0) {
                    return;
                }
                skipped += step;
            }
            byte[] buffer = new byte[CHUNK];
            long remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        } finally {
            input.close();
        }
    }

    // ------------------------------------------------------------------ 上传

    private void handleUpload(InputStream input, OutputStream output, String decoded,
                              Map<String, String> headers) throws IOException {
        File dir = file(decoded);
        String boundary = boundaryOf(headers.get("content-type"));
        if (dir == null || !dir.isDirectory() || boundary == null) {
            sendError(output, 400, "上传请求无效");
            return;
        }
        String expect = headers.get("expect");
        if (expect != null && expect.toLowerCase(Locale.US).contains("100-continue")) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        try {
            saveParts(input, boundary, dir);
        } catch (IOException ignored) {
            // 中途断开：已保存的部分保留，仍然回跳刷新
        }
        writeHead(output, 303, "See Other", "text/html; charset=utf-8", 0,
                "Location: " + encodePath(decoded));
    }

    private int saveParts(InputStream input, String boundary, File dir) throws IOException {
        byte[] first = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] next = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        skipUntil(input, first);
        int saved = 0;
        while (true) {
            int a = input.read();
            int b = input.read();
            if (a == '-' && b == '-') {
                break;
            }
            if (a != '\r' || b != '\n') {
                throw new IOException("上传数据格式无效");
            }
            Map<String, String> partHeaders = readHeaders(input);
            String name = filename(partHeaders.get("content-disposition"));
            if (name == null || name.isEmpty()) {
                skipUntil(input, next);
                continue;
            }
            copyUntil(input, new FileOutputStream(partFile(dir, name)), next);
            saved++;
        }
        return saved;
    }

    /**
     * 把流复制到 output，直到遇到分隔符（分隔符本身不写入）；
     * output 为 null 时仅跳过；流提前结束抛 IOException。
     */
    private static long copyUntil(InputStream input, OutputStream output,
                                  byte[] delimiter) throws IOException {
        long total = 0;
        int matched = 0;
        int value;
        while ((value = input.read()) != -1) {
            byte current = (byte) value;
            if (current == delimiter[matched]) {
                if (++matched == delimiter.length) {
                    return total;
                }
            } else {
                if (matched > 0) {
                    if (output != null) {
                        output.write(delimiter, 0, matched);
                    }
                    total += matched;
                    matched = 0;
                }
                if (current == delimiter[0]) {
                    matched = 1;
                } else {
                    if (output != null) {
                        output.write(current);
                    }
                    total++;
                }
            }
        }
        throw new IOException("上传数据不完整");
    }

    private static long skipUntil(InputStream input, byte[] delimiter) throws IOException {
        return copyUntil(input, null, delimiter);
    }

    /** 从 Content-Disposition 里取 filename，并剥掉客户端可能带来的路径部分。 */
    private static String filename(String disposition) {
        if (disposition == null) {
            return null;
        }
        int key = disposition.toLowerCase(Locale.US).indexOf("filename=");
        if (key < 0) {
            return null;
        }
        String rest = disposition.substring(key + "filename=".length()).trim();
        String name;
        if (rest.startsWith("\"")) {
            StringBuilder value = new StringBuilder(rest.length());
            for (int i = 1; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '\\' && i + 1 < rest.length()) {
                    value.append(rest.charAt(++i));
                } else if (c == '"') {
                    break;
                } else {
                    value.append(c);
                }
            }
            name = value.toString();
        } else {
            int end = rest.indexOf(';');
            name = (end < 0 ? rest : rest.substring(0, end)).trim();
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    private static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.US);
        if (!lower.contains("multipart/form-data")) {
            return null;
        }
        int key = lower.indexOf("boundary=");
        if (key < 0) {
            return null;
        }
        String value = contentType.substring(key + "boundary=".length()).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            return end > 0 ? value.substring(1, end) : null;
        }
        int end = value.indexOf(';');
        return end < 0 ? value : value.substring(0, end);
    }

    /** 同名文件自动加 " (1)"、" (2)" 后缀。 */
    private static File partFile(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) {
            return target;
        }
        String bare = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            bare = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i <= 999; i++) {
            File candidate = new File(dir, bare + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, bare + "." + System.currentTimeMillis() + ext);
    }

    // ------------------------------------------------------------------ 页面

    private String directoryHtml(String decoded, File dir) {
        String label = root.getName().isEmpty() ? "共享目录" : root.getName();
        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>").append(escape(label)).append("</title><style>")
            .append("*{box-sizing:border-box;margin:0}")
            .append("body{background:#f4f5f7;color:#202124;padding-bottom:92px;")
            .append("font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif}")
            .append(".wrap{max-width:680px;margin:0 auto;padding:20px 14px}")
            .append("h1{font-size:17px;font-weight:600;margin-bottom:12px;word-break:break-all}")
            .append(".crumb{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:12px}")
            .append(".crumb a{font-size:12.5px;color:#444746;text-decoration:none;background:#fff;")
            .append("padding:4px 11px;border-radius:99px;box-shadow:0 1px 2px rgba(0,0,0,.06)}")
            .append(".list{background:#fff;border-radius:14px;overflow:hidden;")
            .append("box-shadow:0 1px 3px rgba(0,0,0,.07)}")
            .append("a.row{display:flex;align-items:center;gap:12px;padding:11px 16px;")
            .append("border-bottom:1px solid #f1f3f4;text-decoration:none;color:inherit}")
            .append("a.row:last-child{border-bottom:0}a.row:active{background:#f4f5f7}")
            .append(".name{flex:1;min-width:0;font-size:14.5px;")
            .append("overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
            .append(".dir>.name{font-weight:600}.meta{font-size:12px;color:#80868b;white-space:nowrap}")
            .append(".empty{padding:36px 0;text-align:center;font-size:13.5px;color:#80868b}")
            .append(".up{position:fixed;left:0;right:0;bottom:0;background:#fff;")
            .append("box-shadow:0 -2px 10px rgba(0,0,0,.08);padding:10px 14px 12px}")
            .append(".bar{max-width:680px;margin:0 auto;display:flex;gap:10px;align-items:center}")
            .append(".bar input{flex:1;min-width:0;font-size:12.5px;color:#444746}")
            .append("button{border:0;border-radius:99px;padding:9px 20px;font-size:13.5px;")
            .append("font-weight:600;color:#fff;background:#1a73e8}")
            .append("</style></head><body><div class=\"wrap\"><h1>")
            .append(escape(label)).append("</h1><div class=\"crumb\">");

        String current = trimSlash(decoded);
        html.append("<a href=\"/\">").append(escape(label)).append("</a>");
        String walked = "";
        for (String part : current.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            walked = walked + "/" + encodeName(part);
            html.append("<a href=\"").append(walked).append("\">")
                .append(escape(part)).append("</a>");
        }
        html.append("</div><div class=\"list\">");

        if (!current.isEmpty()) {
            int cut = current.lastIndexOf('/');
            String parentHref = cut <= 0 ? "/" : encodePath(current.substring(0, cut));
            html.append("<a class=\"row\" href=\"").append(parentHref)
                .append("\"><span class=\"name\">../</span></a>");
        }

        List<File> children = new ArrayList<>();
        File[] listed = dir.listFiles();
        if (listed != null) {
            children.addAll(Arrays.asList(listed));
        }
        Collections.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        if (children.isEmpty()) {
            html.append("<div class=\"empty\">空文件夹</div>");
        } else {
            for (File child : children) {
                boolean isDir = child.isDirectory();
                String href = encodePath(current + "/" + child.getName());
                html.append("<a class=\"row").append(isDir ? " dir" : "")
                    .append("\" href=\"").append(href).append("\">")
                    .append("<span class=\"name\">").append(escape(child.getName()))
                    .append(isDir ? "/" : "").append("</span><span class=\"meta\">")
                    .append(isDir ? "—" : Format.size(child.length()))
                    .append(" · ").append(Format.date(child.lastModified()))
                    .append("</span></a>");
            }
        }
        html.append("</div></div><div class=\"up\"><form class=\"bar\" method=\"post\" ")
            .append("enctype=\"multipart/form-data\"><input type=\"file\" name=\"file\" ")
            .append("multiple required><button type=\"submit\">上传</button></form></div>")
            .append("</body></html>");
        return html.toString();
    }

    // ------------------------------------------------------------------ HTTP 读写

    private static void writeHead(OutputStream output, int code, String status,
                                  String contentType, long length, String... extra)
            throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Connection: close\r\n");
        if (extra != null) {
            for (String line : extra) {
                if (line != null) {
                    head.append(line).append("\r\n");
                }
            }
        }
        head.append("\r\n");
        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void sendError(OutputStream output, int code, String message)
            throws IOException {
        String status = code == 400 ? "Bad Request"
                : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found"
                : code == 405 ? "Method Not Allowed"
                : code == 416 ? "Range Not Satisfiable"
                : "Error";
        byte[] body = ("<html><head><meta charset=\"utf-8\"><title>" + code
                + "</title></head><body style=\"font-family:system-ui;text-align:center;"
                + "padding-top:60px\"><h1 style=\"color:#5f6368\">" + code
                + "</h1><p>" + escape(message) + "</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        writeHead(output, code, status, "text/html; charset=utf-8", body.length);
        output.write(body);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > 8192) {
                throw new IOException("请求头过长");
            }
        }
        if (value == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString("UTF-8");
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static String mimeOf(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        if (name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")) {
            return "application/vnd.android.package-archive";
        }
        if (name.endsWith(".zip")) {
            return "application/zip";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "application/octet-stream";
        }
        String mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1));
        return mime == null ? "application/octet-stream" : mime;
    }

    // ------------------------------------------------------------------ 编码工具

    /** 只解码 %XX；不把 + 当空格（那是表单规则，不是路径规则）。 */
    private static String decodePath(String raw) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int high = hex(raw.charAt(i + 1));
                int low = hex(raw.charAt(i + 2));
                if (high >= 0 && low >= 0) {
                    buffer.write(high << 4 | low);
                    i += 3;
                    continue;
                }
            }
            if (c < 0x80) {
                buffer.write((byte) c);
            } else {
                // 请求行里的原始 UTF-8 字符：按 Unicode 码点还原成字节
                int codePoint = Character.codePointAt(raw, i);
                byte[] encoded = new String(Character.toChars(codePoint))
                        .getBytes(StandardCharsets.UTF_8);
                buffer.write(encoded, 0, encoded.length);
                i += Character.charCount(codePoint);
                continue;
            }
            i++;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static final String SAFE = "-._~!$&'()*+,;=:@"
            + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static String encodeName(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if (SAFE.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format(Locale.US, "%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                out.append('/').append(encodeName(part));
            }
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static String trimSlash(String decoded) {
        String value = decoded;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') {
                out.append("&amp;");
            } else if (c == '<') {
                out.append("&lt;");
            } else if (c == '>') {
                out.append("&gt;");
            } else if (c == '"') {
                out.append("&quot;");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
