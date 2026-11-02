package com.esurfing.client.core;

import java.io.IOException;
import java.net.Socket;

/**
 * 把 socket 固定到某张网卡上（Android 侧用 {@code Network.bindSocket} 实现）。
 *
 * <p>这样即使移动数据是默认网络，外网探测与认证请求也只会走 WLAN。</p>
 */
public interface SocketBinder {

    void bind(Socket socket) throws IOException;
}
