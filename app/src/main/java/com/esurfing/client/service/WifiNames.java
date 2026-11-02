package com.esurfing.client.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/**
 * 读当前连接的 Wi-Fi 名称（SSID）。
 *
 * <p>优先用 Android 13+ 的 {@link NetworkCapabilities#getSsid()}（需要
 * {@code NEARBY_WIFI_DEVICES}，声明 neverForLocation 即可，不必再要定位权限）；
 * 读不到时退回老的 {@link WifiInfo#getSSID()}。都读不到就返回 null，
 * 界面退化成「WiFi 已连接」。</p>
 */
public final class WifiNames {

    /** 读 SSID 需要的运行时权限（Android 13+）。 */
    public static final String PERMISSION =
            Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : null;

    private WifiNames() {
    }

    public static boolean granted(Context ctx) {
        return PERMISSION == null
                || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return 当前 SSID；读不到（未连接 / 未授权）返回 null */
    public static String currentSsid(Context ctx) {
        if (!granted(ctx)) {
            return null;
        }
        String fromCaps = ssidFromCapabilities(ctx);
        if (fromCaps != null) {
            return fromCaps;
        }
        return ssidFromWifiInfo(ctx);
    }

    /** Android 13+ 推荐姿势：从当前 WLAN 的 NetworkCapabilities 拿 WifiInfo。 */
    private static String ssidFromCapabilities(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) {
            return null;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    continue;
                }
                TransportInfo transport = caps.getTransportInfo();
                if (!(transport instanceof WifiInfo)) {
                    continue;
                }
                String ssid = clean(((WifiInfo) transport).getSSID());
                if (ssid != null) {
                    return ssid;
                }
            }
        } catch (RuntimeException ignored) {
            // 读不到就退回老接口
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String ssidFromWifiInfo(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return null;
            }
            return clean(info.getSSID());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String ssid = raw.trim();
        if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        ssid = ssid.trim();
        if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid) || "0x".equals(ssid)) {
            return null;
        }
        return ssid;
    }

    // ------------------------------------------------------------------ 本机 MAC

    /**
     * 尽力读本机 WLAN 的 MAC 地址。
     *
     * <p>Android 6 以后系统普遍把 MAC 抹成 {@code 02:00:00:00:00:00}，所以多渠道尝试：
     * WifiInfo → wlan0 网卡硬件地址。都拿不到就返回 null，由调用方退化成固定随机 MAC。</p>
     */
    @SuppressWarnings("deprecation")
    public static String localMac(Context ctx) {
        String viaWifi = macFromWifiInfo(ctx);
        if (viaWifi != null) {
            return viaWifi;
        }
        return macFromInterface("wlan0");
    }

    private static String macFromWifiInfo(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return null;
            }
            return validMac(info.getMacAddress());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String macFromInterface(String name) {
        try {
            java.net.NetworkInterface nif = java.net.NetworkInterface.getByName(name);
            if (nif == null) {
                return null;
            }
            byte[] addr = nif.getHardwareAddress();
            if (addr == null || addr.length != 6) {
                return null;
            }
            StringBuilder sb = new StringBuilder(17);
            for (int i = 0; i < addr.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format(java.util.Locale.US, "%02X", addr[i] & 0xFF));
            }
            return validMac(sb.toString());
        } catch (java.net.SocketException | RuntimeException e) {
            return null;
        }
    }

    /** 过滤掉系统占位值（全 0 / 被系统抹掉的 02:00:00:00:00:00）。 */
    private static String validMac(String mac) {
        if (mac == null) {
            return null;
        }
        String m = mac.trim().toUpperCase(java.util.Locale.US).replace('-', ':');
        if (!com.esurfing.client.core.IpUtil.isMac(m)) {
            return null;
        }
        if ("00:00:00:00:00:00".equals(m) || "02:00:00:00:00:00".equals(m)) {
            return null;
        }
        return m;
    }
}
