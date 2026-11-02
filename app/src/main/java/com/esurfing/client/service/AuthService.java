package com.esurfing.client.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.esurfing.client.App;
import com.esurfing.client.R;
import com.esurfing.client.core.Config;
import com.esurfing.client.core.IpUtil;
import com.esurfing.client.core.Log;
import com.esurfing.client.core.Portal;
import com.esurfing.client.core.PortalException;
import com.esurfing.client.ui.MainActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 认证服务（前台服务）：长期驻留、自动重连、按服务端间隔心跳。
 *
 * <p>保活手段：前台服务 + 常驻通知、START_STICKY、开机自启、15 分钟看门狗、
 * 电池优化白名单（由界面引导用户授权）。</p>
 *
 * <p>配置只在「启动 / 重新启动服务」时读取一次，因此设置页改完要提示重启服务。</p>
 */
public class AuthService extends Service {

    public static final String ACTION_START = "com.esurfing.client.action.START";
    public static final String ACTION_STOP = "com.esurfing.client.action.STOP";
    public static final String ACTION_RESTART = "com.esurfing.client.action.RESTART";

    private static final int NOTIF_ID = 1001;

    private static volatile boolean sRunning;

    private final Object sleeper = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopRequested;
    private volatile boolean restarting;
    private volatile Thread worker;
    private volatile Portal portal;
    private volatile Config config;
    private volatile long startMillis;
    private String lastNotificationPhase = "";

    public static boolean isRunning() {
        return sRunning;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        startMillis = System.currentTimeMillis();
        ServiceState.running = true;
        ServiceState.startTime = startMillis;
        ServiceState.phase = "正在启动";
        openLog();
        pushForeground();
        startWorker();
        KeepAliveJobService.schedule(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            restart();
            return START_STICKY;
        }
        // ACTION_START 或系统粘性重启
        if (!sRunning) {
            sRunning = true;
            ServiceState.running = true;
        }
        if (worker == null || !worker.isAlive()) {
            startWorker();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        logInfo("服务被销毁，准备退出");
        stopRequested = true;
        wake();
        sRunning = false;
        ServiceState.clear();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉：不要让服务跟着走
        logInfo("任务被移除，服务继续运行");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 启动 / 重启 / 停止

    private void pushForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (RuntimeException e) {
            // 通知权限被拒 / 系统限制时不让服务崩掉，仍然跑认证
            logWarn("进入前台服务失败: " + e);
        }
        acquireWifiLock();
    }

    private WifiManager.WifiLock wifiLock;

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "esurfing:auth");
                wifiLock.setReferenceCounted(false);
            }
            wifiLock.acquire();
        } catch (RuntimeException ignored) {
            // 个别机型不允许，忽略
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private void startWorker() {
        config = Config.load(App.get().configFile());
        stopRequested = false;
        Thread t = new Thread(this::loop, "esurfing-auth");
        worker = t;
        t.start();
    }

    /** 重新启动：新启动时间、新日志文件、重新读配置。 */
    private void restart() {
        if (restarting) {
            return;
        }
        restarting = true;
        logInfo("收到重新启动请求");
        stopRequested = true;
        wake();
        new Thread(() -> {
            joinWorker(4000);
            sendTerm();
            main.post(() -> {
                restarting = false;
                startMillis = System.currentTimeMillis();
                ServiceState.startTime = startMillis;
                ServiceState.phase = "正在启动";
                ServiceState.keepInterval = 0;
                ServiceState.ip = "";
                ServiceState.server = "";
                openLog();
                updateNotification(true);
                startWorker();
                ServiceState.publish();
            });
        }, "esurfing-restart").start();
    }

    private void shutdown() {
        logInfo("收到停止请求");
        stopRequested = true;
        wake();
        releaseWifiLock();
        final Thread w = worker;
        new Thread(() -> {
            if (w != null) {
                try {
                    w.join(2500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            sendTerm();
            closeLog();
            sRunning = false;
            ServiceState.clear();
            main.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }, "esurfing-stop").start();
    }

    private void joinWorker(long millis) {
        Thread w = worker;
        if (w != null && w != Thread.currentThread()) {
            try {
                w.join(millis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wake() {
        synchronized (sleeper) {
            sleeper.notifyAll();
        }
    }

    /** 可被打断的等待；返回 false 表示服务正在退出。 */
    private boolean sleepSeconds(int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        synchronized (sleeper) {
            while (!stopRequested) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    sleeper.wait(Math.min(left, 400L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return !stopRequested;
    }

    // ------------------------------------------------------------------ 认证主循环

    private void loop() {
        int delay = 3;
        try {
            while (!stopRequested) {
                try {
                    session();
                    delay = 3;
                } catch (PortalException e) {
                    logWarn("认证失败: " + e.describe());
                    setPhase("认证失败，稍后重试");
                    delay = Math.min(Math.max(delay, 5) * 2, 60);
                } catch (RuntimeException e) {
                    logWarn("运行异常: " + e);
                    setPhase("运行异常，稍后重试");
                    delay = 30;
                }
                if (stopRequested) {
                    break;
                }
                if (!sleepSeconds(delay)) {
                    break;
                }
            }
        } finally {
            sendTerm();
            Log log = Log.current();
            if (log != null) {
                log.info("认证服务已停止");
                log.close();
            }
        }
    }

    /** 跑一轮：探测 → 算法 → ticket → 登录 → 心跳；会话失效后返回，由外层重新开始。 */
    private void session() throws PortalException {
        Config cfg = config;
        if (cfg == null) {
            cfg = Config.load(App.get().configFile());
            config = cfg;
        }
        if (!cfg.ready()) {
            logWarn("账号或密码为空，请在「设置」里填写 user 与 passwd");
            setPhase("未配置账号");
            sleepSeconds(30);
            return;
        }
        NetInfo net = NetInfo.read(this);
        ServiceState.ip = net.ipv4 == null ? "" : net.ipv4;
        if (!net.wifi || net.wifiNetwork == null) {
            ServiceState.ip = "";
            ServiceState.publish();
            setPhase("等待连接 WLAN");
            logStep("当前没有连接 WLAN：认证与外网连通测试都只走 WLAN（不走移动数据），15 秒后重试");
            sleepSeconds(15);
            return;
        }
        if (net.ipv4 == null) {
            setPhase("等待校园网地址");
            logStep("WLAN 还没有拿到 IPv4 地址，10 秒后重试");
            sleepSeconds(10);
            return;
        }

        boolean force = cfg.boolOf("force", false);
        String campus = cfg.get("campus_net").trim();
        if (!campus.isEmpty() && !IpUtil.anyCidrContains(campus, net.ipv4) && !force) {
            logWarn(net.ipv4 + " 不在天翼网段 " + campus + "，跳过认证");
            boolean inEia = inEiaNet(cfg, net);
            if (inEia) {
                logWarn("这是学校 eIA 准入网：本机 MAC 还没登记");
                offerPortal(cfg, net, "", true, "需要用浏览器完成首次认证");
            }
            setPhase(inEia ? "需要门户认证" : "不在天翼网段");
            sleepSeconds(30);
            return;
        }

        String mac = cfg.get("mac").trim();
        if (!IpUtil.isMac(mac)) {
            // 默认上报本机 MAC；系统不给读（Android 6+ 常见）时退化成固定随机 MAC
            String localMac = WifiNames.localMac(this);
            if (IpUtil.isMac(localMac)) {
                mac = localMac;
                logStep("上报本机 MAC " + mac);
            } else {
                mac = com.esurfing.client.AppPrefs.stableMac(this);
                logStep("系统不提供本机 MAC，改用固定的随机 MAC " + mac);
            }
        }
        Portal p = new Portal(net.ipv4, mac, cfg.get("channel", "android"),
                cfg.get("schoolid"), cfg.get("domain"), cfg.get("area"),
                cfg.intOf("port", 7001));
        p.localIp(net.ipv4);
        // 所有 socket 固定绑到 WLAN，避免外网探测/认证跑到移动数据上
        p.setSocketBinder(net.wifiNetwork::bindSocket);
        String ssid = WifiNames.currentSsid(this);
        logStep("本机地址 " + net.ipv4 + "  网关 " + (net.gateway == null ? "-" : net.gateway)
                + "  通道 " + cfg.get("channel", "android")
                + (ssid == null ? "" : "  WiFi " + ssid));

        int netCheck = cfg.intOf("net_check", 300);
        if (!force) {
            setPhase("检测网络");
            logInfo("认证前先测一次外网连通性");
            Portal.Probe probe = p.probe();
            ServiceState.online = probe.online;
            if (probe.online) {
                logOk("外网连通测试通过：" + probe.summary() + "，无需认证");
                setPhase("网络已在线");
                sleepSeconds(Math.max(30, netCheck > 0 ? Math.min(netCheck, 120) : 60));
                return;
            }
            logWarn("外网连通测试未通过：" + probe.summary() + "，开始认证");
        }

        setPhase("探测门户");
        List<String> servers = splitServers(cfg.get("servers"));
        String acip = p.discover(servers);
        if (acip.isEmpty()) {
            String fallback = cfg.get("acip").trim();
            if (IpUtil.isIpv4(fallback)) {
                acip = fallback;
                logStep("使用配置里的 acip=" + acip);
            }
        }
        if (acip.isEmpty()) {
            logWarn("探测不到 wlanacip：请在设置里填写 acip，或确认已连上校园网 WiFi");
            if (!p.lastRedirect().isEmpty()) {
                logWarn("跳转到了 " + p.lastRedirect());
            }
            // 探测不到 BRAS 门户，多半就是被学校的准入门户挡住了：直接把门户交给浏览器
            boolean inEia = inEiaNet(cfg, net);
            offerPortal(cfg, net, p.lastRedirect(), inEia, "认证门户探测失败，需要先在门户里完成认证");
            setPhase("探测门户失败");
            sleepSeconds(30);
            return;
        }

        String server = p.server();
        if (server.isEmpty()) {
            server = servers.isEmpty() ? "" : servers.get(0);
        }
        if (server.isEmpty()) {
            logWarn("没有可用的认证服务器：请在设置里填写 servers");
            setPhase("缺少认证服务器");
            sleepSeconds(30);
            return;
        }
        p.configure(server, acip);
        ServiceState.server = p.server();
        ServiceState.publish();

        setPhase("获取算法");
        String algo = p.fetchAlgo();
        logOk("采用算法 " + algo);

        setPhase("获取 ticket");
        p.getTicket(net.gateway == null ? "" : net.gateway);

        setPhase("登录中");
        Portal.LoginResult lr = p.login(cfg.get("user").trim(), cfg.get("passwd"));
        if (!lr.accepted) {
            setPhase("登录被拒");
            throw new PortalException("登录被服务器拒绝", lr.code, lr.sub);
        }
        logOk("登录请求已受理，keep-url=" + p.keepUrl());
        portal = p;
        setPhase("已在线");

        // 服务跑起来之后真连一次外网：能通就是 campus，被劫到别的门户多半是 eIA
        checkReachability(p, net);

        keepLoop(p, netCheck);
    }

    /**
     * 认证成功后做一次互联网连通性判断，用来区分「真的放行了」和「还卡在门户后面」。
     *
     * <p>只看 HTTP 会被门户骗：不少准入门户放行 {@code generate_204} 但劫持 443，
     * 浏览器就报 {@code ERR_SSL_VERSION_OR_CIPHER_MISMATCH}。所以
     * {@link Portal#probe()} 会再真握手一次 TLS，只有握手成功才算在线。
     * 判定为需要门户认证（eIA 网段 / 被劫持 / TLS 失败）时，把门户地址发布到
     * {@link ServiceState#portalUrl}，由前台界面用默认浏览器打开。</p>
     */
    private void checkReachability(Portal p, NetInfo net) {
        Config cfg = config;
        String eiaNet = cfg.get("eia_net").trim();
        boolean inEia = !eiaNet.isEmpty() && net.ipv4 != null
                && IpUtil.anyCidrContains(eiaNet, net.ipv4);

        logInfo("开始外网连通测试（认证已受理）");
        Portal.Probe probe = p.probe();
        if (!probe.online && probe.intercepted) {
            // 刚认证完网关可能还没放行，给一次机会再验
            logStep("HTTP 通了但 HTTPS 没过，等 2 秒再验一次（刚认证完可能还没放行）");
            sleepSeconds(2);
            probe = p.probe();
        }
        ServiceState.online = probe.online;
        if (probe.online) {
            if (inEia) {
                logWarn("外网连通测试：" + probe.summary()
                        + "，但地址 " + net.ipv4 + " 仍落在 eIA 网段 " + eiaNet + "，请留意");
            } else {
                logOk("外网连通测试通过：" + probe.summary() + "，当前是校园网（campus）");
                setPhase("已在线");
            }
            ServiceState.clearPortal();
            return;
        }

        logWarn("外网连通测试未通过：" + probe.summary());

        // 只有"像门户"的跳转才当作门户地址（百度的 http→m.baidu.com 这种不算）
        String redirect = Portal.looksLikePortal(probe.redirect) ? probe.redirect : "";
        if (redirect.isEmpty()) {
            redirect = p.detectPortalRedirect();
        }
        boolean needPortal = inEia || probe.intercepted || probe.eiaLike;
        if (!needPortal) {
            logStep("既连不上外网，也没有被劫持到门户；先继续心跳，稍后再测");
            return;
        }

        if (probe.intercepted) {
            logWarn("HTTP 能通但 HTTPS 握手失败（浏览器会报 ERR_SSL_VERSION_OR_CIPHER_MISMATCH），"
                    + "说明仍被门户拦着，不能算已联网");
        }
        if (inEia) {
            logWarn("本机地址 " + net.ipv4 + " 落在 eIA 准入网段 " + eiaNet);
        }
        offerPortal(cfg, net, redirect, inEia, "需要用浏览器完成首次认证");
    }

    /** 当前地址是否落在配置的 eIA 准入网段。 */
    private boolean inEiaNet(Config cfg, NetInfo net) {
        String eiaNet = cfg.get("eia_net").trim();
        return !eiaNet.isEmpty() && net.ipv4 != null && IpUtil.anyCidrContains(eiaNet, net.ipv4);
    }

    /**
     * 「需要用户去浏览器里完成认证」时统一走这里：挑一个最靠谱的门户地址发布给界面，
     * 由界面用默认浏览器打开。
     *
     * <p>优先级：配置里的 {@code eia_portal} → 探测到的门户跳转 → 网关首页（只在确认
     * 是 eIA 时猜这一手）。同一个地址只发布一次，避免每 30 秒重试都把浏览器弹一遍。</p>
     */
    private void offerPortal(Config cfg, NetInfo net, String candidate, boolean inEia, String reason) {
        String url = cfg.get("eia_portal").trim();
        if (url.isEmpty() && candidate != null && !candidate.trim().isEmpty()
                && Portal.looksLikePortal(candidate)) {
            url = candidate.trim();
        }
        if (url.isEmpty() && inEia && net.gateway != null && !net.gateway.isEmpty()) {
            // eIA 的门户大多挂在网关上，猜一个总比什么都不给强
            url = "http://" + net.gateway + "/";
        }
        if (url.isEmpty()) {
            logWarn(reason + "，但拿不到门户地址：请在设置里填写 eia_portal");
            setPhase("需要门户认证");
            return;
        }
        logWarn(reason);
        logWarn("尝试用默认浏览器打开：" + url);
        setPhase("需要门户认证");
        ServiceState.setPortal(url);
    }

    private void keepLoop(Portal p, int netCheck) {
        Config cfg = config;
        int minInterval = Math.max(10, cfg.intOf("interval_min", 20));
        int interval = Math.max(minInterval, 60);
        long lastNetCheck = System.currentTimeMillis();
        int fails = 0;
        while (!stopRequested) {
            if (!sleepSeconds(interval)) {
                return;
            }
            try {
                Portal.KeepResult kr = p.keep();
                if (kr.interval > 0) {
                    interval = Math.max(minInterval, kr.interval);
                }
                ServiceState.keepInterval = interval;
                if (kr.code == null || "0".equals(kr.code)) {
                    fails = 0;
                    logInfo("心跳正常，下一次 " + interval + " 秒后");
                    setPhase("已在线");
                } else {
                    fails++;
                    logWarn("心跳 Error-Code=" + kr.code);
                }
            } catch (PortalException e) {
                fails++;
                logWarn("心跳异常: " + e.describe());
            }
            if (fails >= 3) {
                logWarn("会话已失效，重新认证");
                setPhase("会话失效，重新认证");
                return;
            }
            if (netCheck > 0 && System.currentTimeMillis() - lastNetCheck >= netCheck * 1000L) {
                lastNetCheck = System.currentTimeMillis();
                Portal.Probe probe = p.probe();
                ServiceState.online = probe.online;
                if (!probe.online) {
                    logWarn("外网连通测试未通过：" + probe.summary() + "，重新认证");
                    setPhase("外网校验失败，重新认证");
                    return;
                }
                logOk("外网连通测试通过：" + probe.summary());
            }
        }
    }

    private void sendTerm() {
        Portal p = portal;
        portal = null;
        if (p == null) {
            return;
        }
        try {
            String code = p.term();
            logInfo("已发送下线请求 Error-Code=" + code);
        } catch (PortalException e) {
            logWarn("下线失败: " + e.describe());
        } catch (RuntimeException e) {
            logWarn("下线异常: " + e);
        }
    }

    private static List<String> splitServers(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        for (String s : raw.split(",")) {
            String v = s.trim();
            if (!v.isEmpty() && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 日志 / 通知

    private void openLog() {
        File dir = App.get().logDir();
        try {
            Log log = Log.open(dir, startMillis);
            log.info("启动认证服务（Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT + "）");
            log.info("配置文件 " + App.get().configFile().getName() + "，日志文件 " + log.file().getName());
            Config cfg = App.get().config();
            log.step("账号 " + mask(cfg.get("user")) + "  通道 " + cfg.get("channel")
                    + "  服务器 " + (cfg.get("servers").isEmpty() ? "(自动探测)" : cfg.get("servers")));
        } catch (IOException e) {
            android.util.Log.e("ESurfing", "无法创建日志文件", e);
        }
    }

    private void closeLog() {
        Log log = Log.current();
        if (log != null) {
            log.close();
        }
    }

    private static String mask(String user) {
        if (user == null || user.isEmpty()) {
            return "(空)";
        }
        if (user.length() <= 4) {
            return user.charAt(0) + "***";
        }
        return user.substring(0, 2) + "****" + user.substring(user.length() - 2);
    }

    private void logInfo(String s) {
        Log log = Log.current();
        if (log != null) {
            log.info(s);
        }
    }

    private void logOk(String s) {
        Log log = Log.current();
        if (log != null) {
            log.ok(s);
        }
    }

    private void logWarn(String s) {
        Log log = Log.current();
        if (log != null) {
            log.warn(s);
        }
    }

    private void logStep(String s) {
        Log log = Log.current();
        if (log != null) {
            log.step(s);
        }
    }

    private void setPhase(String phase) {
        ServiceState.phase = phase;
        ServiceState.publish();
        updateNotification(false);
    }

    private void updateNotification(boolean force) {
        final String phase = ServiceState.phase;
        if (!force && phase.equals(lastNotificationPhase)) {
            return;
        }
        lastNotificationPhase = phase;
        main.post(() -> {
            try {
                NotificationManager nm = (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(NOTIF_ID, buildNotification());
                }
            } catch (RuntimeException ignored) {
                // 通知发不出去不影响认证
            }
        });
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, AuthService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent restartPi = PendingIntent.getService(this, 2,
                new Intent(this, AuthService.class).setAction(ACTION_RESTART),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String phase = ServiceState.phase == null || ServiceState.phase.isEmpty()
                ? "运行中" : ServiceState.phase;
        Notification.Builder b = new Notification.Builder(this, App.notificationChannelId())
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(phase + " · 启动于 " + hms(startMillis))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openPi);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_notify);
        b.addAction(new Notification.Action.Builder(icon, "重新启动", restartPi).build());
        b.addAction(new Notification.Action.Builder(icon, "停止", stopPi).build());
        return b.build();
    }

    private static String hms(long epochMillis) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(epochMillis);
        return String.format(Locale.US, "%02d:%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
    }

    /** 外部（界面 / 开机广播 / 看门狗）通用的启动入口。 */
    public static void start(android.content.Context ctx, String action) {
        Intent i = new Intent(ctx, AuthService.class).setAction(action);
        try {
            ctx.startForegroundService(i);
        } catch (RuntimeException e) {
            // Android 12+ 后台启动前台服务受限时会抛异常，能做的只有记录
            Log log = Log.current();
            if (log != null) {
                log.warn("启动前台服务失败: " + e);
            }
        }
    }

    /** 申请电池优化白名单（不阻塞，失败也无所谓）。 */
    public static boolean isIgnoringBatteryOptimizations(android.content.Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }
}
