package com.esurfing.client.core;

import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/** IPv4 / CIDR / MAC 的轻量工具，避免为这点事引入依赖。 */
public final class IpUtil {

    private static final Random RANDOM = new Random();

    private IpUtil() {
    }

    public static boolean isIpv4(String s) {
        return toLong(s) >= 0;
    }

    /** 合法 IPv4 返回其 32 位无符号值，否则 -1。 */
    public static long toLong(String s) {
        if (s == null) {
            return -1;
        }
        String text = s.trim();
        if (text.isEmpty()) {
            return -1;
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return -1;
            }
            int n;
            try {
                n = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (n < 0 || n > 255) {
                return -1;
            }
            value = (value << 8) | n;
        }
        return value;
    }

    /** 接受 {@code a.b.c.d/n} 或单个 {@code a.b.c.d}（视为 /32）。 */
    public static boolean isCidr(String s) {
        return parseCidr(s) != null;
    }

    private static long[] parseCidr(String s) {
        if (s == null) {
            return null;
        }
        String text = s.trim();
        if (text.isEmpty()) {
            return null;
        }
        int slash = text.indexOf('/');
        String ip = slash < 0 ? text : text.substring(0, slash);
        int prefix = 32;
        if (slash >= 0) {
            String p = text.substring(slash + 1).trim();
            try {
                prefix = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefix < 0 || prefix > 32) {
                return null;
            }
        }
        long addr = toLong(ip);
        if (addr < 0) {
            return null;
        }
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return new long[]{(addr & mask) & 0xFFFFFFFFL, mask};
    }

    public static boolean cidrContains(String cidr, String ip) {
        long[] net = parseCidr(cidr);
        long addr = toLong(ip);
        if (net == null || addr < 0) {
            return false;
        }
        return (addr & net[1]) == net[0];
    }

    /** 逗号分隔的多个网段，命中任意一个即返回 true。 */
    public static boolean anyCidrContains(String cidrs, String ip) {
        if (cidrs == null || cidrs.trim().isEmpty()) {
            return false;
        }
        for (String part : cidrs.split(",")) {
            String c = part.trim();
            if (!c.isEmpty() && cidrContains(c, ip)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMac(String s) {
        if (s == null) {
            return false;
        }
        return s.trim().matches("(?i)^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$");
    }

    /** 生成本地管理位为 1 的随机 MAC（服务端不校验真伪）。 */
    public static String randomMac() {
        StringBuilder sb = new StringBuilder(17);
        sb.append("02");
        for (int i = 0; i < 5; i++) {
            sb.append(':').append(String.format(Locale.US, "%02X", RANDOM.nextInt(256)));
        }
        return sb.toString();
    }

    /** 10 位小写 hex 主机名，与官方客户端一致。 */
    public static String randomHostName() {
        return String.format(Locale.US, "%010x", RANDOM.nextLong() & 0xFFFFFFFFFFL);
    }

    /** 小写 UUID。 */
    public static String randomUuid() {
        return UUID.randomUUID().toString();
    }
}
