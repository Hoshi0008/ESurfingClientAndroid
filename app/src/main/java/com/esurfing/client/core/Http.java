package com.esurfing.client.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 HTTP/1.1 客户端。
 *
 * <p>为什么不用 {@code HttpURLConnection}：认证报文必须从校园网那张网卡发出，
 * 需要把 socket 的源地址绑定到本机在校园网里的 IPv4 上；而且门户的响应头
 * （{@code Error-Code} / {@code Location}）要原样读到。自己撸一个 200 行不到的
 * 客户端比绕这些行为更省事，也更省安装包体积。</p>
 */
final class Http {

    private static final int DEFAULT_TIMEOUT = 10000;

    static final class Response {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        /** 按单字节字符解释响应体（解析 hex 密文时用）。 */
        String text() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }

        int length() {
            return body.length;
        }
    }

    private Http() {
    }

    static Response get(String url, Map<String, String> headers, String localIp) throws IOException {
        return request("GET", url, null, headers, localIp, DEFAULT_TIMEOUT);
    }

    static Response post(String url, byte[] body, Map<String, String> headers,
                         String localIp, int timeoutMs) throws IOException {
        return request("POST", url, body, headers, localIp, timeoutMs);
    }

    static Response request(String method, String url, byte[] body, Map<String, String> extraHeaders,
                            String localIp, int timeoutMs) throws IOException {
        if (url == null || !url.regionMatches(true, 0, "http://", 0, 7)) {
            throw new IOException("只支持 http:// 地址: " + url);
        }
        String rest = url.substring(7);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        String host = hostPort;
        int port = 80;
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IOException("端口不合法: " + url);
            }
        }
        if (host.isEmpty()) {
            throw new IOException("主机名为空: " + url);
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT;
        }

        Socket socket = new Socket();
        try {
            boolean loopback = host.startsWith("127.") || "localhost".equalsIgnoreCase(host);
            if (!loopback && localIp != null && !localIp.trim().isEmpty()) {
                try {
                    socket.bind(new InetSocketAddress(localIp.trim(), 0));
                } catch (IOException bindFailed) {
                    // 绑不上就从默认路由走，认证仍有机会成功
                    Log log = Log.current();
                    if (log != null) {
                        log.warn("绑定本机地址 " + localIp + " 失败，改用默认路由");
                    }
                }
            }
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);

            byte[] head = buildHead(method, host, port, path, body, extraHeaders);
            OutputStream out = socket.getOutputStream();
            out.write(head);
            if (body != null && body.length > 0) {
                out.write(body);
            }
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream(), 4096);
            int status = readStatus(in);
            Map<String, String> headers = readHeaders(in);
            byte[] payload = readBody(in, headers);
            return new Response(status, headers, payload);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static byte[] buildHead(String method, String host, int port, String path,
                                    byte[] body, Map<String, String> extraHeaders) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host);
        if (port != 80) {
            sb.append(':').append(port);
        }
        sb.append("\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                String k = e.getKey();
                if ("Host".equalsIgnoreCase(k) || "Content-Length".equalsIgnoreCase(k)
                        || "Connection".equalsIgnoreCase(k)) {
                    continue;
                }
                sb.append(k).append(": ").append(e.getValue() == null ? "" : e.getValue()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int readStatus(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
            throw new IOException("连接被对端关闭（没有 HTTP 响应）");
        }
        // HTTP/1.1 200 OK
        int sp = line.indexOf(' ');
        if (sp < 0) {
            throw new IOException("响应行不合法: " + line);
        }
        int sp2 = line.indexOf(' ', sp + 1);
        String code = sp2 < 0 ? line.substring(sp + 1) : line.substring(sp + 1, sp2);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new IOException("响应码不合法: " + line);
        }
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            String old = headers.get(name);
            headers.put(name, old == null ? value : old + ", " + value);
        }
        return headers;
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            return readChunked(in);
        }
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                int len = Integer.parseInt(cl.trim());
                if (len == 0) {
                    return new byte[0];
                }
                if (len > 0) {
                    return readFully(in, len);
                }
            } catch (NumberFormatException ignored) {
                // 落到按 EOF 读
            }
        }
        return readUntilEof(in);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        while (true) {
            String line = readLine(in);
            if (line == null) {
                break;
            }
            int semi = line.indexOf(';');
            String sizeText = (semi < 0 ? line : line.substring(0, semi)).trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size <= 0) {
                // 吃掉 trailer
                String t;
                while ((t = readLine(in)) != null && !t.isEmpty()) {
                    // ignore trailers
                }
                break;
            }
            bos.write(readFully(in, size));
            readLine(in); // 块尾 CRLF
        }
        return bos.toByteArray();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        if (off == len) {
            return out;
        }
        byte[] shorter = new byte[off];
        System.arraycopy(out, 0, shorter, 0, off);
        return shorter;
    }

    private static byte[] readUntilEof(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
