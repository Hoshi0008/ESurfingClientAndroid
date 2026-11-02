package com.esurfing.client.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

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

    /** 把请求固定到 WLAN 网络；Android 侧注入，桌面测试时为 null。 */
    private SocketBinder binder;

    public void setSocketBinder(SocketBinder binder) {
        this.binder = binder;
    }

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
                Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, timeoutMs, binder);
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
                Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, timeoutMs, binder);
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

    /** 一次外网连通性探测的完整结果，供日志把「测了什么、结果是什么」写清楚。 */
    public static final class Probe {
        /** 是否真的连上了外网（HTTP 与 HTTPS 都通才算）。 */
        public boolean online;
        /** 命中的探测地址。 */
        public String url = "";
        /** 该地址的 HTTP 状态码，0 表示没拿到响应。 */
        public int status;
        /** 被劫持时的跳转地址。 */
        public String redirect = "";
        /** 失败原因。 */
        public String error = "";
        /** 跳转目标不像 BRAS 门户（更像学校 eIA 门户）。 */
        public boolean eiaLike;
        /** HTTPS（TLS）是否真的握手成功；门户劫持时这里是 false。 */
        public boolean httpsOk;
        /** HTTPS 的结果说明。 */
        public String httpsDetail = "";
        /** HTTP 看起来通了但 HTTPS 不通 = 仍然被门户拦着，不能算在线。 */
        public boolean intercepted;

        /** 一行摘要，写进日志用。 */
        public String summary() {
            if (online) {
                return "在线（" + url + " 返回 " + status + "，HTTPS 正常）";
            }
            StringBuilder sb = new StringBuilder("不在线");
            if (intercepted) {
                sb.append("（HTTP 通了但 HTTPS 失败：").append(httpsDetail).append("）");
            } else if (!redirect.isEmpty()) {
                sb.append("（被跳转到 ").append(redirect).append("）");
            } else if (!error.isEmpty()) {
                sb.append("（").append(error).append("）");
            }
            return sb.toString();
        }
    }

    /** HTTPS 用来验证「是不是真的放行了」的地址。 */
    private static final String[] TLS_HOSTS = {"www.baidu.com", "www.qq.com"};

    /**
     * 逐个探测外网地址，并把每次请求的结果写进日志。
     *
     * <p>判定分两步：先看 HTTP（204，或 200 且没有跳转），再用 TLS 真握手一次。
     * 只测 HTTP 会被门户骗过去——很多准入门户放行 {@code generate_204} 却劫持 443，
     * 表现就是浏览器报 {@code ERR_SSL_VERSION_OR_CIPHER_MISMATCH}。</p>
     */
    public Probe probe() {
        Probe result = new Probe();
        for (String url : DETECT_URLS) {
            try {
                Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, 6000, binder);
                String loc = r.header("location");
                note(Log.P_INFO, "外网探测 " + url + " -> " + r.status
                        + (loc == null || loc.isEmpty() ? "" : "，跳转 " + loc));
                boolean ok = r.status == 204
                        || (r.status == 200 && (loc == null || !loc.startsWith("http")));
                if (ok) {
                    result.url = url;
                    result.status = r.status;
                    // HTTP 看起来通了，再用 TLS 确认一次（这一步才是"真能上网"的证据）
                    checkTls(result);
                    result.online = result.httpsOk;
                    if (!result.online) {
                        result.intercepted = true;
                        result.error = "HTTPS 握手失败，疑似仍被门户拦截";
                        result.eiaLike = true;
                    }
                    return result;
                }
                if (loc != null && loc.startsWith("http")) {
                    result.redirect = loc;
                    result.status = r.status;
                    result.url = url;
                    if (!loc.contains("wlanuserip") && looksLikePortal(loc)) {
                        result.eiaLike = true;
                        note(Log.P_WARN, "外网请求被劫持到非 BRAS 门户：" + loc);
                    }
                }
            } catch (IOException e) {
                note(Log.P_INFO, "外网探测 " + url + " 失败: " + e.getMessage());
                result.error = e.getMessage() == null ? "网络不可达" : e.getMessage();
            }
        }
        return result;
    }

    /** 对一个知名站点做 TLS 握手；失败通常意味着 443 被门户劫持。 */
    private void checkTls(Probe result) {
        StringBuilder detail = new StringBuilder();
        for (String host : TLS_HOSTS) {
            Socket socket = new Socket();
            try {
                if (binder != null) {
                    binder.bind(socket);
                } else if (localIp != null && !localIp.trim().isEmpty()) {
                    socket.bind(new InetSocketAddress(localIp.trim(), 0));
                }
                socket.connect(new InetSocketAddress(host, 443), 6000);
                socket.setSoTimeout(6000);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, 443, true);
                ssl.startHandshake();
                String protocol = ssl.getSession() == null ? "" : ssl.getSession().getProtocol();
                ssl.close();
                result.httpsOk = true;
                result.httpsDetail = host + " TLS " + protocol + " 握手成功";
                note(Log.P_INFO, "外网探测 HTTPS " + result.httpsDetail);
                return;
            } catch (IOException | RuntimeException e) {
                detail.append(detail.length() == 0 ? "" : "；")
                        .append(host).append(" 失败: ").append(e.getMessage());
                note(Log.P_WARN, "外网探测 HTTPS " + host + " 失败: " + e);
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        result.httpsOk = false;
        result.httpsDetail = detail.length() == 0 ? "TLS 握手失败" : detail.toString();
    }

    /** 是否已在线（能拿到 204 / 正常网页说明放行）。 */
    public boolean probeOnline() {
        return probe().online;
    }

    /**
     * 探测 HTTP 请求被劫持到哪个门户。
     *
     * <p>返回的是<b>不是</b> BRAS 门户（不带 {@code wlanuserip}）的跳转地址——
     * 那种门户基本可以判定为学校自己的 eIA 准入门户，而不是天翼的认证门户。</p>
     *
     * @return 跳转地址；没有跳转或者跳到 BRAS 门户时返回 ""
     */
    public String detectPortalRedirect() {
        return detectPortalRedirect(DETECT_URLS);
    }

    /** 同上，但用调用方给的探测地址（便于用 mock 门户做单元验证）。 */
    public String detectPortalRedirect(String[] urls) {
        if (urls == null) {
            return "";
        }
        for (String url : urls) {
            try {
                Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, 6000, binder);
                String loc = r.header("location");
                if (loc != null && loc.startsWith("http") && !loc.contains("wlanuserip")
                        && looksLikePortal(loc)) {
                    note(Log.P_STEP, "探测到门户劫持：" + url + " -> " + loc);
                    return loc;
                }
            } catch (IOException ignored) {
                // 换下一个
            }
        }
        return "";
    }

    /**
     * 判断一个跳转地址像不像「认证门户」。
     *
     * <p>只认局域网地址（校园门户基本都是内网 IP）或者不像普通网站域名的地址；
     * 像百度的 {@code http → https://m.baidu.com/} 这种正常跳转不能被当成门户，
     * 否则会拿它去开浏览器，把用户带到无关页面。</p>
     */
    public static boolean looksLikePortal(String url) {
        if (url == null) {
            return false;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        if (IpUtil.isIpv4(host)) {
            return IpUtil.cidrContains("10.0.0.0/8", host)
                    || IpUtil.cidrContains("172.16.0.0/12", host)
                    || IpUtil.cidrContains("192.168.0.0/16", host)
                    || IpUtil.cidrContains("169.254.0.0/16", host)
                    || IpUtil.cidrContains("100.64.0.0/10", host);
        }
        String lower = host.toLowerCase(Locale.US);
        return !lower.contains("baidu.com") && !lower.contains("qq.com")
                && !lower.contains("miui.com") && !lower.contains("google.com");
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
            Http.Response r = Http.request("GET", url, null, uaHeaders(), localIp, 8000, binder);
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
        Http.Response r = Http.post(url, body, h, localIp, timeoutMs, binder);
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
