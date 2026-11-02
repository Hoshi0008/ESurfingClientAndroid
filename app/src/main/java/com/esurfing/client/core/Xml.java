package com.esurfing.client.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 报文 XML 的构造与解析。
 *
 * <p>字段顺序、缩进、结尾换行都跟官方客户端保持完全一致：
 * 服务端虽然不严格校验，但对齐参考实现可以少踩坑。</p>
 */
final class Xml {

    private Xml() {
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    static String ticket(String ua, String clientId, String hostName, String ipv4,
                         String mac, String ostag, String gwip) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<request>\n"
                + "    <user-agent>" + ua + "</user-agent>\n"
                + "    <client-id>" + clientId + "</client-id>\n"
                + "    <local-time>" + now() + "</local-time>\n"
                + "    <host-name>" + hostName + "</host-name>\n"
                + "    <ipv4>" + ipv4 + "</ipv4>\n"
                + "    <ipv6></ipv6>\n"
                + "    <mac>" + mac + "</mac>\n"
                + "    <ostag>" + ostag + "</ostag>\n"
                + "    <gwip>" + gwip + "</gwip>\n"
                + "</request>\n";
    }

    static String login(String ua, String clientId, String ticket, String userid, String passwd) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<request>\n"
                + "    <user-agent>" + ua + "</user-agent>\n"
                + "    <client-id>" + clientId + "</client-id>\n"
                + "    <ticket>" + ticket + "</ticket>\n"
                + "    <local-time>" + now() + "</local-time>\n"
                + "    <userid>" + userid + "</userid>\n"
                + "    <passwd>" + passwd + "</passwd>\n"
                + "</request>\n";
    }

    static String keep(String ua, String clientId, String hostName, String ipv4,
                       String ticket, String mac, String ostag) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<request>\n"
                + "    <user-agent>" + ua + "</user-agent>\n"
                + "    <client-id>" + clientId + "</client-id>\n"
                + "    <local-time>" + now() + "</local-time>\n"
                + "    <host-name>" + hostName + "</host-name>\n"
                + "    <ipv4>" + ipv4 + "</ipv4>\n"
                + "    <ticket>" + ticket + "</ticket>\n"
                + "    <ipv6></ipv6>\n"
                + "    <mac>" + mac + "</mac>\n"
                + "    <ostag>" + ostag + "</ostag>\n"
                + "</request>\n";
    }

    /** 取 {@code <name>value</name>} 的 value，找不到返回 ""。 */
    static String tag(String xml, String name) {
        if (xml == null) {
            return "";
        }
        String open = "<" + name + ">";
        String close = "</" + name + ">";
        int a = xml.indexOf(open);
        if (a < 0) {
            return "";
        }
        int from = a + open.length();
        int b = xml.indexOf(close, from);
        if (b < 0) {
            return "";
        }
        return xml.substring(from, b).trim();
    }

    /** 取 {@code <name><![CDATA[value]]></name>} 的 value。 */
    static String cdata(String xml, String name) {
        if (xml == null) {
            return "";
        }
        String open = "<" + name + ">";
        int a = xml.indexOf(open);
        if (a < 0) {
            return "";
        }
        int from = a + open.length();
        int start = xml.indexOf("<![CDATA[", from);
        if (start < 0) {
            return tag(xml, name);
        }
        int end = xml.indexOf("]]>", start);
        if (end < 0) {
            return "";
        }
        return xml.substring(start + "<![CDATA[".length(), end).trim();
    }
}
