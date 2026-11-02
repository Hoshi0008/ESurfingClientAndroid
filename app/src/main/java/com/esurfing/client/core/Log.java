package com.esurfing.client.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 日志系统（后端）。
 *
 * <ul>
 *     <li>文件名固定为 {@code ESurfing 年-月-日-时-分-秒.log}，时间取<b>启动服务时</b>的时间；</li>
 *     <li>每行实时落盘（每行 flush），因此「打开日志」看到的就是磁盘内容；</li>
 *     <li>内存里维护一份环形缓冲，UI 的日志文本框直接从这里取内容并实时追加；</li>
 *     <li>只保留最近 7 天的日志；若日志文件的时间与当前时间相差超过 10 年则不清理。</li>
 * </ul>
 *
 * <p>纯 Java，不依赖 Android API。</p>
 */
public final class Log {

    /** UI 侧监听器：每条日志落地后回调（回调发生在写日志的线程）。 */
    public interface Sink {
        void onLine(String line);
    }

    /** 单行前缀，与参考后端保持一致的观感。 */
    public static final String P_OK = "[+]";
    public static final String P_INFO = "[*]";
    public static final String P_WARN = "[!]";
    public static final String P_STEP = "[i]";

    private static final String FILE_PREFIX = "ESurfing ";
    private static final String FILE_SUFFIX = ".log";
    private static final long SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000;
    private static final long TEN_YEARS = 10L * 365 * 24 * 60 * 60 * 1000;
    private static final int RING_LIMIT = 400 * 1024;

    private static final Object LOCK = new Object();
    private static final List<Sink> SINKS = new CopyOnWriteArrayList<Sink>();

    private static volatile Log sCurrent;

    private final File file;
    private final FileOutputStream out;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final StringBuilder ring = new StringBuilder(4096);
    private boolean closed;

    private Log(File file, FileOutputStream out) {
        this.file = file;
        this.out = out;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 打开（或新建）本次运行的日志。文件名使用 {@code startMillis} 指定的启动时间。
     */
    public static Log open(File dir, long startMillis) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建日志目录: " + dir.getAbsolutePath());
        }
        purge(dir, System.currentTimeMillis());
        File f = fileFor(dir, startMillis);
        FileOutputStream fos = new FileOutputStream(f, true);
        Log log = new Log(f, fos);
        synchronized (LOCK) {
            Log old = sCurrent;
            sCurrent = log;
            if (old != null) {
                old.close();
            }
        }
        return log;
    }

    /** 当前日志；服务未启动时为 null。 */
    public static Log current() {
        return sCurrent;
    }

    public File file() {
        return file;
    }

    /** 当前日志文件名（用于 UI 显示）。 */
    public static String fileNameFor(long startMillis) {
        return FILE_PREFIX + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
                .format(new Date(startMillis)) + FILE_SUFFIX;
    }

    static File fileFor(File dir, long startMillis) {
        return new File(dir, fileNameFor(startMillis));
    }

    public void close() {
        synchronized (LOCK) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.flush();
                out.close();
            } catch (IOException ignored) {
                // 关闭失败没有补救手段
            }
            if (sCurrent == this) {
                sCurrent = null;
            }
        }
    }

    // ------------------------------------------------------------------ 写出

    public void ok(String msg) {
        write(P_OK, msg);
    }

    public void info(String msg) {
        write(P_INFO, msg);
    }

    public void warn(String msg) {
        write(P_WARN, msg);
    }

    public void step(String msg) {
        write(P_STEP, msg);
    }

    public void write(String prefix, String msg) {
        String line = "[" + timeFormat.format(new Date()) + "] " + prefix + " " + msg;
        synchronized (LOCK) {
            append(line + "\n");
            if (!closed) {
                try {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    out.flush();
                } catch (IOException ignored) {
                    // 磁盘写失败也不能让认证线程崩掉
                }
            }
        }
        for (Sink s : SINKS) {
            try {
                s.onLine(line);
            } catch (RuntimeException ignored) {
                // 单个监听器出错不影响其它监听器
            }
        }
    }

    private void append(String text) {
        ring.append(text);
        if (ring.length() > RING_LIMIT) {
            int cut = ring.length() - RING_LIMIT;
            int nl = ring.indexOf("\n", cut);
            cut = nl < 0 ? ring.length() : nl + 1;
            ring.delete(0, cut);
        }
    }

    /** 本次运行的完整日志文本（受环形缓冲上限约束）。 */
    public String snapshot() {
        synchronized (LOCK) {
            return ring.toString();
        }
    }

    // ------------------------------------------------------------------ UI 监听

    public static void addSink(Sink sink) {
        if (sink != null && !SINKS.contains(sink)) {
            SINKS.add(sink);
        }
    }

    public static void removeSink(Sink sink) {
        SINKS.remove(sink);
    }

    // ------------------------------------------------------------------ 文件工具

    /** 列出目录里的日志文件，按修改时间从新到旧。 */
    public static List<File> list(File dir) {
        List<File> out = new ArrayList<File>();
        File[] all = dir == null ? null : dir.listFiles();
        if (all == null) {
            return out;
        }
        for (File f : all) {
            if (f.isFile() && f.getName().startsWith(FILE_PREFIX)
                    && f.getName().endsWith(FILE_SUFFIX)) {
                out.add(f);
            }
        }
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr, (a, b) -> {
            long d = b.lastModified() - a.lastModified();
            return d < 0 ? -1 : (d > 0 ? 1 : a.getName().compareTo(b.getName()));
        });
        return new ArrayList<File>(Arrays.asList(arr));
    }

    /**
     * 清理策略：保存最近 7 天；若文件时间与当前时间相差超过 10 年则跳过（不清理）。
     */
    public static void purge(File dir, long nowMillis) {
        List<File> files = list(dir);
        for (File f : files) {
            long modified = f.lastModified();
            if (modified <= 0) {
                continue;
            }
            long delta = Math.abs(nowMillis - modified);
            if (delta > TEN_YEARS) {
                continue;
            }
            if (nowMillis - modified > SEVEN_DAYS) {
                // 关闭当前文件后再删（当前文件一般不属于这种情况）
                if (sCurrent != null && f.equals(sCurrent.file)) {
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 读取整个日志文件（UTF-8，非法字节按替换处理）。 */
    public static String read(File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length(), 1 << 20));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    /** 把文本写进指定文件（用于「导出日志」）。 */
    public static boolean writeTo(File f, byte[] data) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(data);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }
}
