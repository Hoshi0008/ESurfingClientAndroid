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
 * 读取当前网络的本机 IPv4、默认网关，以及「当前走的是不是 WLAN」。
 *
 * <p>校园网认证只应发生在 WLAN 上：{@link #wifiNetwork} 非空时，认证与
 * 外网探测都会把这个 socket 绑到该网络（{@code Network.bindSocket}），
 * 从而保证即使移动数据是默认网络，流量也不会跑到蜂窝上。</p>
 */
public final class NetInfo {

    /** 是否存在一条可用的 WLAN 连接。 */
    public final boolean wifi;
    /** 用于绑定 socket 的 WLAN 网络；没有 WLAN 时为 null。 */
    public final Network wifiNetwork;
    /** 本机 IPv4（优先取 WLAN 的地址）。 */
    public final String ipv4;
    public final String gateway;

    private NetInfo(boolean wifi, Network wifiNetwork, String ipv4, String gateway) {
        this.wifi = wifi;
        this.wifiNetwork = wifiNetwork;
        this.ipv4 = ipv4;
        this.gateway = gateway;
    }

    public static NetInfo read(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return new NetInfo(false, null, null, null);
        }
        Network wifiNet = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            wifiNet = n;
            break;
        }
        Network target = wifiNet != null ? wifiNet : cm.getActiveNetwork();
        if (target == null) {
            return new NetInfo(false, null, null, null);
        }
        LinkProperties lp = cm.getLinkProperties(target);
        if (lp == null) {
            return new NetInfo(wifiNet != null, wifiNet, null, null);
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
        return new NetInfo(wifiNet != null, wifiNet, ip, gw);
    }
}
