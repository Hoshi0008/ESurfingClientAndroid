package com.esurfing.client.core;

/**
 * 服务端下发的 ZSM（算法模块）解析。
 *
 * <p>结构：{@code "001" | u8 len1 | str1 | u8 len2 | str2(=Algo-ID UUID) | <加密的模块>}。
 * 我们只关心其中的 Algo-ID，据此从内置表里取 key/iv；模块本身不需要执行。</p>
 */
final class Zsm {

    private Zsm() {
    }

    /**
     * @param algoId 服务端本轮下发的 Algo-ID（大写）；解析失败返回 null
     */
    static String parseAlgoId(byte[] data) {
        String[] r = parse(data);
        return r == null ? null : r[1];
    }

    /**
     * @return {@code {str1, algoId}}，解析失败返回 null
     */
    static String[] parse(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        if (data[0] != '0' || data[1] != '0' || data[2] != '1') {
            return null;
        }
        int off = 3;
        String[] strs = new String[3];
        int count = 0;
        for (int i = 0; i < 3 && off < data.length; i++) {
            int len = data[off] & 0xFF;
            off++;
            if (off + len > data.length) {
                break;
            }
            strs[count++] = new String(data, off, len, java.nio.charset.StandardCharsets.ISO_8859_1);
            off += len;
        }
        if (count == 0) {
            return null;
        }
        for (int i = count - 1; i >= 0; i--) {
            if (isUuid(strs[i])) {
                return new String[]{strs[0], strs[i].toUpperCase(java.util.Locale.US)};
            }
        }
        return null;
    }

    private static boolean isUuid(String s) {
        if (s == null || s.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (Character.digit(c, 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
