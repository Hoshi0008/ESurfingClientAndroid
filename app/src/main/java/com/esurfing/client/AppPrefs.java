package com.esurfing.client;

import android.content.Context;
import android.content.SharedPreferences;

import com.esurfing.client.core.Config;

import java.io.File;

/**
 * 应用级配置（主题、开机自启）。
 *
 * <p>这两项和「网络配置」是两回事：它们不写进 {@code esurfing.conf}，而是放在
 * SharedPreferences 里，改完<b>立即生效</b>，所以设置页里也不会提示「重启服务后生效」。</p>
 */
public final class AppPrefs {

    private static final String NAME = "app_prefs";
    private static final String KEY_THEME = "theme";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_MIGRATED = "migrated_from_conf";
    private static final String KEY_STABLE_MAC = "stable_mac";

    public static final String THEME_AUTO = "auto";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private AppPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String theme(Context c) {
        String v = sp(c).getString(KEY_THEME, THEME_AUTO);
        return v == null || v.isEmpty() ? THEME_AUTO : v;
    }

    public static void setTheme(Context c, String value) {
        sp(c).edit().putString(KEY_THEME,
                value == null || value.isEmpty() ? THEME_AUTO : value).apply();
    }

    public static boolean autoStart(Context c) {
        // 默认关闭：打开 App 不会自己把服务拉起来，只有用户明确打开「开机自启」才自启
        return sp(c).getBoolean(KEY_AUTO_START, false);
    }

    public static void setAutoStart(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_AUTO_START, value).apply();
    }

    /**
     * 读不到本机 MAC 时用的固定随机 MAC：只生成一次，之后每次上报都用它，
     * 避免每个会话换一个 MAC 导致服务端会话错乱。
     */
    public static String stableMac(Context c) {
        SharedPreferences p = sp(c);
        String mac = p.getString(KEY_STABLE_MAC, "");
        if (mac == null || !com.esurfing.client.core.IpUtil.isMac(mac)) {
            mac = com.esurfing.client.core.IpUtil.randomMac();
            p.edit().putString(KEY_STABLE_MAC, mac).apply();
        }
        return mac;
    }

    /**
     * 老版本把 theme / auto_start 写在 esurfing.conf 里，升级后一次性迁过来，
     * 并把旧键从配置文件里删掉，避免两份配置打架。
     */
    public static void migrateIfNeeded(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean(KEY_MIGRATED, false)) {
            return;
        }
        File file = App.get() != null ? App.get().configFile()
                : new File(c.getFilesDir(), "esurfing.conf");
        Config legacy = Config.load(file);
        String theme = legacy.get("theme").trim();
        if (!theme.isEmpty()) {
            setTheme(c, theme);
        }
        String autoStart = legacy.get("auto_start").trim();
        if (!autoStart.isEmpty()) {
            setAutoStart(c, !("false".equalsIgnoreCase(autoStart) || "0".equals(autoStart)));
        }
        boolean touched = legacy.remove("theme");
        touched |= legacy.remove("auto_start");
        if (touched) {
            legacy.save();
        }
        p.edit().putBoolean(KEY_MIGRATED, true).apply();
    }
}
