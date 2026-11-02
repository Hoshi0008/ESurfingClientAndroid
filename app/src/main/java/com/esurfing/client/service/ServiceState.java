package com.esurfing.client.service;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 认证服务对界面暴露的运行态。
 *
 * <p>服务是独立进程组件，界面通过这里的静态字段 + 监听器拿到「有没有在跑、
 * 启动时间、当前阶段、本机地址、认证服务器」等信息；不引入任何广播/事件总线依赖。</p>
 */
public final class ServiceState {

    /** 状态变化回调（可能在任意线程触发，界面自己 post 到主线程）。 */
    public interface Listener {
        void onChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();

    /** 服务是否在运行（前台服务已启动）。 */
    public static volatile boolean running;
    /** 服务启动时间（毫秒），界面显示为 hh:mm:ss。 */
    public static volatile long startTime;
    /** 当前阶段，例如「正在启动 / 认证中 / 已在线 / 等待校园网地址 / 认证失败」。 */
    public static volatile String phase = "";
    /** 本机在校园网里的 IPv4。 */
    public static volatile String ip = "";
    /** 当前使用的认证服务器。 */
    public static volatile String server = "";
    /** 最近一次心跳间隔（秒），0 表示未知。 */
    public static volatile int keepInterval;

    private ServiceState() {
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void publish() {
        for (Listener l : LISTENERS) {
            try {
                l.onChanged();
            } catch (RuntimeException ignored) {
                // 单个界面异常不能影响服务
            }
        }
    }

    public static void setPhase(String value) {
        phase = value == null ? "" : value;
        publish();
    }

    public static void clear() {
        running = false;
        phase = "";
        ip = "";
        server = "";
        keepInterval = 0;
        publish();
    }
}
