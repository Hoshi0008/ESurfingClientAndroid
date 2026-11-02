package com.esurfing.client;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.esurfing.client.core.Config;
import com.esurfing.client.service.KeepAliveJobService;

import java.io.File;

/**
 * 应用入口：固定配置文件 / 日志目录位置，创建通知渠道，注册保活看门狗。
 *
 * <p>目录布局（都在 App 私有目录里，无需任何存储权限）：</p>
 * <pre>
 *   files/esurfing.conf     配置文件（后端启动时读取）
 *   files/logs/             日志目录，ESurfing 年-月-日-时-分-秒.log
 * </pre>
 */
public class App extends Application {

    public static final String NOTIF_CHANNEL_ID = "auth";

    private static App sInstance;

    private File configFile;
    private File logDir;
    private Config config;

    @Override
    public void onCreate() {
        super.onCreate();
        long t0 = android.os.SystemClock.uptimeMillis()
                - android.os.Process.getStartUptimeMillis();
        sInstance = this;
        logDir = new File(getFilesDir(), "logs");
        configFile = new File(getFilesDir(), "esurfing.conf");
        if (!configFile.isFile()) {
            // 首次运行：落一份带注释的配置模板，用户可以在「设置」页逐项修改
            Config.load(configFile).save();
        }
        // 老版本把 theme / auto_start 写在配置文件里，升级时迁到应用级设置
        AppPrefs.migrateIfNeeded(this);
        createChannel();
        // JobScheduler 是 binder 调用，放到后台线程，别拖慢冷启动
        new Thread(() -> KeepAliveJobService.schedule(App.this), "keepalive-schedule").start();
        android.util.Log.i("ESurfing", "App.onCreate 开始 +" + t0 + "ms, 完成 +"
                + (android.os.SystemClock.uptimeMillis()
                - android.os.Process.getStartUptimeMillis()) + "ms");
    }

    public static App get() {
        return sInstance;
    }

    public File configFile() {
        return configFile;
    }

    public File logDir() {
        return logDir;
    }

    /** 配置文件（带缓存；保存时请用同一个实例，保证即时可见）。 */
    public Config config() {
        if (config == null) {
            config = Config.load(configFile);
        }
        return config;
    }

    public void reloadConfig() {
        config = null;
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                NOTIF_CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(ch);
    }

    public static String notificationChannelId() {
        return NOTIF_CHANNEL_ID;
    }

    /** 用于判断系统是否允许弹通知（Android 13+ 需要运行时授权）。 */
    public static boolean canPostNotifications(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled());
    }
}
