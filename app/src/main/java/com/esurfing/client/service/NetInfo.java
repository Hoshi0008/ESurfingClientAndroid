package com.esurfing.client.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * 读取当前网络的本机 IPv4 与默认网关。
 *
 * <p>优先取 WiFi 网络（校园网就是这张网），取不到再退回系统默认网络。
 * MAC 地址在 Android 上拿不到真实值，由 {@code IpUtil.randomMac()} 生成，
 * 协议本身也不校验它。</p>
 */
public final class NetInfo {

    public final boolean wifi;
    public final String ipv4;
    public final String gateway;

    private NetInfo(boolean wifi, String ipv4, String gateway) {
        this.wifi = wifi;
        this.ipv4 = ipv4;
        this.gateway = gateway;
    }

    public static NetInfo read(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return new NetInfo(false, null, null);
        }
        Network target = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
                continue;
            }
            target = n;
            break;
        }
        boolean wifi = target != null;
        if (target == null) {
            target = cm.getActiveNetwork();
        }
        if (target == null) {
            return new NetInfo(false, null, null);
        }
        LinkProperties lp = cm.getLinkProperties(target);
        if (lp == null) {
            return new NetInfo(wifi, null, null);
        }
        String ip = null;
        String gw = null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address) {
                ip = a.getHostAddress();
                break;
            }
        }
        for (RouteInfo r : lp.getRoutes()) {
            InetAddress g = r.getGateway();
            if (r.isDefaultRoute() && g instanceof Inet4Address) {
                gw = g.getHostAddress();
                break;
            }
        }
        return new NetInfo(wifi, ip, gw);
    }
}
