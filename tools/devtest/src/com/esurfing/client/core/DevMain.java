package com.esurfing.client.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 开发期自检入口（非 Android 代码）。
 *
 * <pre>
 *   crypto &lt;vectors.txt&gt;                        加密向量比对
 *   interop &lt;host&gt; &lt;port&gt; &lt;acip&gt; &lt;user&gt; &lt;pass&gt;   对 mock BRAS 跑完整认证流程
 *   config  &lt;dir&gt;                               配置文件读写 / 日志清理自检
 * </pre>
 */
public final class DevMain {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: crypto <vec> | interop <host> <port> <acip> <user> <pass> | config <dir>");
            System.exit(2);
        }
        String mode = args[0];
        if ("crypto".equals(mode)) {
            int failed = CryptoCheck.run(args[1]);
            System.exit(failed == 0 ? 0 : 1);
        } else if ("interop".equals(mode)) {
            interop(args);
        } else if ("config".equals(mode)) {
            config(new File(args[1]));
        } else {
            System.out.println("unknown mode " + mode);
            System.exit(2);
        }
    }

    private static void interop(String[] args) throws Exception {
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        String acip = args[3];
        String user = args[4];
        String pass = args[5];

        File logDir = new File("tools/devtest/out/logs");
        Log log = Log.open(logDir, System.currentTimeMillis());
        System.out.println("日志文件: " + log.file().getAbsolutePath());

        List<String> servers = new ArrayList<String>(Arrays.asList(host));
        Portal p = new Portal("127.0.0.1", "02:11:22:33:44:55", "android", "", "", "", port);
        String found = p.discover(servers);
        System.out.println("discover -> wlanacip=" + found + " server=" + p.server());
        if (!acip.isEmpty()) {
            found = acip;
        }
        if (found.isEmpty()) {
            System.out.println("[FAIL] 没探测到 wlanacip");
            System.exit(1);
        }
        p.configure(p.server().isEmpty() ? host : p.server(), found);

        String algo = p.fetchAlgo();
        System.out.println("algo -> " + algo);
        String ticket = p.getTicket("0.0.0.0");
        System.out.println("ticket -> " + ticket);

        Portal.LoginResult lr = p.login(user, pass);
        System.out.println("login -> accepted=" + lr.accepted + " code=" + lr.code
                + " keep=" + p.keepUrl() + " term=" + p.termUrl());
        if (!lr.accepted) {
            System.out.println("[FAIL] 登录未被受理: " + lr.xml);
            System.exit(1);
        }

        Portal.KeepResult kr = p.keep();
        System.out.println("keep -> code=" + kr.code + " interval=" + kr.interval);
        System.out.println("term -> " + p.term());
        System.out.println();
        System.out.println("--- 日志 ---");
        System.out.print(log.snapshot());
        log.close();
        if (!"0".equals(kr.code)) {
            System.exit(1);
        }
        System.out.println("互操作测试通过");
    }

    private static void config(File dir) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 " + dir);
        }
        File f = new File(dir, "esurfing.conf");
        Config c = Config.load(f);
        System.out.println("默认 ready=" + c.ready() + " channel=" + c.get("channel"));
        c.set("user", "20230001");
        c.set("passwd", "p@ss=word");
        c.set("servers", "1.2.3.4,5.6.7.8");
        if (!c.save()) {
            throw new IllegalStateException("保存失败");
        }
        Config again = Config.load(f);
        System.out.println("回读 user=" + again.get("user") + " passwd=" + again.get("passwd")
                + " servers=" + again.get("servers") + " ready=" + again.ready());
        if (!"20230001".equals(again.get("user")) || !"p@ss=word".equals(again.get("passwd"))) {
            System.out.println("[FAIL] 配置回读不一致");
            System.exit(1);
        }
        if (!again.ready()) {
            System.out.println("[FAIL] ready() 判定错误");
            System.exit(1);
        }

        // 日志清理：造 3 个文件（2 天前 / 20 天前 / 20 年前）与正常文件
        File logDir = new File(dir, "logs");
        //noinspection ResultOfMethodCallIgnored
        logDir.mkdirs();
        long now = System.currentTimeMillis();
        File recent = new File(logDir, Log.fileNameFor(now - 2L * 86400000));
        File old = new File(logDir, Log.fileNameFor(now - 20L * 86400000));
        File ancient = new File(logDir, Log.fileNameFor(now - 7300L * 86400000));
        Log.writeTo(recent, "recent".getBytes("UTF-8"));
        Log.writeTo(old, "old".getBytes("UTF-8"));
        Log.writeTo(ancient, "ancient".getBytes("UTF-8"));
        //noinspection ResultOfMethodCallIgnored
        old.setLastModified(now - 20L * 86400000);
        //noinspection ResultOfMethodCallIgnored
        ancient.setLastModified(now - 7300L * 86400000);
        Log.purge(logDir, now);
        System.out.println("purge 后保留: " + Arrays.toString(logDir.list()));
        if (!recent.isFile() || old.isFile() || !ancient.isFile()) {
            System.out.println("[FAIL] 日志清理策略不符合预期（应删 20 天前、保留 10 年以上的）");
            System.exit(1);
        }
        System.out.println("配置 / 日志自检通过");
    }
}
