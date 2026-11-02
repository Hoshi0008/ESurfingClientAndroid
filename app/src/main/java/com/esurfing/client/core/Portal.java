package com.esurfing.client.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 广东天翼校园网 Portal 认证协议实现（cdc-http-pap）。
 *
 * <p>流程：探测门户(--&gt;wlanacip) -&gt; ticket.cgi 取 ZSM 算法 -&gt; ticket.cgi 取 ticket
 * -&gt; auth.cgi 登录 -&gt; keep.cgi 心跳 -&gt; term.cgi 下线。</p>
 *
 * <p>纯 Java，可在桌面 JVM 上直接跑，方便与参考后端做互操作测试。</p>
 */
public final class Portal {

    /** 探测在线状态 / 门户跳转用的地址。 */
    static final String[] DETECT_URLS = {
            "http://connect.rom.miui.com/generate_204",
            "http://www.baidu.com/",
            "http://www.qq.com/",
    };

    static final String ACCEPT =
            "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*";
    public static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    /** 通道 -&gt; {User-Agent, ostag}。 */
    static final String[][] CHANNELS = {
            {"windows", "CCTP/WinSVR5/1068", "Windows 11"},
            {"linux", "CCTP/Linux64/1003", "Linux"},
            {"android", "CCTP/android11_64/2104", "Android 14"},
            {"macos", "CCTP/macdy/5019", "macOS,14.4"},
            {"ios", "CCTP/iOSdy/4023", "iOS 17.0"},
    };

    /** 登录结果。 */
    public static final class LoginResult {
        public final boolean accepted;
        public final String code;
        public final String sub;
        public final String xml;

        LoginResult(boolean accepted, String code, String sub, String xml) {
            this.accepted = accepted;
            this.code = code;
            this.sub = sub;
            this.xml = xml;
        }
    }

    /** 心跳结果。 */
    public static final class KeepResult {
        public final int interval;
        public final String code;
        public final String xml;

        KeepResult(int interval, String code, String xml) {
            this.interval = interval;
            this.code = code;
            this.xml = xml;
        }
    }

    private String ip;
    private String mac;
    private String localIp;
    private final String ua;
    private final String ostag;
    private final String schoolid;
    private final String domain;
    private final String area;
    private final int timeoutMs;

    private String hostName;
    private String clientId;
    private String algoId = ZERO_UUID;
    private DoubleAesCbc cipher;
    private String ticket = "";
    private String lastRedirect = "";
    private String server = "";

    private String ticketUrl = "";
    private String authUrl = "";
    private String keepUrl = "";
    private String termUrl = "";

    private int port = 7001;

    public Portal(String ip, String mac, String channel, String schoolid, String domain,
                  String area, int port) {
        this.ip = ip == null ? "" : ip;
        this.mac = mac == null ? "" : mac;
        this.localIp = this.ip;
        this.schoolid = schoolid == null ? "" : schoolid;
        this.domain = domain == null ? "" : domain;
        this.area = area == null ? "" : area;
        this.port = port > 0 && port < 65536 ? port : 7001;
        this.timeoutMs = 10000;
        String[] ch = channel(channel);
        this.ua = ch[0];
        this.ostag = ch[1];
        refreshIdentity();
    }

    private static String[] channel(String name) {
        String key = name == null ? "android" : name.trim().toLowerCase(Locale.US);
        for (String[] row : CHANNELS) {
            if (row[0].equals(key)) {
                return new String[]{row[1], row[2]};
            }
        }
        return new String[]{CHANNELS[2][1], CHANNELS[2][2]};
    }

    // ------------------------------------------------------------------ 身份

    /** 换一套随机身份（服务端会随机下发失效算法，靠换身份重取）。 */
    public void refreshIdentity() {
        hostName = IpUtil.randomHostName();
        clientId = IpUtil.randomUuid();
    }

    public String ua() {
        return ua;
    }

    public String ostag() {
        return ostag;
    }

    public String ip() {
        return ip;
    }

    public String mac() {
        return mac;
    }

    public void mac(String value) {
        this.mac = value == null ? "" : value;
    }

    public String clientId() {
        return clientId;
    }

    public String algoId() {
        return algoId;
    }

    public String ticket() {
        return ticket;
    }

    public String server() {
        return server;
    }

    public String keepUrl() {
        return keepUrl;
    }

    public String termUrl() {
        return termUrl;
    }

    public String lastRedirect() {
        return lastRedirect;
    }

    public void localIp(String value) {
        if (value != null && !value.isEmpty()) {
            this.localIp = value;
        }
    }

    // ------------------------------------------------------------------ 门户发现

    /**
     * 探测门户并取 {@code wlanacip}。
     *
     * @param servers 配置里的认证服务器（可为空）
     * @return AC 地址；探测失败返回 ""
     */
    public String discover(List<String> servers) {
        for (String url : DETECT_URLS) {
            try {
                Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, timeoutMs);
                String loc = r.header("location");
                if (loc != null && loc.startsWith("http")) {
                    lastRedirect = loc;
                    if (loc.contains("wlanuserip")) {
                        String uip = regex(loc, "wlanuserip=([0-9]{1,3}(?:\\.[0-9]{1,3}){3})");
                        if (uip != null) {
                            ip = uip;
                            localIp = uip;
                        }
                        String host = hostOf(loc);
                        if (server.isEmpty() && !host.isEmpty()) {
                            server = host;
                        }
                        note(Log.P_STEP, "发现门户跳转: " + loc);
                        String acip = regex(loc, "wlanacip=([0-9]{1,3}(?:\\.[0-9]{1,3}){3})");
                        return acip == null ? "" : acip;
                    }
                }
            } catch (IOException e) {
                note(Log.P_INFO, "探测 " + url + " 失败: " + e.getMessage());
            }
        }
        List<String> candidates = new ArrayList<String>();
        if (!server.isEmpty()) {
            candidates.add(server);
        }
        if (servers != null) {
            for (String s : servers) {
                if (s != null && !s.trim().isEmpty() && !candidates.contains(s.trim())) {
                    candidates.add(s.trim());
                }
            }
        }
        for (String srv : candidates) {
            try {
                String url = "http://" + srv + ":" + port + "/index.cgi?wlanuserip=" + ip;
                Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, timeoutMs);
                String txt = new String(r.body, StandardCharsets.UTF_8);
                String acip = regex(txt, "wlanacip=([0-9]{1,3}(?:\\.[0-9]{1,3}){3})");
                if (acip != null) {
                    note(Log.P_STEP, "从 index.cgi 兜底取到 wlanacip=" + acip + "（" + srv + "）");
                    server = srv;
                    return acip;
                }
            } catch (IOException e) {
                note(Log.P_INFO, "兜底探测 " + srv + " 失败: " + e.getMessage());
            }
        }
        return "";
    }

    /** 是否已在线（能拿到 204 / 正常网页说明放行）。 */
    public boolean probeOnline() {
        for (String url : DETECT_URLS) {
            try {
                Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, 6000);
                if (r.status == 204) {
                    return true;
                }
                String loc = r.header("location");
                if (r.status == 200 && (loc == null || !loc.startsWith("http"))) {
                    return true;
                }
            } catch (IOException ignored) {
                // 换下一个
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 配置

    /** 按服务器 + AC 地址拼出四个接口地址。 */
    public void configure(String server, String acip) {
        String srv = server == null ? "" : server.trim();
        if (srv.contains("://")) {
            srv = srv.replaceFirst("^https?://", "");
        }
        int slash = srv.indexOf('/');
        if (slash > 0) {
            srv = srv.substring(0, slash);
        }
        if (!srv.isEmpty()) {
            this.server = srv;
        }
        String base = "http://" + this.server + ":" + port;
        ticketUrl = base + "/ticket.cgi?wlanuserip=" + ip + "&wlanacip=" + acip;
        authUrl = base + "/auth.cgi";
        keepUrl = base + "/keep.cgi";
        termUrl = base + "/term.cgi";
        note(Log.P_STEP, "接口地址: " + base + "  wlanuserip=" + ip + " wlanacip=" + acip);

        // 门户页面里可能覆盖 ticket-url（少数校区）
        try {
            String url = base + "/index.cgi?wlanuserip=" + ip + "&wlanacip=" + acip;
            Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, 8000);
            String html = new String(r.body, StandardCharsets.UTF_8);
            if (html.contains("config.campus.js.chinatelecom.com")) {
                String start = "<!--//config.campus.js.chinatelecom.com";
                String end = "//config.campus.js.chinatelecom.com-->";
                int a = html.indexOf(start);
                int b = end.isEmpty() ? -1 : html.indexOf(end, a < 0 ? 0 : a);
                if (a >= 0 && b > a) {
                    String conf = html.substring(a, b);
                    String tu = Xml.cdata(conf, "ticket-url");
                    if (!tu.isEmpty()) {
                        ticketUrl = tu;
                        note(Log.P_STEP, "门户下发的 ticket-url: " + tu);
                    }
                }
            }
        } catch (IOException ignored) {
            // 门户页面拿不到不影响认证
        }
    }

    // ------------------------------------------------------------------ 取算法

    /** 反复要 ZSM，直到拿到「服务端会认、我们又实现了」的 Algo-ID。 */
    public String fetchAlgo() throws PortalException {
        for (int i = 0; i < 40; i++) {
            Http.Response r;
            try {
                r = post(ticketUrl, algoId.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new PortalException("请求 ZSM 失败: " + e.getMessage(), e);
            }
            if (startsWith(r.body, "001")) {
                String algo = Zsm.parseAlgoId(r.body);
                if (algo != null && !Algos.BAD_ALGOS.contains(algo)
                        && Algos.ALGOS.containsKey(algo)) {
                    DoubleAesCbc c = DoubleAesCbc.of(algo);
                    if (c != null) {
                        algoId = algo;
                        cipher = c;
                        note(Log.P_STEP, "采用 Algo-ID " + algo + "（第 " + (i + 1) + " 次）");
                        return algo;
                    }
                }
                if (algo != null && Algos.BAD_ALGOS.contains(algo)) {
                    note(Log.P_INFO, "丢弃 ZSM Algo-ID=" + algo + "（黑名单）");
                } else {
                    note(Log.P_INFO, "丢弃 ZSM Algo-ID=" + algo + "（未实现）");
                }
            } else {
                note(Log.P_WARN, "ZSM 响应异常 Error-Code=" + r.header("error-code")
                        + " len=" + r.length());
            }
            refreshIdentity();
            sleep(300);
        }
        throw new PortalException("连续 40 次都没拿到可用算法");
    }

    // ------------------------------------------------------------------ 取 ticket

    public String getTicket(String gwip) throws PortalException {
        if (cipher == null) {
            throw new PortalException("还没有可用算法，请先取算法");
        }
        byte[] body = cipher.encrypt(Xml.ticket(ua, clientId, hostName, ip, mac, ostag,
                gwip == null ? "" : gwip)).getBytes(StandardCharsets.ISO_8859_1);
        for (int attempt = 0; attempt < 3; attempt++) {
            Http.Response r;
            try {
                r = post(ticketUrl, body);
            } catch (IOException e) {
                throw new PortalException("请求 ticket 失败: " + e.getMessage(), e);
            }
            String code = r.header("error-code");
            if ("200".equals(code) && startsWith(r.body, "001")) {
                String algo = Zsm.parseAlgoId(r.body);
                note(Log.P_STEP, "服务端要求换算法: " + algo);
                DoubleAesCbc c = DoubleAesCbc.of(algo);
                if (c == null) {
                    throw new PortalException("服务端要求的算法 " + algo + " 尚未实现", code, null);
                }
                algoId = algo;
                cipher = c;
                body = cipher.encrypt(Xml.ticket(ua, clientId, hostName, ip, mac, ostag,
                        gwip == null ? "" : gwip)).getBytes(StandardCharsets.ISO_8859_1);
                continue;
            }
            if (r.length() == 0) {
                throw new PortalException("取 ticket 失败", code, r.header("sub-error"));
            }
            String pt;
            try {
                pt = new String(cipher.decrypt(r.text()), StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                throw new PortalException("ticket 响应解密失败: " + e.getMessage(), code, null);
            }
            String t = Xml.tag(pt, "ticket");
            if (t.isEmpty()) {
                throw new PortalException("ticket 响应异常: " + shorten(pt, 160), code, null);
            }
            ticket = t;
            note(Log.P_INFO, "取到 ticket（" + t.length() + " 字符）");
            return ticket;
        }
        throw new PortalException("取 ticket 重试次数超限");
    }

    // ------------------------------------------------------------------ 登录

    public LoginResult login(String userid, String passwd) throws PortalException {
        if (cipher == null) {
            throw new PortalException("还没有可用算法");
        }
        Http.Response r;
        try {
            r = post(authUrl, cipher.encrypt(
                    Xml.login(ua, clientId, ticket, userid, passwd))
                    .getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new PortalException("登录请求失败: " + e.getMessage(), e);
        }
        String code = r.header("error-code");
        String sub = r.header("sub-error");
        if (r.length() == 0) {
            return new LoginResult(false, code, sub, "");
        }
        String pt;
        try {
            pt = new String(cipher.decrypt(r.text()), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new PortalException("登录响应解密失败: " + e.getMessage(), code, sub);
        }
        String ku = Xml.cdata(pt, "keep-url");
        if (!ku.isEmpty()) {
            keepUrl = ku;
            note(Log.P_STEP, "keep-url = " + ku);
        }
        String tu = Xml.cdata(pt, "term-url");
        if (!tu.isEmpty()) {
            termUrl = tu;
            note(Log.P_STEP, "term-url = " + tu);
        }
        return new LoginResult(pt.contains("<keep-retry>"), code, sub, pt);
    }

    // ------------------------------------------------------------------ 心跳 / 下线

    public KeepResult keep() throws PortalException {
        if (cipher == null) {
            throw new PortalException("还没有可用算法");
        }
        Http.Response r;
        try {
            r = post(keepUrl, cipher.encrypt(Xml.keep(ua, clientId, hostName, ip, ticket, mac, ostag))
                    .getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new PortalException("心跳请求失败: " + e.getMessage(), e);
        }
        String code = r.header("error-code");
        if (r.length() == 0) {
            return new KeepResult(-1, code, "");
        }
        String pt;
        try {
            pt = new String(cipher.decrypt(r.text()), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new PortalException("心跳响应解密失败: " + e.getMessage(), code, null);
        }
        int interval = -1;
        String iv = Xml.tag(pt, "interval");
        if (!iv.isEmpty()) {
            try {
                interval = Integer.parseInt(iv.trim());
            } catch (NumberFormatException ignored) {
                interval = -1;
            }
        }
        return new KeepResult(interval, code, pt);
    }

    public String term() throws PortalException {
        if (cipher == null) {
            return null;
        }
        try {
            Http.Response r = post(termUrl,
                    cipher.encrypt(Xml.keep(ua, clientId, hostName, ip, ticket, mac, ostag))
                            .getBytes(StandardCharsets.ISO_8859_1));
            return r.header("error-code");
        } catch (IOException e) {
            throw new PortalException("下线请求失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, String> uaHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", ua);
        h.put("Accept", ACCEPT);
        return h;
    }

    private Http.Response post(String url, byte[] body) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("CDC-Checksum", md5Hex(body));
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("User-Agent", ua);
        h.put("Accept", ACCEPT);
        h.put("Client-ID", clientId);
        h.put("Algo-ID", algoId);
        h.put("CDC-SchoolId", schoolid);
        h.put("CDC-Domain", domain);
        h.put("CDC-Area", area);
        Http.Response r = Http.post(url, body, h, localIp, timeoutMs);
        note(Log.P_INFO, "POST " + shortUrl(url) + " -> " + r.status
                + " Error-Code=" + r.header("error-code") + " len=" + r.length());
        return r;
    }

    private static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            char[] table = "0123456789abcdef".toCharArray();
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = table[v >>> 4];
                out[i * 2 + 1] = table[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 MD5 实现", e);
        }
    }

    private static boolean startsWith(byte[] data, String prefix) {
        if (data == null || data.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (data[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String regex(String text, String pattern) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String hostOf(String url) {
        String s = url;
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s.trim();
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void note(String prefix, String msg) {
        Log log = Log.current();
        if (log != null) {
            log.write(prefix, msg);
        }
    }
}
