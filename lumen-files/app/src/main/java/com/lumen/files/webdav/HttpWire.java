package com.lumen.files.webdav;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTP/1.1 通道。
 *
 * <p>Android 的 HttpURLConnection 只允许标准方法，调用 PROPFIND / MKCOL / MOVE / COPY
 * 会直接抛错。这里用 Socket 自己拼请求，既能支持 WebDAV 全部方法，也没有额外依赖。</p>
 */
final class HttpWire {

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int MAX_RESPONSE = 32 * 1024 * 1024;
    private static final int CHUNK = 32 * 1024;

    private HttpWire() {
    }

    static Request request(String method, URL url, String authorization) {
        return new Request(method, url, authorization);
    }

    static final class Request {

        private final String method;
        private final URL url;
        private final String authorization;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;

        private Request(String method, URL url, String authorization) {
            this.method = method;
            this.url = url;
            this.authorization = authorization;
        }

        Request header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        Request body(byte[] value) {
            this.body = value;
            return this;
        }

        Reply execute() throws IOException {
            Socket socket = connect(url);
            BufferedInputStream input = null;
            try {
                input = new BufferedInputStream(socket.getInputStream(), CHUNK);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), CHUNK);
                writeHead(out, method, url, authorization, headers,
                        body == null ? -1 : body.length);
                if (body != null && body.length > 0) {
                    out.write(body);
                }
                out.flush();
                return Reply.read(socket, input, method);
            } catch (IOException error) {
                closeQuietly(socket);
                throw error;
            }
        }
    }

    /** PUT 这类流式上传：先拿输出流，写完再取响应。 */
    static Upload upload(URL url, String authorization, String contentType) throws IOException {
        Socket socket = connect(url);
        BufferedInputStream input = null;
        try {
            input = new BufferedInputStream(socket.getInputStream(), CHUNK);
            OutputStream raw = new BufferedOutputStream(socket.getOutputStream(), CHUNK);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            long head = writeHead(raw, "PUT", url, authorization, headers, -2);
            raw.flush();
            return new Upload(socket, input, raw, head);
        } catch (IOException error) {
            closeQuietly(socket);
            throw error;
        }
    }

    static final class Upload implements Closeable {

        private final Socket socket;
        private final BufferedInputStream input;
        private final OutputStream raw;
        private final ChunkedSink sink;
        private final long headLength;
        private boolean finished;

        private Upload(Socket socket, BufferedInputStream input, OutputStream raw,
                       long headLength) {
            this.socket = socket;
            this.input = input;
            this.raw = raw;
            this.headLength = headLength;
            this.sink = new ChunkedSink(raw);
        }

        OutputStream stream() {
            return sink;
        }

        void finishBody() throws IOException {
            if (!finished) {
                finished = true;
                sink.finish();
            }
        }

        Reply reply() throws IOException {
            finishBody();
            return Reply.read(socket, input, "PUT");
        }

        /** 仅用于日志/调试：请求头长度。 */
        long headLength() {
            return headLength;
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }

    static final class Reply implements Closeable {

        final int code;
        final String message;
        final Map<String, String> headers;
        final InputStream body;
        private final Socket socket;

        private Reply(int code, String message, Map<String, String> headers,
                      InputStream body, Socket socket) {
            this.code = code;
            this.message = message;
            this.headers = headers;
            this.body = body;
            this.socket = socket;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        boolean redirect() {
            return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
        }

        byte[] bytes() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(CHUNK);
            byte[] chunk = new byte[CHUNK];
            int read;
            int total = 0;
            while ((read = body.read(chunk)) != -1) {
                total += read;
                if (total > MAX_RESPONSE) {
                    throw new IOException("服务器返回内容过大");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
            }
            closeQuietly(socket);
        }

        private static Reply read(Socket socket, BufferedInputStream input, String method)
                throws IOException {
            String status = readLine(input);
            if (status == null || !status.startsWith("HTTP/")) {
                throw new IOException("服务器响应无效，请检查地址与端口");
            }
            int first = status.indexOf(' ');
            int second = status.indexOf(' ', first + 1);
            String codeText = first < 0 ? "" : status.substring(first + 1,
                    second < 0 ? status.length() : second);
            int code;
            try {
                code = Integer.parseInt(codeText.trim());
            } catch (NumberFormatException error) {
                throw new IOException("服务器响应无效：" + status);
            }
            String message = second < 0 ? "" : status.substring(second + 1);
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            InputStream body;
            String encoding = headers.get("transfer-encoding");
            String length = headers.get("content-length");
            if (encoding != null && encoding.toLowerCase(Locale.US).contains("chunked")) {
                body = new ChunkedInput(input);
            } else if (length != null) {
                long size;
                try {
                    size = Long.parseLong(length.trim());
                } catch (NumberFormatException error) {
                    size = -1;
                }
                body = size < 0 ? input : new LimitedInput(input, size);
            } else {
                body = input;
            }
            if (code == 204 || code == 304 || "HEAD".equalsIgnoreCase(method)) {
                body = new ByteArrayInputStream(new byte[0]);
            }
            String contentEncoding = headers.get("content-encoding");
            if (contentEncoding != null
                    && contentEncoding.toLowerCase(Locale.US).contains("gzip")) {
                try {
                    body = new GZIPInputStream(body);
                } catch (IOException ignored) {
                }
            }
            return new Reply(code, message, headers, body, socket);
        }
    }

    // ------------------------------------------------------------------ 底层读写

    private static Socket connect(URL url) throws IOException {
        String protocol = url.getProtocol().toLowerCase(Locale.US);
        boolean https = "https".equals(protocol);
        if (!https && !"http".equals(protocol)) {
            throw new IOException("不支持的协议：" + protocol);
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("服务器地址无效");
        }
        int port = url.getPort() > 0 ? url.getPort() : (https ? 443 : 80);
        String bare = host;
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(bare, port), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);
            if (!https) {
                return socket;
            }
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, host, port, true);
            ssl.setSoTimeout(READ_TIMEOUT);
            ssl.startHandshake();
            SSLSession session = ssl.getSession();
            if (session == null
                    || !HttpsURLConnection.getDefaultHostnameVerifier().verify(bare, session)) {
                closeQuietly(ssl);
                throw new IOException("HTTPS 证书校验失败，请检查服务器域名");
            }
            return ssl;
        } catch (IOException error) {
            closeQuietly(socket);
            throw error;
        }
    }

    /** contentLength: >=0 固定长度；-2 分块传输。 */
    private static long writeHead(OutputStream out, String method, URL url, String authorization,
                                  Map<String, String> headers, int contentLength)
            throws IOException {
        String protocol = url.getProtocol().toLowerCase(Locale.US);
        boolean https = "https".equals(protocol);
        int port = url.getPort() > 0 ? url.getPort() : (https ? 443 : 80);
        String path = url.getFile();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        builder.append("Host: ").append(hostHeader(url, port, https)).append("\r\n");
        builder.append("User-Agent: LumenFiles/1.0 (Android)\r\n");
        builder.append("Accept: */*\r\n");
        builder.append("Accept-Encoding: identity\r\n");
        builder.append("Connection: close\r\n");
        if (authorization != null) {
            builder.append("Authorization: ").append(authorization).append("\r\n");
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        if (contentLength >= 0) {
            builder.append("Content-Length: ").append(contentLength).append("\r\n");
        } else if (contentLength == -2) {
            builder.append("Transfer-Encoding: chunked\r\n");
        }
        builder.append("\r\n");
        byte[] head = builder.toString().getBytes(StandardCharsets.UTF_8);
        out.write(head);
        return head.length;
    }

    private static String hostHeader(URL url, int port, boolean https) {
        String host = url.getHost();
        if (host.startsWith("[") || host.indexOf(':') < 0) {
            // 已是 IPv6 字面量或普通域名
        } else {
            host = "[" + host + "]";
        }
        boolean standard = (https && port == 443) || (!https && port == 80);
        return standard ? host : host + ":" + port;
    }

    static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > 8192) {
                throw new IOException("服务器响应头过长");
            }
        }
        if (value == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString("UTF-8");
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class LimitedInput extends InputStream {

        private final InputStream input;
        private long left;

        LimitedInput(InputStream input, long left) {
            this.input = input;
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int read = input.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0) {
                left -= read;
            }
            return read;
        }

        @Override
        public int available() {
            return (int) Math.min(left, Integer.MAX_VALUE);
        }
    }

    private static final class ChunkedInput extends InputStream {

        private final InputStream input;
        private long remaining;
        private boolean finished;

        ChunkedInput(InputStream input) {
            this.input = input;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (finished) {
                return -1;
            }
            if (remaining <= 0) {
                String line = readLine(input);
                while (line != null && line.isEmpty()) {
                    line = readLine(input);
                }
                if (line == null) {
                    finished = true;
                    return -1;
                }
                int semi = line.indexOf(';');
                if (semi >= 0) {
                    line = line.substring(0, semi);
                }
                try {
                    remaining = Long.parseLong(line.trim(), 16);
                } catch (NumberFormatException error) {
                    throw new IOException("服务器分块编码无效");
                }
                if (remaining == 0) {
                    String trailer;
                    while ((trailer = readLine(input)) != null && !trailer.isEmpty()) {
                        // 忽略 trailer
                    }
                    finished = true;
                    return -1;
                }
            }
            int read = input.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                finished = true;
                return -1;
            }
            remaining -= read;
            if (remaining == 0) {
                readLine(input);
            }
            return read;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }
    }

    private static final class ChunkedSink extends OutputStream {

        private final OutputStream raw;
        private final byte[] buffer = new byte[CHUNK];
        private int count;
        private boolean closed;

        ChunkedSink(OutputStream raw) {
            this.raw = raw;
        }

        @Override
        public void write(int value) throws IOException {
            if (count == buffer.length) {
                flushBuffer();
            }
            buffer[count++] = (byte) value;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            while (length > 0) {
                int room = buffer.length - count;
                int take = Math.min(room, length);
                System.arraycopy(data, offset, buffer, count, take);
                count += take;
                offset += take;
                length -= take;
                if (count == buffer.length) {
                    flushBuffer();
                }
            }
        }

        private void flushBuffer() throws IOException {
            if (count == 0) {
                return;
            }
            raw.write(Integer.toHexString(count).getBytes(StandardCharsets.US_ASCII));
            raw.write('\r');
            raw.write('\n');
            raw.write(buffer, 0, count);
            raw.write('\r');
            raw.write('\n');
            count = 0;
        }

        void finish() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            flushBuffer();
            raw.write('0');
            raw.write('\r');
            raw.write('\n');
            raw.write('\r');
            raw.write('\n');
            raw.flush();
        }

        @Override
        public void close() throws IOException {
            finish();
        }
    }
}
